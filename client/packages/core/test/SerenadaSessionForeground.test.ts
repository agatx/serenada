import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { TestSessionHarness } from './helpers/TestSessionHarness.js';
import { SerenadaCore } from '../src/SerenadaCore.js';
import { MediaEngine } from '../src/media/MediaEngine.js';
import {
    foregroundArbiter,
    __resetForegroundArbiterForTests,
} from '../src/foregroundArbiter.js';
import { ForegroundLeaseUnavailable } from '../src/types.js';
import { FakeSignalingProvider } from './helpers/FakeSignalingProvider.js';

// window/navigator shim (mirrors SerenadaSessionHold.test.ts).
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
if (typeof globalThis.navigator === 'undefined') {
    (globalThis as Record<string, unknown>).navigator = {};
}

function lastMediaStateBroadcast(harness: TestSessionHarness): Record<string, unknown> | undefined {
    const calls = harness.signaling.broadcastCalls.filter((c) => c.type === 'participant_media_state');
    return calls.at(-1)?.payload as Record<string, unknown> | undefined;
}

describe('SerenadaSession held-initial join (Phase 2)', () => {
    let harness: TestSessionHarness;

    beforeEach(() => { vi.useFakeTimers(); });
    afterEach(() => {
        harness?.destroy();
        vi.useRealTimers();
        __resetForegroundArbiterForTests();
    });

    it('a held-initial session owns no capture and did not acquire the lease', async () => {
        harness = new TestSessionHarness({ initialMediaRole: 'held' });
        harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }, { cid: 'peer-1' }] });
        await vi.advanceTimersByTimeAsync(0);

        // No capture was started (initializeHeldWithoutCapture latched no-capture;
        // the permission-check auto-start path is skipped).
        expect(harness.media.startLocalMediaCalls).toBe(0);
        expect(harness.media.initializeHeldWithoutCaptureCalls).toBe(1);
        // Remote playout was silenced for the held call.
        expect(harness.media.setRemotePlaybackEnabledCalls).toContain(false);
        // Role/activation reflect held.
        expect(harness.session.currentMediaRole).toBe('held');
        expect(harness.session.currentMediaActivationState).toBe('inactive');
        expect(harness.session.currentActualAudioPublished).toBe(false);
        expect(harness.session.currentActualVideoPublished).toBe(false);
        // No foreground lease taken by a held-initial join.
        expect(foregroundArbiter.currentMode).toBeNull();
        // The lease is free: a fresh direct acquire works.
        const token = foregroundArbiter.acquireForeground('probe', 'direct', {});
        expect(foregroundArbiter.isCurrentOwner(token)).toBe(true);
    });

    it('a held-initial session creates stable audio+video senders with no track (MediaEngine)', () => {
        // Real engine + fake RTCPeerConnection: assert transceivers/senders exist
        // before any activation, carrying a null sender track (Core Invariant 3).
        const fakePcs: FakePc[] = [];
        const mkTransceiver = (kind: string) => ({
            mid: kind === 'audio' ? '0' : '1',
            direction: 'sendrecv',
            sender: { track: null as unknown, replaceTrack: async () => {} },
            receiver: { track: { kind } },
        });
        (globalThis as Record<string, unknown>).RTCPeerConnection = class {
            transceivers: ReturnType<typeof mkTransceiver>[] = [];
            getSenders() { return this.transceivers.map(t => t.sender); }
            getTransceivers() { return this.transceivers; }
            getReceivers() { return this.transceivers.map(t => t.receiver); }
            addTransceiver(kind: string) {
                const t = mkTransceiver(kind);
                this.transceivers.push(t);
                return t;
            }
            addTrack() { throw new Error('held join must not addTrack (no capture)'); }
            setConfiguration() {}
            close() {}
            set ontrack(_v: unknown) {}
            set oniceconnectionstatechange(_v: unknown) {}
            set onconnectionstatechange(_v: unknown) {}
            set onsignalingstatechange(_v: unknown) {}
            set onicecandidate(_v: unknown) {}
            set onnegotiationneeded(_v: unknown) {}
            signalingState = 'stable';
            iceConnectionState = 'new';
            connectionState = 'new';
            remoteDescription = null;
            constructor() { fakePcs.push(this as unknown as FakePc); }
        } as unknown as typeof RTCPeerConnection;

        const engine = new MediaEngine(
            { videoMediaEnabled: true, videoCaptureSupported: true },
            () => {},
        );
        engine.initializeHeldWithoutCapture();
        // Drive peer creation as the join flow would (room state with one remote).
        engine.updateRoomState(
            { hostCid: 'me', participants: [{ cid: 'me' }, { cid: 'peer-1' }], maxParticipants: 2 },
            'me',
        );

        const pc = fakePcs[0];
        expect(pc).toBeDefined();
        const senders = pc.getSenders();
        // Stable audio + video senders exist with no attached capture track.
        expect(senders.length).toBeGreaterThanOrEqual(2);
        for (const sender of senders) {
            expect(sender.track).toBeNull();
        }

        delete (globalThis as Record<string, unknown>).RTCPeerConnection;
    });

    it('a held-initial session can be activated via the token-gated activateForeground', async () => {
        harness = new TestSessionHarness({ initialMediaRole: 'held' });
        harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }, { cid: 'peer-1' }] });
        await vi.advanceTimersByTimeAsync(0);
        harness.media.installLocalStream({ audio: true, video: true });

        const token = foregroundArbiter.acquireForeground('test-room-id', 'direct', {});
        const gen = foregroundArbiter.nextOperationGeneration();
        await harness.session.activateForeground(token, gen);

        expect(harness.session.currentMediaRole).toBe('foreground');
        expect(harness.session.currentMediaActivationState).toBe('active');
        const payload = lastMediaStateBroadcast(harness);
        expect(payload?.held).toBe(false);
    });
});

