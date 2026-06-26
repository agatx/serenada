import type { SerenadaCore } from './SerenadaCore.js';
import type { SerenadaSession } from './SerenadaSession.js';
import {
    ForegroundLeaseUnavailable,
    type CallActivationError,
    type CallId,
    type CallMediaRole,
    type CallPhase,
    type CallRegistryState,
    type CallState,
    type ForegroundOwnerToken,
    type JoinAndSwitchResult,
    type JoinResult,
    type ManagedCallState,
    type MediaActivationState,
    type RoomRef,
    type SerenadaLogger,
    type SwitchResult,
} from './types.js';
import { foregroundArbiter, type ForegroundMediaArbiter } from './foregroundArbiter.js';
import { canonicalizeRoomId } from './roomIdentity.js';
import {
    FOREGROUND_ACTIVATE_TIMEOUT_MS,
    FOREGROUND_RELEASE_TIMEOUT_MS,
    HELD_JOIN_TIMEOUT_MS,
} from './constants.js';
import { formatError } from './formatError.js';

/**
 * A {@link RoomRef}, but accepting an optional host display name carried onto the
 * managed call state. Distinct from the public `RoomRef` so the simple shape
 * stays clean for hosts that don't pass a name.
 */
type RoomInput = RoomRef & { displayName?: string };

/**
 * Test/host seam for how the registry creates a held session. Production wires
 * this to `core.joinInternal(room, { initialMediaRole: 'held' })`; tests inject
 * fake-backed sessions. The registry owns the arbiter lease, so the created
 * session MUST NOT self-acquire it (`acquireForegroundLease: false`).
 */
export type RegistrySessionFactory = (
    room: RoomRef,
    options: { initialMediaRole: CallMediaRole; displayName?: string },
) => SerenadaSession;

export interface SerenadaCallRegistryOptions {
    /**
     * Override the session factory (tests inject fake-backed sessions). When
     * omitted, sessions are created via {@link SerenadaCore.joinInternal}.
     */
    createSession?: RegistrySessionFactory;
    /**
     * Override the process-wide arbiter (tests can pass a dedicated instance for
     * isolation). When omitted, the module singleton is used; production always
     * uses the singleton.
     */
    arbiter?: ForegroundMediaArbiter;
    /**
     * How long an `ended` managed call is retained before it is auto-removed from
     * the published state. Hosts can also dismiss explicitly via
     * {@link SerenadaCallRegistry.dismiss}. Defaults to 0 (remove immediately on
     * end); set a positive value to keep an ended call's quality summary around.
     */
    endedCallRetentionMs?: number;
    /** Optional logger for registry diagnostics. */
    logger?: SerenadaLogger;
}

/** Internal mutable record for one managed call (contract §4). */
interface ManagedCall {
    readonly id: CallId;
    readonly roomId: string;
    readonly roomUrl: string | null;
    readonly session: SerenadaSession;
    /** The foreground lease token while this call is foreground, else `null`. */
    foregroundToken: ForegroundOwnerToken | null;
    /** Per-call activation/release/join error or needed permission. */
    activationError: CallActivationError | null;
    /** Set once the call's room join has terminally failed. */
    joinFailed: boolean;
    /**
     * Set once the registry has reconciled this call's own terminal session state
     * (remote-end / fatal error / leave / end): the lease is released, the call
     * is excluded from "live" counts, and it is dismissable. Distinct from the
     * retention timer (which only controls when an already-terminal call is
     * removed from the published list). The terminal-state handler guards on this
     * so it is idempotent and queue-safe — a registry-initiated leave/end that
     * already reconciled leaves a later session-driven terminal callback a no-op.
     */
    terminated: boolean;
    displayName: string | null;
    /** Removes the session's state subscription on teardown. */
    unsubscribe: () => void;
    /** Pending ended-call retention timer, if any. */
    retentionTimer: ReturnType<typeof setTimeout> | null;
}

