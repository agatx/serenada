import type {
    ActiveTransport,
    CallErrorCode,
    CallState,
    CallStats,
    CameraMode,
    ConnectionStatus,
    MediaCapability,
    SerenadaConfig,
    SerenadaSessionHandle,
} from './types.js';
import type {
    ConnectionInfo,
    SignalingErrorEvent,
    JoinOptions,
    PeerEvent,
    PeerMessage,
    RoomStateEvent,
    SignalingProvider,
    SignalingProviderEventMap,
    SignalingProviderEventName,
    SignalingProviderParticipant,
} from './SignalingProvider.js';
import { MediaEngine } from './media/MediaEngine.js';
import { CallStatsCollector } from './media/callStats.js';
import {
    ICE_FETCH_RETRY_DELAYS_MS,
    RECONNECT_BACKOFF_BASE_MS,
    RECONNECT_BACKOFF_CAP_MS,
    JOIN_HARD_TIMEOUT_MS,
    ENDING_SCREEN_MS,
} from './constants.js';
import { formatError } from './formatError.js';
import type { RoomParticipant, RoomState, SignalingMessage } from './signaling/types.js';

interface SessionDependencies {
    media?: MediaEngine;
    statsCollector?: CallStatsCollector;
    autoStart?: boolean;
    displayName?: string;
}

function mapErrorCode(serverCode: string): CallErrorCode {
    switch (serverCode) {
        case 'JOIN_TIMEOUT':
            return 'signalingTimeout';
        case 'ROOM_FULL':
        case 'ROOM_CAPACITY_UNSUPPORTED':
            return 'roomFull';
        case 'ROOM_ENDED':
            return 'roomEnded';
        case 'CONNECTION_FAILED':
            return 'connectionFailed';
        case 'ICE_SERVER_FETCH_FAILED':
            return 'serverError';
        case 'BAD_REQUEST':
        case 'UNSUPPORTED_VERSION':
        case 'INVALID_ROOM_ID':
        case 'SERVER_NOT_CONFIGURED':
        case 'INVALID_RECONNECT_TOKEN':
        case 'TURN_REFRESH_FAILED':
        case 'NOT_IN_ROOM':
        case 'NOT_HOST':
            return 'serverError';
        default:
            return 'unknown';
    }
}

function toRoomParticipant(participant: SignalingProviderParticipant): RoomParticipant {
    return {
        cid: participant.peerId,
        joinedAt: participant.joinedAt,
        displayName: participant.displayName,
        audioEnabled: participant.audioEnabled,
        videoEnabled: participant.videoEnabled,
        connectionStatus: participant.connectionStatus,
    };
}

function dedupeParticipants(
    participants: RoomParticipant[],
    localPeerId: string | null,
): RoomParticipant[] {
    const deduped = new Map<string, RoomParticipant>();
    for (const participant of participants) {
        if (participant.cid.length === 0) {
            continue;
        }
        deduped.set(participant.cid, participant);
    }
    if (localPeerId && !deduped.has(localPeerId)) {
        deduped.set(localPeerId, { cid: localPeerId });
    }
    return Array.from(deduped.values());
}

function resolveHostCid(
    participants: RoomParticipant[],
    nextHostCid: string | null | undefined,
    localPeerId: string | null,
): string | null {
    const candidateHostCid = nextHostCid ?? localPeerId ?? null;
    if (!candidateHostCid) {
        return participants[0]?.cid ?? null;
    }
    const participantCids = new Set(participants.map((participant) => participant.cid));
    if (participantCids.size > 0 && !participantCids.has(candidateHostCid)) {
        return participants[0]?.cid ?? null;
    }
    return candidateHostCid;
}

function buildRoomState(
    event: Pick<RoomStateEvent, 'participants' | 'hostPeerId' | 'maxParticipants'>,
    currentHostCid: string | null,
    localPeerId: string | null,
): RoomState {
    const participants = dedupeParticipants(event.participants.map(toRoomParticipant), localPeerId);
    return {
        hostCid: resolveHostCid(participants, event.hostPeerId ?? currentHostCid, localPeerId),
        participants,
        maxParticipants: event.maxParticipants,
    };
}

