import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SignalingMessage } from '../src/signaling/types.js';

const { mockEngineInstances, MockSignalingEngine } = vi.hoisted(() => {
    const mockEngineInstances: unknown[] = [];

    class MockSignalingEngine {
        isConnected = false;
        activeTransport: 'ws' | 'sse' | null = null;
        joinRoomCalls: Array<{ roomId: string; options?: { createMaxParticipants?: number } }> = [];
        sendMessageCalls: Array<{ type: string; payload: Record<string, unknown> | undefined; to?: string }> = [];
        destroyCalls = 0;
        leaveRoomCalls = 0;
        endRoomCalls = 0;
        private readonly stateListeners = new Set<() => void>();
        private readonly messageListeners = new Set<(message: SignalingMessage) => void>();

        constructor() {
            mockEngineInstances.push(this);
        }

        connect(): void {
            // no-op
        }

        destroy(): void {
            this.destroyCalls += 1;
        }

        joinRoom(roomId: string, options?: { createMaxParticipants?: number }): void {
            this.joinRoomCalls.push({ roomId, options });
        }

        leaveRoom(): void {
            this.leaveRoomCalls += 1;
        }

        endRoom(): void {
            this.endRoomCalls += 1;
        }

        sendMessage(type: string, payload?: Record<string, unknown>, to?: string): void {
            this.sendMessageCalls.push({ type, payload, to });
        }

        onStateChange(listener: () => void): () => void {
            this.stateListeners.add(listener);
            return () => {
                this.stateListeners.delete(listener);
            };
        }

        subscribeToMessages(listener: (message: SignalingMessage) => void): () => void {
            this.messageListeners.add(listener);
            return () => {
                this.messageListeners.delete(listener);
            };
        }

        emitState(partial: Partial<Pick<MockSignalingEngine, 'isConnected' | 'activeTransport'>>): void {
            Object.assign(this, partial);
            for (const listener of this.stateListeners) {
                listener();
            }
        }

        emitMessage(message: SignalingMessage): void {
            for (const listener of this.messageListeners) {
                listener(message);
            }
        }
    }

    return { mockEngineInstances, MockSignalingEngine };
});

vi.mock('../src/signaling/SignalingEngine.js', () => ({
    SignalingEngine: MockSignalingEngine,
}));

import { SerenadaServerProvider } from '../src/SerenadaServerProvider.js';