/**
 * Multi-call session registry (Phase 3, contract §7 / design "Add
 * SerenadaCallRegistry"). Owns the single process-wide foreground lease for a
 * set of managed calls and serializes every operation through one promise-chained
 * queue so foreground-lease and call-map mutations can never interleave.
 *
 * The registry does NOT hide the underlying {@link SerenadaSession}: a host
 * renders the active call with `<SerenadaCallFlow session={registry.activeCall?.session} />`
 * and keeps using the session's own audio/video toggles. The registry only owns
 * which call holds foreground media and the lease.
 *
 * Subscribe like {@link CallState} (useSyncExternalStore-compatible: read
 * {@link state}, subscribe with a change callback). Single-call `SerenadaCore`
 * APIs are unchanged and continue to work alongside (mode arbitration keeps the
 * two from mixing: while a registry holds any live call, a direct
 * `SerenadaCore.join()` fails {@link ForegroundLeaseUnavailable}, and vice versa).
 */
export class SerenadaCallRegistry {
    private readonly createSession: RegistrySessionFactory;
    private readonly arbiter: ForegroundMediaArbiter;
    private readonly endedCallRetentionMs: number;
    private readonly logger: SerenadaLogger | undefined;

    /** Insertion-ordered managed calls keyed by stable CallId. */
    private readonly calls = new Map<CallId, ManagedCall>();
    private activeCallId: CallId | null = null;
    private registryOperationInProgress = false;
    private lastError: CallActivationError | null = null;

    /** Promise chain tail: every queued section runs after this resolves. */
    private opTail: Promise<unknown> = Promise.resolve();

    private listeners = new Set<(state: CallRegistryState) => void>();
    private _state: CallRegistryState = {
        calls: [],
        activeCallId: null,
        registryOperationInProgress: false,
        lastError: null,
    };

    /**
     * Distinct mode-claim ref. The registry claims `'registry'` owning mode with
     * this object while it holds any non-ended call (contract §2 rule 4), so a
     * direct `SerenadaCore.join()` fails fast. Mode clears when the last call ends.
     */
    private readonly modeRef = { registry: true };
    private modeClaimed = false;

    constructor(core: SerenadaCore, options: SerenadaCallRegistryOptions = {}) {
        this.createSession = options.createSession ?? ((room, opts) =>
            core.joinInternal(room, {
                initialMediaRole: opts.initialMediaRole,
                displayName: opts.displayName,
            }));
        this.arbiter = options.arbiter ?? foregroundArbiter;
        this.endedCallRetentionMs = options.endedCallRetentionMs ?? 0;
        this.logger = options.logger;
    }

    private log(level: 'warning' | 'error', message: string): void {
        this.logger?.log(level, 'CallRegistry', message);
    }

    // --- Observable state (useSyncExternalStore-compatible) ---

    /** Current aggregate registry state. Subscribe via {@link subscribe}. */
    get state(): CallRegistryState {
        return this._state;
    }

    /**
     * The foreground managed call (with its live {@link SerenadaSession}), or
     * `null` when none is foregrounded. A host renders
     * `<SerenadaCallFlow session={registry.activeCall?.session} />`.
     */
    get activeCall(): { state: ManagedCallState; session: SerenadaSession } | null {
        if (this.activeCallId === null) return null;
        const call = this.calls.get(this.activeCallId);
        if (!call) return null;
        return { state: this.toManagedCallState(call), session: call.session };
    }

    /** The live {@link SerenadaSession} for a managed call, or `null`. */
    sessionFor(callId: CallId): SerenadaSession | null {
        return this.calls.get(callId)?.session ?? null;
    }

    subscribe(callback: (state: CallRegistryState) => void): () => void {
        this.listeners.add(callback);
        return () => {
            this.listeners.delete(callback);
        };
    }

    // --- Public API (all async; all serialized) ---

    /**
     * Join a room in the background (held: stable senders, no capture, no lease).
     * Section A (queued) creates+registers the call (mode guard, dedup); the held
     * room join runs OUTSIDE the queue bounded by `HELD_JOIN_TIMEOUT_MS`.
     * Idempotent by canonical roomId: a second live join returns the existing id.
     */
    async joinHeld(room: RoomInput): Promise<JoinResult> {
        const created = await this.enqueue(() => this.createOrReuseCall(room));
        if (created.kind === 'reused') {
            return { kind: 'joined', callId: created.call.id };
        }
        if (created.kind === 'rejected') {
            return { kind: 'failed', error: created.error };
        }
        const call = created.call;
        const joinError = await this.awaitHeldJoin(call);
        if (joinError) {
            return { kind: 'failed', callId: call.id, error: joinError };
        }
        return { kind: 'joined', callId: call.id };
    }

