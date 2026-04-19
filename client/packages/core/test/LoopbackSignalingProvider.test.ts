/**
 * End-to-end test validating the SignalingProvider contract with a
 * LoopbackSignalingProvider — an in-memory provider that routes messages
 * between two SerenadaSession instances without any server.
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import {
    SignalingProviderEmitter,
    type JoinOptions,
    type ProviderCapabilities,
} from '../src/SignalingProvider.js';
import type { CallState, SerenadaConfig } from '../src/types.js';
import type { MediaEngine } from '../src/media/MediaEngine.js';
import type { CallStatsCollector } from '../src/media/callStats.js';
import { SerenadaSession } from '../src/SerenadaSession.js';
import { FakeMediaEngine } from './helpers/FakeMediaEngine.js';


// ---------------------------------------------------------------------------
// Node environment shims (same as SerenadaSession.test.ts)
// ---------------------------------------------------------------------------

if (typeof globalThis.window === 'undefined') {
    const handler: ProxyHandler<Record<string, unknown>> = {
        get(_target, prop) {
            if (prop === 'setTimeout') return globalThis.setTimeout.bind(globalThis);
            if (prop === 'clearTimeout') return globalThis.clearTimeout.bind(globalThis);
            if (prop === 'setInterval') return globalThis.setInterval.bind(globalThis);
            if (prop === 'clearInterval') return globalThis.clearInterval.bind(globalThis);
            return undefined;
        },
    };
    (globalThis as Record<string, unknown>).window = new Proxy({}, handler);
}

if (typeof globalThis.navigator === 'undefined') {
    (globalThis as Record<string, unknown>).navigator = {};
}

// ---------------------------------------------------------------------------
// LoopbackRoom — shared in-memory room state
// ---------------------------------------------------------------------------

class LoopbackRoom {
    private participants = new Map<string, LoopbackSignalingProvider>();
    private hostPeerId: string | null = null;

    join(provider: LoopbackSignalingProvider): void {
        const peerId = provider.peerId;
        this.participants.set(peerId, provider);
        if (!this.hostPeerId) this.hostPeerId = peerId;

        const participants = [...this.participants.keys()].map((id, i) => ({
            peerId: id,
            joinedAt: i + 1,
        }));

        // Notify existing participants about the new peer.
        for (const [existingId, existing] of this.participants) {
            if (existingId !== peerId) {
                existing.deliverPeerJoined({ peerId, joinedAt: participants.length });
            }
        }

        // Tell the joining provider it has joined.
        provider.deliverJoined({
            peerId,
            participants,
            hostPeerId: this.hostPeerId!,
            maxParticipants: 4,
        });
    }

    routeToPeer(from: string, to: string, type: string, payload: unknown): void {
        this.participants.get(to)?.deliverMessage({ from, type, payload });
    }

    routeBroadcast(from: string, type: string, payload: unknown): void {
        for (const [peerId, provider] of this.participants) {
            if (peerId !== from) {
                provider.deliverMessage({ from, type, payload });
            }
        }
    }

    leave(peerId: string): void {
        this.participants.delete(peerId);
        if (this.hostPeerId === peerId && this.participants.size > 0) {
            this.hostPeerId = this.participants.keys().next().value!;
        }
        for (const [, provider] of this.participants) {
            provider.deliverPeerLeft({ peerId });
        }
    }

    end(by: string): void {
        for (const [, provider] of this.participants) {
            provider.deliverRoomEnded({ by, reason: 'host_ended' });
        }
        this.participants.clear();
        this.hostPeerId = null;
    }
}

// ---------------------------------------------------------------------------
// LoopbackSignalingProvider — routes through LoopbackRoom
// ---------------------------------------------------------------------------

class LoopbackSignalingProvider extends SignalingProviderEmitter {
    override readonly capabilities: ProviderCapabilities = { handlesReconnection: true };
    readonly peerId: string;
    private readonly room: LoopbackRoom;
    private currentRoomId: string | null = null;

    constructor(room: LoopbackRoom, peerId: string) {
        super();
        this.room = room;
        this.peerId = peerId;
    }

    override connect(): void {
        this.emit('connected', { transport: 'loopback' });
    }

    override disconnect(): void {
        if (this.currentRoomId) {
            this.room.leave(this.peerId);
            this.currentRoomId = null;
        }
    }

    override joinRoom(_roomId: string, _options?: JoinOptions): void {
        this.currentRoomId = _roomId;
        this.room.join(this);
    }

    override leaveRoom(): void {
        if (this.currentRoomId) {
            this.room.leave(this.peerId);
            this.currentRoomId = null;
        }
    }

    override endRoom(): void {
        this.room.end(this.peerId);
        this.currentRoomId = null;
    }

    override sendToPeer(peerId: string, type: string, payload: unknown): void {
        this.room.routeToPeer(this.peerId, peerId, type, payload);
    }

    override broadcast(type: string, payload: unknown): void {
        this.room.routeBroadcast(this.peerId, type, payload);
    }

    override async getIceServers(): Promise<RTCIceServer[]> {
        return [];
    }

    // --- Delivery methods called by LoopbackRoom ---

    deliverJoined(event: {
        peerId: string;
        participants: Array<{ peerId: string; joinedAt?: number }>;
        hostPeerId: string;
        maxParticipants: number;
    }): void {
        this.emit('joined', event);
    }

    deliverPeerJoined(event: { peerId: string; joinedAt?: number }): void {
        this.emit('peerJoined', event);
    }

    deliverPeerLeft(event: { peerId: string }): void {
        this.emit('peerLeft', event);
    }

    deliverMessage(message: { from: string; type: string; payload: unknown }): void {
        this.emit('message', message);
    }

    deliverRoomEnded(event: { by: string; reason: string }): void {
        this.emit('roomEnded', event);
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

class FakeStatsCollector {
    stats: null = null;
    start(): void { /* no-op */ }
    stop(): void { /* no-op */ }
}