describe('SerenadaSession.preflightForeground (Phase 2)', () => {
    let harness: TestSessionHarness;
    beforeEach(() => { vi.useFakeTimers(); });
    afterEach(() => {
        harness?.destroy();
        vi.useRealTimers();
        __resetForegroundArbiterForTests();
        delete (globalThis as Record<string, unknown>).navigator;
        (globalThis as Record<string, unknown>).navigator = {};
    });

    it('returns ok for a muted + camera-off desired state without any permission', async () => {
        harness = new TestSessionHarness({
            config: { defaultAudioEnabled: false, defaultVideoEnabled: false },
            initialMediaRole: 'held',
        });
        // No navigator.permissions at all: a no-device-needed call still preflights ok.
        await expect(harness.session.preflightForeground()).resolves.toBe('ok');
    });

    it('returns needsPermission when desired audio needs an ungranted mic', async () => {
        // Mic permission reads as `prompt` -> not granted -> needsPermission.
        (globalThis as Record<string, unknown>).navigator = {
            permissions: {
                query: async ({ name }: { name: string }) => ({
                    state: name === 'microphone' ? 'prompt' : 'granted',
                }),
            },
        };
        harness = new TestSessionHarness({
            config: { defaultAudioEnabled: true, defaultVideoEnabled: false },
            initialMediaRole: 'held',
        });
        await expect(harness.session.preflightForeground()).resolves.toBe('needsPermission');
    });

    it('returns ok when the required mic permission is already granted', async () => {
        (globalThis as Record<string, unknown>).navigator = {
            permissions: {
                query: async () => ({ state: 'granted' }),
            },
        };
        harness = new TestSessionHarness({
            config: { defaultAudioEnabled: true, defaultVideoEnabled: false },
            initialMediaRole: 'held',
        });
        await expect(harness.session.preflightForeground()).resolves.toBe('ok');
    });
});