function upsertParticipant(
    roomState: RoomState | null,
    event: PeerEvent,
    localPeerId: string | null,
): RoomState | null {
    if (!roomState && !localPeerId) {
        return null;
    }
    const participants = dedupeParticipants([
        ...(roomState?.participants ?? []),
        { cid: event.peerId, joinedAt: event.joinedAt, displayName: event.displayName },
    ], localPeerId);
    return {
        hostCid: resolveHostCid(participants, roomState?.hostCid ?? null, localPeerId),
        participants,
        maxParticipants: roomState?.maxParticipants,
    };
}

function removeParticipant(roomState: RoomState | null, peerId: string, localPeerId: string | null): RoomState | null {
    if (!roomState) {
        return null;
    }
    const participants = dedupeParticipants(
        roomState.participants.filter((participant) => participant.cid !== peerId),
        localPeerId,
    );
    if (participants.length === 0) {
        return null;
    }
    const nextHostCid = roomState.hostCid === peerId ? null : roomState.hostCid;
    return {
        hostCid: resolveHostCid(participants, nextHostCid, localPeerId),
        participants,
        maxParticipants: roomState.maxParticipants,
    };
}

function toMediaSignalingMessage(message: PeerMessage): SignalingMessage {
    const payload = message.payload;
    if (payload && typeof payload === 'object' && !Array.isArray(payload)) {
        return {
            v: 1,
            type: message.type,
            cid: message.from,
            payload: {
                ...(payload as Record<string, unknown>),
                from: typeof (payload as Record<string, unknown>).from === 'string'
                    ? (payload as Record<string, unknown>).from
                    : message.from,
            },
        };
    }

    return {
        v: 1,
        type: message.type,
        cid: message.from,
        payload: {
            from: message.from,
            value: payload,
        },
    };
}

function isMediaSignalingMessageType(type: string): boolean {
    return type === 'content_state' || type === 'offer' || type === 'answer' || type === 'ice';
}

/**
 * Represents an active call session. Created via {@link SerenadaCore.join} or
 * {@link SerenadaCore.createRoom}. Manages media, signaling, and call state.
 */
export class SerenadaSession implements SerenadaSessionHandle {
    private readonly signaling: SignalingProvider;
    private readonly media: MediaEngine;
    private readonly statsCollector: CallStatsCollector;
    private readonly config: SerenadaConfig;
    private readonly roomId: string;
    private readonly roomUrl: string | null;
    private readonly handlesReconnection: boolean;
    private readonly displayName?: string;

    private _state: CallState;
    private stateListeners: Array<(state: CallState) => void> = [];
    private readonly peerMessageListeners = new Set<(message: PeerMessage) => void>();
    private readonly providerUnsubscribers: Array<() => void> = [];

    private _destroyed = false;
    private permissionCheckDone = false;
    private permissionCheckInFlight = false;
    private endingTimer: number | null = null;
    private joinTimeoutTimer: number | null = null;
    private reconnectTimer: number | null = null;
    private reconnectAttempts = 0;
    private pendingJoinOptions: JoinOptions | null = null;
    private joinInFlight = false;
    private reconnectRecoveryPending = false;
    private iceFetchGeneration = 0;
    private started = false;
    private terminated = false;

    private isConnected = false;
    private activeTransport: ActiveTransport | null = null;
    private clientId: string | null = null;
    private roomState: RoomState | null = null;
    private error: SignalingErrorEvent | null = null;
    private readonly remoteMediaStates = new Map<string, { audioEnabled?: boolean; videoEnabled?: boolean }>();
    private userPreferredVideoEnabled: boolean;

    private get isInactive(): boolean {
        return this._destroyed || this.terminated;
    }

    onPermissionsRequired: ((permissions: MediaCapability[]) => void) | null = null;

