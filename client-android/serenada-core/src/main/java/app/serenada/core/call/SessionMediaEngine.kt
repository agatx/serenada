package app.serenada.core.call

import android.content.Intent
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

internal interface SessionMediaEngine {
    fun startLocalMedia(startVideoCapture: Boolean)
    fun release()
    fun toggleAudio(enabled: Boolean)
    fun toggleVideo(enabled: Boolean): Boolean
    fun flipCamera()
    /**
     * Engine-side camera mode, updated synchronously by [flipCamera]. The
     * session's state copy is posted asynchronously, so callers that flip in
     * a loop must consult this instead. Null when the engine has no camera.
     */
    fun activeCameraMode(): LocalCameraMode? = null
    fun startScreenShare(intent: Intent): Boolean
    fun stopScreenShare(): Boolean
    fun setIceServers(servers: List<PeerConnection.IceServer>)
    fun hasIceServers(): Boolean
    fun createSlot(
        remoteCid: String,
        onLocalIceCandidate: (String, IceCandidate) -> Unit,
        onRemoteVideoTrack: (String, VideoTrack?) -> Unit,
        onConnectionStateChange: (String, PeerConnection.PeerConnectionState) -> Unit,
        onIceConnectionStateChange: (String, PeerConnection.IceConnectionState) -> Unit,
        onSignalingStateChange: (String, PeerConnection.SignalingState) -> Unit,
        onRenegotiationNeeded: (String) -> Unit,
        /**
         * Per-peer independent-content gate (local flag AND peer capability AND
         * both videoMediaEnabled). Defaults false ⇒ legacy single-video path.
         */
        supportsIndependentContentVideo: Boolean = false,
        /** Whether the local participant is the deterministic offer owner. */
        isOfferOwner: () -> Boolean = { false },
    ): PeerConnectionSlotProtocol
    fun removeSlot(slot: PeerConnectionSlotProtocol)
    fun attachLocalRenderer(renderer: SurfaceViewRenderer, rendererEvents: RendererCommon.RendererEvents?)
    fun detachLocalRenderer(renderer: SurfaceViewRenderer)
    fun attachLocalSink(sink: VideoSink)
    fun detachLocalSink(sink: VideoSink)

    /**
     * Attach a sink to the LOCAL content (screen share) track for local preview.
     * No-op until a content track exists (independent mode, sharing). The camera
     * preview continues to use [attachLocalSink].
     */
    fun attachLocalContentSink(sink: VideoSink) {}
    fun detachLocalContentSink(sink: VideoSink) {}
    fun initRenderer(renderer: SurfaceViewRenderer, rendererEvents: RendererCommon.RendererEvents?)
    fun adjustWorldCameraZoom(scaleFactor: Float): Boolean
    fun toggleFlashlight(): Boolean
    fun getEglContext(): EglBase.Context
    /**
     * Asynchronously fetches the local audio level from WebRTC's
     * `media-source.audioLevel` stat. The implementation keeps a primer
     * peer connection alive so this stat is available even before any real
     * peer joins. Result is in [0, 1] or null if the stat isn't ready.
     */
    fun collectLocalAudioLevel(onComplete: (Float?) -> Unit)
}
