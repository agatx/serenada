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

class WebRtcEngine(
    context: Context,
    private val onCameraFacingChanged: (Boolean) -> Unit,
    private val onCameraModeChanged: (LocalCameraMode) -> Unit,
    private val onFlashlightStateChanged: (Boolean, Boolean) -> Unit,
    private val onScreenShareStopped: () -> Unit,
    private val onFeatureDegradation: (FeatureDegradationState) -> Unit = {},
    private var isHdVideoExperimentalEnabled: Boolean = false,
    private var isRemoteBlackFrameAnalysisEnabled: Boolean = true,
    private val logger: SerenadaLogger? = null,
) : SessionMediaEngine {

    data class VideoSenderPolicy(
        val maxBitrateBps: Int?,
        val minBitrateBps: Int?,
        val maxFramerate: Int?,
        val degradationPreference: RtpParameters.DegradationPreference?
    )

    private val appContext = context.applicationContext
    private val eglBase: EglBase = EglBase.create()
    private val audioDeviceModule: AudioDeviceModule = createAudioDeviceModule(appContext)
    private val peerConnectionFactory: PeerConnectionFactory
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private var released = false

    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: org.webrtc.VideoSource? = null
    private var audioSource: AudioSource? = null

    private val localSinks = LinkedHashSet<VideoSink>()
    private val peerSlots = LinkedHashSet<PeerConnectionSlot>()

    private var iceServers: List<PeerConnection.IceServer>? = null
    private val initializedRenderers =
        Collections.newSetFromMap(WeakHashMap<SurfaceViewRenderer, Boolean>())

    private val cameraController = CameraCaptureController(
        appContext = appContext,
        eglBase = eglBase,
        cameraManager = cameraManager,
        isHdVideoExperimentalEnabled = isHdVideoExperimentalEnabled,
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
            if (isSharing) {
                onCameraFacingChanged(false)
            }
        },
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

    override fun startLocalMedia() {
        if (released) return
        if (localAudioTrack != null || localVideoTrack != null) return
        cameraController.resetCameraState()
        val audioConstraints = MediaConstraints()
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource)
        applyAudioTrackHints()

        videoSource = peerConnectionFactory.createVideoSource(false)
        cameraController.resetCameraSourceToSelfie()
        if (!cameraController.restartVideoCapturer(CameraCaptureController.LocalCameraSource.SELFIE, videoSource)) {
            logger?.log(SerenadaLogLevel.WARNING, "WebRTC", "No camera capturer available for ${CameraCaptureController.LocalCameraSource.SELFIE}")
            videoSource?.dispose()
            videoSource = null
            localAudioTrack?.setEnabled(false)
            localAudioTrack = null
            audioSource?.dispose()
            audioSource = null
            return
        }
        localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", videoSource)
        localVideoTrack?.setEnabled(true)
        localSinks.forEach { sink ->
            localVideoTrack?.addSink(sink)
        }
        peerSlots.forEach { slot ->
            slot.attachLocalTracks(localAudioTrack, localVideoTrack)
        }
    }

    fun stopLocalMedia() {
        cameraController.resetCameraState()
        screenShareController.reset()
        localVideoTrack?.setEnabled(false)
        localAudioTrack?.setEnabled(false)
        cameraController.disposeVideoCapturer()
        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null
        localVideoTrack = null
        localAudioTrack = null
    }

    override fun release() {
        if (released) return
        released = true
        stopLocalMedia()
        peerSlots.toList().forEach { slot ->
            slot.closePeerConnection()
        }
        peerSlots.clear()
        localSinks.clear()
        runCatching { peerConnectionFactory.dispose() }
        runCatching { audioDeviceModule.release() }
        runCatching { eglBase.release() }
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

    override fun adjustWorldCameraZoom(scaleFactor: Float): Boolean {
        return cameraController.adjustWorldCameraZoom(scaleFactor)
    }

    override fun toggleAudio(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    override fun toggleVideo(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
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
        return screenShareController.startScreenShare(intent)
    }

    override fun stopScreenShare(): Boolean {
        return screenShareController.stopScreenShare()
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
    ): PeerConnectionSlotProtocol {
        val slot = PeerConnectionSlot(
            remoteCid = remoteCid,
            factory = peerConnectionFactory,
            iceServers = iceServers,
            localAudioTrack = localAudioTrack,
            localVideoTrack = localVideoTrack,
            onLocalIceCandidate = onLocalIceCandidate,
            onRemoteVideoTrack = onRemoteVideoTrack,
            onConnectionStateChange = onConnectionStateChange,
            onIceConnectionStateChange = onIceConnectionStateChange,
            onSignalingStateChange = onSignalingStateChange,
            onRenegotiationNeeded = onRenegotiationNeeded,
            applyAudioSenderParameters = ::applyAudioSenderParameters,
            currentVideoSenderPolicy = ::activeVideoSenderPolicy,
            isRemoteBlackFrameAnalysisEnabled = { isRemoteBlackFrameAnalysisEnabled },
            logger = logger,
        )
        peerSlots.add(slot)
        if (!iceServers.isNullOrEmpty()) {
            slot.ensurePeerConnection()
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

    override fun initRenderer(
        renderer: SurfaceViewRenderer,
        rendererEvents: RendererCommon.RendererEvents?
    ) {
        if (!initializedRenderers.add(renderer)) {
            return
        }
        renderer.init(eglBase.eglBaseContext, rendererEvents)
        renderer.setEnableHardwareScaler(true)
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
        val policy = activeVideoSenderPolicy()
        peerSlots.forEach { slot ->
            slot.applyVideoSenderParameters(policy)
        }
    }

    private fun activeVideoSenderPolicy(): VideoSenderPolicy {
        if (screenShareController.isScreenSharing) {
            return VideoSenderPolicy(
                maxBitrateBps = SCREEN_SHARE_MAX_BITRATE_BPS,
                minBitrateBps = SCREEN_SHARE_MIN_BITRATE_BPS,
                maxFramerate = ScreenShareController.SCREEN_SHARE_TARGET_FPS,
                degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            )
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

    private companion object {
        val WEBRTC_LOGGING_ENABLED = AtomicBoolean(false)

        const val SCREEN_SHARE_MAX_BITRATE_BPS = 5_000_000
        const val SCREEN_SHARE_MIN_BITRATE_BPS = 1_000_000
    }
}
