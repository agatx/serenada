import type { RoomState, SignalingMessage } from '../signaling/types.js';
import type { ConnectionStatus, SerenadaLogger } from '../types.js';
import { formatError } from '../formatError.js';
import {
    OFFER_TIMEOUT_MS,
    ICE_RESTART_COOLDOWN_MS,
    NON_HOST_FALLBACK_DELAY_MS,
    NON_HOST_FALLBACK_MAX_ATTEMPTS,
    ICE_CANDIDATE_BUFFER_MAX,
    TURN_FETCH_TIMEOUT_MS,
    CONNECTION_RETRYING_DELAY_MS,
    LOCAL_VIDEO_HEARTBEAT_INTERVAL_MS,
} from '../constants.js';
import { buildApiUrl } from '../serverUrls.js';
import { shouldForceLocalVideoRefresh, shouldRecoverLocalVideo } from './localVideoRecovery.js';

const DEFAULT_RTC_CONFIG: RTCConfiguration = {
    iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
};

const ICE_STATE_PRIORITY: RTCIceConnectionState[] = ['failed', 'disconnected', 'checking', 'new', 'connected', 'completed', 'closed'];
const CONN_STATE_PRIORITY: RTCPeerConnectionState[] = ['failed', 'disconnected', 'connecting', 'new', 'connected', 'closed'];
const SIG_STATE_PRIORITY: RTCSignalingState[] = ['closed', 'have-local-offer', 'have-remote-offer', 'have-local-pranswer', 'have-remote-pranswer', 'stable'];

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
    nonHostFallbackTimer: number | null;
    nonHostFallbackAttempts: number;
}

export interface MediaEngineConfig {
    serverHost: string;
    turnsOnly?: boolean;
    logger?: SerenadaLogger;
}

export class MediaEngine {
    localStream: MediaStream | null = null;
    remoteStreams = new Map<string, MediaStream>();
    isScreenSharing = false;
    canScreenShare = !!navigator.mediaDevices?.getDisplayMedia;
    facingMode: 'user' | 'environment' = 'user';
    hasMultipleCameras = false;
    iceConnectionState: RTCIceConnectionState = 'new';
    connectionState: RTCPeerConnectionState = 'new';
    signalingState: RTCSignalingState = 'stable';
    connectionStatus: ConnectionStatus = 'connected';

