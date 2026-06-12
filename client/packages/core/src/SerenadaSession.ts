import type {
    ActiveTransport,
    CallErrorCode,
    CallQualitySummary,
    CallState,
    CallStats,
    CameraMode,
    ConfigurableCameraMode,
    ConnectionEvent,
    ConnectionStatus,
    DropoutTrigger,
    MediaCapability,
    SerenadaConfig,
    SerenadaSessionHandle,
    SignalingState,
    SnapshotResult,
    SnapshotSource,
} from './types.js';
import { CallQualityTracker } from './media/CallQualityTracker.js';
import { reconnectFailedReasonForCode } from './media/reconnectReason.js';
import {
    captureFrameFromStream,
    resolveSnapshotStream,
    SnapshotError,
} from './media/captureSnapshot.js';
import { resolveCameraModes } from './cameraModes.js';
import type {
    ConnectionInfo,
    SignalingErrorEvent,
    JoinOptions,
    NegotiationDirtyEvent,
    PeerEvent,
    PeerMessage,
    RelayFailedEvent,
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
    EPOCH_RESYNC_TIMEOUT_MS,
    MEDIA_LIVENESS_INTERVAL_MS,
    PEER_SUSPENDED_UI_TIMEOUT_MS,
    SUSPEND_HARD_EVICTION_TIMEOUT_MS,
} from './constants.js';
import { formatError } from './formatError.js';
import type { RoomParticipant, RoomState, SignalingMessage } from './signaling/types.js';

interface SessionDependencies {
    media?: MediaEngine;
    statsCollector?: CallStatsCollector;
    autoStart?: boolean;
    displayName?: string;
    peerId?: string;
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
        case 'INVALID_RECONNECT_TOKEN':
            return 'sessionExpired';
        case 'PERMISSION_DENIED':
            return 'permissionDenied';
        case 'MEDIA_UNSUPPORTED':
            return 'webrtcUnavailable';
        case 'LOCAL_MEDIA_FAILED':
            return 'mediaUnavailable';
        case 'CONNECTION_FAILED':
            return 'connectionFailed';
        case 'ICE_SERVER_FETCH_FAILED':
            return 'serverError';
        case 'BAD_REQUEST':
        case 'UNSUPPORTED_VERSION':
        case 'INVALID_ROOM_ID':
        case 'SERVER_NOT_CONFIGURED':
        case 'NOT_IN_ROOM':
        case 'NOT_HOST':
            return 'serverError';
        default:
            return 'unknown';
    }
}

/**
 * Monotonic millisecond clock for telemetry interval math:
 * a backward wall-clock step must not record a real dropout as 0ms. Prefers
 * `performance.now()`; falls back to `Date.now()` only where unavailable.
 */
function nowMonotonicMs(): number {
    const perf = (globalThis as { performance?: { now?: () => number } }).performance;
    return typeof perf?.now === 'function' ? perf.now() : Date.now();
}

