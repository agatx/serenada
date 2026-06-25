import type { SignalingProvider, PeerMessage } from './SignalingProvider.js';
import type { TransportKind } from './signaling/transports/types.js';
import type { RoomStatus, RoomStatuses } from './signaling/roomStatuses.js';

/** Current phase of the call lifecycle. */
export type CallPhase = 'idle' | 'awaitingPermissions' | 'joining' | 'waiting' | 'inCall' | 'ending' | 'error';

/** Network connection status between the client and signaling server. */
export type ConnectionStatus = 'connected' | 'recovering' | 'retrying' | 'disconnected';

/** Active signaling transport, including custom provider-specific transport labels. */
export type ActiveTransport = TransportKind | (string & {});

/** Camera mode: selfie (front), world (rear), composite (picture-in-picture), or screen share. */
export type CameraMode = 'selfie' | 'world' | 'composite' | 'screenShare';

/** Subset of {@link CameraMode} that can be configured via {@link SerenadaConfig.cameraModes}. */
export type ConfigurableCameraMode = Exclude<CameraMode, 'screenShare'>;

/** Default preference order for camera modes when {@link SerenadaConfig.cameraModes} is unset. */
export const DEFAULT_CAMERA_MODES: readonly ConfigurableCameraMode[] = ['selfie', 'world', 'composite'];

/** Device media capability that may require user permission. */
export type MediaCapability = 'camera' | 'microphone';

/** WebRTC peer connection state. */
export type PeerConnectionState = 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed';

/**
 * Signaling connection status of a remote participant as reported by the
 * server. `'active'` means the participant is currently connected to the
 * signaling server; `'suspended'` means their signaling transport dropped
 * and the server is holding their slot open for reconnect — the peer
 * connection to them is intentionally kept alive and UIs should surface a
 * "reconnecting" indicator instead of rendering them as gone.
 */
export type ParticipantSignalingStatus = 'active' | 'suspended';

/**
 * Active content (screen share) presentation state for a participant. Surfaced
 * on both {@link Participant} and {@link LocalParticipant}. Absent when the
 * participant is not currently sharing content.
 */
export interface ParticipantContent {
    /** `true` while the participant is presenting content. */
    active: boolean;
    /** Content kind. Currently always `'screenShare'`. */
    type: string;
    /**
     * Per-participant monotonic generation marker for the content state, scoped
     * to the sender's current session. Orders presentation-state changes; does
     * not bind RTP media to a share.
     */
    revision: number;
}

/** Remote participant in a call. */
export interface Participant {
    cid: string;
    displayName?: string;
    /**
     * Host-supplied stable identity passed via {@link SerenadaCore.join} (the
     * `peerId` option). Distinct from {@link cid} (per-call, server-issued) —
     * used by the call UI to look up avatars or correlate to host-side records.
     * Absent when the remote peer didn't supply one.
     */
    peerId?: string;
    audioEnabled: boolean;
    /**
     * Whether the participant's camera video specifically is enabled. Precise
     * camera signal distinct from {@link videoEnabled} (which carries the legacy
     * "any video active" meaning for older callers). Currently mirrors
     * {@link videoEnabled}; independent screen-share state is carried by
     * {@link content}.
     */
    cameraEnabled: boolean;
    videoEnabled: boolean;
    /**
     * Active content (screen share) presentation state, or absent when the
     * participant is not currently sharing content. Driven by received
     * `content_state`.
     */
    content?: ParticipantContent;
    /**
     * Per-role inbound media liveness, sampled from this peer's inbound RTP.
     * `cameraReceiving` is `true` while the peer's CAMERA video bytes are
     * advancing; `contentReceiving` is `true` while its CONTENT (screen share)
     * video bytes are advancing. Each is only meaningful when that role's track
     * is expected/active — derive a stall as `content?.active && !contentReceiving`
     * (and analogously for the camera). Drives the per-role stall diagnostics so
     * a consumer can tell a frozen screen share apart from a healthy camera, and
     * which peer ({@link cid}) it is.
     *
     * Flag off / legacy peers: the single inbound video routes to the camera
     * role, so `cameraReceiving` tracks that one video and `contentReceiving`
     * stays `false`. Both default to `false` before the first liveness sample.
     * Audio liveness is not split here (it stays in the global `media_liveness`
     * signal). Additive: existing fields are unchanged.
     */
    cameraReceiving: boolean;
    contentReceiving: boolean;
    connectionState: PeerConnectionState;
    signalingStatus: ParticipantSignalingStatus;
    /**
     * `true` when this peer has been suspended longer than
     * `PEER_SUSPENDED_UI_TIMEOUT_MS` and the SDK has flipped its UI
     * presentation to "presumed lost." The peer connection is intentionally
     * left open so media can resume immediately if the peer reattaches; this
     * flag is purely a UI hint that call shells can use to move the
     * participant out of the active grid or show a "connection lost" badge.
     * Cleared when the peer transitions back to `signalingStatus="active"`.
     */
    presumedLost: boolean;
}

