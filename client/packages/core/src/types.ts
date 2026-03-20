import type { TransportKind } from './signaling/transports/types.js';
import type { SignalingMessage } from './signaling/types.js';

export type CallPhase = 'idle' | 'awaitingPermissions' | 'joining' | 'waiting' | 'inCall' | 'ending' | 'error';

export type ConnectionStatus = 'connected' | 'recovering' | 'retrying';

export type CameraMode = 'selfie' | 'world' | 'composite' | 'screenShare';

export type MediaCapability = 'camera' | 'microphone';

export interface Participant {
    cid: string;
    audioEnabled: boolean;
    videoEnabled: boolean;
    connectionState: string;
}

export interface LocalParticipant {
    cid: string;
    audioEnabled: boolean;
    videoEnabled: boolean;
    cameraMode: CameraMode;
    isHost: boolean;
}

export interface CallError {
    code: string;
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

export type DiagnosticCheckResult =
    | { status: 'available' }
    | { status: 'unavailable'; reason: string }
    | { status: 'notAuthorized' }
    | { status: 'skipped'; reason: string };

export interface DiagnosticsReport {
    camera: DiagnosticCheckResult;
    microphone: DiagnosticCheckResult;
    speaker: DiagnosticCheckResult;
    network: DiagnosticCheckResult;
    signaling: DiagnosticCheckResult & { transport?: string };
    turn: DiagnosticCheckResult & { latencyMs?: number };
    devices: MediaDeviceInfo[];
}