    /**
     * Join a room and switch to it (composite: join held, then run the switch
     * body). Three parts (contract §7): (A) queued create+register, (B) the held
     * join outside the queue, (C) a queued switch. `needsPermission` carries the
     * callId so the host can prompt then retry `switchTo(callId)`.
     */
    async joinAndSwitch(room: RoomInput): Promise<JoinAndSwitchResult> {
        const created = await this.enqueue(() => this.createOrReuseCall(room));
        if (created.kind === 'rejected') {
            return { kind: 'failed', error: created.error };
        }
        const call = created.call;
        // For a reused live call, skip the join wait and go straight to the switch.
        if (created.kind === 'created') {
            const joinError = await this.awaitHeldJoin(call);
            if (joinError) {
                return { kind: 'failed', callId: call.id, error: joinError };
            }
        }
        // Section C: run the switch body inside the queue. It re-reads activeCallId.
        const switchResult = await this.enqueue(() => this.runSwitch(call.id));
        if (switchResult.kind === 'active') {
            return { kind: 'active', callId: call.id };
        }
        if (switchResult.kind === 'needsPermission') {
            return { kind: 'needsPermission', callId: call.id };
        }
        return { kind: 'failed', callId: call.id, error: switchResult.error };
    }

    /**
     * Foreground a held call, holding the current active call first.
     *
     * FIX D: every switch is enqueued (no outside-queue fast path). Per contract
     * §7 the preflight AND the `next == activeCallId` no-op must run INSIDE the
     * queued operation, where `runSwitch` re-reads `activeCallId` after the queue
     * has drained. A fast-path read of `this.activeCallId` here would race a
     * concurrent switch/leave/hold still in flight: it could see a stale active
     * id (returning `active` for a call about to lose foreground, or skipping the
     * enqueue for a call that is no longer active by the time it would run).
     */
    async switchTo(callId: CallId): Promise<SwitchResult> {
        return this.enqueue(() => this.runSwitch(callId));
    }

    /** Hold a call: drain its foreground resources and release the lease (no auto-promote). */
    async hold(callId: CallId): Promise<void> {
        await this.enqueue(() => this.runHold(callId));
    }

    /** Leave a call (release foreground first if active), then tear it down. */
    async leave(callId: CallId): Promise<void> {
        await this.enqueue(() => this.runLeaveOrEnd(callId, 'leave'));
    }

    /** End a call for all participants (release foreground first if active). */
    async end(callId: CallId): Promise<void> {
        await this.enqueue(() => this.runLeaveOrEnd(callId, 'end'));
    }

    /** Explicitly remove an ended managed call from the published state. */
    dismiss(callId: CallId): void {
        const call = this.calls.get(callId);
        if (!call) return;
        if (!this.isEnded(call)) return;
        this.removeCall(call);
        this.publish();
    }

    // --- Operation queue ---

    /**
     * Run `fn` as the next queued operation. The queue serializes foreground-lease
     * + call-map mutations only; slow network I/O (the held room join) runs OUTSIDE
     * a queued section. `registryOperationInProgress` is true while a section runs.
     * A thrown error inside `fn` is propagated to the caller AND does not poison the
     * chain (the tail resolves regardless).
     */
    private enqueue<T>(fn: () => Promise<T> | T): Promise<T> {
        const run = this.opTail.then(async () => {
            this.registryOperationInProgress = true;
            this.publish();
            try {
                return await fn();
            } finally {
                this.registryOperationInProgress = false;
                this.publish();
            }
        });
        // Keep the chain alive even if this op rejects (swallow on the tail only;
        // the caller still sees the rejection via `run`).
        this.opTail = run.then(() => undefined, () => undefined);
        return run;
    }

    // --- Section A: create or reuse a managed call ---

