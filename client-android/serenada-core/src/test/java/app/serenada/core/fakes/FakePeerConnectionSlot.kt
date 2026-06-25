package app.serenada.core.fakes

import app.serenada.core.call.InboundRoleBytes
import app.serenada.core.call.InboundLivenessSample
import app.serenada.core.call.OutboundMediaSample
import app.serenada.core.call.PeerConnectionSlotProtocol
import app.serenada.core.call.RealtimeCallStats
import app.serenada.core.call.WebRtcEngine
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

internal class FakePeerConnectionSlot(
    override val remoteCid: String,
    override val supportsIndependentContentVideo: Boolean = false,
    private val onLocalIceCandidate: ((String, IceCandidate) -> Unit)? = null,
    private val onConnectionStateChange: ((String, PeerConnection.PeerConnectionState) -> Unit)? = null,
    private val onIceConnectionStateChange: ((String, PeerConnection.IceConnectionState) -> Unit)? = null,
    private val onSignalingStateChange: ((String, PeerConnection.SignalingState) -> Unit)? = null,
    private val onRenegotiationNeeded: ((String) -> Unit)? = null,
) : PeerConnectionSlotProtocol {

    // State
    override var sentOffer = false; private set
    override var isMakingOffer = false; private set
    override var pendingIceRestart = false; private set
    override var lastIceRestartAt = 0L; private set
    override var offerTimeoutTask: Runnable? = null; private set
    override var iceRestartTask: Runnable? = null; private set

    // State machine
    private var signalingState = PeerConnection.SignalingState.STABLE
    private var connectionState = PeerConnection.PeerConnectionState.NEW
    private var iceConnectionState = PeerConnection.IceConnectionState.NEW
    private var remoteDescriptionSet = false

    // Call tracking
    var createOfferCalls = 0; private set
    val createOfferIceRestartFlags = mutableListOf<Boolean>()
    var createAnswerCalls = 0; private set
    val setRemoteDescriptionCalls = mutableListOf<Pair<SessionDescription.Type, String>>()
    val addedIceCandidates = mutableListOf<IceCandidate>()
    val appliedIceServerUrls = mutableListOf<List<String>>()
    var rollbackCalls = 0; private set
    var closePeerConnectionCalled = false; private set
    var closePeerConnectionDeferredDispose = false; private set
    var ensurePeerConnectionCalls = 0; private set
    var failNextRemoteOffer = false
    var failNextRemoteAnswer = false
    var failNextAnswer = false
    var failNextRollback = false
    var deferNextOfferSdp = false
    private var pendingOfferSdp: (() -> Unit)? = null
    var deferNextAnswerSdp = false
    private var pendingAnswerSdp: (() -> Unit)? = null

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

    // WebRTC operations
    override fun setIceServers(servers: List<PeerConnection.IceServer>) {
        appliedIceServerUrls += servers.map { it.urls }
    }
    override fun ensurePeerConnection(): Boolean { ensurePeerConnectionCalls++; return true }

    // Per-track attach tracking so tests can assert camera vs content lifecycle.
    data class AttachLocalTracksCall(
        val hasAudio: Boolean,
        val hasCamera: Boolean,
        val hasContent: Boolean,
        val supportsIndependentContentVideo: Boolean,
    )
    val attachLocalTracksCalls = mutableListOf<AttachLocalTracksCall>()
    var lastCameraTrackAttached: VideoTrack? = null; private set
    var lastContentTrackAttached: VideoTrack? = null; private set

    override fun attachLocalTracks(
        audioTrack: AudioTrack?,
        cameraTrack: VideoTrack?,
        contentTrack: VideoTrack?,
        supportsIndependentContentVideo: Boolean,
    ) {
        lastCameraTrackAttached = cameraTrack
        lastContentTrackAttached = contentTrack
        attachLocalTracksCalls += AttachLocalTracksCall(
            hasAudio = audioTrack != null,
            hasCamera = cameraTrack != null,
            hasContent = contentTrack != null,
            supportsIndependentContentVideo = supportsIndependentContentVideo,
        )
    }

    // --- Independent content attach modeling (capable peers) ---
    // A real WebRTC VideoTrack cannot be constructed in a unit test, so the
    // content sender is modeled by a boolean rather than the track object.
    // [contentAttachedToSender] is true while this peer's content sender carries
    // the active share. Lets tests assert per-peer attach-failure ISOLATION (a
    // healthy peer carries the share while a failing one does not).
    var contentAttachedToSender = false; private set

    /**
     * Inject a content setTrack REJECTION for the next [simulateContentAttach]
     * (capable peer). Mirrors the real slot's `replaceContentTrackWithFallback`
     * reject path: the content does NOT land on the sender and the slot fires
     * [onRenegotiationNeeded] for itself (durable retry — the engine keeps the
     * content track set, so a later re-attach fills the sender). Consumed once.
     */
    var failNextContentAttach = false
    var renegotiationRequestedCount = 0; private set

    /**
     * Drive this slot's modeled content sender the way the real
     * `WebRtcEngine.attachLocalTracksToSlot` → `PeerConnectionSlot` reconcile
     * does. [attach] true = the engine is supplying the active content track
     * (start / re-attach); false = detach (stop). A pending [failNextContentAttach]
     * turns an attach into a reject (sender stays empty, renegotiation fires for
     * this peer only). Detach always succeeds.
     */
    fun simulateContentAttach(attach: Boolean) {
        if (!attach) {
            contentAttachedToSender = false
            return
        }
        if (failNextContentAttach) {
            failNextContentAttach = false
            renegotiationRequestedCount += 1
            onRenegotiationNeeded?.invoke(remoteCid)
            return
        }
        contentAttachedToSender = true
    }
    override fun setAudioTrack(track: AudioTrack?) {}
    override fun closePeerConnection(deferDispose: Boolean) {
        closePeerConnectionCalled = true
        closePeerConnectionDeferredDispose = deferDispose
    }
    override fun duckPlayback(ducked: Boolean) {}

    override fun createOffer(iceRestart: Boolean, onSdp: (String) -> Unit, onComplete: ((Boolean) -> Unit)?): Boolean {
        createOfferCalls++
        createOfferIceRestartFlags += iceRestart
        if (signalingState != PeerConnection.SignalingState.STABLE) {
            onComplete?.invoke(false)
            return false
        }
        signalingState = PeerConnection.SignalingState.HAVE_LOCAL_OFFER
        onSignalingStateChange?.invoke(remoteCid, signalingState)
        val complete: () -> Unit = {
            onSdp("fake-offer-sdp")
            onComplete?.invoke(true)
        }
        if (deferNextOfferSdp) {
            deferNextOfferSdp = false
            pendingOfferSdp = complete
        } else {
            complete()
        }
        return true
    }

    fun flushPendingOfferSdp() {
        pendingOfferSdp?.invoke()
        pendingOfferSdp = null
    }

    override fun createAnswer(onSdp: (String) -> Unit, onComplete: ((Boolean) -> Unit)?) {
        createAnswerCalls++
        if (failNextAnswer) {
            failNextAnswer = false
            onComplete?.invoke(false)
            return
        }
        val complete: () -> Unit = {
            onSdp("fake-answer-sdp")
            signalingState = PeerConnection.SignalingState.STABLE
            onSignalingStateChange?.invoke(remoteCid, signalingState)
            onComplete?.invoke(true)
        }
        if (deferNextAnswerSdp) {
            deferNextAnswerSdp = false
            pendingAnswerSdp = complete
        } else {
            complete()
        }
    }

    fun flushPendingAnswerSdp() {
        pendingAnswerSdp?.invoke()
        pendingAnswerSdp = null
    }

    override fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onComplete: ((Boolean) -> Unit)?) {
        setRemoteDescriptionCalls.add(type to sdp)
        if (type == SessionDescription.Type.OFFER && failNextRemoteOffer) {
            failNextRemoteOffer = false
            onComplete?.invoke(false)
            return
        }
        if (type == SessionDescription.Type.ANSWER && failNextRemoteAnswer) {
            failNextRemoteAnswer = false
            onComplete?.invoke(false)
            return
        }
        remoteDescriptionSet = true
        when (type) {
            SessionDescription.Type.OFFER -> signalingState = PeerConnection.SignalingState.HAVE_REMOTE_OFFER
            SessionDescription.Type.ANSWER -> signalingState = PeerConnection.SignalingState.STABLE
            else -> signalingState = PeerConnection.SignalingState.STABLE
        }
        onSignalingStateChange?.invoke(remoteCid, signalingState)
        onComplete?.invoke(true)
    }

    override fun rollbackLocalDescription(onComplete: ((Boolean) -> Unit)?) {
        rollbackCalls++
        if (failNextRollback) {
            failNextRollback = false
            onComplete?.invoke(false)
            return
        }
        signalingState = PeerConnection.SignalingState.STABLE
        onSignalingStateChange?.invoke(remoteCid, signalingState)
        onComplete?.invoke(true)
    }

    override fun addIceCandidate(candidate: IceCandidate) { addedIceCandidates.add(candidate) }

    // State queries
    override fun isReady(): Boolean = true
    override fun isPathDirect(): Boolean? = pathDirectOverride
    override fun getConnectionState(): PeerConnection.PeerConnectionState = connectionState
    override fun getIceConnectionState(): PeerConnection.IceConnectionState = iceConnectionState
    override fun getSignalingState(): PeerConnection.SignalingState = signalingState
    override fun hasRemoteDescription(): Boolean = remoteDescriptionSet
    var remoteVideoTrackEnabledOverride = false
    override fun isRemoteVideoTrackEnabled(): Boolean = remoteVideoTrackEnabledOverride

    var pathDirectOverride: Boolean? = null

    // Renderer/stats stubs
    val attachRemoteSinkCalls = mutableListOf<VideoSink>()
    val detachRemoteSinkCalls = mutableListOf<VideoSink>()
    override fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {}
    override fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {}
    override fun attachRemoteSink(sink: VideoSink) {
        attachRemoteSinkCalls += sink
    }
    override fun detachRemoteSink(sink: VideoSink) {
        detachRemoteSinkCalls += sink
    }
    val attachRemoteContentSinkCalls = mutableListOf<VideoSink>()
    val detachRemoteContentSinkCalls = mutableListOf<VideoSink>()
    override fun attachRemoteContentSink(sink: VideoSink) {
        attachRemoteContentSinkCalls += sink
    }
    override fun detachRemoteContentSink(sink: VideoSink) {
        detachRemoteContentSinkCalls += sink
    }
    /**
     * Stats returned from the next `collectWebRtcStats()` call. Tests can set
     * this to drive the StatsPoller merge + CallQualityTracker. Defaults to
     * null (no stats), matching the previous behavior.
     */
    var realtimeStatsSample: RealtimeCallStats? = null
    var collectWebRtcStatsCalls = 0
    override fun collectWebRtcStats(onComplete: (String, RealtimeCallStats?) -> Unit) {
        collectWebRtcStatsCalls += 1
        onComplete("fake", realtimeStatsSample)
    }
    /** Cumulative inbound bytes returned from the next `collectInboundBytes()` call. */
    var inboundBytesSample: Long = 0L
    var collectInboundLivenessCalls = 0
    override fun collectInboundLiveness(onComplete: (InboundLivenessSample) -> Unit) {
        collectInboundLivenessCalls += 1
        onComplete(InboundLivenessSample(
            inboundBytes = inboundBytesSample,
            roleBytes = inboundRoleBytesSample,
        ))
    }

    var collectInboundBytesCalls = 0
    override fun collectInboundBytes(onComplete: (Long) -> Unit) {
        collectInboundBytesCalls += 1
        onComplete(inboundBytesSample)
    }

    /**
     * Cumulative per-role inbound VIDEO bytes returned from the next
     * `collectInboundRoleBytes()`. Tests set this to drive the session's per-role
     * liveness derivation (camera/content receiving). The real slot computes this
     * split by matching inbound-rtp `trackIdentifier` to the bound content
     * receiver track; that path is exercised on-device.
     */
    var inboundRoleBytesSample: InboundRoleBytes = InboundRoleBytes(cameraBytes = 0L, contentBytes = 0L)
    var collectInboundRoleBytesCalls = 0
    override fun collectInboundRoleBytes(onComplete: (InboundRoleBytes) -> Unit) {
        collectInboundRoleBytesCalls += 1
        onComplete(inboundRoleBytesSample)
    }
    var outboundMediaSample: OutboundMediaSample? = OutboundMediaSample(
        expectsAudio = true,
        expectsVideo = true,
        audioBytesSent = 0L,
        videoBytesSent = 0L,
        videoFramesSent = 0L,
    )
    var collectOutboundMediaSampleCalls = 0
    override fun collectOutboundMediaSample(onComplete: (OutboundMediaSample?) -> Unit) {
        collectOutboundMediaSampleCalls += 1
        onComplete(outboundMediaSample)
    }
    override fun collectAudioLevels(onComplete: (inboundLevel: Float?, mediaSourceLevel: Float?) -> Unit) { onComplete(null, null) }

    /**
     * Video sender policies applied to this slot, in order. Lets tests assert
     * which encoding profile a slot received (e.g. a legacy slot carrying the
     * content track during an independent share should get the content profile,
     * FIX 2). The last entry is the currently-applied policy.
     */
    val appliedVideoSenderPolicies = mutableListOf<WebRtcEngine.VideoSenderPolicy>()
    val lastAppliedVideoSenderPolicy: WebRtcEngine.VideoSenderPolicy?
        get() = appliedVideoSenderPolicies.lastOrNull()
    override fun applyVideoSenderParameters(policy: WebRtcEngine.VideoSenderPolicy) {
        appliedVideoSenderPolicies += policy
    }

    // Test drivers
    fun simulateConnectionStateChange(state: PeerConnection.PeerConnectionState) {
        connectionState = state
        onConnectionStateChange?.invoke(remoteCid, state)
    }

    fun simulateIceConnectionStateChange(state: PeerConnection.IceConnectionState) {
        iceConnectionState = state
        onIceConnectionStateChange?.invoke(remoteCid, state)
    }

    fun simulateLocalIceCandidate(candidate: String = "candidate:local") {
        onLocalIceCandidate?.invoke(remoteCid, IceCandidate("0", 0, candidate))
    }

    fun simulateRenegotiationNeeded() {
        onRenegotiationNeeded?.invoke(remoteCid)
    }
}
