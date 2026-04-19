import type { CallState, SerenadaConfig } from '../../src/types.js';
import type { RoomState } from '../../src/signaling/types.js';
import type { MediaEngine } from '../../src/media/MediaEngine.js';
import type { CallStatsCollector } from '../../src/media/callStats.js';
import { SerenadaSession } from '../../src/SerenadaSession.js';
import { FakeSignalingProvider } from './FakeSignalingProvider.js';
import { FakeMediaEngine } from './FakeMediaEngine.js';

class FakeStatsCollector {
    stats: null = null;
    start(): void { /* no-op */ }
    stop(): void { /* no-op */ }
}

export interface TestSessionOptions {
    config?: Partial<SerenadaConfig>;
    roomId?: string;
    roomUrl?: string | null;
    handlesReconnection?: boolean;
    autoStart?: boolean;
    displayName?: string;
}

/**
 * Creates a SerenadaSession wired to FakeSignalingProvider + FakeMediaEngine.
 * Provides convenience methods to simulate signaling state changes.
 */
export class TestSessionHarness {
    readonly signaling: FakeSignalingProvider;
    readonly media: FakeMediaEngine;
    readonly session: SerenadaSession;
    readonly stateHistory: CallState[] = [];

    private unsubscribe: (() => void) | null = null;

    constructor(options: TestSessionOptions = {}) {
        const config: SerenadaConfig = {
            serverHost: 'localhost:8080',
            ...options.config,
        };
        const roomId = options.roomId ?? 'test-room-id';
        const roomUrl = options.roomUrl ?? 'https://serenada.app/call/test-room-id';

        this.signaling = new FakeSignalingProvider({
            handlesReconnection: options.handlesReconnection ?? true,
        });
        this.media = new FakeMediaEngine();

        this.session = new SerenadaSession(config, roomId, roomUrl, this.signaling, {
            media: this.media as unknown as MediaEngine,
            statsCollector: new FakeStatsCollector() as unknown as CallStatsCollector,
            autoStart: options.autoStart ?? false,
            displayName: options.displayName,
        });

        this.unsubscribe = this.session.subscribe((state) => {
            this.stateHistory.push(state);
        });
    }

    get state(): CallState {
        return this.session.state;
    }

    simulateJoined(opts: {
        clientId?: string;
        participants?: { cid: string; joinedAt?: number; displayName?: string }[];
        hostCid?: string | null;
    } = {}): void {
        const clientId = opts.clientId ?? 'my-cid';
        const participants = opts.participants ?? [{ cid: clientId }];
        const hostCid = opts.hostCid ?? clientId;

        this.signaling.emitConnected('ws');
        this.signaling.emitJoined({
            peerId: clientId,
            participants: participants.map((participant) => ({
                peerId: participant.cid,
                joinedAt: participant.joinedAt,
                displayName: participant.displayName,
            })),
            hostPeerId: hostCid ?? undefined,
        });
    }

    simulateRoomStateUpdate(roomState: RoomState): void {
        this.signaling.emitRoomStateUpdated({
            hostPeerId: roomState.hostCid ?? undefined,
            participants: roomState.participants.map((participant) => ({
                peerId: participant.cid,
                joinedAt: participant.joinedAt,
                connectionStatus: participant.connectionStatus,
            })),
            maxParticipants: roomState.maxParticipants,
        });
    }

    simulateError(message: string, code = 'UNKNOWN'): void {
        this.signaling.emitError(code, message);
    }

    simulateDisconnect(): void {
        this.signaling.emitDisconnected('test');
    }

    simulateRoomEnded(): void {
        this.signaling.emitRoomEnded();
    }

    destroy(): void {
        this.unsubscribe?.();
        this.unsubscribe = null;
        this.session.destroy();
    }
}