interface SessionBundle {
    provider: LoopbackSignalingProvider;
    media: FakeMediaEngine;
    session: SerenadaSession;
    states: CallState[];
    destroy: () => void;
}

function createSession(
    provider: LoopbackSignalingProvider,
    roomId: string,
    autoStart: boolean,
): SessionBundle {
    const config: SerenadaConfig = { serverHost: null as unknown as string };
    const media = new FakeMediaEngine();
    const states: CallState[] = [];

    const session = new SerenadaSession(config, roomId, null, provider, {
        media: media as unknown as MediaEngine,
        statsCollector: new FakeStatsCollector() as unknown as CallStatsCollector,
        autoStart,
    });

    const unsubscribe = session.subscribe((s) => states.push(s));

    return {
        provider,
        media,
        session,
        states,
        destroy: () => { unsubscribe(); session.destroy(); },
    };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('LoopbackSignalingProvider', () => {
    let room: LoopbackRoom;
    let alice: SessionBundle;
    let bob: SessionBundle;

    beforeEach(() => {
        vi.useFakeTimers();
        room = new LoopbackRoom();
    });

    afterEach(() => {
        alice?.destroy();
        bob?.destroy();
        vi.useRealTimers();
    });

    it('session joins alone and reaches waiting phase', async () => {
        const provider = new LoopbackSignalingProvider(room, 'alice');
        alice = createSession(provider, 'room-1', true);

        // Let the async permission check resolve.
        await vi.advanceTimersByTimeAsync(0);

        expect(alice.session.state.phase).toBe('waiting');
        expect(alice.session.state.localParticipant?.cid).toBe('alice');
        expect(alice.session.state.remoteParticipants).toHaveLength(0);
        expect(alice.session.state.activeTransport).toBe('loopback');
    });

    it('two sessions join and both reach inCall phase', async () => {
        const providerA = new LoopbackSignalingProvider(room, 'alice');
        const providerB = new LoopbackSignalingProvider(room, 'bob');

        alice = createSession(providerA, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);
        expect(alice.session.state.phase).toBe('waiting');

        bob = createSession(providerB, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);

        expect(alice.session.state.phase).toBe('inCall');
        expect(alice.session.state.remoteParticipants).toHaveLength(1);
        expect(alice.session.state.remoteParticipants[0].cid).toBe('bob');

        expect(bob.session.state.phase).toBe('inCall');
        expect(bob.session.state.localParticipant?.cid).toBe('bob');
        expect(bob.session.state.remoteParticipants).toHaveLength(1);
        expect(bob.session.state.remoteParticipants[0].cid).toBe('alice');
    });

    it('sendToPeer routes messages between sessions', async () => {
        const providerA = new LoopbackSignalingProvider(room, 'alice');
        const providerB = new LoopbackSignalingProvider(room, 'bob');

        alice = createSession(providerA, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);
        bob = createSession(providerB, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);

        // Alice sends an offer to Bob via the provider.
        providerA.sendToPeer('bob', 'offer', { sdp: 'alice-offer' });

        // Bob's session should have forwarded it to the media engine.
        expect(bob.media.processSignalingMessageCalls).toHaveLength(1);
        const msg = bob.media.processSignalingMessageCalls[0];
        expect(msg.type).toBe('offer');
        expect(msg.cid).toBe('alice');
        expect(msg.payload).toEqual({ from: 'alice', sdp: 'alice-offer' });

        // Alice should NOT have received her own message.
        expect(alice.media.processSignalingMessageCalls).toHaveLength(0);
    });

    it('broadcast routes to all other sessions', async () => {
        const providerA = new LoopbackSignalingProvider(room, 'alice');
        const providerB = new LoopbackSignalingProvider(room, 'bob');

        alice = createSession(providerA, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);
        bob = createSession(providerB, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);

        providerA.broadcast('content_state', { active: true });

        expect(bob.media.processSignalingMessageCalls).toHaveLength(1);
        expect(bob.media.processSignalingMessageCalls[0].type).toBe('content_state');
        expect(alice.media.processSignalingMessageCalls).toHaveLength(0);
    });

    it('peer leaving transitions remaining session back to waiting', async () => {
        const providerA = new LoopbackSignalingProvider(room, 'alice');
        const providerB = new LoopbackSignalingProvider(room, 'bob');

        alice = createSession(providerA, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);
        bob = createSession(providerB, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);

        expect(alice.session.state.phase).toBe('inCall');

        // Bob leaves.
        providerB.leaveRoom();

        expect(alice.session.state.phase).toBe('waiting');
        expect(alice.session.state.remoteParticipants).toHaveLength(0);
    });

    it('endRoom transitions both sessions to ending phase', async () => {
        const providerA = new LoopbackSignalingProvider(room, 'alice');
        const providerB = new LoopbackSignalingProvider(room, 'bob');

        alice = createSession(providerA, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);
        bob = createSession(providerB, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);

        // Host (alice) ends the room.
        providerA.endRoom();

        expect(alice.session.state.phase).toBe('ending');
        expect(bob.session.state.phase).toBe('ending');
    });

    it('custom messages via onPeerMessage reach the other session', async () => {
        const providerA = new LoopbackSignalingProvider(room, 'alice');
        const providerB = new LoopbackSignalingProvider(room, 'bob');

        alice = createSession(providerA, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);
        bob = createSession(providerB, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);

        const received: Array<{ from: string; type: string; payload: unknown }> = [];
        bob.session.onPeerMessage((msg) => received.push(msg));

        providerA.sendToPeer('bob', 'custom_event', { data: 42 });

        expect(received).toHaveLength(1);
        expect(received[0]).toEqual({
            from: 'alice',
            type: 'custom_event',
            payload: { data: 42 },
        });
    });

    it('media engine receives room state updates for each join', async () => {
        const providerA = new LoopbackSignalingProvider(room, 'alice');
        const providerB = new LoopbackSignalingProvider(room, 'bob');

        alice = createSession(providerA, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);

        // Alice's media engine should have been told about the initial room state.
        const aliceRoomCalls = alice.media.updateRoomStateCalls;
        expect(aliceRoomCalls.length).toBeGreaterThanOrEqual(1);
        expect(aliceRoomCalls[0].state?.participants).toEqual([{ cid: 'alice', joinedAt: 1 }]);
        expect(aliceRoomCalls[0].clientId).toBe('alice');

        bob = createSession(providerB, 'room-1', true);
        await vi.advanceTimersByTimeAsync(0);

        // Alice's media engine should now know about both participants.
        const lastAliceCall = aliceRoomCalls.at(-1)!;
        expect(lastAliceCall.state?.participants).toEqual(
            expect.arrayContaining([
                expect.objectContaining({ cid: 'alice' }),
                expect.objectContaining({ cid: 'bob' }),
            ]),
        );
    });
});
