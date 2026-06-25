import type { CallState, CallStats, ConnectionEvent, SerenadaConfig } from '../../src/types.js';
import type { RoomParticipant, RoomState } from '../../src/signaling/types.js';
import type { MediaEngine } from '../../src/media/MediaEngine.js';
import type { CallStatsCollector } from '../../src/media/callStats.js';
import { SerenadaSession } from '../../src/SerenadaSession.js';
import { FakeSignalingProvider } from './FakeSignalingProvider.js';
import { FakeMediaEngine } from './FakeMediaEngine.js';

/**
 * Fake stats collector that lets tests inject snapshots. `emit()` sets the
 * current `stats` and fires the session's `onChange` so the quality tracker
 * is fed exactly as it would be in production.
 */
export class FakeStatsCollector {
    stats: CallStats | null = null;
    started = false;
    private onChange: (() => void) | null = null;

    start(_getPeerConnections: () => RTCPeerConnection[], onChange: () => void): void {
        this.started = true;
        this.onChange = onChange;
    }

    stop(): void {
        this.started = false;
        this.stats = null;
        this.onChange = null;
    }

    /** Inject a stats snapshot and notify the session (drives the tracker). */
    emit(stats: CallStats): void {
        this.stats = stats;
        this.onChange?.();
    }
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
    readonly statsCollector: FakeStatsCollector;
    readonly session: SerenadaSession;
    readonly stateHistory: CallState[] = [];
    readonly connectionEvents: ConnectionEvent[] = [];

    private unsubscribe: (() => void) | null = null;
    private unsubscribeConnectionEvents: (() => void) | null = null;

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
        this.statsCollector = new FakeStatsCollector();

        this.session = new SerenadaSession(config, roomId, roomUrl, this.signaling, {
            media: this.media as unknown as MediaEngine,
            statsCollector: this.statsCollector as unknown as CallStatsCollector,
            autoStart: options.autoStart ?? false,
            displayName: options.displayName,
        });

        this.unsubscribe = this.session.subscribe((state) => {
            this.stateHistory.push(state);
        });
        this.unsubscribeConnectionEvents = this.session.onConnectionEvent((event) => {
            this.connectionEvents.push(event);
        });
    }

    get state(): CallState {
        return this.session.state;
    }

    simulateJoined(opts: {
        clientId?: string;
        participants?: Array<Pick<RoomParticipant, 'cid' | 'joinedAt' | 'displayName' | 'contentState' | 'capabilities' | 'mediaPolicy'>>;
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
                contentState: participant.contentState,
                capabilities: participant.capabilities,
                mediaPolicy: participant.mediaPolicy,
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
                contentState: participant.contentState,
                capabilities: participant.capabilities,
                mediaPolicy: participant.mediaPolicy,
            })),
            maxParticipants: roomState.maxParticipants,
        });
    }

    simulatePeerLeft(peerId: string): void {
        this.signaling.emitPeerLeft({ peerId });
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
        this.unsubscribeConnectionEvents?.();
        this.unsubscribeConnectionEvents = null;
        this.session.destroy();
    }
}
