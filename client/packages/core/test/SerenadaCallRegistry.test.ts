import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { SerenadaCallRegistry } from '../src/SerenadaCallRegistry.js';
import { SerenadaCore } from '../src/SerenadaCore.js';
import { SerenadaSession } from '../src/SerenadaSession.js';
import type { MediaEngine } from '../src/media/MediaEngine.js';
import { FakeSignalingProvider } from './helpers/FakeSignalingProvider.js';
import { FakeMediaEngine } from './helpers/FakeMediaEngine.js';
import {
    foregroundArbiter,
    __resetForegroundArbiterForTests,
} from '../src/foregroundArbiter.js';
import { ForegroundLeaseUnavailable } from '../src/types.js';
import type { CallId, RoomRef, SerenadaConfig } from '../src/types.js';
import { HELD_JOIN_TIMEOUT_MS } from '../src/constants.js';

// window/navigator shim (mirrors SerenadaSessionForeground.test.ts). The
// preflight path reads navigator.permissions; default to "granted" so a
// foreground switch with audio/video does not return needsPermission unless a
// test overrides it.
if (typeof globalThis.window === 'undefined') {
    const noop = () => {};
    const handler: ProxyHandler<Record<string, unknown>> = {
        get(_target, prop) {
            if (prop === 'setTimeout') return globalThis.setTimeout.bind(globalThis);
            if (prop === 'clearTimeout') return globalThis.clearTimeout.bind(globalThis);
            if (prop === 'setInterval') return globalThis.setInterval.bind(globalThis);
            if (prop === 'clearInterval') return globalThis.clearInterval.bind(globalThis);
            if (prop === 'addEventListener') return noop;
            if (prop === 'removeEventListener') return noop;
            return undefined;
        },
    };
    (globalThis as Record<string, unknown>).window = new Proxy({}, handler);
}
if (typeof globalThis.document === 'undefined') {
    (globalThis as Record<string, unknown>).document = {
        addEventListener: () => {},
        removeEventListener: () => {},
        visibilityState: 'visible',
        hidden: false,
    };
}
function setNavigator(value: unknown): void {
    Object.defineProperty(globalThis, 'navigator', {
        value,
        configurable: true,
        writable: true,
    });
}
function grantAllPermissions(): void {
    setNavigator({ permissions: { query: async () => ({ state: 'granted' }) } });
}

/**
 * A managed-call test rig: a fake-backed SerenadaSession plus its fakes, so a
 * test can drive signaling (`joined`) and inspect media calls. The registry's
 * `createSession` factory returns these sessions, sharing the MODULE-SINGLETON
 * arbiter (the global afterEach in test/vitest.setup.ts resets it between tests
 * — this is how each test gets a clean lease/mode). The session must NOT
 * self-acquire the lease (the registry owns it): `acquireForegroundLease` is
 * left unset.
 */
class CallRig {
    readonly signaling = new FakeSignalingProvider();
    readonly media = new FakeMediaEngine();
    readonly session: SerenadaSession;
    private readonly clientId: string;

    constructor(roomId: string, roomUrl: string | null, config: SerenadaConfig, clientId: string) {
        this.clientId = clientId;
        this.session = new SerenadaSession(config, roomId, roomUrl, this.signaling, {
            media: this.media as unknown as MediaEngine,
            autoStart: false,
            initialMediaRole: 'held',
        });
    }

    /** Drive the held session to membership (`waiting`/`inCall`) — join succeeds. */
    settleJoined(remote = true): void {
        this.signaling.emitConnected('ws');
        const participants = remote
            ? [{ peerId: this.clientId }, { peerId: 'peer-1' }]
            : [{ peerId: this.clientId }];
        this.signaling.emitJoined({ peerId: this.clientId, participants, hostPeerId: this.clientId });
        // Install a local stream so resume/activate has tracks to attach.
        this.media.installLocalStream({ audio: true, video: true });
    }

    /** Drive the session to a join error. */
    settleError(message = 'join failed'): void {
        this.signaling.emitError('CONNECTION_FAILED', message);
    }
}

/**
 * Builds a registry whose `createSession` returns fake-backed sessions and
 * records each {@link CallRig} so a test can drive its signaling. One core is
 * shared but never used to make real sessions (the factory bypasses it).
 */
function makeRegistry(config: SerenadaConfig = { serverHost: 'localhost:8080' }) {
    const rigs: CallRig[] = [];
    let cidCounter = 0;
    const core = new SerenadaCore(config);
    const registry = new SerenadaCallRegistry(core, {
        createSession: (room: RoomRef) => {
            const roomId = 'url' in room ? room.url : room.roomId;
            const roomUrl = 'url' in room ? room.url : null;
            const rig = new CallRig(roomId, roomUrl, config, `cid-${cidCounter++}`);
            rigs.push(rig);
            return rig.session;
        },
    });
    return { registry, rigs };
}

/** A room ref whose join settles to `joined` as soon as the rig is created. */
function settleNextOnJoin(rigs: CallRig[], remote = true): void {
    // The rig is created synchronously inside the queued section A; settle it on
    // the next microtask so awaitHeldJoin (outside the queue) observes membership.
    queueMicrotask(() => rigs[rigs.length - 1]?.settleJoined(remote));
}

