import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { SerenadaCore, PROVIDER_SINGLE_SESSION_MESSAGE } from '../src/SerenadaCore.js';
import { SerenadaCallRegistry } from '../src/SerenadaCallRegistry.js';
import { SerenadaSession } from '../src/SerenadaSession.js';
import type { MediaEngine } from '../src/media/MediaEngine.js';
import { FakeSignalingProvider } from './helpers/FakeSignalingProvider.js';
import { FakeMediaEngine } from './helpers/FakeMediaEngine.js';
import { __resetForegroundArbiterForTests } from '../src/foregroundArbiter.js';
import { canonicalizeRoomId } from '../src/roomIdentity.js';
import type {
    MultiSessionSignalingProvider,
    SignalingProvider,
} from '../src/SignalingProvider.js';
import type { CallMediaRole, RoomRef, SerenadaConfig } from '../src/types.js';

// window/document/navigator shims (mirror SerenadaCallRegistry.test.ts). Real
// MediaEngine (used by the v1 registry + direct core-level cases) reaches for
// these, and the core seam needs RTCPeerConnection defined so `isSupported()`
// is true.
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
            if (prop === 'location') return { host: '', protocol: 'https:', hostname: '', href: '' };
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

/**
 * A recordable per-session channel vended by {@link FakeMultiSessionService}.
 * Extends the v1 {@link FakeSignalingProvider} (so it satisfies the exact
 * per-session provider surface the session drives) and remembers its bound room.
 */
class FakeChannel extends FakeSignalingProvider {
    constructor(readonly roomId: string) {
        super();
    }
}

/**
 * ONE app-global fake v2 service that vends a fresh recordable channel per
 * `openSession`. Models the F2 contract: the SDK asks for one channel per
 * session and drives it as a normal v1 provider.
 */
class FakeMultiSessionService implements MultiSessionSignalingProvider {
    readonly version = 2 as const;
    readonly openSessionCalls: string[] = [];
    readonly channels: FakeChannel[] = [];
    getIceServersCalls = 0;

    openSession(roomId: string): SignalingProvider {
        this.openSessionCalls.push(roomId);
        const channel = new FakeChannel(roomId);
        this.channels.push(channel);
        return channel;
    }

    async getIceServers(): Promise<RTCIceServer[]> {
        this.getIceServersCalls += 1;
        return [];
    }
}

interface V2Rig {
    session: SerenadaSession;
    channel: FakeChannel;
    media: FakeMediaEngine;
    clientId: string;
}

/**
 * Registry over ONE global fake v2 service. The `createSession` factory mirrors
 * core's F2 seam exactly (`service.openSession(canonicalRoomId)`) but backs the
 * session with a {@link FakeMediaEngine} so the two-session lifecycle runs
 * headless. Core's own routing (`version === 2 -> openSession`) is proven
 * separately in the "core seam" block below.
 */
function makeV2Registry(service: FakeMultiSessionService) {
    const config: SerenadaConfig = { signalingProvider: service };
    const rigs: V2Rig[] = [];
    let cid = 0;
    const core = new SerenadaCore(config);
    const registry = new SerenadaCallRegistry(core, {
        createSession: (room: RoomRef, opts: { initialMediaRole: CallMediaRole; displayName?: string }) => {
            const roomId = 'url' in room ? canonicalizeRoomId(room.url) : room.roomId;
            const roomUrl = 'url' in room ? room.url : null;
            const channel = service.openSession(roomId) as FakeChannel;
            const media = new FakeMediaEngine();
            const clientId = `cid-${cid++}`;
            const session = new SerenadaSession(config, roomId, roomUrl, channel, {
                media: media as unknown as MediaEngine,
                autoStart: false,
                initialMediaRole: opts.initialMediaRole,
            });
            rigs.push({ session, channel, media, clientId });
            return session;
        },
    });
    return { registry, rigs, service };
}

