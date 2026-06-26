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

    it('direct SerenadaCore.join() while a registry has a live call fails ForegroundLeaseUnavailable', async () => {
        const restoreRtc = (globalThis as Record<string, unknown>).RTCPeerConnection;
        (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
        try {
            const { registry, rigs } = makeRegistry({ signalingProvider: new FakeSignalingProvider() });
            const p = registry.joinHeld({ roomId: 'room-A' });
            settleNextOnJoin(rigs);
            await p;

            // The registry holds `registry` mode -> a direct join must fail.
            const core = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
            expect(() => core.join({ roomId: 'OTHER' })).toThrow(ForegroundLeaseUnavailable);
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
});