    private createOrReuseCall(
        room: RoomInput,
    ):
        | { kind: 'reused'; call: ManagedCall }
        | { kind: 'created'; call: ManagedCall }
        | { kind: 'rejected'; error: CallActivationError } {
        const roomId = this.canonicalRoomId(room);
        // Call-identity policy (contract §7): one live call per canonical roomId.
        const existing = this.findLiveCallByRoomId(roomId);
        if (existing) {
            return { kind: 'reused', call: existing };
        }
        // Mode guard: claiming `'registry'` mode fails if a direct join owns the
        // process. Claim BEFORE creating the session so a rejection has no side
        // effect.
        try {
            this.arbiter.claimMode('registry', this.modeRef);
            this.modeClaimed = true;
        } catch (err) {
            return { kind: 'rejected', error: this.toActivationError('joinFailed', err) };
        }

        let session: SerenadaSession;
        try {
            session = this.createSession(this.toRoomRef(room), {
                initialMediaRole: 'held',
                displayName: room.displayName,
            });
        } catch (err) {
            this.releaseModeIfIdle();
            return { kind: 'rejected', error: this.toActivationError('joinFailed', err) };
        }

        const id = generateCallId();
        const call: ManagedCall = {
            id,
            roomId,
            roomUrl: this.roomUrlOf(room),
            session,
            foregroundToken: null,
            activationError: null,
            joinFailed: false,
            terminated: false,
            displayName: room.displayName ?? null,
            unsubscribe: () => {},
            retentionTimer: null,
        };
        // Forward session state changes into registry state.
        call.unsubscribe = session.subscribe(() => this.onSessionStateChange(call));
        this.calls.set(id, call);
        this.publish();
        return { kind: 'created', call };
    }

    // --- Section B: bounded held room join (OUTSIDE the queue) ---

    /**
     * Wait for the held room join to establish membership, bounded by
     * `HELD_JOIN_TIMEOUT_MS`. Resolves to `null` on success, or a `joinFailed`
     * error on failure/timeout. Runs outside the queue so a multi-second join
     * does not block urgent operations.
     */
    private async awaitHeldJoin(call: ManagedCall): Promise<CallActivationError | null> {
        const session = call.session;
        // Already established / failed (synchronous fakes settle immediately).
        const settled = this.joinSettlement(session.state.phase);
        if (settled === 'joined') return null;
        if (settled === 'failed') return this.markJoinFailed(call, 'Room join failed');

        const error = await new Promise<CallActivationError | null>((resolve) => {
            let done = false;
            const finish = (result: CallActivationError | null) => {
                if (done) return;
                done = true;
                clearTimeout(timer);
                unsubscribe();
                resolve(result);
            };
            const timer = setTimeout(() => {
                finish(this.makeError('joinFailed', `Room join timed out after ${HELD_JOIN_TIMEOUT_MS}ms`));
            }, HELD_JOIN_TIMEOUT_MS);
            const unsubscribe = session.subscribe((state) => {
                const phase = this.joinSettlement(state.phase);
                if (phase === 'joined') finish(null);
                else if (phase === 'failed') finish(this.makeError('joinFailed', state.error?.message ?? 'Room join failed'));
            });
        });

        if (error) {
            this.applyCallError(call, error);
            call.joinFailed = true;
            // FIX F: a failed held join makes this call non-live. If it was the
            // only call, the registry must release its `'registry'` owning-mode
            // claim — otherwise a single failed join wedges the process in
            // registry mode forever and every later direct `SerenadaCore.join()`
            // fails with ForegroundLeaseUnavailable.
            this.releaseModeIfIdle();
            this.publish();
        }
        return error;
    }

    private joinSettlement(phase: CallPhase): 'joined' | 'failed' | 'pending' {
        if (phase === 'waiting' || phase === 'inCall') return 'joined';
        if (phase === 'error') return 'failed';
        return 'pending';
    }

    private markJoinFailed(call: ManagedCall, message: string): CallActivationError {
        const error = this.makeError('joinFailed', call.session.state.error?.message ?? message);
        this.applyCallError(call, error);
        call.joinFailed = true;
        // FIX F: see awaitHeldJoin — release the owning mode if this was the last
        // live call so a failed join does not wedge the process in registry mode.
        this.releaseModeIfIdle();
        this.publish();
        return error;
    }

