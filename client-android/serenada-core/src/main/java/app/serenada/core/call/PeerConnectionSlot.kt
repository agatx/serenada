package app.serenada.core.call

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import java.util.concurrent.atomic.AtomicInteger
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import kotlin.math.max

internal class PeerConnectionSlot(
    override val remoteCid: String,
    private val factory: PeerConnectionFactory?,
    private var iceServers: List<PeerConnection.IceServer>?,
    private var localAudioTrack: AudioTrack?,
    private var localVideoTrack: VideoTrack?,
    private val videoReceiveEnabled: Boolean = true,
    private val onLocalIceCandidate: (String, IceCandidate) -> Unit,
    private val onRemoteVideoTrack: (String, VideoTrack?) -> Unit,
    private val onConnectionStateChange: (String, PeerConnection.PeerConnectionState) -> Unit,
    private val onIceConnectionStateChange: (String, PeerConnection.IceConnectionState) -> Unit,
    private val onSignalingStateChange: (String, PeerConnection.SignalingState) -> Unit,
    private val onRenegotiationNeeded: (String) -> Unit,
    private val applyAudioSenderParameters: (PeerConnection) -> Unit,
    private val currentVideoSenderPolicy: () -> WebRtcEngine.VideoSenderPolicy,
    private val isRemoteBlackFrameAnalysisEnabled: () -> Boolean,
    private val peerConnectionDisposeQueue: PeerConnectionDisposeQueue,
    // Independent-content gate for THIS peer (per-peer). False ⇒ legacy
    // single-video path, byte-identical to today. See [attachLocalTracks].
    override val supportsIndependentContentVideo: Boolean = false,
    // Whether the local participant is the deterministic offer owner for this
    // connection. The owner pre-creates camera/content transceivers up front;
    // the answerer binds roles from the applied remote offer. Evaluated lazily
    // so it tracks glare/ownership the same way the negotiation engine does.
    private val isOfferOwner: () -> Boolean = { false },
    // Conservative content (screen share) sender encoding profile, applied to the
    // content sender so a typical display track fits the negotiated envelope.
    private val contentSenderPolicy: () -> WebRtcEngine.VideoSenderPolicy =
        { WebRtcEngine.VideoSenderPolicy(null, null, null, null) },
    private val logger: SerenadaLogger? = null,
) : PeerConnectionSlotProtocol {
    private companion object {
        const val DEFERRED_DISPOSE_DELAY_MS = 250L
    }

    // --- Independent-content role binding (capable peers only) ---
    // Camera/content video transceivers bound ONCE by object identity / mid:
    // first video m-line → camera, second → content. Never recomputed from
    // media/track state once bound (design "Role binding invariants"). Empty for
    // legacy peers (those keep using the single-video [attachTrackToTransceiver]).
    private var cameraTransceiver: RtpTransceiver? = null
    private var contentTransceiver: RtpTransceiver? = null
    // The local content (screen share) track for this peer. Attached to the
    // content sender once the content transceiver binds (covers answerer /
    // late-join / setTrack-reject fallback). attachBoundVideoTracks reconciles
    // the sender to match this field, so a pending attach is implicit: while a
    // track is set but no transceiver is bound yet, the next bind re-runs the
    // reconcile and attaches it.
    private var localContentTrack: VideoTrack? = null

    private data class MediaTotals(
        var inboundPacketsReceived: Long = 0L,
        var inboundPacketsLost: Long = 0L,
        var inboundBytes: Long = 0L,
        var inboundJitterSumSeconds: Double = 0.0,
        var inboundJitterCount: Int = 0,
        var inboundJitterBufferDelaySeconds: Double = 0.0,
        var inboundJitterBufferEmittedCount: Long = 0L,
        var inboundConcealedSamples: Long = 0L,
        var inboundTotalSamples: Long = 0L,
        var inboundFpsSum: Double = 0.0,
        var inboundFpsCount: Int = 0,
        var inboundFrameWidth: Int = 0,
        var inboundFrameHeight: Int = 0,
        var inboundFramesDecoded: Long = 0L,
        var inboundFramesDropped: Long = 0L,
        var inboundFreezeCount: Long = 0L,
        var inboundFreezeDurationSeconds: Double = 0.0,
        var inboundNackCount: Long = 0L,
        var inboundPliCount: Long = 0L,
        var inboundFirCount: Long = 0L,
        var outboundPacketsSent: Long = 0L,
        var outboundBytes: Long = 0L,
        var outboundPacketsRetransmitted: Long = 0L,
        var remoteInboundPacketsLost: Long = 0L,
        // Number of inbound-rtp stats seen for this kind. Distinguishes a
        // genuine 0 from "no inbound-rtp stat" so telemetry counters surface
        // null (unknown) rather than a fake 0.
        var inboundRtpCount: Int = 0,
        // Per-counter presence. A row can exist (inboundRtpCount > 0) yet omit
        // a specific member; surface null for that member alone, not a fake 0.
        var sawPacketsReceived: Boolean = false,
        var sawPacketsLost: Boolean = false,
        var sawFramesDecoded: Boolean = false,
        var sawFramesDropped: Boolean = false
    )

    private data class RealtimeStatsSample(
        val timestampMs: Long,
        val audioRxBytes: Long,
        val audioTxBytes: Long,
        val videoRxBytes: Long,
        val videoTxBytes: Long,
        val videoFramesDecoded: Long,
        val videoNackCount: Long,
        val videoPliCount: Long,
        val videoFirCount: Long
    )

    private data class FreezeSample(
        val timestampMs: Long,
        val freezeCount: Long,
        val freezeDurationSeconds: Double
    )

    override var sentOffer: Boolean = false
        private set
    override var isMakingOffer: Boolean = false
        private set
    override var pendingIceRestart: Boolean = false
        private set
    override var lastIceRestartAt: Long = 0L
        private set
    override var offerTimeoutTask: Runnable? = null
        private set
    override var iceRestartTask: Runnable? = null
        private set

    // Offer lifecycle
    override fun beginOffer() { isMakingOffer = true }
    override fun completeOffer() { isMakingOffer = false }
    override fun markOfferSent() { sentOffer = true }

    // ICE restart lifecycle
    override fun markPendingIceRestart() { pendingIceRestart = true }
    override fun clearPendingIceRestart() { pendingIceRestart = false }
    override fun recordIceRestart(nowMs: Long) {
        lastIceRestartAt = nowMs
        pendingIceRestart = false
    }

    // Task management
    override fun setOfferTimeoutTask(task: Runnable) { offerTimeoutTask = task }
    override fun cancelOfferTimeout() { offerTimeoutTask = null }
    override fun setIceRestartTask(task: Runnable) { iceRestartTask = task }
    override fun cancelIceRestartTask() { iceRestartTask = null }

    @Volatile private var isClosing = false
    private var peerConnection: PeerConnection? = null
    private var audioSender: RtpSender? = null
    // Camera remote track (the default video track for legacy peers). For
    // capable peers this is the track on the bound CAMERA transceiver.
    @Volatile private var remoteVideoTrack: VideoTrack? = null
    // Content remote track (capable peers: track on the bound CONTENT
    // transceiver). Null for legacy peers; their single track is presented as
    // content by the session based on content_state, not by a separate track.
    @Volatile private var remoteContentVideoTrack: VideoTrack? = null
    @Volatile private var playbackDucked = false
    private var remoteDescriptionSet = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private val remoteSinks = LinkedHashSet<VideoSink>()
    // Sinks attached specifically to the remote CONTENT track (capable peers).
    private val remoteContentSinks = LinkedHashSet<VideoSink>()
    private var lastRealtimeStatsSample: RealtimeStatsSample? = null
    // Written from the stats executor thread, read from the provider handler.
    @Volatile private var lastPathIsDirect: Boolean? = null
    private val freezeSamples = mutableListOf<FreezeSample>()
    private val remoteBlackFrameAnalyzer = RemoteBlackFrameAnalyzer()
    private val remoteVideoStateSink = VideoSink { frame ->
        if (!isClosing) {
            val stateChanged = remoteBlackFrameAnalyzer.onFrame(
                frame = frame,
                blackFrameAnalysisEnabled = isRemoteBlackFrameAnalysisEnabled()
            )
            if (stateChanged) {
                logger?.log(
                    SerenadaLogLevel.DEBUG,
                    "PeerConnection",
                    "[RemoteVideo][$remoteCid] syntheticBlack=${remoteBlackFrameAnalyzer.isSyntheticBlackDetected()} trackPresent=${remoteVideoTrack != null}"
                )
            }
        }
    }

    override fun setIceServers(servers: List<PeerConnection.IceServer>) {
        iceServers = servers
        val pc = peerConnection
        if (pc != null && !isClosing) {
            // Apply refreshed credentials to the live connection so a later
            // ICE restart gathers relay candidates with current (not expired)
            // TURN credentials.
            val config = PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            if (!pc.setConfiguration(config)) {
                logger?.log(SerenadaLogLevel.WARNING, "PeerConnection", "[$remoteCid] Failed to apply refreshed ICE servers")
            }
            return
        }
        ensurePeerConnection()
    }

    override fun ensurePeerConnection(): Boolean {
        if (isClosing) return false
        if (peerConnection != null) return true
        val f = factory ?: return false
        val servers = iceServers ?: return false
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        var observerPeerConnection: PeerConnection? = null
        val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (!isCurrentPeerConnection(observerPeerConnection)) return
                onLocalIceCandidate(remoteCid, candidate)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (!isCurrentPeerConnection(observerPeerConnection)) return
                logger?.log(SerenadaLogLevel.DEBUG, "PeerConnection", "[$remoteCid] Connection state: $newState")
                onConnectionStateChange(remoteCid, newState)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                if (!isCurrentPeerConnection(observerPeerConnection)) return
                val track = transceiver?.receiver?.track()
                if (track is AudioTrack) {
                    // Audio routing is independent of camera/content video
                    // classification (pitfall #4): never disturb the audio sink.
                    track.setVolume(if (playbackDucked) 0.15 else 1.0)
                }
                if (track is VideoTrack) {
                    if (!videoReceiveEnabled) {
                        logger?.log(
                            SerenadaLogLevel.INFO,
                            "PeerConnection",
                            "[$remoteCid] Ignoring remote video track because video media is disabled"
                        )
                        return
                    }
                    // Capable peers: classify by the PERSISTED transceiver role
                    // bound by m-line order. The track may arrive before the
                    // answerer's binding ran, so (re)bind from m-line order first.
                    if (supportsIndependentContentVideo) {
                        ensureVideoRolesBound(observerPeerConnection)
                        // Classify by the stable m-line id (mid), NOT transceiver object
                        // identity. Native libwebrtc delivers a DIFFERENT RtpTransceiver
                        // wrapper to onTrack than the one cached in camera/contentTransceiver
                        // (same underlying native transceiver), so `===` matched neither and
                        // the inbound remote CAMERA was dropped to `else` -> no remote video.
                        // mid is the negotiated m-line id and is stable across wrapper
                        // instances; identity is kept as a fallback before mids are assigned.
                        val incomingMid = transceiver.mid
                        when {
                            incomingMid != null && incomingMid == contentTransceiver?.mid ->
                                { attachRemoteContentTrack(track); return }
                            incomingMid != null && incomingMid == cameraTransceiver?.mid ->
                                { attachRemoteCameraTrack(track); return }
                            transceiver === contentTransceiver -> { attachRemoteContentTrack(track); return }
                            transceiver === cameraTransceiver -> { attachRemoteCameraTrack(track); return }
                            else -> {
                                // Unbound extra video m-line: do not promote it.
                                logger?.log(
                                    SerenadaLogLevel.DEBUG,
                                    "PeerConnection",
                                    "[$remoteCid] Ignoring unbound remote video track (mid=$incomingMid)"
                                )
                                return
                            }
                        }
                    }
                    // Legacy peer: single video track is the camera track. The
                    // session presents it as content when content_state is active.
                    attachRemoteCameraTrack(track)
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                if (!isCurrentPeerConnection(observerPeerConnection)) return
                logger?.log(SerenadaLogLevel.DEBUG, "PeerConnection", "[$remoteCid] Signaling state: $newState")
                onSignalingStateChange(remoteCid, newState)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                if (!isCurrentPeerConnection(observerPeerConnection)) return
                logger?.log(SerenadaLogLevel.DEBUG, "PeerConnection", "[$remoteCid] ICE state: $newState")
                onIceConnectionStateChange(remoteCid, newState)
            }

            override fun onRenegotiationNeeded() {
                if (!isCurrentPeerConnection(observerPeerConnection)) return
                onRenegotiationNeeded(remoteCid)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(dc: org.webrtc.DataChannel) = Unit
        }) ?: return false

        observerPeerConnection = pc
        peerConnection = pc
        attachLocalTracks(
            audioTrack = localAudioTrack,
            cameraTrack = localVideoTrack,
            contentTrack = localContentTrack,
            supportsIndependentContentVideo = supportsIndependentContentVideo,
        )
        ensureReceiveTransceivers(pc)
        applyAudioSenderParameters(pc)
        applyVideoSenderParameters(currentVideoSenderPolicy())
        return true
    }

    override fun attachLocalTracks(
        audioTrack: AudioTrack?,
        cameraTrack: VideoTrack?,
        contentTrack: VideoTrack?,
        supportsIndependentContentVideo: Boolean,
    ) {
        if (isClosing) return
        localAudioTrack = audioTrack
        localVideoTrack = cameraTrack
        localContentTrack = contentTrack
        val pc = peerConnection ?: run {
            if (!ensurePeerConnection()) return
            peerConnection
        } ?: return

        attachTrackToTransceiver(
            pc = pc,
            track = audioTrack,
            mediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            onAttached = { sender ->
                audioSender = sender
                if (audioTrack != null) applyAudioSenderParameters(pc)
            }
        )

        if (this.supportsIndependentContentVideo) {
            attachIndependentVideoTracks(pc, cameraTrack, contentTrack)
        } else {
            // Legacy single-video path: one video transceiver carries whichever
            // video track the engine supplied (camera, or the display track when
            // the engine has routed an active share onto the camera track).
            attachTrackToTransceiver(
                pc = pc,
                track = cameraTrack,
                mediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                onAttached = { _ ->
                    if (cameraTrack != null) applyVideoSenderParameters(currentVideoSenderPolicy())
                }
            )
        }
    }

    /**
     * Independent-capable peer attach: ensure the ordered camera/content
     * transceivers exist (offer owner) or are bound (answerer), then attach the
     * camera and pending/active content tracks via their bound senders.
     */
    private fun attachIndependentVideoTracks(
        pc: PeerConnection,
        cameraTrack: VideoTrack?,
        contentTrack: VideoTrack?,
    ) {
        if (!videoReceiveEnabled) return
        // Drop stale (disposed) cached role transceivers before touching them: a
        // reconnect / deferred-dispose race can leave a reference to a transceiver
        // whose native object was disposed, and ANY access (getDirection, sender,
        // mid) then throws IllegalStateException — which crashed the screen-share
        // start (attachBoundVideoTracks -> ensureRoleSendCapable -> getDirection).
        // Cleared roles re-bind from the current peer connection's live m-lines.
        if (cameraTransceiver?.isLiveTransceiver() == false) cameraTransceiver = null
        if (contentTransceiver?.isLiveTransceiver() == false) contentTransceiver = null
        // Bind any existing live video m-lines first (re-acquires a fresh wrapper
        // after a clear, and is the answerer's normal path); then the offer owner
        // creates any still-missing role m-line. Running both is safe: an already-
        // bound role short-circuits ensureOwnerVideoTransceivers, so no duplicate.
        ensureVideoRolesBound(pc)
        if (isOfferOwner()) {
            ensureOwnerVideoTransceivers(pc)
        }
        // The content track (if any) attaches the moment the content transceiver
        // binds — see attachBoundVideoTracks, which reconciles purely from
        // localContentTrack and is the single role-state-driven attach point.
        attachBoundVideoTracks()
    }

    /**
     * Offer-owner only: pre-create the camera transceiver first then the content
     * transceiver second, both send-capable up front (no track yet sends
     * nothing). Idempotent — re-entry binds nothing new.
     */
    private fun ensureOwnerVideoTransceivers(pc: PeerConnection) {
        if (cameraTransceiver == null) {
            cameraTransceiver = pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV),
            )
        }
        if (contentTransceiver == null) {
            contentTransceiver = pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV),
            )
            contentTransceiver?.let { applyContentSenderParameters(it) }
        }
    }

    /**
     * Bind video roles by m-line order from the current transceiver list: first
     * video m-line → camera, second → content. Binds ONCE per role by
     * transceiver object identity; never recomputes an already-bound role. Extra
     * video m-lines beyond the second are answered inactive and never promoted.
     */
    private fun ensureVideoRolesBound(pc: PeerConnection?) {
        pc ?: return
        if (cameraTransceiver != null && contentTransceiver != null) return
        val videoTransceivers = pc.transceivers
            .filter {
                it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO &&
                    it.currentDirection != RtpTransceiver.RtpTransceiverDirection.STOPPED
            }
        for (transceiver in videoTransceivers) {
            if (transceiver === cameraTransceiver || transceiver === contentTransceiver) continue
            when {
                cameraTransceiver == null -> {
                    cameraTransceiver = transceiver
                    ensureRoleSendCapable(transceiver)
                }
                contentTransceiver == null -> {
                    contentTransceiver = transceiver
                    ensureRoleSendCapable(transceiver)
                    applyContentSenderParameters(transceiver)
                }
                else -> {
                    // Extra video m-line: never promoted. Answer inactive.
                    if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.INACTIVE &&
                        transceiver.direction != RtpTransceiver.RtpTransceiverDirection.STOPPED
                    ) {
                        runCatching { transceiver.direction = RtpTransceiver.RtpTransceiverDirection.INACTIVE }
                    }
                }
            }
        }
    }

    /**
     * Attach the camera and content tracks to their bound role senders.
     *
     * Single role-state-driven attach point (pitfall #1): re-attaches whenever a
     * role is bound and the sender does not already carry the right track, so
     * pending content attaches regardless of how it became pending (owner,
     * answerer, late-join, setTrack-reject retry). Idempotent via the
     * `sender.track !== track` guard.
     */
    private fun attachBoundVideoTracks() {
        val camera = cameraTransceiver
        val cameraTrack = localVideoTrack
        if (camera != null) {
            ensureRoleSendCapable(camera)
            if (camera.sender.track() !== cameraTrack) {
                camera.sender.setTrack(cameraTrack, false)
                if (cameraTrack != null) applyVideoSenderParameters(currentVideoSenderPolicy())
            }
        }
        // Reconcile the content sender to match the current local content track:
        // attach when a track is set (pending or live), detach (setTrack null)
        // when it has been cleared on stop. The `sender.track !== track`
        // idempotence guard makes a re-entry a no-op.
        val content = contentTransceiver
        val contentTrack = localContentTrack
        if (content != null && content.sender.track() !== contentTrack) {
            if (contentTrack != null) {
                ensureRoleSendCapable(content)
                replaceContentTrackWithFallback(content, contentTrack)
            } else {
                // Detach on stop: clear the content sender (no renegotiation).
                runCatching { content.sender.setTrack(null, false) }
            }
        }
    }

    /**
     * Replace the content sender's track. On setTrack rejection (returns false or
     * throws for an incompatible envelope), force a renegotiation for this peer's
     * content m-line (structural change), so the share re-attaches after the
     * forced renegotiation instead of leaving the content sender empty.
     */
    private fun replaceContentTrackWithFallback(content: RtpTransceiver, track: VideoTrack?) {
        // setTrack returns a boolean success AND can throw; treat either as reject.
        val ok = runCatching { content.sender.setTrack(track, false) }.getOrDefault(false)
        if (ok) {
            if (track != null) applyContentSenderParameters(content)
            return
        }
        // Durable retry: `localContentTrack` stays set, so the post-renegotiation
        // setRemoteDescription → attachBoundVideoTracks re-attaches it (the
        // content sender would otherwise stay empty after a structural change).
        logger?.log(
            SerenadaLogLevel.WARNING,
            "PeerConnection",
            "[$remoteCid] Failed to setTrack on content sender; renegotiating",
        )
        onRenegotiationNeeded(remoteCid)
    }

    /** False once the native transceiver has been disposed (any access throws). */
    private fun RtpTransceiver.isLiveTransceiver(): Boolean =
        runCatching { this.direction }.isSuccess

    private fun ensureRoleSendCapable(transceiver: RtpTransceiver) {
        // A disposed transceiver throws on any access; never crash the caller.
        val direction = runCatching { transceiver.direction }.getOrNull() ?: return
        if (direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV &&
            direction != RtpTransceiver.RtpTransceiverDirection.STOPPED
        ) {
            runCatching { transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV }
        }
    }

    private fun applyContentSenderParameters(transceiver: RtpTransceiver) {
        applySenderParameters(transceiver.sender, contentSenderPolicy())
    }

    override fun setAudioTrack(track: AudioTrack?) {
        localAudioTrack = track
        val pc = peerConnection ?: return
        attachTrackToTransceiver(
            pc = pc,
            track = track,
            mediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            onAttached = { sender ->
                audioSender = sender
                if (track != null) applyAudioSenderParameters(pc)
            }
        )
    }

    // Look up the transceiver by its stable mediaType (Unified Plan) instead of
    // sender.track?.kind. The latter mis-reports when the sender's track is null
    // (e.g. the recv-only transceiver created by ensureReceiveTransceivers), which
    // would cause pc.addTrack to append a duplicate transceiver of the same type.
    private fun attachTrackToTransceiver(
        pc: PeerConnection,
        track: MediaStreamTrack?,
        mediaType: MediaStreamTrack.MediaType,
        onAttached: (RtpSender?) -> Unit,
    ) {
        val transceiver = pc.transceivers.firstOrNull { it.mediaType == mediaType }
        if (transceiver != null) {
            val sender = transceiver.sender
            if (sender.track() !== track) {
                sender.setTrack(track, false)
            }
            val targetDirection = if (track != null) {
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            } else {
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
            if (transceiver.direction != targetDirection) {
                transceiver.direction = targetDirection
            }
            onAttached(sender)
        } else if (track != null) {
            val sender = pc.addTrack(track, listOf("serenada"))
            onAttached(sender)
        }
    }

    /** Bind/replace the remote CAMERA track and rewire camera sinks + analyzer. */
    private fun attachRemoteCameraTrack(track: VideoTrack) {
        if (track === remoteVideoTrack) return
        remoteVideoTrack?.removeSink(remoteVideoStateSink)
        remoteSinks.forEach { sink -> remoteVideoTrack?.removeSink(sink) }
        remoteVideoTrack = track
        remoteBlackFrameAnalyzer.onTrackAttached()
        track.addSink(remoteVideoStateSink)
        remoteSinks.forEach { sink -> track.addSink(sink) }
        onRemoteVideoTrack(remoteCid, track)
    }

    /** Bind/replace the remote CONTENT (screen share) track and rewire its sinks. */
    private fun attachRemoteContentTrack(track: VideoTrack) {
        if (track === remoteContentVideoTrack) return
        remoteContentSinks.forEach { sink -> remoteContentVideoTrack?.removeSink(sink) }
        remoteContentVideoTrack = track
        remoteContentSinks.forEach { sink -> track.addSink(sink) }
    }

    override fun attachRemoteContentSink(sink: VideoSink) {
        if (isClosing) return
        if (!remoteContentSinks.add(sink)) return
        remoteContentVideoTrack?.addSink(sink)
    }

    override fun detachRemoteContentSink(sink: VideoSink) {
        remoteContentVideoTrack?.removeSink(sink)
        remoteContentSinks.remove(sink)
    }

    override fun closePeerConnection(deferDispose: Boolean) {
        if (isClosing && peerConnection == null) return
        isClosing = true
        offerTimeoutTask = null
        iceRestartTask = null
        isMakingOffer = false
        pendingIceRestart = false
        val pc = peerConnection
        peerConnection = null
        val track = remoteVideoTrack
        track?.removeSink(remoteVideoStateSink)
        remoteSinks.forEach { sink -> track?.removeSink(sink) }
        remoteSinks.clear()
        remoteVideoTrack = null
        val contentTrack = remoteContentVideoTrack
        remoteContentSinks.forEach { sink -> contentTrack?.removeSink(sink) }
        remoteContentSinks.clear()
        remoteContentVideoTrack = null
        cameraTransceiver = null
        contentTransceiver = null
        localContentTrack = null
        audioSender = null
        remoteBlackFrameAnalyzer.onTrackDetached()
        remoteDescriptionSet = false
        pendingIceCandidates.clear()
        lastRealtimeStatsSample = null
        freezeSamples.clear()
        onRemoteVideoTrack(remoteCid, null)
        pc?.let {
            closePeerConnectionSafely(it)
            disposePeerConnectionSafely(it, deferDispose)
        }
    }

    override fun createOffer(
        iceRestart: Boolean,
        onSdp: (String) -> Unit,
        onComplete: ((Boolean) -> Unit)?,
    ): Boolean {
        if (isClosing) return false
        val pc = peerConnection ?: run {
            if (!ensurePeerConnection()) return false
            peerConnection
        } ?: return false
        if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            onComplete?.invoke(false)
            return false
        }

        val constraints = MediaConstraints()
        if (iceRestart) {
            constraints.optional.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (!isCurrentPeerConnection(pc)) return
                if (desc == null) {
                    onComplete?.invoke(false)
                    return
                }
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        if (!isCurrentPeerConnection(pc)) return
                        onSdp(desc.description)
                        onComplete?.invoke(true)
                    }

                    override fun onSetFailure(error: String?) {
                        if (!isCurrentPeerConnection(pc)) return
                        logger?.log(SerenadaLogLevel.WARNING, "PeerConnection", "[$remoteCid] Failed to set local offer: $error")
                        onComplete?.invoke(false)
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                if (!isCurrentPeerConnection(pc)) return
                logger?.log(SerenadaLogLevel.WARNING, "PeerConnection", "[$remoteCid] Offer creation failed: $error")
                onComplete?.invoke(false)
            }
        }, constraints)
        return true
    }

    override fun createAnswer(onSdp: (String) -> Unit, onComplete: ((Boolean) -> Unit)?) {
        if (isClosing) {
            onComplete?.invoke(false)
            return
        }
        val pc = peerConnection ?: run {
            if (!ensurePeerConnection()) {
                onComplete?.invoke(false)
                return
            }
            peerConnection
        } ?: run {
            onComplete?.invoke(false)
            return
        }

        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (!isCurrentPeerConnection(pc)) return
                if (desc == null) {
                    onComplete?.invoke(false)
                    return
                }
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        if (!isCurrentPeerConnection(pc)) return
                        onSdp(desc.description)
                        onComplete?.invoke(true)
                    }

                    override fun onSetFailure(error: String?) {
                        if (!isCurrentPeerConnection(pc)) return
                        logger?.log(SerenadaLogLevel.WARNING, "PeerConnection", "[$remoteCid] Failed to set local answer: $error")
                        onComplete?.invoke(false)
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                if (!isCurrentPeerConnection(pc)) return
                logger?.log(SerenadaLogLevel.WARNING, "PeerConnection", "[$remoteCid] Answer creation failed: $error")
                onComplete?.invoke(false)
            }
        }, constraints)
    }

    override fun setRemoteDescription(
        type: SessionDescription.Type,
        sdp: String,
        onComplete: ((Boolean) -> Unit)?,
    ) {
        if (isClosing) {
            onComplete?.invoke(false)
            return
        }
        val pc = peerConnection ?: run {
            if (!ensurePeerConnection()) {
                onComplete?.invoke(false)
                return
            }
            peerConnection
        } ?: run {
            onComplete?.invoke(false)
            return
        }
        val desc = SessionDescription(type, sdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                if (!isCurrentPeerConnection(pc)) return
                remoteDescriptionSet = true
                flushPendingIceCandidates()
                if (supportsIndependentContentVideo && videoReceiveEnabled) {
                    // Answerer: materialize role bindings from the applied offer
                    // (m-line order). Owner: re-bind is idempotent. Then attach any
                    // live/pending camera+content tracks via their bound senders —
                    // this is the single bind/attach point that also re-attaches
                    // a setTrack-reject fallback once the m-line re-negotiates.
                    ensureVideoRolesBound(pc)
                    attachBoundVideoTracks()
                }
                onComplete?.invoke(true)
            }

            override fun onSetFailure(error: String?) {
                if (!isCurrentPeerConnection(pc)) return
                logger?.log(SerenadaLogLevel.WARNING, "PeerConnection", "[$remoteCid] Failed to set remote description ($type): $error")
                onComplete?.invoke(false)
            }
        }, desc)
    }

    override fun addIceCandidate(candidate: IceCandidate) {
        if (isClosing) return
        val safeCandidate = sanitizeIceCandidate(candidate, remoteCid, logger) ?: return
        val pc = peerConnection ?: run {
            if (!ensurePeerConnection()) {
                if (pendingIceCandidates.size < WebRtcResilienceConstants.ICE_CANDIDATE_BUFFER_MAX) {
                    pendingIceCandidates.add(safeCandidate)
                }
                return
            }
            peerConnection
        } ?: return

        if (!remoteDescriptionSet) {
            if (pendingIceCandidates.size < WebRtcResilienceConstants.ICE_CANDIDATE_BUFFER_MAX) {
                pendingIceCandidates.add(safeCandidate)
            }
            return
        }
        pc.addIceCandidate(safeCandidate)
    }

    override fun rollbackLocalDescription(onComplete: ((Boolean) -> Unit)?) {
        if (isClosing) {
            onComplete?.invoke(false)
            return
        }
        val pc = peerConnection ?: return
        val desc = SessionDescription(SessionDescription.Type.ROLLBACK, "")
        pc.setLocalDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                if (!isCurrentPeerConnection(pc)) return
                onComplete?.invoke(true)
            }

            override fun onSetFailure(error: String?) {
                if (!isCurrentPeerConnection(pc)) return
                logger?.log(SerenadaLogLevel.WARNING, "PeerConnection", "[$remoteCid] Failed to rollback local description: $error")
                onComplete?.invoke(false)
            }
        }, desc)
    }

    override fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        attachRemoteSink(renderer)
    }

    override fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        detachRemoteSink(renderer)
    }

    override fun attachRemoteSink(sink: VideoSink) {
        if (isClosing) return
        if (!remoteSinks.add(sink)) return
        remoteVideoTrack?.addSink(sink)
    }

    override fun detachRemoteSink(sink: VideoSink) {
        remoteVideoTrack?.removeSink(sink)
        remoteSinks.remove(sink)
    }

    override fun collectWebRtcStats(onComplete: (String, RealtimeCallStats?) -> Unit) {
        if (isClosing) {
            onComplete("pc=closing remote=$remoteCid", null)
            return
        }
        val pc = peerConnection
        if (pc == null) {
            onComplete("pc=none remote=$remoteCid", null)
            return
        }
        pc.getStats { report ->
            onComplete(
                buildWebRtcStatsSummary(report),
                buildRealtimeCallStats(report),
            )
        }
    }

    override fun collectInboundLiveness(onComplete: (InboundLivenessSample) -> Unit) {
        if (isClosing) {
            onComplete(InboundLivenessSample(
                inboundBytes = 0L,
                roleBytes = InboundRoleBytes(cameraBytes = 0L, contentBytes = 0L),
            ))
            return
        }
        val pc = peerConnection
        if (pc == null) {
            onComplete(InboundLivenessSample(
                inboundBytes = 0L,
                roleBytes = InboundRoleBytes(cameraBytes = 0L, contentBytes = 0L),
            ))
            return
        }
        // Receiver track id and m-line id bound to the content role, if any.
        // Prefer track id when WebRTC reports it, but fall back to mid when
        // trackIdentifier is absent so content stats are not mis-attributed as
        // camera. Null for legacy peers and the flag-off path, so every video
        // stat falls through to the camera bucket.
        val contentTrackId = contentTransceiver?.receiver?.track()?.id()
            ?.takeIf { it.isNotEmpty() }
        val contentMid = contentTransceiver?.mid?.takeIf { it.isNotEmpty() }
        pc.getStats { report ->
            var bytes = 0L
            var cameraBytes = 0L
            var contentBytes = 0L
            for (stat in report.statsMap.values) {
                if (stat.type != "inbound-rtp") continue
                val statBytes = memberLong(stat, "bytesReceived") ?: 0L
                bytes += statBytes
                if (getMediaKind(stat) != "video") continue
                val trackId = memberString(stat, "trackIdentifier")
                val mid = memberString(stat, "mid")
                val matchesContentTrack = contentTrackId != null && trackId == contentTrackId
                val matchesContentMid = trackId == null && contentMid != null && mid == contentMid
                if (matchesContentTrack || matchesContentMid) {
                    contentBytes += statBytes
                } else {
                    cameraBytes += statBytes
                }
            }
            onComplete(InboundLivenessSample(
                inboundBytes = bytes,
                roleBytes = InboundRoleBytes(cameraBytes = cameraBytes, contentBytes = contentBytes),
            ))
        }
    }

    override fun collectInboundBytes(onComplete: (Long) -> Unit) {
        collectInboundLiveness { sample ->
            onComplete(sample.inboundBytes)
        }
    }

    override fun collectInboundRoleBytes(onComplete: (InboundRoleBytes) -> Unit) {
        collectInboundLiveness { sample ->
            onComplete(sample.roleBytes)
        }
    }

    override fun collectOutboundMediaSample(onComplete: (OutboundMediaSample?) -> Unit) {
        if (isClosing) {
            onComplete(null)
            return
        }
        val pc = peerConnection
        if (pc == null) {
            onComplete(null)
            return
        }

        val expectsAudio = pc.senders.any { sender ->
            val track = sender.track()
            track?.kind() == MediaStreamTrack.AUDIO_TRACK_KIND && track.enabled()
        }
        val expectsVideo = pc.senders.any { sender ->
            val track = sender.track()
            track?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND && track.enabled()
        }

        pc.getStats { report ->
            var audioBytesSent = 0L
            var videoBytesSent = 0L
            var videoFramesSent = 0L
            for (stat in report.statsMap.values) {
                if (stat.type != "outbound-rtp") continue
                when (getMediaKind(stat)) {
                    "audio" -> audioBytesSent += memberLong(stat, "bytesSent") ?: 0L
                    "video" -> {
                        videoBytesSent += memberLong(stat, "bytesSent") ?: 0L
                        videoFramesSent += memberLong(stat, "framesSent")
                            ?: memberLong(stat, "framesEncoded")
                            ?: 0L
                    }
                }
            }
            onComplete(
                OutboundMediaSample(
                    expectsAudio = expectsAudio,
                    expectsVideo = expectsVideo,
                    audioBytesSent = audioBytesSent,
                    videoBytesSent = videoBytesSent,
                    videoFramesSent = videoFramesSent,
                )
            )
        }
    }

    override fun collectAudioLevels(onComplete: (inboundLevel: Float?, mediaSourceLevel: Float?) -> Unit) {
        if (isClosing) {
            onComplete(null, null)
            return
        }
        val pc = peerConnection
        if (pc == null) {
            onComplete(null, null)
            return
        }
        pc.getStats { report ->
            var inbound: Float? = null
            var mediaSource: Float? = null
            report.statsMap.values.forEach { stat ->
                when (stat.type) {
                    "inbound-rtp" -> if (getMediaKind(stat) == "audio") {
                        memberDouble(stat, "audioLevel")?.let { inbound = it.toFloat().coerceIn(0f, 1f) }
                    }
                    "media-source" -> if (getMediaKind(stat) == "audio") {
                        memberDouble(stat, "audioLevel")?.let { mediaSource = it.toFloat().coerceIn(0f, 1f) }
                    }
                }
            }
            onComplete(inbound, mediaSource)
        }
    }

    override fun isReady(): Boolean = !isClosing && peerConnection != null

    override fun isPathDirect(): Boolean? = lastPathIsDirect

    override fun getConnectionState(): PeerConnection.PeerConnectionState =
        if (isClosing) PeerConnection.PeerConnectionState.CLOSED else peerConnection?.connectionState() ?: PeerConnection.PeerConnectionState.NEW

    override fun getIceConnectionState(): PeerConnection.IceConnectionState =
        if (isClosing) PeerConnection.IceConnectionState.CLOSED else peerConnection?.iceConnectionState() ?: PeerConnection.IceConnectionState.NEW

    override fun getSignalingState(): PeerConnection.SignalingState =
        if (isClosing) PeerConnection.SignalingState.CLOSED else peerConnection?.signalingState() ?: PeerConnection.SignalingState.STABLE

    override fun hasRemoteDescription(): Boolean = !isClosing && (remoteDescriptionSet || peerConnection?.remoteDescription != null)

    override fun isRemoteVideoTrackEnabled(): Boolean {
        // Do not call VideoTrack.enabled() here. It crosses into native WebRTC
        // and can block the main-thread participant refresh path.
        if (remoteVideoTrack == null) return false
        return !remoteBlackFrameAnalyzer.isVideoConsideredOff()
    }

    override fun duckPlayback(ducked: Boolean) {
        playbackDucked = ducked
        val pc = peerConnection ?: return
        for (receiver in pc.receivers) {
            val track = receiver.track()
            if (track is AudioTrack) {
                track.setVolume(if (ducked) 0.15 else 1.0)
            }
        }
    }

    override fun applyVideoSenderParameters(policy: WebRtcEngine.VideoSenderPolicy) {
        if (isClosing) return
        val pc = peerConnection ?: return
        if (supportsIndependentContentVideo) {
            // Camera and content ride separate senders with separate profiles.
            // The camera policy applies to the camera sender only; the content
            // sender keeps its own conservative content profile.
            cameraTransceiver?.sender?.let { applySenderParameters(it, policy) }
            contentTransceiver?.sender?.let { applySenderParameters(it, contentSenderPolicy()) }
            return
        }
        val sender = pc.senders.firstOrNull { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND } ?: return
        applySenderParameters(sender, policy)
    }

    private fun applySenderParameters(sender: RtpSender, policy: WebRtcEngine.VideoSenderPolicy) {
        try {
            val params = sender.parameters
            val encodings = params.encodings
            if (encodings.isNullOrEmpty()) return
            params.degradationPreference = policy.degradationPreference
            encodings[0].maxBitrateBps = policy.maxBitrateBps
            encodings[0].minBitrateBps = policy.minBitrateBps
            encodings[0].maxFramerate = policy.maxFramerate
            sender.setParameters(params)
        } catch (e: Exception) {
            logger?.log(SerenadaLogLevel.WARNING, "PeerConnection", "[$remoteCid] Failed to apply video sender parameters: ${e.message}")
        }
    }

    private fun isCurrentPeerConnection(pc: PeerConnection?): Boolean =
        !isClosing && pc != null && peerConnection === pc

    private fun closePeerConnectionSafely(pc: PeerConnection) {
        runCatching { pc.close() }
            .onFailure { error ->
                logger?.log(
                    SerenadaLogLevel.WARNING,
                    "PeerConnection",
                    "[$remoteCid] Failed to close peer connection: ${error.message}",
                )
            }
    }

    private fun disposePeerConnectionSafely(pc: PeerConnection, deferDispose: Boolean) {
        val dispose = Runnable {
            runCatching { pc.dispose() }
                .onFailure { error ->
                    logger?.log(
                        SerenadaLogLevel.WARNING,
                        "PeerConnection",
                        "[$remoteCid] Failed to dispose peer connection: ${error.message}",
                    )
                }
        }
        if (deferDispose) {
            peerConnectionDisposeQueue.postDelayed(dispose, DEFERRED_DISPOSE_DELAY_MS)
        } else {
            dispose.run()
        }
    }

    private fun ensureReceiveTransceivers(pc: PeerConnection) {
        if (localAudioTrack == null) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
        // Capable peers manage their own video m-lines: the owner pre-creates the
        // ordered camera+content transceivers, and the answerer materializes them
        // from the remote offer. Adding a generic recvonly video transceiver here
        // would create an unbound extra m-line, so it is skipped for them.
        if (supportsIndependentContentVideo) return
        if (videoReceiveEnabled && localVideoTrack == null) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
    }

    private fun flushPendingIceCandidates() {
        val pc = peerConnection ?: return
        if (pendingIceCandidates.isEmpty()) return
        val pending = pendingIceCandidates.toList()
        pendingIceCandidates.clear()
        pending.forEach { pc.addIceCandidate(it) }
    }

    @Synchronized
    private fun buildWebRtcStatsSummary(report: RTCStatsReport): String {
        val stats = report.statsMap.values
        val statsById = report.statsMap
        val pc = peerConnection

        val selectedPair = stats.firstOrNull { stat ->
            stat.type == "candidate-pair" && memberBoolean(stat, "selected") == true
        } ?: stats.firstOrNull { stat ->
            stat.type == "candidate-pair" &&
                memberBoolean(stat, "nominated") == true &&
                memberString(stat, "state") == "succeeded"
        } ?: stats.firstOrNull { stat ->
            stat.type == "candidate-pair" && memberString(stat, "state") == "succeeded"
        }

        val outboundVideo = stats
            .filter { it.type == "outbound-rtp" && getMediaKind(it) == "video" }
            .maxByOrNull { memberLong(it, "bytesSent") ?: -1L }
        val inboundVideo = stats
            .filter { it.type == "inbound-rtp" && getMediaKind(it) == "video" }
            .maxByOrNull { memberLong(it, "bytesReceived") ?: -1L }
        val remoteInboundVideo = stats
            .filter { it.type == "remote-inbound-rtp" && getMediaKind(it) == "video" }
            .maxByOrNull { memberLong(it, "packetsReceived") ?: -1L }

        val pairRttMs = memberDouble(selectedPair, "currentRoundTripTime")?.times(1000.0)
            ?: memberDouble(remoteInboundVideo, "roundTripTime")?.times(1000.0)
        val outboundKbps = memberDouble(selectedPair, "availableOutgoingBitrate")?.div(1000.0)
        val inboundKbps = memberDouble(selectedPair, "availableIncomingBitrate")?.div(1000.0)
        val inboundJitterMs = memberDouble(inboundVideo, "jitter")?.times(1000.0)
        val outboundFps = memberDouble(outboundVideo, "framesPerSecond")
        val inboundFps = memberDouble(inboundVideo, "framesPerSecond")
        val framesEncoded = memberLong(outboundVideo, "framesEncoded")
        val framesDecoded = memberLong(inboundVideo, "framesDecoded")
        val framesDropped = memberLong(inboundVideo, "framesDropped")
        val packetsLost = memberLong(inboundVideo, "packetsLost")
        val qualityLimitationReason = memberString(outboundVideo, "qualityLimitationReason")
        val outCodec = resolveCodecName(outboundVideo, statsById)
        val inCodec = resolveCodecName(inboundVideo, statsById)

        return buildString {
            append("remote=")
            append(remoteCid)
            append(",conn=")
            append(pc?.connectionState()?.name ?: "NA")
            append(",ice=")
            append(pc?.iceConnectionState()?.name ?: "NA")
            append(",rttMs=")
            append(formatNumber(pairRttMs, 0))
            append(",outKbps=")
            append(formatNumber(outboundKbps, 1))
            append(",inKbps=")
            append(formatNumber(inboundKbps, 1))
            append(",outFps=")
            append(formatNumber(outboundFps, 1))
            append(",inFps=")
            append(formatNumber(inboundFps, 1))
            append(",encFrames=")
            append(framesEncoded ?: "n/a")
            append(",decFrames=")
            append(framesDecoded ?: "n/a")
            append(",dropFrames=")
            append(framesDropped ?: "n/a")
            append(",lostPkts=")
            append(packetsLost ?: "n/a")
            append(",jitterMs=")
            append(formatNumber(inboundJitterMs, 1))
            append(",outCodec=")
            append(outCodec ?: "n/a")
            append(",inCodec=")
            append(inCodec ?: "n/a")
            append(",qualityLimit=")
            append(qualityLimitationReason ?: "n/a")
        }
    }

    @Synchronized
    private fun buildRealtimeCallStats(report: RTCStatsReport): RealtimeCallStats {
        val stats = report.statsMap.values
        val statsById = report.statsMap
        val audio = MediaTotals()
        val video = MediaTotals()

        var selectedCandidatePair: RTCStats? = null
        var fallbackCandidatePair: RTCStats? = null
        var remoteInboundRttSumSeconds = 0.0
        var remoteInboundRttCount = 0

        stats.forEach { stat ->
            if (stat.type == "candidate-pair") {
                val isSelected = memberBoolean(stat, "selected") == true
                val isNominated = memberBoolean(stat, "nominated") == true
                val pairState = memberString(stat, "state")
                if (isSelected) {
                    selectedCandidatePair = stat
                } else if (fallbackCandidatePair == null && isNominated && pairState == "succeeded") {
                    fallbackCandidatePair = stat
                }
                return@forEach
            }

            val kind = getMediaKind(stat) ?: return@forEach
            val bucket = if (kind == "audio") audio else video

            when (stat.type) {
                "inbound-rtp" -> {
                    bucket.inboundRtpCount += 1
                    memberLong(stat, "packetsReceived")?.let { bucket.inboundPacketsReceived += it; bucket.sawPacketsReceived = true }
                    memberLong(stat, "packetsLost")?.let { bucket.inboundPacketsLost += max(0L, it); bucket.sawPacketsLost = true }
                    bucket.inboundBytes += memberLong(stat, "bytesReceived") ?: 0L

                    val jitter = memberDouble(stat, "jitter")
                    if (jitter != null) {
                        bucket.inboundJitterSumSeconds += jitter
                        bucket.inboundJitterCount += 1
                    }

                    bucket.inboundJitterBufferDelaySeconds += memberDouble(stat, "jitterBufferDelay") ?: 0.0
                    bucket.inboundJitterBufferEmittedCount += memberLong(stat, "jitterBufferEmittedCount") ?: 0L
                    bucket.inboundConcealedSamples += memberLong(stat, "concealedSamples") ?: 0L
                    bucket.inboundTotalSamples += memberLong(stat, "totalSamplesReceived") ?: 0L

                    val fps = memberDouble(stat, "framesPerSecond")
                    if (fps != null) {
                        bucket.inboundFpsSum += fps
                        bucket.inboundFpsCount += 1
                    }

                    val frameWidth = (memberLong(stat, "frameWidth") ?: 0L).toInt()
                    val frameHeight = (memberLong(stat, "frameHeight") ?: 0L).toInt()
                    bucket.inboundFrameWidth = max(bucket.inboundFrameWidth, frameWidth)
                    bucket.inboundFrameHeight = max(bucket.inboundFrameHeight, frameHeight)

                    memberLong(stat, "framesDecoded")?.let { bucket.inboundFramesDecoded += it; bucket.sawFramesDecoded = true }
                    memberLong(stat, "framesDropped")?.let { bucket.inboundFramesDropped += it; bucket.sawFramesDropped = true }
                    bucket.inboundFreezeCount += memberLong(stat, "freezeCount") ?: 0L
                    bucket.inboundFreezeDurationSeconds += memberDouble(stat, "totalFreezesDuration") ?: 0.0
                    bucket.inboundNackCount += memberLong(stat, "nackCount") ?: 0L
                    bucket.inboundPliCount += memberLong(stat, "pliCount") ?: 0L
                    bucket.inboundFirCount += memberLong(stat, "firCount") ?: 0L
                }

                "outbound-rtp" -> {
                    bucket.outboundPacketsSent += memberLong(stat, "packetsSent") ?: 0L
                    bucket.outboundBytes += memberLong(stat, "bytesSent") ?: 0L
                    bucket.outboundPacketsRetransmitted += memberLong(stat, "retransmittedPacketsSent") ?: 0L
                }

                "remote-inbound-rtp" -> {
                    bucket.remoteInboundPacketsLost += max(0L, memberLong(stat, "packetsLost") ?: 0L)
                    val remoteRtt = memberDouble(stat, "roundTripTime")
                    if (remoteRtt != null) {
                        remoteInboundRttSumSeconds += remoteRtt
                        remoteInboundRttCount += 1
                    }
                }
            }
        }

        val selectedPair = selectedCandidatePair ?: fallbackCandidatePair
        val localCandidate = selectedPair?.let { pair ->
            val id = memberString(pair, "localCandidateId")
            if (id.isNullOrBlank()) null else statsById[id]
        }
        val remoteCandidate = selectedPair?.let { pair ->
            val id = memberString(pair, "remoteCandidateId")
            if (id.isNullOrBlank()) null else statsById[id]
        }

        val localCandidateType = memberString(localCandidate, "candidateType")
        val remoteCandidateType = memberString(remoteCandidate, "candidateType")
        val localProtocol = memberString(localCandidate, "protocol")
        val remoteProtocol = memberString(remoteCandidate, "protocol")
        val isRelay = localCandidateType == "relay" || remoteCandidateType == "relay"
        // Cache for isPathDirect(): null stays null while candidate types are
        // unknown so the TURN refresh gate errs on the side of refreshing.
        if (localCandidateType != null || remoteCandidateType != null) {
            val direct = !isRelay
            if (lastPathIsDirect != direct) lastPathIsDirect = direct
        }
        val transportPath =
            if (localCandidateType != null || remoteCandidateType != null) {
                "${if (isRelay) "TURN relay" else "Direct"} (${localCandidateType ?: "n/a"} -> ${remoteCandidateType ?: "n/a"}, ${localProtocol ?: remoteProtocol ?: "n/a"})"
            } else {
                null
            }

        val candidateRttSeconds = memberDouble(selectedPair, "currentRoundTripTime")
        val remoteInboundRttSeconds =
            if (remoteInboundRttCount > 0) {
                remoteInboundRttSumSeconds / remoteInboundRttCount
            } else {
                null
            }
        val chosenRttSeconds = candidateRttSeconds ?: remoteInboundRttSeconds
        val rttMs = chosenRttSeconds?.times(1000.0)
        val availableOutgoingKbps = memberDouble(selectedPair, "availableOutgoingBitrate")?.div(1000.0)

        val now = System.currentTimeMillis()
        val previousSample = lastRealtimeStatsSample
        val elapsedSeconds =
            if (previousSample != null) {
                (now - previousSample.timestampMs) / 1000.0
            } else {
                0.0
            }

        val audioRxKbps = previousSample?.let {
            calculateBitrateKbps(it.audioRxBytes, audio.inboundBytes, elapsedSeconds)
        }
        val audioTxKbps = previousSample?.let {
            calculateBitrateKbps(it.audioTxBytes, audio.outboundBytes, elapsedSeconds)
        }
        val videoRxKbps = previousSample?.let {
            calculateBitrateKbps(it.videoRxBytes, video.inboundBytes, elapsedSeconds)
        }
        val videoTxKbps = previousSample?.let {
            calculateBitrateKbps(it.videoTxBytes, video.outboundBytes, elapsedSeconds)
        }

        val videoFps =
            if (video.inboundFpsCount > 0) {
                video.inboundFpsSum / video.inboundFpsCount
            } else if (
                previousSample != null &&
                elapsedSeconds > 0.0 &&
                video.inboundFramesDecoded >= previousSample.videoFramesDecoded
            ) {
                (video.inboundFramesDecoded - previousSample.videoFramesDecoded) / elapsedSeconds
            } else {
                null
            }

        freezeSamples.add(
            FreezeSample(
                timestampMs = now,
                freezeCount = video.inboundFreezeCount,
                freezeDurationSeconds = video.inboundFreezeDurationSeconds
            )
        )
        freezeSamples.removeAll { sample -> now - sample.timestampMs > 60_000L }
        val freezeWindowBase = freezeSamples.firstOrNull()
        val videoFreezeCount60s =
            freezeWindowBase?.let { base ->
                max(0L, video.inboundFreezeCount - base.freezeCount)
            }
        val videoFreezeDuration60s =
            freezeWindowBase?.let { base ->
                max(0.0, video.inboundFreezeDurationSeconds - base.freezeDurationSeconds)
            }

        val audioRxPacketLossPct =
            ratioPercent(
                numerator = audio.inboundPacketsLost,
                denominator = audio.inboundPacketsReceived + audio.inboundPacketsLost
            )
        val audioTxPacketLossPct =
            ratioPercent(
                numerator = audio.remoteInboundPacketsLost,
                denominator = audio.outboundPacketsSent + audio.remoteInboundPacketsLost
            )
        val videoRxPacketLossPct =
            ratioPercent(
                numerator = video.inboundPacketsLost,
                denominator = video.inboundPacketsReceived + video.inboundPacketsLost
            )
        val videoTxPacketLossPct =
            ratioPercent(
                numerator = video.remoteInboundPacketsLost,
                denominator = video.outboundPacketsSent + video.remoteInboundPacketsLost
            )

        val audioJitterMs =
            if (audio.inboundJitterCount > 0) {
                (audio.inboundJitterSumSeconds / audio.inboundJitterCount) * 1000.0
            } else {
                null
            }
        val audioPlayoutDelayMs =
            if (audio.inboundJitterBufferEmittedCount > 0) {
                (audio.inboundJitterBufferDelaySeconds / audio.inboundJitterBufferEmittedCount) * 1000.0
            } else {
                null
            }
        val audioConcealedPct =
            ratioPercent(
                numerator = audio.inboundConcealedSamples,
                denominator = audio.inboundConcealedSamples + audio.inboundTotalSamples
            )
        val videoRetransmitPct =
            ratioPercent(
                numerator = video.outboundPacketsRetransmitted,
                denominator = video.outboundPacketsSent
            )

        val videoNackPerMin = previousSample?.let {
            positiveRatePerMinute(video.inboundNackCount, it.videoNackCount, elapsedSeconds)
        }
        val videoPliPerMin = previousSample?.let {
            positiveRatePerMinute(video.inboundPliCount, it.videoPliCount, elapsedSeconds)
        }
        val videoFirPerMin = previousSample?.let {
            positiveRatePerMinute(video.inboundFirCount, it.videoFirCount, elapsedSeconds)
        }

        val videoResolution =
            if (video.inboundFrameWidth > 0 && video.inboundFrameHeight > 0) {
                "${video.inboundFrameWidth}x${video.inboundFrameHeight}"
            } else {
                null
            }

        lastRealtimeStatsSample =
            RealtimeStatsSample(
                timestampMs = now,
                audioRxBytes = audio.inboundBytes,
                audioTxBytes = audio.outboundBytes,
                videoRxBytes = video.inboundBytes,
                videoTxBytes = video.outboundBytes,
                videoFramesDecoded = video.inboundFramesDecoded,
                videoNackCount = video.inboundNackCount,
                videoPliCount = video.inboundPliCount,
                videoFirCount = video.inboundFirCount
            )

        return RealtimeCallStats(
            transportPath = transportPath,
            rttMs = rttMs,
            availableOutgoingKbps = availableOutgoingKbps,
            audioRxPacketLossPct = audioRxPacketLossPct,
            audioTxPacketLossPct = audioTxPacketLossPct,
            audioJitterMs = audioJitterMs,
            audioPlayoutDelayMs = audioPlayoutDelayMs,
            audioConcealedPct = audioConcealedPct,
            audioRxKbps = audioRxKbps,
            audioTxKbps = audioTxKbps,
            videoRxPacketLossPct = videoRxPacketLossPct,
            videoTxPacketLossPct = videoTxPacketLossPct,
            videoRxKbps = videoRxKbps,
            videoTxKbps = videoTxKbps,
            videoFps = videoFps,
            videoResolution = videoResolution,
            videoFreezeCount60s = videoFreezeCount60s,
            videoFreezeDuration60s = videoFreezeDuration60s,
            videoRetransmitPct = videoRetransmitPct,
            videoNackPerMin = videoNackPerMin,
            videoPliPerMin = videoPliPerMin,
            videoFirPerMin = videoFirPerMin,
            // Null (unknown) when the specific counter
            // member was never present, never a fake 0. Per-field presence,
            // not just per-kind.
            videoFramesDecoded = if (video.sawFramesDecoded) video.inboundFramesDecoded else null,
            videoFramesDropped = if (video.sawFramesDropped) video.inboundFramesDropped else null,
            audioPacketsLost = if (audio.sawPacketsLost) audio.inboundPacketsLost else null,
            audioPacketsReceived = if (audio.sawPacketsReceived) audio.inboundPacketsReceived else null,
            updatedAtMs = now,
        )
    }

    private fun calculateBitrateKbps(
        previousBytes: Long,
        currentBytes: Long,
        elapsedSeconds: Double
    ): Double? {
        if (elapsedSeconds <= 0.0 || currentBytes < previousBytes) return null
        val bits = (currentBytes - previousBytes) * 8.0
        return bits / elapsedSeconds / 1000.0
    }

    private fun positiveRatePerMinute(
        currentValue: Long,
        previousValue: Long,
        elapsedSeconds: Double
    ): Double? {
        if (elapsedSeconds <= 0.0 || currentValue < previousValue) return null
        return ((currentValue - previousValue) / elapsedSeconds) * 60.0
    }

    private fun ratioPercent(numerator: Long, denominator: Long): Double? {
        if (denominator <= 0L) return null
        return (numerator.toDouble() / denominator.toDouble()) * 100.0
    }

    private fun resolveCodecName(rtpStat: RTCStats?, statsById: Map<String, RTCStats>): String? {
        val codecId = memberString(rtpStat, "codecId") ?: return null
        val codecStat = statsById[codecId] ?: return null
        val mimeType = memberString(codecStat, "mimeType") ?: return null
        return mimeType.removePrefix("video/")
    }

}

