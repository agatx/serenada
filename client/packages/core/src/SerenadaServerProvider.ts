import { SignalingProviderEmitter, type JoinOptions, type RoomEndedEvent, type RoomStateEvent, type SignalingProviderParticipant } from './SignalingProvider.js';
import type { SerenadaLogger } from './types.js';
import { SignalingEngine } from './signaling/SignalingEngine.js';
import {
    parseErrorPayload,
    parseJoinedPayload,
    parseNegotiationDirtyPayload,
    parseRelayFailedPayload,
    parseRoomStatePayload,
    parseTurnRefreshedPayload,
} from './signaling/payloads.js';
import type { RoomParticipant, SignalingMessage } from './signaling/types.js';
import type { TransportKind } from './signaling/transports/types.js';
import { TURN_FETCH_TIMEOUT_MS } from './constants.js';
import { formatError } from './formatError.js';
import { buildApiUrl, resolveServerUrls } from './serverUrls.js';

interface TurnCredentialsResponse {
    username?: string;
    password?: string;
    uris?: string[];
}

export interface SerenadaServerProviderConfig {
    serverHost: string;
    transports?: TransportKind[];
    logger?: SerenadaLogger;
}

export class SerenadaServerProvider extends SignalingProviderEmitter {
    readonly capabilities = { handlesReconnection: true };

    private readonly serverHost: string;
    private readonly signaling: SignalingEngine;
    private readonly logger?: SerenadaLogger;
    private readonly unsubscribeStateChange: () => void;
    private readonly unsubscribeMessages: () => void;
    private lastConnected = false;
    private currentTurnToken: string | null = null;
    private previousParticipants = new Map<string, SignalingProviderParticipant>();
    private currentHostPeerId: string | null = null;
    private disconnected = false;

    constructor(config: SerenadaServerProviderConfig) {
        super();
        const urls = resolveServerUrls(config.serverHost);
        this.serverHost = config.serverHost;
        this.logger = config.logger;
        this.signaling = new SignalingEngine({
            wsUrl: urls.wsUrl,
            httpBaseUrl: urls.httpBaseUrl,
            transports: config.transports,
            logger: config.logger,
        });
        this.unsubscribeStateChange = this.signaling.onStateChange(() => {
            this.handleStateChange();
        });
        this.unsubscribeMessages = this.signaling.subscribeToMessages((message) => {
            this.handleMessage(message);
        });
    }

    connect(): void {
        this.signaling.connect();
    }

    disconnect(): void {
        if (this.disconnected) {
            return;
        }
        this.disconnected = true;
        this.unsubscribeStateChange();
        this.unsubscribeMessages();
        this.signaling.destroy();
        this.previousParticipants.clear();
        this.currentTurnToken = null;
        this.currentHostPeerId = null;
    }

    joinRoom(roomId: string, options?: JoinOptions): void {
        this.previousParticipants.clear();
        this.signaling.joinRoom(roomId, {
            createMaxParticipants: options?.maxParticipants,
            displayName: options?.displayName,
            peerId: options?.appPeerId,
        });
    }

    leaveRoom(): void {
        this.previousParticipants.clear();
        this.currentTurnToken = null;
        this.currentHostPeerId = null;
        this.signaling.leaveRoom();
    }

    endRoom(): void {
        this.signaling.endRoom();
    }

    sendToPeer(peerId: string, type: string, payload: unknown): void {
        this.signaling.sendMessage(type, toRecordPayload(payload), peerId);
    }

    broadcast(type: string, payload: unknown): void {
        this.signaling.sendMessage(type, toRecordPayload(payload));
    }

    /**
     * Install (or clear) a gate consulted before each periodic TURN refresh.
     * The gate returns false to skip the refresh — used by the session to
     * suppress refreshes while every peer is on a direct ICE path, so the
     * call remains independent of signaling for the refresh cadence.
     */
    setTurnRefreshGate(gate: (() => Promise<boolean>) | null): void {
        this.signaling.setTurnRefreshGate(gate);
    }

    async getIceServers(): Promise<RTCIceServer[]> {
        const token = this.currentTurnToken?.trim();
        if (!token) {
            return [];
        }

        const controller = new AbortController();
        const timeout = globalThis.setTimeout(() => controller.abort(), TURN_FETCH_TIMEOUT_MS);

        try {
            const response = await fetch(
                buildApiUrl(this.serverHost, `/api/turn-credentials?token=${encodeURIComponent(token)}`),
                { signal: controller.signal },
            );

            if (!response.ok) {
                throw new Error(`TURN credentials request failed: ${response.status}`);
            }

            const data = await response.json() as TurnCredentialsResponse;
            const uris = Array.isArray(data.uris)
                ? data.uris.filter((uri): uri is string => typeof uri === 'string' && uri.trim().length > 0)
                : [];

            if (uris.length === 0) {
                return [];
            }

            return [{
                urls: uris,
                username: typeof data.username === 'string' ? data.username : undefined,
                credential: typeof data.password === 'string' ? data.password : undefined,
            }];
        } finally {
            globalThis.clearTimeout(timeout);
        }
    }

    private handleStateChange(): void {
        const connected = this.signaling.isConnected;
        if (connected === this.lastConnected) {
            return;
        }

        this.lastConnected = connected;
        if (connected) {
            this.emit('connected', {
                transport: this.signaling.activeTransport ?? undefined,
            });
            return;
        }

        this.emit('disconnected', undefined);
    }

