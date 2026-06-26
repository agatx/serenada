import type { RoomState, SignalingMessage } from '../../src/signaling/types.js';
import type { ConnectionStatus, VideoMode } from '../../src/types.js';

/**
 * Minimal fake MediaStreamTrack that records `stop()` so hold tests can assert
 * capture was actually released (vs only `enabled=false`). `enabled` is a plain
 * field so tests can also assert the mute distinction.
 */
export class FakeMediaStreamTrack {
    kind: 'audio' | 'video';
    enabled = true;
    readyState: 'live' | 'ended' = 'live';
    stopCalls = 0;
    constructor(kind: 'audio' | 'video') {
        this.kind = kind;
    }
    stop(): void {
        this.stopCalls += 1;
        this.readyState = 'ended';
    }
}

/**
 * Minimal fake MediaStream backed by {@link FakeMediaStreamTrack}s so the
 * session's `getAudioTracks()[0].enabled` toggles and the hold capture-release
 * path are both observable.
 */
export class FakeMediaStream {
    private tracks: FakeMediaStreamTrack[];
    constructor(tracks: FakeMediaStreamTrack[] = []) {
        this.tracks = tracks;
    }
    getTracks(): FakeMediaStreamTrack[] { return [...this.tracks]; }
    getAudioTracks(): FakeMediaStreamTrack[] { return this.tracks.filter(t => t.kind === 'audio'); }
    getVideoTracks(): FakeMediaStreamTrack[] { return this.tracks.filter(t => t.kind === 'video'); }
    addTrack(track: FakeMediaStreamTrack): void { this.tracks.push(track); }
    removeTrack(track: FakeMediaStreamTrack): void {
        this.tracks = this.tracks.filter(t => t !== track);
    }
}

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

    // Counts getDisplayMedia-equivalent screen-share starts so the held-guard
    // test can assert a held call never starts capture. Sets isScreenSharing so
    // the session's broadcast branch is exercised on the foreground path.
    startScreenShareCalls = 0;
    async startScreenShare(): Promise<void> {
        this.startScreenShareCalls += 1;
        this.isScreenSharing = true;
    }
    async stopScreenShare(): Promise<void> { this.isScreenSharing = false; }
    async flipCamera(): Promise<void> {
        this.facingMode = this.facingMode === 'user' ? 'environment' : 'user';
    }

    // Capture-touch counters so hold tests can assert the foreground toggle path
    // (camera reacquire/release) is NOT taken while held.
    releaseVideoTrackCalls = 0;
    reacquireVideoTrackCalls = 0;

    async releaseVideoTrack(): Promise<void> {
        this.releaseVideoTrackCalls += 1;
        const stream = this.localStream as unknown as FakeMediaStream | null;
        if (!stream) return;
        for (const track of stream.getVideoTracks()) {
            track.stop();
            stream.removeTrack(track);
        }
    }

    async reacquireVideoTrack(): Promise<void> {
        this.reacquireVideoTrackCalls += 1;
        const stream = this.localStream as unknown as FakeMediaStream | null;
        if (!stream || stream.getVideoTracks().length > 0) return;
        stream.addTrack(new FakeMediaStreamTrack('video'));
    }

    // --- Multi-call hold/resume primitives ---
    suspendLocalMediaForHoldCalls = 0;
    resumeLocalMediaFromHoldCalls: Array<{ desiredAudio: boolean; desiredVideoMode: VideoMode }> = [];
    setRemotePlaybackEnabledCalls: boolean[] = [];
    detachOrPauseRenderersForHoldCalls = 0;
    /** Tracks stopped during the most recent hold (capture-release assertions). */
    lastSuspendedAudioTracks: FakeMediaStreamTrack[] = [];
    lastSuspendedVideoTracks: FakeMediaStreamTrack[] = [];

    async suspendLocalMediaForHold(): Promise<void> {
        this.suspendLocalMediaForHoldCalls += 1;
        if (this.isScreenSharing) {
            await this.stopScreenShare();
        }
        const stream = this.localStream as unknown as FakeMediaStream | null;
        this.lastSuspendedAudioTracks = [];
        this.lastSuspendedVideoTracks = [];
        if (stream) {
            for (const track of stream.getAudioTracks()) {
                track.stop();
                this.lastSuspendedAudioTracks.push(track);
                stream.removeTrack(track);
            }
            for (const track of stream.getVideoTracks()) {
                track.stop();
                this.lastSuspendedVideoTracks.push(track);
                stream.removeTrack(track);
            }
        }
        this.setRemotePlaybackEnabled(false);
        this.detachOrPauseRenderersForHold();
    }

    async resumeLocalMediaFromHold(desiredAudio: boolean, desiredVideoMode: VideoMode): Promise<void> {
        this.resumeLocalMediaFromHoldCalls.push({ desiredAudio, desiredVideoMode });
        const stream = this.localStream as unknown as FakeMediaStream | null;
        if (stream) {
            if (desiredAudio && stream.getAudioTracks().length === 0) {
                stream.addTrack(new FakeMediaStreamTrack('audio'));
            }
            if (desiredVideoMode !== 'off' && stream.getVideoTracks().length === 0) {
                stream.addTrack(new FakeMediaStreamTrack('video'));
            }
        }
        this.setRemotePlaybackEnabled(true);
    }

    setRemotePlaybackEnabled(enabled: boolean): void {
        this.setRemotePlaybackEnabledCalls.push(enabled);
    }

    detachOrPauseRenderersForHold(): void {
        this.detachOrPauseRenderersForHoldCalls += 1;
    }

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

    /**
     * Install a live local stream with audio and (optionally) camera tracks so
     * hold/resume tests can observe capture release. Returns the stream so a
     * test can hold references to the underlying tracks.
     */
    installLocalStream(opts: { audio?: boolean; video?: boolean } = {}): FakeMediaStream {
        const tracks: FakeMediaStreamTrack[] = [];
        if (opts.audio !== false) tracks.push(new FakeMediaStreamTrack('audio'));
        if (opts.video) tracks.push(new FakeMediaStreamTrack('video'));
        const stream = new FakeMediaStream(tracks);
        this.localStream = stream as unknown as MediaStream;
        return stream;
    }

    /** Apply a partial state update and trigger onChange (which triggers rebuildState). */
    emit(partial: Partial<Pick<FakeMediaEngine, 'localStream' | 'remoteStreams' | 'isScreenSharing' | 'lastContentRevision' | 'canScreenShare' | 'facingMode' | 'hasMultipleCameras' | 'iceConnectionState' | 'connectionState' | 'signalingState' | 'connectionStatus'>>): void {
        Object.assign(this, partial);
        this.onChange?.();
    }
}
