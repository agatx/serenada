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

    /**
     * Create stable audio + video transceivers/senders WITHOUT starting capture
     * (multi-call session, contract §5 / Core Invariant 3). Used by a session
     * joined in the `held` initial role: the senders exist (send-capable, null
     * track) so a later resume attaches freshly acquired tracks to them via
     * `setTrack` with no SDP renegotiation on the common path. Unlike
     * [startLocalMedia] this acquires NO mic/camera, so the OS never reports the
     * held session as capturing. Default no-op so fakes/custom engines that do
     * not implement the held-join path are unaffected.
     */
    fun createSendersForHold() {}

    fun release()

    /**
     * Enable or disable local mic publishing and return the EFFECTIVE state.
     * When enabling with no live mic capture track (e.g. after a muted hold was
     * resumed muted, leaving the mic released), the engine (re)acquires and
     * attaches the mic track before publishing — so the session never broadcasts
     * a live audio state backed by a null track. When the track already exists
     * this only flips `setEnabled` (normal foreground mute/unmute), preserving
     * single-call behavior.
     */
    fun toggleAudio(enabled: Boolean): Boolean
    fun toggleVideo(enabled: Boolean): Boolean
    fun flipCamera()

    /**
     * Suspend local media for a hold transition. Stops the camera capturer and
     * RELEASES the microphone capture (disposes the mic track/source and nulls
     * the audio sender via `setTrack(null, false)`), so the OS stops reporting
     * this session as capturing. Distinct from [toggleAudio]/[toggleVideo],
     * which only flip `track.enabled` and leave capture live. Peer connections,
     * transceivers, and senders are preserved for a renegotiation-free resume.
     * Screen share is stopped by the session before this is called.
     */
    fun suspendLocalMediaForHold() {}

    /**
     * Resume local media after a hold. Reacquires capture per [audioEnabled] /
     * [videoMode] (off = camera disabled) and attaches fresh tracks to the
     * existing senders. Resume must not renegotiate on the common path.
     */
    fun resumeLocalMediaFromHold(audioEnabled: Boolean, videoMode: LocalCameraMode?) {}

    /**
     * Gate audible remote playback. When false, the remote receiver
     * `AudioTrack`s are disabled (`setEnabled(false)`), a hard deafen distinct
     * from the external-audio volume duck (`setVolume(0.15)`). Held calls set
     * this false so their remote audio is silent.
     */
    fun setRemotePlaybackEnabled(enabled: Boolean) {}

    /**
     * Detach the visible renderers for a held call — local preview plus every
     * peer's remote camera and content sinks — so a held call renders no frames
     * and the decoder wastes no delivery to hidden renderers. Sink registrations
     * are preserved so [reattachRenderersAfterResume] can replay them; no
     * renegotiation. Undone by [reattachRenderersAfterResume].
     */
    fun detachRenderersForHold() {}

    /**
     * Re-attach exactly the renderers detached by [detachRenderersForHold] after a
     * resume. Idempotent: repeated hold/resume cycles never accumulate duplicate
     * sinks on a track.
     */
    fun reattachRenderersAfterResume() {}
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