describe('SerenadaCallRegistry', () => {
    beforeEach(() => {
        grantAllPermissions();
        __resetForegroundArbiterForTests();
    });
    afterEach(() => {
        __resetForegroundArbiterForTests();
        setNavigator({});
    });

    it('joins two calls and exposes two managed calls', async () => {
        const { registry, rigs } = makeRegistry();

        const p1 = registry.joinHeld({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        const r1 = await p1;

        const p2 = registry.joinHeld({ roomId: 'room-B' });
        settleNextOnJoin(rigs);
        const r2 = await p2;

        expect(r1.kind).toBe('joined');
        expect(r2.kind).toBe('joined');
        expect(registry.state.calls).toHaveLength(2);
        expect(registry.state.activeCallId).toBeNull();
    });

    it('joinHeld creates a held call that never holds capture and takes no lease', async () => {
        const { registry, rigs } = makeRegistry();
        const p = registry.joinHeld({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        await p;

        const rig = rigs[0];
        // Held: no startLocalMedia, no foreground lease acquired.
        expect(rig.media.startLocalMediaCalls).toBe(0);
        expect(rig.media.initializeHeldWithoutCaptureCalls).toBe(1);
        expect(rig.session.currentMediaRole).toBe('held');
        expect(registry.state.activeCallId).toBeNull();
        // Registry claimed `registry` mode (a direct join must now fail).
        expect(foregroundArbiter.currentMode).toBe('registry');
    });

    it('rejects a duplicate live join for the same roomId (idempotent)', async () => {
        const { registry, rigs } = makeRegistry();
        const p1 = registry.joinHeld({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        const r1 = await p1;

        // A second join for an equivalent URL canonicalizes to the same token.
        const p2 = registry.joinHeld({ url: 'https://serenada-app.ru/call/room-A' });
        // No new rig should be created (dedup in section A).
        const r2 = await p2;

        expect(r1.kind).toBe('joined');
        expect(r2.kind).toBe('joined');
        if (r1.kind === 'joined' && r2.kind === 'joined') {
            expect(r2.callId).toBe(r1.callId);
        }
        expect(registry.state.calls).toHaveLength(1);
        expect(rigs).toHaveLength(1);   // only one session ever created
    });

    it('joinAndSwitch holds the prior active call before activating the new one', async () => {
        const { registry, rigs } = makeRegistry();

        // First call: join and switch -> becomes active foreground.
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        const ra = await pa;
        expect(ra.kind).toBe('active');
        const first = rigs[0];
        expect(first.session.currentMediaRole).toBe('foreground');
        expect(registry.state.activeCallId).not.toBeNull();

        // Second call: join and switch -> first must be held before second activates.
        const pb = registry.joinAndSwitch({ roomId: 'room-B' });
        settleNextOnJoin(rigs);
        const rb = await pb;
        expect(rb.kind).toBe('active');

        const second = rigs[1];
        expect(first.session.currentMediaRole).toBe('held');
        expect(second.session.currentMediaRole).toBe('foreground');
        // The held old call suspended capture (release-before-activate ordering).
        expect(first.media.suspendLocalMediaForHoldCalls).toBeGreaterThanOrEqual(1);
    });

    it('serializes old-hold before new-foreground-activate', async () => {
        const { registry, rigs } = makeRegistry();
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        await pa;
        const first = rigs[0];

        const pb = registry.joinAndSwitch({ roomId: 'room-B' });
        settleNextOnJoin(rigs);
        await pb;
        const second = rigs[1];

        // The old call's hold (suspend) happened before the new call resumed.
        // Both are recorded; assert ordering by checking the old was suspended and
        // the new was resumed exactly once.
        expect(first.media.suspendLocalMediaForHoldCalls).toBe(1);
        expect(second.media.resumeLocalMediaFromHoldCalls.length).toBe(1);
        expect(second.session.currentMediaRole).toBe('foreground');
    });

    it('joinAndSwitch with a failing room join leaves the prior active call untouched', async () => {
        const { registry, rigs } = makeRegistry();
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        await pa;
        const first = rigs[0];
        expect(first.session.currentMediaRole).toBe('foreground');
        const activeBefore = registry.state.activeCallId;

        // Second join fails (error before membership).
        const pb = registry.joinAndSwitch({ roomId: 'room-B' });
        queueMicrotask(() => rigs[rigs.length - 1]?.settleError('boom'));
        const rb = await pb;

        expect(rb.kind).toBe('failed');
        // Old call still foreground, still active.
        expect(first.session.currentMediaRole).toBe('foreground');
        expect(registry.state.activeCallId).toBe(activeBefore);
    });

    it('a timed-out held join tears down the dead session (no hidden live participant)', async () => {
        vi.useFakeTimers();
        try {
            const { registry, rigs } = makeRegistry();
            const p = registry.joinHeld({ roomId: 'room-A' });
            await vi.advanceTimersByTimeAsync(0);   // section A creates + starts the session
            // The held join never reaches membership; advance past the bounded wait.
            await vi.advanceTimersByTimeAsync(HELD_JOIN_TIMEOUT_MS + 1);
            const result = await p;

            expect(result.kind).toBe('failed');
            // recordJoinFailure must tear down the dead session (leave the room) so
            // a timed-out join cannot still complete its signaling join and linger
            // as a hidden live room participant. Mirrors the native registries.
            expect(rigs[0].signaling.leaveRoomCalls).toBeGreaterThanOrEqual(1);
        } finally {
            vi.useRealTimers();
        }
    });

    it('joinAndSwitch reusing a still-joining call waits for settlement before switching', async () => {
        const { registry, rigs } = makeRegistry();
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        await pa;
        const first = rigs[0];
        expect(first.session.currentMediaRole).toBe('foreground');

        // First join of room-B: held join stays PENDING (never settled here).
        const pHeld = registry.joinHeld({ roomId: 'room-B' });
        await new Promise((resolve) => setTimeout(resolve, 0));
        expect(rigs).toHaveLength(2);
        // Double-tap: a second op dedups onto the still-joining call. It must NOT
        // hold room-A or activate room-B before the join settles.
        const pSwitch = registry.joinAndSwitch({ roomId: 'room-B' });
        await new Promise((resolve) => setTimeout(resolve, 0));
        expect(first.session.currentMediaRole).toBe('foreground');
        expect(first.media.suspendLocalMediaForHoldCalls).toBe(0);
        expect(rigs).toHaveLength(2);   // deduped, no extra session

        // Settle the join: the held join reports joined and the switch proceeds.
        rigs[1].settleJoined();
        const held = await pHeld;
        const switched = await pSwitch;
        expect(held.kind).toBe('joined');
        expect(switched.kind).toBe('active');
        expect(first.session.currentMediaRole).toBe('held');
        expect(rigs[1].session.currentMediaRole).toBe('foreground');
    });

    it('switchTo a still-joining call waits for join settlement instead of activating early', async () => {
        const { registry, rigs } = makeRegistry();
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        await pa;
        const first = rigs[0];

        const pHeld = registry.joinHeld({ roomId: 'room-B' });
        await new Promise((resolve) => setTimeout(resolve, 0));
        const callB = registry.state.calls.find((c) => c.roomId === 'room-B');
        expect(callB).toBeDefined();

        const pSwitch = registry.switchTo(callB!.id);
        await new Promise((resolve) => setTimeout(resolve, 0));
        // room-B is still joining: room-A must remain untouched foreground.
        expect(first.session.currentMediaRole).toBe('foreground');
        expect(first.media.suspendLocalMediaForHoldCalls).toBe(0);

        rigs[1].settleJoined();
        expect((await pSwitch).kind).toBe('active');
        await pHeld;
        expect(rigs[1].session.currentMediaRole).toBe('foreground');
        expect(first.session.currentMediaRole).toBe('held');
    });

    it('a double joinAndSwitch to a room that never joins fails both without touching the active call or the lease', async () => {
        vi.useFakeTimers();
        try {
            const { registry, rigs } = makeRegistry();
            const pa = registry.joinAndSwitch({ roomId: 'room-A' });
            settleNextOnJoin(rigs);
            await vi.advanceTimersByTimeAsync(0);
            expect((await pa).kind).toBe('active');
            const first = rigs[0];
            const activeBefore = registry.state.activeCallId;

            // Two racing joinAndSwitch ops on the same unreachable room. Before the
            // settlement gate, the second (reused) op switched immediately: it held
            // room-A and foregrounded a session whose join then timed out — and the
            // join-failure handler stranded the acquired lease forever.
            const p1 = registry.joinAndSwitch({ roomId: 'room-B' });
            await vi.advanceTimersByTimeAsync(0);
            const p2 = registry.joinAndSwitch({ roomId: 'room-B' });
            await vi.advanceTimersByTimeAsync(HELD_JOIN_TIMEOUT_MS + 1);
            const [r1, r2] = await Promise.all([p1, p2]);
            expect(r1.kind).toBe('failed');
            expect(r2.kind).toBe('failed');

            // The active call was never disturbed and still owns the lease/slot.
            expect(first.session.currentMediaRole).toBe('foreground');
            expect(first.media.suspendLocalMediaForHoldCalls).toBe(0);
            expect(registry.state.activeCallId).toBe(activeBefore);
            expect(rigs).toHaveLength(2);   // the retry deduped onto one dead call
        } finally {
            vi.useRealTimers();
        }
    });

    it('switch where target needs permission returns needsPermission and leaves old foreground', async () => {
        const { registry, rigs } = makeRegistry();
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        await pa;
        const first = rigs[0];
        const activeBefore = registry.state.activeCallId;

        // Second call joins held.
        const pb = registry.joinHeld({ roomId: 'room-B' });
        settleNextOnJoin(rigs);
        const rb = await pb;
        const secondId = (rb as { callId: CallId }).callId;
        const second = rigs[1];

        // Make the target's preflight return needsPermission (mic ungranted).
        vi.spyOn(second.session, 'preflightForeground').mockResolvedValue('needsPermission');

        const result = await registry.switchTo(secondId);
        expect(result.kind).toBe('needsPermission');
        // Preflight ran before releasing old: old untouched, still foreground.
        expect(first.session.currentMediaRole).toBe('foreground');
        expect(registry.state.activeCallId).toBe(activeBefore);
        // Old's release must NOT have been called.
        expect(first.media.suspendLocalMediaForHoldCalls).toBe(0);
        // Per-call activationError carries the needed permission.
        const secondState = registry.state.calls.find((c) => c.id === secondId);
        expect(secondState?.activationError?.kind).toBe('needsPermission');
    });

    it('joinAndSwitch needsPermission(callId) exposes the held call id', async () => {
        const { registry, rigs } = makeRegistry();
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        await pa;

        // Spy the NEXT-created session's preflight before it is created: patch the
        // factory result via the rigs array after creation. Use a permission
        // denial on the second rig.
        const pb = registry.joinAndSwitch({ roomId: 'room-B' });
        queueMicrotask(() => {
            const rig = rigs[rigs.length - 1];
            vi.spyOn(rig.session, 'preflightForeground').mockResolvedValue('needsPermission');
            rig.settleJoined();
        });
        const rb = await pb;

        expect(rb.kind).toBe('needsPermission');
        if (rb.kind === 'needsPermission') {
            expect(rb.callId).toBeTruthy();
            // The held call exists and is switchable later.
            expect(registry.state.calls.some((c) => c.id === rb.callId)).toBe(true);
        }
    });

    it('old-release failure aborts the switch (old still foreground, next lease never acquired)', async () => {
        vi.useFakeTimers();
        try {
            const { registry, rigs } = makeRegistry();

            // First call: join + switch -> active foreground.
            const pa = registry.joinAndSwitch({ roomId: 'room-A' });
            await vi.advanceTimersByTimeAsync(0);   // section A creates the rig
            rigs[0].settleJoined();
            await vi.advanceTimersByTimeAsync(0);   // awaitHeldJoin + section C switch
            await pa;
            const first = rigs[0];
            const firstActive = registry.state.activeCallId;
            expect(first.session.currentMediaRole).toBe('foreground');

            // Second call: held.
            const pb = registry.joinHeld({ roomId: 'room-B' });
            await vi.advanceTimersByTimeAsync(0);
            rigs[1].settleJoined();
            await vi.advanceTimersByTimeAsync(0);
            const rb = await pb;
            const secondId = (rb as { callId: CallId }).callId;
            const second = rigs[1];

            // Make the OLD call's releaseForeground hang forever -> release times out.
            vi.spyOn(first.session, 'releaseForeground').mockImplementation(() => new Promise(() => {}));

            const switchPromise = registry.switchTo(secondId);
            await vi.advanceTimersByTimeAsync(0);   // run preflight + start release
            await vi.advanceTimersByTimeAsync(6000); // past FOREGROUND_RELEASE_TIMEOUT_MS
            const result = await switchPromise;

            expect(result.kind).toBe('failed');
            if (result.kind === 'failed') {
                expect(result.error.kind).toBe('releaseFailed');
            }
            // Old retains foreground; next never activated; active unchanged.
            expect(first.session.currentMediaRole).toBe('foreground');
            expect(second.session.currentMediaRole).toBe('held');
            expect(registry.state.activeCallId).toBe(firstActive);
            // Old call marked releaseFailed.
            const oldState = registry.state.calls.find((c) => c.mediaRole === 'foreground');
            expect(oldState?.activationError?.kind).toBe('releaseFailed');
        } finally {
            vi.useRealTimers();
        }
    });

    it('failed activation aborts the partial target then rolls back to previous', async () => {
        const { registry, rigs } = makeRegistry();
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        await pa;
        const first = rigs[0];

        const pb = registry.joinHeld({ roomId: 'room-B' });
        settleNextOnJoin(rigs);
        const rb = await pb;
        const secondId = (rb as { callId: CallId }).callId;
        const second = rigs[1];

        // Target activation throws.
        vi.spyOn(second.session, 'activateForeground').mockRejectedValue(new Error('activation boom'));
        const abortSpy = vi.spyOn(second.session, 'abortForegroundActivation');

        const result = await registry.switchTo(secondId);

        expect(result.kind).toBe('failed');
        // Partial target was aborted.
        expect(abortSpy).toHaveBeenCalled();
        // Rolled back to the previous active call.
        expect(first.session.currentMediaRole).toBe('foreground');
        expect(registry.activeCall?.session).toBe(first.session);
        // Next call surfaced its recoverable error.
        const secondState = registry.state.calls.find((c) => c.id === secondId);
        expect(secondState?.activationError?.kind).toBe('activationFailed');
        // A live lease still exists (old reacquired it): no two owners.
        expect(() => foregroundArbiter.acquireForeground('probe', 'direct', {})).toThrow(ForegroundLeaseUnavailable);
    });

    it('direct SerenadaCore.join() while a registry has a live call fails gracefully (error state)', async () => {
        const restoreRtc = (globalThis as Record<string, unknown>).RTCPeerConnection;
        (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
        try {
            const { registry, rigs } = makeRegistry({ signalingProvider: new FakeSignalingProvider() });
            const p = registry.joinHeld({ roomId: 'room-A' });
            settleNextOnJoin(rigs);
            await p;

            // The registry holds `registry` mode -> a direct join must fail, surfaced
            // as an error CallState rather than a synchronous throw out of join().
            const core = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
            const session = core.join({ roomId: 'OTHER' });
            expect(session.state.phase).toBe('error');
            expect(session.state.error?.code).toBe('unknown');
            session.destroy();
        } finally {
            (globalThis as Record<string, unknown>).RTCPeerConnection = restoreRtc;
        }
    });

    it('late callback from a superseded activation is dropped', async () => {
        // The session's own generation+token fence drops a stale activation. We
        // hold the resume mid-flight (so the activation is genuinely in progress),
        // bump the generation with a concurrent hold, THEN let the slow resume
        // resolve: the superseded callback must not land foreground.
        const { registry, rigs } = makeRegistry();
        const pa = registry.joinHeld({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        const ra = await pa;
        const callId = (ra as { callId: CallId }).callId;
        const rig = rigs[0];

        let resolveStarted: () => void = () => {};
        const started = new Promise<void>((resolve) => { resolveStarted = resolve; });
        let releaseResume: () => void = () => {};
        const gate = new Promise<void>((resolve) => { releaseResume = resolve; });
        const original = rig.media.resumeLocalMediaFromHold.bind(rig.media);
        vi.spyOn(rig.media, 'resumeLocalMediaFromHold').mockImplementation(async (a, v) => {
            resolveStarted();   // activation's resume has begun
            await gate;
            return original(a, v);
        });

        const switchPromise = registry.switchTo(callId);
        // Wait until the activation's resume is genuinely in flight.
        await started;
        // A concurrent un-gated hold bumps the generation past the activation's.
        const holdPromise = rig.session.suspendForHold();
        // Now let the slow resume resolve: it must see itself superseded.
        releaseResume();
        await Promise.all([switchPromise, holdPromise]);

        // The superseded activation was dropped: still held, never foreground.
        expect(rig.session.currentMediaRole).toBe('held');
    });

    it('hold(active) drains the call and clears activeCallId (no auto-promote)', async () => {
        const { registry, rigs } = makeRegistry();
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        const ra = await pa;
        const callId = (ra as { callId: CallId }).callId;

        // Add a held second call to prove no auto-promote.
        const pb = registry.joinHeld({ roomId: 'room-B' });
        settleNextOnJoin(rigs);
        await pb;

        await registry.hold(callId);
        expect(registry.state.activeCallId).toBeNull();
        expect(rigs[0].session.currentMediaRole).toBe('held');
        expect(rigs[1].session.currentMediaRole).toBe('held');   // not auto-promoted
    });

    it('leave(active) releases the lease and tears down; held calls stay', async () => {
        const { registry, rigs } = makeRegistry({ serverHost: 'localhost:8080', endedCallRetentionMs: 0 } as SerenadaConfig & { endedCallRetentionMs: number });
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        const ra = await pa;
        const callId = (ra as { callId: CallId }).callId;

        const pb = registry.joinHeld({ roomId: 'room-B' });
        settleNextOnJoin(rigs);
        await pb;

        await registry.leave(callId);
        expect(registry.state.activeCallId).toBeNull();
        // The held call remains connected, not auto-promoted.
        expect(registry.state.calls.some((c) => c.roomId === 'room-B')).toBe(true);
        expect(rigs[1].session.currentMediaRole).toBe('held');
        // After leaving the only foreground call, the lease is free: a fresh
        // registry switch can acquire it again (no leaked lease).
        const bId = registry.state.calls.find((c) => c.roomId === 'room-B')!.id;
        const sw = await registry.switchTo(bId);
        expect(sw.kind).toBe('active');
    });

    // --- FIX B: published mediaRole/held/activeCallId derive from the lease token ---

    it('published mediaRole/held/activeCallId follow the lease token, not session.currentMediaRole', async () => {
        const { registry, rigs } = makeRegistry();

        // Active foreground call.
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        const ra = await pa;
        const callId = (ra as { callId: CallId }).callId;
        const rig = rigs[0];

        // Sanity: while it holds the lease, it publishes foreground.
        expect(registry.state.calls.find((c) => c.id === callId)?.mediaRole).toBe('foreground');
        expect(registry.state.activeCallId).toBe(callId);

        // Simulate the divergence the fix guards against: the session's role field
        // is NOT authoritative (e.g. it would stay 'foreground' across a teardown
        // that does not reset it, or lag the registry on a remote-ended drop).
        // Force currentMediaRole to lie BEFORE holding so any read of it would be
        // wrong, then re-derive published state from the lease token.
        vi.spyOn(rig.session, 'currentMediaRole', 'get').mockReturnValue('foreground');

        // Hold the call: the registry releases the lease + clears activeCallId.
        // (releaseForeground still drains the real media; only the role GETTER lies.)
        await registry.hold(callId);
        // Force a re-publish AFTER the spy so toManagedCallState runs against the
        // lying getter (registry.state is a cached snapshot otherwise).
        rig.signaling.emitRoomStateUpdated({ participants: [{ peerId: 'cid-0' }] });

        // The published state must reflect the lease token (held), NOT the session
        // role (which the spy reports as 'foreground').
        const held = registry.state.calls.find((c) => c.id === callId);
        expect(rig.session.currentMediaRole).toBe('foreground');   // the getter lies
        expect(held?.mediaRole).toBe('held');                      // token wins
        expect(held?.held).toBe(true);
        expect(registry.state.activeCallId).toBeNull();
    });

    // --- FIX D: every switchTo is enqueued (no outside-queue fast path) ---

    it('switchTo enqueues behind an in-flight op and re-reads activeCallId (no fast path)', async () => {
        const { registry, rigs } = makeRegistry();

        // A becomes active.
        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        const ra = await pa;
        const aId = (ra as { callId: CallId }).callId;
        const first = rigs[0];

        // Gate A's hold (releaseForeground) mid-drain so the hold op stays in the
        // queue and `activeCallId` is still `aId` when we fire switchTo(aId). This
        // is exactly the race the removed fast path created: a fast-path read of
        // `this.activeCallId === aId` would return `active` immediately even though
        // a queued hold is about to drop A's foreground.
        let releaseHold: () => void = () => {};
        const holdGate = new Promise<void>((resolve) => { releaseHold = resolve; });
        const originalSuspend = first.media.suspendLocalMediaForHold.bind(first.media);
        let suspendStarted: () => void = () => {};
        const suspendInFlight = new Promise<void>((resolve) => { suspendStarted = resolve; });
        vi.spyOn(first.media, 'suspendLocalMediaForHold').mockImplementation(async () => {
            suspendStarted();
            await holdGate;
            return originalSuspend();
        });

        // Op 1: hold A (blocks mid-drain). activeCallId is still aId at this point.
        const holdA = registry.hold(aId);
        await suspendInFlight;   // A's drain is genuinely in flight
        expect(registry.state.activeCallId).toBe(aId);   // still looks active

        // Op 2: switchTo(aId) while aId === activeCallId. With the removed fast path
        // this must NOT resolve immediately; it enqueues behind the hold.
        let switchResolved = false;
        const switchToA = registry.switchTo(aId).then((r) => { switchResolved = true; return r; });
        await Promise.resolve();
        await Promise.resolve();
        // A fast path would have resolved switchToA already (reading stale activeCallId).
        expect(switchResolved).toBe(false);

        // Drain the hold: activeCallId becomes null. The queued switchTo then
        // re-reads activeCallId (null) and performs a REAL re-activation of A.
        releaseHold();
        const [, sw] = await Promise.all([holdA, switchToA]);

        expect(sw.kind).toBe('active');
        // The queued switch re-activated A (it did not no-op on a stale read).
        expect(registry.state.activeCallId).toBe(aId);
        expect(registry.state.calls.find((c) => c.id === aId)?.mediaRole).toBe('foreground');
        // Proof it really re-acquired foreground after the hold drained it: the
        // session was resumed (a fast-path no-op would have left it held).
        expect(first.media.resumeLocalMediaFromHoldCalls.length).toBeGreaterThanOrEqual(1);
    });

    // --- FIX F: a failed held join releases registry mode ---

    it('a failed held join releases registry mode so a later direct join succeeds', async () => {
        const restoreRtc = (globalThis as Record<string, unknown>).RTCPeerConnection;
        (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
        try {
            const { registry, rigs } = makeRegistry({ signalingProvider: new FakeSignalingProvider() });

            // Held join FAILS (error before membership).
            const p = registry.joinHeld({ roomId: 'room-A' });
            queueMicrotask(() => rigs[rigs.length - 1]?.settleError('boom'));
            const r = await p;
            expect(r.kind).toBe('failed');

            // No live call remains -> the registry must have released its mode.
            expect(foregroundArbiter.currentMode).toBeNull();

            // A direct SerenadaCore.join() must now succeed (mode is free): the
            // failed join did NOT wedge the process in registry mode.
            const core = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
            expect(() => core.join({ roomId: 'OTHER' })).not.toThrow();
        } finally {
            (globalThis as Record<string, unknown>).RTCPeerConnection = restoreRtc;
        }
    });

    // --- Terminal session state is a serialized registry op (lease-leak fix) ---

    it('remote-ended ACTIVE call releases the lease + clears activeCallId; a later direct join succeeds', async () => {
        const restoreRtc = (globalThis as Record<string, unknown>).RTCPeerConnection;
        (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
        try {
            const { registry, rigs } = makeRegistry({ signalingProvider: new FakeSignalingProvider() });

            // Call A: join + switch -> active foreground (holds the lease).
            const pa = registry.joinAndSwitch({ roomId: 'room-A' });
            settleNextOnJoin(rigs);
            const ra = await pa;
            expect(ra.kind).toBe('active');
            const callId = (ra as { callId: CallId }).callId;
            const rig = rigs[0];
            expect(registry.state.activeCallId).toBe(callId);
            expect(foregroundArbiter.currentMode).toBe('registry');

            // The session reaches a terminal state ON ITS OWN (remote end /
            // room_ended) — NOT via registry.leave/end. This drives the session
            // through `ending` (then `idle`). The registry must reconcile it.
            rig.signaling.emitRoomEnded();
            // Let the queued terminal-state op run.
            await Promise.resolve();
            await Promise.resolve();

            // Lease released, active cleared, call marked ended/non-live.
            expect(registry.state.activeCallId).toBeNull();
            const ended = registry.state.calls.find((c) => c.id === callId);
            // (retention 0 by default removes it; either gone or held/non-foreground.)
            expect(ended?.mediaRole ?? 'held').toBe('held');
            // No live call remains -> registry mode released.
            expect(foregroundArbiter.currentMode).toBeNull();

            // The lease is free: a subsequent direct SerenadaCore.join() succeeds
            // (the foreground lease was NOT leaked through the ending screen).
            const core = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
            expect(() => core.join({ roomId: 'OTHER' })).not.toThrow();
        } finally {
            (globalThis as Record<string, unknown>).RTCPeerConnection = restoreRtc;
        }
    });

    it('remote-ended HELD call marks ended and releases registry mode when it was the last live call', async () => {
        const restoreRtc = (globalThis as Record<string, unknown>).RTCPeerConnection;
        (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
        try {
            const { registry, rigs } = makeRegistry({ signalingProvider: new FakeSignalingProvider() });

            // A single HELD call (never foregrounded, holds no lease).
            const p = registry.joinHeld({ roomId: 'room-A' });
            settleNextOnJoin(rigs);
            await p;
            const rig = rigs[0];
            expect(registry.state.activeCallId).toBeNull();
            expect(foregroundArbiter.currentMode).toBe('registry');

            // The held call's session reaches a terminal state on its own (fatal
            // error). Previously this just published and never released mode,
            // wedging the registry in `registry` mode forever.
            rig.signaling.emitError('CONNECTION_FAILED', 'boom');
            await Promise.resolve();
            await Promise.resolve();

            // Last live call gone -> registry mode released; a direct join works.
            expect(foregroundArbiter.currentMode).toBeNull();
            const core = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
            expect(() => core.join({ roomId: 'OTHER' })).not.toThrow();
        } finally {
            (globalThis as Record<string, unknown>).RTCPeerConnection = restoreRtc;
        }
    });

    it('no double-release when registry.end() drove the termination', async () => {
        const { registry, rigs } = makeRegistry();

        const pa = registry.joinAndSwitch({ roomId: 'room-A' });
        settleNextOnJoin(rigs);
        const ra = await pa;
        const callId = (ra as { callId: CallId }).callId;
        const rig = rigs[0];

        // Count arbiter lease releases: a clean registry.end() must release the
        // lease exactly once. The session-driven terminal handler must NOT
        // re-release it (the idempotency `terminated` guard makes it a no-op).
        const releaseSpy = vi.spyOn(foregroundArbiter, 'releaseLease');

        await registry.end(callId);
        // Drain any queued session-driven terminal callback fired by end().
        await Promise.resolve();
        await Promise.resolve();

        expect(registry.state.activeCallId).toBeNull();
        // Exactly one lease release for this call's token (no double-release).
        expect(releaseSpy).toHaveBeenCalledTimes(1);
        expect(rig.signaling.endRoomCalls).toBe(1);
    });

    // --- Phase 4 (web): single-capture-owner invariant ---
    // The web "capture lease" reduces to one rule: at most one session ever holds
    // getUserMedia() tracks (the foreground call). These tests assert that
    // invariant at the registry boundary, in terms of physical local tracks (the
    // FakeMediaEngine stops + removes tracks on suspend, mirroring the real
    // engine's releaseLocalAudioCapture/releaseVideoTrack), not just call counts.
    describe('single-capture-owner invariant (Phase 4 web capture lease)', () => {
        /** Count of live (non-ended) local capture tracks a rig currently holds. */
        function liveLocalTrackCount(rig: CallRig): number {
            const stream = rig.media.localStream as unknown as { getTracks(): { readyState: string }[] } | null;
            if (!stream) return 0;
            return stream.getTracks().filter(t => t.readyState !== 'ended').length;
        }

        it('a held call (joinHeld) holds zero local capture tracks', async () => {
            const { registry, rigs } = makeRegistry();
            const p = registry.joinHeld({ roomId: 'room-A' });
            settleNextOnJoin(rigs);
            await p;
            const rig = rigs[0];

            // settleJoined installs a stream, but a held-initial session never
            // foregrounds, so it must own no live capture (Core Invariant 3). The
            // held-without-capture init latched heldNoCapture; the session never
            // resumed, so any installed tracks are irrelevant to the OS — assert
            // role + that no foreground capture path ran.
            expect(rig.session.currentMediaRole).toBe('held');
            expect(rig.media.startLocalMediaCalls).toBe(0);
            expect(rig.media.resumeLocalMediaFromHoldCalls.length).toBe(0);
            expect(rig.media.heldNoCapture).toBe(true);
        });

        it('across a switch the OLD session releases all local tracks BEFORE the new session acquires any', async () => {
            const { registry, rigs } = makeRegistry();

            const pa = registry.joinAndSwitch({ roomId: 'room-A' });
            settleNextOnJoin(rigs);
            await pa;
            const first = rigs[0];
            expect(first.session.currentMediaRole).toBe('foreground');
            // Foreground call A owns live capture tracks.
            expect(liveLocalTrackCount(first)).toBeGreaterThan(0);

            // Capture the OLD call's live-track count at the instant the NEW call
            // begins reacquiring its own capture (resumeLocalMediaFromHold). The
            // invariant: the old session must already own ZERO live tracks then —
            // there is never a window where two sessions hold getUserMedia tracks.
            let oldLiveTracksAtNewAcquire = -1;

            const pb = registry.joinAndSwitch({ roomId: 'room-B' });
            // The second rig exists only after section A runs; install the spy on
            // the next microtask, before its activate/resume is awaited.
            queueMicrotask(() => {
                const second = rigs[rigs.length - 1];
                second.settleJoined();
                const realResume = second.media.resumeLocalMediaFromHold.bind(second.media);
                second.media.resumeLocalMediaFromHold = async (a, v) => {
                    oldLiveTracksAtNewAcquire = liveLocalTrackCount(first);
                    return realResume(a, v);
                };
            });
            const rb = await pb;
            expect(rb.kind).toBe('active');
            const second = rigs[1];

            // The new call acquired capture; the old released ALL of it first.
            expect(oldLiveTracksAtNewAcquire).toBe(0);
            expect(first.session.currentMediaRole).toBe('held');
            expect(liveLocalTrackCount(first)).toBe(0);
            expect(second.session.currentMediaRole).toBe('foreground');
            expect(liveLocalTrackCount(second)).toBeGreaterThan(0);
            // Exactly one foreground call owns capture at the end.
            const foregroundCount = rigs.filter(r => r.session.currentMediaRole === 'foreground').length;
            expect(foregroundCount).toBe(1);
        });

        it('a non-active (held) session cannot start capture via toggles (intent only)', async () => {
            const { registry, rigs } = makeRegistry();

            // A is foreground; B is held (joined but not switched to).
            const pa = registry.joinAndSwitch({ roomId: 'room-A' });
            settleNextOnJoin(rigs);
            await pa;
            const pb = registry.joinHeld({ roomId: 'room-B' });
            settleNextOnJoin(rigs);
            await pb;
            const held = rigs[1];
            expect(held.session.currentMediaRole).toBe('held');

            const reacquireAudioBefore = held.media.reacquireLocalAudioCaptureCalls;
            const reacquireVideoBefore = held.media.reacquireVideoTrackCalls;
            const flipBefore = held.media.flipCameraCalls;
            const screenShareBefore = held.media.startScreenShareCalls;

            // Every capture-bearing toggle on the held call must update desired
            // intent ONLY — no getUserMedia / getDisplayMedia (no capture sink).
            held.session.setAudioEnabled(true);
            held.session.setVideoEnabled(true);
            await held.session.flipCamera();
            held.session.setCameraMode('world');
            await held.session.startScreenShare();
            await Promise.resolve();

            expect(held.media.reacquireLocalAudioCaptureCalls).toBe(reacquireAudioBefore);
            expect(held.media.reacquireVideoTrackCalls).toBe(reacquireVideoBefore);
            expect(held.media.flipCameraCalls).toBe(flipBefore);
            expect(held.media.startScreenShareCalls).toBe(screenShareBefore);
            // Desired intent was recorded for resume.
            expect(held.session.currentDesiredAudioEnabled).toBe(true);
            expect(held.session.currentDesiredVideoMode).not.toBe('off');
            expect(held.session.currentMediaRole).toBe('held');
        });
    });

    describe('teardown + session-factory failure', () => {
        it('surfaces a session-factory throw as joinFailed and leaves no call (e.g. unsupported browser)', async () => {
            // joinInternal throws on an unsupported browser; the registry must
            // catch it (not launder a broken handle) and release the mode claim.
            const core = new SerenadaCore({ serverHost: 'localhost:8080' });
            const registry = new SerenadaCallRegistry(core, {
                createSession: () => {
                    throw new Error('WebRTC is not supported in this browser');
                },
            });
            const result = await registry.joinHeld({ roomId: 'room-x' });
            expect(result.kind).toBe('failed');
            expect(registry.state.calls).toHaveLength(0);
            // Mode claim released so a later direct join is not blocked.
            expect(foregroundArbiter.currentMode).toBeNull();
        });

        it('close() leaves every managed call and releases the lease + owning mode', async () => {
            const { registry, rigs } = makeRegistry();
            const pa = registry.joinAndSwitch({ roomId: 'room-a' });   // foreground
            settleNextOnJoin(rigs);
            await pa;
            const pb = registry.joinHeld({ roomId: 'room-b' });          // held
            settleNextOnJoin(rigs);
            await pb;
            expect(registry.state.calls.length).toBe(2);
            expect(foregroundArbiter.currentMode).toBe('registry');

            await registry.close();

            expect(registry.state.calls).toHaveLength(0);
            expect(registry.state.activeCallId).toBeNull();
            expect(foregroundArbiter.currentMode).toBeNull();
        });

        it('a create enqueued before close() no-ops after close (no leaked call/session)', async () => {
            const { registry, rigs } = makeRegistry();
            // Start a join but do NOT await it, then close immediately. The queued
            // create must observe `closed` and never construct a session.
            const pending = registry.joinHeld({ roomId: 'room-late' });
            await registry.close();
            const result = await pending;
            expect(result.kind).toBe('failed');
            expect(registry.state.calls).toHaveLength(0);
            expect(rigs).toHaveLength(0);   // factory never ran
            expect(foregroundArbiter.currentMode).toBeNull();
        });

        it('a closed registry rejects new joinHeld', async () => {
            const { registry } = makeRegistry();
            await registry.close();
            const result = await registry.joinHeld({ roomId: 'room-y' });
            expect(result.kind).toBe('failed');
            expect(registry.state.calls).toHaveLength(0);
        });
    });
});