internal class PeerConnectionDisposeQueue(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val pending = LinkedHashSet<Runnable>()
    private val flushThread = HandlerThread("serenada-pc-dispose").apply { start() }
    private val flushHandler = Handler(flushThread.looper)
    @Volatile private var isShutdown = false

    @Synchronized
    fun postDelayed(dispose: Runnable, delayMs: Long) {
        if (isShutdown) {
            dispose.run()
            return
        }
        lateinit var wrapper: Runnable
        wrapper = Runnable {
            synchronized(this) {
                pending.remove(wrapper)
            }
            dispose.run()
        }
        pending.add(wrapper)
        handler.postDelayed(wrapper, delayMs)
    }

    fun flush(shutdownAfterDrain: Boolean = false, onDrained: (() -> Unit)? = null) {
        val runnables = synchronized(this) {
            pending.toList().also { pending.clear() }
        }
        if (runnables.isEmpty()) {
            onDrained?.invoke()
            if (shutdownAfterDrain) shutdown()
            return
        }
        val remaining = AtomicInteger(runnables.size)
        for (runnable in runnables) {
            handler.removeCallbacks(runnable)
            flushHandler.post {
                runnable.run()
                if (remaining.decrementAndGet() == 0) {
                    onDrained?.invoke()
                    if (shutdownAfterDrain) shutdown()
                }
            }
        }
    }

    private fun shutdown() {
        if (isShutdown) return
        isShutdown = true
        flushThread.quitSafely()
    }
}