    // --- Section C: the switch body (contract §7 pseudocode, EXACT) ---

    private async runSwitch(nextId: CallId): Promise<SwitchResult> {
        const next = this.calls.get(nextId);
        if (!next) {
            return { kind: 'failed', error: this.setLastError(this.makeError('activationFailed', `No managed call ${nextId}`)) };
        }
        // Section C re-reads activeCallId (the world may have changed since A/B).
        if (this.activeCallId === nextId) {
            return { kind: 'active' };
        }
        const old = this.activeCallId !== null ? this.calls.get(this.activeCallId) ?? null : null;

        const gen = this.arbiter.nextOperationGeneration();

        // 0. PREFLIGHT before touching old (Core Invariant 4).
        let permission: 'ok' | 'needsPermission' | 'failed';
        try {
            permission = await next.session.preflightForeground();
        } catch (err) {
            const error = this.setLastError(this.toActivationError('activationFailed', err));
            this.applyCallError(next, error);
            this.publish();
            return { kind: 'failed', error };
        }
        if (permission === 'needsPermission') {
            next.activationError = this.makeError('needsPermission', 'Foreground requires a device permission grant');
            this.publish();
            return { kind: 'needsPermission' };   // old foreground call untouched
        }
        if (permission === 'failed') {
            const error = this.setLastError(this.makeError('activationFailed', 'Preflight failed (no audio route)'));
            this.applyCallError(next, error);
            this.publish();
            return { kind: 'failed', error };
        }

        // 1. drain old with ITS token, bounded by the release timeout.
        if (old && old.foregroundToken) {
            const oldToken = old.foregroundToken;
            // Mark a release pending so the arbiter refuses any acquire until this
            // confirms (Core Invariant 1 / contract §2 rule 2).
            this.arbiter.markReleasePending();
            const released = await withTimeout(
                old.session.releaseForeground(oldToken),
                FOREGROUND_RELEASE_TIMEOUT_MS,
            );
            if (!released) {
                // Timed out: old keeps the lease, mark failed, abort the switch.
                old.activationError = this.makeError('releaseFailed', `Releasing the active call timed out after ${FOREGROUND_RELEASE_TIMEOUT_MS}ms`);
                const error = this.setLastError(old.activationError);
                this.publish();
                return { kind: 'failed', error };
            }
            this.arbiter.releaseLease(oldToken);
            old.foregroundToken = null;
            this.activeCallId = null;
            old.activationError = null;
            this.publish();
        }

        // 2. acquire a fresh token and activate next, bounded.
        try {
            const newToken = this.arbiter.acquireForeground(next.id, 'registry', this.modeRef);
            next.foregroundToken = newToken;
            const activated = await withTimeout(
                next.session.activateForeground(newToken, gen),
                FOREGROUND_ACTIVATE_TIMEOUT_MS,
            );
            if (!activated) {
                throw new Error(`Foreground activation timed out after ${FOREGROUND_ACTIVATE_TIMEOUT_MS}ms`);
            }
            this.activeCallId = next.id;
            next.activationError = null;
            this.lastError = null;
            this.publish();
            return { kind: 'active' };
        } catch (err) {
            const activationError = this.toActivationError('activationFailed', err);
            // Clean up the partially-activated target before touching the lease.
            await this.safeAbort(next);
            if (next.foregroundToken) {
                this.arbiter.releaseLease(next.foregroundToken);
                next.foregroundToken = null;
            }
            this.applyCallError(next, activationError);
            // 3. roll back to old by default, under a FRESH generation.
            if (old) {
                const rollbackError = await this.rollbackToOld(old);
                if (!rollbackError) {
                    this.setLastError(activationError);   // recoverable: surfaced on next
                    this.publish();
                    return { kind: 'failed', error: activationError };
                }
                // Rollback also failed: no foreground owner; surface both.
                this.activeCallId = null;
                old.activationError = rollbackError;
                this.setLastError(activationError);
                this.publish();
                return { kind: 'failed', error: activationError };
            }
            this.activeCallId = null;
            this.setLastError(activationError);
            this.publish();
            return { kind: 'failed', error: activationError };
        }
    }

