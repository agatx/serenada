import type { CallState, SerenadaConfig } from '../../src/types.js';
import type { RoomState } from '../../src/signaling/types.js';
import type { SignalingEngine } from '../../src/signaling/SignalingEngine.js';
import type { MediaEngine } from '../../src/media/MediaEngine.js';
import type { CallStatsCollector } from '../../src/media/callStats.js';
import { SerenadaSession } from '../../src/SerenadaSession.js';
import { FakeSignalingEngine } from './FakeSignalingEngine.js';
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
}

/**
 * Creates a SerenadaSession wired to FakeSignalingEngine + FakeMediaEngine.
 * Provides convenience methods to simulate signaling state changes.
 */
export class TestSessionHarness {
    readonly signaling: FakeSignalingEngine;
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

        this.signaling = new FakeSignalingEngine();
        this.media = new FakeMediaEngine();

        this.session = new SerenadaSession(config, roomId, roomUrl, {
            signaling: this.signaling as unknown as SignalingEngine,
            media: this.media as unknown as MediaEngine,
            statsCollector: new FakeStatsCollector() as unknown as CallStatsCollector,
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
        participants?: { cid: string; joinedAt?: number }[];
        hostCid?: string | null;
    } = {}): void {
        const clientId = opts.clientId ?? 'my-cid';
        const participants = opts.participants ?? [{ cid: clientId }];
        const hostCid = opts.hostCid ?? clientId;

        this.signaling.emit({
            isConnected: true,
            activeTransport: 'ws',
            clientId,
            roomState: { hostCid, participants },
        });
    }

    simulateRoomStateUpdate(roomState: RoomState): void {
        this.signaling.emit({ roomState });
    }

    simulateError(message: string): void {
        this.signaling.emit({ error: message });
    }

    simulateDisconnect(): void {
        this.signaling.emit({
            isConnected: false,
            activeTransport: null,
        });
    }

    simulateRoomEnded(): void {
        this.signaling.emit({ roomState: null });
    }

    destroy(): void {
        this.unsubscribe?.();
        this.unsubscribe = null;
        this.session.destroy();
    }
}