function toRoomParticipant(participant: SignalingProviderParticipant): RoomParticipant {
    return {
        cid: participant.peerId,
        joinedAt: participant.joinedAt,
        displayName: participant.displayName,
        peerId: participant.appPeerId,
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
        {
            cid: event.peerId,
            joinedAt: event.joinedAt,
            displayName: event.displayName,
            peerId: event.appPeerId,
        },
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
    return type === 'content_state' ||
        type === 'offer' ||
        type === 'answer' ||
        type === 'ice' ||
        type === 'media_restart_request';
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
    private readonly appPeerId?: string;

    private _state: CallState;
    private stateListeners: Array<(state: CallState) => void> = [];
    private readonly peerMessageListeners = new Set<(message: PeerMessage) => void>();
    private readonly connectionEventListeners = new Set<(event: ConnectionEvent) => void>();
    private readonly providerUnsubscribers: Array<() => void> = [];

    // Aggregate call-quality tracker, driven by explicit
    // inputs. `_qualitySummary` is snapshotted at finalize and survives
    // teardown so hosts can read it after the session stops.
    private readonly qualityTracker: CallQualityTracker;
    private _qualitySummary: CallQualitySummary | null = null;
    private lastConnectionStatus: ConnectionStatus = 'connected';

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
    // True between transport reconnect and the first authoritative room_state
    // snapshot; gates ICE restart so it runs against a confirmed peer set.
    private pendingPostReconnectResync = false;
    private postReconnectResyncTimer: number | null = null;
    private iceFetchGeneration = 0;
    private started = false;
    private terminated = false;

    private isConnected = false;
    private activeTransport: ActiveTransport | null = null;
    private clientId: string | null = null;
    private roomState: RoomState | null = null;
    private error: SignalingErrorEvent | null = null;
    private readonly remoteMediaStates = new Map<string, { audioEnabled?: boolean; videoEnabled?: boolean }>();
    private readonly availableCameraModes: ConfigurableCameraMode[];
    private userPreferredVideoEnabled: boolean;

    // Wall-clock ms when the local transport last dropped while a roomState
    // was present (i.e. mid-call). Cleared on reconnect.
    private localSuspendedSinceMs: number | null = null;

    // After a peer transitions to suspended, we start a 30s timer; on expiry
    // we flip `presumedLost=true` for that CID so call UIs can move it out of
    // the active grid. Timers cancel when the peer goes back to active or is
    // removed from the room.
    private readonly suspendedPresentationTimers = new Map<string, number>();
    private readonly presumedLostRemoteCids = new Set<string>();

    // #3 — periodic `media_liveness` emission. Active across the in-call
    // window so the server can defer hard-eviction of suspended peers whose
    // media is still flowing locally. Emission skipped while transport is
    // disconnected (ticks just no-op).
    private mediaLivenessTimer: number | null = null;
    private mediaLivenessEmitInFlight = false;

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
        this.appPeerId = deps.peerId;
        this.availableCameraModes = Object.freeze(resolveCameraModes(config.cameraModes)) as ConfigurableCameraMode[];
        this.userPreferredVideoEnabled = this.availableCameraModes.length > 0 && config.defaultVideoEnabled !== false;

        this._state = {
            phase: 'joining',
            roomId,
            roomUrl,
            localParticipant: null,
            remoteParticipants: [],
            connectionStatus: 'connected',
            signalingState: { kind: 'connected' },
            activeTransport: null,
            requiredPermissions: null,
            error: null,
        };

        const initialCameraMode = this.availableCameraModes[0];
        this.media = deps.media ?? new MediaEngine(
            {
                turnsOnly: config.turnsOnly,
                logger: config.logger,
                initialFacingMode: initialCameraMode === 'world' ? 'environment' : 'user',
                initialVideoEnabled: this.userPreferredVideoEnabled,
                videoCaptureSupported: this.availableCameraModes.length > 0,
            },
            (type, payload, to) => {
                if (to) {
                    this.signaling.sendToPeer(to, type, payload);
                    return;
                }
                this.signaling.broadcast(type, payload);
            },
        );
        this.statsCollector = deps.statsCollector ?? new CallStatsCollector(config.logger);
        this.qualityTracker = new CallQualityTracker((event) => this.dispatchConnectionEvent(event));

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
    /**
     * Aggregate call-quality summary. Reflects the live
     * tracker while in-call and the finalized snapshot after the call ends;
     * stays readable after teardown.
     */
    get callQualitySummary(): CallQualitySummary | null {
        return this._qualitySummary ?? this.qualityTracker.summarize();
    }
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

    onConnectionEvent(callback: (event: ConnectionEvent) => void): () => void {
        this.connectionEventListeners.add(callback);
        return () => {
            this.connectionEventListeners.delete(callback);
        };
    }

    private dispatchConnectionEvent(event: ConnectionEvent): void {
        for (const listener of this.connectionEventListeners) {
            try {
                listener(event);
            } catch (error) {
                this.config.logger?.log('error', 'Session', `onConnectionEvent listener failed for ${event.kind}: ${formatError(error)}`);
            }
        }
    }

    /** Resume joining after media permissions have been granted. */
    async resumeJoin(): Promise<void> {
        if (this.isInactive) {
            return;
        }
        this.permissionCheckDone = true;
        const stream = await this.media.startLocalMedia();
        if (this.isInactive) {
            return;
        }
        if (stream) {
            this.broadcastLocalMediaState();
            this.rebuildState();
        } else {
            this.failOnLocalMediaError();
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
        this.finalizeQuality();
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
        if (mode !== 'selfie' && mode !== 'world') return;
        if (!this.availableCameraModes.includes(mode)) return;
        if (mode === 'world' && this.media.facingMode === 'user') {
            void this.media.flipCamera();
        } else if (mode === 'selfie' && this.media.facingMode === 'environment') {
            void this.media.flipCamera();
        }
    }

    /** Cycle to the next camera mode in the configured order. */
    async flipCamera(): Promise<void> {
        if (this.availableCameraModes.length <= 1) return;
        await this.media.flipCamera();
    }

    /** Start sharing the screen, replacing the camera video track. */
    async startScreenShare(): Promise<void> {
        const wasScreenSharing = this.media.isScreenSharing;
        await this.media.startScreenShare();
        if (!this.isInactive && !wasScreenSharing && this.media.isScreenSharing) {
            this.broadcastLocalMediaState();
            this.rebuildState();
        }
    }

    /** Stop screen sharing and restore the camera video track. */
    async stopScreenShare(): Promise<void> {
        const wasScreenSharing = this.media.isScreenSharing;
        await this.media.stopScreenShare();
        if (!this.isInactive && wasScreenSharing && !this.media.isScreenSharing) {
            this.broadcastLocalMediaState();
            this.rebuildState();
        }
    }

    /**
     * Capture the current video frame from the chosen stream as an
     * `image/jpeg` Blob at the source track's full intrinsic resolution.
     * Rejects with a `SnapshotError` if the stream is missing/inactive,
     * the chosen participant has video off, or no frame arrives in time.
     */
    async captureSnapshot(source: SnapshotSource = { kind: 'local' }): Promise<SnapshotResult> {
        if (this.isInactive) {
            throw new SnapshotError('streamNotActive', 'Session is not active');
        }
        if (source.kind === 'local') {
            if (this._state.localParticipant?.videoEnabled !== true) {
                throw new SnapshotError('streamNotActive', 'Local video is not enabled');
            }
        } else if (source.kind === 'remote') {
            const participant = this._state.remoteParticipants.find((p) => p.cid === source.cid);
            if (!participant) {
                throw new SnapshotError('streamNotActive', `Remote participant ${source.cid} is not joined`);
            }
            if (!participant.videoEnabled) {
                throw new SnapshotError('streamNotActive', `Remote participant ${source.cid} has video off`);
            }
        }
        const stream = resolveSnapshotStream(source, this.media.localStream, this.media.remoteStreams);
        const { blob, width, height } = await captureFrameFromStream(stream);
        return { blob, width, height, timestampMs: Date.now(), source };
    }

    /** Clean up all resources. Call when done with the session. */
    destroy(): void {
        if (this._destroyed) return;
        this._destroyed = true;
        // Snapshot the quality summary before stats/media teardown so a host
        // that tears down via `destroy()` directly (React UI cleanup) still
        // reads a finalized summary — including an open dropout at teardown.
        this.finalizeQuality();
        this.invalidateIceFetches();
        this.clearReconnectTimer();
        this.clearEndingTimer();
        this.clearJoinTimeout();
        this.cancelPostReconnectResync();
        this.clearAllRemoteSuspensionTracking();
        this.stopMediaLivenessTimer();
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
            appPeerId: this.appPeerId,
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
        this.bindProviderEvent('negotiationDirty', this.handleNegotiationDirty);
        this.bindProviderEvent('relayFailed', this.handleRelayFailed);
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
        this.localSuspendedSinceMs = null;
        this.clearReconnectTimer();
        this.reconnectAttempts = 0;
        this.media.updateSignalingConnected(true);

        if (this.pendingJoinOptions && !this.joinInFlight) {
            this.joinInFlight = true;
            this.signaling.joinRoom(this.roomId, this.pendingJoinOptions);
        } else if (!wasConnected && this.handlesReconnection && this.reconnectRecoveryPending && this.roomState) {
            this.reconnectRecoveryPending = false;
            this.armPostReconnectResync();
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
        if (hadRoomState && this.localSuspendedSinceMs === null) {
            this.localSuspendedSinceMs = Date.now();
        }
        this.media.updateSignalingConnected(false);

        if (this.handlesReconnection) {
            this.reconnectRecoveryPending = hadRoomState;
        } else {
            this.pendingJoinOptions = {
                reconnectPeerId: this.clientId ?? undefined,
                displayName: this.displayName,
                appPeerId: this.appPeerId,
            };
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
        this.maybeStartMediaLivenessTimer();
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
        this.maybeStartMediaLivenessTimer();
        this.flushPostReconnectResync('snapshot');
        this.rebuildState();
    };

    private readonly handlePeerJoined = (event: PeerEvent): void => {
        if (this.isInactive) {
            return;
        }
        this.error = null;
        this.roomState = upsertParticipant(this.roomState, event, this.clientId);
        this.media.updateRoomState(this.roomState, this.clientId);
        this.maybeStartMediaLivenessTimer();
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
        if (event.code === 'TURN_REFRESH_FAILED') {
            // Non-fatal: media keeps flowing on the existing credentials until expiry.
            this.config.logger?.log('warning', 'Session', `TURN refresh failed: ${event.message}`);
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

    private readonly handleNegotiationDirty = (event: NegotiationDirtyEvent): void => {
        if (this.isInactive) {
            return;
        }
        this.media.scheduleDirtyPairRestart(event.withCid);
    };

    private readonly handleRelayFailed = (event: RelayFailedEvent): void => {
        if (this.isInactive) {
            return;
        }
        // The server has the dirty-pair record; once the suspended target
        // reattaches we'll get `negotiation_dirty` and renegotiate then.
        // For now, just surface in logs so suppressed offers/ICE are visible.
        this.config.logger?.log(
            'debug',
            'Session',
            `relay_failed reason=${event.reason} of=${event.of ?? 'n/a'} targets=${event.targets.join(',')}`,
        );
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
            this.pendingJoinOptions = {
                reconnectPeerId: this.clientId ?? undefined,
                displayName: this.displayName,
                appPeerId: this.appPeerId,
            };
            this.signaling.connect();
            this.rebuildState();
        }, delayMs);
    }

    private clearReconnectTimer(): void {
        if (this.reconnectTimer !== null) {
            window.clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
    }

    private armPostReconnectResync(): void {
        if (this.postReconnectResyncTimer !== null) {
            window.clearTimeout(this.postReconnectResyncTimer);
        }
        this.pendingPostReconnectResync = true;
        this.postReconnectResyncTimer = window.setTimeout(() => {
            this.postReconnectResyncTimer = null;
            this.flushPostReconnectResync('timeout');
        }, EPOCH_RESYNC_TIMEOUT_MS);
    }

    private flushPostReconnectResync(reason: 'snapshot' | 'timeout'): void {
        if (!this.pendingPostReconnectResync) {
            return;
        }
        this.cancelPostReconnectResync();
        if (reason === 'timeout') {
            this.config.logger?.log(
                'warning',
                'Session',
                `Post-reconnect snapshot timeout after ${EPOCH_RESYNC_TIMEOUT_MS}ms; firing ICE restart against last-known peer map`,
            );
        }
        this.media.handleSignalingReconnect();
    }

    private cancelPostReconnectResync(): void {
        this.pendingPostReconnectResync = false;
        if (this.postReconnectResyncTimer !== null) {
            window.clearTimeout(this.postReconnectResyncTimer);
            this.postReconnectResyncTimer = null;
        }
    }

    private clearEndingTimer(): void {
        if (this.endingTimer !== null) {
            window.clearTimeout(this.endingTimer);
            this.endingTimer = null;
        }
    }

    /**
     * Finalize the quality summary and snapshot it so it survives teardown.
     * Must run BEFORE `resetSessionResources()`/`statsCollector.stop()`.
     * Idempotent — the first call wins.
     */
    private finalizeQuality(): void {
        if (this._qualitySummary !== null) return;
        this.qualityTracker.finalize(nowMonotonicMs());
        this._qualitySummary = this.qualityTracker.summarize();
    }

    private resetSessionResources(): void {
        this.clearReconnectTimer();
        this.clearJoinTimeout();
        this.clearEndingTimer();
        this.cancelPostReconnectResync();
        this.clearAllRemoteSuspensionTracking();
        this.stopMediaLivenessTimer();
        this.invalidateIceFetches();
        this.statsCollector.stop();

        this.started = false;
        this.isConnected = false;
        this.activeTransport = null;
        this.pendingJoinOptions = null;
        this.joinInFlight = false;
        this.reconnectRecoveryPending = false;
        this.reconnectAttempts = 0;
        this.localSuspendedSinceMs = null;
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
        const signalingState: SignalingState = error
            ? { kind: 'failed', reason: error.code }
            : this._state.signalingState;
        this._state = {
            phase,
            roomId: this.roomId,
            roomUrl: this.roomUrl,
            localParticipant: null,
            remoteParticipants: [],
            connectionStatus: 'disconnected',
            signalingState,
            activeTransport: null,
            requiredPermissions: null,
            error,
        };
        this.notifyListeners();
    }

    private cleanupCall(): void {
        this.terminated = true;
        this.error = null;
        this.finalizeQuality();
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
        // Emit reconnectFailed only on concrete terminal
        // recovery-abandonment paths, and only for a call that actually
        // reached `inCall` (the host's reliability events key off a
        // connected call). Never for user hangup / remote-ended.
        if (this.qualityTracker.hasStartedSampling()) {
            const reconnectFailedReason = reconnectFailedReasonForCode(event.code);
            if (reconnectFailedReason !== null) {
                this.qualityTracker.reportReconnectFailed(reconnectFailedReason);
            }
        }
        this.finalizeQuality();
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
            if (this.availableCameraModes.length === 0) return;
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

        const previousPhase = this._state.phase;
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
            peerId: this.appPeerId,
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
            availableCameraModes: this.availableCameraModes,
            isHost: signalingState?.hostCid === clientId,
        } : null;

        this.reconcileRemoteSuspensionTimers(signalingState?.participants ?? []);

        const remoteParticipants = (signalingState?.participants ?? [])
            .filter((participant) => participant.cid !== clientId)
            .map((participant) => {
                const peerState = this.remoteMediaStates.get(participant.cid);
                const status = participant.connectionStatus ?? 'active';
                return {
                    cid: participant.cid,
                    displayName: participant.displayName,
                    peerId: participant.peerId,
                    audioEnabled: peerState?.audioEnabled ?? participant.audioEnabled ?? true,
                    videoEnabled: peerState?.videoEnabled ?? participant.videoEnabled ?? true,
                    connectionState: this.media.connectionState,
                    signalingStatus: status,
                    presumedLost: status === 'suspended' && this.presumedLostRemoteCids.has(participant.cid),
                };
            });

        const errorPayload = this.error ? { code: mapErrorCode(this.error.code), message: this.error.message } : null;
        const connectionStatus = this.media.connectionStatus as ConnectionStatus;

        // Drive the quality tracker from the freshly computed
        // phase and connection status. Sampling/dropout tracking only begins
        // once the tracker sees the first `inCall` transition.
        this.feedQualityTracker(previousPhase, phase, connectionStatus);
        // `feedQualityTracker` can synchronously emit a `reconnected` event to
        // a host `onConnectionEvent` handler that calls `leave()`/`destroy()`.
        // Re-check before assigning `_state` / notifying so we don't deliver a
        // post-teardown state or re-run teardown side effects.
        if (this.isInactive) return;

        this._state = {
            phase,
            roomId: this.roomId,
            roomUrl: this.roomUrl,
            localParticipant,
            remoteParticipants,
            connectionStatus,
            signalingState: this.computeSignalingState(errorPayload),
            activeTransport: this.activeTransport,
            requiredPermissions: this._state.requiredPermissions,
            error: errorPayload,
        };

        this.notifyListeners();
    }

    /**
     * Feed phase + connection-status transitions to the quality tracker.
     * The dropout **trigger** is derived at the transition: a degradation
     * driven by lost signaling is `networkLost`; an ICE/peer-level
     * degradation while signaling is up is `unknown`.
     */
    private feedQualityTracker(
        previousPhase: CallState['phase'],
        nextPhase: CallState['phase'],
        nextStatus: ConnectionStatus,
    ): void {
        const now = nowMonotonicMs();
        if (nextPhase !== previousPhase) {
            this.qualityTracker.onPhaseTransition(nextPhase, now);
        }
        if (nextStatus !== this.lastConnectionStatus) {
            const trigger: DropoutTrigger = !this.isConnected ? 'networkLost' : 'unknown';
            this.qualityTracker.onConnectionStatusTransition(
                nextStatus,
                trigger,
                now,
            );
            this.lastConnectionStatus = nextStatus;
        }
    }

    /**
     * Compute the public {@link SignalingState} surface from current internal
     * state. Mid-call transport drops surface as `suspended` (carries
     * `suspendedSinceMs` + estimated hard-eviction deadline); pre-join drops
     * surface as `reconnecting`. Terminal errors map to `failed`.
     */
    private computeSignalingState(error: CallState['error']): SignalingState {
        if (error) {
            return { kind: 'failed', reason: error.code };
        }
        if (this.isConnected) {
            return { kind: 'connected' };
        }
        if (this.localSuspendedSinceMs !== null) {
            return {
                kind: 'suspended',
                suspendedSinceMs: this.localSuspendedSinceMs,
                estimatedHardEvictionAtMs: this.localSuspendedSinceMs + SUSPEND_HARD_EVICTION_TIMEOUT_MS,
            };
        }
        return {
            kind: 'reconnecting',
            attempt: this.reconnectAttempts,
            nextRetryAtMs: null,
        };
    }

    /**
     * Walk the latest authoritative participant list and start/cancel per-CID
     * suspended-presentation timers. Cancels cleanly when peers go back to
     * active or are removed; flips `presumedLost=true` on timer expiry.
     *
     * "Already presumed lost" is a sticky state: once the timer has fired,
     * we don't reschedule a new one if the peer remains suspended across
     * subsequent room_state updates. The flag clears the moment the peer
     * transitions back to active or leaves the room.
     */
    private reconcileRemoteSuspensionTimers(participants: RoomParticipant[]): void {
        const remoteCids = new Set<string>();
        for (const participant of participants) {
            if (participant.cid === this.clientId) {
                continue;
            }
            remoteCids.add(participant.cid);
            const isSuspended = participant.connectionStatus === 'suspended';
            const hasTimer = this.suspendedPresentationTimers.has(participant.cid);
            const isPresumedLost = this.presumedLostRemoteCids.has(participant.cid);
            if (isSuspended) {
                if (!hasTimer && !isPresumedLost) {
                    this.startRemoteSuspensionTimer(participant.cid);
                }
            } else {
                this.clearRemoteSuspensionTracking(participant.cid);
            }
        }
        const trackedCids = new Set<string>([
            ...this.suspendedPresentationTimers.keys(),
            ...this.presumedLostRemoteCids,
        ]);
        for (const cid of trackedCids) {
            if (!remoteCids.has(cid)) {
                this.clearRemoteSuspensionTracking(cid);
            }
        }
    }

    private startRemoteSuspensionTimer(cid: string): void {
        const handle = window.setTimeout(() => {
            if (this.isInactive) return;
            this.suspendedPresentationTimers.delete(cid);
            this.presumedLostRemoteCids.add(cid);
            this.config.logger?.log(
                'info',
                'Session',
                `Remote ${cid} presumed lost after ${PEER_SUSPENDED_UI_TIMEOUT_MS}ms suspended`,
            );
            this.rebuildState();
        }, PEER_SUSPENDED_UI_TIMEOUT_MS);
        this.suspendedPresentationTimers.set(cid, handle);
    }

    /**
     * Clear all per-CID suspension state (timer + presumed-lost flag).
     * Called when a peer transitions back to active, leaves the room, or
     * the session is reset.
     */
    private clearRemoteSuspensionTracking(cid: string): void {
        const handle = this.suspendedPresentationTimers.get(cid);
        if (handle !== undefined) {
            window.clearTimeout(handle);
            this.suspendedPresentationTimers.delete(cid);
        }
        this.presumedLostRemoteCids.delete(cid);
    }

    private clearAllRemoteSuspensionTracking(): void {
        for (const handle of this.suspendedPresentationTimers.values()) {
            window.clearTimeout(handle);
        }
        this.suspendedPresentationTimers.clear();
        this.presumedLostRemoteCids.clear();
    }

    /**
     * Periodic `media_liveness{cids}` emission for #3. Started once we have
     * remote peers (i.e. the call reaches `inCall`); runs across reconnects
     * (ticks no-op while disconnected but baseline samples persist so the
     * next post-reconnect tick can detect flow). Stopped on session
     * reset/destroy.
     */
    private maybeStartMediaLivenessTimer(): void {
        if (this.mediaLivenessTimer !== null) return;
        const remoteCount = (this.roomState?.participants?.length ?? 0) - (this.clientId ? 1 : 0);
        if (remoteCount <= 0) return;
        this.mediaLivenessTimer = window.setInterval(() => {
            void this.emitMediaLiveness();
        }, MEDIA_LIVENESS_INTERVAL_MS);
    }

    private stopMediaLivenessTimer(): void {
        if (this.mediaLivenessTimer !== null) {
            window.clearInterval(this.mediaLivenessTimer);
            this.mediaLivenessTimer = null;
        }
    }

    private async emitMediaLiveness(): Promise<void> {
        if (this.isInactive || this.mediaLivenessEmitInFlight) return;
        if (!this.isConnected || this.roomState === null) return;
        this.mediaLivenessEmitInFlight = true;
        try {
            const flowing = await this.media.getInboundFlowingCids();
            if (this.isInactive || flowing.length === 0) return;
            this.signaling.broadcast('media_liveness', { cids: flowing });
        } catch (error) {
            this.config.logger?.log(
                'debug',
                'Session',
                `media_liveness emit failed: ${formatError(error)}`,
            );
        } finally {
            this.mediaLivenessEmitInFlight = false;
        }
    }

    private async checkPermissionsAndStartMedia(): Promise<void> {
        if (this.permissionCheckDone || this.permissionCheckInFlight) return;
        this.permissionCheckInFlight = true;

        const needsCamera = this.availableCameraModes.length > 0 && this.userPreferredVideoEnabled;
        const permissionsNeeded: MediaCapability[] = [];
        try {
            if (navigator.permissions) {
                const [cameraResult, micResult] = await Promise.all([
                    needsCamera
                        ? navigator.permissions.query({ name: 'camera' as PermissionName }).catch(() => null)
                        : Promise.resolve(null),
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
            const required: MediaCapability[] = needsCamera ? ['camera', 'microphone'] : ['microphone'];
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
        const stream = await this.media.startLocalMedia();
        if (this.isInactive) {
            return;
        }
        if (!stream) {
            this.failOnLocalMediaError();
            return;
        }
        this.rebuildState();
    }

    /**
     * Surface a failed local-media acquisition as a terminal error instead of
     * silently joining without media (or hanging in `awaitingPermissions`).
     * A null stream without a recorded error means the attempt was superseded
     * (e.g. concurrent stop) — not a failure.
     */
    private failOnLocalMediaError(): void {
        const mediaError = this.media.lastLocalMediaError;
        if (!mediaError) {
            return;
        }
        const code = mediaError.name === 'NotAllowedError' || mediaError.name === 'PermissionDeniedError' || mediaError.name === 'SecurityError'
            ? 'PERMISSION_DENIED'
            : (mediaError.name === 'NotSupportedError' ? 'MEDIA_UNSUPPORTED' : 'LOCAL_MEDIA_FAILED');
        this.failWithError({ code, message: mediaError.message });
    }

    private ensureStatsCollection(): void {
        if (this.statsCollector.stats !== null) return;
        this.statsCollector.start(
            () => this.media.getPeerConnections(),
            () => {
                const stats = this.statsCollector.stats;
                if (stats !== null) {
                    this.qualityTracker.onStatsSample(stats, nowMonotonicMs());
                }
                this.notifyListeners();
            },
        );
    }

    private notifyListeners(): void {
        const state = this._state;
        [...this.stateListeners].forEach((callback) => callback(state));
    }
}