    private peers = new Map<string, PeerState>();
    private rtcConfig: RTCConfiguration = DEFAULT_RTC_CONFIG;
    private screenShareTrack: MediaStreamTrack | null = null;
    private requestingMedia = false;
    private destroyed = false;
    private cameraRecoveryInFlight = false;
    private mediaRequestId = 0;
    private retryingTimer: number | null = null;
    private localVideoHeartbeatAt = Date.now();
    private localVideoHiddenAt: number | null = typeof document !== 'undefined' && document.hidden ? Date.now() : null;
    private visibilityHandler: (() => void) | null = null;
    private pageShowHandler: ((e: PageTransitionEvent) => void) | null = null;
    private heartbeatInterval: number | null = null;
    private onlineHandler: (() => void) | null = null;
    private networkChangeHandler: (() => void) | null = null;
    private deviceChangeHandler: (() => void) | null = null;
    private turnFetchController: AbortController | null = null;
    private turnTokenInFlight: string | null = null;
    private appliedTurnToken: string | null = null;
    private serverHost: string;
    private turnsOnly: boolean;
    private logger?: SerenadaLogger;

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
        this.serverHost = config.serverHost;
        this.turnsOnly = config.turnsOnly ?? false;
        this.logger = config.logger;
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
                if (peer.pendingLocalTrackNegotiation && peer.pc.remoteDescription && peer.pc.signalingState === 'stable') {
                    peer.pendingLocalTrackNegotiation = false;
                    void this.createOfferTo(cid);
                }
            }
        }
    }

    updateTurnToken(token: string): void {
        if (token === this.appliedTurnToken || token === this.turnTokenInFlight) {
            return;
        }
        this.turnFetchController?.abort();
        this.turnFetchController = new AbortController();
        this.turnTokenInFlight = token;
        void this.fetchIceServers(token, this.turnFetchController.signal).then((applied) => {
            if (applied) {
                this.appliedTurnToken = token;
            }
        }).finally(() => {
            if (this.turnTokenInFlight === token) {
                this.turnTokenInFlight = null;
            }
        });
    }

    processSignalingMessage(msg: SignalingMessage): void {
        const { type, payload } = msg;
        if (!payload) return;
        const fromCid = payload.from as string | undefined;
        try {
            switch (type) {
                case 'offer':
                    if (fromCid && payload.sdp) void this.handleOfferFrom(fromCid, payload.sdp as string);
                    break;
                case 'answer':
                    if (fromCid && payload.sdp) void this.handleAnswerFrom(fromCid, payload.sdp as string);
                    break;
                case 'ice':
                    if (fromCid && payload.candidate) void this.handleIceFrom(fromCid, payload.candidate as RTCIceCandidateInit);
                    break;
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
            try {
                stream = await navigator.mediaDevices.getUserMedia({
                    video: { facingMode: this.facingMode },
                    audio: audioConstraints
                });
            } catch {
                stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
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
                stream.getTracks().forEach(track => peer.pc.addTrack(track, stream));
                void this.applyAudioSenderParameters(peer.pc);
                if (!this.shouldIOffer(remoteCid) && peer.pc.remoteDescription) {
                    if (peer.pc.signalingState === 'stable') {
                        void this.createOfferTo(remoteCid);
                    } else {
                        peer.pendingLocalTrackNegotiation = true;
                    }
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
        if (this.localStream) {
            this.localStream.getTracks().forEach(t => t.stop());
            this.localStream = null;
        }
        this.isScreenSharing = false;
        this.facingMode = 'user';
        this.requestingMedia = false;
        this.notifyChange();
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
            const wasVideoEnabled = previousVideoTrack ? previousVideoTrack.enabled : true;
            displayTrack.enabled = wasVideoEnabled;
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
            this.logger?.log('error', 'ScreenShare', `Failed to start screen share: ${formatError(err)}`);
        }
    }

    async stopScreenShare(): Promise<void> {
        if (!this.isScreenSharing) return;
        if (!this.localStream) {
            this.isScreenSharing = false;
            this.sendSignalingMessage('content_state', { active: false });
            this.notifyChange();
            return;
        }

        if (this.screenShareTrack) {
            this.screenShareTrack.onended = null;
            this.screenShareTrack = null;
        }

        const previousVideoTrack = this.localStream.getVideoTracks()[0];
        const wasVideoEnabled = previousVideoTrack ? previousVideoTrack.enabled : true;

        try {
            const cameraTrack = await this.acquireCameraTrack(this.facingMode, wasVideoEnabled);
            await this.swapLocalVideoTrack(cameraTrack, previousVideoTrack);
        } catch (err) {
            this.logger?.log('error', 'ScreenShare', `Failed to stop screen share and restore camera: ${formatError(err)}`);
            await this.swapLocalVideoTrack(null, previousVideoTrack);
        } finally {
            this.isScreenSharing = false;
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
            peer.pc.close();
        }
        this.peers.clear();
        this.remoteStreams = new Map();
        if (this.retryingTimer) { window.clearTimeout(this.retryingTimer); this.retryingTimer = null; }
        this.iceConnectionState = 'closed';
        this.connectionState = 'closed';
        this.signalingState = 'closed';
        this.connectionStatus = 'connected';
        this.notifyChange();
    }

    destroy(): void {
        this.destroyed = true;
        this.cleanupAllPeers();
        this.stopLocalMedia();
        this.removeEventListeners();
        this.turnFetchController?.abort();
        this.turnFetchController = null;
        this.turnTokenInFlight = null;
        this.appliedTurnToken = null;
        if (this.retryingTimer) { window.clearTimeout(this.retryingTimer); this.retryingTimer = null; }
    }

    // --- Private methods ---

    private syncPeers(): void {
        const myId = this.clientId;
        if (!this.roomState || !myId) {
            if (this.peers.size > 0) {
                this.logger?.log('debug', 'WebRTC', 'Room state cleared, cleaning up all peers');
                this.cleanupAllPeers();
            }
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

        for (const peer of remotePeers) {
            if (!this.peers.has(peer.cid)) {
                this.getOrCreatePeer(peer.cid);
                if (this.shouldIOffer(peer.cid)) {
                    const peerState = this.peers.get(peer.cid);
                    if (peerState && peerState.pc.signalingState === 'stable' && !peerState.pc.remoteDescription) {
                        void this.createOfferTo(peer.cid);
                    }
                } else {
                    this.scheduleNonHostFallback(peer.cid);
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
            nonHostFallbackTimer: null, nonHostFallbackAttempts: 0,
        };

        if (this.localStream) {
            this.localStream.getTracks().forEach(track => pc.addTrack(track, this.localStream!));
            void this.applyAudioSenderParameters(pc);
        }

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
                if (!this.isSignalingConnected || !peerState.pc.remoteDescription) return;
                peerState.pendingLocalTrackNegotiation = false;
                void this.createOfferTo(remoteCid);
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
                this.sendSignalingMessage('ice', { candidate: event.candidate }, remoteCid);
            }
        };

        pc.onnegotiationneeded = async () => {
            if (!this.shouldIOffer(remoteCid)) return;
            await this.createOfferTo(remoteCid);
        };

        this.peers.set(remoteCid, peerState);
        return peerState;
    }

    private cleanupPeer(remoteCid: string): void {
        const peer = this.peers.get(remoteCid);
        if (!peer) return;
        this.clearPeerTimers(peer);
        peer.pc.close();
        this.peers.delete(remoteCid);
        const next = new Map(this.remoteStreams);
        next.delete(remoteCid);
        this.remoteStreams = next;
        this.updateAggregateState();
    }

    private clearPeerTimers(peer: PeerState): void {
        if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }
        if (peer.iceRestartTimer) { window.clearTimeout(peer.iceRestartTimer); peer.iceRestartTimer = null; }
        if (peer.nonHostFallbackTimer) { window.clearTimeout(peer.nonHostFallbackTimer); peer.nonHostFallbackTimer = null; }
    }

    private shouldIOffer(remoteCid: string): boolean {
        if (!this.roomState) return false;
        const myId = this.clientId;
        if (!myId) return false;
        const myP = this.roomState.participants?.find(p => p.cid === myId);
        const theirP = this.roomState.participants?.find(p => p.cid === remoteCid);
        if (!myP || !theirP) return false;
        const myJoinedAt = myP.joinedAt ?? 0;
        const theirJoinedAt = theirP.joinedAt ?? 0;
        return myJoinedAt < theirJoinedAt || (myJoinedAt === theirJoinedAt && myId < remoteCid);
    }

    private async createOfferTo(remoteCid: string, options?: { iceRestart?: boolean }): Promise<void> {
        const peer = this.peers.get(remoteCid);
        if (!peer) return;
        if (peer.isMakingOffer) { if (options?.iceRestart) peer.pendingIceRestart = true; return; }
        try {
            if (peer.pc.signalingState !== 'stable') { if (options?.iceRestart) peer.pendingIceRestart = true; return; }
            peer.isMakingOffer = true;
            const offer = await peer.pc.createOffer(options);
            await peer.pc.setLocalDescription(offer as RTCSessionDescriptionInit);
            this.sendSignalingMessage('offer', { sdp: offer.sdp }, remoteCid);

            if (peer.offerTimeout) window.clearTimeout(peer.offerTimeout);
            peer.offerTimeout = window.setTimeout(() => {
                peer.offerTimeout = null;
                const currentPeer = this.peers.get(remoteCid);
                if (!currentPeer) return;
                this.logger?.log('warning', 'WebRTC', `[${remoteCid}] Offer timeout`);
                currentPeer.pendingIceRestart = true;
                if (currentPeer.pc.signalingState === 'have-local-offer') {
                    currentPeer.pc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit)
                        .catch(err => this.logger?.log('warning', 'WebRTC', `[${remoteCid}] Rollback failed: ${formatError(err)}`))
                        .finally(() => this.scheduleIceRestart(remoteCid, 'offer-timeout', 0));
                } else {
                    this.scheduleIceRestart(remoteCid, 'offer-timeout-unexpected-state', 0);
                }
            }, OFFER_TIMEOUT_MS);
        } catch (err) {
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
        if (!this.isSignalingConnected) { peer.pendingIceRestart = true; return; }
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
        if (!this.isSignalingConnected) { peer.pendingIceRestart = true; return; }
        if (!this.shouldIOffer(remoteCid)) return;
        if (peer.isMakingOffer) { peer.pendingIceRestart = true; return; }
        peer.lastIceRestartAt = Date.now();
        peer.pendingIceRestart = false;
        this.logger?.log('warning', 'WebRTC', `ICE restart triggered for ${remoteCid} (${reason})`);
        await this.createOfferTo(remoteCid, { iceRestart: true });
    }

    private scheduleNonHostFallback(remoteCid: string): void {
        if (this.shouldIOffer(remoteCid)) return;
        const peer = this.peers.get(remoteCid);
        if (!peer || peer.nonHostFallbackTimer) return;
        if (peer.nonHostFallbackAttempts >= NON_HOST_FALLBACK_MAX_ATTEMPTS) return;

        peer.nonHostFallbackTimer = window.setTimeout(async () => {
            peer.nonHostFallbackTimer = null;
            const currentPeer = this.peers.get(remoteCid);
            if (!currentPeer) return;
            if (this.shouldIOffer(remoteCid)) return;
            if (currentPeer.pc.remoteDescription) return;
            if (currentPeer.pc.signalingState !== 'stable') return;
            if (!this.isSignalingConnected) return;

            currentPeer.nonHostFallbackAttempts++;
            this.logger?.log('warning', 'WebRTC', `[${remoteCid}] Non-host fallback offer (attempt ${currentPeer.nonHostFallbackAttempts})`);
            try {
                const offer = await currentPeer.pc.createOffer();
                await currentPeer.pc.setLocalDescription(offer as RTCSessionDescriptionInit);
                this.sendSignalingMessage('offer', { sdp: offer.sdp }, remoteCid);

                if (currentPeer.offerTimeout) window.clearTimeout(currentPeer.offerTimeout);
                currentPeer.offerTimeout = window.setTimeout(async () => {
                    currentPeer.offerTimeout = null;
                    const p = this.peers.get(remoteCid);
                    if (!p) return;
                    if (p.pc.signalingState === 'have-local-offer') {
                        try { await p.pc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit); }
                        catch (err) { this.logger?.log('warning', 'WebRTC', `[${remoteCid}] Non-host rollback failed: ${formatError(err)}`); }
                    }
                    this.scheduleNonHostFallback(remoteCid);
                }, OFFER_TIMEOUT_MS);
            } catch (err) {
                this.logger?.log('error', 'WebRTC', `[${remoteCid}] Non-host fallback offer failed: ${formatError(err)}`);
                this.scheduleNonHostFallback(remoteCid);
            }
        }, NON_HOST_FALLBACK_DELAY_MS);
    }

    private async handleOfferFrom(fromCid: string, sdp: string): Promise<void> {
        try {
            const peer = this.getOrCreatePeer(fromCid);
            if (peer.nonHostFallbackTimer) { window.clearTimeout(peer.nonHostFallbackTimer); peer.nonHostFallbackTimer = null; }
            await peer.pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
            if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }
            while (peer.iceBuffer.length > 0) {
                const c = peer.iceBuffer.shift();
                if (c) await peer.pc.addIceCandidate(c);
            }
            const answer = await peer.pc.createAnswer();
            await peer.pc.setLocalDescription(answer);
            this.sendSignalingMessage('answer', { sdp: answer.sdp }, fromCid);
        } catch (err) {
            this.logger?.log('error', 'WebRTC', `[${fromCid}] Error handling offer: ${formatError(err)}`);
        }
    }

    private async handleAnswerFrom(fromCid: string, sdp: string): Promise<void> {
        try {
            const peer = this.peers.get(fromCid);
            if (!peer) return;
            if (peer.nonHostFallbackTimer) { window.clearTimeout(peer.nonHostFallbackTimer); peer.nonHostFallbackTimer = null; }
            await peer.pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }));
            if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }
        } catch (err) {
            this.logger?.log('error', 'WebRTC', `[${fromCid}] Error handling answer: ${formatError(err)}`);
        }
    }

    private async handleIceFrom(fromCid: string, candidate: RTCIceCandidateInit): Promise<void> {
        try {
            const peer = this.getOrCreatePeer(fromCid);
            if (peer.pc.remoteDescription) {
                await peer.pc.addIceCandidate(candidate);
            } else {
                if (peer.iceBuffer.length >= ICE_CANDIDATE_BUFFER_MAX) peer.iceBuffer.shift();
                peer.iceBuffer.push(candidate);
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

    private async fetchIceServers(token: string, signal: AbortSignal): Promise<boolean> {
        const fetchController = new AbortController();
        const timeoutTimer = setTimeout(() => fetchController.abort(), TURN_FETCH_TIMEOUT_MS);
        const onExternalAbort = () => fetchController.abort();
        signal.addEventListener('abort', onExternalAbort);
        try {
            const apiUrl = buildApiUrl(this.serverHost, `/api/turn-credentials?token=${encodeURIComponent(token)}`);

            const res = await fetch(apiUrl, { signal: fetchController.signal });

            if (signal.aborted) return false;

            if (res.ok) {
                const data = await res.json();
                const turnsOnly = this.turnsOnly;

                const servers: RTCIceServer[] = [];
                if (data.uris) {
                    let uris = data.uris;
                    if (turnsOnly) uris = uris.filter((u: string) => u.startsWith('turns:'));
                    if (uris.length > 0) servers.push({ urls: uris, username: data.username, credential: data.password });
                }

                const config: RTCConfiguration = {
                    iceServers: servers.length > 0 ? servers : DEFAULT_RTC_CONFIG.iceServers
                };
                if (turnsOnly) config.iceTransportPolicy = 'relay';
                this.rtcConfig = config;
                return true;
            }
        } catch (err) {
            if (!signal.aborted) this.logger?.log('error', 'WebRTC', `Error fetching ICE servers: ${formatError(err)}`);
        } finally {
            clearTimeout(timeoutTimer);
            signal.removeEventListener('abort', onExternalAbort);
        }
        return false;
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

    private async replaceVideoTrackOnAllPeers(newTrack: MediaStreamTrack | null): Promise<void> {
        await Promise.all(
            Array.from(this.peers.values()).map(async (peer) => {
                const sender = peer.pc.getSenders().find(s => s.track?.kind === 'video');
                if (sender) {
                    try { await sender.replaceTrack(newTrack); }
                    catch (err) { this.logger?.log('warning', 'WebRTC', `Failed to replace track on peer: ${formatError(err)}`); }
                }
            })
        );
    }

    private async swapLocalVideoTrack(nextTrack: MediaStreamTrack | null, previousTrack: MediaStreamTrack | null): Promise<void> {
        if (!this.localStream) {
            if (previousTrack && previousTrack !== nextTrack) previousTrack.stop();
            return;
        }
        await this.replaceVideoTrackOnAllPeers(nextTrack);
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
        if (previousTrack && previousTrack !== nextTrack) previousTrack.stop();
        this.notifyChange();
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
    }

    private notifyChange(): void { this.onChange?.(); }
}
