import type { RoomState, SignalingMessage } from '../signaling/types.js';
import type { ConnectionStatus, SerenadaLogger } from '../types.js';
import { parseOfferPayload, parseAnswerPayload, parseIceCandidatePayload } from '../signaling/payloads.js';
import { MEDIA_RESTART_REASON_LOCAL_TRACK_NEGOTIATION } from '../signaling/protocolConstants.js';
import { formatError } from '../formatError.js';
import { normalizeIceServers } from '../iceServers.js';
import {
    OFFER_TIMEOUT_MS,
    ICE_RESTART_COOLDOWN_MS,
    ICE_CANDIDATE_BUFFER_MAX,
    CONNECTION_RETRYING_DELAY_MS,
    LOCAL_VIDEO_HEARTBEAT_INTERVAL_MS,
    OUTBOUND_MEDIA_WATCHDOG_INTERVAL_MS,
    OUTBOUND_MEDIA_STALL_SAMPLES,
    OUTBOUND_MEDIA_RECOVERY_COOLDOWN_MS,
} from '../constants.js';
import { shouldForceLocalVideoRefresh, shouldRecoverLocalVideo } from './localVideoRecovery.js';

const DEFAULT_RTC_CONFIG: RTCConfiguration = {
    iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
};

const ICE_STATE_PRIORITY: RTCIceConnectionState[] = ['failed', 'disconnected', 'checking', 'new', 'connected', 'completed', 'closed'];
const CONN_STATE_PRIORITY: RTCPeerConnectionState[] = ['failed', 'disconnected', 'connecting', 'new', 'connected', 'closed'];
const SIG_STATE_PRIORITY: RTCSignalingState[] = ['closed', 'have-local-offer', 'have-remote-offer', 'have-local-pranswer', 'have-remote-pranswer', 'stable'];
const LEGACY_OFFER_ID = '__legacy__';

function getSignalingState(pc: RTCPeerConnection): RTCSignalingState {
    return pc.signalingState;
}