/** Drive a held rig's channel to membership (`waiting`), local participant only. */
function settleJoined(rig: V2Rig): void {
    rig.channel.emitConnected('ws');
    rig.channel.emitJoined({
        peerId: rig.clientId,
        participants: [{ peerId: rig.clientId }],
        hostPeerId: rig.clientId,
    });
    rig.media.installLocalStream({ audio: true, video: true });
}

/** Settle the most-recently created rig on the next microtask (awaitHeldJoin is outside the queue). */
function settleNext(rigs: V2Rig[]): void {
    queueMicrotask(() => {
        const rig = rigs[rigs.length - 1];
        if (rig) settleJoined(rig);
    });
}

describe('MultiSessionSignalingProvider (F2)', () => {
    beforeEach(() => {
        __resetForegroundArbiterForTests();
        (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
    });
    afterEach(() => {
        __resetForegroundArbiterForTests();
        delete (globalThis as Record<string, unknown>).RTCPeerConnection;
    });

    // --- Core seam: version-2 routing (real Core.join path) ---

    describe('core seam', () => {
        it('routes a version-2 provider through openSession(canonicalRoomId), one channel per join', () => {
            const service = new FakeMultiSessionService();
            const core = new SerenadaCore({ signalingProvider: service });

            // Join by URL: openSession receives the CANONICAL room token, and the
            // vended channel is the one the session drives (connect()).
            const a = core.join('https://serenada.app/call/ROOM_A');
            expect(service.openSessionCalls).toEqual(['ROOM_A']);
            expect(service.channels).toHaveLength(1);
            expect(service.channels[0].connectCalls).toBe(1);
            a.destroy();

            // A second (sequential) join vends a fresh, distinct channel.
            const b = core.join({ roomId: 'ROOM_B' });
            expect(service.openSessionCalls).toEqual(['ROOM_A', 'ROOM_B']);
            expect(service.channels).toHaveLength(2);
            expect(service.channels[1]).not.toBe(service.channels[0]);
            expect(service.channels[1].connectCalls).toBe(1);
            b.destroy();
        });
    });

    // --- Registry: two sessions, channel isolation (spec §Tests 1-6) ---

    describe('registry channel isolation', () => {
        it('isolates joined CIDs, room state, peer messages and errors across channels (item 1)', async () => {
            const service = new FakeMultiSessionService();
            const { registry, rigs } = makeV2Registry(service);

            const pa = registry.joinHeld({ roomId: 'room-A' });
            settleNext(rigs);
            await pa;
            const pb = registry.joinHeld({ roomId: 'room-B' });
            settleNext(rigs);
            await pb;

            const [a, b] = rigs;
            // Each session adopted only its own channel's joined CID.
            expect(a.session.state.localParticipant?.cid).toBe('cid-0');
            expect(b.session.state.localParticipant?.cid).toBe('cid-1');
            expect(a.session.state.localParticipant?.cid).not.toBe(b.session.state.localParticipant?.cid);

            // A remote peer on channel A is seen by A only.
            a.channel.emitPeerJoined({ peerId: 'remote-A' });
            expect(a.session.state.remoteParticipants.map((p) => p.cid)).toContain('remote-A');
            expect(b.session.state.remoteParticipants.map((p) => p.cid)).not.toContain('remote-A');

            // A peer message on channel A reaches A's onPeerMessage only.
            const aMessages = vi.fn();
            const bMessages = vi.fn();
            a.session.onPeerMessage(aMessages);
            b.session.onPeerMessage(bMessages);
            a.channel.emitMessage({ from: 'remote-A', type: 'chat', payload: { hi: 1 } });
            expect(aMessages).toHaveBeenCalledTimes(1);
            expect(bMessages).not.toHaveBeenCalled();

            // A signaling error on channel B fails B only.
            b.channel.emitError('CONNECTION_FAILED', 'boom-B');
            expect(b.session.state.phase).toBe('error');
            expect(a.session.state.phase).not.toBe('error');
        });

        it('routes outbound leave/broadcast to the owning channel only (item 2)', async () => {
            const service = new FakeMultiSessionService();
            const { registry, rigs } = makeV2Registry(service);

            const pa = registry.joinHeld({ roomId: 'room-A' });
            settleNext(rigs);
            await pa;
            const pb = registry.joinHeld({ roomId: 'room-B' });
            settleNext(rigs);
            await pb;

            const [a, b] = rigs;
            // Only the settled channel A broadcast its media state on join.
            expect(a.channel.broadcastCalls.length).toBeGreaterThanOrEqual(1);

            const bLeaveBefore = b.channel.leaveRoomCalls;
            await registry.leave(findId(registry, a));
            expect(a.channel.leaveRoomCalls).toBe(1);
            expect(b.channel.leaveRoomCalls).toBe(bLeaveBefore);   // untouched
        });

        it('leaving one call keeps the other channel open and receiving events (item 3)', async () => {
            const service = new FakeMultiSessionService();
            const { registry, rigs } = makeV2Registry(service);

            const pa = registry.joinHeld({ roomId: 'room-A' });
            settleNext(rigs);
            await pa;
            const pb = registry.joinHeld({ roomId: 'room-B' });
            settleNext(rigs);
            await pb;

            const [a, b] = rigs;
            await registry.leave(findId(registry, a));

            // A's channel closed exactly once; B's channel still open.
            expect(a.channel.disconnectCalls).toBe(1);
            expect(b.channel.disconnectCalls).toBe(0);

            // B still receives events after A is gone.
            b.channel.emitPeerJoined({ peerId: 'remote-B' });
            expect(b.session.state.remoteParticipants.map((p) => p.cid)).toContain('remote-B');
        });

        it('scopes reconnect/turn-refresh + ICE state per channel (item 4)', async () => {
            const service = new FakeMultiSessionService();
            const { registry, rigs } = makeV2Registry(service);

            const pa = registry.joinHeld({ roomId: 'room-A' });
            settleNext(rigs);
            await pa;
            const pb = registry.joinHeld({ roomId: 'room-B' });
            settleNext(rigs);
            await pb;

            const [a, b] = rigs;
            // Each session installed its OWN TURN-refresh gate on its OWN channel.
            expect(a.channel.turnRefreshGate).not.toBeNull();
            expect(b.channel.turnRefreshGate).not.toBeNull();
            expect(a.channel.turnRefreshGate).not.toBe(b.channel.turnRefreshGate);

            // getIceServers counters are independent per channel: a call on A's
            // channel never touches B's counter.
            const aBefore = a.channel.getIceServersCalls;
            const bBefore = b.channel.getIceServersCalls;
            await a.channel.getIceServers();
            expect(a.channel.getIceServersCalls).toBe(aBefore + 1);
            expect(b.channel.getIceServersCalls).toBe(bBefore);
        });

        it('opens one channel per session with the canonical roomId and closes each once (item 5)', async () => {
            const service = new FakeMultiSessionService();
            const { registry, rigs } = makeV2Registry(service);

            const pa = registry.joinHeld({ url: 'https://serenada.app/call/room-A' });
            settleNext(rigs);
            await pa;
            const pb = registry.joinHeld({ roomId: 'room-B' });
            settleNext(rigs);
            await pb;

            // One openSession per session, each with the canonical room token.
            expect(service.openSessionCalls).toEqual(['room-A', 'room-B']);
            expect(service.channels).toHaveLength(2);

            const [a] = rigs;
            await registry.leave(findId(registry, a));
            expect(a.channel.disconnectCalls).toBe(1);
            // Idempotence: a second close is tolerated (does not throw).
            expect(() => a.channel.disconnect()).not.toThrow();
        });

        it('does not deliver a stale event emitted into a closed channel (item 6)', async () => {
            const service = new FakeMultiSessionService();
            const { registry, rigs } = makeV2Registry(service);

            const pa = registry.joinHeld({ roomId: 'room-A' });
            settleNext(rigs);
            await pa;

            const [a] = rigs;
            const messages = vi.fn();
            a.session.onPeerMessage(messages);

            await registry.leave(findId(registry, a));   // teardown unbinds handlers first (off before disconnect)

            // A stale event into the now-closed channel reaches no session handler.
            a.channel.emitMessage({ from: 'ghost', type: 'chat', payload: {} });
            a.channel.emitPeerJoined({ peerId: 'ghost' });
            expect(messages).not.toHaveBeenCalled();
        });
    });

    // --- v1 single-session liveness guard (spec §Tests 7) ---

    describe('v1 single-session liveness guard', () => {
        it('registry: a second concurrent v1 session fails typed; the first is unaffected', async () => {
            const provider = new FakeSignalingProvider();
            const core = new SerenadaCore({ signalingProvider: provider });
            const registry = new SerenadaCallRegistry(core);   // DEFAULT createSession -> real core seam

            // Both creates enqueue; A binds the v1 provider, B's create then throws.
            const pa = registry.joinHeld({ roomId: 'room-A' });
            const pb = registry.joinHeld({ roomId: 'room-B' });

            // Settle A's held join (A's session bound its handlers on the provider
            // via the liveness channel, so an emit on the provider reaches it).
            queueMicrotask(() => {
                provider.emitConnected('ws');
                provider.emitJoined({ peerId: 'cid-A', participants: [{ peerId: 'cid-A' }], hostPeerId: 'cid-A' });
            });

            const [ra, rb] = await Promise.all([pa, pb]);
            expect(ra.kind).toBe('joined');
            expect(rb.kind).toBe('failed');
            if (rb.kind === 'failed') {
                expect(rb.error.kind).toBe('joinFailed');
                expect(rb.error.message).toBe(PROVIDER_SINGLE_SESSION_MESSAGE);
            }
            // First call still live.
            expect(registry.state.calls).toHaveLength(1);
        });

        it('registry: sequential v1 reuse works after the first call is left', async () => {
            const provider = new FakeSignalingProvider();
            const core = new SerenadaCore({ signalingProvider: provider });
            const registry = new SerenadaCallRegistry(core);

            const pa = registry.joinHeld({ roomId: 'room-A' });
            queueMicrotask(() => {
                provider.emitConnected('ws');
                provider.emitJoined({ peerId: 'cid-A', participants: [{ peerId: 'cid-A' }], hostPeerId: 'cid-A' });
            });
            const ra = await pa;
            expect(ra.kind).toBe('joined');
            if (ra.kind === 'joined') await registry.leave(ra.callId);

            // The v1 provider is free again: a fresh session may bind it.
            const pb = registry.joinHeld({ roomId: 'room-B' });
            queueMicrotask(() => {
                provider.emitConnected('ws');
                provider.emitJoined({ peerId: 'cid-B', participants: [{ peerId: 'cid-B' }], hostPeerId: 'cid-B' });
            });
            const rb = await pb;
            expect(rb.kind).toBe('joined');
        });

        it('direct: a second concurrent v1 join surfaces an error CallState; the first is unaffected', () => {
            const provider = new FakeSignalingProvider();
            const core = new SerenadaCore({ signalingProvider: provider });

            const a = core.join({ roomId: 'room-A' });
            expect(a.state.phase).not.toBe('error');

            const b = core.join({ roomId: 'room-B' });
            expect(b.state.phase).toBe('error');
            expect(b.state.error?.code).toBe('providerUnavailable');
            expect(b.state.error?.message).toBe(PROVIDER_SINGLE_SESSION_MESSAGE);
            expect(b.state.roomId).toBe('room-B');

            // First join untouched.
            expect(a.state.phase).not.toBe('error');
            a.destroy();
        });

        it('direct: sequential v1 reuse works after the first session is destroyed', () => {
            const provider = new FakeSignalingProvider();
            const core = new SerenadaCore({ signalingProvider: provider });

            const a = core.join({ roomId: 'room-A' });
            expect(a.state.phase).not.toBe('error');
            a.destroy();

            const b = core.join({ roomId: 'room-B' });
            expect(b.state.phase).not.toBe('error');
            b.destroy();
        });

        it('direct: a retired session (terminal reset) never disconnects a reused provider or leaks events', () => {
            // Session A binds the v1 provider, then hits a TERMINAL reset WITHOUT
            // destroy() — which releases the liveness bind (resetSessionResources
            // -> channel.disconnect()). A newer session B then rebinds the SAME
            // provider object. A's later destroy()/leave() must not disconnect B's
            // transport, and A's stale handlers must not fire.
            const provider = new FakeSignalingProvider();
            const coreA = new SerenadaCore({ signalingProvider: provider });

            const a = coreA.join({ roomId: 'room-A' });
            expect(a.state.phase).not.toBe('error');

            // Drive A to a terminal error: failWithError -> resetSessionResources
            // -> disconnect() retires A's channel and releases the bind.
            provider.emitError('CONNECTION_FAILED', 'boom-A');
            expect(a.state.phase).toBe('error');
            const disconnectsAfterReset = provider.disconnectCalls;   // exactly A's reset

            // A second core rebinds the SAME provider (bind was released) and B joins.
            const coreB = new SerenadaCore({ signalingProvider: provider });
            const b = coreB.join({ roomId: 'room-B' });
            expect(b.state.phase).not.toBe('error');
            provider.emitConnected('ws');
            provider.emitJoined({ peerId: 'cid-B', participants: [{ peerId: 'cid-B' }], hostPeerId: 'cid-B' });
            const bMessages = vi.fn();
            b.onPeerMessage(bMessages);

            // Destroy the OLD session A: its retired channel must NOT forward
            // disconnect() to the provider B now owns.
            a.destroy();
            expect(provider.disconnectCalls).toBe(disconnectsAfterReset);

            // B still receives provider events after A's destroy (A's handlers
            // were detached on retire, so no cross-wiring into the dead session).
            provider.emitMessage({ from: 'remote-B', type: 'chat', payload: { hi: 1 } });
            expect(bMessages).toHaveBeenCalledTimes(1);

            // Double-destroy of A is safe and still does not touch the provider.
            expect(() => a.destroy()).not.toThrow();
            expect(provider.disconnectCalls).toBe(disconnectsAfterReset);

            b.destroy();
        });

        it('direct: two cores sharing one v1 provider object cannot both bind (guard is per-provider, not per-core)', () => {
            // The single-session contract is a property of the PROVIDER object:
            // two cores configured with the SAME v1 provider must not both bind it.
            const provider = new FakeSignalingProvider();
            const coreA = new SerenadaCore({ signalingProvider: provider });
            const coreB = new SerenadaCore({ signalingProvider: provider });

            const a = coreA.join({ roomId: 'room-A' });
            expect(a.state.phase).not.toBe('error');

            // Second core, SAME provider object: the process-wide identity guard
            // refuses with the identical typed failure.
            const b = coreB.join({ roomId: 'room-B' });
            expect(b.state.phase).toBe('error');
            expect(b.state.error?.code).toBe('providerUnavailable');
            expect(b.state.error?.message).toBe(PROVIDER_SINGLE_SESSION_MESSAGE);

            // Release the first core's bind: the second core can now reuse the
            // provider (sequential reuse across cores).
            a.destroy();
            const b2 = coreB.join({ roomId: 'room-B' });
            expect(b2.state.phase).not.toBe('error');
            b2.destroy();
        });
    });
});

/** Find the managed callId for a rig's session in the registry state. */
function findId(registry: SerenadaCallRegistry, rig: V2Rig): string {
    const match = registry.state.calls.find((c) => registry.sessionFor(c.id) === rig.session);
    if (!match) throw new Error('managed call not found for rig');
    return match.id;
}