describe('SerenadaServerProvider', () => {
    beforeEach(() => {
        mockEngineInstances.length = 0;
        vi.restoreAllMocks();
    });

    it('routes connected state changes and join options to the signaling engine', () => {
        const provider = new SerenadaServerProvider({ serverHost: 'serenada.app' });
        const engine = mockEngineInstances[0] as InstanceType<typeof MockSignalingEngine>;
        const connected = vi.fn();

        provider.on('connected', connected);
        engine.emitState({ isConnected: true, activeTransport: 'sse' });
        provider.joinRoom('room-1', { maxParticipants: 6 });

        expect(connected).toHaveBeenCalledWith({ transport: 'sse' });
        expect(engine.joinRoomCalls).toEqual([
            {
                roomId: 'room-1',
                options: { createMaxParticipants: 6 },
            },
        ]);
    });

    it('emits participant diffs and parses room_ended payload fields', () => {
        const provider = new SerenadaServerProvider({ serverHost: 'serenada.app' });
        const engine = mockEngineInstances[0] as InstanceType<typeof MockSignalingEngine>;
        const joined = vi.fn();
        const peerJoined = vi.fn();
        const peerLeft = vi.fn();
        const roomStateUpdated = vi.fn();
        const roomEnded = vi.fn();

        provider.on('joined', joined);
        provider.on('peerJoined', peerJoined);
        provider.on('peerLeft', peerLeft);
        provider.on('roomStateUpdated', roomStateUpdated);
        provider.on('roomEnded', roomEnded);

        engine.emitMessage({
            v: 1,
            type: 'joined',
            rid: 'room-1',
            cid: 'local-cid',
            payload: {
                hostCid: 'local-cid',
                participants: [
                    { cid: 'local-cid', joinedAt: 1 },
                    { cid: 'peer-a', joinedAt: 2 },
                ],
            },
        });

        engine.emitMessage({
            v: 1,
            type: 'room_state',
            rid: 'room-1',
            payload: {
                hostCid: 'peer-b',
                participants: [
                    { cid: 'local-cid', joinedAt: 1 },
                    { cid: 'peer-b', joinedAt: 3 },
                ],
            },
        });

        engine.emitMessage({
            v: 1,
            type: 'room_ended',
            rid: 'room-1',
            payload: {
                by: 'peer-b',
                reason: 'host ended',
            },
        });

        expect(joined).toHaveBeenCalledWith({
            peerId: 'local-cid',
            participants: [
                { peerId: 'local-cid', joinedAt: 1 },
                { peerId: 'peer-a', joinedAt: 2 },
            ],
            hostPeerId: 'local-cid',
            maxParticipants: undefined,
        });
        expect(peerJoined).toHaveBeenCalledWith({ peerId: 'peer-b', joinedAt: 3 });
        expect(peerLeft).toHaveBeenCalledWith({ peerId: 'peer-a', joinedAt: 2 });
        expect(roomStateUpdated).toHaveBeenCalledWith({
            participants: [
                { peerId: 'local-cid', joinedAt: 1 },
                { peerId: 'peer-b', joinedAt: 3 },
            ],
            hostPeerId: 'peer-b',
            maxParticipants: undefined,
        });
        expect(roomEnded).toHaveBeenCalledWith({ by: 'peer-b', reason: 'host ended' });
    });

    it('refreshes ICE servers from turn-refreshed and forwards peer messages', async () => {
        const fetchMock = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({
                username: 'turn-user',
                password: 'turn-pass',
                uris: ['turn:turn.example.com:3478'],
            }),
        });
        vi.stubGlobal('fetch', fetchMock);

        const provider = new SerenadaServerProvider({ serverHost: 'serenada.app' });
        const engine = mockEngineInstances[0] as InstanceType<typeof MockSignalingEngine>;
        const iceServersChanged = vi.fn();
        const messages = vi.fn();

        provider.on('iceServersChanged', iceServersChanged);
        provider.on('message', messages);

        engine.emitMessage({
            v: 1,
            type: 'turn-refreshed',
            rid: 'room-1',
            payload: {
                turnToken: 'turn-token',
            },
        });

        await vi.waitFor(() => {
            expect(fetchMock).toHaveBeenCalledTimes(1);
        });
        await vi.waitFor(() => {
            expect(iceServersChanged).toHaveBeenCalledWith([
                {
                    urls: ['turn:turn.example.com:3478'],
                    username: 'turn-user',
                    credential: 'turn-pass',
                },
            ]);
        });

        engine.emitMessage({
            v: 1,
            type: 'offer',
            rid: 'room-1',
            payload: {
                from: 'peer-1',
                sdp: 'offer-sdp',
            },
        });

        expect(messages).toHaveBeenCalledWith({
            from: 'peer-1',
            type: 'offer',
            payload: {
                from: 'peer-1',
                sdp: 'offer-sdp',
            },
        });

        messages.mockClear();
        engine.emitMessage({
            v: 1,
            type: 'media_restart_request',
            rid: 'room-1',
            payload: {
                from: 'peer-1',
                reason: 'stalled outbound media',
            },
        });

        expect(messages).toHaveBeenCalledWith({
            from: 'peer-1',
            type: 'media_restart_request',
            payload: {
                from: 'peer-1',
                reason: 'stalled outbound media',
            },
        });
    });

    it('omits roomEnded.by when neither payload nor room state provide an owner', () => {
        const provider = new SerenadaServerProvider({ serverHost: 'serenada.app' });
        const engine = mockEngineInstances[0] as InstanceType<typeof MockSignalingEngine>;
        const roomEnded = vi.fn();

        provider.on('roomEnded', roomEnded);

        engine.emitMessage({
            v: 1,
            type: 'room_ended',
            rid: 'room-1',
            payload: {
                reason: 'host ended',
            },
        });

        expect(roomEnded).toHaveBeenCalledWith({ reason: 'host ended' });
    });

    it('forwards sendToPeer and broadcast messages to the signaling engine', () => {
        const provider = new SerenadaServerProvider({ serverHost: 'serenada.app' });
        const engine = mockEngineInstances[0] as InstanceType<typeof MockSignalingEngine>;

        provider.sendToPeer('peer-1', 'offer', { sdp: 'offer-sdp' });
        provider.broadcast('content_state', { active: true });

        expect(engine.sendMessageCalls).toEqual([
            {
                type: 'offer',
                payload: { sdp: 'offer-sdp' },
                to: 'peer-1',
            },
            {
                type: 'content_state',
                payload: { active: true },
                to: undefined,
            },
        ]);
    });
});