    /** Re-activate `old` as foreground under a fresh generation. Returns an error on failure. */
    private async rollbackToOld(old: ManagedCall): Promise<CallActivationError | null> {
        const rollbackGen = this.arbiter.nextOperationGeneration();
        try {
            const token = this.arbiter.acquireForeground(old.id, 'registry', this.modeRef);
            old.foregroundToken = token;
            const ok = await withTimeout(
                old.session.activateForeground(token, rollbackGen),
                FOREGROUND_ACTIVATE_TIMEOUT_MS,
            );
            if (!ok) {
                throw new Error('Rollback activation timed out');
            }
            this.activeCallId = old.id;
            old.activationError = null;
            return null;
        } catch (err) {
            if (old.foregroundToken) {
                await this.safeAbort(old);
                this.arbiter.releaseLease(old.foregroundToken);
                old.foregroundToken = null;
            }
            return this.toActivationError('activationFailed', err);
        }
    }

    // --- hold / leave / end ---

    private async runHold(callId: CallId): Promise<void> {
        const call = this.calls.get(callId);
        if (!call) return;
        if (call.id !== this.activeCallId || !call.foregroundToken) {
            return;   // already held / not the active call -> no-op (no auto-promote)
        }
        const token = call.foregroundToken;
        this.arbiter.markReleasePending();
        const released = await withTimeout(call.session.releaseForeground(token), FOREGROUND_RELEASE_TIMEOUT_MS);
        if (!released) {
            call.activationError = this.makeError('releaseFailed', `Hold timed out after ${FOREGROUND_RELEASE_TIMEOUT_MS}ms`);
            this.setLastError(call.activationError);
            this.publish();
            return;
        }
        this.arbiter.releaseLease(token);
        call.foregroundToken = null;
        this.activeCallId = null;   // no auto-promote (Core Invariant 5)
        call.activationError = null;
        this.publish();
    }

    private async runLeaveOrEnd(callId: CallId, kind: 'leave' | 'end'): Promise<void> {
        const call = this.calls.get(callId);
        if (!call) return;
        // Active: drain + release the lease first, then tear down.
        if (call.id === this.activeCallId && call.foregroundToken) {
            const token = call.foregroundToken;
            this.arbiter.markReleasePending();
            await withTimeout(call.session.releaseForeground(token), FOREGROUND_RELEASE_TIMEOUT_MS);
            // Release the lease regardless: the call is going away, so even a
            // partial drain must not leave the lease stuck.
            try {
                this.arbiter.releaseLease(token);
            } catch (err) {
                this.log('warning', `releaseLease during ${kind} failed: ${formatError(err)}`);
            }
            call.foregroundToken = null;
            this.activeCallId = null;   // no auto-promote
        }
        // Mark reconciled BEFORE driving the session terminal so the resulting
        // session-state callback (phase -> idle/error) finds nothing left to do
        // (idempotency: lease already released, activeCallId already cleared).
        call.terminated = true;
        try {
            if (kind === 'end') call.session.end();
            else call.session.leave();
        } catch (err) {
            this.log('warning', `${kind} teardown failed: ${formatError(err)}`);
        }
        this.scheduleEndedRemoval(call);
        this.releaseModeIfIdle();
        this.publish();
    }

    // --- Session -> registry state forwarding ---

    private onSessionStateChange(call: ManagedCall): void {
        // When a managed call's OWN session reaches a terminal state (remote end /
        // `room_ended` / fatal error) rather than via registry leave()/end(), the
        // registry must reconcile it: drop the lease, clear activeCallId, mark it
        // non-live, and release the owning mode if no live call remains. This must
        // hold for ANY managed call — active OR held — not just the active one:
        // a held call that ends on its own would otherwise wedge registry mode
        // forever, and an active call that ends would keep the lease through the
        // ~3s ending screen. `'ending'` is treated as terminal for lease purposes
        // (release promptly — don't wait through the ending screen). Routed
        // through the SAME operation queue so it cannot interleave a switch.
        if (this.isSessionTerminal(call) && !call.terminated) {
            void this.enqueue(() => this.handleCallTerminated(call));
        }
        this.publish();
    }

