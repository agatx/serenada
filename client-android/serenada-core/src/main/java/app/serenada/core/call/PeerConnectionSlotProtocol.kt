package app.serenada.core.call

import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

internal interface PeerConnectionSlotProtocol {
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

    /**
     * Lightweight stats fetch that extracts only `audioLevel` (W3C webrtc-stats):
     * the inbound-rtp audio level for the remote participant on this slot, and
     * the media-source audio level for the locally captured mic. Either may
     * be null if stats haven't populated yet. The callback thread is not
     * guaranteed; post to the appropriate handler/executor if needed.
     */
    fun collectAudioLevels(onComplete: (inboundLevel: Float?, mediaSourceLevel: Float?) -> Unit)
    fun applyVideoSenderParameters(policy: WebRtcEngine.VideoSenderPolicy)

    /**
     * Returns the last observed path type for the selected candidate pair:
     * true if direct (host/srflx/prflx), false if relayed through TURN, null
     * if no stats sample has been collected yet. Updated by the stats poller
     * on each WebRTC stats cycle. Used by the TURN refresh gate to decide
     * whether the credentials can be allowed to expire without impact.
     */
    fun isPathDirect(): Boolean?
}
