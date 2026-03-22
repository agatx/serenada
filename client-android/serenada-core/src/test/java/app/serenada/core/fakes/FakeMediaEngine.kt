package app.serenada.core.fakes

import android.content.Intent
import app.serenada.core.call.PeerConnectionSlotProtocol
import app.serenada.core.call.SessionMediaEngine
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

class FakeMediaEngine : SessionMediaEngine {
    var startLocalMediaCalls = 0
        private set
    var releaseCalls = 0
        private set
    val toggleAudioCalls = mutableListOf<Boolean>()
    val toggleVideoCalls = mutableListOf<Boolean>()
    var iceServersSet = false
        private set
    val createdSlotCids = mutableListOf<String>()
    val removedSlots = mutableListOf<PeerConnectionSlotProtocol>()
    val fakeSlots = mutableMapOf<String, FakePeerConnectionSlot>()

    private var _iceServers: List<PeerConnection.IceServer>? = null

    override fun startLocalMedia() { startLocalMediaCalls++ }
    override fun release() { releaseCalls++ }
    override fun toggleAudio(enabled: Boolean) { toggleAudioCalls.add(enabled) }
    override fun toggleVideo(enabled: Boolean) { toggleVideoCalls.add(enabled) }
    override fun flipCamera() {}
    override fun startScreenShare(intent: Intent): Boolean = false
    override fun stopScreenShare(): Boolean = false

    override fun setIceServers(servers: List<PeerConnection.IceServer>) {
        _iceServers = servers
        iceServersSet = true
    }

    override fun hasIceServers(): Boolean = _iceServers != null

    override fun createSlot(
        remoteCid: String,
        onLocalIceCandidate: (String, IceCandidate) -> Unit,
        onRemoteVideoTrack: (String, VideoTrack?) -> Unit,
        onConnectionStateChange: (String, PeerConnection.PeerConnectionState) -> Unit,
        onIceConnectionStateChange: (String, PeerConnection.IceConnectionState) -> Unit,
        onSignalingStateChange: (String, PeerConnection.SignalingState) -> Unit,
        onRenegotiationNeeded: (String) -> Unit,
    ): PeerConnectionSlotProtocol {
        createdSlotCids.add(remoteCid)
        val slot = FakePeerConnectionSlot(
            remoteCid = remoteCid,
            onConnectionStateChange = onConnectionStateChange,
            onIceConnectionStateChange = onIceConnectionStateChange,
            onSignalingStateChange = onSignalingStateChange,
        )
        fakeSlots[remoteCid] = slot
        return slot
    }

    override fun removeSlot(slot: PeerConnectionSlotProtocol) {
        removedSlots.add(slot)
    }

    override fun attachLocalRenderer(renderer: SurfaceViewRenderer, rendererEvents: RendererCommon.RendererEvents?) {}
    override fun detachLocalRenderer(renderer: SurfaceViewRenderer) {}
    override fun attachLocalSink(sink: VideoSink) {}
    override fun detachLocalSink(sink: VideoSink) {}
    override fun initRenderer(renderer: SurfaceViewRenderer, rendererEvents: RendererCommon.RendererEvents?) {}
    override fun adjustWorldCameraZoom(scaleFactor: Float): Boolean = false
    override fun toggleFlashlight(): Boolean = false
    override fun getEglContext(): EglBase.Context =
        throw UnsupportedOperationException("EGL context not available in tests")
}