    /**
     * Whether the call's underlying session has reached a terminal lifecycle
     * state. `'ending'` counts (the call is going away and the lease must be
     * released promptly rather than held through the ending screen); `'error'`
     * and `'idle'` are the fully-settled terminal phases.
     */
    private isSessionTerminal(call: ManagedCall): boolean {
        const phase = call.session.state.phase;
        return phase === 'ending' || phase === 'error' || phase === 'idle';
    }

    /**
     * Serialized terminal-state reconciliation for a call that ended on its own.
     * Idempotent and queue-safe: a second invocation (or one racing a registry
     * leave/end that already set `terminated`) is a no-op. Never auto-promotes a
     * held call (Core Invariant 5).
     */
    private async handleCallTerminated(call: ManagedCall): Promise<void> {
        // Idempotency guard: already reconciled (by a prior terminal callback or a
        // registry-initiated leave/end). Re-check the phase too, in case the state
        // recovered between enqueue and run.
        if (call.terminated || !this.isSessionTerminal(call)) return;
        call.terminated = true;
        // Release the lease if this call still holds it (active call lost on its
        // own). A held call carries no token, so this is skipped for it.
        if (call.foregroundToken) {
            const token = call.foregroundToken;
            call.foregroundToken = null;
            try {
                this.arbiter.releaseLease(token);
            } catch (err) {
                this.log('warning', `releaseLease for terminated call failed: ${formatError(err)}`);
            }
        }
        // Clear activeCallId without auto-promote (Core Invariant 5).
        if (this.activeCallId === call.id) {
            this.activeCallId = null;
        }
        this.scheduleEndedRemoval(call);
        // Release the `'registry'` owning mode if this was the last live call, so
        // a remote-ended/error HELD call (or the last active call ending) does not
        // wedge the process in registry mode forever.
        this.releaseModeIfIdle();
        this.publish();
    }

    // --- helpers ---

    private async safeAbort(call: ManagedCall): Promise<void> {
        if (!call.foregroundToken) return;
        try {
            await call.session.abortForegroundActivation(call.foregroundToken);
        } catch (err) {
            this.log('warning', `abortForegroundActivation failed: ${formatError(err)}`);
        }
    }

    private scheduleEndedRemoval(call: ManagedCall): void {
        if (this.endedCallRetentionMs <= 0) {
            this.removeCall(call);
            return;
        }
        if (call.retentionTimer) return;
        call.retentionTimer = setTimeout(() => {
            this.removeCall(call);
            this.publish();
        }, this.endedCallRetentionMs);
    }

    private removeCall(call: ManagedCall): void {
        if (call.retentionTimer) {
            clearTimeout(call.retentionTimer);
            call.retentionTimer = null;
        }
        call.unsubscribe();
        this.calls.delete(call.id);
        if (this.activeCallId === call.id) {
            this.activeCallId = null;
        }
        this.releaseModeIfIdle();
    }

    /** Release the `'registry'` mode claim once no non-ended call remains. */
    private releaseModeIfIdle(): void {
        if (!this.modeClaimed) return;
        const hasLive = [...this.calls.values()].some((c) => !this.isEnded(c));
        if (!hasLive) {
            this.arbiter.releaseMode(this.modeRef);
            this.modeClaimed = false;
        }
    }

    private findLiveCallByRoomId(roomId: string): ManagedCall | null {
        for (const call of this.calls.values()) {
            if (call.roomId !== roomId) continue;
            if (!this.isEnded(call)) {
                return call;
            }
        }
        return null;
    }

    /**
     * Whether a managed call has terminally ended (no longer live/switchable).
     * A call left/ended via the session lands at phase `'idle'`; a remote-ended
     * call passes through `'ending'` before `'idle'`; a failed join/room error
     * lands at `'error'` (or sets {@link ManagedCall.joinFailed}). The
     * {@link ManagedCall.terminated} flag covers the window where the registry has
     * reconciled a terminal call but its session phase has not fully settled yet
     * (e.g. `'ending'`), so live counts exclude it the moment the lease is
     * dropped — independent of the ended-call retention timer.
     */
    private isEnded(call: ManagedCall): boolean {
        if (call.terminated || call.joinFailed) return true;
        const phase = this.toMembershipPhase(call);
        return phase === 'ending' || phase === 'idle' || phase === 'error';
    }

