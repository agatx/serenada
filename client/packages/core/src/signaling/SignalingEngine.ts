import type { RoomState, SignalingMessage } from './types.js';
import type { RoomStatuses } from './roomStatuses.js';
import type { SignalingTransport, TransportKind } from './transports/types.js';
import type { SerenadaLogger } from '../types.js';
import { createSignalingTransport } from './transports/index.js';
import { mergeRoomStatusesPayload, mergeRoomStatusUpdatePayload } from './roomStatuses.js';
import { formatError } from '../formatError.js';
import {
    RECONNECT_BACKOFF_BASE_MS,
    RECONNECT_BACKOFF_CAP_MS,
    PING_INTERVAL_MS,
    PONG_MISS_THRESHOLD,
    WS_FALLBACK_CONSECUTIVE_FAILURES,
    JOIN_CONNECT_KICKSTART_MS,
    JOIN_RECOVERY_MS,
    JOIN_HARD_TIMEOUT_MS,
    TURN_REFRESH_TRIGGER_RATIO,
} from '../constants.js';

export interface SignalingEngineConfig {
    wsUrl: string;
    httpBaseUrl: string;
    transports?: TransportKind[];
    logger?: SerenadaLogger;
}

export type SignalingStateListener = () => void;
export type SignalingMessageListener = (msg: SignalingMessage) => void;

export class SignalingEngine {
    // Public state
    isConnected = false;
    activeTransport: TransportKind | null = null;
    clientId: string | null = null;
    roomState: RoomState | null = null;
    turnToken: string | null = null;
    turnTokenTTLMs: number | null = null;
    error: { code: string; message: string } | null = null;
    roomStatuses: RoomStatuses = {};

    // Config
    private wsUrl: string;
    private httpBaseUrl: string;
    private transportOrder: TransportKind[];

    // Internal state
    private transport: SignalingTransport | null = null;
    private transportIndex = 0;
    private transportConnectedOnce: Record<TransportKind, boolean> = { ws: false, sse: false };
    private transportId = 0;
    private currentRoomId: string | null = null;
    private pendingJoin: string | null = null;
    private lastClientId: string | null = null;
    private needsRejoin = false;
    private reconnectToken: string | null = null;
    private reconnectTokenRoomId: string | null = null;
    private lastPongAt = Date.now();
    private missedPongs = 0;
    private wsConsecutiveFailures = 0;
    private sseSid: string | null = null;
    private joinAttemptId = 0;
    private joinAcked = false;
    private joinKickstartTimer: number | null = null;
    private joinRecoveryTimer: number | null = null;
    private joinHardTimeout: number | null = null;
    private turnRefreshTimer: number | null = null;
    private pingInterval: number | null = null;
    private reconnectTimeout: number | null = null;
    private reconnectAttempts = 0;
    private closedByDestroy = false;
    private connecting = false;
    private lastCreateMaxParticipants: number | undefined = undefined;

    // Logger
    private logger?: SerenadaLogger;

    // Listeners
    private messageListeners: SignalingMessageListener[] = [];
    private stateListeners: SignalingStateListener[] = [];

    constructor(config: SignalingEngineConfig) {
        this.wsUrl = config.wsUrl;
        this.httpBaseUrl = config.httpBaseUrl;
        this.transportOrder = config.transports ?? ['ws', 'sse'];
        this.logger = config.logger;
        this.loadReconnectStorage();
    }

    connect(): void {
        this.closedByDestroy = false;
        this.transportIndex = 0;
        this.transportConnectedOnce = { ws: false, sse: false };
        this.doConnect(0);
    }

    destroy(): void {
        this.closedByDestroy = true;
        this.clearReconnectTimeout();
        this.clearJoinTimers();
        this.clearPingInterval();
        this.clearTurnRefreshTimer();
        if (this.transport) {
            this.transport.close();
            this.transport = null;
        }
    }

    sendMessage(type: string, payload?: Record<string, unknown>, to?: string): void {
        if (this.transport && this.transport.isOpen()) {
            const msg: SignalingMessage = {
                v: 1,
                type,
                rid: this.currentRoomId || undefined,
                cid: this.clientId || undefined,
                to,
                payload
            };
            this.transport.send(msg);
        } else {
            this.logger?.log('warning', 'Signaling', 'Transport not connected');
        }
    }

