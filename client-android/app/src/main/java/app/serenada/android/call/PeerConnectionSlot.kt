package app.serenada.android.call

import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

class PeerConnectionSlot(
    val remoteCid: String,
    private val factory: PeerConnectionFactory,
    private var iceServers: List<PeerConnection.IceServer>?,
    private var localAudioTrack: AudioTrack?,
    private var localVideoTrack: VideoTrack?,
    private val onLocalIceCandidate: (String, IceCandidate) -> Unit,
    private val onRemoteVideoTrack: (String, VideoTrack?) -> Unit,
    private val onConnectionStateChange: (String, PeerConnection.PeerConnectionState) -> Unit,
    private val onIceConnectionStateChange: (String, PeerConnection.IceConnectionState) -> Unit,
    private val onSignalingStateChange: (String, PeerConnection.SignalingState) -> Unit,
    private val onRenegotiationNeeded: (String) -> Unit,
    private val applyAudioSenderParameters: (PeerConnection) -> Unit,
    private val currentVideoSenderPolicy: () -> WebRtcEngine.VideoSenderPolicy,
    private val isRemoteBlackFrameAnalysisEnabled: () -> Boolean,
) {
    var sentOffer: Boolean = false
    var isMakingOffer: Boolean = false
    var pendingIceRestart: Boolean = false
    var lastIceRestartAt: Long = 0L
    var offerTimeoutTask: Runnable? = null
    var iceRestartTask: Runnable? = null
    var nonHostFallbackTask: Runnable? = null
    var nonHostFallbackAttempts: Int = 0

    private var peerConnection: PeerConnection? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteDescriptionSet = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private val remoteSinks = LinkedHashSet<VideoSink>()
    private val remoteBlackFrameAnalyzer = RemoteBlackFrameAnalyzer()
    private val remoteVideoStateSink = VideoSink { frame ->
        val stateChanged = remoteBlackFrameAnalyzer.onFrame(
            frame = frame,
            blackFrameAnalysisEnabled = isRemoteBlackFrameAnalysisEnabled()
        )
        if (stateChanged) {
            Log.d(
                "PeerConnectionSlot",
                "[RemoteVideo][$remoteCid] syntheticBlack=${remoteBlackFrameAnalyzer.isSyntheticBlackDetected()} trackEnabled=${remoteVideoTrack?.enabled()}"
            )
        }
    }

    fun setIceServers(servers: List<PeerConnection.IceServer>) {
        iceServers = servers
        ensurePeerConnection()
    }

    fun ensurePeerConnection(): Boolean {
        if (peerConnection != null) return true
        val servers = iceServers ?: return false
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onLocalIceCandidate(remoteCid, candidate)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d("PeerConnectionSlot", "[$remoteCid] Connection state: $newState")
                onConnectionStateChange(remoteCid, newState)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack?.removeSink(remoteVideoStateSink)
                    remoteSinks.forEach { sink -> remoteVideoTrack?.removeSink(sink) }
                    remoteVideoTrack = track
                    remoteBlackFrameAnalyzer.onTrackAttached()
                    track.addSink(remoteVideoStateSink)
                    remoteSinks.forEach { sink -> track.addSink(sink) }
                    onRemoteVideoTrack(remoteCid, track)
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                Log.d("PeerConnectionSlot", "[$remoteCid] Signaling state: $newState")
                onSignalingStateChange(remoteCid, newState)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d("PeerConnectionSlot", "[$remoteCid] ICE state: $newState")
                onIceConnectionStateChange(remoteCid, newState)
            }

            override fun onRenegotiationNeeded() {
                onRenegotiationNeeded(remoteCid)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(dc: org.webrtc.DataChannel) = Unit
        }) ?: return false

        peerConnection = pc
        attachLocalTracks(localAudioTrack, localVideoTrack)
        ensureReceiveTransceivers(pc)
        applyAudioSenderParameters(pc)
        applyVideoSenderParameters(currentVideoSenderPolicy())
        return true
    }

    fun attachLocalTracks(audioTrack: AudioTrack?, videoTrack: VideoTrack?) {
        localAudioTrack = audioTrack
        localVideoTrack = videoTrack
        val pc = peerConnection ?: run {
            if (!ensurePeerConnection()) return
            peerConnection
        } ?: return

        if (audioTrack != null && pc.senders.none { it.track()?.kind() == MediaStreamTrack.AUDIO_TRACK_KIND }) {
            pc.addTrack(audioTrack, listOf("serenada"))
            applyAudioSenderParameters(pc)
        }
        if (videoTrack != null && pc.senders.none { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND }) {
            pc.addTrack(videoTrack, listOf("serenada"))
            applyVideoSenderParameters(currentVideoSenderPolicy())
        }
    }

    fun closePeerConnection() {
        offerTimeoutTask = null
        iceRestartTask = null
        nonHostFallbackTask = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        remoteVideoTrack?.removeSink(remoteVideoStateSink)
        remoteSinks.forEach { sink -> remoteVideoTrack?.removeSink(sink) }
        remoteSinks.clear()
        remoteVideoTrack = null
        remoteBlackFrameAnalyzer.onTrackDetached()
        remoteDescriptionSet = false
        pendingIceCandidates.clear()
        onRemoteVideoTrack(remoteCid, null)
    }

    fun createOffer(
        iceRestart: Boolean = false,
        onSdp: (String) -> Unit,
        onComplete: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val pc = peerConnection ?: run {
            if (!ensurePeerConnection()) return false
            peerConnection
        } ?: return false
        if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            onComplete?.invoke(false)
            return false
        }

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
                        onSdp(desc.description)
                        onComplete?.invoke(true)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.w("PeerConnectionSlot", "[$remoteCid] Failed to set local offer: $error")
                        onComplete?.invoke(false)
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                Log.w("PeerConnectionSlot", "[$remoteCid] Offer creation failed: $error")
                onComplete?.invoke(false)
            }
        }, constraints)
        return true
    }

    fun createAnswer(onSdp: (String) -> Unit, onComplete: ((Boolean) -> Unit)? = null) {
        val pc = peerConnection ?: run {
            if (!ensurePeerConnection()) {
                onComplete?.invoke(false)
                return
            }
            peerConnection
        } ?: run {
            onComplete?.invoke(false)
            return
        }

        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) {
                    onComplete?.invoke(false)
                    return
                }
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        onSdp(desc.description)
                        onComplete?.invoke(true)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.w("PeerConnectionSlot", "[$remoteCid] Failed to set local answer: $error")
                        onComplete?.invoke(false)
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                Log.w("PeerConnectionSlot", "[$remoteCid] Answer creation failed: $error")
                onComplete?.invoke(false)
            }
        }, constraints)
    }

    fun setRemoteDescription(
        type: SessionDescription.Type,
        sdp: String,
        onComplete: (() -> Unit)? = null,
    ) {
        val pc = peerConnection ?: run {
            if (!ensurePeerConnection()) return
            peerConnection
        } ?: return
        val desc = SessionDescription(type, sdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingIceCandidates()
                onComplete?.invoke()
            }

            override fun onSetFailure(error: String?) {
                Log.w("PeerConnectionSlot", "[$remoteCid] Failed to set remote description ($type): $error")
            }
        }, desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection ?: run {
            if (!ensurePeerConnection()) {
                if (pendingIceCandidates.size < WebRtcResilienceConstants.ICE_CANDIDATE_BUFFER_MAX) {
                    pendingIceCandidates.add(candidate)
                }
                return
            }
            peerConnection
        } ?: return

        if (!remoteDescriptionSet) {
            if (pendingIceCandidates.size < WebRtcResilienceConstants.ICE_CANDIDATE_BUFFER_MAX) {
                pendingIceCandidates.add(candidate)
            }
            return
        }
        pc.addIceCandidate(candidate)
    }

    fun rollbackLocalDescription(onComplete: ((Boolean) -> Unit)? = null) {
        val pc = peerConnection ?: return
        val desc = SessionDescription(SessionDescription.Type.ROLLBACK, "")
        pc.setLocalDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                onComplete?.invoke(true)
            }

            override fun onSetFailure(error: String?) {
                Log.w("PeerConnectionSlot", "[$remoteCid] Failed to rollback local description: $error")
                onComplete?.invoke(false)
            }
        }, desc)
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        attachRemoteSink(renderer)
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        detachRemoteSink(renderer)
    }

    fun attachRemoteSink(sink: VideoSink) {
        if (!remoteSinks.add(sink)) return
        remoteVideoTrack?.addSink(sink)
    }

    fun detachRemoteSink(sink: VideoSink) {
        remoteVideoTrack?.removeSink(sink)
        remoteSinks.remove(sink)
    }

    fun collectWebRtcStats(onComplete: (String, RealtimeCallStats?) -> Unit) {
        val pc = peerConnection
        if (pc == null) {
            onComplete("pc=none remote=$remoteCid", null)
            return
        }
        pc.getStats { report ->
            val selectedPair = report.statsMap.values.firstOrNull { stat ->
                stat.type == "candidate-pair" && memberBoolean(stat, "selected") == true
            } ?: report.statsMap.values.firstOrNull { stat ->
                stat.type == "candidate-pair" &&
                    memberBoolean(stat, "nominated") == true &&
                    memberString(stat, "state") == "succeeded"
            }

            val inboundVideo = report.statsMap.values.firstOrNull { stat ->
                stat.type == "inbound-rtp" && getMediaKind(stat) == "video"
            }
            val rttMs = memberDouble(selectedPair, "currentRoundTripTime")?.times(1000.0)
            val width = memberLong(inboundVideo, "frameWidth")?.toInt()
            val height = memberLong(inboundVideo, "frameHeight")?.toInt()
            val resolution =
                if (width != null && height != null && width > 0 && height > 0) {
                    "${width}x${height}"
                } else {
                    null
                }
            onComplete(
                "remote=$remoteCid,conn=${pc.connectionState().name},ice=${pc.iceConnectionState().name},rttMs=${formatNumber(rttMs, 0)}",
                RealtimeCallStats(
                    rttMs = rttMs,
                    videoResolution = resolution,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun isReady(): Boolean = peerConnection != null

    fun getConnectionState(): PeerConnection.PeerConnectionState =
        peerConnection?.connectionState() ?: PeerConnection.PeerConnectionState.NEW

    fun getIceConnectionState(): PeerConnection.IceConnectionState =
        peerConnection?.iceConnectionState() ?: PeerConnection.IceConnectionState.NEW

    fun getSignalingState(): PeerConnection.SignalingState =
        peerConnection?.signalingState() ?: PeerConnection.SignalingState.STABLE

    fun hasRemoteDescription(): Boolean = remoteDescriptionSet || peerConnection?.remoteDescription != null

    fun isRemoteVideoTrackEnabled(): Boolean {
        val track = remoteVideoTrack ?: return false
        if (!track.enabled()) return false
        return !remoteBlackFrameAnalyzer.isVideoConsideredOff()
    }

    fun applyVideoSenderParameters(policy: WebRtcEngine.VideoSenderPolicy) {
        val pc = peerConnection ?: return
        val sender = pc.senders.firstOrNull { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND } ?: return
        try {
            val params = sender.parameters
            val encodings = params.encodings
            if (encodings.isNullOrEmpty()) return
            params.degradationPreference = policy.degradationPreference
            encodings[0].maxBitrateBps = policy.maxBitrateBps
            encodings[0].minBitrateBps = policy.minBitrateBps
            encodings[0].maxFramerate = policy.maxFramerate
            sender.setParameters(params)
        } catch (e: Exception) {
            Log.w("PeerConnectionSlot", "[$remoteCid] Failed to apply video sender parameters", e)
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

    private fun memberString(stat: RTCStats?, key: String): String? =
        stat?.members?.get(key)?.toString()?.takeIf { it.isNotBlank() }

    private fun memberBoolean(stat: RTCStats?, key: String): Boolean? =
        stat?.members?.get(key) as? Boolean

    private fun memberLong(stat: RTCStats?, key: String): Long? {
        val value = stat?.members?.get(key) ?: return null
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().toLongOrNull()
        }
    }

    private fun memberDouble(stat: RTCStats?, key: String): Double? {
        val value = stat?.members?.get(key) ?: return null
        return when (value) {
            is Number -> value.toDouble()
            else -> value.toString().toDoubleOrNull()
        }
    }

    private fun getMediaKind(stat: RTCStats?): String? {
        val kind = memberString(stat, "kind")
        if (!kind.isNullOrBlank()) return kind
        val mediaType = memberString(stat, "mediaType")
        if (!mediaType.isNullOrBlank()) return mediaType
        return null
    }

    private fun formatNumber(value: Double?, decimals: Int): String {
        if (value == null || !value.isFinite()) return "n/a"
        return "%.${decimals}f".format(value)
    }
}
