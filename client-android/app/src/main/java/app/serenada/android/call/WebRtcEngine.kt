package app.serenada.android.call

import android.content.Intent
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.DisplayMetrics
import android.util.Log
import app.serenada.android.BuildConfig
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.Logging
import org.webrtc.RendererCommon
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcEngine(
    context: Context,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionState: (PeerConnection.PeerConnectionState) -> Unit,
    private val onIceConnectionState: (PeerConnection.IceConnectionState) -> Unit,
    private val onSignalingState: (PeerConnection.SignalingState) -> Unit,
    private val onRenegotiationNeededCallback: () -> Unit,
    private val onRemoteVideoTrack: (VideoTrack?) -> Unit,
    private val onCameraFacingChanged: (Boolean) -> Unit,
    private val onCameraModeChanged: (LocalCameraMode) -> Unit,
    private val onFlashlightStateChanged: (Boolean, Boolean) -> Unit,
    private val onScreenShareStopped: () -> Unit,
    private var isHdVideoExperimentalEnabled: Boolean = false,
    private var isRemoteBlackFrameAnalysisEnabled: Boolean = true
) {
    private enum class LocalCameraSource {
        SELFIE,
        WORLD,
        COMPOSITE
    }

    private data class CapturerSelection(
        val capturer: VideoCapturer,
        val isFrontFacing: Boolean,
        val captureProfile: CaptureProfile,
        val torchCameraId: String?,
        val zoomCameraId: String?
    )

    private data class CaptureProfile(
        val width: Int,
        val height: Int,
        val fps: Int
    )

    private data class CapturePolicy(
        val targetWidth: Int,
        val targetHeight: Int,
        val targetFps: Int,
        val minFps: Int
    )

    private data class VideoSenderPolicy(
        val maxBitrateBps: Int?,
        val minBitrateBps: Int?,
        val maxFramerate: Int?,
        val degradationPreference: RtpParameters.DegradationPreference?
    )

    private data class ZoomCapabilities(
        val minRatio: Float,
        val maxRatio: Float,
        val sensorRect: Rect?
    )

    private val appContext = context.applicationContext
    private val eglBase: EglBase = EglBase.create()
    private val audioDeviceModule: AudioDeviceModule = createAudioDeviceModule(appContext)
    private val peerConnectionFactory: PeerConnectionFactory
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val fallbackTorchCameraId: String? = findTorchCameraId()
    private var released = false

    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var currentCameraSource = LocalCameraSource.SELFIE
    private var cameraSourceBeforeScreenShare: LocalCameraSource? = null
    private var isScreenSharing = false
    private var activeTorchCameraId: String? = null
    private var activeZoomCameraId: String? = null
    private var activeZoomCapabilities: ZoomCapabilities? = null
    private var isTorchPreferenceEnabled = false
    private var isTorchEnabled = false
    private var torchSyncRequired = false
    private var torchRetryRunnable: Runnable? = null
    private var torchRetryAttempts = 0
    private var desiredCameraZoomRatio = 1f
    private var appliedCameraZoomRatio = 1f
    private var compositeSupportCache: Pair<Pair<String, String>, Boolean>? = null
    private var compositeDisabledAfterFailure = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var localSink: VideoSink? = null
    private var remoteSink: VideoSink? = null
    private var remoteVideoTrack: VideoTrack? = null
    private val remoteBlackFrameAnalyzer = RemoteBlackFrameAnalyzer()
    private val remoteVideoStateSink = VideoSink { frame -> onRemoteVideoFrame(frame) }

    private var iceServers: List<PeerConnection.IceServer>? = null
    private var remoteDescriptionSet = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var iceCandidateCount = 0
    private var lastStatsTimestampMs: Double? = null
    private var lastOutboundVideoBytes: Long? = null
    private var lastInboundVideoBytes: Long? = null
    private val initializedRenderers =
        Collections.newSetFromMap(WeakHashMap<SurfaceViewRenderer, Boolean>())

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
        enableVerboseWebRtcLoggingIfDebug()
        Log.i("WebRtcEngine", "Using WebRTC provider: ${BuildConfig.WEBRTC_PROVIDER}")

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
        if (!BuildConfig.DEBUG) return
        if (!WEBRTC_LOGGING_ENABLED.compareAndSet(false, true)) return
        runCatching {
            Logging.enableLogThreads()
            Logging.enableLogTimeStamps()
            Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE)
            Log.i("WebRtcEngine", "Verbose native WebRTC logging enabled")
        }.onFailure { error ->
            Log.w("WebRtcEngine", "Failed to enable WebRTC verbose logging", error)
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
                        Log.w("WebRtcEngine", "AudioTrack init error: $errorMessage")
                    }

                    override fun onWebRtcAudioTrackStartError(
                        errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.w("WebRtcEngine", "AudioTrack start error: code=$errorCode message=$errorMessage")
                    }

                    override fun onWebRtcAudioTrackError(errorMessage: String?) {
                        Log.w("WebRtcEngine", "AudioTrack runtime error: $errorMessage")
                    }
                }
            )
            .setAudioRecordErrorCallback(
                object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                        Log.w("WebRtcEngine", "AudioRecord init error: $errorMessage")
                    }

                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.w("WebRtcEngine", "AudioRecord start error: code=$errorCode message=$errorMessage")
                    }

                    override fun onWebRtcAudioRecordError(errorMessage: String?) {
                        Log.w("WebRtcEngine", "AudioRecord runtime error: $errorMessage")
                    }
                }
            )
    }

    fun getEglContext(): EglBase.Context = eglBase.eglBaseContext

    fun startLocalMedia() {
        if (released) return
        if (localAudioTrack != null || localVideoTrack != null) return
        cancelTorchRetry()
        activeTorchCameraId = null
        activeZoomCameraId = null
        activeZoomCapabilities = null
        isTorchPreferenceEnabled = false
        torchSyncRequired = false
        setTorchEnabled(false, notify = false)
        desiredCameraZoomRatio = 1f
        appliedCameraZoomRatio = 1f
        val audioConstraints = MediaConstraints()
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource)
        applyAudioTrackHints()

        videoSource = peerConnectionFactory.createVideoSource(false)
        currentCameraSource = LocalCameraSource.SELFIE
        if (!restartVideoCapturer(currentCameraSource)) {
            Log.w("WebRtcEngine", "No camera capturer available for $currentCameraSource")
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
        localSink?.let { sink ->
            localVideoTrack?.addSink(sink)
        }
        createPeerConnectionIfReady()
    }

    fun stopLocalMedia() {
        cancelTorchRetry()
        activeTorchCameraId = null
        activeZoomCameraId = null
        activeZoomCapabilities = null
        isTorchPreferenceEnabled = false
        torchSyncRequired = false
        setTorchEnabled(false)
        desiredCameraZoomRatio = 1f
        appliedCameraZoomRatio = 1f
        isScreenSharing = false
        cameraSourceBeforeScreenShare = null
        localVideoTrack?.setEnabled(false)
        localAudioTrack?.setEnabled(false)
        disposeVideoCapturer()
        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null
        localVideoTrack = null
        localAudioTrack = null
    }

    fun closePeerConnection() {
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        remoteVideoTrack?.removeSink(remoteVideoStateSink)
        remoteVideoTrack = null
        remoteBlackFrameAnalyzer.onTrackDetached()
        remoteDescriptionSet = false
        pendingIceCandidates.clear()
        iceCandidateCount = 0
        lastStatsTimestampMs = null
        lastOutboundVideoBytes = null
        lastInboundVideoBytes = null
        onRemoteVideoTrack(null)
    }

    fun release() {
        if (released) return
        released = true
        stopLocalMedia()
        closePeerConnection()
        runCatching { peerConnectionFactory.dispose() }
        runCatching { audioDeviceModule.release() }
        runCatching { eglBase.release() }
    }

    fun setIceServers(servers: List<PeerConnection.IceServer>) {
        if (released) return
        Log.d("WebRtcEngine", "ICE servers set: ${servers.size}")
        iceServers = servers
        createPeerConnectionIfReady()
    }

    fun isReady(): Boolean = peerConnection != null
    fun ensurePeerConnection() {
        createPeerConnectionIfReady()
    }
    fun getSignalingState(): PeerConnection.SignalingState? = peerConnection?.signalingState()
    fun hasRemoteDescription(): Boolean = peerConnection?.remoteDescription != null

    fun rollbackLocalDescription(onComplete: ((Boolean) -> Unit)? = null) {
        val pc = peerConnection ?: return
        val desc = SessionDescription(SessionDescription.Type.ROLLBACK, "")
        pc.setLocalDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.d("WebRtcEngine", "Local description rolled back")
                onComplete?.invoke(true)
            }

            override fun onSetFailure(error: String?) {
                Log.w("WebRtcEngine", "Failed to rollback local description: $error")
                onComplete?.invoke(false)
            }
        }, desc)
    }

    fun flipCamera() {
        if (isScreenSharing) return
        if (videoSource == null) return
        val compositeAvailable = canUseCompositeSource()
        val targetMode = nextFlipCameraMode(
            current = activeCameraMode(),
            compositeAvailable = compositeAvailable
        )
        val target = cameraSourceFromMode(targetMode)
        if (!compositeAvailable && targetMode == LocalCameraMode.SELFIE && currentCameraSource == LocalCameraSource.WORLD) {
            Log.w("WebRtcEngine", "Transitioning from WORLD to SELFIE (COMPOSITE unavailable)")
        }
        if (restartVideoCapturer(target)) {
            return
        }
        Log.w("WebRtcEngine", "Failed to switch camera source to $target")
        val fallback = if (targetMode == LocalCameraMode.COMPOSITE) {
            compositeDisabledAfterFailure = true
            LocalCameraSource.SELFIE
        } else {
            currentCameraSource
        }
        if (fallback != target && restartVideoCapturer(fallback)) {
            Log.w("WebRtcEngine", "Camera source fallback applied: $fallback")
        }
    }

    fun adjustWorldCameraZoom(scaleFactor: Float): Boolean {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return false
        if (!isZoomAvailableForCurrentMode()) return false
        val capabilities = activeZoomCapabilities ?: return false
        val targetRatio =
            (desiredCameraZoomRatio * scaleFactor).coerceIn(
                capabilities.minRatio,
                capabilities.maxRatio
            )
        if (abs(targetRatio - desiredCameraZoomRatio) < ZOOM_RATIO_DELTA_EPSILON) {
            return false
        }
        desiredCameraZoomRatio = targetRatio
        return applyZoomForCurrentMode()
    }

    fun createOffer(
        iceRestart: Boolean = false,
        onSdp: (String) -> Unit,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        val pc = peerConnection ?: return false
        if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            Log.d("WebRtcEngine", "Skipping offer; signaling state is ${pc.signalingState()}")
            onComplete?.invoke(false)
            return false
        }
        Log.d("WebRtcEngine", "Creating offer")
        val constraints = MediaConstraints()
        if (iceRestart) {
            constraints.optional.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) {
                    onComplete?.invoke(false)
                    return
                }
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d("WebRtcEngine", "Local description set (offer)")
                        onSdp(desc.description)
                        onComplete?.invoke(true)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.w("WebRtcEngine", "Failed to set local offer: $error")
                        onComplete?.invoke(false)
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                Log.w("WebRtcEngine", "Offer creation failed: $error")
                onComplete?.invoke(false)
            }
        }, constraints)
        return true
    }

    fun createAnswer(onSdp: (String) -> Unit, onComplete: ((Boolean) -> Unit)? = null) {
        val pc = peerConnection ?: return
        Log.d("WebRtcEngine", "Creating answer")
        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) {
                    onComplete?.invoke(false)
                    return
                }
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d("WebRtcEngine", "Local description set (answer)")
                        onSdp(desc.description)
                        onComplete?.invoke(true)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.w("WebRtcEngine", "Failed to set local answer: $error")
                        onComplete?.invoke(false)
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                Log.w("WebRtcEngine", "Answer creation failed: $error")
                onComplete?.invoke(false)
            }
        }, constraints)
    }

    fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onComplete: (() -> Unit)? = null) {
        val pc = peerConnection ?: return
        val desc = SessionDescription(type, sdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingIceCandidates()
                Log.d("WebRtcEngine", "Remote description set ($type)")
                onComplete?.invoke()
            }

            override fun onSetFailure(error: String?) {
                Log.w("WebRtcEngine", "Failed to set remote description ($type): $error")
            }
        }, desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection ?: return
        if (!remoteDescriptionSet) {
            pendingIceCandidates.add(candidate)
            return
        }
        pc.addIceCandidate(candidate)
    }

    fun toggleAudio(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun toggleVideo(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun setHdVideoExperimentalEnabled(enabled: Boolean) {
        if (isHdVideoExperimentalEnabled == enabled) return
        isHdVideoExperimentalEnabled = enabled
        if (!isScreenSharing && localVideoTrack != null && videoSource != null) {
            if (!restartVideoCapturer(currentCameraSource)) {
                Log.w("WebRtcEngine", "Failed to apply HD video setting by restarting capturer")
            }
        }
        applyVideoSenderParameters()
    }

    fun toggleFlashlight(): Boolean {
        if (!isTorchAvailableForCurrentMode()) return false
        isTorchPreferenceEnabled = !isTorchPreferenceEnabled
        Log.d(
            "WebRtcEngine",
            "Flash toggle requested: preference=$isTorchPreferenceEnabled mode=${activeVideoModeLabel()} torchCamera=$activeTorchCameraId"
        )
        if (applyTorchForCurrentMode()) {
            return true
        }
        if (isTorchPreferenceEnabled) {
            scheduleTorchRetry()
        }
        isTorchPreferenceEnabled = isTorchEnabled
        notifyCameraModeAndFlash()
        return false
    }

    fun startScreenShare(intent: Intent): Boolean {
        if (isScreenSharing) return true
        val observer = videoSource?.capturerObserver ?: return false
        val previousSource = currentCameraSource
        cancelTorchRetry()
        activeTorchCameraId = null
        activeZoomCameraId = null
        activeZoomCapabilities = null
        torchSyncRequired = false
        setTorchEnabled(false, notify = false)
        appliedCameraZoomRatio = 1f
        disposeVideoCapturer()
        val capturer = ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    if (isScreenSharing) {
                        stopScreenShare()
                        onScreenShareStopped()
                    }
                }
            }
        })
        val textureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
        val captureProfile = selectScreenShareCaptureProfile()
        return try {
            capturer.initialize(textureHelper, appContext, observer)
            capturer.startCapture(captureProfile.width, captureProfile.height, captureProfile.fps)
            videoCapturer = capturer
            surfaceTextureHelper = textureHelper
            cameraSourceBeforeScreenShare = previousSource
            isScreenSharing = true
            applyVideoSenderParameters()
            Log.d(
                "WebRtcEngine",
                "Screen share capture profile: ${captureProfile.width}x${captureProfile.height}@${captureProfile.fps}fps"
            )
            onCameraFacingChanged(false)
            applyTorchForCurrentMode()
            true
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "Failed to start screen sharing", e)
            runCatching { capturer.dispose() }
            runCatching { textureHelper.dispose() }
            if (!restartVideoCapturer(previousSource)) {
                restartVideoCapturer(LocalCameraSource.SELFIE)
            }
            false
        }
    }

    fun stopScreenShare(): Boolean {
        if (!isScreenSharing) return true
        val sourceToRestore = cameraSourceBeforeScreenShare ?: currentCameraSource
        isScreenSharing = false
        cameraSourceBeforeScreenShare = null
        disposeVideoCapturer()
        if (!restartVideoCapturer(sourceToRestore) && !restartVideoCapturer(LocalCameraSource.SELFIE)) {
            Log.w("WebRtcEngine", "Failed to restore camera after screen sharing stop")
        }
        return true
    }

    fun setRemoteBlackFrameAnalysisEnabled(enabled: Boolean) {
        isRemoteBlackFrameAnalysisEnabled = enabled
    }

    fun isRemoteVideoTrackEnabled(): Boolean {
        val track = remoteVideoTrack ?: return false
        if (!track.enabled()) return false
        if (remoteBlackFrameAnalyzer.isVideoConsideredOff()) {
            return false
        }
        return true
    }

    fun remoteVideoDiagnostics(): String {
        val track = remoteVideoTrack
        return remoteBlackFrameAnalyzer.diagnostics(
            trackPresent = track != null,
            trackEnabled = track?.enabled() == true
        )
    }

    fun collectWebRtcStatsSummary(onComplete: (String) -> Unit) {
        val pc = peerConnection
        if (pc == null) {
            onComplete("pc=none")
            return
        }
        pc.getStats { report ->
            onComplete(buildWebRtcStatsSummary(report))
        }
    }

    fun attachLocalRenderer(
        renderer: SurfaceViewRenderer,
        rendererEvents: RendererCommon.RendererEvents? = null
    ) {
        initRenderer(renderer, rendererEvents)
        attachLocalSink(renderer)
    }

    fun attachRemoteRenderer(
        renderer: SurfaceViewRenderer,
        rendererEvents: RendererCommon.RendererEvents? = null
    ) {
        initRenderer(renderer, rendererEvents)
        attachRemoteSink(renderer)
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        detachLocalSink(renderer)
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        detachRemoteSink(renderer)
    }

    fun attachLocalSink(sink: VideoSink) {
        if (localSink === sink) return
        localSink?.let { previous -> localVideoTrack?.removeSink(previous) }
        localSink = sink
        localVideoTrack?.addSink(sink)
    }

    fun detachLocalSink(sink: VideoSink) {
        localVideoTrack?.removeSink(sink)
        if (localSink === sink) {
            localSink = null
        }
    }

    fun attachRemoteSink(sink: VideoSink) {
        if (remoteSink === sink) return
        remoteSink?.let { previous -> remoteVideoTrack?.removeSink(previous) }
        remoteSink = sink
        remoteVideoTrack?.addSink(sink)
    }

    fun detachRemoteSink(sink: VideoSink) {
        remoteVideoTrack?.removeSink(sink)
        if (remoteSink === sink) {
            remoteSink = null
        }
    }

    private fun initRenderer(
        renderer: SurfaceViewRenderer,
        rendererEvents: RendererCommon.RendererEvents?
    ) {
        if (!initializedRenderers.add(renderer)) {
            return
        }
        renderer.init(eglBase.eglBaseContext, rendererEvents)
        renderer.setEnableHardwareScaler(true)
    }

    private fun createPeerConnectionIfReady() {
        if (peerConnection != null) return
        val servers = iceServers ?: return
        if (localAudioTrack == null && localVideoTrack == null) return

        remoteDescriptionSet = false
        pendingIceCandidates.clear()
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                iceCandidateCount += 1
                if (iceCandidateCount <= 5 || iceCandidateCount % 25 == 0) {
                    Log.d("WebRtcEngine", "Local ICE candidate #$iceCandidateCount")
                }
                onLocalIceCandidate(candidate)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d("WebRtcEngine", "Connection state: $newState")
                onConnectionState(newState)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack?.removeSink(remoteVideoStateSink)
                    remoteVideoTrack = track
                    remoteBlackFrameAnalyzer.onTrackAttached()
                    Log.d(
                        "WebRtcEngine",
                        "[RemoteVideo] onTrack attached, trackEnabled=${track.enabled()}"
                    )
                    track.addSink(remoteVideoStateSink)
                    remoteSink?.let { sink -> track.addSink(sink) }
                    onRemoteVideoTrack(track)
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                Log.d("WebRtcEngine", "Signaling state: $newState")
                onSignalingState(newState)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d("WebRtcEngine", "ICE state: $newState")
                onIceConnectionState(newState)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
            }

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                Log.d("WebRtcEngine", "ICE gathering: $newState")
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
            }

            override fun onAddStream(stream: org.webrtc.MediaStream) {
            }

            override fun onRemoveStream(stream: org.webrtc.MediaStream) {
            }

            override fun onDataChannel(dc: org.webrtc.DataChannel) {
            }

            override fun onRenegotiationNeeded() {
                onRenegotiationNeededCallback()
            }
        })

        peerConnection?.let { pc ->
            localAudioTrack?.let { pc.addTrack(it, listOf("serenada")) }
            localVideoTrack?.let { pc.addTrack(it, listOf("serenada")) }
            ensureReceiveTransceivers(pc)
            applyAudioSenderParameters(pc)
            applyVideoSenderParameters(pc)
        }
    }

    private fun ensureReceiveTransceivers(pc: PeerConnection) {
        if (localAudioTrack == null) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
        if (localVideoTrack == null) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
    }

    private fun flushPendingIceCandidates() {
        val pc = peerConnection ?: return
        if (pendingIceCandidates.isEmpty()) return
        val pending = pendingIceCandidates.toList()
        pendingIceCandidates.clear()
        pending.forEach { pc.addIceCandidate(it) }
    }

    private fun applyAudioTrackHints() {
        val track = localAudioTrack ?: return
        runCatching {
            val method = track.javaClass.getMethod("setContentHint", String::class.java)
            method.invoke(track, "speech")
        }.onFailure {
            Log.d("WebRtcEngine", "Audio content hint not supported")
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
            Log.d("WebRtcEngine", "Cleared audio sender max bitrate cap")
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "Failed to apply audio sender parameters", e)
        }
    }

    private fun applyVideoSenderParameters(pc: PeerConnection? = peerConnection) {
        val activePc = pc ?: return
        val sender = activePc.senders.firstOrNull { it.track()?.kind() == "video" } ?: return
        val policy = activeVideoSenderPolicy()
        try {
            val params = sender.parameters
            val encodings = params.encodings
            if (encodings.isNullOrEmpty()) return
            params.degradationPreference = policy.degradationPreference
            encodings[0].maxBitrateBps = policy.maxBitrateBps
            encodings[0].minBitrateBps = policy.minBitrateBps
            encodings[0].maxFramerate = policy.maxFramerate
            sender.setParameters(params)
            Log.d(
                "WebRtcEngine",
                "Video sender policy applied: max=${policy.maxBitrateBps ?: "default"} min=${policy.minBitrateBps ?: "default"} fps=${policy.maxFramerate ?: "default"} mode=${activeVideoModeLabel()}"
            )
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "Failed to apply video sender parameters", e)
        }
    }

    private fun restartVideoCapturer(source: LocalCameraSource): Boolean {
        val observer = videoSource?.capturerObserver ?: return false
        disposeVideoCapturer()
        val selection = createVideoCapturer(source) ?: return false
        val textureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        try {
            selection.capturer.initialize(textureHelper, appContext, observer)
            selection.capturer.startCapture(
                selection.captureProfile.width,
                selection.captureProfile.height,
                selection.captureProfile.fps
            )
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "Failed to start capture for $source", e)
            runCatching { selection.capturer.dispose() }
            runCatching { textureHelper.dispose() }
            return false
        }
        videoCapturer = selection.capturer
        surfaceTextureHelper = textureHelper
        currentCameraSource = source
        activeTorchCameraId = selection.torchCameraId
        activeZoomCameraId = selection.zoomCameraId
        activeZoomCapabilities = queryZoomCapabilities(activeZoomCameraId)
        desiredCameraZoomRatio = clampZoomRatioForCapabilities(desiredCameraZoomRatio, activeZoomCapabilities)
        appliedCameraZoomRatio = 1f
        isTorchEnabled = false
        torchSyncRequired = true
        onCameraFacingChanged(selection.isFrontFacing)
        applyTorchForCurrentMode()
        if (isZoomAvailableForCurrentMode()) {
            applyZoomForCurrentMode()
        }
        applyVideoSenderParameters()
        Log.d(
            "WebRtcEngine",
            "Camera source active: $source (${selection.captureProfile.width}x${selection.captureProfile.height}@${selection.captureProfile.fps}fps)"
        )
        return true
    }

    private fun disposeVideoCapturer() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "Failed to stop capture", e)
        }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
    }

    private fun createVideoCapturer(source: LocalCameraSource): CapturerSelection? {
        val enumerator = Camera2Enumerator(appContext)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val back = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        return when (source) {
            LocalCameraSource.SELFIE -> {
                if (front != null) {
                    enumerator.createCapturer(front, null)?.let {
                        CapturerSelection(
                            capturer = it,
                            isFrontFacing = true,
                            captureProfile = selectCameraCaptureProfile(
                                enumerator = enumerator,
                                deviceNames = listOf(front),
                                policy = cameraCapturePolicyFor(LocalCameraSource.SELFIE)
                            ),
                            torchCameraId = null,
                            zoomCameraId = front
                        )
                    }
                } else if (back != null) {
                    enumerator.createCapturer(back, null)?.let {
                        CapturerSelection(
                            capturer = it,
                            isFrontFacing = false,
                            captureProfile = selectCameraCaptureProfile(
                                enumerator = enumerator,
                                deviceNames = listOf(back),
                                policy = cameraCapturePolicyFor(LocalCameraSource.SELFIE)
                            ),
                            torchCameraId = back.takeIf { hasFlashUnit(it) },
                            zoomCameraId = back
                        )
                    }
                } else {
                    null
                }
            }

            LocalCameraSource.WORLD -> {
                if (back == null) {
                    null
                } else {
                    enumerator.createCapturer(back, null)?.let {
                        CapturerSelection(
                            capturer = it,
                            isFrontFacing = false,
                            captureProfile = selectCameraCaptureProfile(
                                enumerator = enumerator,
                                deviceNames = listOf(back),
                                policy = cameraCapturePolicyFor(LocalCameraSource.WORLD)
                            ),
                            torchCameraId = back.takeIf { hasFlashUnit(it) },
                            zoomCameraId = back
                        )
                    }
                }
            }

            LocalCameraSource.COMPOSITE -> {
                if (front == null || back == null) {
                    null
                } else if (!canUseCompositeSource(frontDevice = front, backDevice = back)) {
                    null
                } else {
                    val mainCapturer = enumerator.createCapturer(back, null)
                    val overlayCapturer = enumerator.createCapturer(front, null)
                    if (mainCapturer == null || overlayCapturer == null) {
                        mainCapturer?.dispose()
                        overlayCapturer?.dispose()
                        null
                    } else {
                        val profile = selectCameraCaptureProfile(
                            enumerator = enumerator,
                            deviceNames = listOf(back, front),
                            policy = cameraCapturePolicyFor(LocalCameraSource.COMPOSITE)
                        )
                        CapturerSelection(
                            capturer = CompositeCameraCapturer(
                                context = appContext,
                                eglContext = eglBase.eglBaseContext,
                                mainCapturer = mainCapturer,
                                overlayCapturer = overlayCapturer,
                                onStartFailure = { onCompositeStartFailure() }
                            ),
                            isFrontFacing = false,
                            captureProfile = profile,
                            torchCameraId = back.takeIf { hasFlashUnit(it) },
                            zoomCameraId = back
                        )
                    }
                }
            }
        }
    }

    private fun cameraCapturePolicyFor(source: LocalCameraSource): CapturePolicy {
        if (!isHdVideoExperimentalEnabled) {
            return CapturePolicy(
                targetWidth = LEGACY_CAMERA_WIDTH,
                targetHeight = LEGACY_CAMERA_HEIGHT,
                targetFps = LEGACY_CAMERA_FPS,
                minFps = LEGACY_CAMERA_MIN_FPS
            )
        }
        return when (source) {
            LocalCameraSource.COMPOSITE -> {
                CapturePolicy(
                    targetWidth = COMPOSITE_TARGET_WIDTH,
                    targetHeight = COMPOSITE_TARGET_HEIGHT,
                    targetFps = COMPOSITE_TARGET_FPS,
                    minFps = COMPOSITE_MIN_FPS
                )
            }

            else -> {
                CapturePolicy(
                    targetWidth = CAMERA_TARGET_WIDTH,
                    targetHeight = CAMERA_TARGET_HEIGHT,
                    targetFps = CAMERA_TARGET_FPS,
                    minFps = CAMERA_MIN_FPS
                )
            }
        }
    }

    private fun activeVideoModeLabel(): String {
        if (isScreenSharing) return "screen_share"
        return when (currentCameraSource) {
            LocalCameraSource.SELFIE -> "selfie"
            LocalCameraSource.WORLD -> "world"
            LocalCameraSource.COMPOSITE -> "composite"
        }
    }

    private fun activeVideoSenderPolicy(): VideoSenderPolicy {
        if (isScreenSharing) {
            return VideoSenderPolicy(
                maxBitrateBps = SCREEN_SHARE_MAX_BITRATE_BPS,
                minBitrateBps = SCREEN_SHARE_MIN_BITRATE_BPS,
                maxFramerate = SCREEN_SHARE_TARGET_FPS,
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
        return when (currentCameraSource) {
            LocalCameraSource.COMPOSITE -> VideoSenderPolicy(
                maxBitrateBps = COMPOSITE_MAX_BITRATE_BPS,
                minBitrateBps = COMPOSITE_MIN_BITRATE_BPS,
                maxFramerate = COMPOSITE_TARGET_FPS,
                degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            )

            else -> VideoSenderPolicy(
                maxBitrateBps = CAMERA_MAX_BITRATE_BPS,
                minBitrateBps = CAMERA_MIN_BITRATE_BPS,
                maxFramerate = CAMERA_TARGET_FPS,
                degradationPreference = RtpParameters.DegradationPreference.BALANCED
            )
        }
    }

    private fun selectCameraCaptureProfile(
        enumerator: Camera2Enumerator,
        deviceNames: List<String>,
        policy: CapturePolicy
    ): CaptureProfile {
        if (deviceNames.isEmpty()) {
            return defaultCaptureProfile(policy)
        }
        val formatsByDevice = deviceNames.mapNotNull { deviceName ->
            val bestFpsByResolution = (enumerator.getSupportedFormats(deviceName) ?: emptyList())
                .groupBy { Pair(it.width, it.height) }
                .mapValues { (_, formats) ->
                    formats.maxOfOrNull { format -> normalizeFps(format.framerate.max) } ?: policy.targetFps
                }
            if (bestFpsByResolution.isEmpty()) null else bestFpsByResolution
        }
        if (formatsByDevice.isEmpty()) {
            return defaultCaptureProfile(policy)
        }

        var commonResolutions: Set<Pair<Int, Int>> = formatsByDevice.first().keys
        formatsByDevice.drop(1).forEach { map ->
            commonResolutions = commonResolutions.intersect(map.keys)
        }
        if (commonResolutions.isNotEmpty()) {
            val commonFpsByResolution = commonResolutions.associateWith { size ->
                formatsByDevice.minOf { formatMap ->
                    formatMap[size] ?: policy.targetFps
                }
            }
            chooseProfileForPolicy(commonFpsByResolution, policy)?.let { return it }
        }

        val perDeviceProfiles = formatsByDevice.mapNotNull { map ->
            chooseProfileForPolicy(map, policy)
        }
        if (perDeviceProfiles.isNotEmpty()) {
            return CaptureProfile(
                width = perDeviceProfiles.minOf { it.width },
                height = perDeviceProfiles.minOf { it.height },
                fps = perDeviceProfiles.minOf { it.fps }
            )
        }

        return defaultCaptureProfile(policy)
    }

    private fun chooseProfileForPolicy(
        fpsByResolution: Map<Pair<Int, Int>, Int>,
        policy: CapturePolicy
    ): CaptureProfile? {
        val profiles = fpsByResolution.map { (size, fps) ->
            CaptureProfile(
                width = normalizeDimension(size.first),
                height = normalizeDimension(size.second),
                fps = normalizeFps(fps)
            )
        }
        if (profiles.isEmpty()) return null
        val inTargetBounds = profiles.filter { profileFitsPolicyBounds(it, policy) }
        val candidatePool = if (inTargetBounds.isNotEmpty()) inTargetBounds else profiles
        val targetArea = policy.targetWidth * policy.targetHeight
        val targetFps = policy.targetFps
        val minFps = policy.minFps
        val chosen = candidatePool.minWithOrNull(
            compareBy<CaptureProfile>(
                { if (it.fps >= minFps) 0 else 1 },
                { if (it.width * it.height <= targetArea) 0 else 1 },
                { abs((it.width * it.height) - targetArea) },
                { abs(it.fps - targetFps) },
                { -(it.width * it.height) },
                { -it.fps }
            )
        ) ?: return null
        val selectedFps = if (chosen.fps >= minFps) {
            min(chosen.fps, targetFps)
        } else {
            chosen.fps
        }
        return chosen.copy(fps = normalizeFps(selectedFps))
    }

    private fun profileFitsPolicyBounds(profile: CaptureProfile, policy: CapturePolicy): Boolean {
        val profileLong = max(profile.width, profile.height)
        val profileShort = min(profile.width, profile.height)
        val targetLong = max(policy.targetWidth, policy.targetHeight)
        val targetShort = min(policy.targetWidth, policy.targetHeight)
        return profileLong <= targetLong && profileShort <= targetShort
    }

    private fun selectScreenShareCaptureProfile(): CaptureProfile {
        val (rawWidth, rawHeight) = readDisplaySize()
        val (width, height) = clampResolutionToTarget(
            width = rawWidth,
            height = rawHeight,
            targetWidth = SCREEN_SHARE_MAX_WIDTH,
            targetHeight = SCREEN_SHARE_MAX_HEIGHT
        )
        val displayFps = readDisplayFps()
        val fps = displayFps.coerceIn(SCREEN_SHARE_MIN_FPS, SCREEN_SHARE_TARGET_FPS)
        return CaptureProfile(
            width = width,
            height = height,
            fps = normalizeFps(fps)
        )
    }

    private fun readDisplaySize(): Pair<Int, Int> {
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        if (windowManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return Pair(bounds.width(), bounds.height())
                }
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return Pair(metrics.widthPixels, metrics.heightPixels)
                }
            }
        }
        return Pair(SCREEN_SHARE_MAX_WIDTH, SCREEN_SHARE_MAX_HEIGHT)
    }

    private fun clampResolutionToTarget(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Pair<Int, Int> {
        val safeWidth = width.coerceAtLeast(2)
        val safeHeight = height.coerceAtLeast(2)
        val scale = min(
            1.0,
            min(
                targetWidth.toDouble() / safeWidth.toDouble(),
                targetHeight.toDouble() / safeHeight.toDouble()
            )
        )
        val scaledWidth = normalizeDimension((safeWidth * scale).roundToInt())
        val scaledHeight = normalizeDimension((safeHeight * scale).roundToInt())
        return Pair(scaledWidth.coerceAtLeast(2), scaledHeight.coerceAtLeast(2))
    }

    private fun readDisplayFps(): Int {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        @Suppress("DEPRECATION")
        val refreshRate = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate
        if (refreshRate != null && refreshRate > 0f) {
            return refreshRate.roundToInt()
        }
        return SCREEN_SHARE_TARGET_FPS
    }

    private fun normalizeDimension(value: Int): Int {
        val positive = value.coerceAtLeast(2)
        return if (positive % 2 == 0) positive else positive - 1
    }

    private fun normalizeFps(value: Int): Int {
        val normalized = if (value > 1000) value / 1000 else value
        return normalized.coerceIn(1, MAX_CAPTURE_FPS)
    }

    private fun defaultCaptureProfile(policy: CapturePolicy): CaptureProfile {
        return CaptureProfile(
            width = normalizeDimension(policy.targetWidth),
            height = normalizeDimension(policy.targetHeight),
            fps = normalizeFps(policy.targetFps)
        )
    }

    private fun onCompositeStartFailure() {
        mainHandler.post {
            if (currentCameraSource != LocalCameraSource.COMPOSITE) return@post
            if (videoCapturer !is CompositeCameraCapturer) return@post
            compositeDisabledAfterFailure = true
            Log.w("WebRtcEngine", "Composite source failed; disabling composite and falling back to selfie")
            if (restartVideoCapturer(LocalCameraSource.SELFIE)) {
                Log.w("WebRtcEngine", "Camera source fallback applied: ${LocalCameraSource.SELFIE}")
            }
        }
    }

    private fun canUseCompositeSource(): Boolean {
        val enumerator = Camera2Enumerator(appContext)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: return false
        val back = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) } ?: return false
        return canUseCompositeSource(frontDevice = front, backDevice = back)
    }

    private fun canUseCompositeSource(frontDevice: String, backDevice: String): Boolean {
        if (compositeDisabledAfterFailure) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API < 30 has no stable concurrent-camera query API, so allow trial.
            return true
        }
        val cacheKey = Pair(frontDevice, backDevice)
        compositeSupportCache?.let { (savedKey, savedValue) ->
            if (savedKey == cacheKey) {
                return savedValue
            }
        }
        val manager = appContext.getSystemService(CameraManager::class.java) ?: return false
        val supported = runCatching {
            manager.concurrentCameraIds.any { ids ->
                ids.contains(frontDevice) && ids.contains(backDevice)
            }
        }.getOrDefault(false)
        compositeSupportCache = Pair(cacheKey, supported)
        if (!supported) {
            Log.w(
                "WebRtcEngine",
                "Composite source unsupported by concurrent camera constraints. front=$frontDevice back=$backDevice"
            )
        }
        return supported
    }

    private fun findTorchCameraId(): String? {
        val manager = cameraManager ?: return null
        return runCatching {
            manager.cameraIdList.firstOrNull { cameraId ->
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                hasFlash && lensFacing == CameraCharacteristics.LENS_FACING_BACK
            }
        }.onFailure { error ->
            Log.w("WebRtcEngine", "Failed to query torch camera id", error)
        }.getOrNull()
    }

    private fun hasFlashUnit(cameraId: String): Boolean {
        val manager = cameraManager ?: return false
        return runCatching {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }.onFailure { error ->
            Log.w("WebRtcEngine", "Failed to query flash availability for camera=$cameraId", error)
        }.getOrDefault(false)
    }

    private fun supportsZoomForSource(source: LocalCameraSource): Boolean {
        return source == LocalCameraSource.WORLD || source == LocalCameraSource.COMPOSITE
    }

    private fun isZoomAvailableForCurrentMode(): Boolean {
        if (isScreenSharing) return false
        if (!supportsZoomForSource(currentCameraSource)) return false
        val capabilities = activeZoomCapabilities ?: return false
        return capabilities.maxRatio > capabilities.minRatio + ZOOM_RATIO_DELTA_EPSILON
    }

    private fun clampZoomRatioForCapabilities(
        ratio: Float,
        capabilities: ZoomCapabilities?
    ): Float {
        val caps = capabilities ?: return 1f
        return ratio.coerceIn(caps.minRatio, caps.maxRatio)
    }

    private fun requestedZoomRatioForCurrentMode(): Float {
        if (!isZoomAvailableForCurrentMode()) return 1f
        return clampZoomRatioForCapabilities(desiredCameraZoomRatio, activeZoomCapabilities)
    }

    private fun queryZoomCapabilities(cameraId: String?): ZoomCapabilities? {
        val manager = cameraManager ?: return null
        val activeCameraId = cameraId ?: return null
        return runCatching {
            val characteristics = manager.getCameraCharacteristics(activeCameraId)
            val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            var minRatio = 1f
            var maxRatio =
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?.coerceAtLeast(1f)
                    ?: 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    minRatio = max(1f, zoomRange.lower)
                    // Keep the larger max as a guard against inconsistent vendor-reported limits.
                    maxRatio = max(maxRatio, zoomRange.upper)
                }
            }
            if (maxRatio <= minRatio + ZOOM_RATIO_DELTA_EPSILON) {
                null
            } else {
                ZoomCapabilities(
                    minRatio = minRatio,
                    maxRatio = maxRatio,
                    sensorRect = sensorRect
                )
            }
        }.onFailure { error ->
            Log.w("WebRtcEngine", "Failed to query zoom capabilities for camera=$activeCameraId", error)
        }.getOrNull()
    }

    private fun supportsTorchForSource(source: LocalCameraSource): Boolean {
        val supportsMode = source == LocalCameraSource.WORLD || source == LocalCameraSource.COMPOSITE
        if (!supportsMode) return false
        return activeTorchCameraId != null || fallbackTorchCameraId != null
    }

    private fun isTorchAvailableForCurrentMode(): Boolean {
        if (isScreenSharing) return false
        return supportsTorchForSource(currentCameraSource)
    }

    private fun applyTorchForCurrentMode(): Boolean {
        cancelTorchRetry()
        val shouldEnable = isTorchAvailableForCurrentMode() && isTorchPreferenceEnabled
        val allowGlobalFallback = !shouldEnable || !torchSyncRequired
        val applied = setTorchEnabled(
            enabled = shouldEnable,
            notify = false,
            allowGlobalFallback = allowGlobalFallback
        )
        if (!applied && shouldEnable && torchSyncRequired) {
            scheduleTorchRetry()
        }
        notifyCameraModeAndFlash()
        return applied
    }

    private fun applyZoomForCurrentMode(): Boolean {
        if (!isZoomAvailableForCurrentMode()) {
            appliedCameraZoomRatio = 1f
            return false
        }
        val session = resolveActiveCamera2Session() ?: return false
        val cameraHandler = readFieldValue(session, "cameraThreadHandler") as? Handler ?: return false
        val targetZoomRatio = requestedZoomRatioForCurrentMode()
        if (abs(targetZoomRatio - appliedCameraZoomRatio) < ZOOM_RATIO_DELTA_EPSILON) {
            return true
        }
        cameraHandler.post {
            runCatching {
                applyCameraControlsViaCaptureRequestInternal(
                    session = session,
                    cameraHandler = cameraHandler,
                    torchEnabled = isTorchEnabled,
                    zoomRatio = targetZoomRatio
                )
            }.onSuccess { applied ->
                if (applied) {
                    appliedCameraZoomRatio = targetZoomRatio
                }
            }.onFailure { error ->
                Log.w("WebRtcEngine", "Failed to apply zoom via capture request", error)
            }
        }
        return true
    }

    private fun setTorchEnabled(
        enabled: Boolean,
        notify: Boolean = true,
        allowGlobalFallback: Boolean = true
    ): Boolean {
        if (isTorchEnabled == enabled && !torchSyncRequired) {
            if (notify) {
                notifyCameraModeAndFlash()
            }
            return true
        }
        if (applyTorchViaCaptureRequest(enabled, requestedZoomRatioForCurrentMode())) {
            isTorchEnabled = enabled
            torchSyncRequired = false
            Log.d(
                "WebRtcEngine",
                "Torch mode set via capture request: enabled=$enabled mode=${activeVideoModeLabel()} camera=$activeTorchCameraId"
            )
            if (notify) {
                notifyCameraModeAndFlash()
            }
            return true
        }
        if (!allowGlobalFallback) {
            if (!enabled) {
                isTorchEnabled = false
                torchSyncRequired = false
                if (notify) {
                    notifyCameraModeAndFlash()
                }
            }
            return false
        }
        val manager = cameraManager
        val cameraId = activeTorchCameraId ?: fallbackTorchCameraId
        if (manager == null || cameraId == null) {
            if (!enabled) {
                isTorchEnabled = false
                torchSyncRequired = false
                if (notify) {
                    notifyCameraModeAndFlash()
                }
                return true
            }
            Log.w("WebRtcEngine", "Torch unavailable. managerPresent=${manager != null} cameraId=$cameraId")
            return false
        }
        return try {
            manager.setTorchMode(cameraId, enabled)
            isTorchEnabled = enabled
            torchSyncRequired = false
            Log.d("WebRtcEngine", "Torch mode set: enabled=$enabled cameraId=$cameraId")
            if (notify) {
                notifyCameraModeAndFlash()
            }
            true
        } catch (error: CameraAccessException) {
            Log.w("WebRtcEngine", "Failed to set torch mode", error)
            if (!enabled) {
                isTorchEnabled = false
                torchSyncRequired = false
                if (notify) {
                    notifyCameraModeAndFlash()
                }
            }
            false
        } catch (error: SecurityException) {
            Log.w("WebRtcEngine", "Torch permission denied", error)
            if (!enabled) {
                isTorchEnabled = false
                torchSyncRequired = false
                if (notify) {
                    notifyCameraModeAndFlash()
                }
            }
            false
        } catch (error: IllegalArgumentException) {
            Log.w("WebRtcEngine", "Torch camera id invalid", error)
            if (!enabled) {
                isTorchEnabled = false
                torchSyncRequired = false
                if (notify) {
                    notifyCameraModeAndFlash()
                }
            }
            false
        }
    }

    private fun scheduleTorchRetry() {
        if (!torchSyncRequired) return
        if (!isTorchPreferenceEnabled) return
        if (!isTorchAvailableForCurrentMode()) return
        if (torchRetryRunnable != null) return
        torchRetryAttempts = 0
        val runnable = object : Runnable {
            override fun run() {
                torchRetryRunnable = null
                if (!torchSyncRequired || !isTorchPreferenceEnabled || !isTorchAvailableForCurrentMode()) {
                    notifyCameraModeAndFlash()
                    return
                }
                val applied = setTorchEnabled(
                    enabled = true,
                    notify = false,
                    allowGlobalFallback = false
                )
                notifyCameraModeAndFlash()
                if (applied) {
                    return
                }
                torchRetryAttempts += 1
                if (torchRetryAttempts >= MAX_TORCH_RETRY_ATTEMPTS) {
                    return
                }
                torchRetryRunnable = this
                mainHandler.postDelayed(this, TORCH_RETRY_DELAY_MS)
            }
        }
        torchRetryRunnable = runnable
        mainHandler.postDelayed(runnable, TORCH_RETRY_DELAY_MS)
    }

    private fun cancelTorchRetry() {
        torchRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        torchRetryRunnable = null
        torchRetryAttempts = 0
    }

    private fun applyTorchViaCaptureRequest(enabled: Boolean, zoomRatio: Float): Boolean {
        val session = resolveActiveCamera2Session() ?: return false
        val cameraHandler = readFieldValue(session, "cameraThreadHandler") as? Handler ?: return false
        val latch = CountDownLatch(1)
        var applied = false
        cameraHandler.post {
            applied = runCatching {
                applyCameraControlsViaCaptureRequestInternal(
                    session = session,
                    cameraHandler = cameraHandler,
                    torchEnabled = enabled,
                    zoomRatio = zoomRatio
                )
            }
                .onFailure { error ->
                    Log.w("WebRtcEngine", "Failed to apply torch via capture request", error)
                }
                .getOrDefault(false)
            latch.countDown()
        }
        if (!latch.await(750, TimeUnit.MILLISECONDS)) {
            Log.w("WebRtcEngine", "Timed out waiting for torch capture request")
            return false
        }
        if (applied && isZoomAvailableForCurrentMode()) {
            appliedCameraZoomRatio = zoomRatio
        }
        return applied
    }

    private fun applyCameraControlsViaCaptureRequestInternal(
        session: Any,
        cameraHandler: Handler,
        torchEnabled: Boolean,
        zoomRatio: Float
    ): Boolean {
        val captureSession = readFieldValue(session, "captureSession") as? CameraCaptureSession ?: return false
        val cameraDevice = readFieldValue(session, "cameraDevice") as? CameraDevice ?: return false
        val surface = readFieldValue(session, "surface") as? android.view.Surface ?: return false
        val captureFormat = readFieldValue(session, "captureFormat") ?: return false
        val framerate = readFieldValue(captureFormat, "framerate") ?: return false
        val minFps = readFieldValue(framerate, "min") as? Int ?: return false
        val maxFps = readFieldValue(framerate, "max") as? Int ?: return false
        val fpsUnitFactor = readFieldValue(session, "fpsUnitFactor") as? Int ?: return false
        val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.set(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(minFps / fpsUnitFactor, maxFps / fpsUnitFactor)
        )
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        runCatching {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        applyZoomRequest(builder, zoomRatio)
        builder.addTarget(surface)
        captureSession.setRepeatingRequest(builder.build(), null, cameraHandler)
        return true
    }

    private fun applyZoomRequest(builder: CaptureRequest.Builder, zoomRatio: Float) {
        if (!isZoomAvailableForCurrentMode()) return
        val capabilities = activeZoomCapabilities ?: return
        val clampedZoom = clampZoomRatioForCapabilities(zoomRatio, capabilities)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appliedViaRatio = runCatching {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedZoom)
                true
            }.onFailure { error ->
                Log.w(
                    "WebRtcEngine",
                    "Failed to apply CONTROL_ZOOM_RATIO; falling back to SCALER_CROP_REGION",
                    error
                )
            }.getOrDefault(false)
            if (appliedViaRatio) {
                return
            }
        }
        val sensorRect = capabilities.sensorRect ?: return
        val cropRegion = cropRegionForZoom(sensorRect, clampedZoom)
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
    }

    private fun cropRegionForZoom(sensorRect: Rect, zoomRatio: Float): Rect {
        val clampedZoom = zoomRatio.coerceAtLeast(1f)
        val centerX = sensorRect.centerX()
        val centerY = sensorRect.centerY()
        val halfWidth = (sensorRect.width() / (2f * clampedZoom)).roundToInt().coerceAtLeast(1)
        val halfHeight = (sensorRect.height() / (2f * clampedZoom)).roundToInt().coerceAtLeast(1)
        return Rect(
            (centerX - halfWidth).coerceAtLeast(sensorRect.left),
            (centerY - halfHeight).coerceAtLeast(sensorRect.top),
            (centerX + halfWidth).coerceAtMost(sensorRect.right),
            (centerY + halfHeight).coerceAtMost(sensorRect.bottom)
        )
    }

    private fun resolveActiveCamera2Session(): Any? {
        val capturer = when (val activeCapturer = videoCapturer) {
            is CompositeCameraCapturer -> activeCapturer.mainCameraCapturerForTorch()
            else -> activeCapturer
        } ?: return null
        val session = readFieldValueFromHierarchy(capturer, "currentSession") ?: return null
        return if (session.javaClass.name == "org.webrtc.Camera2Session") session else null
    }

    private fun readFieldValue(instance: Any, fieldName: String): Any? {
        return runCatching {
            val field = instance.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(instance)
        }.getOrNull()
    }

    private fun readFieldValueFromHierarchy(instance: Any, fieldName: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            val value = try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                field.get(instance)
            } catch (_: Exception) {
                null
            }
            if (value != null) {
                return value
            }
            current = current.superclass
        }
        return null
    }

    private fun activeCameraMode(): LocalCameraMode {
        if (isScreenSharing) return LocalCameraMode.SCREEN_SHARE
        return cameraModeFromSource(currentCameraSource)
    }

    private fun cameraModeFromSource(source: LocalCameraSource): LocalCameraMode {
        return when (source) {
            LocalCameraSource.SELFIE -> LocalCameraMode.SELFIE
            LocalCameraSource.WORLD -> LocalCameraMode.WORLD
            LocalCameraSource.COMPOSITE -> LocalCameraMode.COMPOSITE
        }
    }

    private fun cameraSourceFromMode(mode: LocalCameraMode): LocalCameraSource {
        return when (mode) {
            LocalCameraMode.SELFIE -> LocalCameraSource.SELFIE
            LocalCameraMode.WORLD -> LocalCameraSource.WORLD
            LocalCameraMode.COMPOSITE -> LocalCameraSource.COMPOSITE
            LocalCameraMode.SCREEN_SHARE -> LocalCameraSource.SELFIE
        }
    }

    private fun notifyCameraModeAndFlash() {
        val flashAvailable = isTorchAvailableForCurrentMode()
        onCameraModeChanged(activeCameraMode())
        onFlashlightStateChanged(flashAvailable, flashAvailable && isTorchEnabled)
    }

    private fun onRemoteVideoFrame(frame: org.webrtc.VideoFrame) {
        val stateChanged =
            remoteBlackFrameAnalyzer.onFrame(
                frame = frame,
                blackFrameAnalysisEnabled = isRemoteBlackFrameAnalysisEnabled
            )
        if (stateChanged) {
            val syntheticBlackNow = remoteBlackFrameAnalyzer.isSyntheticBlackDetected()
            Log.d(
                "WebRtcEngine",
                "[RemoteVideo] syntheticBlack=$syntheticBlackNow trackEnabled=${remoteVideoTrack?.enabled()} analyzerEnabled=$isRemoteBlackFrameAnalysisEnabled"
            )
        }
    }

    @Synchronized
    private fun buildWebRtcStatsSummary(report: RTCStatsReport): String {
        val stats = report.statsMap.values
        val statsById = report.statsMap

        val selectedPair = stats.firstOrNull { stat ->
            stat.type == "candidate-pair" && memberBoolean(stat, "selected") == true
        } ?: stats.firstOrNull { stat ->
            stat.type == "candidate-pair" &&
                memberBoolean(stat, "nominated") == true &&
                memberString(stat, "state") == "succeeded"
        } ?: stats.firstOrNull { stat ->
            stat.type == "candidate-pair" && memberString(stat, "state") == "succeeded"
        }

        val outboundVideo = stats
            .filter { it.type == "outbound-rtp" && isVideoRtpStat(it) }
            .maxByOrNull { memberLong(it, "bytesSent") ?: -1L }
        val inboundVideo = stats
            .filter { it.type == "inbound-rtp" && isVideoRtpStat(it) }
            .maxByOrNull { memberLong(it, "bytesReceived") ?: -1L }
        val remoteInboundVideo = stats
            .filter { it.type == "remote-inbound-rtp" && isVideoRtpStat(it) }
            .maxByOrNull { memberLong(it, "packetsReceived") ?: -1L }

        val reportTimestampMs = report.timestampUs / 1000.0
        val outboundBytes = memberLong(outboundVideo, "bytesSent")
        val inboundBytes = memberLong(inboundVideo, "bytesReceived")

        var measuredOutKbps: Double? = null
        var measuredInKbps: Double? = null
        val previousTimestampMs = lastStatsTimestampMs
        if (previousTimestampMs != null && reportTimestampMs > previousTimestampMs) {
            val deltaSeconds = (reportTimestampMs - previousTimestampMs) / 1000.0
            if (deltaSeconds > 0.0) {
                val previousOutBytes = lastOutboundVideoBytes
                if (previousOutBytes != null && outboundBytes != null && outboundBytes >= previousOutBytes) {
                    measuredOutKbps = ((outboundBytes - previousOutBytes) * 8.0 / deltaSeconds) / 1000.0
                }
                val previousInBytes = lastInboundVideoBytes
                if (previousInBytes != null && inboundBytes != null && inboundBytes >= previousInBytes) {
                    measuredInKbps = ((inboundBytes - previousInBytes) * 8.0 / deltaSeconds) / 1000.0
                }
            }
        }
        lastStatsTimestampMs = reportTimestampMs
        lastOutboundVideoBytes = outboundBytes
        lastInboundVideoBytes = inboundBytes

        val pairRttMs = memberDouble(selectedPair, "currentRoundTripTime")?.times(1000.0)
            ?: memberDouble(remoteInboundVideo, "roundTripTime")?.times(1000.0)
        val pairOutKbps = memberDouble(selectedPair, "availableOutgoingBitrate")?.div(1000.0)
        val pairInKbps = memberDouble(selectedPair, "availableIncomingBitrate")?.div(1000.0)
        val outboundKbps = measuredOutKbps ?: pairOutKbps
        val inboundKbps = measuredInKbps ?: pairInKbps

        val inboundJitterMs = memberDouble(inboundVideo, "jitter")?.times(1000.0)
        val outboundFps = memberDouble(outboundVideo, "framesPerSecond")
        val inboundFps = memberDouble(inboundVideo, "framesPerSecond")
        val framesEncoded = memberLong(outboundVideo, "framesEncoded")
        val framesDecoded = memberLong(inboundVideo, "framesDecoded")
        val framesDropped = memberLong(inboundVideo, "framesDropped")
        val packetsLost = memberLong(inboundVideo, "packetsLost")
        val qualityLimitationReason = memberString(outboundVideo, "qualityLimitationReason")
        val outCodec = resolveCodecName(outboundVideo, statsById)
        val inCodec = resolveCodecName(inboundVideo, statsById)

        return buildString {
            append("conn=")
            append(peerConnection?.connectionState()?.name ?: "NA")
            append(",ice=")
            append(peerConnection?.iceConnectionState()?.name ?: "NA")
            append(",rttMs=")
            append(formatNumber(pairRttMs, 0))
            append(",outKbps=")
            append(formatNumber(outboundKbps, 1))
            append(",inKbps=")
            append(formatNumber(inboundKbps, 1))
            append(",outFps=")
            append(formatNumber(outboundFps, 1))
            append(",inFps=")
            append(formatNumber(inboundFps, 1))
            append(",encFrames=")
            append(framesEncoded ?: "n/a")
            append(",decFrames=")
            append(framesDecoded ?: "n/a")
            append(",dropFrames=")
            append(framesDropped ?: "n/a")
            append(",lostPkts=")
            append(packetsLost ?: "n/a")
            append(",jitterMs=")
            append(formatNumber(inboundJitterMs, 1))
            append(",outCodec=")
            append(outCodec ?: "n/a")
            append(",inCodec=")
            append(inCodec ?: "n/a")
            append(",qualityLimit=")
            append(qualityLimitationReason ?: "n/a")
        }
    }

    private fun isVideoRtpStat(stat: RTCStats): Boolean {
        val mediaType = memberString(stat, "kind") ?: memberString(stat, "mediaType")
        return mediaType == "video"
    }

    private fun resolveCodecName(rtpStat: RTCStats?, statsById: Map<String, RTCStats>): String? {
        val codecId = memberString(rtpStat, "codecId") ?: return null
        val codecStat = statsById[codecId] ?: return null
        val mimeType = memberString(codecStat, "mimeType") ?: return null
        return mimeType.removePrefix("video/")
    }

    private fun memberString(stat: RTCStats?, key: String): String? {
        val value = stat?.members?.get(key) ?: return null
        return value.toString().ifBlank { null }
    }

    private fun memberDouble(stat: RTCStats?, key: String): Double? {
        val value = stat?.members?.get(key) ?: return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun memberLong(stat: RTCStats?, key: String): Long? {
        val value = stat?.members?.get(key) ?: return null
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun memberBoolean(stat: RTCStats?, key: String): Boolean? {
        val value = stat?.members?.get(key) ?: return null
        return when (value) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            else -> null
        }
    }

    private fun formatNumber(value: Double?, decimals: Int): String {
        val current = value ?: return "n/a"
        return "%.${decimals}f".format(java.util.Locale.US, current)
    }

    private companion object {
        val WEBRTC_LOGGING_ENABLED = AtomicBoolean(false)

        const val LEGACY_CAMERA_WIDTH = 640
        const val LEGACY_CAMERA_HEIGHT = 480
        const val LEGACY_CAMERA_FPS = 30
        const val LEGACY_CAMERA_MIN_FPS = 15

        const val CAMERA_TARGET_WIDTH = 1280
        const val CAMERA_TARGET_HEIGHT = 720
        const val CAMERA_TARGET_FPS = 30
        const val CAMERA_MIN_FPS = 20

        const val COMPOSITE_TARGET_WIDTH = 960
        const val COMPOSITE_TARGET_HEIGHT = 540
        const val COMPOSITE_TARGET_FPS = 24
        const val COMPOSITE_MIN_FPS = 15

        const val SCREEN_SHARE_MAX_WIDTH = 1920
        const val SCREEN_SHARE_MAX_HEIGHT = 1080
        const val SCREEN_SHARE_TARGET_FPS = 30
        const val SCREEN_SHARE_MIN_FPS = 15

        const val CAMERA_MAX_BITRATE_BPS = 2_500_000
        const val CAMERA_MIN_BITRATE_BPS = 350_000
        const val COMPOSITE_MAX_BITRATE_BPS = 1_500_000
        const val COMPOSITE_MIN_BITRATE_BPS = 300_000
        const val SCREEN_SHARE_MAX_BITRATE_BPS = 5_000_000
        const val SCREEN_SHARE_MIN_BITRATE_BPS = 1_000_000

        const val MAX_CAPTURE_FPS = 60
        const val ZOOM_RATIO_DELTA_EPSILON = 0.01f
        const val TORCH_RETRY_DELAY_MS = 120L
        const val MAX_TORCH_RETRY_ATTEMPTS = 8
    }
}
