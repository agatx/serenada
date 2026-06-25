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
    /** Mirrors MediaEngine.lastContentRevision (read for local content state). */
    lastContentRevision = 0;
    canScreenShare = false;
    facingMode: 'user' | 'environment' = 'user';
    hasMultipleCameras = false;
    iceConnectionState: RTCIceConnectionState = 'new';
    connectionState: RTCPeerConnectionState = 'new';
    signalingState: RTCSignalingState = 'stable';
    connectionStatus: ConnectionStatus = 'connected';

    // --- Call tracking ---
    startLocalMediaCalls = 0;
    stopLocalMediaCalls = 0;
    cleanupAllPeersCalls = 0;
    destroyCalls = 0;
    handleSignalingReconnectCalls = 0;
    scheduleDirtyPairRestartCalls: string[] = [];
    processSignalingMessageCalls: SignalingMessage[] = [];
    setIceServersCalls: RTCIceServer[][] = [];
    updateRoomStateCalls: { state: RoomState | null; clientId: string | null }[] = [];
    updateSignalingConnectedCalls: boolean[] = [];

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

    /**
     * Mirrors MediaEngine.lastLocalMediaError. Set alongside
     * startLocalMediaResult = null to simulate a failed (vs superseded)
     * acquisition.
     */
    lastLocalMediaError: { name: string; message: string } | null = null;

    setOnChange(cb: () => void): void {
        this.onChange = cb;
    }

    seedContentRevision(revision: number | undefined): void {
        if (revision === undefined || !Number.isSafeInteger(revision) || revision < 0) {
            return;
        }
        this.lastContentRevision = Math.max(this.lastContentRevision, revision);
    }

    async startLocalMedia(): Promise<MediaStream | null> {
        this.startLocalMediaCalls++;
        this.localStream = this.startLocalMediaResult;
        return this.startLocalMediaResult;
    }

    stopLocalMedia(): void {
        this.stopLocalMediaCalls++;
        this.localStream = null;
    }

    async startScreenShare(): Promise<void> { /* no-op */ }
    async stopScreenShare(): Promise<void> { /* no-op */ }
    async flipCamera(): Promise<void> { /* no-op */ }

    // Independent-content stream accessors (Phase 2). Tests can override the maps.
    remoteCameraStreams = new Map<string, MediaStream>();
    remoteContentStreams = new Map<string, MediaStream>();
    localContentStream: MediaStream | null = null;
    getRemoteCameraStream(cid: string): MediaStream | undefined {
        return this.remoteCameraStreams.get(cid) ?? this.remoteStreams.get(cid);
    }
    getRemoteContentStream(cid: string): MediaStream | undefined {
        return this.remoteContentStreams.get(cid);
    }
    getRemoteStream(cid: string): MediaStream | undefined {
        return this.remoteStreams.get(cid);
    }
    getLocalContentStream(): MediaStream | null {
        return this.localContentStream;
    }

    processSignalingMessage(msg: SignalingMessage): void {
        this.processSignalingMessageCalls.push(msg);
    }

    updateRoomState(state: RoomState | null, clientId: string | null): void {
        this.updateRoomStateCalls.push({ state, clientId });
    }

    updateSignalingConnected(connected: boolean): void {
        this.updateSignalingConnectedCalls.push(connected);
    }

    setIceServers(iceServers: RTCIceServer[]): void {
        this.setIceServersCalls.push(iceServers);
    }

    handleSignalingReconnect(): void {
        this.handleSignalingReconnectCalls++;
    }

    scheduleDirtyPairRestart(remoteCid: string): void {
        this.scheduleDirtyPairRestartCalls.push(remoteCid);
    }

    allPathsDirect = false;
    async arePeerPathsAllDirect(): Promise<boolean> {
        return this.allPathsDirect;
    }

    /**
     * CIDs returned from the next `getInboundFlowingCids()` call. Tests can
     * override to assert the periodic `media_liveness` emit picks up the
     * right list.
     */
    inboundFlowingCids: string[] = [];
    sampleInboundLivenessCalls = 0;
    async sampleInboundLiveness(): Promise<{
        flowingCids: string[];
        roleLiveness: Map<string, { camera: boolean; content: boolean }>;
    }> {
        this.sampleInboundLivenessCalls += 1;
        return {
            flowingCids: [...this.inboundFlowingCids],
            roleLiveness: new Map(this.roleLiveness),
        };
    }

    getInboundFlowingCidsCalls = 0;
    async getInboundFlowingCids(): Promise<string[]> {
        this.getInboundFlowingCidsCalls += 1;
        return [...this.inboundFlowingCids];
    }

    /**
     * Per-role inbound liveness snapshot, read synchronously by
     * SerenadaSession.rebuildState to populate `cameraReceiving` /
     * `contentReceiving`. Tests can seed this map to drive the participant
     * state. `sampleInboundRoleLiveness()` is a no-op refresh here (the real
     * engine recomputes from RTP stats); tests set the snapshot directly.
     */
    roleLiveness = new Map<string, { camera: boolean; content: boolean }>();
    sampleInboundRoleLivenessCalls = 0;
    async sampleInboundRoleLiveness(): Promise<Map<string, { camera: boolean; content: boolean }>> {
        this.sampleInboundRoleLivenessCalls += 1;
        return new Map(this.roleLiveness);
    }
    getRoleLiveness(cid: string): { camera: boolean; content: boolean } {
        return this.roleLiveness.get(cid) ?? { camera: false, content: false };
    }

    cleanupAllPeers(): void {
        this.cleanupAllPeersCalls++;
        this.remoteStreams = new Map();
    }

    /**
     * Peer connections returned from `getPeerConnections()`. Tests can push
     * fakes here (each with a `getStats()` returning an `RTCStatsReport`) so
     * the real `CallStatsCollector` produces snapshots.
     */
    peerConnections: RTCPeerConnection[] = [];

    getPeerConnections(): RTCPeerConnection[] {
        return this.peerConnections;
    }

    getPeerConnectionsMap(): Map<string, RTCPeerConnection> {
        return new Map();
    }

    destroy(): void {
        this.destroyCalls++;
    }

    // --- Test helpers ---

    /** Apply a partial state update and trigger onChange (which triggers rebuildState). */
    emit(partial: Partial<Pick<FakeMediaEngine, 'localStream' | 'remoteStreams' | 'isScreenSharing' | 'lastContentRevision' | 'canScreenShare' | 'facingMode' | 'hasMultipleCameras' | 'iceConnectionState' | 'connectionState' | 'signalingState' | 'connectionStatus'>>): void {
        Object.assign(this, partial);
        this.onChange?.();
    }
}
