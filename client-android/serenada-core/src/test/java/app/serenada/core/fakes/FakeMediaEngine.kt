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

    // Hold/resume observation.
    var suspendLocalMediaForHoldCalls = 0
        private set
    /** Recorded (audioEnabled, videoMode) pairs passed to resume. */
    val resumeLocalMediaFromHoldCalls = mutableListOf<Pair<Boolean, LocalCameraMode?>>()
    /** Recorded enabled values passed to setRemotePlaybackEnabled. */
    val setRemotePlaybackEnabledCalls = mutableListOf<Boolean>()
    var detachRenderersForHoldCalls = 0
        private set

    // FIX A1 model: whether the engine currently holds a live mic CAPTURE track.
    // Mirrors the real WebRtcEngine: startLocalMedia creates it, suspend releases
    // it, and resume recreates it ONLY when audio is desired (a muted resume must
    // leave it released so the OS mic indicator stays off). Counts recreations so
    // a test can assert a muted resume does NOT recreate the mic.
    var micCaptureTrackPresent = false
        private set
    var micCaptureRecreateCount = 0
        private set

    // Simulates a failed mic ACQUIRE: when true, an enabling toggleAudio(true)
    // cannot (re)create the capture track and reports the effective state as
    // false (no live track). Mirrors the real engine returning false when the OS
    // denies/loses the mic. Lets a test assert the broadcast/published audio
    // state never reports live audio that isn't actually captured (FIX P5).
    var failMicAcquire = false

    // FIX P5 model: whether the engine currently holds a live camera CAPTURE
    // track. Mirrors the real WebRtcEngine: startLocalMedia/resume(camera) create
    // it, suspend + resume-camera-off release it. A FOREGROUND enable with no
    // track (toggleVideo(true)) must (re)create + attach it before publishing
    // true. Counts recreations so a test can assert the (re)create on enable.
    var cameraCaptureTrackPresent = false
        private set
    var cameraCaptureRecreateCount = 0
        private set

    override fun startLocalMedia(startVideoCapture: Boolean) {
        startLocalMediaCalls++
        startVideoCaptureCalls.add(startVideoCapture)
        micCaptureTrackPresent = true
        cameraCaptureTrackPresent = startVideoCapture
    }

    // Multi-call held join: createSendersForHold creates stable senders WITHOUT
    // capture (contract §5 / Core Invariant 3). Tracked so a held-join test can
    // assert senders were created while NO mic/camera capture track exists.
    var createSendersForHoldCalls = 0
        private set
    override fun createSendersForHold() {
        createSendersForHoldCalls++
        // Senders exist but carry NO capture track (the held invariant): leave
        // micCaptureTrackPresent / cameraCaptureTrackPresent false.
    }

    override fun release() { releaseCalls++ }

    override fun suspendLocalMediaForHold() {
        suspendLocalMediaForHoldCalls++
        micCaptureTrackPresent = false
        cameraCaptureTrackPresent = false
    }
    override fun resumeLocalMediaFromHold(audioEnabled: Boolean, videoMode: LocalCameraMode?) {
        resumeLocalMediaFromHoldCalls.add(audioEnabled to videoMode)
        // Recreate the mic capture ONLY when audio is desired (FIX A1). A muted
        // resume keeps capture released — no recreate, sender track stays null.
        if (audioEnabled && !micCaptureTrackPresent) {
            micCaptureTrackPresent = true
            micCaptureRecreateCount++
        }
        // Recreate the camera capture ONLY when a camera mode is desired. A
        // camera-off resume keeps it released (FIX P5: a later video-on toggle
        // must (re)create it via toggleVideo).
        if (videoMode != null && videoMode != LocalCameraMode.SCREEN_SHARE && !cameraCaptureTrackPresent) {
            cameraCaptureTrackPresent = true
            cameraCaptureRecreateCount++
        }
    }
    override fun setRemotePlaybackEnabled(enabled: Boolean) {
        setRemotePlaybackEnabledCalls.add(enabled)
    }
    override fun detachRenderersForHold() {
        detachRenderersForHoldCalls++
    }
    override fun toggleAudio(enabled: Boolean): Boolean {
        toggleAudioCalls.add(enabled)
        // Simulated acquire failure: an enable cannot obtain a live mic track, so
        // the effective state is false (no recreate, no live track).
        if (enabled && failMicAcquire) {
            micCaptureTrackPresent = false
            return false
        }
        // FIX P5: a FOREGROUND enable with no live mic track (re)creates + attaches
        // it before publishing — mirrors the real engine ensuring the track exists.
        // When the track already exists this is a plain setEnabled (no recreate),
        // preserving single-call mute/unmute behavior. Returns the EFFECTIVE state.
        if (enabled && !micCaptureTrackPresent) {
            micCaptureTrackPresent = true
            micCaptureRecreateCount++
        }
        return enabled && micCaptureTrackPresent
    }
    override fun toggleVideo(enabled: Boolean): Boolean {
        toggleVideoCalls.add(enabled)
        // FIX P5: a FOREGROUND enable with no live camera track (re)creates +
        // attaches it before returning true. When the track already exists this is
        // a plain setEnabled (no recreate). Returns the EFFECTIVE state.
        if (enabled && !cameraCaptureTrackPresent) {
            cameraCaptureTrackPresent = true
            cameraCaptureRecreateCount++
        } else if (!enabled) {
            cameraCaptureTrackPresent = false
        }
        return enabled && cameraCaptureTrackPresent
    }
    var flipCameraCalls = 0
        private set
    override fun flipCamera() { flipCameraCalls++ }

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
