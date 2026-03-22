import type { TransportKind } from './signaling/transports/types.js';
import type { SignalingMessage } from './signaling/types.js';
import type { RoomStatus, RoomStatuses } from './signaling/roomStatuses.js';

export type CallPhase = 'idle' | 'awaitingPermissions' | 'joining' | 'waiting' | 'inCall' | 'ending' | 'error';

export type ConnectionStatus = 'connected' | 'recovering' | 'retrying';

export type CameraMode = 'selfie' | 'world' | 'composite' | 'screenShare';

export type MediaCapability = 'camera' | 'microphone';

export type PeerConnectionState = 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed';

export interface Participant {
    cid: string;
    audioEnabled: boolean;
    videoEnabled: boolean;
    connectionState: PeerConnectionState;
}

export interface LocalParticipant {
    cid: string;
    audioEnabled: boolean;
    videoEnabled: boolean;
    cameraMode: CameraMode;
    isHost: boolean;
}

export type CallErrorCode =
    | 'signalingTimeout'
    | 'connectionFailed'
    | 'roomFull'
    | 'roomEnded'
    | 'permissionDenied'
    | 'serverError'
    | 'webrtcUnavailable'
    | 'unknown';

export interface CallError {
    code: CallErrorCode;
    message: string;
}

export interface CallState {
    phase: CallPhase;
    roomId: string | null;
    roomUrl: string | null;
    localParticipant: LocalParticipant | null;
    remoteParticipants: Participant[];
    connectionStatus: ConnectionStatus;
    activeTransport: TransportKind | null;
    requiredPermissions: MediaCapability[] | null;
    error: CallError | null;
}

export interface SerenadaConfig {
    /** Bare host or full origin, e.g. `serenada.app` or `http://qa-box:8080`. */
    serverHost: string;
    defaultAudioEnabled?: boolean;
    defaultVideoEnabled?: boolean;
    transports?: TransportKind[];
    turnsOnly?: boolean;
    logger?: SerenadaLogger;
}

export interface CreateRoomResult {
    url: string;
    roomId: string;
    /** Public app-facing session contract. Prefer this over the concrete class in host-app code. */
    session: SerenadaSessionHandle;
}

export interface SerenadaSessionHandle {
    subscribe(callback: (state: CallState) => void): () => void;
    subscribeToMessages(callback: (message: SignalingMessage) => void): () => void;
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

export interface RoomWatcherState {
    isConnected: boolean;
    activeTransport: TransportKind | null;
    roomStatuses: RoomStatuses;
}

export type DiagnosticCheckResult =
    | { status: 'available' }
    | { status: 'unavailable'; reason: string }
    | { status: 'notAuthorized' }
    | { status: 'skipped'; reason: string };

export type CheckOutcome =
    | { status: 'notRun' }
    | { status: 'passed'; latencyMs: number }
    | { status: 'failed'; error: string };

export interface DiagnosticsReport {
    camera: DiagnosticCheckResult;
    microphone: DiagnosticCheckResult;
    speaker: DiagnosticCheckResult;
    network: DiagnosticCheckResult;
    signaling: DiagnosticCheckResult & { transport?: string };
    turn: DiagnosticCheckResult & { latencyMs?: number };
    devices: MediaDeviceInfo[];
}

export interface ConnectivityReport {
    roomApi: CheckOutcome;
    webSocket: CheckOutcome;
    sse: CheckOutcome;
    diagnosticToken: CheckOutcome;
    turnCredentials: CheckOutcome;
}

export interface IceProbeReport {
    stunPassed: boolean;
    turnPassed: boolean;
    logs: string[];
    iceServersSummary?: string;
}

export type SerenadaLogLevel = 'debug' | 'info' | 'warning' | 'error';

export interface SerenadaLogger {
    log(level: SerenadaLogLevel, tag: string, message: string): void;
}
