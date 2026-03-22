package app.serenada.core.call

import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

interface PeerConnectionSlotProtocol {
    // Properties
    val remoteCid: String
    val sentOffer: Boolean
    val isMakingOffer: Boolean
    val pendingIceRestart: Boolean
    val lastIceRestartAt: Long
    val offerTimeoutTask: Runnable?
    val iceRestartTask: Runnable?
    val nonHostFallbackTask: Runnable?
    val nonHostFallbackAttempts: Int

    // Offer lifecycle
    fun beginOffer()
    fun completeOffer()
    fun markOfferSent()

    // ICE restart lifecycle
    fun markPendingIceRestart()
    fun clearPendingIceRestart()
    fun recordIceRestart(nowMs: Long)

    // Task management
    fun setOfferTimeoutTask(task: Runnable)
    fun cancelOfferTimeout()
    fun setIceRestartTask(task: Runnable)
    fun cancelIceRestartTask()
    fun setNonHostFallbackTask(task: Runnable)
    fun cancelNonHostFallbackTask()
    fun clearNonHostFallbackTask()
    fun incrementNonHostFallbackAttempts()

    // WebRTC operations
    fun setIceServers(servers: List<PeerConnection.IceServer>)
    fun ensurePeerConnection(): Boolean
    fun attachLocalTracks(audioTrack: AudioTrack?, videoTrack: VideoTrack?)
    fun closePeerConnection()
    fun createOffer(
        iceRestart: Boolean = false,
        onSdp: (String) -> Unit,
        onComplete: ((Boolean) -> Unit)? = null,
    ): Boolean
    fun createAnswer(onSdp: (String) -> Unit, onComplete: ((Boolean) -> Unit)? = null)
    fun setRemoteDescription(
        type: SessionDescription.Type,
        sdp: String,
        onComplete: (() -> Unit)? = null,
    )
    fun rollbackLocalDescription(onComplete: ((Boolean) -> Unit)? = null)
    fun addIceCandidate(candidate: IceCandidate)

    // State queries
    fun isReady(): Boolean
    fun getConnectionState(): PeerConnection.PeerConnectionState
    fun getIceConnectionState(): PeerConnection.IceConnectionState
    fun getSignalingState(): PeerConnection.SignalingState
    fun hasRemoteDescription(): Boolean
    fun isRemoteVideoTrackEnabled(): Boolean

    // Renderer/stats
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer)
    fun detachRemoteRenderer(renderer: SurfaceViewRenderer)
    fun attachRemoteSink(sink: VideoSink)
    fun detachRemoteSink(sink: VideoSink)
    fun collectWebRtcStats(onComplete: (String, RealtimeCallStats?) -> Unit)
    fun applyVideoSenderParameters(policy: WebRtcEngine.VideoSenderPolicy)
}