    constructor(
        config: SerenadaConfig,
        roomId: string,
        roomUrl: string | null,
        signaling: SignalingProvider,
        deps: SessionDependencies = {},
    ) {
        this.config = config;
        this.roomId = roomId;
        this.roomUrl = roomUrl;
        this.signaling = signaling;
        this.handlesReconnection = signaling.capabilities?.handlesReconnection === true;
        this.displayName = deps.displayName;
        this.userPreferredVideoEnabled = config.defaultVideoEnabled !== false;

        this._state = {
            phase: 'joining',
            roomId,
            roomUrl,
            localParticipant: null,
            remoteParticipants: [],
            connectionStatus: 'connected',
            activeTransport: null,
            requiredPermissions: null,
            error: null,
        };

        this.media = deps.media ?? new MediaEngine(
            { turnsOnly: config.turnsOnly, logger: config.logger },
            (type, payload, to) => {
                if (to) {
                    this.signaling.sendToPeer(to, type, payload);
                    return;
                }
                this.signaling.broadcast(type, payload);
            },
        );
        this.statsCollector = deps.statsCollector ?? new CallStatsCollector(config.logger);

        this.bindProviderEvents();
        this.media.setOnChange(() => {
            if (this.isInactive) {
                return;
            }
            this.rebuildState();
        });

        // Skip periodic TURN refresh while every peer is on a direct ICE
        // path — the credentials go unused and the call can continue
        // through arbitrary-length signaling outages. A path that falls
        // back to relay causes the next cycle to refresh normally.
        signaling.setTurnRefreshGate?.(
            () => this.media.arePeerPathsAllDirect().then((direct) => !direct),
        );

        if (deps.autoStart !== false) {
            this.start();
        }
    }

    /** Current call state. Subscribe via {@link subscribe} for updates. */
    get state(): CallState { return this._state; }
    /** Local media stream (camera/microphone), or `null` before media is acquired. */
    get localStream(): MediaStream | null { return this.media.localStream; }
    /** Map of remote participant CID to their media stream. */
    get remoteStreams(): Map<string, MediaStream> { return this.media.remoteStreams; }
    /** Current WebRTC call statistics, or `null` if not yet collecting. */
    get callStats(): CallStats | null { return this.statsCollector.stats; }
    get hasMultipleCameras(): boolean { return this.media.hasMultipleCameras; }
    get canScreenShare(): boolean { return this.media.canScreenShare; }
    get isSignalingConnected(): boolean { return this.isConnected; }
    get iceConnectionState(): RTCIceConnectionState { return this.media.iceConnectionState; }
    get peerConnectionState(): RTCPeerConnectionState { return this.media.connectionState; }
    get rtcSignalingState(): RTCSignalingState { return this.media.signalingState; }

    /** Subscribe to state changes. Returns an unsubscribe function. */
    subscribe(callback: (state: CallState) => void): () => void {
        this.stateListeners.push(callback);
        return () => {
            this.stateListeners = this.stateListeners.filter((listener) => listener !== callback);
        };
    }

    onPeerMessage(callback: (message: PeerMessage) => void): () => void {
        this.peerMessageListeners.add(callback);
        return () => {
            this.peerMessageListeners.delete(callback);
        };
    }

    /** Resume joining after media permissions have been granted. */
    async resumeJoin(): Promise<void> {
        if (this.isInactive) {
            return;
        }
        this.permissionCheckDone = true;
        const stream = await this.media.startLocalMedia();
        if (stream) {
            this.broadcastLocalMediaState();
            this.rebuildState();
        }
    }

    /** Cancel an in-progress join and destroy the session. */
    cancelJoin(): void {
        this.permissionCheckDone = true;
        this._state = { ...this._state, phase: 'idle', requiredPermissions: null };
        this.notifyListeners();
        this.destroy();
    }

