package app.serenada.core.fakes

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

class FakePeerConnectionSlot(
    override val remoteCid: String,
    private val onConnectionStateChange: ((String, PeerConnection.PeerConnectionState) -> Unit)? = null,
    private val onIceConnectionStateChange: ((String, PeerConnection.IceConnectionState) -> Unit)? = null,
    private val onSignalingStateChange: ((String, PeerConnection.SignalingState) -> Unit)? = null,
) : PeerConnectionSlotProtocol {

    // State
    override var sentOffer = false; private set
    override var isMakingOffer = false; private set
    override var pendingIceRestart = false; private set
    override var lastIceRestartAt = 0L; private set
    override var offerTimeoutTask: Runnable? = null; private set
    override var iceRestartTask: Runnable? = null; private set
    override var nonHostFallbackTask: Runnable? = null; private set
    override var nonHostFallbackAttempts = 0; private set

    // State machine
    private var signalingState = PeerConnection.SignalingState.STABLE
    private var connectionState = PeerConnection.PeerConnectionState.NEW
    private var iceConnectionState = PeerConnection.IceConnectionState.NEW
    private var remoteDescriptionSet = false

    // Call tracking
    var createOfferCalls = 0; private set
    var createAnswerCalls = 0; private set
    val setRemoteDescriptionCalls = mutableListOf<Pair<SessionDescription.Type, String>>()
    val addedIceCandidates = mutableListOf<IceCandidate>()
    var rollbackCalls = 0; private set
    var closePeerConnectionCalled = false; private set
    var ensurePeerConnectionCalls = 0; private set

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
    override fun setNonHostFallbackTask(task: Runnable) { nonHostFallbackTask = task }
    override fun cancelNonHostFallbackTask() { nonHostFallbackTask = null }
    override fun clearNonHostFallbackTask() { nonHostFallbackTask = null }
    override fun incrementNonHostFallbackAttempts() { nonHostFallbackAttempts++ }

    // WebRTC operations
    override fun setIceServers(servers: List<PeerConnection.IceServer>) {}
    override fun ensurePeerConnection(): Boolean { ensurePeerConnectionCalls++; return true }
    override fun attachLocalTracks(audioTrack: AudioTrack?, videoTrack: VideoTrack?) {}
    override fun closePeerConnection() { closePeerConnectionCalled = true }

    override fun createOffer(iceRestart: Boolean, onSdp: (String) -> Unit, onComplete: ((Boolean) -> Unit)?): Boolean {
        createOfferCalls++
        if (signalingState != PeerConnection.SignalingState.STABLE) {
            onComplete?.invoke(false)
            return false
        }
        signalingState = PeerConnection.SignalingState.HAVE_LOCAL_OFFER
        onSignalingStateChange?.invoke(remoteCid, signalingState)
        onSdp("fake-offer-sdp")
        onComplete?.invoke(true)
        return true
    }

    override fun createAnswer(onSdp: (String) -> Unit, onComplete: ((Boolean) -> Unit)?) {
        createAnswerCalls++
        onSdp("fake-answer-sdp")
        signalingState = PeerConnection.SignalingState.STABLE
        onSignalingStateChange?.invoke(remoteCid, signalingState)
        onComplete?.invoke(true)
    }

    override fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onComplete: (() -> Unit)?) {
        setRemoteDescriptionCalls.add(type to sdp)
        remoteDescriptionSet = true
        when (type) {
            SessionDescription.Type.OFFER -> signalingState = PeerConnection.SignalingState.HAVE_REMOTE_OFFER
            SessionDescription.Type.ANSWER -> signalingState = PeerConnection.SignalingState.STABLE
            else -> signalingState = PeerConnection.SignalingState.STABLE
        }
        onSignalingStateChange?.invoke(remoteCid, signalingState)
        onComplete?.invoke()
    }

    override fun rollbackLocalDescription(onComplete: ((Boolean) -> Unit)?) {
        rollbackCalls++
        signalingState = PeerConnection.SignalingState.STABLE
        onSignalingStateChange?.invoke(remoteCid, signalingState)
        onComplete?.invoke(true)
    }

    override fun addIceCandidate(candidate: IceCandidate) { addedIceCandidates.add(candidate) }

    // State queries
    override fun isReady(): Boolean = true
    override fun getConnectionState(): PeerConnection.PeerConnectionState = connectionState
    override fun getIceConnectionState(): PeerConnection.IceConnectionState = iceConnectionState
    override fun getSignalingState(): PeerConnection.SignalingState = signalingState
    override fun hasRemoteDescription(): Boolean = remoteDescriptionSet
    override fun isRemoteVideoTrackEnabled(): Boolean = false

    // Renderer/stats stubs
    override fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {}
    override fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {}
    override fun attachRemoteSink(sink: VideoSink) {}
    override fun detachRemoteSink(sink: VideoSink) {}
    override fun collectWebRtcStats(onComplete: (String, RealtimeCallStats?) -> Unit) { onComplete("fake", null) }
    override fun applyVideoSenderParameters(policy: WebRtcEngine.VideoSenderPolicy) {}

    // Test drivers
    fun simulateConnectionStateChange(state: PeerConnection.PeerConnectionState) {
        connectionState = state
        onConnectionStateChange?.invoke(remoteCid, state)
    }

    fun simulateIceConnectionStateChange(state: PeerConnection.IceConnectionState) {
        iceConnectionState = state
        onIceConnectionStateChange?.invoke(remoteCid, state)
    }
}
