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
const DEVICE_CHANGE_SETTLE_DELAY_MS = 2000;

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

/** Internal media role for a video transceiver in independent-content mode. */
type VideoRole = 'camera' | 'content';

/**
 * Per-peer, per-role inbound liveness derived from `inbound-rtp.bytesReceived`.
 * `true` for a role when its inbound bytes advanced since the previous sample
 * (i.e. that role's video is flowing). Drives the per-role stall diagnostics
 * (`cameraReceiving` / `contentReceiving` on the public remote participant):
 * a consumer reads "content stalled" as `content.active && !contentReceiving`.
 *
 * `camera` covers the single legacy video track too (flag off / legacy peers
 * route their one inbound video to the `camera` role), so existing camera-only
 * consumers see `cameraReceiving` track that single video and `contentReceiving`
 * stays `false` — byte-identical observable behavior aside from the additive
 * field. Audio liveness is intentionally NOT split here (see
 * {@link getInboundFlowingCids}).
 */
export interface RoleLiveness {
    camera: boolean;
    content: boolean;
}

export interface InboundLivenessSample {
    flowingCids: string[];
    roleLiveness: Map<string, RoleLiveness>;
}

interface PeerState {
    pc: RTCPeerConnection;
    remoteStream: MediaStream | null;
    /**
     * Independent-content mode only: per-role transceivers, bound once by
     * object identity / mid (offer owner pre-creates; answerer maps in m-line
     * order). Empty for legacy peers (the legacy single-video path is used).
     */
    mediaRoles: { audio?: RTCRtpTransceiver; camera?: RTCRtpTransceiver; content?: RTCRtpTransceiver };
    /** Whether THIS peer is routed via the independent-content path (per-peer gate). */
    supportsIndependentContentVideo: boolean;
    /**
     * Independent mode: a local content (screen share) track is waiting to be
     * attached to this peer's content sender once its content transceiver binds.
     * True after `startScreenShare` for a capable peer that is not yet bound.
     */
    pendingContentAttach: boolean;
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

interface PreferredAudioInputSelection {
    device: MediaDeviceInfo | null;
    currentMatchesPreferredRoute: boolean;
    basis: 'default-input' | 'default-output' | 'already-matched' | 'none' | 'no-devices';
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
    /** When `false`, no video media is negotiated or received. */
    videoMediaEnabled?: boolean;
    /**
     * When `true`, this build can negotiate a dedicated content (screen share)
     * video stream independent of the camera, per-peer, for peers that also
     * advertise the capability. When `false` (default), every peer uses the
     * legacy single-video screen-share path and behavior is identical to today.
     */
    enableIndependentContentVideo?: boolean;
    /** Initial outgoing content revision restored from a recovered room snapshot. */
    initialContentRevision?: number;
    /** Defer initial offer timeout/ICE restart while awaiting the first answer. */
    deferInitialAnswer?: boolean;
}

/**
 * Conservative content (screen share) sender encoding profile so a typical
 * display track fits the negotiated envelope and `replaceTrack` stays on the
 * no-renegotiation path. Tunable per platform; legibility over motion.
 */
const CONTENT_MAX_FRAMERATE = 5;
const CONTENT_MAX_BITRATE = 1_500_000;
const CONTENT_MAX_WIDTH = 1920;
const CONTENT_MAX_HEIGHT = 1080;

export class MediaEngine {
    localStream: MediaStream | null = null;
    remoteStreams = new Map<string, MediaStream>();
    isScreenSharing = false;
    canScreenShare = false;
    facingMode: 'user' | 'environment' = 'user';
    hasMultipleCameras = false;
    readonly videoCaptureSupported: boolean;
    readonly videoMediaEnabled: boolean;
    readonly enableIndependentContentVideo: boolean;
    // Independent-content mode: remote tracks split by role. Empty for legacy
    // peers (those keep using `remoteStreams` / `remoteStream`). Exposed via
    // getRemoteCameraStream / getRemoteContentStream.
    remoteCameraStreams = new Map<string, MediaStream>();
    remoteContentStreams = new Map<string, MediaStream>();
    iceConnectionState: RTCIceConnectionState = 'new';
    connectionState: RTCPeerConnectionState = 'new';
    signalingState: RTCSignalingState = 'stable';
    connectionStatus: ConnectionStatus = 'connected';

    private peers = new Map<string, PeerState>();
    private readonly initialVideoEnabled: boolean;
    // Per-remote-CID tally of cumulative `inbound-rtp.bytesReceived`. Sampled
    // by `sampleInboundLiveness()`; a CID is "flowing" when its
    // current sample exceeds the previous one. Drives #3's `media_liveness`
    // emission (see SerenadaSession.startMediaLivenessTimer).
    private lastInboundBytesByCid = new Map<string, number>();
    // Per-remote-CID, per-role tally of cumulative inbound video
    // `bytesReceived`, split by the BOUND transceiver role (camera vs content)
    // so a stalled CONTENT stream is distinguishable from a healthy camera on
    // the same peer. Sampled by `sampleInboundLiveness()`; a role
    // is "receiving" when its current sample exceeds the previous one. The
    // derived booleans are cached in `roleLivenessByCid` for synchronous reads
    // from the public participant state. Separate from `lastInboundBytesByCid`
    // (which stays an all-RTP audio-inclusive sum for the server `media_liveness`
    // eviction-deferral signal, deliberately unchanged).
    private lastInboundRoleBytesByCid = new Map<string, { camera: number; content: number }>();
    private roleLivenessByCid = new Map<string, RoleLiveness>();
    private rtcConfig: RTCConfiguration = DEFAULT_RTC_CONFIG;
    private screenShareTrack: MediaStreamTrack | null = null;
    private screenShareRestoreVideoEnabled: boolean | null = null;
    // Independent-content mode local content (screen share) track. Also acts as
    // the "pending" track attached to capable peers as their content
    // transceiver binds. NOT part of `localStream` (which stays audio+camera).
    private localContentTrack: MediaStreamTrack | null = null;
    // Stable MediaStream wrapper around `localContentTrack`, built once when the
    // content track is set and nulled on release, so `getLocalContentStream`
    // returns a stable identity while sharing (instead of churning a new
    // MediaStream on every call).
    private localContentStream: MediaStream | null = null;
    // Idempotency latch for the shared stop path (API / rollback / onended).
    private screenShareStopInFlight = false;
    private requestingMedia = false;
    private destroyed = false;
    private cameraRecoveryInFlight = false;
    private audioRecoveryInFlight = false;
    private localMediaStartPromise: Promise<MediaStream | null> | null = null;
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
    private initialAnswerReceivedCids = new Set<string>();
    private onlineHandler: (() => void) | null = null;
    private networkChangeHandler: (() => void) | null = null;
    private deviceChangeHandler: (() => void) | null = null;
    private deviceChangeSettleTimer: number | null = null;
    private turnsOnly: boolean;
    private deferInitialAnswer: boolean;
    private logger?: SerenadaLogger;
    private offerSequence = 0;
    // Per-session monotonic generation marker for outgoing `content_state`.
    // Incremented on every send (start/stop) so receivers can order
    // presentation-state changes within this (cid, sid). Starts at 0; the first
    // send carries `revision: 1`. A recovered session can seed this from its
    // persisted local participant content revision before sending again.
    private contentRevision = 0;

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
        this.deferInitialAnswer = config.deferInitialAnswer === true;
        this.logger = config.logger;
        this.facingMode = config.initialFacingMode ?? 'user';
        this.initialVideoEnabled = config.initialVideoEnabled !== false;
        this.videoMediaEnabled = config.videoMediaEnabled !== false;
        this.enableIndependentContentVideo = this.videoMediaEnabled && config.enableIndependentContentVideo === true;
        this.videoCaptureSupported = this.videoMediaEnabled && config.videoCaptureSupported !== false;
        this.canScreenShare = this.videoMediaEnabled && !!navigator.mediaDevices?.getDisplayMedia;
        this.seedContentRevision(config.initialContentRevision);
        this.sendSignalingMessage = sendMessage;
        this.setupEventListeners();
    }

    setOnChange(cb: () => void): void { this.onChange = cb; }

    /**
     * Preserve outgoing `content_state` ordering after session recovery. The
     * server persists the last local content revision in `joined`/`room_state`;
     * when this tab resumes with the same CID/SID, the next send must advance
     * beyond that snapshot instead of restarting from 1.
     */
    seedContentRevision(revision: number | undefined): void {
        if (revision === undefined || !Number.isSafeInteger(revision) || revision < 0) {
            return;
        }
        this.contentRevision = Math.max(this.contentRevision, revision);
    }

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

    /** Details of the last failed local-media acquisition, if any. */
    get lastLocalMediaError(): { name: string; message: string } | null {
        return this._lastLocalMediaError;
    }
    private _lastLocalMediaError: { name: string; message: string } | null = null;

    async startLocalMedia(): Promise<MediaStream | null> {
        if (this.localStream) return this.localStream;
        if (this.localMediaStartPromise) return this.localMediaStartPromise;
        const startPromise = this.startLocalMediaInternal();
        this.localMediaStartPromise = startPromise;
        try {
            return await startPromise;
        } finally {
            if (this.localMediaStartPromise === startPromise) {
                this.localMediaStartPromise = null;
            }
        }
    }