describe('SerenadaSession.activateForeground fencing (Phase 2)', () => {
    let harness: TestSessionHarness;
    beforeEach(() => { vi.useFakeTimers(); });
    afterEach(() => {
        harness?.destroy();
        vi.useRealTimers();
        __resetForegroundArbiterForTests();
    });

    async function joinHeldAndSettle(): Promise<void> {
        harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }, { cid: 'peer-1' }] });
        await vi.advanceTimersByTimeAsync(0);
        harness.media.installLocalStream({ audio: true, video: true });
    }

    it('drops a late activation with a stale operation generation', async () => {
        harness = new TestSessionHarness({ initialMediaRole: 'held' });
        await joinHeldAndSettle();

        const token = foregroundArbiter.acquireForeground('test-room-id', 'direct', {});
        // Start an activation, but supersede its generation mid-await with a hold.
        const staleGen = foregroundArbiter.nextOperationGeneration();
        const activatePromise = harness.session.activateForeground(token, staleGen);
        // A concurrent un-gated hold bumps the generation past staleGen.
        await harness.session.suspendForHold();
        await activatePromise;

        // The stale activation was dropped: the call stayed held, never foreground.
        expect(harness.session.currentMediaRole).toBe('held');
        const mediaBroadcasts = harness.signaling.broadcastCalls
            .filter((c) => c.type === 'participant_media_state');
        expect(mediaBroadcasts.some((c) => (c.payload as Record<string, unknown>).held === false)).toBe(false);
    });

    it('drops a late activation whose owner token is no longer the lease owner', async () => {
        harness = new TestSessionHarness({ initialMediaRole: 'held' });
        await joinHeldAndSettle();

        // Activate with a token that the arbiter does NOT recognize as the current
        // owner (released right after minting): the token fence drops the callback.
        const staleToken = foregroundArbiter.acquireForeground('test-room-id', 'direct', {});
        foregroundArbiter.releaseLease(staleToken);
        const gen = foregroundArbiter.nextOperationGeneration();

        await expect(harness.session.activateForeground(staleToken, gen)).resolves.toBeUndefined();
        // Token fence: not the current owner -> rolled back to held, no foreground.
        expect(harness.session.currentMediaRole).toBe('held');
    });
});

describe('SerenadaCore.join routes through the arbiter (Phase 2)', () => {
    let restoreRtc: unknown;
    beforeEach(() => {
        vi.useFakeTimers();
        restoreRtc = (globalThis as Record<string, unknown>).RTCPeerConnection;
        (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
    });
    afterEach(() => {
        (globalThis as Record<string, unknown>).RTCPeerConnection = restoreRtc;
        vi.useRealTimers();
        __resetForegroundArbiterForTests();
    });

    it('a second concurrent direct join while one is live fails with ForegroundLeaseUnavailable', () => {
        const coreA = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
        const sessionA = coreA.join({ roomId: 'ROOM_A' });
        try {
            const coreB = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
            expect(() => coreB.join({ roomId: 'ROOM_B' })).toThrow(ForegroundLeaseUnavailable);
        } finally {
            sessionA.destroy();
        }
    });

    it('single-call join/leave releases the lease so a later join reacquires', () => {
        const core = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
        const first = core.join({ roomId: 'ROOM_1' });
        // Lease is held by the live direct join.
        expect(() => foregroundArbiter.acquireForeground('probe', 'direct', {})).toThrow(ForegroundLeaseUnavailable);
        first.leave();
        // After leave(), the lease + mode are released; a fresh direct join works.
        const second = core.join({ roomId: 'ROOM_2' });
        expect(second.state.roomId).toBe('ROOM_2');
        second.destroy();
    });

    it('destroy() also releases the lease (a later direct join reacquires)', () => {
        const core = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
        const first = core.join({ roomId: 'ROOM_1' });
        first.destroy();
        const second = core.join({ roomId: 'ROOM_2' });
        expect(second.state.roomId).toBe('ROOM_2');
        second.destroy();
    });
});

interface FakePc {
    getSenders(): { track: unknown }[];
    getTransceivers(): unknown[];
}