/** Local participant info including camera mode and host status. */
export interface LocalParticipant {
    cid: string;
    displayName?: string;
    /** Host-supplied stable identity — see {@link Participant.peerId}. */
    peerId?: string;
    audioEnabled: boolean;
    /**
     * Whether the local camera video specifically is enabled. Precise camera
     * signal distinct from {@link videoEnabled}. Currently mirrors
     * {@link videoEnabled}; independent screen-share state is carried by
     * {@link content}.
     */
    cameraEnabled: boolean;
    videoEnabled: boolean;
    /**
     * Active local content (screen share) presentation state, or absent when
     * content is not currently being shared. Driven by local screen-share state.
     */
    content?: ParticipantContent;
    cameraMode: CameraMode;
    /**
     * Camera modes the user can cycle through, in preference order.
     * Derived from {@link SerenadaConfig.cameraModes} minus modes unsupported
     * on this device/platform. An empty array means camera video is unavailable
     * — the call UI should hide the camera video toggle.
     */
    availableCameraModes: ConfigurableCameraMode[];
    isHost: boolean;
}

/** Error codes for call failures. */
export type CallErrorCode =
    | 'signalingTimeout'
    | 'connectionFailed'
    | 'roomFull'
    | 'roomEnded'
    | 'sessionExpired'
    | 'permissionDenied'
    | 'serverError'
    | 'webrtcUnavailable'
    | 'mediaUnavailable'
    | 'unknown';

/** Error with a machine-readable code and human-readable message. */
export interface CallError {
    code: CallErrorCode;
    message: string;
}

/**
 * Source for a video snapshot — either the local stream or a specific remote
 * participant's stream identified by their per-call CID.
 */
export type SnapshotSource =
    | { kind: 'local' }
    | { kind: 'remote'; cid: string };

/** Error codes returned by {@link SerenadaSessionHandle.captureSnapshot}. */
export type SnapshotErrorCode =
    | 'streamNotActive'
    | 'noVideoTrack'
    | 'captureTimeout'
    | 'captureFailed'
    | 'unsupportedSource';

/**
 * Result of a successful video snapshot. The blob holds the encoded image
 * (`image/jpeg` by default) at the source video track's full intrinsic
 * resolution — `width` and `height` are pixels, not CSS units.
 */
export interface SnapshotResult {
    blob: Blob;
    width: number;
    height: number;
    /** Wall-clock time the snapshot was decoded, from `Date.now()`. */
    timestampMs: number;
    source: SnapshotSource;
}

/**
 * Richer view of the local signaling transport state. Apps can use this to
 * render reconnect spinners, "you have been disconnected" UI, and a hard-
 * eviction countdown when applicable. {@link CallState.connectionStatus}
 * remains the simpler four-value summary.
 */
export type SignalingState =
    | { kind: 'connected' }
    | {
          kind: 'reconnecting';
          /** Number of consecutive reconnect attempts since transport last dropped. */
          attempt: number;
          /** Wall-clock ms for the next scheduled retry, or `null` if a retry is in flight. */
          nextRetryAtMs: number | null;
      }
    | {
          kind: 'suspended';
          /** Wall-clock ms when the local transport last dropped. */
          suspendedSinceMs: number;
          /**
           * Wall-clock ms when the server is expected to hard-evict the slot
           * absent a successful reconnect. Computed locally from
           * `suspendedSinceMs + SUSPEND_HARD_EVICTION_TIMEOUT_MS`. Best-effort
           * — server media-liveness hints can extend retention.
           */
          estimatedHardEvictionAtMs: number;
      }
    | { kind: 'failed'; reason: CallErrorCode };