    joinRoom(roomId: string, options?: { createMaxParticipants?: number }): void {
        this.logger?.log('debug', 'Signaling', `joinRoom call for ${roomId}`);
        this.error = null;
        this.clearJoinTimers();
        this.needsRejoin = false;
        this.currentRoomId = roomId;
        this.joinAttemptId += 1;
        const attemptId = this.joinAttemptId;
        this.joinAcked = false;

        if (options?.createMaxParticipants !== undefined) {
            this.lastCreateMaxParticipants = options.createMaxParticipants;
        }

        if (this.transport && this.transport.isOpen()) {
            const payload: Record<string, unknown> = {
                capabilities: { trickleIce: true, maxParticipants: 4 },
                createMaxParticipants: options?.createMaxParticipants ?? this.lastCreateMaxParticipants ?? 4,
            };
            const reconnectCid = this.clientId || this.lastClientId;
            if (reconnectCid) {
                payload.reconnectCid = reconnectCid;
                if (this.reconnectToken && this.reconnectTokenRoomId === roomId) {
                    payload.reconnectToken = this.reconnectToken;
                }
            }

            const doSendJoin = () => {
                if (this.joinAttemptId !== attemptId) return;
                this.sendMessage('join', payload);
            };

            doSendJoin();

            this.joinKickstartTimer = window.setTimeout(() => {
                this.joinKickstartTimer = null;
                if (this.joinAttemptId !== attemptId || this.joinAcked) return;
                this.logger?.log('debug', 'Signaling', 'Join kickstart: re-sending join');
                doSendJoin();
            }, JOIN_CONNECT_KICKSTART_MS);

            this.joinRecoveryTimer = window.setTimeout(() => {
                this.joinRecoveryTimer = null;
                if (this.joinAttemptId !== attemptId || this.joinAcked) return;
                this.logger?.log('debug', 'Signaling', 'Join recovery: re-sending join');
                doSendJoin();
            }, JOIN_RECOVERY_MS);

            this.joinHardTimeout = window.setTimeout(() => {
                this.joinHardTimeout = null;
                if (this.joinAttemptId !== attemptId || this.joinAcked) return;
                this.logger?.log('error', 'Signaling', 'Join hard timeout reached');
                this.clearJoinTimers();
                this.error = { code: 'JOIN_TIMEOUT', message: 'Join timed out' };
                this.notifyStateChange();
            }, JOIN_HARD_TIMEOUT_MS);
        } else {
            this.logger?.log('debug', 'Signaling', 'Transport not ready, buffering join');
            this.pendingJoin = roomId;
        }
        this.notifyStateChange();
    }

    leaveRoom(options?: { preserveReconnectState?: boolean }): void {
        const preserveReconnectState = options?.preserveReconnectState === true;
        this.clearJoinTimers();
        this.sendMessage('leave');
        this.currentRoomId = null;
        this.needsRejoin = false;
        if (preserveReconnectState) {
            this.lastClientId = this.clientId;
        } else {
            this.lastClientId = null;
            this.clearReconnectStorage();
        }
        this.clientId = null;
        this.roomState = null;
        this.turnToken = null;
        this.turnTokenTTLMs = null;
        this.notifyStateChange();
    }

    endRoom(): void {
        this.clearJoinTimers();
        this.sendMessage('end_room');
    }

    watchRooms(rids: string[]): void {
        if (rids.length === 0) return;
        this.sendMessage('watch_rooms', { rids });
    }

    clearError(): void {
        this.error = null;
        this.notifyStateChange();
    }

    subscribeToMessages(cb: SignalingMessageListener): () => void {
        this.messageListeners.push(cb);
        return () => {
            this.messageListeners = this.messageListeners.filter(l => l !== cb);
        };
    }

    onStateChange(cb: SignalingStateListener): () => void {
        this.stateListeners.push(cb);
        return () => {
            this.stateListeners = this.stateListeners.filter(l => l !== cb);
        };
    }