    /** Leave the call gracefully. The other participant stays connected. */
    leave(): void {
        if (this.isInactive) return;
        this.clearReconnectTimer();
        this.invalidateIceFetches();
        this.pendingJoinOptions = null;
        this.joinInFlight = false;
        this.signaling.leaveRoom();
        this.media.cleanupAllPeers();
        this.statsCollector.stop();
        this.roomState = null;
        this.remoteMediaStates.clear();
        this._state = { ...this._state, phase: 'idle' };
        this.notifyListeners();
        this.destroy();
    }

    /** End the call for all participants. */
    end(): void {
        if (this.isInactive) return;
        this.signaling.endRoom();
        this.leave();
    }

    /** Toggle local audio on/off. */
    toggleAudio(): void { this.setTrackEnabled('audio'); }
    /** Toggle local video on/off. */
    toggleVideo(): void { this.setTrackEnabled('video'); }

    /** Set local audio enabled state explicitly. */
    setAudioEnabled(enabled: boolean): void { this.setTrackEnabled('audio', enabled); }
    /** Set local video enabled state explicitly. */
    setVideoEnabled(enabled: boolean): void { this.setTrackEnabled('video', enabled); }

    /** Switch camera mode (selfie/world). Composite is not available on web. */
    setCameraMode(mode: CameraMode): void {
        if (mode === 'world' && this.media.facingMode === 'user') {
            void this.flipCamera();
        } else if (mode === 'selfie' && this.media.facingMode === 'environment') {
            void this.flipCamera();
        }
    }

    /** Cycle to the next camera mode (selfie to world or vice versa). */
    async flipCamera(): Promise<void> {
        await this.media.flipCamera();
    }

    /** Start sharing the screen, replacing the camera video track. */
    async startScreenShare(): Promise<void> {
        await this.media.startScreenShare();
    }

    /** Stop screen sharing and restore the camera video track. */
    async stopScreenShare(): Promise<void> {
        await this.media.stopScreenShare();
    }

    /** Clean up all resources. Call when done with the session. */
    destroy(): void {
        if (this._destroyed) return;
        this._destroyed = true;
        this.invalidateIceFetches();
        this.clearReconnectTimer();
        this.clearEndingTimer();
        this.clearJoinTimeout();
        for (const unsubscribe of this.providerUnsubscribers) {
            unsubscribe();
        }
        this.providerUnsubscribers.length = 0;
        this.statsCollector.stop();
        this.media.destroy();
        this.signaling.disconnect();
    }

    private start(): void {
        if (this.started) {
            return;
        }
        this.started = true;
        this.pendingJoinOptions = {
            displayName: this.displayName,
        };
        this.scheduleJoinTimeout();
        this.signaling.connect();
    }

    private bindProviderEvents(): void {
        this.bindProviderEvent('connected', this.handleConnected);
        this.bindProviderEvent('disconnected', this.handleDisconnected);
        this.bindProviderEvent('joined', this.handleJoined);
        this.bindProviderEvent('roomStateUpdated', this.handleRoomStateUpdated);
        this.bindProviderEvent('peerJoined', this.handlePeerJoined);
        this.bindProviderEvent('peerLeft', this.handlePeerLeft);
        this.bindProviderEvent('message', this.handlePeerMessage);
        this.bindProviderEvent('roomEnded', this.handleRoomEnded);
        this.bindProviderEvent('error', this.handleError);
        this.bindProviderEvent('iceServersChanged', this.handleIceServersChanged);
    }

    private bindProviderEvent<K extends SignalingProviderEventName>(
        event: K,
        callback: (payload: SignalingProviderEventMap[K]) => void,
    ): void {
        this.signaling.on(event, callback);
        this.providerUnsubscribers.push(() => {
            this.signaling.off(event, callback);
        });
    }