    private applyCallError(call: ManagedCall, error: CallActivationError): void {
        call.activationError = error;
        this.setLastError(error);
    }

    private setLastError(error: CallActivationError): CallActivationError {
        this.lastError = error;
        return error;
    }

    private makeError(kind: CallActivationError['kind'], message: string): CallActivationError {
        return { kind, message };
    }

    private toActivationError(kind: CallActivationError['kind'], err: unknown): CallActivationError {
        if (err instanceof ForegroundLeaseUnavailable) {
            return { kind, message: err.message };
        }
        return { kind, message: formatError(err) };
    }

    private canonicalRoomId(room: RoomInput): string {
        if ('url' in room) return canonicalizeRoomId(room.url);
        return canonicalizeRoomId(room.roomId);
    }

    private roomUrlOf(room: RoomInput): string | null {
        return 'url' in room ? room.url : null;
    }

    private toRoomRef(room: RoomInput): RoomRef {
        return 'url' in room ? { url: room.url } : { roomId: room.roomId };
    }

    private toMembershipPhase(call: ManagedCall): CallPhase {
        return call.session.state.phase;
    }

    private toManagedCallState(call: ManagedCall): ManagedCallState {
        const session = call.session;
        const callState: CallState = session.state;
        const participantCount =
            callState.remoteParticipants.length + (callState.localParticipant ? 1 : 0);
        // FIX B: derive role/held from the REGISTRY'S lease token, not
        // `session.currentMediaRole`. The session's role can diverge from the
        // registry's authoritative view (e.g. on teardown the session keeps its
        // last role rather than being reset, and a lost/remote-ended active call
        // drops the lease before the session's role settles). The registry owns
        // which call is foreground (it holds the single lease), so a call is
        // foreground iff it currently holds the lease token AND is the active
        // call. Everything else is held.
        const mediaRole: CallMediaRole =
            call.foregroundToken !== null && call.id === this.activeCallId ? 'foreground' : 'held';
        return {
            id: call.id,
            roomId: call.roomId,
            roomUrl: call.roomUrl,
            membershipPhase: callState.phase,
            mediaRole,
            mediaActivationState: session.currentMediaActivationState as MediaActivationState,
            desiredAudioEnabled: session.currentDesiredAudioEnabled,
            desiredVideoMode: session.currentDesiredVideoMode,
            actualAudioPublished: session.currentActualAudioPublished,
            actualVideoPublished: session.currentActualVideoPublished,
            participantCount,
            localCid: callState.localParticipant?.cid ?? null,
            held: mediaRole === 'held',
            displayName: call.displayName ?? callState.localParticipant?.displayName ?? null,
            activationError: call.activationError,
            qualitySummary: session.callQualitySummary,
        };
    }

    private publish(): void {
        const calls = [...this.calls.values()].map((call) => this.toManagedCallState(call));
        this._state = {
            calls,
            activeCallId: this.activeCallId,
            registryOperationInProgress: this.registryOperationInProgress,
            lastError: this.lastError,
        };
        for (const listener of this.listeners) {
            try {
                listener(this._state);
            } catch {
                // A listener throwing must not break the registry's own bookkeeping.
            }
        }
    }
}

/** Registry-generated stable CallId. Prefers crypto.randomUUID, falls back. */
function generateCallId(): CallId {
    const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
    if (c?.randomUUID) return c.randomUUID();
    return `call-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * Race a promise against a timeout. Returns `true` if `promise` fulfills before
 * the timeout, `false` on timeout. If `promise` REJECTS before the timeout the
 * rejection propagates out of this function (the race rejects) — callers that
 * wrap a throwing operation (activation) catch it; callers of a no-throw
 * operation (idempotent release) only ever see `true`/`false`.
 */
async function withTimeout(promise: Promise<unknown>, ms: number): Promise<boolean> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<'timeout'>((resolve) => {
        timer = setTimeout(() => resolve('timeout'), ms);
    });
    try {
        const result = await Promise.race([
            promise.then(() => 'ok' as const),
            timeout,
        ]);
        return result === 'ok';
    } finally {
        if (timer) clearTimeout(timer);
    }
}