/**
 * Primary observable call state. This is the main state object consumers subscribe to
 * via {@link SerenadaSessionHandle.subscribe}.
 */
export interface CallState {
    phase: CallPhase;
    roomId: string | null;
    roomUrl: string | null;
    localParticipant: LocalParticipant | null;
    remoteParticipants: Participant[];
    connectionStatus: ConnectionStatus;
    /**
     * Richer signaling-transport state with timing details. Apps that don't
     * need the extra detail can stick with {@link connectionStatus}.
     */
    signalingState: SignalingState;
    activeTransport: ActiveTransport | null;
    requiredPermissions: MediaCapability[] | null;
    error: CallError | null;
}

/** SDK configuration passed to {@link SerenadaCore}. */
export interface SerenadaConfig {
    /** Bare host or full origin, e.g. `serenada.app` or `http://qa-box:8080`. */
    serverHost?: string;
    /** Custom signaling provider. Provide exactly one of `serverHost` or `signalingProvider`. */
    signalingProvider?: SignalingProvider;
    /** Whether the microphone is enabled when joining. Defaults to `true`. */
    defaultAudioEnabled?: boolean;
    /** Whether the camera is enabled when joining. Defaults to `true`. */
    defaultVideoEnabled?: boolean;
    /**
     * Whether this call can negotiate any video media. Set to `false` for strict
     * audio-only calls such as PSTN: camera capture, screen sharing, and remote
     * video are all disabled. Defaults to `true`.
     */
    videoMediaEnabled?: boolean;
    /**
     * Static capability gate for the independent screen-share content stream.
     * When `true`, this build can negotiate a dedicated content video stream
     * separate from the camera. Advertised to peers at join via
     * `capabilities.independentContentVideo`. Immutable per session. Defaults to
     * `false`. When enabled, capable peers use the independent content media path;
     * legacy peers continue to use the single-video fallback.
     */
    enableIndependentContentVideo?: boolean;
    /**
     * Camera modes available in the call UI, in preference order. The first
     * entry is the initial mode. When only one mode is listed the flip-camera
     * control is hidden; an empty array disables camera capture (the video
     * toggle is hidden and the camera is never requested). Remote video and
     * screen sharing remain available unless `videoMediaEnabled` is `false`.
     * Modes unsupported on the current platform or device are silently dropped
     * (`'composite'` is always dropped on web). Defaults to
     * `['selfie', 'world', 'composite']`.
     */
    cameraModes?: ConfigurableCameraMode[];
    /**
     * When `true`, defer the initial-negotiation offer-timeout/ICE-restart while the host peer
     * awaits its first answer. Use for app-owned calls whose answer is gated on a remote action
     * that may take longer than the offer timeout, such as PSTN pickup. Defaults to `false`.
     */
    deferInitialAnswer?: boolean;
    /** Signaling transport priority order. Defaults to `['ws', 'sse']`. */
    transports?: TransportKind[];
    /** When `true`, only use TURNS (TLS) relay candidates. */
    turnsOnly?: boolean;
    /** Custom logger for SDK diagnostic output. */
    logger?: SerenadaLogger;
}

/** Result of creating a new room via {@link SerenadaCore.createRoom}. */
export interface CreateRoomResult {
    url: string;
    roomId: string;
}

/**
 * Public interface for an active call session. Consumers should use this
 * instead of the concrete {@link SerenadaSession} class.
 */