    private readonly handleConnected = (info: ConnectionInfo | undefined): void => {
        if (this.isInactive) {
            return;
        }
        const wasConnected = this.isConnected;
        this.isConnected = true;
        this.activeTransport = info?.transport ?? null;
        this.clearReconnectTimer();
        this.reconnectAttempts = 0;
        this.media.updateSignalingConnected(true);

        if (this.pendingJoinOptions && !this.joinInFlight) {
            this.joinInFlight = true;
            this.signaling.joinRoom(this.roomId, this.pendingJoinOptions);
        } else if (!wasConnected && this.handlesReconnection && this.reconnectRecoveryPending && this.roomState) {
            this.reconnectRecoveryPending = false;
            this.media.handleSignalingReconnect();
        }

        this.rebuildState();
    };

    private readonly handleDisconnected = (): void => {
        if (this.isInactive) {
            return;
        }
        const hadRoomState = this.roomState !== null;
        this.isConnected = false;
        this.activeTransport = null;
        this.joinInFlight = false;
        this.media.updateSignalingConnected(false);

        if (this.handlesReconnection) {
            this.reconnectRecoveryPending = hadRoomState;
        } else {
            this.pendingJoinOptions = { reconnectPeerId: this.clientId ?? undefined, displayName: this.displayName };
            this.scheduleReconnect();
        }

        this.rebuildState();
    };

    private readonly handleJoined = (event: SignalingProviderEventMap['joined']): void => {
        if (this.isInactive) {
            return;
        }
        this.joinInFlight = false;
        this.pendingJoinOptions = null;
        this.reconnectRecoveryPending = false;
        this.clearJoinTimeout();
        this.error = null;
        this.clientId = event.peerId;
        this.roomState = buildRoomState(event, null, event.peerId);
        this.media.updateRoomState(this.roomState, this.clientId);
        this.rebuildState();
        this.broadcastLocalMediaState();
        void this.fetchInitialIceServers();
    };

    private readonly handleRoomStateUpdated = (event: RoomStateEvent): void => {
        if (this.isInactive) {
            return;
        }
        this.error = null;
        this.roomState = buildRoomState(event, this.roomState?.hostCid ?? null, this.clientId);
        this.media.updateRoomState(this.roomState, this.clientId);
        this.rebuildState();
    };

    private readonly handlePeerJoined = (event: PeerEvent): void => {
        if (this.isInactive) {
            return;
        }
        this.error = null;
        this.roomState = upsertParticipant(this.roomState, event, this.clientId);
        this.media.updateRoomState(this.roomState, this.clientId);
        this.rebuildState();
        this.broadcastLocalMediaState();
    };

    private readonly handlePeerLeft = (event: PeerEvent): void => {
        if (this.isInactive) {
            return;
        }
        this.remoteMediaStates.delete(event.peerId);
        this.roomState = removeParticipant(this.roomState, event.peerId, this.clientId);
        this.media.updateRoomState(this.roomState, this.clientId);
        this.rebuildState();
    };

    private readonly handlePeerMessage = (message: PeerMessage): void => {
        if (this.isInactive) {
            return;
        }
        if (isMediaSignalingMessageType(message.type)) {
            this.media.processSignalingMessage(toMediaSignalingMessage(message));
        }
        if (message.type === 'participant_media_state') {
            this.handleRemoteMediaState(message);
        }
        for (const listener of this.peerMessageListeners) {
            try {
                listener(message);
            } catch (error) {
                this.config.logger?.log('error', 'Session', `onPeerMessage listener failed for ${message.type}: ${formatError(error)}`);
            }
        }
    };

    private readonly handleRoomEnded = (): void => {
        if (this.isInactive) {
            return;
        }
        this.cleanupCall();
    };

    private readonly handleError = (event: SignalingErrorEvent): void => {
        if (this.isInactive) {
            return;
        }
        this.failWithError(event);
    };

    private readonly handleIceServersChanged = (iceServers: RTCIceServer[]): void => {
        if (this.isInactive) {
            return;
        }
        this.media.setIceServers(iceServers);
    };

