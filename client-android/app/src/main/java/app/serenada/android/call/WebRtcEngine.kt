package app.serenada.android.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Collections
import java.util.WeakHashMap
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
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRtcEngine(
    context: Context,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionState: (PeerConnection.PeerConnectionState) -> Unit,
    private val onIceConnectionState: (PeerConnection.IceConnectionState) -> Unit,
    private val onSignalingState: (PeerConnection.SignalingState) -> Unit,
    private val onRenegotiationNeededCallback: () -> Unit,
    private val onRemoteVideoTrack: (VideoTrack?) -> Unit,
    private val onCameraFacingChanged: (Boolean) -> Unit,
    private var isRemoteBlackFrameAnalysisEnabled: Boolean = true
) {
    private enum class LocalCameraSource {
        SELFIE,
        WORLD,
        COMPOSITE
    }

    private data class CapturerSelection(
        val capturer: VideoCapturer,
        val isFrontFacing: Boolean
    )

    private val appContext = context.applicationContext
    private val eglBase: EglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var currentCameraSource = LocalCameraSource.SELFIE
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
    private val initializedRenderers =
        Collections.newSetFromMap(WeakHashMap<SurfaceViewRenderer, Boolean>())

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun getEglContext(): EglBase.Context = eglBase.eglBaseContext

    fun startLocalMedia() {
        if (localAudioTrack != null || localVideoTrack != null) return
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
        onRemoteVideoTrack(null)
    }

    fun release() {
        stopLocalMedia()
        closePeerConnection()
    }

    fun setIceServers(servers: List<PeerConnection.IceServer>) {
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
        if (videoSource == null) return
        val target = when (currentCameraSource) {
            LocalCameraSource.SELFIE -> LocalCameraSource.WORLD
            LocalCameraSource.WORLD -> LocalCameraSource.COMPOSITE
            LocalCameraSource.COMPOSITE -> LocalCameraSource.SELFIE
        }
        if (restartVideoCapturer(target)) {
            return
        }
        Log.w("WebRtcEngine", "Failed to switch camera source to $target")
        val fallback = if (target == LocalCameraSource.COMPOSITE) {
            LocalCameraSource.SELFIE
        } else {
            currentCameraSource
        }
        if (fallback != target && restartVideoCapturer(fallback)) {
            Log.w("WebRtcEngine", "Camera source fallback applied: $fallback")
        }
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
                val mungedSdp = forceVp8(desc.description)
                val finalDesc = SessionDescription(desc.type, mungedSdp)
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d("WebRtcEngine", "Local description set (offer)")
                        onSdp(finalDesc.description)
                        onComplete?.invoke(true)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.w("WebRtcEngine", "Failed to set local offer: $error")
                        onComplete?.invoke(false)
                    }
                }, finalDesc)
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
            encodings[0].maxBitrateBps = 32_000
            sender.setParameters(params)
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "Failed to apply audio sender parameters", e)
        }
    }

    private fun forceVp8(sdp: String): String {
        return try {
            val lines = sdp.split("\r\n").toMutableList()
            val mLineIndex = lines.indexOfFirst { it.startsWith("m=video") }
            if (mLineIndex == -1) return sdp
            val mLine = lines[mLineIndex]
            val parts = mLine.split(" ")
            if (parts.size <= 3) return sdp

            val payloadTypes = parts.drop(3)
            val vp8Pts = mutableListOf<String>()
            lines.forEach { line ->
                if (line.startsWith("a=rtpmap:")) {
                    val values = line.substring(9).split(" ")
                    if (values.size >= 2) {
                        val pt = values[0]
                        val name = values[1].split("/")[0]
                        if (name.equals("VP8", ignoreCase = true)) {
                            vp8Pts.add(pt)
                        }
                    }
                }
            }
            if (vp8Pts.isEmpty()) return sdp
            val newPtList = vp8Pts + payloadTypes.filter { it !in vp8Pts }
            lines[mLineIndex] = (parts.take(3) + newPtList).joinToString(" ")
            lines.joinToString("\r\n")
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "VP8 SDP munging failed", e)
            sdp
        }
    }

    private fun restartVideoCapturer(source: LocalCameraSource): Boolean {
        val observer = videoSource?.capturerObserver ?: return false
        disposeVideoCapturer()
        val selection = createVideoCapturer(source) ?: return false
        val textureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        try {
            selection.capturer.initialize(textureHelper, appContext, observer)
            selection.capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "Failed to start capture for $source", e)
            runCatching { selection.capturer.dispose() }
            runCatching { textureHelper.dispose() }
            return false
        }
        videoCapturer = selection.capturer
        surfaceTextureHelper = textureHelper
        currentCameraSource = source
        onCameraFacingChanged(selection.isFrontFacing)
        Log.d("WebRtcEngine", "Camera source active: $source")
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
                        CapturerSelection(capturer = it, isFrontFacing = true)
                    }
                } else if (back != null) {
                    enumerator.createCapturer(back, null)?.let {
                        CapturerSelection(capturer = it, isFrontFacing = false)
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
                        CapturerSelection(capturer = it, isFrontFacing = false)
                    }
                }
            }

            LocalCameraSource.COMPOSITE -> {
                if (front == null || back == null) {
                    null
                } else {
                    val mainCapturer = enumerator.createCapturer(back, null)
                    val overlayCapturer = enumerator.createCapturer(front, null)
                    if (mainCapturer == null || overlayCapturer == null) {
                        mainCapturer?.dispose()
                        overlayCapturer?.dispose()
                        null
                    } else {
                        CapturerSelection(
                            capturer = CompositeCameraCapturer(
                                context = appContext,
                                eglContext = eglBase.eglBaseContext,
                                mainCapturer = mainCapturer,
                                overlayCapturer = overlayCapturer,
                                onStartFailure = { onCompositeStartFailure() }
                            ),
                            isFrontFacing = false
                        )
                    }
                }
            }
        }
    }

    private fun onCompositeStartFailure() {
        mainHandler.post {
            if (currentCameraSource != LocalCameraSource.COMPOSITE) return@post
            if (videoCapturer !is CompositeCameraCapturer) return@post
            Log.w("WebRtcEngine", "Composite source failed to start; falling back to selfie")
            if (restartVideoCapturer(LocalCameraSource.SELFIE)) {
                Log.w("WebRtcEngine", "Camera source fallback applied: ${LocalCameraSource.SELFIE}")
            }
        }
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

    private companion object {
        const val CAPTURE_WIDTH = 640
        const val CAPTURE_HEIGHT = 480
        const val CAPTURE_FPS = 30
    }
}
