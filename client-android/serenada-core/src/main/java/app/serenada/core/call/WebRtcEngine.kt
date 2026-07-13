package app.serenada.core.call

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaRecorder
import app.serenada.core.FeatureDegradationState
import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.Logging
import org.webrtc.RendererCommon
import org.webrtc.RtpParameters
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

internal class WebRtcEngine(
    context: Context,
    private val eglBase: EglBase,
    private val onCameraFacingChanged: (Boolean) -> Unit,
    private val onCameraModeChanged: (LocalCameraMode) -> Unit,
    private val onFlashlightStateChanged: (Boolean, Boolean) -> Unit,
    private val onScreenShareStopped: () -> Unit,
    private val onFeatureDegradation: (FeatureDegradationState) -> Unit = {},
    private var isHdVideoExperimentalEnabled: Boolean = false,
    private var isRemoteBlackFrameAnalysisEnabled: Boolean = true,
    private val videoMediaEnabled: Boolean = true,
    // Local build capability gate. When false (default), every peer uses the
    // legacy single-video screen-share path and behavior is byte-identical to
    // today. When true, screen share rides a SEPARATE content track/transceiver
    // for peers that also advertise the capability (per-peer routing).
    private val enableIndependentContentVideo: Boolean = false,
    availableCameraModes: List<LocalCameraMode> = app.serenada.core.DEFAULT_CAMERA_MODES,
    private val logger: SerenadaLogger? = null,
) : SessionMediaEngine {

    data class VideoSenderPolicy(
        val maxBitrateBps: Int?,
        val minBitrateBps: Int?,
        val maxFramerate: Int?,
        val degradationPreference: RtpParameters.DegradationPreference?
    )

    private data class LocalMediaResources(
        val videoTrack: VideoTrack?,
        val videoSource: org.webrtc.VideoSource?,
        val contentVideoTrack: VideoTrack?,
        val contentVideoSource: org.webrtc.VideoSource?,
        val audioTrack: AudioTrack?,
        val audioSource: AudioSource?,
    )

    private val appContext = context.applicationContext
    private val audioDeviceModule: AudioDeviceModule = createAudioDeviceModule(appContext)
    private val peerConnectionFactory: PeerConnectionFactory
    private val audioPipelinePrimer: LocalAudioPipelinePrimer
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private var released = false

    // Camera video: `videoSource`/`localVideoTrack` are the camera path (legacy
    // names retained). CameraCaptureController writes the camera source.
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: org.webrtc.VideoSource? = null
    private var audioSource: AudioSource? = null

    // Independent-content path (flag ON only): a SEPARATE source/track carries
    // the screen share. ScreenShareController writes the content source. The
    // content track is also the "pending" track attached to capable peers as
    // their content transceiver binds. Never touched on the legacy path.
    private var localContentVideoSource: org.webrtc.VideoSource? = null
    private var localContentVideoTrack: VideoTrack? = null

    private val localSinks = LinkedHashSet<VideoSink>()
    private val localContentSinks = LinkedHashSet<VideoSink>()
    private val peerSlots = LinkedHashSet<PeerConnectionSlot>()
    private val peerConnectionDisposeQueue = PeerConnectionDisposeQueue()

    private var iceServers: List<PeerConnection.IceServer>? = null
    private val initializedRenderers =
        Collections.newSetFromMap(WeakHashMap<SurfaceViewRenderer, Boolean>())

    private val cameraController = CameraCaptureController(
        appContext = appContext,
        eglBase = eglBase,
        cameraManager = cameraManager,
        isHdVideoExperimentalEnabled = isHdVideoExperimentalEnabled,
        availableCameraModes = availableCameraModes,
        videoSourceProvider = { videoSource },
        onCameraFacingChanged = onCameraFacingChanged,
        onCameraModeChanged = onCameraModeChanged,
        onFlashlightStateChanged = onFlashlightStateChanged,
        onFeatureDegradation = onFeatureDegradation,
        onVideoSenderParametersChanged = { applyVideoSenderParameters() },
        logger = logger,
    )

    private val screenShareController = ScreenShareController(
        appContext = appContext,
        eglBase = eglBase,
        cameraController = cameraController,
        capturerObserverProvider = { videoSource?.capturerObserver },
        videoSourceProvider = { videoSource },
        onScreenShareStopped = onScreenShareStopped,
        onStateChanged = { isSharing ->
            applyVideoSenderParameters()
            // Legacy share repurposes the single camera sender, so it flips the
            // facing indicator. Independent share leaves the camera untouched, so
            // the facing state must NOT change (pitfall #6).
            if (isSharing && !enableIndependentContentVideo) {
                onCameraFacingChanged(false)
            }
            // Independent stop (programmatic OR external MediaProjection onStop):
            // the controller has stopped capture, so tear down the content track
            // and detach it from every peer here. This is the single engine-side
            // content teardown point, so the external-stop path detaches peers
            // too (the session's onScreenShareStopped only handles signaling).
            // Idempotent: dispose null-checks and the per-peer attach with
            // content=null is a no-op when already detached.
            if (!isSharing && enableIndependentContentVideo) {
                tearDownContentAndDetach()
            }
        },
        independentContentEnabled = enableIndependentContentVideo,
        contentCapturerObserverProvider = { localContentVideoSource?.capturerObserver },
        logger = logger,
    )

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
        enableVerboseWebRtcLoggingIfDebug()
        logger?.log(SerenadaLogLevel.INFO, "WebRTC", "WebRTC initialized")

        // Keep VP8 hardware support enabled, but disable H264 high profile to reduce encode latency
        // regressions seen on some Android devices with constrained hardware encoders.
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        audioPipelinePrimer = LocalAudioPipelinePrimer(peerConnectionFactory, logger)
    }

    private fun enableVerboseWebRtcLoggingIfDebug() {
        if (!false) return
        if (!WEBRTC_LOGGING_ENABLED.compareAndSet(false, true)) return
        runCatching {
            Logging.enableLogThreads()
            Logging.enableLogTimeStamps()
            Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE)
            logger?.log(SerenadaLogLevel.INFO, "WebRTC", "Verbose native WebRTC logging enabled")
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "WebRTC", "Failed to enable WebRTC verbose logging: ${error.message}")
        }
    }

    private fun createAudioDeviceModule(context: Context): AudioDeviceModule {
        val builder = JavaAudioDeviceModule.builder(context)
        configureAudioDeviceModule(builder)
        return builder.createAudioDeviceModule()
    }

    private fun configureAudioDeviceModule(builder: JavaAudioDeviceModule.Builder) {
        builder
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.ENCODING_PCM_16BIT)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseLowLatency(true)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setEnableVolumeLogger(false)
            .setAudioTrackErrorCallback(
                object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                        logger?.log(SerenadaLogLevel.WARNING, "WebRTC", "AudioTrack init error: $errorMessage")
                    }

                    override fun onWebRtcAudioTrackStartError(
                        errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                        errorMessage: String?
                    ) {
                        logger?.log(SerenadaLogLevel.WARNING, "WebRTC", "AudioTrack start error: code=$errorCode message=$errorMessage")
                    }

                    override fun onWebRtcAudioTrackError(errorMessage: String?) {
                        logger?.log(SerenadaLogLevel.WARNING, "WebRTC", "AudioTrack runtime error: $errorMessage")
                    }
                }
            )
            .setAudioRecordErrorCallback(
                object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                        logger?.log(SerenadaLogLevel.WARNING, "WebRTC", "AudioRecord init error: $errorMessage")
                    }

                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                        errorMessage: String?
                    ) {
                        logger?.log(SerenadaLogLevel.WARNING, "WebRTC", "AudioRecord start error: code=$errorCode message=$errorMessage")
                    }

                    override fun onWebRtcAudioRecordError(errorMessage: String?) {
                        logger?.log(SerenadaLogLevel.WARNING, "WebRTC", "AudioRecord runtime error: $errorMessage")
                    }
                }
            )
    }

    override fun getEglContext(): EglBase.Context = eglBase.eglBaseContext

    override fun collectLocalAudioLevel(onComplete: (Float?) -> Unit) {
        audioPipelinePrimer.collectAudioLevel(onComplete)
    }

    override fun startLocalMedia(startVideoCapture: Boolean) {
        if (released) return
        if (localAudioTrack != null || localVideoTrack != null) return
        cameraController.resetCameraState()
        val audioConstraints = MediaConstraints()
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource)
        applyAudioTrackHints()
        localAudioTrack?.let { audioPipelinePrimer.start(it) }

        if (!videoMediaEnabled || cameraController.availableCameraModes.isEmpty()) {
            peerSlots.forEach { slot -> attachLocalTracksToSlot(slot) }
            return
        }

        ensureVideoSource()
        cameraController.resetCameraSourceToInitial()
        val startedVideo = startVideoCapture && restartVideoCapturerWithFallback(cameraController.currentCameraSource)
        if (!startedVideo) {
            if (startVideoCapture) {
                logger?.log(SerenadaLogLevel.WARNING, "WebRTC", "No camera capturer available; continuing audio-only")
            } else {
                logger?.log(SerenadaLogLevel.INFO, "WebRTC", "Camera starts disabled; continuing audio-only")
            }
        }
        ensureLocalVideoTrack(enabled = startedVideo)
        peerSlots.forEach { slot -> attachLocalTracksToSlot(slot) }
    }

    /**
     * Route the current local tracks to a slot per its per-peer capability.
     *
     * - Capable peer: camera track → camera role, content track → content role
     *   (camera and screen share simultaneously).
     * - Legacy peer: a SINGLE video track. While an independent share is active
     *   the screen takes priority over camera on that connection (matches
     *   today's legacy precedence), otherwise the camera track is used. This is
     *   the only place the legacy single-sender precedence is decided, so camera
     *   ops never clobber a legacy peer's content sender during a share
     *   (pitfall #7).
     */
    private fun attachLocalTracksToSlot(slot: PeerConnectionSlot) {
        if (slot.supportsIndependentContentVideo) {
            slot.attachLocalTracks(
                audioTrack = localAudioTrack,
                cameraTrack = localVideoTrack,
                contentTrack = localContentVideoTrack,
                supportsIndependentContentVideo = true,
            )
        } else {
            val legacyVideoTrack =
                if (screenShareController.isScreenSharing && localContentVideoTrack != null) {
                    localContentVideoTrack
                } else {
                    localVideoTrack
                }
            slot.attachLocalTracks(
                audioTrack = localAudioTrack,
                cameraTrack = legacyVideoTrack,
                contentTrack = null,
                supportsIndependentContentVideo = false,
            )
        }
    }

    private fun ensureContentVideoSource(): org.webrtc.VideoSource {
        return localContentVideoSource
            ?: peerConnectionFactory.createVideoSource(true).also { localContentVideoSource = it }
    }

    private fun ensureLocalContentVideoTrack(): VideoTrack {
        localContentVideoTrack?.let { track ->
            track.setEnabled(true)
            return track
        }
        return peerConnectionFactory.createVideoTrack("ARDAMScontent0", ensureContentVideoSource()).also { track ->
            track.setEnabled(true)
            localContentSinks.forEach { sink -> track.addSink(sink) }
            localContentVideoTrack = track
        }
    }

    private fun disposeLocalContentVideoTrack() {
        localContentVideoTrack?.let { track ->
            localContentSinks.forEach { sink -> track.removeSink(sink) }
            track.dispose()
        }
        localContentVideoTrack = null
        localContentVideoSource?.dispose()
        localContentVideoSource = null
    }

    private fun ensureVideoSource(): org.webrtc.VideoSource {
        return videoSource ?: peerConnectionFactory.createVideoSource(false).also { videoSource = it }
    }

    private fun ensureLocalVideoTrack(enabled: Boolean): VideoTrack {
        localVideoTrack?.let { track ->
            track.setEnabled(enabled)
            return track
        }
        return peerConnectionFactory.createVideoTrack("ARDAMSv0", ensureVideoSource()).also { track ->
            track.setEnabled(enabled)
            localSinks.forEach { sink -> track.addSink(sink) }
            localVideoTrack = track
        }
    }

    private fun disposeLocalVideoTrack() {
        localVideoTrack?.let { track ->
            localSinks.forEach { sink -> track.removeSink(sink) }
            track.dispose()
        }
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
    }

    private fun detachLocalMediaForRelease(): LocalMediaResources {
        cameraController.resetCameraState()
        localVideoTrack?.setEnabled(false)
        localContentVideoTrack?.setEnabled(false)
        localAudioTrack?.setEnabled(false)
        localVideoTrack?.let { track ->
            localSinks.forEach { sink -> track.removeSink(sink) }
        }
        localContentVideoTrack?.let { track ->
            localContentSinks.forEach { sink -> track.removeSink(sink) }
        }
        val resources = LocalMediaResources(
            videoTrack = localVideoTrack,
            videoSource = videoSource,
            contentVideoTrack = localContentVideoTrack,
            contentVideoSource = localContentVideoSource,
            audioTrack = localAudioTrack,
            audioSource = audioSource,
        )
        localVideoTrack = null
        videoSource = null
        localContentVideoTrack = null
        localContentVideoSource = null
        localAudioTrack = null
        audioSource = null
        localSinks.clear()
        localContentSinks.clear()
        return resources
    }

    private fun releaseLocalMedia(resources: LocalMediaResources) {
        runCatching { screenShareController.reset() }
        runCatching { cameraController.disposeVideoCapturer() }
        runCatching { resources.videoTrack?.dispose() }
        runCatching { resources.contentVideoTrack?.dispose() }
        // Tear down the primer before disposing its audio track — closing the
        // PC first releases the sender's reference to the track.
        runCatching { audioPipelinePrimer.stop() }
        runCatching { resources.audioTrack?.dispose() }
        runCatching { resources.videoSource?.dispose() }
        runCatching { resources.contentVideoSource?.dispose() }
        runCatching { resources.audioSource?.dispose() }
    }

    override fun release() {
        if (released) return
        released = true
        val peerTeardowns = peerSlots.mapNotNull { slot -> slot.prepareTerminalClose() }
        peerSlots.clear()
        val localMedia = detachLocalMediaForRelease()
        val teardownTicket = terminalMediaTeardownFence.begin()
        // Capturer/track/source disposal, PeerConnection.close/dispose, and the final
        // factory/ADM release synchronously wait on media and libwebrtc threads on some devices.
        // Sinks and session-visible state have already been detached on Main, so the remaining
        // native teardown can run without racing renderer unmounts or stale session callbacks.
        peerConnectionDisposeQueue.enqueueForFlush {
            try {
                if (!teardownTicket.awaitTurnBlocking(PROCESS_TEARDOWN_HANDOFF_TIMEOUT_MS)) {
                    logger?.log(
                        SerenadaLogLevel.WARNING,
                        "WebRTC",
                        "Timed out waiting for the previous terminal media teardown",
                    )
                }
                peerTeardowns.forEach { teardown -> runCatching { teardown.run() } }
                releaseLocalMedia(localMedia)
                runCatching { peerConnectionFactory.dispose() }
                runCatching { audioDeviceModule.release() }
            } finally {
                teardownTicket.complete()
            }
        }
        peerConnectionDisposeQueue.flush(shutdownAfterDrain = true)
        // eglBase is owned by SerenadaSession and outlives the engine — do not release it here.
    }

    override fun setIceServers(servers: List<PeerConnection.IceServer>) {
        if (released) return
        logger?.log(SerenadaLogLevel.DEBUG, "WebRTC", "ICE servers set: ${servers.size}")
        iceServers = servers
        peerSlots.forEach { slot ->
            slot.setIceServers(servers)
        }
    }

    override fun hasIceServers(): Boolean = !iceServers.isNullOrEmpty()

    override fun flipCamera() {
        cameraController.flipCamera(videoSource)
    }

    override fun activeCameraMode(): LocalCameraMode = cameraController.activeCameraMode()

    override fun adjustWorldCameraZoom(scaleFactor: Float): Boolean {
        return cameraController.adjustWorldCameraZoom(scaleFactor)
    }

    override fun toggleAudio(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    /**
     * True only while a LEGACY single-video screen share is active (the display
     * track has repurposed the single camera sender). In INDEPENDENT mode this is
     * always false, so camera ops (toggle/flip/restart) keep working during a
     * share — the screen rides a separate content track (pitfall #6).
     */
    private val isLegacyScreenSharing: Boolean
        get() = screenShareController.isScreenSharing && !enableIndependentContentVideo

    override fun toggleVideo(enabled: Boolean): Boolean {
        if (!videoMediaEnabled) {
            localVideoTrack?.setEnabled(false)
            return false
        }
        if (enabled && cameraController.availableCameraModes.isEmpty() && !isLegacyScreenSharing) {
            localVideoTrack?.setEnabled(false)
            return false
        }
        if (enabled && !isLegacyScreenSharing && cameraController.videoCapturer == null) {
            if (!restartVideoCapturerWithFallback(cameraController.currentCameraSource)) {
                localVideoTrack?.setEnabled(false)
                return false
            }
        }
        if (!enabled && !isLegacyScreenSharing) {
            cameraController.disposeVideoCapturer()
        }
        val effectiveEnabled = enabled && (cameraController.videoCapturer != null || isLegacyScreenSharing)
        localVideoTrack?.setEnabled(effectiveEnabled)
        return effectiveEnabled
    }

    private fun restartVideoCapturerWithFallback(preferredSource: CameraCaptureController.LocalCameraSource): Boolean {
        for (candidate in cameraSourceCandidates(preferredSource)) {
            if (cameraController.restartVideoCapturer(candidate, videoSource)) {
                if (candidate != preferredSource) {
                    logger?.log(SerenadaLogLevel.WARNING, "Camera", "Camera source fallback applied: $candidate")
                }
                return true
            }
        }
        return false
    }

    private fun cameraSourceCandidates(
        preferredSource: CameraCaptureController.LocalCameraSource
    ): List<CameraCaptureController.LocalCameraSource> {
        val candidates = mutableListOf(preferredSource)
        cameraController.availableCameraModes
            .map { cameraSourceFromMode(it) }
            .forEach { source ->
                if (source !in candidates) candidates.add(source)
            }
        return candidates
    }

    private fun cameraSourceFromMode(mode: LocalCameraMode): CameraCaptureController.LocalCameraSource {
        return when (mode) {
            LocalCameraMode.SELFIE -> CameraCaptureController.LocalCameraSource.SELFIE
            LocalCameraMode.WORLD -> CameraCaptureController.LocalCameraSource.WORLD
            LocalCameraMode.COMPOSITE -> CameraCaptureController.LocalCameraSource.COMPOSITE
            LocalCameraMode.SCREEN_SHARE -> CameraCaptureController.LocalCameraSource.SELFIE
        }
    }

    fun setHdVideoExperimentalEnabled(enabled: Boolean) {
        if (isHdVideoExperimentalEnabled == enabled) return
        isHdVideoExperimentalEnabled = enabled
        cameraController.setHdVideoExperimentalEnabled(enabled, videoSource, localVideoTrack)
    }

    override fun toggleFlashlight(): Boolean {
        return cameraController.toggleFlashlight()
    }

    override fun startScreenShare(intent: Intent): Boolean {
        if (!videoMediaEnabled) return false
        return if (enableIndependentContentVideo) {
            startScreenShareIndependent(intent)
        } else {
            startScreenShareLegacy(intent)
        }
    }

    // --- Legacy single-video screen share (flag OFF): byte-identical to today ---

    private fun startScreenShareLegacy(intent: Intent): Boolean {
        val createdVideoTrack = localVideoTrack == null
        ensureLocalVideoTrack(enabled = false)
        val started = screenShareController.startScreenShare(intent)
        if (!started) {
            if (createdVideoTrack && !cameraController.canCaptureVideo) {
                disposeLocalVideoTrack()
            }
            return false
        }
        localVideoTrack?.setEnabled(true)
        peerSlots.forEach { slot -> attachLocalTracksToSlot(slot) }
        return true
    }

    private fun stopScreenShareLegacy(): Boolean {
        val stopped = screenShareController.stopScreenShare()
        if (stopped && !cameraController.canCaptureVideo) {
            localVideoTrack?.setEnabled(false)
            peerSlots.forEach { slot -> attachLocalTracksToSlot(slot) }
        }
        return stopped
    }

    // --- Independent content screen share (flag ON): separate content track ---

    private fun startScreenShareIndependent(intent: Intent): Boolean {
        // Create the content source/track first (also the pending track), then
        // hand the content source's observer to the controller. The camera path
        // is untouched.
        val createdContentTrack = localContentVideoTrack == null
        ensureLocalContentVideoTrack()
        val started = screenShareController.startScreenShare(intent)
        if (!started) {
            if (createdContentTrack) disposeLocalContentVideoTrack()
            return false
        }
        // Per-peer attach: capable peers get the content track on their content
        // sender (or pending until bound); legacy peers get it swapped onto the
        // single video sender. Camera tracks to capable peers are NOT touched.
        peerSlots.forEach { slot -> attachLocalTracksToSlot(slot) }
        return true
    }

    private fun stopScreenShareIndependent(): Boolean {
        // Shared idempotent stop path. The controller's stop releases the
        // capturer and fires onStateChanged(false), which runs
        // tearDownContentAndDetach() (detach peers + dispose the content track).
        // A second entry via MediaProjection onStop is a no-op (controller latch).
        return screenShareController.stopScreenShare()
    }

    /**
     * Engine-side content teardown: detach the content track from every peer
     * (capable: content sender → null; legacy: restore camera on the single
     * sender) and release the content source/track. Idempotent — runs once per
     * logical stop via the controller's onStateChanged(false), covering both the
     * programmatic stop and the external MediaProjection onStop.
     */
    private fun tearDownContentAndDetach() {
        if (localContentVideoTrack == null && localContentVideoSource == null) return
        localContentVideoTrack?.setEnabled(false)
        disposeLocalContentVideoTrack()
        peerSlots.forEach { slot -> attachLocalTracksToSlot(slot) }
        // Restore camera sender params on any legacy slot that carried content
        // during the share (FIX 2 restore leg). With the content track now gone,
        // videoSenderPolicyForSlot reverts to the camera profile; re-apply
        // unconditionally so a legacy sender restores even when the camera is off
        // (the conditional re-apply inside attachLocalTracks only fires when a
        // camera track is present). Mirrors web's restoreLegacySenderCameraEncoding.
        applyVideoSenderParameters()
    }

    override fun stopScreenShare(): Boolean {
        return if (enableIndependentContentVideo) {
            stopScreenShareIndependent()
        } else {
            stopScreenShareLegacy()
        }
    }

    fun setRemoteBlackFrameAnalysisEnabled(enabled: Boolean) {
        isRemoteBlackFrameAnalysisEnabled = enabled
    }

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
        // Defense in depth: a slot is only independent-routed when the local
        // build flag is on too (the session already ANDs these, but keep the
        // engine authoritative so flag-off is byte-identical).
        val independentRouted = enableIndependentContentVideo && supportsIndependentContentVideo
        // Captured lazily so the slot's own video sender policy tracks whether
        // THIS (legacy) slot is currently carrying the content track during an
        // independent share (FIX 2). Initialized right after construction.
        var policySlot: PeerConnectionSlot? = null
        val slot = PeerConnectionSlot(
            remoteCid = remoteCid,
            factory = peerConnectionFactory,
            iceServers = iceServers,
            localAudioTrack = localAudioTrack,
            localVideoTrack = localVideoTrack,
            videoReceiveEnabled = videoMediaEnabled,
            onLocalIceCandidate = onLocalIceCandidate,
            onRemoteVideoTrack = onRemoteVideoTrack,
            onConnectionStateChange = onConnectionStateChange,
            onIceConnectionStateChange = onIceConnectionStateChange,
            onSignalingStateChange = onSignalingStateChange,
            onRenegotiationNeeded = onRenegotiationNeeded,
            applyAudioSenderParameters = ::applyAudioSenderParameters,
            currentVideoSenderPolicy = { policySlot?.let { videoSenderPolicyForSlot(it) } ?: activeVideoSenderPolicy() },
            isRemoteBlackFrameAnalysisEnabled = { isRemoteBlackFrameAnalysisEnabled },
            peerConnectionDisposeQueue = peerConnectionDisposeQueue,
            supportsIndependentContentVideo = independentRouted,
            isOfferOwner = isOfferOwner,
            contentSenderPolicy = ::contentVideoSenderPolicy,
            logger = logger,
        )
        policySlot = slot
        peerSlots.add(slot)
        if (!iceServers.isNullOrEmpty()) {
            slot.ensurePeerConnection()
        }
        // A peer created mid-share must pick up the active content: capable peers
        // via the content sender / pending-track mechanism, legacy peers via the
        // single-sender swap (pitfall #5). attachLocalTracksToSlot routes both.
        if (localAudioTrack != null || localVideoTrack != null || localContentVideoTrack != null) {
            attachLocalTracksToSlot(slot)
        }
        return slot
    }

    override fun removeSlot(slot: PeerConnectionSlotProtocol) {
        peerSlots.remove(slot)
    }

    override fun attachLocalRenderer(
        renderer: SurfaceViewRenderer,
        rendererEvents: RendererCommon.RendererEvents?
    ) {
        initRenderer(renderer, rendererEvents)
        attachLocalSink(renderer)
    }

    override fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        detachLocalSink(renderer)
    }

    override fun attachLocalSink(sink: VideoSink) {
        if (!localSinks.add(sink)) return
        localVideoTrack?.addSink(sink)
    }

    override fun detachLocalSink(sink: VideoSink) {
        localVideoTrack?.removeSink(sink)
        localSinks.remove(sink)
    }

    override fun attachLocalContentSink(sink: VideoSink) {
        if (!localContentSinks.add(sink)) return
        localContentVideoTrack?.addSink(sink)
    }

    override fun detachLocalContentSink(sink: VideoSink) {
        localContentVideoTrack?.removeSink(sink)
        localContentSinks.remove(sink)
    }

    override fun initRenderer(
        renderer: SurfaceViewRenderer,
        rendererEvents: RendererCommon.RendererEvents?
    ) {
        if (!initializedRenderers.add(renderer)) {
            return
        }
        renderer.init(eglBase.eglBaseContext, rendererEvents)
        // Hardware scaler issues setFixedSize(...) with a buffer smaller than the view.
        // On Android 9 / older SurfaceFlinger this leaves the surface anchored at top-left
        // after a remote resolution change, producing a black bar on the right until the
        // next forced layout pass. Sizing the buffer from layout avoids the race.
        renderer.setEnableHardwareScaler(false)
    }

    private fun applyAudioTrackHints() {
        val track = localAudioTrack ?: return
        runCatching {
            val method = track.javaClass.getMethod("setContentHint", String::class.java)
            method.invoke(track, "speech")
        }.onFailure {
            logger?.log(SerenadaLogLevel.DEBUG, "WebRTC", "Audio content hint not supported")
        }
    }

    private fun applyAudioSenderParameters(pc: PeerConnection) {
        val sender = pc.senders.firstOrNull { it.track()?.kind() == "audio" } ?: return
        try {
            val params = sender.parameters
            val encodings = params.encodings
            if (encodings.isNullOrEmpty()) return
            if (encodings[0].maxBitrateBps == null) return
            encodings[0].maxBitrateBps = null
            sender.setParameters(params)
            logger?.log(SerenadaLogLevel.DEBUG, "WebRTC", "Cleared audio sender max bitrate cap")
        } catch (e: Exception) {
            logger?.log(SerenadaLogLevel.WARNING, "WebRTC", "Failed to apply audio sender parameters: ${e.message}")
        }
    }

    private fun applyVideoSenderParameters() {
        peerSlots.forEach { slot ->
            slot.applyVideoSenderParameters(videoSenderPolicyForSlot(slot))
        }
    }

    /**
     * Per-slot video sender policy (FIX 2). A LEGACY slot's SINGLE video sender
     * carries the content (display) track during an independent share
     * (attachLocalTracksToSlot legacy precedence). Because [isLegacyScreenSharing]
     * is false in independent mode, [activeVideoSenderPolicy] would hand it the
     * CAMERA profile. Give that sender the conservative screen-content profile
     * instead (mirrors web's applyLegacyContentSenderEncoding); on share stop the
     * camera track returns to the sender and it reverts to the camera profile
     * (mirrors restoreLegacySenderCameraEncoding). Capable slots keep
     * [activeVideoSenderPolicy]: the slot internally applies the camera policy to
     * the camera sender and its own content profile to the content sender.
     *
     * Keyed on what the sender is carrying (legacy slot + active independent
     * share) rather than [isLegacyScreenSharing], which is false in independent
     * mode. Flag-off inert: never reached for a legacy share (that branch goes
     * through [isLegacyScreenSharing] in [activeVideoSenderPolicy]).
     */
    private fun videoSenderPolicyForSlot(slot: PeerConnectionSlot): VideoSenderPolicy {
        if (
            enableIndependentContentVideo &&
            !slot.supportsIndependentContentVideo &&
            screenShareController.isScreenSharing &&
            localContentVideoTrack != null
        ) {
            return contentVideoSenderPolicy()
        }
        return activeVideoSenderPolicy()
    }

    /**
     * Camera-sender policy. In LEGACY screen share the single sender carries the
     * display track, so it gets the screen-share profile. In INDEPENDENT mode the
     * camera sender keeps its camera profile (the content sender carries its own
     * profile via [contentVideoSenderPolicy]).
     */
    private fun activeVideoSenderPolicy(): VideoSenderPolicy {
        if (isLegacyScreenSharing) {
            return screenShareSenderPolicy()
        }
        if (!isHdVideoExperimentalEnabled) {
            return VideoSenderPolicy(
                maxBitrateBps = null,
                minBitrateBps = null,
                maxFramerate = null,
                degradationPreference = null
            )
        }
        return when (cameraController.currentCameraSource) {
            CameraCaptureController.LocalCameraSource.COMPOSITE -> VideoSenderPolicy(
                maxBitrateBps = CameraCaptureController.COMPOSITE_MAX_BITRATE_BPS,
                minBitrateBps = CameraCaptureController.COMPOSITE_MIN_BITRATE_BPS,
                maxFramerate = CameraCaptureController.COMPOSITE_TARGET_FPS,
                degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            )

            else -> VideoSenderPolicy(
                maxBitrateBps = CameraCaptureController.CAMERA_MAX_BITRATE_BPS,
                minBitrateBps = CameraCaptureController.CAMERA_MIN_BITRATE_BPS,
                maxFramerate = CameraCaptureController.CAMERA_TARGET_FPS,
                degradationPreference = RtpParameters.DegradationPreference.BALANCED
            )
        }
    }

    private fun screenShareSenderPolicy(): VideoSenderPolicy = VideoSenderPolicy(
        maxBitrateBps = SCREEN_SHARE_MAX_BITRATE_BPS,
        minBitrateBps = SCREEN_SHARE_MIN_BITRATE_BPS,
        maxFramerate = ScreenShareController.SCREEN_SHARE_TARGET_FPS,
        degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
    )

    /**
     * Conservative content (screen share) sender profile for the independent
     * content transceiver: legibility of mostly-static content over motion
     * (~1080p / ~5 fps / modest bitrate, design "Content encoding profile").
     */
    private fun contentVideoSenderPolicy(): VideoSenderPolicy = VideoSenderPolicy(
        maxBitrateBps = ScreenShareController.CONTENT_MAX_BITRATE_BPS,
        minBitrateBps = ScreenShareController.CONTENT_MIN_BITRATE_BPS,
        maxFramerate = ScreenShareController.CONTENT_TARGET_FPS,
        degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
    )

    private companion object {
        val WEBRTC_LOGGING_ENABLED = AtomicBoolean(false)

        const val SCREEN_SHARE_MAX_BITRATE_BPS = 5_000_000
        const val SCREEN_SHARE_MIN_BITRATE_BPS = 1_000_000
    }
}