    private handleMessage(message: SignalingMessage): void {
        switch (message.type) {
            case 'joined':
                this.handleJoined(message);
                break;
            case 'room_state':
                this.handleRoomState(message);
                break;
            case 'room_ended':
                this.previousParticipants.clear();
                this.emitRoomEnded(message.payload);
                break;
            case 'error': {
                const error = parseErrorPayload(message.payload);
                if (error) {
                    this.emit('error', error);
                }
                break;
            }
            case 'turn-refreshed': {
                const refreshed = parseTurnRefreshedPayload(message.payload);
                if (!refreshed) {
                    break;
                }
                this.currentTurnToken = refreshed.turnToken;
                void this.refreshIceServers();
                break;
            }
            case 'offer':
            case 'answer':
            case 'ice':
            case 'media_restart_request':
            case 'content_state':
            case 'participant_media_state':
                this.emitPeerMessage(message);
                break;
            case 'negotiation_dirty': {
                const dirty = parseNegotiationDirtyPayload(message.payload);
                if (dirty) {
                    this.emit('negotiationDirty', { withCid: dirty.with });
                }
                break;
            }
            case 'relay_failed': {
                const failed = parseRelayFailedPayload(message.payload);
                if (failed) {
                    this.emit('relayFailed', failed);
                }
                break;
            }
        }
    }

    private emitRoomEnded(payload: Record<string, unknown> | undefined): void {
        const by = roomEndedBy(payload) ?? this.currentHostPeerId ?? undefined;
        const event = {
            reason: roomEndedReason(payload) ?? 'room ended',
        } as RoomEndedEvent;
        if (by) {
            event.by = by;
        }
        this.emit('roomEnded', event);
    }

    private handleJoined(message: SignalingMessage): void {
        const payload = parseJoinedPayload(message.payload);
        if (!payload || !message.cid) {
            return;
        }

        if (payload.turnToken) {
            this.currentTurnToken = payload.turnToken;
        }

        const event = {
            peerId: message.cid,
            participants: payload.participants.map(mapParticipant),
            hostPeerId: payload.hostCid ?? undefined,
            maxParticipants: payload.maxParticipants,
        };

        this.currentHostPeerId = event.hostPeerId ?? null;
        this.previousParticipants = toParticipantMap(event.participants);
        this.emit('joined', event);
    }

    private handleRoomState(message: SignalingMessage): void {
        const payload = parseRoomStatePayload(message.payload);
        if (!payload) {
            return;
        }

        if (typeof message.payload?.turnToken === 'string') {
            this.currentTurnToken = message.payload.turnToken;
        }

        const nextState: RoomStateEvent = {
            participants: payload.participants.map(mapParticipant),
            hostPeerId: payload.hostCid ?? undefined,
            maxParticipants: payload.maxParticipants,
        };

        const nextMap = toParticipantMap(nextState.participants);
        this.emitParticipantDiffs(this.previousParticipants, nextMap);
        this.previousParticipants = nextMap;
        this.currentHostPeerId = nextState.hostPeerId ?? null;
        this.emit('roomStateUpdated', nextState);
    }

    private emitParticipantDiffs(
        previous: Map<string, SignalingProviderParticipant>,
        next: Map<string, SignalingProviderParticipant>,
    ): void {
        for (const [peerId, participant] of next) {
            if (!previous.has(peerId)) {
                this.emit('peerJoined', participant);
            }
        }

        for (const [peerId, participant] of previous) {
            if (!next.has(peerId)) {
                this.emit('peerLeft', participant);
            }
        }
    }

    private emitPeerMessage(message: SignalingMessage): void {
        const payload = message.payload;
        const from = typeof payload?.from === 'string'
            ? payload.from
            : (typeof message.cid === 'string' ? message.cid : null);
        if (!from) {
            return;
        }

        this.emit('message', {
            from,
            type: message.type,
            payload: payload ?? {},
        });
    }

    private async refreshIceServers(): Promise<void> {
        try {
            const servers = await this.getIceServers();
            this.emit('iceServersChanged', servers);
        } catch (error) {
            this.logger?.log('warning', 'Signaling', `Failed to refresh ICE servers: ${formatError(error)}`);
            this.emit('error', {
                code: 'TURN_REFRESH_FAILED',
                message: formatError(error),
            });
        }
    }
}

function mapParticipant(participant: RoomParticipant): SignalingProviderParticipant {
    return {
        peerId: participant.cid,
        joinedAt: participant.joinedAt,
        displayName: participant.displayName,
        appPeerId: participant.peerId,
        audioEnabled: participant.audioEnabled,
        videoEnabled: participant.videoEnabled,
        connectionStatus: participant.connectionStatus,
    };
}

function toParticipantMap(participants: SignalingProviderParticipant[]): Map<string, SignalingProviderParticipant> {
    return new Map(participants.map((participant) => [participant.peerId, participant]));
}

function toRecordPayload(payload: unknown): Record<string, unknown> | undefined {
    if (payload === undefined) {
        return undefined;
    }
    if (payload && typeof payload === 'object' && !Array.isArray(payload)) {
        return payload as Record<string, unknown>;
    }
    return { value: payload };
}

function roomEndedBy(payload: Record<string, unknown> | undefined): string | null {
    const by = payload?.by;
    return typeof by === 'string' && by.trim().length > 0 ? by : null;
}

function roomEndedReason(payload: Record<string, unknown> | undefined): string | null {
    const reason = payload?.reason;
    return typeof reason === 'string' && reason.trim().length > 0 ? reason : null;
}
