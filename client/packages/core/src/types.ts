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
    videoEnabled: boolean;
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
    videoEnabled: boolean;
    cameraMode: CameraMode;
    /**
     * Camera modes the user can cycle through, in preference order.
     * Derived from {@link SerenadaConfig.cameraModes} minus modes unsupported
     * on this device/platform. An empty array means video is unavailable
     * — the call UI should hide the video toggle.
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
    | 'unknown';

/** Error with a machine-readable code and human-readable message. */
export interface CallError {
    code: CallErrorCode;
    message: string;
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
     * Camera modes available in the call UI, in preference order. The first
     * entry is the initial mode. When only one mode is listed the flip-camera
     * control is hidden; an empty array disables video entirely (the video
     * toggle is hidden and the camera is never requested). Modes unsupported
     * on the current platform or device are silently dropped (`'composite'`
     * is always dropped on web). Defaults to `['selfie', 'world', 'composite']`.
     */
    cameraModes?: ConfigurableCameraMode[];
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
    resumeJoin(): Promise<void>;
    cancelJoin(): void;
    destroy(): void;
    readonly state: CallState;
    readonly localStream: MediaStream | null;
    readonly remoteStreams: Map<string, MediaStream>;
    readonly callStats: CallStats | null;
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
    updatedAtMs: number;
}

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