    get currentRoom(): string | null {
        return this.currentRoomId;
    }

    // --- Private methods ---

    private handleIncomingMessage(msg: SignalingMessage): void {
        switch (msg.type) {
            case 'joined':
                this.clearJoinTimers();
                this.joinAcked = true;
                if (msg.cid) this.clientId = msg.cid;
                if (msg.payload) {
                    this.roomState = msg.payload as RoomState;
                    if (msg.payload.turnToken) {
                        this.turnToken = msg.payload.turnToken as string;
                    }
                    if (msg.payload.turnTokenTTLMs) {
                        this.turnTokenTTLMs = msg.payload.turnTokenTTLMs as number;
                        this.scheduleTurnRefresh();
                    }
                    if (msg.payload.reconnectToken) {
                        this.reconnectToken = msg.payload.reconnectToken as string;
                        this.reconnectTokenRoomId = msg.rid || this.currentRoomId;
                        this.persistReconnectStorage();
                    }
                }
                this.persistClientId();
                break;
            case 'turn-refreshed':
                if (msg.payload) {
                    if (msg.payload.turnToken) {
                        this.turnToken = msg.payload.turnToken as string;
                    }
                    if (msg.payload.turnTokenTTLMs) {
                        this.turnTokenTTLMs = msg.payload.turnTokenTTLMs as number;
                        this.scheduleTurnRefresh();
                    }
                    this.logger?.log('debug', 'Signaling', 'TURN credentials refreshed');
                }
                break;
            case 'pong':
                this.lastPongAt = Date.now();
                this.missedPongs = 0;
                // Pong is internal bookkeeping — skip notifyStateChange to avoid unnecessary rebuilds
                [...this.messageListeners].forEach(listener => listener(msg));
                return;
            case 'room_state':
                if (msg.payload) {
                    this.roomState = msg.payload as RoomState;
                }
                break;
            case 'room_ended':
                this.clearJoinTimers();
                this.roomState = null;
                this.currentRoomId = null;
                this.needsRejoin = false;
                this.clearReconnectStorage();
                break;
            case 'room_statuses':
                if (msg.payload) {
                    this.roomStatuses = mergeRoomStatusesPayload(this.roomStatuses, msg.payload);
                }
                break;
            case 'room_status_update':
                if (msg.payload) {
                    this.roomStatuses = mergeRoomStatusUpdatePayload(this.roomStatuses, msg.payload);
                }
                break;
            case 'error':
                if (msg.payload && msg.payload.message) {
                    this.error = {
                        code: (msg.payload.code as string) ?? 'UNKNOWN',
                        message: String(msg.payload.message),
                    };
                }
                break;
        }

        this.notifyStateChange();
        [...this.messageListeners].forEach(listener => listener(msg));
    }

