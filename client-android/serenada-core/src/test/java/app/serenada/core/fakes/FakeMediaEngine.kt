package app.serenada.core.fakes

import android.content.Intent
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.PeerConnectionSlotProtocol
import app.serenada.core.call.SessionMediaEngine
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

internal class FakeMediaEngine : SessionMediaEngine {
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
    var failNextCreatedSlotRemoteOffer = false
    var deferNextCreatedSlotOfferSdp = false
    var deferNextCreatedSlotAnswerSdp = false

    private var _iceServers: List<PeerConnection.IceServer>? = null

    // Camera capture starts (true = video capture requested). Tracked SEPARATELY
    // from content (screen share) starts so tests can assert camera vs content
    // lifecycle independently in independent mode.
    val startVideoCaptureCalls = mutableListOf<Boolean>()
    // Content (screen share) start/stop counts. In independent mode these are the
    // CONTENT track lifecycle; they never increment camera counts.
    var startScreenShareCalls = 0
        private set
    var stopScreenShareCalls = 0
        private set
    /** Alias for content-start count, to read clearly in independent-mode tests. */
    val contentShareStartCalls: Int get() = startScreenShareCalls
    /** Alias for content-stop count. */
    val contentShareStopCalls: Int get() = stopScreenShareCalls
    // Per-peer capability params seen at slot creation (cid → supported / policy).
    val createdSlotSupportsIndependent = mutableMapOf<String, Boolean>()
    val createdSlotOfferOwner = mutableMapOf<String, Boolean>()
    // Result the fake reports for screen-share start/stop. Defaults to success
    // so session-level content_state/revision flow can be exercised.
    var startScreenShareResult = true
    var stopScreenShareResult = true
    // When true, startScreenShare / stopScreenShare drive the per-peer content
    // attach loop on the modeled fake slots (capable peers only), mirroring the
    // real WebRtcEngine's `peerSlots.forEach { attachLocalTracksToSlot }`. Lets
    // session-level tests observe per-peer attach-failure ISOLATION (a failing
    // peer renegotiates while a healthy peer carries the share) without a real
    // VideoTrack. Off by default so existing tests are unaffected.
    var modelIndependentContentAttach = false

    val attachLocalContentSinkCalls = mutableListOf<VideoSink>()
    val detachLocalContentSinkCalls = mutableListOf<VideoSink>()

    override fun startLocalMedia(startVideoCapture: Boolean) {
        startLocalMediaCalls++
        startVideoCaptureCalls.add(startVideoCapture)
    }
    override fun release() { releaseCalls++ }
    override fun toggleAudio(enabled: Boolean) { toggleAudioCalls.add(enabled) }
    override fun toggleVideo(enabled: Boolean): Boolean {
        toggleVideoCalls.add(enabled)
        return enabled
    }
    override fun flipCamera() {}

    /**
     * Engine-side active camera mode. Tests set this to simulate the camera being
     * in WORLD/COMPOSITE while an independent screen share runs, so the session's
     * post-stop camera-hint restore can be asserted. Null mirrors a no-camera
     * engine (the session then falls back to its own [LocalCameraMode] copy).
     */
    var activeCameraMode: LocalCameraMode? = null
    override fun activeCameraMode(): LocalCameraMode? = activeCameraMode
    override fun startScreenShare(intent: Intent): Boolean {
        startScreenShareCalls++
        if (startScreenShareResult && modelIndependentContentAttach) {
            // Per-peer attach: only capable slots get the content track on their
            // content sender (mirrors attachLocalTracksToSlot's capable branch).
            // A per-slot failNextContentAttach turns that peer's attach into a
            // reject + renegotiation; other peers are unaffected (isolation).
            fakeSlots.values
                .filter { it.supportsIndependentContentVideo }
                .forEach { it.simulateContentAttach(attach = true) }
        }
        return startScreenShareResult
    }
    override fun stopScreenShare(): Boolean {
        stopScreenShareCalls++
        if (stopScreenShareResult && modelIndependentContentAttach) {
            fakeSlots.values
                .filter { it.supportsIndependentContentVideo }
                .forEach { it.simulateContentAttach(attach = false) }
        }
        return stopScreenShareResult
    }

    override fun setIceServers(servers: List<PeerConnection.IceServer>) {
        _iceServers = servers
        iceServersSet = true
        fakeSlots.values.forEach { it.setIceServers(servers) }
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
        supportsIndependentContentVideo: Boolean,
        isOfferOwner: () -> Boolean,
    ): PeerConnectionSlotProtocol {
        createdSlotCids.add(remoteCid)
        createdSlotSupportsIndependent[remoteCid] = supportsIndependentContentVideo
        createdSlotOfferOwner[remoteCid] = isOfferOwner()
        val slot = FakePeerConnectionSlot(
            remoteCid = remoteCid,
            onLocalIceCandidate = onLocalIceCandidate,
            onConnectionStateChange = onConnectionStateChange,
            onIceConnectionStateChange = onIceConnectionStateChange,
            onSignalingStateChange = onSignalingStateChange,
            onRenegotiationNeeded = onRenegotiationNeeded,
            supportsIndependentContentVideo = supportsIndependentContentVideo,
        )
        if (failNextCreatedSlotRemoteOffer) {
            slot.failNextRemoteOffer = true
            failNextCreatedSlotRemoteOffer = false
        }
        if (deferNextCreatedSlotOfferSdp) {
            slot.deferNextOfferSdp = true
            deferNextCreatedSlotOfferSdp = false
        }
        if (deferNextCreatedSlotAnswerSdp) {
            slot.deferNextAnswerSdp = true
            deferNextCreatedSlotAnswerSdp = false
        }
        fakeSlots[remoteCid] = slot
        _iceServers?.let(slot::setIceServers)
        return slot
    }

    override fun removeSlot(slot: PeerConnectionSlotProtocol) {
        removedSlots.add(slot)
        fakeSlots.remove(slot.remoteCid)
    }

    val attachLocalSinkCalls = mutableListOf<VideoSink>()
    val detachLocalSinkCalls = mutableListOf<VideoSink>()
    override fun attachLocalRenderer(renderer: SurfaceViewRenderer, rendererEvents: RendererCommon.RendererEvents?) {}
    override fun detachLocalRenderer(renderer: SurfaceViewRenderer) {}
    override fun attachLocalSink(sink: VideoSink) {
        attachLocalSinkCalls += sink
    }
    override fun detachLocalSink(sink: VideoSink) {
        detachLocalSinkCalls += sink
    }
    override fun attachLocalContentSink(sink: VideoSink) {
        attachLocalContentSinkCalls += sink
    }
    override fun detachLocalContentSink(sink: VideoSink) {
        detachLocalContentSinkCalls += sink
    }
    override fun initRenderer(renderer: SurfaceViewRenderer, rendererEvents: RendererCommon.RendererEvents?) {}
    override fun adjustWorldCameraZoom(scaleFactor: Float): Boolean = false
    override fun toggleFlashlight(): Boolean = false
    override fun getEglContext(): EglBase.Context =
        throw UnsupportedOperationException("EGL context not available in tests")

    var nextLocalAudioLevel: Float? = null
    override fun collectLocalAudioLevel(onComplete: (Float?) -> Unit) {
        onComplete(nextLocalAudioLevel)
    }
}