    private async fetchInitialIceServers(): Promise<void> {
        const generation = this.iceFetchGeneration + 1;
        this.iceFetchGeneration = generation;
        let lastError: unknown = null;

        for (const delayMs of ICE_FETCH_RETRY_DELAYS_MS) {
            if (delayMs > 0) {
                await this.wait(delayMs);
            }
            if (!this.isCurrentIceFetch(generation)) {
                return;
            }
            try {
                const iceServers = await this.signaling.getIceServers();
                if (!this.isCurrentIceFetch(generation)) {
                    return;
                }
                this.media.setIceServers(iceServers);
                return;
            } catch (error) {
                lastError = error;
            }
        }

        if (!this.isCurrentIceFetch(generation)) {
            return;
        }
        this.failWithError({
            code: 'ICE_SERVER_FETCH_FAILED',
            message: formatError(lastError),
        });
    }

    private isCurrentIceFetch(generation: number): boolean {
        return !this._destroyed && generation === this.iceFetchGeneration;
    }

    private invalidateIceFetches(): void {
        this.iceFetchGeneration += 1;
    }

    private wait(delayMs: number): Promise<void> {
        return new Promise((resolve) => {
            window.setTimeout(resolve, delayMs);
        });
    }

    private scheduleJoinTimeout(): void {
        if (this.isInactive) {
            return;
        }
        this.clearJoinTimeout();
        this.joinTimeoutTimer = window.setTimeout(() => {
            this.joinTimeoutTimer = null;
            if (this.isInactive || this.roomState || this.error || !this.pendingJoinOptions) {
                return;
            }
            this.failWithError({
                code: 'JOIN_TIMEOUT',
                message: 'Join timed out',
            });
        }, JOIN_HARD_TIMEOUT_MS);
    }

    private clearJoinTimeout(): void {
        if (this.joinTimeoutTimer !== null) {
            window.clearTimeout(this.joinTimeoutTimer);
            this.joinTimeoutTimer = null;
        }
    }

    private scheduleReconnect(): void {
        if (this.isInactive || this.handlesReconnection || !this.started) {
            return;
        }
        if (!this.pendingJoinOptions && !this.roomState && !this.clientId) {
            return;
        }
        if (this.reconnectTimer !== null) {
            return;
        }

        const attempt = this.reconnectAttempts + 1;
        const delayMs = Math.min(
            RECONNECT_BACKOFF_BASE_MS * Math.pow(2, attempt - 1),
            RECONNECT_BACKOFF_CAP_MS,
        );

        this.reconnectTimer = window.setTimeout(() => {
            this.reconnectTimer = null;
            this.reconnectAttempts = attempt;
            if (this.isInactive) {
                return;
            }
            this.pendingJoinOptions = { reconnectPeerId: this.clientId ?? undefined, displayName: this.displayName };
            this.signaling.connect();
        }, delayMs);
    }

