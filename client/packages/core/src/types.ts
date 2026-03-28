import type { SignalingProvider, PeerMessage } from './SignalingProvider.js';
import type { TransportKind } from './signaling/transports/types.js';
import type { RoomStatus, RoomStatuses } from './signaling/roomStatuses.js';

/** Current phase of the call lifecycle. */
export type CallPhase = 'idle' | 'awaitingPermissions' | 'joining' | 'waiting' | 'inCall' | 'ending' | 'error';

/** Network connection status between the client and signaling server. */
export type ConnectionStatus = 'connected' | 'recovering' | 'retrying';

/** Active signaling transport, including custom provider-specific transport labels. */
export type ActiveTransport = TransportKind | (string & {});

/** Camera mode: selfie (front), world (rear), composite (picture-in-picture), or screen share. */
export type CameraMode = 'selfie' | 'world' | 'composite' | 'screenShare';

/** Device media capability that may require user permission. */
export type MediaCapability = 'camera' | 'microphone';

/** WebRTC peer connection state. */
export type PeerConnectionState = 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed';

/** Remote participant in a call. */
export interface Participant {
    cid: string;
    audioEnabled: boolean;
    videoEnabled: boolean;
    connectionState: PeerConnectionState;
}

/** Local participant info including camera mode and host status. */
export interface LocalParticipant {
    cid: string;
    audioEnabled: boolean;
    videoEnabled: boolean;
    cameraMode: CameraMode;
    isHost: boolean;
}

/** Error codes for call failures. */
export type CallErrorCode =
    | 'signalingTimeout'
    | 'connectionFailed'
    | 'roomFull'
    | 'roomEnded'
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
    /** Public app-facing session contract. Prefer this over the concrete class in host-app code. */
    session: SerenadaSessionHandle;
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
