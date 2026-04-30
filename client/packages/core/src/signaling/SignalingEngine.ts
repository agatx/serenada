import type { ReconnectOutcome, RoomState, SignalingMessage } from './types.js';
import type { RoomStatuses } from './roomStatuses.js';
import type { SignalingTransport, TransportKind } from './transports/types.js';
import type { SerenadaLogger } from '../types.js';
import { createSignalingTransport } from './transports/index.js';
import { mergeRoomStatusesPayload, mergeRoomStatusUpdatePayload } from './roomStatuses.js';
import {
    parseJoinedPayload,
    parseRoomStatePayload,
    parseErrorPayload,
    parseTurnRefreshedPayload,
    parseRelayFailedPayload,
    parseNegotiationDirtyPayload,
} from './payloads.js';
import { formatError } from '../formatError.js';
import { saveRecoveryRecord, clearRecoveryRecord } from '../recoveryStorage.js';
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
    SUSPEND_HARD_EVICTION_TIMEOUT_MS,
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
    error: { code: string; message: string; reason?: string } | null = null;
    roomStatuses: RoomStatuses = {};
    /** Most-recent reconnect outcome reported by the server in `joined`. */
    lastReconnectOutcome: ReconnectOutcome | null = null;
    /**
     * Highest room-state epoch observed so far. Advances monotonically (per
     * room) on every membership change. Used by MediaEngine to gate ICE
     * restart on an authoritative post-reconnect snapshot.
     */
    lastEpoch: number | null = null;
    /** Epoch at the moment the transport was last observed disconnected. */
    epochAtDisconnect: number | null = null;
    /**
     * Unix-ms timestamp of the first successful `joined` for the current
     * session. Stable across reconnects so the persisted recovery record
     * carries the original join time, not the latest reattach time.
     */
    private sessionStartTs: number | null = null;
    /**
     * True from disconnect until we've seen a fresh authoritative snapshot
     * for the current room on the new transport. Consumers should suppress
     * ICE restart while this flag is set.
     */
    awaitingPostReconnectSnapshot = false;

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
    private lastDisplayName: string | undefined = undefined;
    private lastPeerId: string | undefined = undefined;

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
        this.awaitingPostReconnectSnapshot = false;
        this.epochAtDisconnect = null;
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

    joinRoom(roomId: string, options?: { createMaxParticipants?: number; displayName?: string; peerId?: string }): void {
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
        if (options?.displayName !== undefined) {
            this.lastDisplayName = options.displayName;
        }
        if (options?.peerId !== undefined) {
            this.lastPeerId = options.peerId;
        }

        if (this.transport && this.transport.isOpen()) {
            const payload: Record<string, unknown> = {
                capabilities: { trickleIce: true, maxParticipants: 4 },
                createMaxParticipants: options?.createMaxParticipants ?? this.lastCreateMaxParticipants ?? 4,
            };
            const displayName = options?.displayName ?? this.lastDisplayName;
            if (displayName !== undefined) {
                payload.displayName = displayName;
            }
            const peerId = options?.peerId ?? this.lastPeerId;
            if (peerId !== undefined) {
                payload.peerId = peerId;
            }
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
        this.turnTokenExpiresAtMs = null;
        this.lastReconnectOutcome = null;
        this.lastEpoch = null;
        this.awaitingPostReconnectSnapshot = false;
        this.epochAtDisconnect = null;
        this.notifyStateChange();
    }

    endRoom(): void {
        this.clearJoinTimers();
        this.sendMessage('end_room');
    }

    watchRooms(rids: string[]): void {
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
            case 'joined': {
                if (msg.cid) this.clientId = msg.cid;
                const joined = parseJoinedPayload(msg.payload);
                if (!joined) break;
                this.clearJoinTimers();
                this.joinAcked = true;
                this.lastReconnectOutcome = joined.reconnect ?? null;
                if (joined.epoch !== undefined) {
                    this.lastEpoch = joined.epoch;
                }
                this.roomState = {
                    hostCid: joined.hostCid,
                    participants: joined.participants,
                    maxParticipants: joined.maxParticipants,
                    epoch: joined.epoch,
                };
                if (joined.turnToken) {
                    this.turnToken = joined.turnToken;
                }
                if (joined.turnTokenTTLMs) {
                    this.turnTokenTTLMs = joined.turnTokenTTLMs;
                    this.scheduleTurnRefresh();
                }
                if (joined.reconnectToken) {
                    this.reconnectToken = joined.reconnectToken;
                    this.reconnectTokenRoomId = msg.rid || this.currentRoomId;
                    this.persistReconnectStorage();
                }
                this.persistClientId();
                if (this.sessionStartTs === null) {
                    this.sessionStartTs = Date.now();
                }
                this.persistRecoveryRecord(joined.reconnectTokenTTLMs);
                this.logger?.log(
                    'debug',
                    'Signaling',
                    `joined outcome=${joined.reconnect ?? 'fresh'} epoch=${joined.epoch ?? 'n/a'}`
                );
                // joined alone is not the authoritative post-reconnect
                // snapshot — wait for the dedicated room_state that the
                // server emits immediately after.
                break;
            }
            case 'turn-refreshed': {
                const turnRefreshed = parseTurnRefreshedPayload(msg.payload);
                if (turnRefreshed) {
                    this.turnToken = turnRefreshed.turnToken;
                    if (turnRefreshed.turnTokenTTLMs) {
                        this.turnTokenTTLMs = turnRefreshed.turnTokenTTLMs;
                        this.scheduleTurnRefresh();
                    }
                    this.logger?.log('debug', 'Signaling', 'TURN credentials refreshed');
                }
                break;
            }
            case 'pong':
                this.lastPongAt = Date.now();
                this.missedPongs = 0;
                // Pong is internal bookkeeping — skip notifyStateChange to avoid unnecessary rebuilds
                [...this.messageListeners].forEach(listener => listener(msg));
                return;
            case 'room_state': {
                const roomState = parseRoomStatePayload(msg.payload);
                if (roomState) {
                    this.roomState = roomState;
                    if (roomState.epoch !== undefined) {
                        this.lastEpoch = roomState.epoch;
                    }
                    // First room_state seen after a transport reconnect is the
                    // authoritative sync point. Clear the gate so MediaEngine
                    // can proceed with renegotiation against confirmed state.
                    if (this.awaitingPostReconnectSnapshot) {
                        this.awaitingPostReconnectSnapshot = false;
                        this.epochAtDisconnect = null;
                        this.logger?.log(
                            'debug',
                            'Signaling',
                            `Post-reconnect snapshot received (epoch=${roomState.epoch ?? 'n/a'})`
                        );
                    }
                }
                break;
            }
            case 'room_ended':
                this.resetForTerminal();
                break;
            case 'negotiation_dirty':
            case 'relay_failed': {
                // Validate payload shape and drop anything malformed before
                // it reaches downstream listeners. MediaEngine handles the
                // actual renegotiation/back-off behavior off the listener
                // stream.
                const parsed = msg.type === 'relay_failed'
                    ? parseRelayFailedPayload(msg.payload)
                    : parseNegotiationDirtyPayload(msg.payload);
                if (!parsed) {
                    this.logger?.log('warning', 'Signaling', `Ignoring malformed ${msg.type} payload`);
                    return;
                }
                break;
            }
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
            case 'error': {
                const errorPayload = parseErrorPayload(msg.payload);
                if (errorPayload) {
                    this.error = errorPayload;
                    // Terminal errors that invalidate persisted reconnect
                    // state. The session is over for this CID — clear the
                    // token so a future join can't try to reclaim it.
                    if (
                        errorPayload.code === 'ROOM_ENDED' ||
                        errorPayload.code === 'INVALID_RECONNECT_TOKEN'
                    ) {
                        this.resetForTerminal();
                    }
                }
                break;
            }
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
                // We may miss membership transitions while disconnected. Set
                // the gate so consumers wait for an authoritative
                // post-reconnect snapshot before scheduling renegotiation.
                if (this.currentRoomId) {
                    this.epochAtDisconnect = this.lastEpoch;
                    this.awaitingPostReconnectSnapshot = true;
                }

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

    private turnRefreshGate: (() => Promise<boolean>) | null = null;
    // Absolute timestamp (epoch ms) at which the current TURN credential expires.
    // Set when TTL is installed; used to compute "remaining until expiry" so the
    // skip-path reschedule has a real safety buffer on repeat skips.
    private turnTokenExpiresAtMs: number | null = null;

    setTurnRefreshGate(gate: (() => Promise<boolean>) | null): void {
        this.turnRefreshGate = gate;
    }

    private scheduleTurnRefresh(delayOverrideMs?: number): void {
        this.clearTurnRefreshTimer();
        if (!this.isConnected || !this.turnTokenTTLMs || !this.currentRoomId) return;
        if (delayOverrideMs === undefined) {
            // Initial schedule after fresh creds: trigger at `ratio * TTL`.
            this.turnTokenExpiresAtMs = Date.now() + this.turnTokenTTLMs;
        }

        const refreshDelay = delayOverrideMs ?? this.turnTokenTTLMs * TURN_REFRESH_TRIGGER_RATIO;
        this.logger?.log('debug', 'Signaling', `Scheduling TURN refresh in ${Math.round(refreshDelay / 1000)}s`);
        this.turnRefreshTimer = window.setTimeout(() => {
            this.turnRefreshTimer = null;
            void this.maybeSendTurnRefresh();
        }, refreshDelay);
    }

    private async maybeSendTurnRefresh(): Promise<void> {
        if (!this.isConnected || !this.currentRoomId) return;
        if (this.turnRefreshGate) {
            let shouldRefresh = true;
            try {
                shouldRefresh = await this.turnRefreshGate();
            } catch { /* gate failure → default to refreshing */ }
            if (!shouldRefresh) {
                this.logger?.log('debug', 'Signaling', 'Skipping turn-refresh: all peer paths direct');
                // Reschedule at a fraction of the remaining lifetime so a late
                // path failover to relay still has time to refresh before the
                // current credentials expire. Using `remaining * ratio` gives
                // an exponential approach to expiry on repeat skips.
                if (this.turnTokenExpiresAtMs !== null) {
                    const remainingMs = this.turnTokenExpiresAtMs - Date.now();
                    if (remainingMs > 0) {
                        this.scheduleTurnRefresh(remainingMs * TURN_REFRESH_TRIGGER_RATIO);
                    }
                    // remainingMs <= 0 → creds already expired; stop polling.
                    // A later relay transition is out of our hands; the call
                    // was direct when signaling gave us a chance to refresh.
                }
                return;
            }
        }
        this.logger?.log('debug', 'Signaling', 'Sending turn-refresh request');
        this.sendMessage('turn-refresh');
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

    // Drops in-room state, persisted reconnect authority, and the
    // post-reconnect gate. Called for any terminal event that means the
    // current session is over and should not influence a future join:
    // server `room_ended`, terminal error codes, etc.
    private resetForTerminal(): void {
        this.clearJoinTimers();
        this.roomState = null;
        this.currentRoomId = null;
        this.needsRejoin = false;
        this.clearReconnectStorage();
        this.lastReconnectOutcome = null;
        this.lastEpoch = null;
        this.awaitingPostReconnectSnapshot = false;
        this.epochAtDisconnect = null;
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
        this.sessionStartTs = null;
        clearRecoveryRecord();
    }

    // Snapshots the in-memory reconnect state into the cross-launch
    // recovery store so a relaunched tab can offer a "Rejoin call?" prompt.
    // No-op when we don't have full credentials yet (e.g. first transport
    // open before the server has answered with `joined`).
    private persistRecoveryRecord(reconnectTokenTTLMs: number | undefined): void {
        if (!this.currentRoomId || !this.clientId || !this.reconnectToken || !this.sessionStartTs) {
            return;
        }
        // Fall back to the cross-platform suspendHardEvictionTimeout when
        // the server didn't surface a token TTL. The Go server's reconnect
        // token TTL is bound to suspendHardEvictionTimeout (see
        // signaling.go: reconnectTokenTTL = suspendHardEvictionTimeout), so
        // mirroring that constant keeps Web aligned with iOS/Android and
        // the server without local drift.
        const ttl = reconnectTokenTTLMs && reconnectTokenTTLMs > 0
            ? reconnectTokenTTLMs
            : SUSPEND_HARD_EVICTION_TIMEOUT_MS;
        saveRecoveryRecord({
            roomId: this.currentRoomId,
            cid: this.clientId,
            reconnectToken: this.reconnectToken,
            lastEpoch: this.lastEpoch,
            sessionStartTs: this.sessionStartTs,
            expiresAtMs: Date.now() + ttl,
        });
    }
}