export interface SerenadaSessionHandle {
    subscribe(callback: (state: CallState) => void): () => void;
    onPeerMessage(callback: (message: PeerMessage) => void): () => void;
    /**
     * Subscribe to connection-quality events (reconnected / reconnect failed).
     * Returns an unsubscribe function. Mirrors {@link onPeerMessage}.
     */
    onConnectionEvent(callback: (event: ConnectionEvent) => void): () => void;
    leave(): void;
    end(): void;
    toggleAudio(): void;
    toggleVideo(): void;
    flipCamera(): Promise<void>;
    setAudioEnabled(enabled: boolean): void;
    setVideoEnabled(enabled: boolean): void;
    setCameraMode(mode: CameraMode): void;
    startScreenShare(): Promise<void>;
    stopScreenShare(): Promise<void>;
    /**
     * Capture the current video frame from the chosen stream at full
     * intrinsic resolution. Defaults to the local stream. Rejects with a
     * `SnapshotError` (code `'streamNotActive'`) when the stream is missing
     * or has no live video track.
     */
    captureSnapshot(source?: SnapshotSource): Promise<SnapshotResult>;
    resumeJoin(): Promise<void>;
    cancelJoin(): void;
    destroy(): void;
    readonly state: CallState;
    readonly localStream: MediaStream | null;
    readonly remoteStreams: Map<string, MediaStream>;
    /**
     * Remote camera stream for a participant, or `undefined`. In independent
     * content mode this is the camera-only stream; otherwise it falls back to
     * the legacy combined stream (same as `remoteStreams.get(cid)`).
     */
    getRemoteCameraStream(cid: string): MediaStream | undefined;
    /**
     * Remote content (screen share) stream for a participant when the
     * independent content video stream is negotiated, else `undefined`.
     */
    getRemoteContentStream(cid: string): MediaStream | undefined;
    /** Legacy camera stream accessor — same as `remoteStreams.get(cid)`. */
    getRemoteStream(cid: string): MediaStream | undefined;
    /** Local content (screen share) stream for optional local preview. */
    getLocalContentStream(): MediaStream | null;
    /** Whether this session negotiated the independent content video path. */
    readonly independentContentVideoEnabled: boolean;
    /**
     * Whether a remote participant advertised independent content video support.
     * Missing capabilities default to `false`.
     */
    getRemoteIndependentContentVideo(cid: string): boolean;
    readonly callStats: CallStats | null;
    /**
     * Aggregate call-quality summary. Updated live during the
     * call and finalized at end; readable after the session stops. `null`
     * before sampling begins (first `inCall`).
     */
    readonly callQualitySummary: CallQualitySummary | null;
    readonly hasMultipleCameras: boolean;
    readonly canScreenShare: boolean;
    readonly isSignalingConnected: boolean;
    readonly iceConnectionState: RTCIceConnectionState;
    readonly peerConnectionState: RTCPeerConnectionState;
    readonly rtcSignalingState: RTCSignalingState;
    onPermissionsRequired: ((permissions: MediaCapability[]) => void) | null;
}

/** Aggregated WebRTC call statistics (bitrate, packet loss, jitter, codec, resolution). */
export interface CallStats {
    transportPath: string | null;
    rttMs: number | null;
    availableOutgoingKbps: number | null;
    audioRxPacketLossPct: number | null;
    audioTxPacketLossPct: number | null;
    audioJitterMs: number | null;
    audioPlayoutDelayMs: number | null;
    audioConcealedPct: number | null;
    audioRxKbps: number | null;
    audioTxKbps: number | null;
    videoRxPacketLossPct: number | null;
    videoTxPacketLossPct: number | null;
    videoRxKbps: number | null;
    videoTxKbps: number | null;
    videoFps: number | null;
    videoResolution: string | null;
    videoFreezeCount60s: number | null;
    videoFreezeDuration60s: number | null;
    videoRetransmitPct: number | null;
    /**
     * Cumulative inbound-video `framesDecoded`, summed across peer slots.
     * Surfaced so hosts can diff per video segment to feed their
     * per-video-segment frame-drop analytics.
     */
    videoFramesDecoded: number | null;
    /** Cumulative inbound-video `framesDropped`, summed across peer slots. */
    videoFramesDropped: number | null;
    /**
     * Cumulative inbound-audio `packetsLost`, summed across peer slots.
     * Feeds the call-level delta-based packet-loss computation — do not
     * median the cumulative loss percentage.
     */
    audioPacketsLost: number | null;
    /** Cumulative inbound-audio `packetsReceived`, summed across peer slots. */
    audioPacketsReceived: number | null;
    updatedAtMs: number;
}