    private doConnect(index?: number): void {
        if (this.closedByDestroy) return;
        if (this.connecting) return;

        const targetIndex = index ?? this.transportIndex;
        const targetKind = this.transportOrder[targetIndex];
        if (!targetKind) return;
        this.transportIndex = targetIndex;
        this.connecting = true;

        if (this.transport) {
            if (this.transport.getSessionId) {
                this.sseSid = this.transport.getSessionId();
            }
            this.transport.close();
        }

        const connectionId = this.transportId + 1;
        this.transportId = connectionId;

        const transport = createSignalingTransport(targetKind, {
            onOpen: () => {
                if (connectionId !== this.transportId) return;
                this.connecting = false;
                this.reconnectAttempts = 0;
                if (targetKind === 'ws') {
                    this.wsConsecutiveFailures = 0;
                }
                const wasConnected = this.isConnected;
                this.isConnected = true;
                this.activeTransport = targetKind;
                this.transportConnectedOnce[targetKind] = true;
                this.startPingInterval();
                if (!wasConnected) {
                    if (this.pendingJoin) {
                        const roomId = this.pendingJoin;
                        this.pendingJoin = null;
                        this.joinRoom(roomId);
                    } else if (this.needsRejoin && this.currentRoomId) {
                        this.logger?.log('debug', 'Signaling', `Auto-rejoining room ${this.currentRoomId}`);
                        this.needsRejoin = false;
                        this.joinRoom(this.currentRoomId);
                    }
                }
                this.notifyStateChange();
            },
            onClose: (reason, err) => {
                if (connectionId !== this.transportId) return;
                this.connecting = false;
                if (this.closedByDestroy) return;
                this.logger?.log('error', 'Signaling', `Disconnected via ${reason}${err ? `: ${formatError(err)}` : ''}`);
                this.isConnected = false;
                this.activeTransport = null;
                this.clearPingInterval();
                if (targetKind === 'ws') {
                    this.wsConsecutiveFailures++;
                }
                if (this.clientId) {
                    this.lastClientId = this.clientId;
                }
                this.transport = null;
                this.needsRejoin = !!this.currentRoomId;

                if (this.shouldFallback(targetKind, reason) && this.tryNextTransport(reason)) {
                    this.notifyStateChange();
                    return;
                }

                this.scheduleReconnect();
                this.notifyStateChange();
            },
            onMessage: (msg) => {
                if (connectionId !== this.transportId) return;
                this.handleIncomingMessage(msg);
            }
        }, {
            wsUrl: this.wsUrl,
            httpBaseUrl: this.httpBaseUrl,
            sseSid: this.sseSid || undefined,
            logger: this.logger,
        });

        this.transport = transport;
        try {
            transport.connect();
        } catch (err) {
            this.connecting = false;
            this.logger?.log('error', 'Signaling', `Transport connect() threw: ${formatError(err)}`);
            this.scheduleReconnect();
        }
    }

    private shouldFallback(kind: TransportKind, reason: string): boolean {
        if (this.transportOrder.length <= 1) return false;
        if (this.transportIndex >= this.transportOrder.length - 1) return false;
        if (reason === 'unsupported' || reason === 'timeout') return true;
        if (!this.transportConnectedOnce[kind]) return true;
        if (kind === 'ws' && this.wsConsecutiveFailures >= WS_FALLBACK_CONSECUTIVE_FAILURES) {
            this.logger?.log('warning', 'Signaling', `${this.wsConsecutiveFailures} consecutive WS failures, allowing SSE fallback`);
            return true;
        }
        return false;
    }

    private tryNextTransport(reason: string): boolean {
        const nextIndex = this.transportIndex + 1;
        if (nextIndex >= this.transportOrder.length) return false;
        this.logger?.log('warning', 'Signaling', `${this.transportOrder[this.transportIndex]} failed (${reason}), trying ${this.transportOrder[nextIndex]}`);
        this.reconnectAttempts = 0;
        this.doConnect(nextIndex);
        return true;
    }

    private scheduleReconnect(): void {
        if (this.closedByDestroy) return;
        if (this.reconnectTimeout !== null) return;
        const attempt = this.reconnectAttempts + 1;
        this.reconnectAttempts = attempt;
        const backoff = Math.min(RECONNECT_BACKOFF_BASE_MS * Math.pow(2, attempt - 1), RECONNECT_BACKOFF_CAP_MS);

        this.reconnectTimeout = window.setTimeout(() => {
            this.reconnectTimeout = null;
            this.transportIndex = 0;
            this.transportConnectedOnce = { ws: false, sse: false };
            this.doConnect(0);
        }, backoff);
    }

    private startPingInterval(): void {
        this.clearPingInterval();
        this.lastPongAt = Date.now();
        this.missedPongs = 0;

        this.pingInterval = window.setInterval(() => {
            const elapsed = Date.now() - this.lastPongAt;
            if (elapsed > PING_INTERVAL_MS) {
                this.missedPongs++;
                if (this.missedPongs >= PONG_MISS_THRESHOLD) {
                    this.logger?.log('warning', 'Signaling', `${this.missedPongs} missed pongs, treating connection as dead`);
                    this.missedPongs = 0;
                    if (this.transport) {
                        if (this.transport.forceClose) {
                            this.transport.forceClose('ping-timeout');
                        } else {
                            this.transport.close();
                        }
                    }
                    return;
                }
            }
            this.sendMessage('ping', { ts: Date.now() });
        }, PING_INTERVAL_MS);
    }