    private clearReconnectTimer(): void {
        if (this.reconnectTimer !== null) {
            window.clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
    }

    private clearEndingTimer(): void {
        if (this.endingTimer !== null) {
            window.clearTimeout(this.endingTimer);
            this.endingTimer = null;
        }
    }

    private resetSessionResources(): void {
        this.clearReconnectTimer();
        this.clearJoinTimeout();
        this.clearEndingTimer();
        this.invalidateIceFetches();
        this.statsCollector.stop();

        this.started = false;
        this.isConnected = false;
        this.activeTransport = null;
        this.pendingJoinOptions = null;
        this.joinInFlight = false;
        this.reconnectRecoveryPending = false;
        this.reconnectAttempts = 0;
        this.roomState = null;
        this.clientId = null;
        this.remoteMediaStates.clear();

        this.media.updateRoomState(null, null);
        this.media.updateSignalingConnected(false);
        this.media.cleanupAllPeers();
        this.media.stopLocalMedia();

        this.signaling.disconnect();
    }

    private commitTerminalState(
        phase: CallState['phase'],
        error: CallState['error'] = null,
    ): void {
        this._state = {
            phase,
            roomId: this.roomId,
            roomUrl: this.roomUrl,
            localParticipant: null,
            remoteParticipants: [],
            connectionStatus: 'disconnected',
            activeTransport: null,
            requiredPermissions: null,
            error,
        };
        this.notifyListeners();
    }

    private cleanupCall(): void {
        this.terminated = true;
        this.error = null;
        this.resetSessionResources();
        this.commitTerminalState('ending');
        this.endingTimer = window.setTimeout(() => {
            this.endingTimer = null;
            if (this._destroyed) {
                return;
            }
            this.commitTerminalState('idle');
        }, ENDING_SCREEN_MS);
    }

    private failWithError(event: SignalingErrorEvent): void {
        this.terminated = true;
        this.error = event;
        this.resetSessionResources();
        this.commitTerminalState('error', {
            code: mapErrorCode(event.code),
            message: event.message,
        });
    }

    private setTrackEnabled(kind: 'audio' | 'video', enabled?: boolean): void {
        const stream = this.media.localStream;
        if (!stream) return;
        if (kind === 'video') {
            const videoTrack = stream.getVideoTracks()[0];
            const newEnabled = enabled ?? !(videoTrack?.enabled ?? this.userPreferredVideoEnabled);
            this.userPreferredVideoEnabled = newEnabled;
            const swap = newEnabled ? this.media.reacquireVideoTrack() : this.media.releaseVideoTrack();
            void swap.then(() => {
                if (!this.isInactive) {
                    this.broadcastLocalMediaState();
                    this.rebuildState();
                }
            });
            this.rebuildState();
        } else {
            const track = stream.getAudioTracks()[0];
            if (track) track.enabled = enabled ?? !track.enabled;
            this.broadcastLocalMediaState();
            this.rebuildState();
        }
    }

    private broadcastLocalMediaState(): void {
        const stream = this.media.localStream;
        const audioTrack = stream?.getAudioTracks()[0];
        const videoTrack = stream?.getVideoTracks()[0];
        // Audio: track is always present once media starts; we toggle the
        // `enabled` flag in place. Video: track may be absent (released to free
        // the camera) — derive from track presence so we never advertise
        // camera-on while reacquire is pending or has failed. Pre-media-start
        // (no stream) we fall back to the user's stated preference.
        this.signaling.broadcast('participant_media_state', {
            audioEnabled: audioTrack?.enabled ?? (this.config.defaultAudioEnabled !== false),
            videoEnabled: stream ? !!videoTrack && videoTrack.enabled : this.userPreferredVideoEnabled,
        });
    }

    private handleRemoteMediaState(message: PeerMessage): void {
        const payload = message.payload as Record<string, unknown> | null;
        if (!payload) return;
        const existing = this.remoteMediaStates.get(message.from);
        this.remoteMediaStates.set(message.from, {
            audioEnabled: typeof payload.audioEnabled === 'boolean' ? payload.audioEnabled : existing?.audioEnabled,
            videoEnabled: typeof payload.videoEnabled === 'boolean' ? payload.videoEnabled : existing?.videoEnabled,
        });
        this.rebuildState();
    }

    private rebuildState(): void {
        if (this.isInactive) return;
        const signalingState = this.roomState;
        const clientId = this.clientId;

        let phase = this._state.phase;

        if (this.error) {
            phase = 'error';
        } else if (!signalingState && phase !== 'idle' && phase !== 'ending') {
            if (this.isConnected && this._state.phase === 'joining') {
                phase = 'joining';
            }
        } else if (signalingState) {
            const hasRemote = (signalingState.participants?.length ?? 0) > 1;
            if (hasRemote) {
                phase = 'inCall';
                this.ensureStatsCollection();
            } else {
                phase = 'waiting';
            }

            if (!this.permissionCheckDone && !this.media.localStream) {
                void this.checkPermissionsAndStartMedia();
            }
        }

        const stream = this.media.localStream;
        const audioTrack = stream?.getAudioTracks()[0];
        const videoTrack = stream?.getVideoTracks()[0];

        const localParticipant = clientId ? {
            cid: clientId,
            displayName: this.displayName,
            audioEnabled: audioTrack?.enabled ?? (this.config.defaultAudioEnabled !== false),
            // Mirror broadcast: derive from real track presence/state so the
            // local UI matches what peers see. Pre-media-start (no stream),
            // fall back to the user's preference.
            videoEnabled: stream ? !!videoTrack && videoTrack.enabled : this.userPreferredVideoEnabled,
            cameraMode: (this.media.isScreenSharing
                ? 'screenShare'
                : this.media.facingMode === 'user'
                    ? 'selfie'
                    : 'world') as CameraMode,
            isHost: signalingState?.hostCid === clientId,
        } : null;

        const remoteParticipants = (signalingState?.participants ?? [])
            .filter((participant) => participant.cid !== clientId)
            .map((participant) => {
                const peerState = this.remoteMediaStates.get(participant.cid);
                return {
                    cid: participant.cid,
                    displayName: participant.displayName,
                    audioEnabled: peerState?.audioEnabled ?? participant.audioEnabled ?? true,
                    videoEnabled: peerState?.videoEnabled ?? participant.videoEnabled ?? true,
                    connectionState: this.media.connectionState,
                    signalingStatus: participant.connectionStatus ?? 'active',
                };
            });

        this._state = {
            phase,
            roomId: this.roomId,
            roomUrl: this.roomUrl,
            localParticipant,
            remoteParticipants,
            connectionStatus: this.media.connectionStatus as ConnectionStatus,
            activeTransport: this.activeTransport,
            requiredPermissions: this._state.requiredPermissions,
            error: this.error ? { code: mapErrorCode(this.error.code), message: this.error.message } : null,
        };

        this.notifyListeners();
    }

    private async checkPermissionsAndStartMedia(): Promise<void> {
        if (this.permissionCheckDone || this.permissionCheckInFlight) return;
        this.permissionCheckInFlight = true;

        const permissionsNeeded: MediaCapability[] = [];
        try {
            if (navigator.permissions) {
                const [cameraResult, micResult] = await Promise.all([
                    navigator.permissions.query({ name: 'camera' as PermissionName }).catch(() => null),
                    navigator.permissions.query({ name: 'microphone' as PermissionName }).catch(() => null),
                ]);
                if (cameraResult?.state === 'denied') permissionsNeeded.push('camera');
                if (micResult?.state === 'denied') permissionsNeeded.push('microphone');

                if (cameraResult?.state === 'prompt' || micResult?.state === 'prompt') {
                    const required: MediaCapability[] = [];
                    if (cameraResult?.state === 'prompt') required.push('camera');
                    if (micResult?.state === 'prompt') required.push('microphone');
                    this.permissionCheckInFlight = false;
                    this._state = { ...this._state, phase: 'awaitingPermissions', requiredPermissions: required };
                    this.notifyListeners();
                    this.onPermissionsRequired?.(required);
                    return;
                }
            }
        } catch {
            this.permissionCheckInFlight = false;
            const required: MediaCapability[] = ['camera', 'microphone'];
            this._state = { ...this._state, phase: 'awaitingPermissions', requiredPermissions: required };
            this.notifyListeners();
            this.onPermissionsRequired?.(required);
            return;
        }

        if (permissionsNeeded.length > 0) {
            this.permissionCheckInFlight = false;
            this._state = { ...this._state, phase: 'awaitingPermissions', requiredPermissions: permissionsNeeded };
            this.notifyListeners();
            this.onPermissionsRequired?.(permissionsNeeded);
            return;
        }

        this.permissionCheckDone = true;
        this.permissionCheckInFlight = false;
        await this.media.startLocalMedia();
        this.rebuildState();
    }

    private ensureStatsCollection(): void {
        if (this.statsCollector.stats !== null) return;
        this.statsCollector.start(
            () => this.media.getPeerConnections(),
            () => this.notifyListeners(),
        );
    }

    private notifyListeners(): void {
        const state = this._state;
        [...this.stateListeners].forEach((callback) => callback(state));
    }
}
