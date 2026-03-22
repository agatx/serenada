package app.serenada.core.call

import android.content.Intent
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

interface SessionMediaEngine {
    fun startLocalMedia()
    fun release()
    fun toggleAudio(enabled: Boolean)
    fun toggleVideo(enabled: Boolean)
    fun flipCamera()
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
    ): PeerConnectionSlotProtocol
    fun removeSlot(slot: PeerConnectionSlotProtocol)
    fun attachLocalRenderer(renderer: SurfaceViewRenderer, rendererEvents: RendererCommon.RendererEvents?)
    fun detachLocalRenderer(renderer: SurfaceViewRenderer)
    fun attachLocalSink(sink: VideoSink)
    fun detachLocalSink(sink: VideoSink)
    fun initRenderer(renderer: SurfaceViewRenderer, rendererEvents: RendererCommon.RendererEvents?)
    fun adjustWorldCameraZoom(scaleFactor: Float): Boolean
    fun toggleFlashlight(): Boolean
    fun getEglContext(): EglBase.Context
}