function getStatNumber(stat: RTCStats, key: string): number {
    const value = (stat as unknown as Record<string, unknown>)[key];
    return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function getStatString(stat: RTCStats, key: string): string | null {
    const value = (stat as unknown as Record<string, unknown>)[key];
    return typeof value === 'string' ? value : null;
}

interface OutboundMediaSample {
    audioBytesSent: number;
    videoBytesSent: number;
    videoFramesSent: number;
}

interface PeerState {
    pc: RTCPeerConnection;
    remoteStream: MediaStream | null;
    iceBuffer: RTCIceCandidateInit[];
    isMakingOffer: boolean;
    offerTimeout: number | null;
    iceRestartTimer: number | null;
    lastIceRestartAt: number;
    pendingIceRestart: boolean;
    pendingLocalTrackNegotiation: boolean;
    isSettingRemoteAnswerPending: boolean;
    pendingLocalOfferId: string | null;
    acceptedRemoteOfferId: string | null;
    currentNegotiationId: string | null;
    ignoredOfferId: string | null;
    pendingRemoteIceByOfferId: Map<string, RTCIceCandidateInit[]>;
    lastOutboundMediaSample: OutboundMediaSample | null;
    outboundMediaStallSamples: number;
    outboundMediaWatchInFlight: boolean;
    lastOutboundMediaRecoveryAt: number;
}

export interface MediaEngineConfig {
    turnsOnly?: boolean;
    logger?: SerenadaLogger;
    /** Initial camera facing mode. Defaults to `'user'` (selfie). */
    initialFacingMode?: 'user' | 'environment';
    /** When `false`, media starts audio-only and camera is requested on first video enable. */
    initialVideoEnabled?: boolean;
    /** When `false`, the camera is never requested and video is always off. */
    videoCaptureSupported?: boolean;
}

export class MediaEngine {
    localStream: MediaStream | null = null;
    remoteStreams = new Map<string, MediaStream>();
    isScreenSharing = false;
    canScreenShare = !!navigator.mediaDevices?.getDisplayMedia;
    facingMode: 'user' | 'environment' = 'user';
    hasMultipleCameras = false;
    readonly videoCaptureSupported: boolean;
    iceConnectionState: RTCIceConnectionState = 'new';
    connectionState: RTCPeerConnectionState = 'new';
    signalingState: RTCSignalingState = 'stable';
    connectionStatus: ConnectionStatus = 'connected';

    private peers = new Map<string, PeerState>();
    private readonly initialVideoEnabled: boolean;
    // Per-remote-CID tally of cumulative `inbound-rtp.bytesReceived`. Sampled
    // on every `getInboundFlowingCids()` call; a CID is "flowing" when its
    // current sample exceeds the previous one. Drives #3's `media_liveness`
    // emission (see SerenadaSession.startMediaLivenessTimer).
    private lastInboundBytesByCid = new Map<string, number>();
    private rtcConfig: RTCConfiguration = DEFAULT_RTC_CONFIG;
    private screenShareTrack: MediaStreamTrack | null = null;
    private screenShareRestoreVideoEnabled: boolean | null = null;
    private requestingMedia = false;
    private destroyed = false;
    private cameraRecoveryInFlight = false;
    private mediaRequestId = 0;
    private retryingTimer: number | null = null;
    private localVideoHeartbeatAt = Date.now();
    private localVideoHiddenAt: number | null = typeof document !== 'undefined' && document.hidden ? Date.now() : null;
    private participantConnectionStatus = new Map<string, 'active' | 'suspended'>();
    private visibilityHandler: (() => void) | null = null;
    private pageShowHandler: ((e: PageTransitionEvent) => void) | null = null;
    private heartbeatInterval: number | null = null;
    private outboundMediaWatchdogInterval: number | null = null;
    private mediaRestartHandledAtByCid = new Map<string, number>();
    private onlineHandler: (() => void) | null = null;
    private networkChangeHandler: (() => void) | null = null;
    private deviceChangeHandler: (() => void) | null = null;
    private turnsOnly: boolean;
    private logger?: SerenadaLogger;
    private offerSequence = 0;

    // Injected dependencies
    private sendSignalingMessage: (type: string, payload?: Record<string, unknown>, to?: string) => void;
    private roomState: RoomState | null = null;
    private clientId: string | null = null;
    private isSignalingConnected = false;
    private onChange: (() => void) | null = null;

    constructor(
        config: MediaEngineConfig,
        sendMessage: (type: string, payload?: Record<string, unknown>, to?: string) => void,
    ) {
        this.turnsOnly = config.turnsOnly ?? false;
        this.logger = config.logger;
        this.facingMode = config.initialFacingMode ?? 'user';
        this.initialVideoEnabled = config.initialVideoEnabled !== false;
        this.videoCaptureSupported = config.videoCaptureSupported !== false;
        this.sendSignalingMessage = sendMessage;
        this.setupEventListeners();
    }

    setOnChange(cb: () => void): void { this.onChange = cb; }

    updateRoomState(state: RoomState | null, clientId: string | null): void {
        this.roomState = state;
        this.clientId = clientId;
        this.syncPeers();
    }

    updateSignalingConnected(connected: boolean): void {
        this.isSignalingConnected = connected;
        this.updateConnectionStatusValue();
        if (connected) {
            for (const [cid, peer] of this.peers) {
                if (peer.pendingIceRestart && this.shouldIOffer(cid) && peer.pc.signalingState === 'stable') {
                    peer.pendingIceRestart = false;
                    peer.lastIceRestartAt = Date.now();
                    void this.createOfferTo(cid, { iceRestart: true });
                }
                if (peer.pendingLocalTrackNegotiation) {
                    this.scheduleLocalTrackNegotiation(cid, peer);
                }
            }
        }
    }

    setIceServers(iceServers: RTCIceServer[]): void {
        const nextServers = this.normalizeIceServers(iceServers);
        const nextConfig: RTCConfiguration = {
            iceServers: nextServers.length > 0 ? nextServers : DEFAULT_RTC_CONFIG.iceServers,
        };
        if (this.turnsOnly) {
            nextConfig.iceTransportPolicy = 'relay';
        }

        this.rtcConfig = nextConfig;
        for (const [, peer] of this.peers) {
            try {
                peer.pc.setConfiguration(nextConfig);
            } catch (error) {
                this.logger?.log('warning', 'WebRTC', `Failed to update ICE config: ${formatError(error)}`);
            }
        }
    }

    handleSignalingReconnect(): void {
        if (!this.isSignalingConnected) {
            return;
        }
        for (const [remoteCid] of this.peers) {
            if (this.shouldIOffer(remoteCid)) {
                this.scheduleIceRestart(remoteCid, 'signaling-reconnect', 0);
            }
        }
    }

    /**
     * Schedule glare-safe ICE restart for a specific peer because the server
     * told us the pair is dirty after the peer reattached (#1).
     */
    scheduleDirtyPairRestart(remoteCid: string): void {
        if (!this.peers.has(remoteCid)) {
            return;
        }
        if (this.shouldIOffer(remoteCid)) {
            this.scheduleIceRestart(remoteCid, 'negotiation-dirty', 0);
        }
    }

    processSignalingMessage(msg: SignalingMessage): void {
        const { type, payload } = msg;
        if (!payload) return;
        try {
            switch (type) {
                case 'offer': {
                    const offer = parseOfferPayload(payload);
                    if (offer && this.isCurrentParticipant(offer.from)) {
                        void this.handleOfferFrom(offer.from, offer.sdp, offer.offerId ?? LEGACY_OFFER_ID);
                    }
                    break;
                }
                case 'answer': {
                    const answer = parseAnswerPayload(payload);
                    if (answer && this.isCurrentParticipant(answer.from)) {
                        void this.handleAnswerFrom(answer.from, answer.sdp, answer.offerId ?? LEGACY_OFFER_ID);
                    }
                    break;
                }
                case 'ice': {
                    const ice = parseIceCandidatePayload(payload);
                    if (ice && this.isCurrentParticipant(ice.from)) {
                        void this.handleIceFrom(ice.from, ice.candidate, ice.offerId ?? LEGACY_OFFER_ID);
                    }
                    break;
                }
                case 'media_restart_request': {
                    const fromCid = typeof payload.from === 'string' ? payload.from.trim() : '';
                    const reason = typeof payload.reason === 'string' ? payload.reason.trim() : '';
                    if (fromCid && this.isCurrentParticipant(fromCid)) {
                        void this.handleMediaRestartRequest(fromCid, reason);
                    }
                    break;
                }
            }
        } catch (err) {
            this.logger?.log('error', 'WebRTC', `Error processing message ${type}: ${formatError(err)}`);
        }
    }

    async startLocalMedia(): Promise<MediaStream | null> {
        const requestId = this.mediaRequestId + 1;
        this.mediaRequestId = requestId;

        if (this.localStream) return this.localStream;
        this.requestingMedia = true;
        try {
            if (!navigator.mediaDevices?.getUserMedia) {
                this.requestingMedia = false;
                return null;
            }
            const audioConstraints: MediaTrackConstraints = {
                echoCancellation: { ideal: true },
                noiseSuppression: { ideal: true },
                autoGainControl: { ideal: true },
                channelCount: { ideal: 1 },
                sampleRate: { ideal: 48000 }
            };
            let stream: MediaStream;
            if (!this.videoCaptureSupported || !this.initialVideoEnabled) {
                stream = await navigator.mediaDevices.getUserMedia({ video: false, audio: audioConstraints });
            } else {
                try {
                    stream = await navigator.mediaDevices.getUserMedia({
                        video: { facingMode: this.facingMode },
                        audio: audioConstraints
                    });
                } catch {
                    stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
                }
            }

            if (this.destroyed || this.mediaRequestId !== requestId) {
                stream.getTracks().forEach(t => t.stop());
                return null;
            }

            this.applySpeechTrackHints(stream);
            this.localStream = stream;
            await this.detectCameras();
            this.requestingMedia = false;

            for (const [remoteCid, peer] of this.peers) {
                await this.attachLocalTracksToPeer(remoteCid, peer, stream);
                void this.applyAudioSenderParameters(peer.pc);
                if (this.shouldIOffer(remoteCid) && !peer.pc.remoteDescription) {
                    if (peer.pc.signalingState === 'stable') {
                        void this.createOfferTo(remoteCid);
                    } else {
                        peer.pendingLocalTrackNegotiation = true;
                    }
                } else if (!this.shouldIOffer(remoteCid) && peer.pc.remoteDescription) {
                    this.scheduleLocalTrackNegotiation(remoteCid, peer);
                }
            }
            this.notifyChange();
            return stream;
        } catch (err) {
            this.logger?.log('error', 'WebRTC', `Error accessing media: ${formatError(err)}`);
            this.requestingMedia = false;
            return null;
        }
    }

    stopLocalMedia(): void {
        this.mediaRequestId += 1;
        if (this.screenShareTrack) {
            this.screenShareTrack.onended = null;
            this.screenShareTrack = null;
        }
        this.screenShareRestoreVideoEnabled = null;
        if (this.localStream) {
            this.localStream.getTracks().forEach(t => t.stop());
            this.localStream = null;
        }
        this.isScreenSharing = false;
        this.requestingMedia = false;
        this.notifyChange();
    }

    async releaseVideoTrack(): Promise<void> {
        if (this.isScreenSharing) return;
        const currentTrack = this.localStream?.getVideoTracks()[0] ?? null;
        if (!currentTrack) return;
        await this.swapLocalVideoTrack(null, currentTrack);
    }

    async reacquireVideoTrack(): Promise<void> {
        if (!this.videoCaptureSupported) return;
        if (this.isScreenSharing) return;
        if (this.localStream?.getVideoTracks()[0]) return;
        if (this.cameraRecoveryInFlight || this.requestingMedia) return;
        this.cameraRecoveryInFlight = true;
        try {
            const track = await this.acquireCameraTrack(this.facingMode, true);
            await this.swapLocalVideoTrack(track, null);
        } catch (err) {
            this.logger?.log('error', 'Camera', `Failed to reacquire camera: ${formatError(err)}`);
        } finally {
            this.cameraRecoveryInFlight = false;
        }
    }

    async startScreenShare(): Promise<void> {
        if (this.isScreenSharing || !this.canScreenShare) return;
        if (!this.localStream) return;

        try {
            const displayStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
            const displayTrack = displayStream.getVideoTracks()[0];
            if (!displayTrack) {
                displayStream.getTracks().forEach(track => track.stop());
                throw new Error('No display track returned');
            }

            const previousVideoTrack = this.localStream.getVideoTracks()[0];
            this.screenShareRestoreVideoEnabled = previousVideoTrack ? previousVideoTrack.enabled : null;
            displayTrack.enabled = true;
            if ('contentHint' in displayTrack) {
                // eslint-disable-next-line @typescript-eslint/no-explicit-any -- contentHint is a valid but untyped browser API
                try { (displayTrack as any).contentHint = 'detail'; } catch { /* ignore */ }
            }

            if (this.screenShareTrack) this.screenShareTrack.onended = null;
            this.screenShareTrack = displayTrack;
            displayTrack.onended = () => { void this.stopScreenShare(); };

            await this.swapLocalVideoTrack(displayTrack, previousVideoTrack);
            this.isScreenSharing = true;
            this.sendSignalingMessage('content_state', { active: true, contentType: 'screenShare' });
            this.notifyChange();
        } catch (err) {
            this.screenShareRestoreVideoEnabled = null;
            this.logger?.log('error', 'ScreenShare', `Failed to start screen share: ${formatError(err)}`);
        }
    }

    async stopScreenShare(): Promise<void> {
        if (!this.isScreenSharing) return;
        if (!this.localStream) {
            this.isScreenSharing = false;
            this.screenShareRestoreVideoEnabled = null;
            this.sendSignalingMessage('content_state', { active: false });
            this.notifyChange();
            return;
        }

        if (this.screenShareTrack) {
            this.screenShareTrack.onended = null;
            this.screenShareTrack = null;
        }

        const previousVideoTrack = this.localStream.getVideoTracks()[0];
        const restoreVideoEnabled = this.screenShareRestoreVideoEnabled;

        try {
            if (restoreVideoEnabled === null) {
                await this.swapLocalVideoTrack(null, previousVideoTrack);
            } else {
                const cameraTrack = await this.acquireCameraTrack(this.facingMode, restoreVideoEnabled);
                await this.swapLocalVideoTrack(cameraTrack, previousVideoTrack);
            }
        } catch (err) {
            this.logger?.log('error', 'ScreenShare', `Failed to stop screen share and restore camera: ${formatError(err)}`);
            await this.swapLocalVideoTrack(null, previousVideoTrack);
        } finally {
            this.isScreenSharing = false;
            this.screenShareRestoreVideoEnabled = null;
            this.sendSignalingMessage('content_state', { active: false });
            this.notifyChange();
        }
    }

    async flipCamera(): Promise<void> {
        if (this.isScreenSharing) return;
        if (!this.hasMultipleCameras) return;

        const newMode = this.facingMode === 'user' ? 'environment' : 'user';
        this.facingMode = newMode;

        if (!this.localStream) { this.notifyChange(); return; }

        try {
            const oldVideoTrack = this.localStream.getVideoTracks()[0];
            const newVideoTrack = await this.acquireCameraTrack(newMode, oldVideoTrack?.enabled ?? true);
            await this.swapLocalVideoTrack(newVideoTrack, oldVideoTrack);
            this.notifyChange();
        } catch (err) {
            this.logger?.log('error', 'Camera', `Failed to flip camera: ${formatError(err)}`);
        }
    }

    getPeerConnections(): RTCPeerConnection[] {
        return Array.from(this.peers.values()).map(ps => ps.pc);
    }

    getPeerConnectionsMap(): Map<string, RTCPeerConnection> {
        const map = new Map<string, RTCPeerConnection>();
        for (const [cid, ps] of this.peers) map.set(cid, ps.pc);
        return map;
    }

    cleanupAllPeers(): void {
        for (const [, peer] of this.peers) {
            this.clearPeerTimers(peer);
            this.clearPeerNegotiation(peer);
            peer.pc.close();
        }
        this.peers.clear();
        this.remoteStreams = new Map();
        this.lastInboundBytesByCid.clear();
        this.mediaRestartHandledAtByCid.clear();
        if (this.retryingTimer) { window.clearTimeout(this.retryingTimer); this.retryingTimer = null; }
        this.iceConnectionState = 'closed';
        this.connectionState = 'closed';
        this.signalingState = 'closed';
        this.connectionStatus = 'connected';
        this.notifyChange();
    }

    /**
     * Sample inbound RTP `bytesReceived` per remote peer and return the CIDs
     * whose totals advanced since the previous sample. Drives #3's
     * `media_liveness{cids}` emission so the server can defer hard-eviction
     * of suspended peers whose media is still being received locally.
     *
     * Conservative on first call (no baseline → empty result). Cleans up
     * tracking for peers that have left.
     */
    async getInboundFlowingCids(): Promise<string[]> {
        const flowing: string[] = [];
        const seen = new Set<string>();
        for (const [cid, peer] of this.peers) {
            seen.add(cid);
            let bytes = 0;
            try {
                const report = await peer.pc.getStats();
                report.forEach((stat) => {
                    if (stat.type !== 'inbound-rtp') return;
                    const value = (stat as unknown as Record<string, unknown>)['bytesReceived'];
                    if (typeof value === 'number') bytes += value;
                });
            } catch {
                continue;
            }
            const previous = this.lastInboundBytesByCid.get(cid);
            if (previous !== undefined && bytes > previous) {
                flowing.push(cid);
            }
            this.lastInboundBytesByCid.set(cid, bytes);
        }
        for (const cid of [...this.lastInboundBytesByCid.keys()]) {
            if (!seen.has(cid)) this.lastInboundBytesByCid.delete(cid);
        }
        return flowing;
    }

    destroy(): void {
        this.destroyed = true;
        this.cleanupAllPeers();
        this.stopLocalMedia();
        this.removeEventListeners();
        if (this.retryingTimer) { window.clearTimeout(this.retryingTimer); this.retryingTimer = null; }
    }

    /**
     * Inspects each peer connection's currently-selected ICE candidate pair
     * and returns true only when at least one peer exists and every peer's
     * local candidate is direct (host / srflx / prflx). Returns false when
     * any peer is relaying through TURN, when any stats query fails, or when
     * there are no peers (TURN may be needed for a future join).
     *
     * We identify the active pair via `RTCTransportStats.selectedCandidatePairId`,
     * with a fallback to the nominated+succeeded pair. We do NOT accept any
     * arbitrary succeeded pair: after an ICE failover the old pair stays
     * present as "succeeded" for a while, so reading it would lie about the
     * current active path and wrongly suppress TURN refresh while media is
     * actually relaying.
     *
     * Used by the TURN refresh gate: if all active media flows are direct,
     * refreshing TURN credentials over signaling is unnecessary upkeep. This
     * lets a P2P call survive indefinite signaling outages.
     */
    async arePeerPathsAllDirect(): Promise<boolean> {
        const activePeers = Array.from(this.peers.values())
            .filter((peer) => peer.pc.connectionState !== 'closed' && peer.pc.connectionState !== 'failed');
        if (activePeers.length === 0) return false;
        const results = await Promise.all(activePeers.map(async (peer) => {
            try {
                return this.isPeerOnDirectPath(await peer.pc.getStats());
            } catch {
                return false;
            }
        }));
        return results.every(Boolean);
    }

    private isPeerOnDirectPath(stats: RTCStatsReport): boolean {
        // Preferred: resolve the active pair through the transport stat.
        let selectedPairId: string | null = null;
        for (const report of stats.values()) {
            if (report.type !== 'transport') continue;
            const id = (report as { selectedCandidatePairId?: string }).selectedCandidatePairId;
            if (typeof id === 'string' && id !== '') {
                selectedPairId = id;
                break;
            }
        }

        let activePair: RTCIceCandidatePairStats | null = null;
        if (selectedPairId) {
            const pair = stats.get(selectedPairId);
            if (pair && pair.type === 'candidate-pair') {
                activePair = pair as RTCIceCandidatePairStats;
            }
        }

        // Fallback for browsers that don't populate selectedCandidatePairId:
        // the nominated + succeeded pair is authoritative once ICE settles.
        if (!activePair) {
            for (const report of stats.values()) {
                if (report.type !== 'candidate-pair') continue;
                const pair = report as RTCIceCandidatePairStats;
                if (pair.state !== 'succeeded') continue;
                if (!pair.nominated) continue;
                activePair = pair;
                break;
            }
        }

        if (!activePair) return false;
        const localId = activePair.localCandidateId;
        if (!localId) return false;
        const local = stats.get(localId);
        if (!local || local.type !== 'local-candidate') return false;
        const candType = ((local as { candidateType?: string }).candidateType ?? '').toString();
        return candType !== '' && candType !== 'relay';
    }

    // --- Private methods ---

    private syncPeers(): void {
        const myId = this.clientId;
        if (!this.roomState || !myId) {
            if (this.peers.size > 0) {
                this.logger?.log('debug', 'WebRTC', 'Room state cleared, cleaning up all peers');
                this.cleanupAllPeers();
            }
            this.participantConnectionStatus.clear();
            this.mediaRestartHandledAtByCid.clear();
            return;
        }

        const remotePeers = this.roomState.participants?.filter(p => p.cid !== myId) ?? [];
        const remoteCids = new Set(remotePeers.map(p => p.cid));

        for (const [cid] of this.peers) {
            if (!remoteCids.has(cid)) {
                this.logger?.log('debug', 'WebRTC', `Participant ${cid} left, cleaning up peer`);
                this.cleanupPeer(cid);
            }
        }
        for (const cid of Array.from(this.participantConnectionStatus.keys())) {
            if (!remoteCids.has(cid)) this.participantConnectionStatus.delete(cid);
        }
        for (const cid of Array.from(this.mediaRestartHandledAtByCid.keys())) {
            if (!remoteCids.has(cid)) this.mediaRestartHandledAtByCid.delete(cid);
        }

        for (const peer of remotePeers) {
            const previousStatus = this.participantConnectionStatus.get(peer.cid);
            const nextStatus = peer.connectionStatus === 'suspended' ? 'suspended' : 'active';
            this.participantConnectionStatus.set(peer.cid, nextStatus);
            const becameActive = previousStatus === 'suspended' && nextStatus === 'active';
            if (!this.peers.has(peer.cid)) {
                this.getOrCreatePeer(peer.cid);
            }
            if (this.shouldIOffer(peer.cid)) {
                const peerState = this.peers.get(peer.cid);
                if (becameActive) {
                    this.scheduleIceRestart(peer.cid, 'peer-reattached', 0);
                } else if (
                    peerState &&
                    this.localStream &&
                    peerState.pc.signalingState === 'stable' &&
                    !peerState.pc.remoteDescription
                ) {
                    void this.createOfferTo(peer.cid);
                }
            }
        }
        this.notifyChange();
    }

    private getOrCreatePeer(remoteCid: string): PeerState {
        const existing = this.peers.get(remoteCid);
        if (existing) return existing;

        const pc = new RTCPeerConnection(this.rtcConfig);
        const peerState: PeerState = {
            pc, remoteStream: null, iceBuffer: [],
            isMakingOffer: false, offerTimeout: null, iceRestartTimer: null,
            lastIceRestartAt: 0, pendingIceRestart: false,
            pendingLocalTrackNegotiation: false,
            isSettingRemoteAnswerPending: false,
            pendingLocalOfferId: null,
            acceptedRemoteOfferId: null,
            currentNegotiationId: null,
            ignoredOfferId: null,
            pendingRemoteIceByOfferId: new Map(),
            lastOutboundMediaSample: null,
            outboundMediaStallSamples: 0,
            outboundMediaWatchInFlight: false,
            lastOutboundMediaRecoveryAt: 0,
        };

        if (this.localStream) {
            this.localStream.getTracks().forEach(track => pc.addTrack(track, this.localStream!));
            void this.applyAudioSenderParameters(pc);
        }
        this.ensureMediaTransceivers(pc);

        pc.ontrack = (event) => {
            this.logger?.log('debug', 'WebRTC', `[${remoteCid}] Remote track received`);
            let remoteStream: MediaStream;
            if (event.streams?.[0]) {
                remoteStream = event.streams[0];
            } else {
                remoteStream = peerState.remoteStream || new MediaStream();
                if (!remoteStream.getTracks().some(t => t.id === event.track.id)) {
                    remoteStream.addTrack(event.track);
                }
            }
            peerState.remoteStream = remoteStream;
            this.remoteStreams = new Map(this.remoteStreams).set(remoteCid, remoteStream);
            this.notifyChange();
        };

        pc.oniceconnectionstatechange = () => {
            this.logger?.log('debug', 'WebRTC', `[${remoteCid}] ICE: ${pc.iceConnectionState}`);
            this.updateAggregateState();
            if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
                if (peerState.iceRestartTimer) { window.clearTimeout(peerState.iceRestartTimer); peerState.iceRestartTimer = null; }
                peerState.pendingIceRestart = false;
                return;
            }
            if (pc.iceConnectionState === 'disconnected') {
                this.scheduleIceRestart(remoteCid, 'ice-disconnected', 2000);
            } else if (pc.iceConnectionState === 'failed') {
                this.scheduleIceRestart(remoteCid, 'ice-failed', 0);
            }
        };

        pc.onconnectionstatechange = () => {
            this.logger?.log('debug', 'WebRTC', `[${remoteCid}] Connection: ${pc.connectionState}`);
            this.updateAggregateState();
            if (pc.connectionState === 'connected') {
                if (peerState.iceRestartTimer) { window.clearTimeout(peerState.iceRestartTimer); peerState.iceRestartTimer = null; }
                peerState.pendingIceRestart = false;
                return;
            }
            if (pc.connectionState === 'disconnected') {
                this.scheduleIceRestart(remoteCid, 'conn-disconnected', 2000);
            } else if (pc.connectionState === 'failed') {
                this.scheduleIceRestart(remoteCid, 'conn-failed', 0);
            }
        };

        pc.onsignalingstatechange = () => {
            this.updateAggregateState();
            if (pc.signalingState === 'stable') {
                if (peerState.offerTimeout) { window.clearTimeout(peerState.offerTimeout); peerState.offerTimeout = null; }
            }
            if (pc.signalingState === 'stable' && peerState.pendingLocalTrackNegotiation) {
                this.scheduleLocalTrackNegotiation(remoteCid, peerState);
            }
            if (pc.signalingState === 'stable' && peerState.pendingIceRestart) {
                if (peerState.offerTimeout) { window.clearTimeout(peerState.offerTimeout); peerState.offerTimeout = null; }
                if (!this.isSignalingConnected || !this.shouldIOffer(remoteCid)) return;
                peerState.pendingIceRestart = false;
                peerState.lastIceRestartAt = Date.now();
                void this.createOfferTo(remoteCid, { iceRestart: true });
            }
        };

        pc.onicecandidate = (event) => {
            if (event.candidate) {
                const candidate = event.candidate.toJSON();
                if (!candidate.sdpMid) {
                    candidate.sdpMid = String(candidate.sdpMLineIndex ?? 0);
                }
                const payload: Record<string, unknown> = { candidate };
                const offerId = this.currentLocalOfferId(peerState);
                if (offerId) {
                    payload.offerId = offerId;
                }
                this.sendSignalingMessage('ice', payload, remoteCid);
            }
        };

        pc.onnegotiationneeded = async () => {
            const peer = this.peers.get(remoteCid);
            if (!peer) return;
            if (!this.shouldIOffer(remoteCid)) return;
            if (!this.localStream) {
                peer.pendingLocalTrackNegotiation = true;
                return;
            }
            await this.createOfferTo(remoteCid);
        };

        this.peers.set(remoteCid, peerState);
        return peerState;
    }

    private cleanupPeer(remoteCid: string, options: { clearMediaRestartCooldown?: boolean } = {}): void {
        const peer = this.peers.get(remoteCid);
        if (!peer) return;
        const clearMediaRestartCooldown = options.clearMediaRestartCooldown ?? true;
        this.clearPeerTimers(peer);
        this.clearPeerNegotiation(peer);
        peer.pc.close();
        this.peers.delete(remoteCid);
        this.participantConnectionStatus.delete(remoteCid);
        const next = new Map(this.remoteStreams);
        next.delete(remoteCid);
        this.remoteStreams = next;
        this.lastInboundBytesByCid.delete(remoteCid);
        if (clearMediaRestartCooldown) {
            this.mediaRestartHandledAtByCid.delete(remoteCid);
        }
        this.updateAggregateState();
    }

    private replacePeerForRemoteOffer(remoteCid: string, offerId: string, pendingIce: RTCIceCandidateInit[]): PeerState {
        this.cleanupPeer(remoteCid, { clearMediaRestartCooldown: false });
        const peer = this.getOrCreatePeer(remoteCid);
        if (pendingIce.length > 0) {
            peer.pendingRemoteIceByOfferId.set(offerId, [...pendingIce]);
        }
        return peer;
    }

    private clearPeerTimers(peer: PeerState): void {
        if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }
        if (peer.iceRestartTimer) { window.clearTimeout(peer.iceRestartTimer); peer.iceRestartTimer = null; }
    }

    private isCurrentParticipant(remoteCid: string): boolean {
        if (remoteCid === this.clientId) return false;
        if (!this.roomState) return true;
        return this.roomState.participants?.some(p => p.cid === remoteCid) ?? false;
    }

    private shouldIOffer(remoteCid: string): boolean {
        const myId = this.clientId;
        return typeof myId === 'string' && myId.length > 0 && myId < remoteCid && this.isCurrentParticipant(remoteCid);
    }

    private isParticipantActive(remoteCid: string): boolean {
        if (!this.roomState) return true;
        const participant = this.roomState.participants?.find(p => p.cid === remoteCid);
        return !!participant && participant.connectionStatus !== 'suspended';
    }

    private nextOfferId(remoteCid: string): string {
        this.offerSequence += 1;
        return `${this.clientId ?? ''}:${remoteCid}:${Date.now()}:${this.offerSequence}`;
    }

    private currentLocalOfferId(peer: PeerState): string | null {
        return peer.pendingLocalOfferId ?? peer.acceptedRemoteOfferId ?? peer.currentNegotiationId;
    }

    private clearPeerNegotiation(peer: PeerState): void {
        peer.isSettingRemoteAnswerPending = false;
        peer.pendingLocalOfferId = null;
        peer.acceptedRemoteOfferId = null;
        peer.currentNegotiationId = null;
        peer.ignoredOfferId = null;
        peer.pendingRemoteIceByOfferId.clear();
    }

    private isKnownNegotiationId(peer: PeerState, offerId: string): boolean {
        return peer.pendingLocalOfferId === offerId ||
            peer.acceptedRemoteOfferId === offerId ||
            peer.currentNegotiationId === offerId;
    }

    private async flushPendingRemoteIce(peer: PeerState, offerId: string): Promise<void> {
        const pending = peer.pendingRemoteIceByOfferId.get(offerId);
        if (!pending) return;
        peer.pendingRemoteIceByOfferId.delete(offerId);
        for (const candidate of pending) {
            await peer.pc.addIceCandidate(candidate);
        }
    }

    private async createOfferTo(remoteCid: string, options?: { iceRestart?: boolean }): Promise<void> {
        const peer = this.peers.get(remoteCid);
        if (!peer) return;
        if (peer.isMakingOffer) { if (options?.iceRestart) peer.pendingIceRestart = true; return; }
        if (!this.shouldIOffer(remoteCid) || !this.isParticipantActive(remoteCid)) return;
        try {
            if (peer.pc.signalingState !== 'stable') { if (options?.iceRestart) peer.pendingIceRestart = true; return; }
            const offerId = this.nextOfferId(remoteCid);
            peer.pendingLocalOfferId = offerId;
            peer.acceptedRemoteOfferId = null;
            peer.ignoredOfferId = null;
            peer.isMakingOffer = true;
            const offer = await peer.pc.createOffer(options);
            await peer.pc.setLocalDescription(offer as RTCSessionDescriptionInit);
            this.sendSignalingMessage('offer', { sdp: offer.sdp, offerId }, remoteCid);

            if (peer.offerTimeout) window.clearTimeout(peer.offerTimeout);
            peer.offerTimeout = window.setTimeout(() => {
                if (this.peers.get(remoteCid) !== peer) return;
                peer.offerTimeout = null;
                this.logger?.log('warning', 'WebRTC', `[${remoteCid}] Offer timeout`);
                peer.pendingIceRestart = true;
                peer.pendingLocalOfferId = null;
                if (peer.pc.signalingState === 'have-local-offer') {
                    peer.pc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit)
                        .catch(err => this.logger?.log('warning', 'WebRTC', `[${remoteCid}] Rollback failed: ${formatError(err)}`))
                        .finally(() => this.scheduleIceRestart(remoteCid, 'offer-timeout', 0));
                } else {
                    this.scheduleIceRestart(remoteCid, 'offer-timeout-unexpected-state', 0);
                }
            }, OFFER_TIMEOUT_MS);
        } catch (err) {
            peer.pendingLocalOfferId = null;
            this.logger?.log('error', 'WebRTC', `[${remoteCid}] Error creating offer: ${formatError(err)}`);
        } finally {
            peer.isMakingOffer = false;
            if (peer.pendingIceRestart) {
                peer.pendingIceRestart = false;
                this.scheduleIceRestart(remoteCid, 'pending-retry', 500);
            }
        }
    }

    private scheduleIceRestart(remoteCid: string, reason: string, delayMs: number): void {
        const peer = this.peers.get(remoteCid);
        if (!peer) return;
        if (!this.isSignalingConnected || !this.isParticipantActive(remoteCid)) { peer.pendingIceRestart = true; return; }
        if (peer.iceRestartTimer) return;
        if (Date.now() - peer.lastIceRestartAt < ICE_RESTART_COOLDOWN_MS) return;
        peer.iceRestartTimer = window.setTimeout(() => {
            peer.iceRestartTimer = null;
            void this.triggerIceRestart(remoteCid, reason);
        }, delayMs);
    }

    private async triggerIceRestart(remoteCid: string, reason: string): Promise<void> {
        const peer = this.peers.get(remoteCid);
        if (!peer) return;
        if (!this.isSignalingConnected || !this.isParticipantActive(remoteCid)) { peer.pendingIceRestart = true; return; }
        if (!this.shouldIOffer(remoteCid)) return;
        if (peer.isMakingOffer) { peer.pendingIceRestart = true; return; }
        if (peer.pc.signalingState !== 'stable') {
            peer.pendingIceRestart = true;
            if (peer.pc.signalingState === 'have-local-offer') {
                peer.pendingLocalOfferId = null;
                try {
                    await peer.pc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit);
                } catch (err) {
                    this.logger?.log('warning', 'WebRTC', `[${remoteCid}] ICE restart rollback failed: ${formatError(err)}`);
                    return;
                }
                const signalingStateAfterRollback = getSignalingState(peer.pc);
                if (signalingStateAfterRollback !== 'stable') {
                    return;
                }
                peer.pendingIceRestart = false;
            } else {
                return;
            }
        }
        peer.lastIceRestartAt = Date.now();
        peer.pendingIceRestart = false;
        this.logger?.log('warning', 'WebRTC', `ICE restart triggered for ${remoteCid} (${reason})`);
        await this.createOfferTo(remoteCid, { iceRestart: true });
    }

    private async handleOfferFrom(fromCid: string, sdp: string, offerId: string): Promise<void> {
        try {
            const peer = this.getOrCreatePeer(fromCid);
            const readyForOffer = !peer.isMakingOffer &&
                (peer.pc.signalingState === 'stable' || peer.isSettingRemoteAnswerPending);
            const offerCollision = !readyForOffer;
            const polite = !this.shouldIOffer(fromCid);

            if (offerCollision && !polite) {
                peer.ignoredOfferId = offerId;
                this.logger?.log('warning', 'WebRTC', `[${fromCid}] Ignoring colliding offer`);
                return;
            }

            if (offerCollision && peer.pc.signalingState === 'have-local-offer') {
                peer.pendingLocalOfferId = null;
                if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }
                await peer.pc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit);
                if (this.peers.get(fromCid) !== peer) return;
            }

            await this.applyRemoteOffer(peer, fromCid, sdp, offerId, true);
        } catch (err) {
            this.logger?.log('error', 'WebRTC', `[${fromCid}] Error handling offer: ${formatError(err)}`);
        }
    }

    private async applyRemoteOffer(peer: PeerState, fromCid: string, sdp: string, offerId: string, allowPeerReset: boolean): Promise<void> {
        peer.ignoredOfferId = null;
        peer.pendingLocalOfferId = null;
        try {
            await peer.pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
        } catch (err) {
            if (allowPeerReset && this.peers.get(fromCid) === peer) {
                this.logger?.log('warning', 'WebRTC', `[${fromCid}] Recreating peer after remote offer failed: ${formatError(err)}`);
                const pendingIce = peer.pendingRemoteIceByOfferId.get(offerId) ?? [];
                const replacementPeer = this.replacePeerForRemoteOffer(fromCid, offerId, pendingIce);
                await this.applyRemoteOffer(replacementPeer, fromCid, sdp, offerId, false);
                return;
            }
            throw err;
        }
        peer.acceptedRemoteOfferId = offerId;
        peer.currentNegotiationId = offerId;
        if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }
        await this.flushPendingRemoteIce(peer, offerId);
        while (peer.iceBuffer.length > 0) {
            const c = peer.iceBuffer.shift();
            if (c) await peer.pc.addIceCandidate(c);
        }
        if (this.localStream) {
            await this.attachLocalTracksToPeer(fromCid, peer, this.localStream);
        } else {
            await this.startLocalMedia();
        }
        await this.applyAudioSenderParameters(peer.pc);
        const answer = await peer.pc.createAnswer();
        await peer.pc.setLocalDescription(answer);
        this.sendSignalingMessage('answer', { sdp: answer.sdp, offerId }, fromCid);
    }

    private async handleAnswerFrom(fromCid: string, sdp: string, offerId: string): Promise<void> {
        try {
            const peer = this.peers.get(fromCid);
            if (!peer) return;
            // Snapshot the pending offer id before the await; it identifies the offer this
            // answer completes (and covers the legacy/no-offerId path, where `offerId` is the
            // sentinel rather than our real local id).
            const pendingOfferId = peer.pendingLocalOfferId;
            if (peer.pc.signalingState !== 'have-local-offer') {
                this.logger?.log('debug', 'WebRTC', `[${fromCid}] Dropping stale answer in ${peer.pc.signalingState}`);
                return;
            }
            if (offerId !== LEGACY_OFFER_ID && pendingOfferId !== offerId) {
                this.logger?.log('debug', 'WebRTC', `[${fromCid}] Dropping answer for stale offerId=${offerId}`);
                return;
            }
            peer.isSettingRemoteAnswerPending = true;
            await peer.pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }));
            peer.isSettingRemoteAnswerPending = false;
            const completedOfferId = pendingOfferId ?? offerId;
            // Finalize negotiation state only while the pending offer is still the one we
            // completed. The await above yields to the event loop, so a renegotiation offer
            // (e.g. an ICE restart) can reassign pendingLocalOfferId; finalizing then would
            // clobber the newer offer's id and cancel its offer-timeout / pending-retry,
            // leaving it stuck in have-local-offer if its answer is lost.
            if (peer.pendingLocalOfferId === completedOfferId) {
                peer.pendingLocalOfferId = null;
                peer.currentNegotiationId = completedOfferId;
                peer.ignoredOfferId = null;
                if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }
                peer.pendingIceRestart = false;
            }
            await this.flushPendingRemoteIce(peer, completedOfferId);
        } catch (err) {
            const peer = this.peers.get(fromCid);
            if (peer) peer.isSettingRemoteAnswerPending = false;
            this.logger?.log('error', 'WebRTC', `[${fromCid}] Error handling answer: ${formatError(err)}`);
        }
    }

    private async handleIceFrom(fromCid: string, candidate: RTCIceCandidateInit, offerId: string): Promise<void> {
        try {
            const peer = this.getOrCreatePeer(fromCid);
            if (peer.ignoredOfferId === offerId) {
                return;
            }
            if (offerId !== LEGACY_OFFER_ID && !this.isKnownNegotiationId(peer, offerId)) {
                const pending = peer.pendingRemoteIceByOfferId.get(offerId) ?? [];
                if (pending.length < ICE_CANDIDATE_BUFFER_MAX) {
                    pending.push(candidate);
                }
                peer.pendingRemoteIceByOfferId.set(offerId, pending);
                return;
            }
            if (peer.pc.remoteDescription) {
                await peer.pc.addIceCandidate(candidate);
            } else {
                if (peer.iceBuffer.length < ICE_CANDIDATE_BUFFER_MAX) peer.iceBuffer.push(candidate);
            }
        } catch (err) {
            this.logger?.log('error', 'WebRTC', `[${fromCid}] Error handling ICE candidate: ${formatError(err)}`);
        }
    }

    private updateAggregateState(): void {
        const peers = this.peers;
        let worstIce: RTCIceConnectionState = peers.size === 0 ? 'new' : 'completed';
        let worstConn: RTCPeerConnectionState = peers.size === 0 ? 'new' : 'connected';
        let worstSig: RTCSignalingState = peers.size === 0 ? 'stable' : 'stable';

        if (peers.size > 0) {
            for (const [, peer] of peers) {
                const ice = peer.pc.iceConnectionState;
                const conn = peer.pc.connectionState;
                const sig = peer.pc.signalingState;
                if (ICE_STATE_PRIORITY.indexOf(ice) < ICE_STATE_PRIORITY.indexOf(worstIce)) worstIce = ice;
                if (CONN_STATE_PRIORITY.indexOf(conn) < CONN_STATE_PRIORITY.indexOf(worstConn)) worstConn = conn;
                if (SIG_STATE_PRIORITY.indexOf(sig) < SIG_STATE_PRIORITY.indexOf(worstSig)) worstSig = sig;
            }
        }

        this.iceConnectionState = worstIce;
        this.connectionState = worstConn;
        this.signalingState = worstSig;
        this.updateConnectionStatusValue();
        this.notifyChange();
    }

    private updateConnectionStatusValue(): void {
        const isActive = !!this.roomState && (this.roomState.participants?.length ?? 0) > 1;
        if (!isActive) { this.resetConnectionStatusMachine(); return; }
        const isDegraded =
            !this.isSignalingConnected ||
            this.iceConnectionState === 'disconnected' || this.iceConnectionState === 'failed' ||
            this.connectionState === 'disconnected' || this.connectionState === 'failed';
        if (isDegraded) { this.setConnectionRecovering(); return; }
        this.resetConnectionStatusMachine();
    }

    private resetConnectionStatusMachine(): void {
        if (this.retryingTimer) { window.clearTimeout(this.retryingTimer); this.retryingTimer = null; }
        this.connectionStatus = 'connected';
    }

    private setConnectionRecovering(): void {
        if (this.connectionStatus === 'connected') this.connectionStatus = 'recovering';
        if (this.connectionStatus !== 'retrying') this.scheduleRetryingTransition();
    }

    private scheduleRetryingTransition(): void {
        if (this.retryingTimer) return;
        this.retryingTimer = window.setTimeout(() => {
            this.retryingTimer = null;
            if (this.connectionStatus === 'recovering') this.connectionStatus = 'retrying';
            this.notifyChange();
        }, CONNECTION_RETRYING_DELAY_MS);
    }

    private normalizeIceServers(iceServers: RTCIceServer[]): RTCIceServer[] {
        return normalizeIceServers(iceServers, this.turnsOnly);
    }

    private applySpeechTrackHints(stream: MediaStream): void {
        const audioTrack = stream.getAudioTracks()[0];
        if (!audioTrack) return;
        if ('contentHint' in audioTrack) {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any -- contentHint is a valid but untyped browser API
            try { (audioTrack as any).contentHint = 'speech'; } catch { /* ignore */ }
        }
    }

    private async applyAudioSenderParameters(pc: RTCPeerConnection): Promise<void> {
        const sender = pc.getSenders().find(s => s.track?.kind === 'audio');
        if (!sender?.getParameters || !sender?.setParameters) return;
        try {
            const params = sender.getParameters();
            if (!params.encodings || params.encodings.length === 0) return;
            const firstEncoding = params.encodings[0];
            if (!firstEncoding || firstEncoding.maxBitrate === 32000) return;

            const nextParams: RTCRtpSendParameters = {
                ...params,
                encodings: params.encodings.map((encoding, index) => (
                    index === 0 ? { ...encoding, maxBitrate: 32000 } : encoding
                )),
            };
            await sender.setParameters(nextParams);
        } catch (err) {
            this.logger?.log('warning', 'WebRTC', `Failed to apply audio sender parameters: ${formatError(err)}`);
        }
    }

    private async acquireCameraTrack(targetFacingMode: 'user' | 'environment', enabled: boolean): Promise<MediaStreamTrack> {
        let cameraStream: MediaStream;
        try {
            cameraStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: targetFacingMode }, audio: false });
        } catch {
            cameraStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
        }
        const cameraTrack = cameraStream.getVideoTracks()[0];
        if (!cameraTrack) {
            cameraStream.getTracks().forEach(track => track.stop());
            throw new Error('No camera track returned');
        }
        cameraTrack.enabled = enabled;
        return cameraTrack;
    }

    private async replaceVideoTrackOnAllPeers(newTrack: MediaStreamTrack | null, stream: MediaStream | null): Promise<void> {
        await Promise.all(
            Array.from(this.peers.entries()).map(async ([remoteCid, peer]) => {
                const videoTransceiver = this.findTransceiver(peer.pc, 'video');
                if (videoTransceiver) {
                    try {
                        await videoTransceiver.sender.replaceTrack(newTrack);
                        if (videoTransceiver.direction !== 'sendrecv' && videoTransceiver.direction !== 'stopped' && this.videoCaptureSupported) {
                            videoTransceiver.direction = 'sendrecv';
                        }
                        if (newTrack && this.needsLocalTrackNegotiation(videoTransceiver)) {
                            this.scheduleLocalTrackNegotiation(remoteCid, peer);
                        }
                    } catch (err) {
                        this.logger?.log('warning', 'WebRTC', `Failed to replace video track on peer: ${formatError(err)}`);
                    }
                    return;
                }
                if (newTrack && stream) {
                    try {
                        peer.pc.addTrack(newTrack, stream);
                        this.scheduleLocalTrackNegotiation(remoteCid, peer);
                    } catch (err) {
                        this.logger?.log('warning', 'WebRTC', `Failed to add video track on peer: ${formatError(err)}`);
                    }
                }
            })
        );
    }

    private async swapLocalVideoTrack(nextTrack: MediaStreamTrack | null, previousTrack: MediaStreamTrack | null): Promise<void> {
        if (!this.localStream) {
            if (previousTrack && previousTrack !== nextTrack) previousTrack.stop();
            return;
        }
        const nextStream = new MediaStream();
        let replacedVideo = false;
        for (const track of this.localStream.getTracks()) {
            if (track.kind !== 'video') {
                nextStream.addTrack(track);
                continue;
            }
            if (!replacedVideo && nextTrack) {
                nextStream.addTrack(nextTrack);
                replacedVideo = true;
            }
        }
        if (nextTrack && !replacedVideo) {
            nextStream.addTrack(nextTrack);
        }
        this.localStream = nextStream;
        await this.replaceVideoTrackOnAllPeers(nextTrack, nextStream);
        if (previousTrack && previousTrack !== nextTrack) previousTrack.stop();
        this.notifyChange();
    }

    private ensureMediaTransceivers(pc: RTCPeerConnection): void {
        if (!this.findTransceiver(pc, 'audio') && !pc.getSenders().some(sender => sender.track?.kind === 'audio')) {
            pc.addTransceiver('audio', { direction: 'recvonly' });
        }
        if (!this.findTransceiver(pc, 'video') && !pc.getSenders().some(sender => sender.track?.kind === 'video')) {
            pc.addTransceiver('video', { direction: this.videoCaptureSupported ? 'sendrecv' : 'recvonly' });
        }
    }

    private findTransceiver(pc: RTCPeerConnection, kind: 'audio' | 'video'): RTCRtpTransceiver | undefined {
        const transceivers = pc.getTransceivers().filter(transceiver => (
            transceiver.receiver.track?.kind === kind || transceiver.sender.track?.kind === kind
        ));
        return transceivers.find(transceiver => transceiver.mid !== null) ?? transceivers[0];
    }

    private async attachLocalTracksToPeer(remoteCid: string, peer: PeerState, stream: MediaStream): Promise<void> {
        let negotiationNeeded = false;
        for (const track of stream.getTracks()) {
            negotiationNeeded = await this.attachLocalTrackToPeer(peer, track, stream) || negotiationNeeded;
        }
        if (negotiationNeeded) {
            this.scheduleLocalTrackNegotiation(remoteCid, peer);
        }
    }

    private async attachLocalTrackToPeer(peer: PeerState, track: MediaStreamTrack, stream: MediaStream): Promise<boolean> {
        const transceiver = track.kind === 'audio' || track.kind === 'video'
            ? this.findTransceiver(peer.pc, track.kind)
            : undefined;
        if (transceiver) {
            if (transceiver.sender.track?.kind === track.kind) {
                return this.needsLocalTrackNegotiation(transceiver);
            }
            for (const sender of peer.pc.getSenders()) {
                if (sender === transceiver.sender || sender.track?.kind !== track.kind) {
                    continue;
                }
                try {
                    await sender.replaceTrack(null);
                } catch (err) {
                    this.logger?.log('warning', 'WebRTC', `Failed to detach stale ${track.kind} track from peer: ${formatError(err)}`);
                }
            }
            try {
                await transceiver.sender.replaceTrack(track);
                let negotiationNeeded = this.needsLocalTrackNegotiation(transceiver);
                if (transceiver.direction !== 'sendrecv' && transceiver.direction !== 'stopped') {
                    transceiver.direction = 'sendrecv';
                    negotiationNeeded = true;
                }
                return negotiationNeeded;
            } catch (err) {
                this.logger?.log('warning', 'WebRTC', `Failed to attach ${track.kind} track to peer: ${formatError(err)}`);
            }
            return false;
        }

        if (peer.pc.getSenders().some(sender => sender.track?.kind === track.kind)) {
            return false;
        }
        peer.pc.addTrack(track, stream);
        return true;
    }

    private scheduleLocalTrackNegotiation(remoteCid: string, peer: PeerState): void {
        if (!this.isSignalingConnected) {
            peer.pendingLocalTrackNegotiation = true;
            return;
        }
        if (!this.shouldIOffer(remoteCid) && !peer.pc.remoteDescription) {
            peer.pendingLocalTrackNegotiation = true;
            return;
        }
        if (peer.pc.signalingState !== 'stable') {
            peer.pendingLocalTrackNegotiation = true;
            return;
        }
        peer.pendingLocalTrackNegotiation = false;
        if (!this.hasUnnegotiatedLocalTracks(peer.pc)) {
            return;
        }
        if (!this.shouldIOffer(remoteCid)) {
            this.requestPeerMediaRecovery(remoteCid, MEDIA_RESTART_REASON_LOCAL_TRACK_NEGOTIATION);
            return;
        }
        void this.createOfferTo(remoteCid);
    }

    private hasUnnegotiatedLocalTracks(pc: RTCPeerConnection): boolean {
        for (const sender of pc.getSenders()) {
            const track = sender.track;
            if (!track || track.readyState !== 'live' || !track.enabled) {
                continue;
            }
            const transceiver = pc.getTransceivers().find(candidate => candidate.sender === sender);
            if (!transceiver) {
                return true;
            }
            if (this.needsLocalTrackNegotiation(transceiver)) {
                return true;
            }
        }
        return false;
    }

    private needsLocalTrackNegotiation(transceiver: RTCRtpTransceiver): boolean {
        const track = transceiver.sender.track;
        if (!track || track.readyState !== 'live' || !track.enabled) {
            return false;
        }
        return transceiver.currentDirection !== 'sendrecv' && transceiver.currentDirection !== 'sendonly';
    }

    private async refreshLocalVideoTrack(reason: string, forceRefresh = false): Promise<boolean> {
        const currentVideoTrack = this.localStream?.getVideoTracks()[0] ?? null;
        const shouldRecover = shouldRecoverLocalVideo({
            hasVideoTrack: !!currentVideoTrack,
            isScreenSharing: this.isScreenSharing,
            videoTrackReadyState: currentVideoTrack?.readyState ?? null,
            videoTrackMuted: currentVideoTrack?.muted ?? false,
            forceRefresh
        });

        if (!shouldRecover || this.cameraRecoveryInFlight || this.requestingMedia) return false;

        this.cameraRecoveryInFlight = true;
        try {
            const nextTrack = await this.acquireCameraTrack(this.facingMode, currentVideoTrack?.enabled ?? true);
            await this.swapLocalVideoTrack(nextTrack, currentVideoTrack);
            this.logger?.log('info', 'WebRTC', `Refreshed local video track (${reason})`);
            return true;
        } catch (err) {
            this.logger?.log('error', 'WebRTC', `Failed to refresh local video track (${reason}): ${formatError(err)}`);
            return false;
        } finally {
            this.cameraRecoveryInFlight = false;
        }
    }

    private async detectCameras(): Promise<void> {
        if (!navigator.mediaDevices?.enumerateDevices) return;
        try {
            const devices = await navigator.mediaDevices.enumerateDevices();
            this.hasMultipleCameras = devices.filter(d => d.kind === 'videoinput').length > 1;
        } catch { /* ignore */ }
    }

    private setupEventListeners(): void {
        this.onlineHandler = () => {
            for (const [cid] of this.peers) this.scheduleIceRestart(cid, 'network-online', 0);
        };
        window.addEventListener('online', this.onlineHandler);

        this.networkChangeHandler = () => {
            for (const [cid] of this.peers) this.scheduleIceRestart(cid, 'network-change', 0);
        };
        // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Network Information API is untyped
        const conn = (navigator as any).connection;
        conn?.addEventListener?.('change', this.networkChangeHandler);

        this.deviceChangeHandler = () => { void this.detectCameras(); };
        navigator.mediaDevices?.addEventListener?.('devicechange', this.deviceChangeHandler);
        void this.detectCameras();

        // Local video recovery
        const consumeHiddenDuration = (): number | null => {
            const now = Date.now();
            const hiddenDurationMs = this.localVideoHiddenAt ? now - this.localVideoHiddenAt : null;
            this.localVideoHiddenAt = null;
            this.localVideoHeartbeatAt = now;
            return hiddenDurationMs;
        };

        this.visibilityHandler = () => {
            if (document.hidden) {
                const now = Date.now();
                this.localVideoHiddenAt = now;
                this.localVideoHeartbeatAt = now;
                return;
            }
            const hiddenDurationMs = consumeHiddenDuration();
            const forceRefresh = shouldForceLocalVideoRefresh({ hiddenDurationMs });
            void this.refreshLocalVideoTrack('visibility-resume', forceRefresh);
        };
        document.addEventListener('visibilitychange', this.visibilityHandler);

        this.pageShowHandler = (event: PageTransitionEvent) => {
            const hiddenDurationMs = consumeHiddenDuration();
            const forceRefresh = event.persisted || shouldForceLocalVideoRefresh({ hiddenDurationMs });
            void this.refreshLocalVideoTrack('pageshow-resume', forceRefresh);
        };
        window.addEventListener('pageshow', this.pageShowHandler);

        this.heartbeatInterval = window.setInterval(() => {
            const now = Date.now();
            const sleepGapMs = now - this.localVideoHeartbeatAt;
            this.localVideoHeartbeatAt = now;
            if (document.hidden || !shouldForceLocalVideoRefresh({ sleepGapMs })) return;
            void this.refreshLocalVideoTrack('sleep-resume', true);
        }, LOCAL_VIDEO_HEARTBEAT_INTERVAL_MS);

        this.outboundMediaWatchdogInterval = window.setInterval(() => {
            if (!document.hidden) {
                void this.recoverStalledOutboundMedia();
            }
        }, OUTBOUND_MEDIA_WATCHDOG_INTERVAL_MS);
    }

    private removeEventListeners(): void {
        if (this.onlineHandler) window.removeEventListener('online', this.onlineHandler);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Network Information API is untyped
        const conn = (navigator as any).connection;
        if (this.networkChangeHandler) conn?.removeEventListener?.('change', this.networkChangeHandler);
        if (this.deviceChangeHandler) navigator.mediaDevices?.removeEventListener?.('devicechange', this.deviceChangeHandler);
        if (this.visibilityHandler) document.removeEventListener('visibilitychange', this.visibilityHandler);
        if (this.pageShowHandler) window.removeEventListener('pageshow', this.pageShowHandler);
        if (this.heartbeatInterval !== null) window.clearInterval(this.heartbeatInterval);
        if (this.outboundMediaWatchdogInterval !== null) window.clearInterval(this.outboundMediaWatchdogInterval);
    }

    private async recoverStalledOutboundMedia(): Promise<void> {
        if (this.destroyed || !this.localStream) return;
        await Promise.all(
            Array.from(this.peers.entries()).map(([remoteCid, peer]) => (
                this.recoverStalledOutboundMediaForPeer(remoteCid, peer)
            ))
        );
    }

    private async recoverStalledOutboundMediaForPeer(remoteCid: string, peer: PeerState): Promise<void> {
        if (peer.outboundMediaWatchInFlight) return;
        if (!this.isPeerMediaConnected(peer.pc)) {
            this.resetOutboundMediaWatch(peer);
            return;
        }

        const expected = this.getExpectedOutboundMedia(peer.pc);
        if (!expected.audio && !expected.video) {
            this.resetOutboundMediaWatch(peer);
            return;
        }

        peer.outboundMediaWatchInFlight = true;
        try {
            const sample = this.readOutboundMediaSample(await peer.pc.getStats());
            if (this.peers.get(remoteCid) !== peer) return;
            const previous = peer.lastOutboundMediaSample;
            peer.lastOutboundMediaSample = sample;
            if (!previous) {
                peer.outboundMediaStallSamples = 0;
                return;
            }

            const videoStalled = expected.video &&
                sample.videoBytesSent <= previous.videoBytesSent &&
                sample.videoFramesSent <= previous.videoFramesSent;
            const audioOnlyStalled = !expected.video && expected.audio &&
                sample.audioBytesSent <= previous.audioBytesSent;
            if (!videoStalled && !audioOnlyStalled) {
                peer.outboundMediaStallSamples = 0;
                return;
            }

            peer.outboundMediaStallSamples += 1;
            if (peer.outboundMediaStallSamples < OUTBOUND_MEDIA_STALL_SAMPLES) return;

            const now = Date.now();
            if (now - peer.lastOutboundMediaRecoveryAt < OUTBOUND_MEDIA_RECOVERY_COOLDOWN_MS) return;

            peer.lastOutboundMediaRecoveryAt = now;
            peer.outboundMediaStallSamples = 0;
            peer.lastOutboundMediaSample = null;
            if (this.shouldIOffer(remoteCid)) {
                await this.recreatePeerForMediaRecovery(remoteCid, 'stalled outbound media');
            } else {
                this.requestPeerMediaRecovery(remoteCid, 'stalled outbound media');
            }
        } catch (err) {
            this.logger?.log('debug', 'WebRTC', `[${remoteCid}] Outbound media watchdog failed: ${formatError(err)}`);
        } finally {
            peer.outboundMediaWatchInFlight = false;
        }
    }

    private isPeerMediaConnected(pc: RTCPeerConnection): boolean {
        return pc.signalingState === 'stable' &&
            (pc.connectionState === 'connected') &&
            (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed');
    }

    private getExpectedOutboundMedia(pc: RTCPeerConnection): { audio: boolean; video: boolean } {
        let audio = false;
        let video = false;
        for (const sender of pc.getSenders()) {
            const track = sender.track;
            if (!track || track.readyState !== 'live' || !track.enabled) continue;
            if (track.kind === 'audio') audio = true;
            if (track.kind === 'video') video = true;
        }
        return { audio, video };
    }

    private readOutboundMediaSample(stats: RTCStatsReport): OutboundMediaSample {
        const sample: OutboundMediaSample = {
            audioBytesSent: 0,
            videoBytesSent: 0,
            videoFramesSent: 0,
        };

        stats.forEach((stat) => {
            if (stat.type !== 'outbound-rtp') return;
            const kind = getStatString(stat, 'kind') ?? getStatString(stat, 'mediaType');
            if (kind === 'audio') {
                sample.audioBytesSent += getStatNumber(stat, 'bytesSent');
                return;
            }
            if (kind === 'video') {
                sample.videoBytesSent += getStatNumber(stat, 'bytesSent');
                sample.videoFramesSent += getStatNumber(stat, 'framesSent');
            }
        });

        return sample;
    }

    private resetOutboundMediaWatch(peer: PeerState): void {
        peer.lastOutboundMediaSample = null;
        peer.outboundMediaStallSamples = 0;
    }

    private requestPeerMediaRecovery(remoteCid: string, reason: string): void {
        if (!this.isSignalingConnected || !this.isParticipantActive(remoteCid)) return;
        if (reason === MEDIA_RESTART_REASON_LOCAL_TRACK_NEGOTIATION) {
            this.logger?.log('debug', 'WebRTC', `[${remoteCid}] Requesting offer after ${reason}`);
        } else {
            this.logger?.log('warning', 'WebRTC', `[${remoteCid}] Requesting media restart after ${reason}`);
        }
        this.sendSignalingMessage('media_restart_request', { reason }, remoteCid);
    }

    private async handleMediaRestartRequest(fromCid: string, reason = ''): Promise<void> {
        if (reason === MEDIA_RESTART_REASON_LOCAL_TRACK_NEGOTIATION) {
            await this.handlePeerLocalTrackNegotiationRequest(fromCid);
            return;
        }
        const now = Date.now();
        const lastHandledAt = this.mediaRestartHandledAtByCid.get(fromCid) ?? 0;
        if (now - lastHandledAt < OUTBOUND_MEDIA_RECOVERY_COOLDOWN_MS) return;
        this.mediaRestartHandledAtByCid.set(fromCid, now);
        await this.recreatePeerForMediaRecovery(fromCid, 'peer media restart request');
    }

    private async handlePeerLocalTrackNegotiationRequest(fromCid: string): Promise<void> {
        if (!this.shouldIOffer(fromCid) || !this.isSignalingConnected || !this.isParticipantActive(fromCid)) return;
        const peer = this.peers.get(fromCid);
        if (!peer || peer.pc.signalingState !== 'stable') return;
        this.logger?.log('debug', 'WebRTC', `[${fromCid}] Creating offer after peer local track negotiation request`);
        await this.createOfferTo(fromCid);
    }

    private async recreatePeerForMediaRecovery(remoteCid: string, reason: string): Promise<void> {
        if (!this.shouldIOffer(remoteCid) || !this.isSignalingConnected || !this.isParticipantActive(remoteCid)) return;
        const previousStatus = this.participantConnectionStatus.get(remoteCid);
        this.logger?.log('warning', 'WebRTC', `[${remoteCid}] Recreating peer after ${reason}`);
        this.cleanupPeer(remoteCid, { clearMediaRestartCooldown: false });
        if (previousStatus) this.participantConnectionStatus.set(remoteCid, previousStatus);
        const replacement = this.getOrCreatePeer(remoteCid);
        replacement.lastOutboundMediaRecoveryAt = Date.now();
        await this.createOfferTo(remoteCid);
    }

    private notifyChange(): void { this.onChange?.(); }
}