    private scheduleTurnRefresh(): void {
        this.clearTurnRefreshTimer();
        if (!this.isConnected || !this.turnTokenTTLMs || !this.currentRoomId) return;

        const refreshDelay = this.turnTokenTTLMs * TURN_REFRESH_TRIGGER_RATIO;
        this.logger?.log('debug', 'Signaling', `Scheduling TURN refresh in ${Math.round(refreshDelay / 1000)}s`);
        this.turnRefreshTimer = window.setTimeout(() => {
            this.turnRefreshTimer = null;
            if (this.isConnected && this.currentRoomId) {
                this.logger?.log('debug', 'Signaling', 'Sending turn-refresh request');
                this.sendMessage('turn-refresh');
            }
        }, refreshDelay);
    }

    private clearJoinTimers(): void {
        if (this.joinKickstartTimer !== null) { window.clearTimeout(this.joinKickstartTimer); this.joinKickstartTimer = null; }
        if (this.joinRecoveryTimer !== null) { window.clearTimeout(this.joinRecoveryTimer); this.joinRecoveryTimer = null; }
        if (this.joinHardTimeout !== null) { window.clearTimeout(this.joinHardTimeout); this.joinHardTimeout = null; }
    }

    private clearPingInterval(): void {
        if (this.pingInterval !== null) { window.clearInterval(this.pingInterval); this.pingInterval = null; }
    }

    private clearTurnRefreshTimer(): void {
        if (this.turnRefreshTimer !== null) { window.clearTimeout(this.turnRefreshTimer); this.turnRefreshTimer = null; }
    }

    private clearReconnectTimeout(): void {
        if (this.reconnectTimeout !== null) { window.clearTimeout(this.reconnectTimeout); this.reconnectTimeout = null; }
    }

    private notifyStateChange(): void {
        [...this.stateListeners].forEach(l => l());
    }

    // Session storage helpers
    private readonly storageKeyClientId = 'serenada.reconnectCid';
    private readonly storageKeyReconnectToken = 'serenada.reconnectToken';
    private readonly storageKeyReconnectTokenRoom = 'serenada.reconnectTokenRoom';

    private loadReconnectStorage(): void {
        try {
            const stored = window.sessionStorage.getItem(this.storageKeyClientId);
            if (stored && !this.lastClientId) this.lastClientId = stored;
            const storedToken = window.sessionStorage.getItem(this.storageKeyReconnectToken);
            if (storedToken && !this.reconnectToken) this.reconnectToken = storedToken;
            const storedTokenRoom = window.sessionStorage.getItem(this.storageKeyReconnectTokenRoom);
            if (storedTokenRoom && !this.reconnectTokenRoomId) this.reconnectTokenRoomId = storedTokenRoom;
        } catch (err) {
            this.logger?.log('warning', 'Signaling', `Failed to load reconnectCid: ${err}`);
        }
    }

    private persistClientId(): void {
        if (this.clientId) {
            try { window.sessionStorage.setItem(this.storageKeyClientId, this.clientId); }
            catch (err) { this.logger?.log('warning', 'Signaling', `Failed to persist reconnectCid: ${err}`); }
        }
    }

    private persistReconnectStorage(): void {
        try {
            if (this.reconnectToken) {
                window.sessionStorage.setItem(this.storageKeyReconnectToken, this.reconnectToken);
            }
            if (this.reconnectTokenRoomId) {
                window.sessionStorage.setItem(this.storageKeyReconnectTokenRoom, this.reconnectTokenRoomId);
            }
        } catch (err) {
            this.logger?.log('warning', 'Signaling', `Failed to persist reconnectToken: ${err}`);
        }
    }

    private clearReconnectStorage(): void {
        try {
            window.sessionStorage.removeItem(this.storageKeyClientId);
            window.sessionStorage.removeItem(this.storageKeyReconnectToken);
            window.sessionStorage.removeItem(this.storageKeyReconnectTokenRoom);
        } catch (err) {
            this.logger?.log('warning', 'Signaling', `Failed to clear reconnectCid: ${err}`);
        }
        this.reconnectToken = null;
        this.reconnectTokenRoomId = null;
    }
}
