import type { RoomState, SignalingMessage } from '../../src/signaling/types.js';
import type { ConnectionStatus } from '../../src/types.js';

/**
 * Fake MediaEngine for testing SerenadaSession.
 *
 * Mirrors the public property surface that SerenadaSession reads during
 * `rebuildState()` and exposes call-tracking arrays for assertions.
 */
export class FakeMediaEngine {
    // --- Public state (read by SerenadaSession.rebuildState) ---
    localStream: MediaStream | null = null;
    remoteStreams = new Map<string, MediaStream>();
    isScreenSharing = false;
    canScreenShare = false;
    facingMode: 'user' | 'environment' = 'user';
    hasMultipleCameras = false;
    iceConnectionState: RTCIceConnectionState = 'new';
    connectionState: RTCPeerConnectionState = 'new';
    signalingState: RTCSignalingState = 'stable';
    connectionStatus: ConnectionStatus = 'connected';

    // --- Call tracking ---
    startLocalMediaCalls = 0;
    cleanupAllPeersCalls = 0;
    destroyCalls = 0;
    processSignalingMessageCalls: SignalingMessage[] = [];
    updateRoomStateCalls: { state: RoomState | null; clientId: string | null }[] = [];
    updateSignalingConnectedCalls: boolean[] = [];
    updateTurnTokenCalls: string[] = [];

    // --- Callbacks ---
    private onChange: (() => void) | null = null;

    /**
     * Resolve value for startLocalMedia(). Defaults to a stub object that
     * satisfies the surface SerenadaSession reads (getAudioTracks, getVideoTracks).
     * Set to null to simulate media access failure.
     */
    startLocalMediaResult: MediaStream | null = {
        getAudioTracks: () => [],
        getVideoTracks: () => [],
    } as unknown as MediaStream;

    setOnChange(cb: () => void): void {
        this.onChange = cb;
    }

    async startLocalMedia(): Promise<MediaStream | null> {
        this.startLocalMediaCalls++;
        this.localStream = this.startLocalMediaResult;
        return this.startLocalMediaResult;
    }

    stopLocalMedia(): void {
        this.localStream = null;
    }

    async startScreenShare(): Promise<void> { /* no-op */ }
    async stopScreenShare(): Promise<void> { /* no-op */ }
    async flipCamera(): Promise<void> { /* no-op */ }

    processSignalingMessage(msg: SignalingMessage): void {
        this.processSignalingMessageCalls.push(msg);
    }

    updateRoomState(state: RoomState | null, clientId: string | null): void {
        this.updateRoomStateCalls.push({ state, clientId });
    }

    updateSignalingConnected(connected: boolean): void {
        this.updateSignalingConnectedCalls.push(connected);
    }

    updateTurnToken(token: string): void {
        this.updateTurnTokenCalls.push(token);
    }

    cleanupAllPeers(): void {
        this.cleanupAllPeersCalls++;
    }

    getPeerConnections(): RTCPeerConnection[] {
        return [];
    }

    getPeerConnectionsMap(): Map<string, RTCPeerConnection> {
        return new Map();
    }

    destroy(): void {
        this.destroyCalls++;
    }

    // --- Test helpers ---

    /** Apply a partial state update and trigger onChange (which triggers rebuildState). */
    emit(partial: Partial<Pick<FakeMediaEngine, 'localStream' | 'remoteStreams' | 'isScreenSharing' | 'canScreenShare' | 'facingMode' | 'hasMultipleCameras' | 'iceConnectionState' | 'connectionState' | 'signalingState' | 'connectionStatus'>>): void {
        Object.assign(this, partial);
        this.onChange?.();
    }
}