/**
 * Immutable snapshot of aggregate call quality, computed by the SDK and
 * consumed by hosts to populate their call-ended analytics.
 * Updated live during the call and finalized at call end; remains readable
 * after the session stops.
 */
export interface CallQualitySummary {
    /**
     * MOS estimate (heuristic). `null` unless all of
     * `medianLatencyMs`, `medianJitterMs`, and `packetLossPct` are defined.
     */
    mosScore: number | null;
    /**
     * Call-level audio rx packet loss percentage, computed from counter
     * deltas over the in-call window (NOT a median of cumulative loss %).
     * `null` until at least one usable audio-loss sample is seen.
     */
    packetLossPct: number | null;
    /** Median of sampled `rttMs`, or `null`. */
    medianLatencyMs: number | null;
    /** Median of sampled `audioJitterMs`, or `null`. */
    medianJitterMs: number | null;
    /** Number of dropout starts while in-call. */
    countDisconnects: number;
    /** Number of dropouts that recovered. */
    countReconnects: number;
    /** Σ of dropout interval durations in ms. */
    totalDropoutDurationMs: number;
    /**
     * Count of stats samples that contributed ≥1 usable quality field (a
     * latency/jitter gauge, or a loss interval that was actually accumulated).
     * Diagnostic only — the MOS null policy gates on the three medians being
     * non-null, not on this count.
     */
    qualitySampleCount: number;
}

/** Reason a dropout began, carried so hosts can distinguish recovery causes. */
export type DropoutTrigger = 'networkLost' | 'unknown';

/**
 * Connection-quality event emitted by the SDK through {@link
 * SerenadaSessionHandle.onConnectionEvent}. Hosts map these to their
 * reconnect/disconnect analytics events.
 */
export type ConnectionEvent =
    | {
          kind: 'reconnected';
          /** Downtime of the recovered dropout, in ms. */
          downtimeMs: number;
          /** `networkLost` if the dropout began with signaling/network loss, else `unknown`. */
          reason: DropoutTrigger;
      }
    | {
          kind: 'reconnectFailed';
          /** `timeout` (recovery window elapsed) or `networkConnectivity` (no network/transport). */
          reason: 'timeout' | 'networkConnectivity';
      };

export type RoomOccupancy = RoomStatus;

/** Current state of room occupancy watching. */
export interface RoomWatcherState {
    isConnected: boolean;
    activeTransport: TransportKind | null;
    roomStatuses: RoomStatuses;
}

/** Result of a single diagnostic check (available, unavailable, not authorized, or skipped). */
export type DiagnosticCheckResult =
    | { status: 'available' }
    | { status: 'unavailable'; reason: string }
    | { status: 'notAuthorized' }
    | { status: 'skipped'; reason: string };

/** Outcome of a timed connectivity check with latency on success or error on failure. */
export type CheckOutcome =
    | { status: 'notRun' }
    | { status: 'passed'; latencyMs: number }
    | { status: 'failed'; error: string };

/** Full diagnostics report covering device capabilities and server connectivity. */
export interface DiagnosticsReport {
    camera: DiagnosticCheckResult;
    microphone: DiagnosticCheckResult;
    speaker: DiagnosticCheckResult;
    network: DiagnosticCheckResult;
    signaling: DiagnosticCheckResult & { transport?: string };
    turn: DiagnosticCheckResult & { latencyMs?: number };
    devices: MediaDeviceInfo[];
}

/** Server connectivity check results (room API, WebSocket, SSE, TURN). */
export interface ConnectivityReport {
    roomApi: CheckOutcome;
    webSocket: CheckOutcome;
    sse: CheckOutcome;
    diagnosticToken: CheckOutcome;
    turnCredentials: CheckOutcome;
}

/** ICE connectivity probe results indicating STUN/TURN reachability. */
export interface IceProbeReport {
    stunPassed: boolean;
    turnPassed: boolean;
    logs: string[];
    iceServersSummary?: string;
}

/** Log level for SDK diagnostic output. */
export type SerenadaLogLevel = 'debug' | 'info' | 'warning' | 'error';

/** Logger interface for custom log handling. Implement this to capture SDK logs. */
export interface SerenadaLogger {
    log(level: SerenadaLogLevel, tag: string, message: string): void;
}
