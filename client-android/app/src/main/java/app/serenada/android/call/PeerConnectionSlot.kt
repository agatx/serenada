package app.serenada.android.call

import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import kotlin.math.max

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
    private data class MediaTotals(
        var inboundPacketsReceived: Long = 0L,
        var inboundPacketsLost: Long = 0L,
        var inboundBytes: Long = 0L,
        var inboundJitterSumSeconds: Double = 0.0,
        var inboundJitterCount: Int = 0,
        var inboundJitterBufferDelaySeconds: Double = 0.0,
        var inboundJitterBufferEmittedCount: Long = 0L,
        var inboundConcealedSamples: Long = 0L,
        var inboundTotalSamples: Long = 0L,
        var inboundFpsSum: Double = 0.0,
        var inboundFpsCount: Int = 0,
        var inboundFrameWidth: Int = 0,
        var inboundFrameHeight: Int = 0,
        var inboundFramesDecoded: Long = 0L,
        var inboundFreezeCount: Long = 0L,
        var inboundFreezeDurationSeconds: Double = 0.0,
        var inboundNackCount: Long = 0L,
        var inboundPliCount: Long = 0L,
        var inboundFirCount: Long = 0L,
        var outboundPacketsSent: Long = 0L,
        var outboundBytes: Long = 0L,
        var outboundPacketsRetransmitted: Long = 0L,
        var remoteInboundPacketsLost: Long = 0L
    )

    private data class RealtimeStatsSample(
        val timestampMs: Long,
        val audioRxBytes: Long,
        val audioTxBytes: Long,
        val videoRxBytes: Long,
        val videoTxBytes: Long,
        val videoFramesDecoded: Long,
        val videoNackCount: Long,
        val videoPliCount: Long,
        val videoFirCount: Long
    )

    private data class FreezeSample(
        val timestampMs: Long,
        val freezeCount: Long,
        val freezeDurationSeconds: Double
    )

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
    private var lastRealtimeStatsSample: RealtimeStatsSample? = null
    private val freezeSamples = mutableListOf<FreezeSample>()
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
        lastRealtimeStatsSample = null
        freezeSamples.clear()
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
            onComplete(
                buildWebRtcStatsSummary(report),
                buildRealtimeCallStats(report),
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

    @Synchronized
    private fun buildWebRtcStatsSummary(report: RTCStatsReport): String {
        val stats = report.statsMap.values
        val statsById = report.statsMap
        val pc = peerConnection

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
            .filter { it.type == "outbound-rtp" && getMediaKind(it) == "video" }
            .maxByOrNull { memberLong(it, "bytesSent") ?: -1L }
        val inboundVideo = stats
            .filter { it.type == "inbound-rtp" && getMediaKind(it) == "video" }
            .maxByOrNull { memberLong(it, "bytesReceived") ?: -1L }
        val remoteInboundVideo = stats
            .filter { it.type == "remote-inbound-rtp" && getMediaKind(it) == "video" }
            .maxByOrNull { memberLong(it, "packetsReceived") ?: -1L }

        val pairRttMs = memberDouble(selectedPair, "currentRoundTripTime")?.times(1000.0)
            ?: memberDouble(remoteInboundVideo, "roundTripTime")?.times(1000.0)
        val outboundKbps = memberDouble(selectedPair, "availableOutgoingBitrate")?.div(1000.0)
        val inboundKbps = memberDouble(selectedPair, "availableIncomingBitrate")?.div(1000.0)
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
            append("remote=")
            append(remoteCid)
            append(",conn=")
            append(pc?.connectionState()?.name ?: "NA")
            append(",ice=")
            append(pc?.iceConnectionState()?.name ?: "NA")
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

    @Synchronized
    private fun buildRealtimeCallStats(report: RTCStatsReport): RealtimeCallStats {
        val stats = report.statsMap.values
        val statsById = report.statsMap
        val audio = MediaTotals()
        val video = MediaTotals()

        var selectedCandidatePair: RTCStats? = null
        var fallbackCandidatePair: RTCStats? = null
        var remoteInboundRttSumSeconds = 0.0
        var remoteInboundRttCount = 0

        stats.forEach { stat ->
            if (stat.type == "candidate-pair") {
                val isSelected = memberBoolean(stat, "selected") == true
                val isNominated = memberBoolean(stat, "nominated") == true
                val pairState = memberString(stat, "state")
                if (isSelected) {
                    selectedCandidatePair = stat
                } else if (fallbackCandidatePair == null && isNominated && pairState == "succeeded") {
                    fallbackCandidatePair = stat
                }
                return@forEach
            }

            val kind = getMediaKind(stat) ?: return@forEach
            val bucket = if (kind == "audio") audio else video

            when (stat.type) {
                "inbound-rtp" -> {
                    bucket.inboundPacketsReceived += memberLong(stat, "packetsReceived") ?: 0L
                    bucket.inboundPacketsLost += max(0L, memberLong(stat, "packetsLost") ?: 0L)
                    bucket.inboundBytes += memberLong(stat, "bytesReceived") ?: 0L

                    val jitter = memberDouble(stat, "jitter")
                    if (jitter != null) {
                        bucket.inboundJitterSumSeconds += jitter
                        bucket.inboundJitterCount += 1
                    }

                    bucket.inboundJitterBufferDelaySeconds += memberDouble(stat, "jitterBufferDelay") ?: 0.0
                    bucket.inboundJitterBufferEmittedCount += memberLong(stat, "jitterBufferEmittedCount") ?: 0L
                    bucket.inboundConcealedSamples += memberLong(stat, "concealedSamples") ?: 0L
                    bucket.inboundTotalSamples += memberLong(stat, "totalSamplesReceived") ?: 0L

                    val fps = memberDouble(stat, "framesPerSecond")
                    if (fps != null) {
                        bucket.inboundFpsSum += fps
                        bucket.inboundFpsCount += 1
                    }

                    val frameWidth = (memberLong(stat, "frameWidth") ?: 0L).toInt()
                    val frameHeight = (memberLong(stat, "frameHeight") ?: 0L).toInt()
                    bucket.inboundFrameWidth = max(bucket.inboundFrameWidth, frameWidth)
                    bucket.inboundFrameHeight = max(bucket.inboundFrameHeight, frameHeight)

                    bucket.inboundFramesDecoded += memberLong(stat, "framesDecoded") ?: 0L
                    bucket.inboundFreezeCount += memberLong(stat, "freezeCount") ?: 0L
                    bucket.inboundFreezeDurationSeconds += memberDouble(stat, "totalFreezesDuration") ?: 0.0
                    bucket.inboundNackCount += memberLong(stat, "nackCount") ?: 0L
                    bucket.inboundPliCount += memberLong(stat, "pliCount") ?: 0L
                    bucket.inboundFirCount += memberLong(stat, "firCount") ?: 0L
                }

                "outbound-rtp" -> {
                    bucket.outboundPacketsSent += memberLong(stat, "packetsSent") ?: 0L
                    bucket.outboundBytes += memberLong(stat, "bytesSent") ?: 0L
                    bucket.outboundPacketsRetransmitted += memberLong(stat, "retransmittedPacketsSent") ?: 0L
                }

                "remote-inbound-rtp" -> {
                    bucket.remoteInboundPacketsLost += max(0L, memberLong(stat, "packetsLost") ?: 0L)
                    val remoteRtt = memberDouble(stat, "roundTripTime")
                    if (remoteRtt != null) {
                        remoteInboundRttSumSeconds += remoteRtt
                        remoteInboundRttCount += 1
                    }
                }
            }
        }

        val selectedPair = selectedCandidatePair ?: fallbackCandidatePair
        val localCandidate = selectedPair?.let { pair ->
            val id = memberString(pair, "localCandidateId")
            if (id.isNullOrBlank()) null else statsById[id]
        }
        val remoteCandidate = selectedPair?.let { pair ->
            val id = memberString(pair, "remoteCandidateId")
            if (id.isNullOrBlank()) null else statsById[id]
        }

        val localCandidateType = memberString(localCandidate, "candidateType")
        val remoteCandidateType = memberString(remoteCandidate, "candidateType")
        val localProtocol = memberString(localCandidate, "protocol")
        val remoteProtocol = memberString(remoteCandidate, "protocol")
        val isRelay = localCandidateType == "relay" || remoteCandidateType == "relay"
        val transportPath =
            if (localCandidateType != null || remoteCandidateType != null) {
                "${if (isRelay) "TURN relay" else "Direct"} (${localCandidateType ?: "n/a"} -> ${remoteCandidateType ?: "n/a"}, ${localProtocol ?: remoteProtocol ?: "n/a"})"
            } else {
                null
            }

        val candidateRttSeconds = memberDouble(selectedPair, "currentRoundTripTime")
        val remoteInboundRttSeconds =
            if (remoteInboundRttCount > 0) {
                remoteInboundRttSumSeconds / remoteInboundRttCount
            } else {
                null
            }
        val chosenRttSeconds = candidateRttSeconds ?: remoteInboundRttSeconds
        val rttMs = chosenRttSeconds?.times(1000.0)
        val availableOutgoingKbps = memberDouble(selectedPair, "availableOutgoingBitrate")?.div(1000.0)

        val now = System.currentTimeMillis()
        val previousSample = lastRealtimeStatsSample
        val elapsedSeconds =
            if (previousSample != null) {
                (now - previousSample.timestampMs) / 1000.0
            } else {
                0.0
            }

        val audioRxKbps = previousSample?.let {
            calculateBitrateKbps(it.audioRxBytes, audio.inboundBytes, elapsedSeconds)
        }
        val audioTxKbps = previousSample?.let {
            calculateBitrateKbps(it.audioTxBytes, audio.outboundBytes, elapsedSeconds)
        }
        val videoRxKbps = previousSample?.let {
            calculateBitrateKbps(it.videoRxBytes, video.inboundBytes, elapsedSeconds)
        }
        val videoTxKbps = previousSample?.let {
            calculateBitrateKbps(it.videoTxBytes, video.outboundBytes, elapsedSeconds)
        }

        val videoFps =
            if (video.inboundFpsCount > 0) {
                video.inboundFpsSum / video.inboundFpsCount
            } else if (
                previousSample != null &&
                elapsedSeconds > 0.0 &&
                video.inboundFramesDecoded >= previousSample.videoFramesDecoded
            ) {
                (video.inboundFramesDecoded - previousSample.videoFramesDecoded) / elapsedSeconds
            } else {
                null
            }

        freezeSamples.add(
            FreezeSample(
                timestampMs = now,
                freezeCount = video.inboundFreezeCount,
                freezeDurationSeconds = video.inboundFreezeDurationSeconds
            )
        )
        freezeSamples.removeAll { sample -> now - sample.timestampMs > 60_000L }
        val freezeWindowBase = freezeSamples.firstOrNull()
        val videoFreezeCount60s =
            freezeWindowBase?.let { base ->
                max(0L, video.inboundFreezeCount - base.freezeCount)
            }
        val videoFreezeDuration60s =
            freezeWindowBase?.let { base ->
                max(0.0, video.inboundFreezeDurationSeconds - base.freezeDurationSeconds)
            }

        val audioRxPacketLossPct =
            ratioPercent(
                numerator = audio.inboundPacketsLost,
                denominator = audio.inboundPacketsReceived + audio.inboundPacketsLost
            )
        val audioTxPacketLossPct =
            ratioPercent(
                numerator = audio.remoteInboundPacketsLost,
                denominator = audio.outboundPacketsSent + audio.remoteInboundPacketsLost
            )
        val videoRxPacketLossPct =
            ratioPercent(
                numerator = video.inboundPacketsLost,
                denominator = video.inboundPacketsReceived + video.inboundPacketsLost
            )
        val videoTxPacketLossPct =
            ratioPercent(
                numerator = video.remoteInboundPacketsLost,
                denominator = video.outboundPacketsSent + video.remoteInboundPacketsLost
            )

        val audioJitterMs =
            if (audio.inboundJitterCount > 0) {
                (audio.inboundJitterSumSeconds / audio.inboundJitterCount) * 1000.0
            } else {
                null
            }
        val audioPlayoutDelayMs =
            if (audio.inboundJitterBufferEmittedCount > 0) {
                (audio.inboundJitterBufferDelaySeconds / audio.inboundJitterBufferEmittedCount) * 1000.0
            } else {
                null
            }
        val audioConcealedPct =
            ratioPercent(
                numerator = audio.inboundConcealedSamples,
                denominator = audio.inboundConcealedSamples + audio.inboundTotalSamples
            )
        val videoRetransmitPct =
            ratioPercent(
                numerator = video.outboundPacketsRetransmitted,
                denominator = video.outboundPacketsSent
            )

        val videoNackPerMin = previousSample?.let {
            positiveRatePerMinute(video.inboundNackCount, it.videoNackCount, elapsedSeconds)
        }
        val videoPliPerMin = previousSample?.let {
            positiveRatePerMinute(video.inboundPliCount, it.videoPliCount, elapsedSeconds)
        }
        val videoFirPerMin = previousSample?.let {
            positiveRatePerMinute(video.inboundFirCount, it.videoFirCount, elapsedSeconds)
        }

        val videoResolution =
            if (video.inboundFrameWidth > 0 && video.inboundFrameHeight > 0) {
                "${video.inboundFrameWidth}x${video.inboundFrameHeight}"
            } else {
                null
            }

        lastRealtimeStatsSample =
            RealtimeStatsSample(
                timestampMs = now,
                audioRxBytes = audio.inboundBytes,
                audioTxBytes = audio.outboundBytes,
                videoRxBytes = video.inboundBytes,
                videoTxBytes = video.outboundBytes,
                videoFramesDecoded = video.inboundFramesDecoded,
                videoNackCount = video.inboundNackCount,
                videoPliCount = video.inboundPliCount,
                videoFirCount = video.inboundFirCount
            )

        return RealtimeCallStats(
            transportPath = transportPath,
            rttMs = rttMs,
            availableOutgoingKbps = availableOutgoingKbps,
            audioRxPacketLossPct = audioRxPacketLossPct,
            audioTxPacketLossPct = audioTxPacketLossPct,
            audioJitterMs = audioJitterMs,
            audioPlayoutDelayMs = audioPlayoutDelayMs,
            audioConcealedPct = audioConcealedPct,
            audioRxKbps = audioRxKbps,
            audioTxKbps = audioTxKbps,
            videoRxPacketLossPct = videoRxPacketLossPct,
            videoTxPacketLossPct = videoTxPacketLossPct,
            videoRxKbps = videoRxKbps,
            videoTxKbps = videoTxKbps,
            videoFps = videoFps,
            videoResolution = videoResolution,
            videoFreezeCount60s = videoFreezeCount60s,
            videoFreezeDuration60s = videoFreezeDuration60s,
            videoRetransmitPct = videoRetransmitPct,
            videoNackPerMin = videoNackPerMin,
            videoPliPerMin = videoPliPerMin,
            videoFirPerMin = videoFirPerMin,
            updatedAtMs = now,
        )
    }

    private fun calculateBitrateKbps(
        previousBytes: Long,
        currentBytes: Long,
        elapsedSeconds: Double
    ): Double? {
        if (elapsedSeconds <= 0.0 || currentBytes < previousBytes) return null
        val bits = (currentBytes - previousBytes) * 8.0
        return bits / elapsedSeconds / 1000.0
    }

    private fun positiveRatePerMinute(
        currentValue: Long,
        previousValue: Long,
        elapsedSeconds: Double
    ): Double? {
        if (elapsedSeconds <= 0.0 || currentValue < previousValue) return null
        return ((currentValue - previousValue) / elapsedSeconds) * 60.0
    }

    private fun ratioPercent(numerator: Long, denominator: Long): Double? {
        if (denominator <= 0L) return null
        return (numerator.toDouble() / denominator.toDouble()) * 100.0
    }

    private fun resolveCodecName(rtpStat: RTCStats?, statsById: Map<String, RTCStats>): String? {
        val codecId = memberString(rtpStat, "codecId") ?: return null
        val codecStat = statsById[codecId] ?: return null
        val mimeType = memberString(codecStat, "mimeType") ?: return null
        return mimeType.removePrefix("video/")
    }

}