    private async startLocalMediaInternal(): Promise<MediaStream | null> {
        const requestId = this.mediaRequestId + 1;
        this.mediaRequestId = requestId;

        this.requestingMedia = true;
        this._lastLocalMediaError = null;
        try {
            if (!navigator.mediaDevices?.getUserMedia) {
                this.requestingMedia = false;
                this._lastLocalMediaError = { name: 'NotSupportedError', message: 'getUserMedia is not supported' };
                return null;
            }
            const initialDevices = await this.enumerateMediaDevices();
            const preferredInput = this.selectPreferredAudioInput(initialDevices, null);
            const preferredDeviceId = preferredInput.device?.deviceId;
            let stream: MediaStream;
            if (!this.videoCaptureSupported || !this.initialVideoEnabled) {
                stream = await this.acquireInitialMedia(false, preferredDeviceId);
            } else {
                try {
                    stream = await this.acquireInitialMedia({ facingMode: this.facingMode }, preferredDeviceId);
                } catch {
                    stream = await this.acquireInitialMedia(true, preferredDeviceId);
                }
            }

            if (this.destroyed || this.mediaRequestId !== requestId) {
                stream.getTracks().forEach(t => t.stop());
                return null;
            }

            this.applySpeechTrackHints(stream);
            this.localStream = stream;
            const postStartDevices = await this.enumerateMediaDevices();
            await this.detectCameras(postStartDevices);
            this.requestingMedia = false;
            if (this.roomState && this.clientId) {
                await this.refreshLocalAudioTrack('initial-route-check', postStartDevices);
            }
            const activeStream = this.localStream;
            if (!activeStream) return null;

            for (const [remoteCid, peer] of this.peers) {
                if (peer.supportsIndependentContentVideo) {
                    this.attachLocalTracksToIndependentPeer(remoteCid, peer);
                } else {
                    await this.attachLocalTracksToPeer(remoteCid, peer, activeStream);
                }
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
            return activeStream;
        } catch (err) {
            this.logger?.log('error', 'WebRTC', `Error accessing media: ${formatError(err)}`);
            this._lastLocalMediaError = {
                name: err instanceof DOMException ? err.name : 'Error',
                message: formatError(err),
            };
            this.requestingMedia = false;
            return null;
        }
    }

    stopLocalMedia(): void {
        this.mediaRequestId += 1;
        this.localMediaStartPromise = null;
        if (this.screenShareTrack) {
            this.screenShareTrack.onended = null;
            this.screenShareTrack = null;
        }
        if (this.localContentTrack) {
            try { this.localContentTrack.stop(); } catch { /* ignore */ }
            this.localContentTrack = null;
        }
        this.localContentStream = null;
        this.screenShareStopInFlight = false;
        this.screenShareRestoreVideoEnabled = null;
        if (this.localStream) {
            this.localStream.getTracks().forEach(t => t.stop());
            this.localStream = null;
        }
        this.isScreenSharing = false;
        this.requestingMedia = false;
        this.notifyChange();
    }

    /**
     * True only while a LEGACY single-video screen share is active: the local
     * flag is off, so the camera track has been repurposed AS the display track
     * on the single video sender. This is the precise predicate for "the camera
     * is NOT its own track during this share", and is the inverse of an
     * independent share (flag on) where the display rides a SEPARATE content
     * track and the camera track is untouched.
     *
     * Use this (NOT `isScreenSharing`) to decide whether legacy camera-control
     * suppressions apply. In legacy mode the camera track IS the display track,
     * so camera ops are suppressed while sharing (touching the single video
     * sender would clobber the share / restore camera mid-share). In independent
     * mode this is always false, so camera ops (enable/disable, flip,
     * release/reacquire, stall recovery) keep working normally during an active
     * content share — that simultaneity is the whole point of independent mode.
     */
    private get isLegacyScreenSharing(): boolean {
        return this.isScreenSharing && !this.enableIndependentContentVideo;
    }

    async releaseVideoTrack(): Promise<void> {
        // Suppress only for a LEGACY share (the camera track IS the display
        // track). In independent mode the camera track is separate, so a camera
        // toggle-off MUST release it (otherwise camera keeps sending — privacy).
        if (this.isLegacyScreenSharing) return;
        const currentTrack = this.localStream?.getVideoTracks()[0] ?? null;
        if (!currentTrack) return;
        await this.swapLocalVideoTrack(null, currentTrack);
    }

    async reacquireVideoTrack(): Promise<void> {
        if (!this.videoCaptureSupported) return;
        // Independent share: camera is separate, so re-enabling the camera must
        // work while sharing. Only the legacy single-video share blocks it.
        if (this.isLegacyScreenSharing) return;
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

    /**
     * Most recent outgoing `content_state` revision for this session. `0` before
     * any content_state has been sent. Read by the session to populate the local
     * participant's published `content.revision`.
     */
    get lastContentRevision(): number {
        return this.contentRevision;
    }

    /**
     * Send a `content_state` with the next per-session revision. Centralizes the
     * monotonic increment so every send (start active:true, stop active:false)
     * carries a strictly greater `revision` than the one it supersedes.
     */
    private sendContentState(payload: { active: boolean; contentType?: string }): void {
        this.contentRevision += 1;
        this.sendSignalingMessage('content_state', { ...payload, revision: this.contentRevision });
    }

    async startScreenShare(): Promise<void> {
        if (this.isScreenSharing || !this.canScreenShare) return;
        if (!this.videoMediaEnabled) return;
        if (this.enableIndependentContentVideo) {
            await this.startScreenShareIndependent();
            return;
        }
        await this.startScreenShareLegacy();
    }

    async stopScreenShare(): Promise<void> {
        if (this.enableIndependentContentVideo) {
            await this.stopScreenShareIndependent();
            return;
        }
        await this.stopScreenShareLegacy();
    }

    // --- Legacy single-video screen share (flag OFF): byte-identical to today ---

    private async startScreenShareLegacy(): Promise<void> {
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
            this.sendContentState({ active: true, contentType: 'screenShare' });
            this.notifyChange();
        } catch (err) {
            this.screenShareRestoreVideoEnabled = null;
            this.logger?.log('error', 'ScreenShare', `Failed to start screen share: ${formatError(err)}`);
        }
    }

    private async stopScreenShareLegacy(): Promise<void> {
        if (!this.isScreenSharing) return;
        if (!this.localStream) {
            this.isScreenSharing = false;
            this.screenShareRestoreVideoEnabled = null;
            this.sendContentState({ active: false });
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
            this.sendContentState({ active: false });
            this.notifyChange();
        }
    }

    // --- Independent content screen share (flag ON) ---

    /**
     * Global start sequence (design "Starting Screen Share"): acquire the
     * display track, record it as the pending local content track, subscribe to
     * its `onended`, attach per peer (capable -> content sender or pending;
     * legacy -> swap single sender), then broadcast `content_state {active:true}`
     * only once the share has a viable attach path. Roll back silently if zero
     * peers attached and none is pending.
     */
    private async startScreenShareIndependent(): Promise<void> {
        let displayTrack: MediaStreamTrack | null = null;
        try {
            const displayStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
            displayTrack = displayStream.getVideoTracks()[0] ?? null;
            if (!displayTrack) {
                displayStream.getTracks().forEach(track => track.stop());
                throw new Error('No display track returned');
            }
        } catch (err) {
            // Permission/capture denied: whole-operation failure, untouched camera.
            this.logger?.log('error', 'ScreenShare', `Failed to start screen share: ${formatError(err)}`);
            return;
        }

        displayTrack.enabled = true;
        if ('contentHint' in displayTrack) {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any -- contentHint is a valid but untyped browser API
            try { (displayTrack as any).contentHint = 'detail'; } catch { /* ignore */ }
        }
        // Conservative capture envelope so the display track fits the negotiated
        // content profile and `replaceTrack` stays renegotiation-free.
        if (typeof displayTrack.applyConstraints === 'function') {
            void displayTrack.applyConstraints({
                width: { max: CONTENT_MAX_WIDTH },
                height: { max: CONTENT_MAX_HEIGHT },
                frameRate: { max: CONTENT_MAX_FRAMERATE },
            }).catch(() => { /* best-effort */ });
        }

        this.localContentTrack = displayTrack;
        this.localContentStream = new MediaStream([displayTrack]);
        this.screenShareTrack = displayTrack;
        this.screenShareStopInFlight = false;
        displayTrack.onended = () => { void this.stopScreenShare(); };

        this.isScreenSharing = true;

        // Attach per peer.
        let attachedCount = 0;
        let pendingCount = 0;
        await Promise.all(Array.from(this.peers.entries()).map(async ([remoteCid, peer]) => {
            if (peer.supportsIndependentContentVideo) {
                if (peer.mediaRoles.content) {
                    const ok = await this.replaceRoleTrack(peer, 'content', displayTrack);
                    if (ok) attachedCount += 1; else pendingCount += 1; // renegotiation fallback counts as in-flight
                } else {
                    peer.pendingContentAttach = true;
                    pendingCount += 1;
                }
            } else {
                // Legacy peer: swap the single video sender's track to the display track.
                if (await this.swapLegacyPeerVideoTrack(remoteCid, peer, displayTrack)) {
                    attachedCount += 1;
                }
            }
        }));

        // Roll back only if the share can never flow anywhere. Because the
        // active:true signal is delayed until after this check, peers do not see
        // a false "is sharing" flicker on full attach failure.
        if (attachedCount === 0 && pendingCount === 0 && this.peers.size > 0) {
            // Restore any legacy senders we touched (none attached, but be safe).
            this.releaseLocalContentTrack();
            this.isScreenSharing = false;
            this.notifyChange();
            return;
        }

        this.sendContentState({ active: true, contentType: 'screenShare' });

        // No-eligible-peer / no-peer case: start-and-wait (capture live, content
        // pending). The revision bump is owned by `sendContentState`.
        this.notifyChange();
    }

    /**
     * Shared idempotent stop path for the independent mode (API, rollback,
     * `onended`). A single latch ensures one capture stop, one revision bump,
     * one resource release; camera tracks to capable peers are never touched.
     */
    private async stopScreenShareIndependent(): Promise<void> {
        if (!this.isScreenSharing && !this.localContentTrack) return;
        if (this.screenShareStopInFlight) return;
        this.screenShareStopInFlight = true;

        try {
            // Detach content from capable peers; restore camera on legacy peers.
            await Promise.all(Array.from(this.peers.values()).map(async (peer) => {
                if (peer.supportsIndependentContentVideo) {
                    peer.pendingContentAttach = false;
                    if (peer.mediaRoles.content) {
                        await this.replaceRoleTrack(peer, 'content', null);
                    }
                } else {
                    await this.restoreLegacyPeerCameraTrack(peer);
                }
            }));

            this.isScreenSharing = false;
            this.sendContentState({ active: false });
            this.releaseLocalContentTrack();
            this.notifyChange();
        } finally {
            this.screenShareStopInFlight = false;
        }
    }

    /** Release the local content (display) capture resources. */
    private releaseLocalContentTrack(): void {
        if (this.screenShareTrack) {
            this.screenShareTrack.onended = null;
        }
        if (this.localContentTrack) {
            try { this.localContentTrack.stop(); } catch { /* ignore */ }
        }
        this.localContentTrack = null;
        this.localContentStream = null;
        this.screenShareTrack = null;
    }

    /**
     * Legacy peer (in independent mode): swap the single video sender to a track.
     *
     * Intentionally does NOT participate in `screenShareRestoreVideoEnabled`:
     * the independent flow has no local camera mute toggle to restore on the
     * legacy peer (the display track simply takes over the single video sender),
     * so do not "fix" this into the camera-restore behavior of the non-independent
     * legacy path — that would diverge the two flows.
     */
    private async swapLegacyPeerVideoTrack(remoteCid: string, peer: PeerState, track: MediaStreamTrack): Promise<boolean> {
        const transceiver = this.findTransceiver(peer.pc, 'video');
        if (!transceiver) {
            if (this.localStream) {
                try {
                    const sender = peer.pc.addTrack(track, this.localStream);
                    // The legacy single sender is now carrying the content (display)
                    // track during the share → give it the conservative content
                    // encoding profile (FIX 2), not the camera profile.
                    this.applyLegacyContentSenderEncoding(sender);
                    this.scheduleLocalTrackNegotiation(remoteCid, peer);
                    return true;
                } catch (err) {
                    this.logger?.log('warning', 'WebRTC', `Failed to add legacy video track: ${formatError(err)}`);
                }
            }
            return false;
        }
        try {
            await transceiver.sender.replaceTrack(track);
            // The legacy single sender is now carrying the content (display) track
            // during the share. In independent mode `isLegacyScreenSharing` is
            // false, so without this it would keep the CAMERA sender encoding
            // params. Apply the same conservative screen-content profile used on
            // capable peers' content transceiver (FIX 2).
            this.applyLegacyContentSenderEncoding(transceiver.sender);
            // A direction flip is a structural change → schedule renegotiation.
            // (On the independent path `videoMediaEnabled` is always true, so the
            // helper's gate is a no-op here.)
            const needsNegotiation = transceiver.direction !== 'sendrecv' && transceiver.direction !== 'stopped';
            this.ensureRoleSendCapable(transceiver);
            if (needsNegotiation) {
                this.scheduleLocalTrackNegotiation(remoteCid, peer);
            }
            return true;
        } catch (err) {
            this.logger?.log('warning', 'WebRTC', `Failed to swap legacy video track: ${formatError(err)}`);
            return false;
        }
    }

    /**
     * FIX 2: a legacy peer's single video sender carrying the content (display)
     * track during an independent share must use the screen-content encoding
     * profile, not the camera profile. Mirrors {@link applyContentSenderEncoding}
     * (the capable-peer content transceiver) but keyed on the sender, and gated
     * on an active independent share so it is inert when the flag is off (the
     * legacy single sender is never swapped to content unless we are sharing in
     * independent mode). Best-effort; failures leave the previous params.
     */
    private applyLegacyContentSenderEncoding(sender: RTCRtpSender): void {
        if (!this.enableIndependentContentVideo || !this.isScreenSharing) return;
        this.setSenderVideoEncoding(sender, { maxBitrate: CONTENT_MAX_BITRATE, maxFramerate: CONTENT_MAX_FRAMERATE });
    }

    /**
     * FIX 2 restore leg: when the legacy single sender goes back to carrying the
     * camera track after a share, drop the content encoding overrides so the
     * camera uses its default (unbounded) profile again. Clearing the two
     * content-specific fields restores the pre-share camera params.
     */
    private restoreLegacySenderCameraEncoding(sender: RTCRtpSender): void {
        this.setSenderVideoEncoding(sender, { maxBitrate: undefined, maxFramerate: undefined });
    }

    /** Apply a partial encoding override to a video sender's first encoding. Best-effort. */
    private setSenderVideoEncoding(
        sender: RTCRtpSender,
        overrides: { maxBitrate?: number; maxFramerate?: number },
    ): void {
        if (!sender?.getParameters || !sender?.setParameters) return;
        try {
            const params = sender.getParameters();
            if (!params.encodings || params.encodings.length === 0) {
                params.encodings = [{}];
            }
            params.encodings = params.encodings.map((encoding, index) => (
                index === 0 ? { ...encoding, ...overrides } : encoding
            ));
            void sender.setParameters(params).catch(err =>
                this.logger?.log('debug', 'WebRTC', `Failed to apply legacy content sender encoding: ${formatError(err)}`));
        } catch (err) {
            this.logger?.log('debug', 'WebRTC', `Failed to read legacy sender params: ${formatError(err)}`);
        }
    }

    /**
     * Legacy peer created mid-share (in independent mode): route the active
     * screen share to its single video sender. `startScreenShareIndependent` only
     * swaps the peers that existed when the share started, so a late legacy joiner
     * (from a local offer or an inbound offer) would otherwise negotiate camera
     * while `content_state` advertises an active share. Idempotent and a no-op
     * unless we are currently sharing with a live content track.
     */
    private attachActiveShareToLegacyPeer(remoteCid: string, peer: PeerState): void {
        // Flag-off path stays byte-identical: localContentTrack is only ever set
        // by the independent share path, so this also gates flag-off (defensive).
        if (!this.enableIndependentContentVideo || !this.isScreenSharing) return;
        const contentTrack = this.localContentTrack;
        if (!contentTrack || contentTrack.readyState !== 'live') return;
        void this.swapLegacyPeerVideoTrack(remoteCid, peer, contentTrack);
    }

    /** Legacy peer (in independent mode): restore the camera track after a share. */
    private async restoreLegacyPeerCameraTrack(peer: PeerState): Promise<void> {
        const cameraTrack = this.localCameraTrack;
        const transceiver = this.findTransceiver(peer.pc, 'video');
        if (!transceiver) return;
        try {
            await transceiver.sender.replaceTrack(cameraTrack && cameraTrack.readyState === 'live' ? cameraTrack : null);
            // The sender carried the content profile during the share; the camera
            // is back now, so drop the content encoding overrides (FIX 2).
            this.restoreLegacySenderCameraEncoding(transceiver.sender);
        } catch (err) {
            this.logger?.log('warning', 'WebRTC', `Failed to restore legacy camera track: ${formatError(err)}`);
        }
    }

    async flipCamera(): Promise<void> {
        // Independent share: the camera is a separate track, so flipping it
        // during a share is valid and leaves the content track untouched. Only
        // the legacy single-video share (camera == display track) blocks flip.
        if (this.isLegacyScreenSharing) return;
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
        this.remoteCameraStreams = new Map();
        this.remoteContentStreams = new Map();
        this.lastInboundBytesByCid.clear();
        this.lastInboundRoleBytesByCid.clear();
        this.roleLivenessByCid.clear();
        this.mediaRestartHandledAtByCid.clear();
        this.initialAnswerReceivedCids.clear();
        if (this.retryingTimer) { window.clearTimeout(this.retryingTimer); this.retryingTimer = null; }
        this.iceConnectionState = 'closed';
        this.connectionState = 'closed';
        this.signalingState = 'closed';
        this.connectionStatus = 'connected';
        this.notifyChange();
    }

    /**
     * Sample inbound RTP once per peer and refresh both the total-media liveness
     * used by `media_liveness{cids}` and the per-role video liveness used by
     * camera/content stall diagnostics.
     *
     * Conservative on first sample for a peer (no baseline -> not flowing).
     * Cleans up tracking for peers that have left.
     */
    async sampleInboundLiveness(): Promise<InboundLivenessSample> {
        const flowing: string[] = [];
        const seen = new Set<string>();
        for (const [cid, peer] of this.peers) {
            seen.add(cid);
            let bytes = 0;
            let cameraBytes = 0;
            let contentBytes = 0;
            const contentTrackId = this.roleReceiverTrackId(peer, 'content');
            const contentMid = this.roleReceiverMid(peer, 'content');
            try {
                const report = await peer.pc.getStats();
                report.forEach((stat) => {
                    if (stat.type !== 'inbound-rtp') return;
                    const value = getStatNumber(stat, 'bytesReceived');
                    bytes += value;
                    const kind = getStatString(stat, 'kind') ?? getStatString(stat, 'mediaType');
                    if (kind !== 'video') return;
                    const trackId = getStatString(stat, 'trackIdentifier');
                    const mid = getStatString(stat, 'mid');
                    const matchesContentTrack = contentTrackId !== null && trackId === contentTrackId;
                    const matchesContentMid = trackId === null && contentMid !== null && mid === contentMid;
                    if (matchesContentTrack || matchesContentMid) {
                        contentBytes += value;
                    } else {
                        // Camera role, legacy single video, or any video stat we
                        // cannot positively attribute to content -> count as camera.
                        cameraBytes += value;
                    }
                });
            } catch {
                continue;
            }
            const previous = this.lastInboundBytesByCid.get(cid);
            if (previous !== undefined && bytes > previous) {
                flowing.push(cid);
            }
            this.lastInboundBytesByCid.set(cid, bytes);
            const previousRole = this.lastInboundRoleBytesByCid.get(cid);
            this.roleLivenessByCid.set(cid, {
                camera: previousRole !== undefined && cameraBytes > previousRole.camera,
                content: previousRole !== undefined && contentBytes > previousRole.content,
            });
            this.lastInboundRoleBytesByCid.set(cid, { camera: cameraBytes, content: contentBytes });
        }
        for (const cid of [...this.lastInboundBytesByCid.keys()]) {
            if (!seen.has(cid)) this.lastInboundBytesByCid.delete(cid);
        }
        for (const cid of [...this.lastInboundRoleBytesByCid.keys()]) {
            if (!seen.has(cid)) this.lastInboundRoleBytesByCid.delete(cid);
        }
        for (const cid of [...this.roleLivenessByCid.keys()]) {
            if (!seen.has(cid)) this.roleLivenessByCid.delete(cid);
        }
        return { flowingCids: flowing, roleLiveness: new Map(this.roleLivenessByCid) };
    }

    /**
     * Sample inbound RTP `bytesReceived` per remote peer and return the CIDs
     * whose totals advanced since the previous sample. Kept for focused callers;
     * the session uses `sampleInboundLiveness()` to avoid duplicate `getStats()`.
     */
    async getInboundFlowingCids(): Promise<string[]> {
        const { flowingCids } = await this.sampleInboundLiveness();
        return flowingCids;
    }

    /**
     * Sample inbound video `bytesReceived` per remote peer, split by the bound
     * transceiver role. Kept for focused callers; the session uses
     * `sampleInboundLiveness()` to avoid duplicate `getStats()`.
     */
    async sampleInboundRoleLiveness(): Promise<Map<string, RoleLiveness>> {
        const { roleLiveness } = await this.sampleInboundLiveness();
        return roleLiveness;
    }

    /**
     * Latest cached per-role inbound liveness for a peer (camera/content
     * receiving), or both `false` when no sample has been taken yet. Synchronous
     * so the session can read it while assembling participant state.
     */
    getRoleLiveness(cid: string): RoleLiveness {
        return this.roleLivenessByCid.get(cid) ?? { camera: false, content: false };
    }

    /** Receiver track id bound to a peer's role transceiver, if any. */
    private roleReceiverTrackId(peer: PeerState, role: VideoRole): string | null {
        const transceiver = peer.mediaRoles[role];
        const id = transceiver?.receiver?.track?.id;
        return typeof id === 'string' && id !== '' ? id : null;
    }

    /** Receiver m-line id bound to a peer's role transceiver, if any. */
    private roleReceiverMid(peer: PeerState, role: VideoRole): string | null {
        const mid = peer.mediaRoles[role]?.mid;
        return typeof mid === 'string' && mid !== '' ? mid : null;
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
            this.initialAnswerReceivedCids.clear();
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
        for (const cid of Array.from(this.initialAnswerReceivedCids)) {
            if (!remoteCids.has(cid)) this.initialAnswerReceivedCids.delete(cid);
        }

        for (const peer of remotePeers) {
            const previousStatus = this.participantConnectionStatus.get(peer.cid);
            const nextStatus = peer.connectionStatus === 'suspended' ? 'suspended' : 'active';
            this.participantConnectionStatus.set(peer.cid, nextStatus);
            const becameActive = previousStatus === 'suspended' && nextStatus === 'active';
            if (!this.peers.has(peer.cid)) {
                this.getOrCreatePeer(peer.cid);
            } else if (this.reconcilePeerCapability(peer.cid)) {
                // Capability flipped (e.g. caps arrived after the peer was created
                // legacy from an early offer / peer_joined). The peer was recreated
                // with the correct camera/content m-line layout; the deterministic
                // offer owner re-offers from inside the recreate, so skip the
                // generic offer block below for this peer this pass.
                continue;
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
        const independentCapable = this.isPeerIndependentCapable(remoteCid);
        const peerState: PeerState = {
            pc, remoteStream: null,
            mediaRoles: {},
            supportsIndependentContentVideo: independentCapable,
            pendingContentAttach: false,
            iceBuffer: [],
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

        if (independentCapable) {
            this.setupIndependentPeerTracks(remoteCid, peerState);
        } else {
            if (this.localStream) {
                this.localStream.getTracks().forEach(track => pc.addTrack(track, this.localStream!));
                void this.applyAudioSenderParameters(pc);
            }
            this.ensureMediaTransceivers(pc);
            // A legacy peer created while a screen share is already active must
            // receive the share, not the camera. `startScreenShareIndependent`
            // already ran for the peers that existed when sharing began, so it
            // never swaps THIS late joiner's single video sender. Route content
            // to it via the same legacy mechanism (swap the single video sender's
            // track to the display track). Covers legacy peers created from both a
            // local offer and an inbound offer (both flow through getOrCreatePeer).
            // Idempotent: `replaceTrack` with the live content track is a no-op if
            // already attached; the subsequent offer carries the swapped track.
            this.attachActiveShareToLegacyPeer(remoteCid, peerState);
        }

        pc.ontrack = (event) => {
            if (event.track.kind === 'video' && !this.videoMediaEnabled) {
                this.logger?.log('info', 'WebRTC', `[${remoteCid}] Ignoring remote video track because video media is disabled`);
                return;
            }
            if (peerState.supportsIndependentContentVideo && event.track.kind === 'video') {
                this.handleIndependentRemoteVideoTrack(remoteCid, peerState, event);
                return;
            }
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
            // Independent-mode camera tracks are also surfaced via the legacy
            // `remoteStreams` accessor (getRemoteStream) for back-compat.
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
        if (this.remoteCameraStreams.has(remoteCid)) {
            const cameras = new Map(this.remoteCameraStreams);
            cameras.delete(remoteCid);
            this.remoteCameraStreams = cameras;
        }
        if (this.remoteContentStreams.has(remoteCid)) {
            const contents = new Map(this.remoteContentStreams);
            contents.delete(remoteCid);
            this.remoteContentStreams = contents;
        }
        this.lastInboundBytesByCid.delete(remoteCid);
        this.lastInboundRoleBytesByCid.delete(remoteCid);
        this.roleLivenessByCid.delete(remoteCid);
        if (clearMediaRestartCooldown) {
            this.mediaRestartHandledAtByCid.delete(remoteCid);
            this.initialAnswerReceivedCids.delete(remoteCid);
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
        if (typeof myId !== 'string' || myId.length === 0 || !this.isCurrentParticipant(remoteCid)) {
            return false;
        }
        if (this.deferInitialAnswer && this.roomState) {
            const participantCids = new Set(this.roomState.participants?.map(p => p.cid) ?? []);
            const candidateHostCid = this.roomState.hostCid;
            const hostCid = typeof candidateHostCid === 'string' && participantCids.has(candidateHostCid) ? candidateHostCid : null;
            if (participantCids.size <= 2 && hostCid) {
                return myId === hostCid;
            }
        }
        return myId < remoteCid;
    }

    private isParticipantActive(remoteCid: string): boolean {
        if (!this.roomState) return true;
        const participant = this.roomState.participants?.find(p => p.cid === remoteCid);
        return !!participant && participant.connectionStatus !== 'suspended';
    }

    /**
     * Per-peer independent-content capability gate. A peer is routed through the
     * independent camera+content path only when ALL hold: the local build flag
     * is on, BOTH ends' `videoMediaEnabled` are true, and the peer advertised
     * `independentContentVideo`. When the local flag is off this is always
     * false, so every peer uses the legacy single-video path (byte-identical to
     * today). Read from the authoritative room state so it tracks peer lifecycle.
     */
    private isPeerIndependentCapable(remoteCid: string): boolean {
        if (!this.enableIndependentContentVideo) return false;
        const participant = this.roomState?.participants?.find(p => p.cid === remoteCid);
        if (!participant) return false;
        if (participant.capabilities?.independentContentVideo !== true) return false;
        // Peer's media policy (default true when absent) and our own must allow video.
        if (participant.mediaPolicy?.videoMediaEnabled === false) return false;
        return this.videoMediaEnabled;
    }

    /** Resolve the persisted media role bound to a transceiver, if any. */
    private getRoleForTransceiver(peer: PeerState, transceiver: RTCRtpTransceiver): VideoRole | null {
        if (peer.mediaRoles.camera === transceiver) return 'camera';
        if (peer.mediaRoles.content === transceiver) return 'content';
        return null;
    }

    // --- Independent-content media path (capable peers only) ---

    /** Local camera video track in independent mode (lives in `localStream`). */
    private get localCameraTrack(): MediaStreamTrack | null {
        return this.localStream?.getVideoTracks()[0] ?? null;
    }

    /**
     * Set up media for an independent-capable peer. The offer OWNER pre-creates
     * the ordered transceivers (audio, then camera, then content; video both
     * `sendrecv` up front with no track until live). The ANSWERER pre-creates
     * NOTHING — it materializes and binds everything from the first applied
     * remote offer (see {@link applyRemoteOffer} → {@link assignRemoteVideoRoles}).
     */
    private setupIndependentPeerTracks(remoteCid: string, peer: PeerState): void {
        if (!this.shouldIOffer(remoteCid)) {
            return;
        }
        const pc = peer.pc;
        // Owner: add the live audio track if present, else reserve a transceiver.
        const audioTrack = this.localStream?.getAudioTracks()[0] ?? null;
        if (audioTrack) {
            if (!pc.getSenders().some(sender => sender.track?.kind === 'audio')) {
                peer.mediaRoles.audio = pc.addTransceiver(audioTrack, { direction: 'sendrecv' });
            }
            void this.applyAudioSenderParameters(pc);
        } else if (!this.findTransceiver(pc, 'audio') && !pc.getSenders().some(sender => sender.track?.kind === 'audio')) {
            peer.mediaRoles.audio = pc.addTransceiver('audio', { direction: 'recvonly' });
        }

        this.ensureOwnerVideoTransceivers(peer);
    }

    /**
     * Offer-owner only: pre-create the camera transceiver first then the content
     * transceiver second, both `sendrecv`, with no sender track yet (a null
     * sender track sends nothing). Idempotent — re-entry binds nothing new.
     */
    private ensureOwnerVideoTransceivers(peer: PeerState): void {
        if (!peer.mediaRoles.camera) {
            const cameraTrack = this.localCameraTrack;
            const transceiver = cameraTrack && cameraTrack.readyState === 'live'
                ? peer.pc.addTransceiver(cameraTrack, { direction: 'sendrecv' })
                : peer.pc.addTransceiver('video', { direction: 'sendrecv' });
            peer.mediaRoles.camera = transceiver;
        }
        if (!peer.mediaRoles.content) {
            peer.mediaRoles.content = peer.pc.addTransceiver('video', { direction: 'sendrecv' });
            void this.applyContentSenderEncoding(peer.mediaRoles.content);
            // If a screen share is already live, attach it now (pending model).
            if (this.localContentTrack) {
                peer.pendingContentAttach = true;
            }
        }
        this.attachPendingLocalTracks(peer);
    }

    /**
     * Answerer: bind video roles from the applied remote offer, once, by m-line
     * (mid) order — first video → camera, second video → content. Never
     * recomputes a role for an already-bound transceiver. Extra video m-lines
     * are left as-is (answered inactive). Then attaches any pending local tracks.
     */
    private assignRemoteVideoRoles(peer: PeerState): void {
        const videoTransceivers = peer.pc.getTransceivers()
            .filter(transceiver => this.isTransceiverKind(transceiver, 'video') && transceiver.currentDirection !== 'stopped')
            .sort((a, b) => this.compareMids(a.mid, b.mid));

        const alreadyBound = new Set<RTCRtpTransceiver>();
        if (peer.mediaRoles.camera) alreadyBound.add(peer.mediaRoles.camera);
        if (peer.mediaRoles.content) alreadyBound.add(peer.mediaRoles.content);

        for (const transceiver of videoTransceivers) {
            if (alreadyBound.has(transceiver)) continue;
            if (!peer.mediaRoles.camera) {
                peer.mediaRoles.camera = transceiver;
                alreadyBound.add(transceiver);
                this.ensureRoleSendCapable(transceiver);
                continue;
            }
            if (!peer.mediaRoles.content) {
                peer.mediaRoles.content = transceiver;
                alreadyBound.add(transceiver);
                this.ensureRoleSendCapable(transceiver);
                void this.applyContentSenderEncoding(transceiver);
                continue;
            }
            // Extra video m-line: never promoted to a role. Leave inactive.
            try {
                if (transceiver.direction !== 'inactive' && transceiver.direction !== 'stopped') {
                    transceiver.direction = 'inactive';
                }
            } catch { /* ignore */ }
        }
        this.attachPendingLocalTracks(peer);
    }

    /**
     * Reserve send capability on a bound role m-line so a later `replaceTrack`
     * stays on the no-renegotiation path (mirrors the owner's pre-negotiated
     * `sendrecv` transceivers). Skipped when video media is disabled.
     */
    private ensureRoleSendCapable(transceiver: RTCRtpTransceiver): void {
        if (!this.videoMediaEnabled) return;
        try {
            if (transceiver.direction !== 'sendrecv' && transceiver.direction !== 'stopped') {
                transceiver.direction = 'sendrecv';
            }
        } catch { /* ignore */ }
    }

    /** Compare two mids numerically when possible, else lexically; null last. */
    private compareMids(a: string | null, b: string | null): number {
        if (a === b) return 0;
        if (a === null) return 1;
        if (b === null) return -1;
        const na = Number(a);
        const nb = Number(b);
        if (Number.isFinite(na) && Number.isFinite(nb) && na !== nb) return na - nb;
        return a < b ? -1 : 1;
    }

    /**
     * Attach any pending local camera/content tracks to a capable peer's bound
     * role senders via `replaceTrack` (no renegotiation in the steady state).
     *
     * Idempotent and role-state driven, NOT just `pendingContentAttach`-driven:
     * whenever a content transceiver is bound and we are screen sharing, the
     * content track is (re)attached if the sender does not already carry it. This
     * is the single reliable bind/attach point for BOTH directions:
     *   - answerer whose capable peer was created mid-share (its
     *     `setupIndependentPeerTracks` early-returned, so `pendingContentAttach`
     *     was never set, but the content transceiver binds via the remote offer →
     *     {@link assignRemoteVideoRoles}); and
     *   - the `replaceTrack`-reject fallback retry: after the forced structural
     *     renegotiation re-binds the content m-line, the share re-attaches here
     *     instead of leaving the content sender empty.
     * Mirrors the camera path's `sender.track !== track` idempotence guard.
     */
    private attachPendingLocalTracks(peer: PeerState): void {
        const cameraTrack = this.localCameraTrack;
        if (peer.mediaRoles.camera && cameraTrack && cameraTrack.readyState === 'live' &&
            peer.mediaRoles.camera.sender.track !== cameraTrack) {
            void this.replaceRoleTrack(peer, 'camera', cameraTrack);
        }
        const contentTrack = this.localContentTrack;
        const wantsContent = peer.pendingContentAttach ||
            (this.isScreenSharing && contentTrack !== null && contentTrack.readyState === 'live');
        if (peer.mediaRoles.content && wantsContent && contentTrack &&
            peer.mediaRoles.content.sender.track !== contentTrack) {
            peer.pendingContentAttach = false;
            void this.replaceRoleTrack(peer, 'content', contentTrack);
        } else if (peer.mediaRoles.content && contentTrack &&
            peer.mediaRoles.content.sender.track === contentTrack) {
            // Already attached (e.g. re-bind after a successful replaceTrack):
            // clear the pending flag so it doesn't linger.
            peer.pendingContentAttach = false;
        }
    }

    /**
     * Replace the track on a bound role sender. On `replaceTrack` rejection
     * (e.g. an incompatible content envelope), fall back to renegotiation for
     * that role's m-line — treated as a structural change.
     */
    private async replaceRoleTrack(peer: PeerState, role: VideoRole, track: MediaStreamTrack | null): Promise<boolean> {
        const transceiver = peer.mediaRoles[role];
        if (!transceiver) return false;
        try {
            await transceiver.sender.replaceTrack(track);
            // Ensure the role m-line is send-capable when a track is attached.
            // For owner-created transceivers this is already `sendrecv`; for an
            // answerer's materialized `recvonly` role it flips it before the
            // answer is created so the SDP advertises the local send direction.
            if (track) {
                this.ensureRoleSendCapable(transceiver);
            }
            return true;
        } catch (err) {
            this.logger?.log('warning', 'WebRTC', `Failed to replaceTrack on ${role} sender, renegotiating: ${formatError(err)}`);
            // Structural fallback: ensure direction is send-capable and force a
            // renegotiation (a rejected replaceTrack is a structural change, so
            // bypass the steady-state hasUnnegotiatedLocalTracks guard).
            if (track) {
                this.ensureRoleSendCapable(transceiver);
            }
            // Durable retry: forcing renegotiation alone is not enough — when the
            // content m-line re-binds / negotiation completes, the content sender
            // would otherwise stay empty (receiver stuck active-but-loading). Mark
            // a pending content attach so `attachPendingLocalTracks` re-attaches
            // the content track post-negotiation. Idempotent via that path's guard.
            if (role === 'content' && track) {
                peer.pendingContentAttach = true;
            }
            const remoteCid = this.cidForPeer(peer);
            if (remoteCid) this.forceStructuralRenegotiation(remoteCid, peer);
            return false;
        }
    }

    /**
     * Force a structural renegotiation for a peer (used by the `replaceTrack`
     * rejection fallback). Unlike `scheduleLocalTrackNegotiation`, this does not
     * gate on the steady-state `hasUnnegotiatedLocalTracks` check.
     */
    private forceStructuralRenegotiation(remoteCid: string, peer: PeerState): void {
        if (!this.isSignalingConnected || peer.pc.signalingState !== 'stable') {
            peer.pendingLocalTrackNegotiation = true;
            return;
        }
        if (this.shouldIOffer(remoteCid)) {
            void this.createOfferTo(remoteCid);
        } else {
            this.requestPeerMediaRecovery(remoteCid, MEDIA_RESTART_REASON_LOCAL_TRACK_NEGOTIATION);
        }
    }

    /** Attach the live local audio track to a capable peer's audio transceiver. */
    private attachAudioTrackToIndependentPeer(peer: PeerState): void {
        const audioTrack = this.localStream?.getAudioTracks()[0] ?? null;
        if (!audioTrack || audioTrack.readyState !== 'live') return;
        const audioTransceiver = peer.mediaRoles.audio
            ?? this.findTransceiver(peer.pc, 'audio')
            ?? peer.pc.getTransceivers().find(t => t.sender.track?.kind === 'audio');
        if (audioTransceiver) {
            peer.mediaRoles.audio = audioTransceiver;
            if (audioTransceiver.sender.track !== audioTrack) {
                void audioTransceiver.sender.replaceTrack(audioTrack).catch(err =>
                    this.logger?.log('warning', 'WebRTC', `Failed to attach audio to capable peer: ${formatError(err)}`));
            }
            // Inlined (not `ensureRoleSendCapable`): that helper is video-gated
            // (`videoMediaEnabled`), but audio must stay send-capable even when
            // video media is disabled.
            try {
                if (audioTransceiver.direction !== 'sendrecv' && audioTransceiver.direction !== 'stopped') {
                    audioTransceiver.direction = 'sendrecv';
                }
            } catch { /* ignore */ }
        }
    }

    /**
     * Full local-track attach for an independent-capable peer after local media
     * starts: ensure owner video transceivers (offer owner), attach audio, and
     * attach camera/pending content via role senders. Renegotiation for any newly
     * created owner transceivers is driven by the generic shouldIOffer /
     * !remoteDescription block at the sole call site, so nothing is returned.
     */
    private attachLocalTracksToIndependentPeer(remoteCid: string, peer: PeerState): void {
        this.attachAudioTrackToIndependentPeer(peer);
        if (this.shouldIOffer(remoteCid)) {
            this.ensureOwnerVideoTransceivers(peer);
        }
        this.attachPendingLocalTracks(peer);
    }

    /**
     * Reverse-lookup the remote CID for a peer state. The O(N) scan over
     * `this.peers` is fine at maxParticipants = 2 (at most one other peer).
     */
    private cidForPeer(peer: PeerState): string | null {
        for (const [cid, candidate] of this.peers) {
            if (candidate === peer) return cid;
        }
        return null;
    }

    /** Apply the conservative content-sender encoding profile. Best-effort. */
    private async applyContentSenderEncoding(transceiver: RTCRtpTransceiver): Promise<void> {
        const sender = transceiver.sender;
        if (!sender?.getParameters || !sender?.setParameters) return;
        try {
            const params = sender.getParameters();
            if (!params.encodings || params.encodings.length === 0) {
                params.encodings = [{}];
            }
            params.encodings = params.encodings.map((encoding, index) => (
                index === 0
                    ? { ...encoding, maxBitrate: CONTENT_MAX_BITRATE, maxFramerate: CONTENT_MAX_FRAMERATE }
                    : encoding
            ));
            await sender.setParameters(params);
        } catch (err) {
            this.logger?.log('debug', 'WebRTC', `Failed to apply content sender encoding: ${formatError(err)}`);
        }
    }

    /**
     * Classify an incoming video track for an independent-capable peer by its
     * persisted (bound) transceiver role and route it to the per-role remote
     * stream maps. Camera also feeds the legacy `remoteStreams` accessor.
     */
    private handleIndependentRemoteVideoTrack(remoteCid: string, peer: PeerState, event: RTCTrackEvent): void {
        let role = this.getRoleForTransceiver(peer, event.transceiver);
        if (!role) {
            // Track arrived before binding; map roles now (covers the rare case
            // ontrack fires before assignRemoteVideoRoles runs).
            this.assignRemoteVideoRoles(peer);
            role = this.getRoleForTransceiver(peer, event.transceiver);
        }
        if (!role) {
            this.logger?.log('debug', 'WebRTC', `[${remoteCid}] Ignoring unbound remote video track`);
            return;
        }
        const stream = event.streams?.[0] ?? new MediaStream([event.track]);
        if (role === 'content') {
            this.remoteContentStreams = new Map(this.remoteContentStreams).set(remoteCid, stream);
        } else {
            // Camera-specific stream: exactly the camera (per-role accessor).
            this.remoteCameraStreams = new Map(this.remoteCameraStreams).set(remoteCid, stream);
            // Legacy compat (`remoteStream` / `remoteStreams`, which RemoteAudioSink
            // follows): MERGE the camera track into the existing audio+camera
            // aggregate rather than REPLACE it. For an independent peer, incoming
            // audio arrives via the legacy `ontrack` path (empty `event.streams`)
            // and builds `peer.remoteStream` holding the audio track; replacing it
            // with a video-only stream here would drop that audio. Build the
            // aggregate the same way the legacy path does (reuse existing, add the
            // track if absent), so audio is never dropped.
            const aggregate = peer.remoteStream ?? new MediaStream();
            if (!aggregate.getTracks().some(t => t.id === event.track.id)) {
                aggregate.addTrack(event.track);
            }
            peer.remoteStream = aggregate;
            this.remoteStreams = new Map(this.remoteStreams).set(remoteCid, aggregate);
        }
        this.notifyChange();
    }

    // --- Public role-specific stream accessors ---

    /** Independent-mode remote camera stream for a peer, or undefined. */
    getRemoteCameraStream(remoteCid: string): MediaStream | undefined {
        return this.remoteCameraStreams.get(remoteCid) ?? this.remoteStreams.get(remoteCid);
    }

    /** Independent-mode remote content (screen share) stream for a peer. */
    getRemoteContentStream(remoteCid: string): MediaStream | undefined {
        return this.remoteContentStreams.get(remoteCid);
    }

    /** Legacy camera stream accessor (audio+camera as today). */
    getRemoteStream(remoteCid: string): MediaStream | undefined {
        return this.remoteStreams.get(remoteCid);
    }

    /** Local content (screen share) stream for optional local preview. */
    getLocalContentStream(): MediaStream | null {
        // Stable identity: built once when the content track is set, nulled on
        // release. Returns null when not sharing (matches the prior semantics).
        return this.localContentStream;
    }

    /**
     * True while this peer is the deferred-answer offerer awaiting its first answer from `remoteCid`.
     * Gates the initial offer-timeout/ICE-restart/media-restart suppression; renegotiations after
     * the first answer behave normally.
     */
    private isDeferringInitialNegotiation(remoteCid: string): boolean {
        return this.deferInitialAnswer && this.shouldIOffer(remoteCid) && !this.initialAnswerReceivedCids.has(remoteCid);
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
            if (this.isDeferringInitialNegotiation(remoteCid)) {
                this.logger?.log('debug', 'WebRTC', `[${remoteCid}] Deferring initial offer timeout`);
                return;
            }
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

    private scheduleIceRestart(
        remoteCid: string,
        reason: string,
        delayMs: number,
        options?: { allowBeforeFirstAnswer?: boolean },
    ): void {
        const peer = this.peers.get(remoteCid);
        if (!peer) return;
        if (!options?.allowBeforeFirstAnswer && this.isDeferringInitialNegotiation(remoteCid)) {
            return;
        }
        if (!this.isSignalingConnected || !this.isParticipantActive(remoteCid)) { peer.pendingIceRestart = true; return; }
        if (peer.iceRestartTimer) return;
        // Inside the cooldown window, defer to its expiry instead of dropping:
        // ICE state changes are edge-triggered, so a dropped restart for a
        // connection parked in `failed` would never be retried.
        const cooldownRemainingMs = Math.min(
            ICE_RESTART_COOLDOWN_MS,
            Math.max(0, peer.lastIceRestartAt + ICE_RESTART_COOLDOWN_MS - Date.now()),
        );
        peer.iceRestartTimer = window.setTimeout(() => {
            peer.iceRestartTimer = null;
            if (options) {
                void this.triggerIceRestart(remoteCid, reason, options);
            } else {
                void this.triggerIceRestart(remoteCid, reason);
            }
        }, Math.max(delayMs, cooldownRemainingMs));
    }

    private async triggerIceRestart(
        remoteCid: string,
        reason: string,
        options?: { allowBeforeFirstAnswer?: boolean },
    ): Promise<void> {
        const peer = this.peers.get(remoteCid);
        if (!peer) return;
        if (!options?.allowBeforeFirstAnswer && this.isDeferringInitialNegotiation(remoteCid)) {
            this.logger?.log('debug', 'WebRTC', `[${remoteCid}] Suppressing ICE restart before first answer (${reason})`);
            return;
        }
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
        if (!this.videoMediaEnabled) {
            this.rejectRemoteVideoTransceivers(peer.pc, fromCid);
        }
        peer.acceptedRemoteOfferId = offerId;
        peer.currentNegotiationId = offerId;
        if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }
        await this.flushPendingRemoteIce(peer, offerId);
        while (peer.iceBuffer.length > 0) {
            const c = peer.iceBuffer.shift();
            if (c) await peer.pc.addIceCandidate(c);
        }
        if (peer.supportsIndependentContentVideo && this.videoMediaEnabled) {
            // Answerer/owner: bind video roles from the applied offer by m-line
            // order (idempotent for already-bound transceivers), then attach
            // any live/pending local camera + content tracks via replaceTrack.
            if (!this.localStream) {
                await this.startLocalMedia();
            } else {
                this.attachAudioTrackToIndependentPeer(peer);
            }
            this.assignRemoteVideoRoles(peer);
        } else if (this.localStream) {
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
        let shouldRecoverFromFailedAnswer = false;
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
            shouldRecoverFromFailedAnswer = this.shouldIOffer(fromCid);
            await peer.pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }));
            shouldRecoverFromFailedAnswer = false;
            peer.isSettingRemoteAnswerPending = false;
            this.initialAnswerReceivedCids.add(fromCid);
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
            // Owner renegotiation-complete attach: when a structural
            // renegotiation was forced (e.g. a `replaceTrack`-reject fallback for
            // the content sender), the content m-line is now re-negotiated, so
            // (re)attach any pending/active local tracks via replaceTrack. This is
            // the owner-side counterpart to the answerer's `assignRemoteVideoRoles`
            // attach. Idempotent: `attachPendingLocalTracks` skips senders that
            // already carry the right track.
            if (peer.supportsIndependentContentVideo && this.videoMediaEnabled) {
                this.attachPendingLocalTracks(peer);
            }
        } catch (err) {
            const peer = this.peers.get(fromCid);
            if (peer) peer.isSettingRemoteAnswerPending = false;
            if (shouldRecoverFromFailedAnswer) {
                this.scheduleIceRestart(fromCid, 'answer-apply-failed', 0, { allowBeforeFirstAnswer: true });
            }
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
                // Independent-capable peers route the local camera track through
                // the bound camera role sender only — never the content sender,
                // and never via findTransceiver (ambiguous with two video m-lines).
                if (peer.supportsIndependentContentVideo) {
                    if (peer.mediaRoles.camera) {
                        await this.replaceRoleTrack(peer, 'camera', newTrack);
                    } else if (newTrack && this.shouldIOffer(remoteCid)) {
                        // Owner not yet set up (peer pre-dates media): create now.
                        this.ensureOwnerVideoTransceivers(peer);
                        this.scheduleLocalTrackNegotiation(remoteCid, peer);
                    }
                    return;
                }
                // Mixed mesh during an independent share: a legacy peer's single
                // video sender is carrying the display (content) track, not the
                // camera (`startScreenShareIndependent`/`attachActiveShareToLegacyPeer`
                // swapped it). Camera operations (disable/enable/flip/stall-recovery)
                // must NOT clobber that — screen share takes priority over camera on
                // a legacy connection (design: Mixed Mesh Rooms). Skip this peer;
                // `stopScreenShare` → `restoreLegacyPeerCameraTrack` restores its
                // camera when the share ends, after which camera ops affect it again.
                // Flag off → `enableIndependentContentVideo` is false → never skips
                // (the legacy share path is suppressed wholesale upstream), so the
                // flag-off build is byte-identical.
                if (this.enableIndependentContentVideo && this.isScreenSharing) {
                    return;
                }
                const videoTransceiver = this.findTransceiver(peer.pc, 'video');
                if (videoTransceiver) {
                    try {
                        await videoTransceiver.sender.replaceTrack(newTrack);
                        if (newTrack && videoTransceiver.direction !== 'sendrecv' && videoTransceiver.direction !== 'stopped' && this.videoMediaEnabled) {
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

    private async acquireInitialMedia(
        video: boolean | MediaTrackConstraints,
        preferredDeviceId?: string,
    ): Promise<MediaStream> {
        try {
            return await navigator.mediaDevices.getUserMedia({
                video,
                audio: this.createAudioConstraints(preferredDeviceId),
            });
        } catch (err) {
            if (!preferredDeviceId) throw err;
            this.logger?.log('warning', 'WebRTC', `Failed to acquire preferred initial audio input: ${formatError(err)}`);
            return navigator.mediaDevices.getUserMedia({
                video,
                audio: this.createAudioConstraints(),
            });
        }
    }

    private async acquireAudioTrack(enabled: boolean, preferredDeviceId?: string): Promise<MediaStreamTrack> {
        let audioStream: MediaStream;
        try {
            audioStream = await navigator.mediaDevices.getUserMedia({
                video: false,
                audio: this.createAudioConstraints(preferredDeviceId),
            });
        } catch (err) {
            if (!preferredDeviceId) throw err;
            this.logger?.log('warning', 'WebRTC', `Failed to acquire preferred audio input: ${formatError(err)}`);
            audioStream = await navigator.mediaDevices.getUserMedia({
                video: false,
                audio: this.createAudioConstraints(),
            });
        }
        const audioTrack = audioStream.getAudioTracks()[0];
        if (!audioTrack) {
            audioStream.getTracks().forEach(track => track.stop());
            throw new Error('No audio track returned');
        }
        audioTrack.enabled = enabled;
        this.applySpeechTrackHints(audioStream);
        return audioTrack;
    }

    private async replaceAudioTrackOnAllPeers(newTrack: MediaStreamTrack, stream: MediaStream): Promise<void> {
        await Promise.all(
            Array.from(this.peers.entries()).map(async ([remoteCid, peer]) => {
                const audioTransceiver = this.findTransceiver(peer.pc, 'audio');
                if (audioTransceiver) {
                    try {
                        await audioTransceiver.sender.replaceTrack(newTrack);
                        let negotiationNeeded = this.needsLocalTrackNegotiation(audioTransceiver);
                        if (audioTransceiver.direction !== 'sendrecv' && audioTransceiver.direction !== 'stopped') {
                            audioTransceiver.direction = 'sendrecv';
                            negotiationNeeded = true;
                        }
                        if (negotiationNeeded) {
                            this.scheduleLocalTrackNegotiation(remoteCid, peer);
                        }
                    } catch (err) {
                        this.logger?.log('warning', 'WebRTC', `Failed to replace audio track on peer: ${formatError(err)}`);
                    }
                    return;
                }
                try {
                    peer.pc.addTrack(newTrack, stream);
                    this.scheduleLocalTrackNegotiation(remoteCid, peer);
                } catch (err) {
                    this.logger?.log('warning', 'WebRTC', `Failed to add audio track on peer: ${formatError(err)}`);
                }
            })
        );
    }

    private async refreshLocalAudioTrack(reason: string, devices?: MediaDeviceInfo[] | null, updatePeers = true): Promise<boolean> {
        const currentAudioTrack = this.localStream?.getAudioTracks()[0] ?? null;
        if (!this.localStream) {
            return false;
        }
        if (this.requestingMedia) {
            return false;
        }
        if (this.audioRecoveryInFlight) {
            return false;
        }
        const preferredInput = this.selectPreferredAudioInput(devices ?? null, currentAudioTrack);
        if (preferredInput.basis === 'no-devices' || preferredInput.basis === 'none') {
            return false;
        }
        if (preferredInput.currentMatchesPreferredRoute) {
            return false;
        }
        if (!preferredInput.device) {
            return false;
        }

        const requestId = this.mediaRequestId;
        this.audioRecoveryInFlight = true;
        try {
            const nextTrack = await this.acquireAudioTrack(currentAudioTrack?.enabled ?? true, preferredInput.device?.deviceId);
            if (this.destroyed || !this.localStream || this.mediaRequestId !== requestId) {
                nextTrack.stop();
                return false;
            }

            const nextStream = new MediaStream();
            let replacedAudio = false;
            for (const track of this.localStream.getTracks()) {
                if (track.kind !== 'audio') {
                    nextStream.addTrack(track);
                    continue;
                }
                if (!replacedAudio) {
                    nextStream.addTrack(nextTrack);
                    replacedAudio = true;
                }
            }
            if (!replacedAudio) {
                nextStream.addTrack(nextTrack);
            }
            this.localStream = nextStream;
            if (updatePeers) {
                await this.replaceAudioTrackOnAllPeers(nextTrack, nextStream);
            }
            if (currentAudioTrack && currentAudioTrack !== nextTrack) currentAudioTrack.stop();
            this.logger?.log('info', 'WebRTC', `Refreshed local audio track (${reason})`);
            this.notifyChange();
            return true;
        } catch (err) {
            this.logger?.log('warning', 'WebRTC', `Failed to refresh local audio track (${reason}): ${formatError(err)}`);
            return false;
        } finally {
            this.audioRecoveryInFlight = false;
        }
    }

    private ensureMediaTransceivers(pc: RTCPeerConnection): void {
        if (!this.findTransceiver(pc, 'audio') && !pc.getSenders().some(sender => sender.track?.kind === 'audio')) {
            pc.addTransceiver('audio', { direction: 'recvonly' });
        }
        if (this.videoMediaEnabled && !this.findTransceiver(pc, 'video') && !pc.getSenders().some(sender => sender.track?.kind === 'video')) {
            pc.addTransceiver('video', { direction: this.videoCaptureSupported ? 'sendrecv' : 'recvonly' });
        }
    }

    private rejectRemoteVideoTransceivers(pc: RTCPeerConnection, remoteCid: string): void {
        for (const transceiver of pc.getTransceivers().filter(candidate => this.isTransceiverKind(candidate, 'video'))) {
            try {
                if (transceiver.direction !== 'inactive' && transceiver.direction !== 'stopped') {
                    transceiver.direction = 'inactive';
                }
            } catch (err) {
                this.logger?.log('warning', 'WebRTC', `[${remoteCid}] Failed to reject remote video transceiver: ${formatError(err)}`);
            }
            if (transceiver.sender.track?.kind === 'video') {
                void transceiver.sender.replaceTrack(null).catch(err => {
                    this.logger?.log('warning', 'WebRTC', `[${remoteCid}] Failed to detach rejected video sender: ${formatError(err)}`);
                });
            }
        }
    }

    private findTransceiver(pc: RTCPeerConnection, kind: 'audio' | 'video'): RTCRtpTransceiver | undefined {
        const transceivers = pc.getTransceivers().filter(transceiver => this.isTransceiverKind(transceiver, kind));
        return transceivers.find(transceiver => transceiver.mid !== null) ?? transceivers[0];
    }

    private isTransceiverKind(transceiver: RTCRtpTransceiver, kind: 'audio' | 'video'): boolean {
        return transceiver.receiver.track?.kind === kind || transceiver.sender.track?.kind === kind;
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
            // Suppress recovery only when the camera track IS the display track
            // (legacy share). In independent mode the camera lives on its own
            // track in `localStream` and a stalled camera must still recover
            // while the (separate) content share continues.
            isScreenSharing: this.isLegacyScreenSharing,
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

    private async enumerateMediaDevices(): Promise<MediaDeviceInfo[] | null> {
        if (!navigator.mediaDevices?.enumerateDevices) {
            return null;
        }
        try {
            return await navigator.mediaDevices.enumerateDevices();
        } catch {
            return null;
        }
    }

    private async detectCameras(devices?: MediaDeviceInfo[] | null): Promise<void> {
        const resolvedDevices = devices === undefined ? await this.enumerateMediaDevices() : devices;
        if (!resolvedDevices) return;
        this.hasMultipleCameras = resolvedDevices.filter(d => d.kind === 'videoinput').length > 1;
    }

    private createAudioConstraints(deviceId?: string): MediaTrackConstraints {
        const constraints: MediaTrackConstraints = {
            echoCancellation: { ideal: true },
            noiseSuppression: { ideal: true },
            autoGainControl: { ideal: true },
            channelCount: { ideal: 1 },
            sampleRate: { ideal: 48000 },
        };
        if (deviceId) {
            constraints.deviceId = { exact: deviceId };
        }
        return constraints;
    }

    private getTrackGroupId(track: MediaStreamTrack | null): string | null {
        try {
            const groupId = track?.getSettings().groupId;
            return groupId ? groupId : null;
        } catch {
            return null;
        }
    }

    private isVirtualDefaultAudioDevice(device: MediaDeviceInfo): boolean {
        return device.deviceId === 'default' || device.deviceId === 'communications';
    }

    private selectPreferredAudioInput(devices: MediaDeviceInfo[] | null, currentAudioTrack: MediaStreamTrack | null): PreferredAudioInputSelection {
        if (!devices) {
            return {
                device: null,
                currentMatchesPreferredRoute: false,
                basis: 'no-devices',
            };
        }
        const hasCurrentAudioTrack = currentAudioTrack?.readyState === 'live';
        const currentGroupId = hasCurrentAudioTrack ? this.getTrackGroupId(currentAudioTrack) : null;
        const audioInputs = devices.filter(device => device.kind === 'audioinput');
        const defaultInput = audioInputs.find(device => device.deviceId === 'default');
        const defaultOutput = devices.find(device => device.kind === 'audiooutput' && device.deviceId === 'default');
        const inputForGroup = (groupId: string): MediaDeviceInfo | null => {
            const matchingInputs = audioInputs.filter(device => device.groupId === groupId);
            return matchingInputs.find(device => !this.isVirtualDefaultAudioDevice(device)) ?? matchingInputs[0] ?? null;
        };

        if (defaultInput && !defaultInput.groupId) {
            return {
                device: hasCurrentAudioTrack && !currentGroupId ? null : defaultInput,
                currentMatchesPreferredRoute: hasCurrentAudioTrack && !currentGroupId,
                basis: hasCurrentAudioTrack && !currentGroupId ? 'already-matched' : 'default-input',
            };
        }

        if (defaultInput && currentGroupId !== defaultInput.groupId) {
            return {
                device: inputForGroup(defaultInput.groupId) ?? defaultInput,
                currentMatchesPreferredRoute: false,
                basis: 'default-input',
            };
        }

        if (defaultOutput?.groupId && currentGroupId !== defaultOutput.groupId) {
            const outputInput = inputForGroup(defaultOutput.groupId);
            if (!outputInput) {
                return {
                    device: null,
                    currentMatchesPreferredRoute: false,
                    basis: 'none',
                };
            }
            return {
                device: outputInput,
                currentMatchesPreferredRoute: false,
                basis: 'default-output',
            };
        }

        if (currentGroupId && (currentGroupId === defaultInput?.groupId || currentGroupId === defaultOutput?.groupId)) {
            return {
                device: null,
                currentMatchesPreferredRoute: true,
                basis: 'already-matched',
            };
        }

        return {
            device: null,
            currentMatchesPreferredRoute: false,
            basis: 'none',
        };
    }

    private async refreshDevicesAfterChange(reason: string): Promise<void> {
        const devices = await this.enumerateMediaDevices();
        await Promise.all([
            this.detectCameras(devices),
            this.refreshLocalAudioTrack(reason, devices),
        ]);
    }

    private handleDeviceChange(): void {
        void this.refreshDevicesAfterChange('device-change');
        if (this.deviceChangeSettleTimer !== null) {
            window.clearTimeout(this.deviceChangeSettleTimer);
        }
        this.deviceChangeSettleTimer = window.setTimeout(() => {
            this.deviceChangeSettleTimer = null;
            void this.refreshDevicesAfterChange('device-change-settled');
        }, DEVICE_CHANGE_SETTLE_DELAY_MS);
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

        this.deviceChangeHandler = () => { this.handleDeviceChange(); };
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
        if (this.deviceChangeSettleTimer !== null) {
            window.clearTimeout(this.deviceChangeSettleTimer);
            this.deviceChangeSettleTimer = null;
        }
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
        if (this.isDeferringInitialNegotiation(fromCid)) {
            this.logger?.log('debug', 'WebRTC', `[${fromCid}] Ignoring media restart before first answer`);
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

    /**
     * Capability-transition slot handling (independent-content mode). A peer can
     * be created LEGACY before its capabilities arrive (an early offer or a
     * `peer_joined` with no capabilities), and the later cap-bearing `room_state`
     * only updates the stored room caps — the existing peer connection stays
     * immutably legacy, so a late-announced CAPABLE peer would never negotiate or
     * bind the content transceiver. Detect when a peer's COMPUTED independent
     * capability now differs from the capability its current peer connection was
     * built with, and recreate that peer so it re-runs role binding from scratch
     * with the correct camera/content m-line layout. Returns true when a recreate
     * was performed (the caller skips its generic offer block for this peer).
     *
     * Flag-off byte-identical: `isPeerIndependentCapable` is always false when
     * `enableIndependentContentVideo` is off, and every legacy peer is also built
     * with `supportsIndependentContentVideo === false`, so the capability never
     * flips and this is inert (no recreate ever).
     */
    private reconcilePeerCapability(remoteCid: string): boolean {
        const peer = this.peers.get(remoteCid);
        if (!peer) return false;
        const capableNow = this.isPeerIndependentCapable(remoteCid);
        if (capableNow === peer.supportsIndependentContentVideo) return false;
        void this.recreatePeerForCapabilityChange(remoteCid);
        return true;
    }

    /**
     * Close and recreate a peer whose independent-content capability flipped.
     * Mirrors {@link recreatePeerForMediaRecovery}'s close+recreate, but is NOT
     * gated on `shouldIOffer`: BOTH ends saw the same capability change in the
     * shared `room_state`, so each side must rebuild its own peer connection.
     * `getOrCreatePeer` re-snapshots the new capability and re-creates the
     * correct transceiver layout (owner pre-creates camera+content; answerer
     * pre-creates nothing). The deterministic offer owner re-offers here; the
     * answerer's fresh connection waits for that re-offer. An in-progress screen
     * share re-attaches automatically via the existing pending-content mechanism
     * (`ensureOwnerVideoTransceivers` sets `pendingContentAttach` when
     * `localContentTrack` is live; a recreated legacy peer picks the share up via
     * `attachActiveShareToLegacyPeer`).
     */
    private async recreatePeerForCapabilityChange(remoteCid: string): Promise<void> {
        if (!this.isParticipantActive(remoteCid)) return;
        const previousStatus = this.participantConnectionStatus.get(remoteCid);
        this.logger?.log('debug', 'WebRTC', `[${remoteCid}] Recreating peer after independent-content capability change`);
        this.cleanupPeer(remoteCid, { clearMediaRestartCooldown: false });
        if (previousStatus) this.participantConnectionStatus.set(remoteCid, previousStatus);
        this.getOrCreatePeer(remoteCid);
        if (this.shouldIOffer(remoteCid) && this.localStream) {
            await this.createOfferTo(remoteCid);
        }
    }

    private notifyChange(): void { this.onChange?.(); }
}
