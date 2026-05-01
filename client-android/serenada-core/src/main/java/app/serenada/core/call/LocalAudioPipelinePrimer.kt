package app.serenada.core.call

import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Holds a self-loopback pair of [PeerConnection]s with the local audio
 * track attached so WebRTC's full audio pipeline (capture → AEC/NS/AGC →
 * encode → media-source stat) stays active continuously while the user is
 * in a room — including the Waiting phase before any peer joins. The
 * audio-level poller can then read the same `media-source.audioLevel`
 * stat regardless of whether real peers exist, giving consistent indicator
 * sensitivity throughout the session.
 *
 * Two PCs are required because `AudioSendStream` only starts pulling
 * samples once both descriptions are set and the transport is established
 * — without an answer the source-level stat reads zero (verified
 * experimentally). The pair negotiates over loopback host candidates with
 * default DTLS, so no real network traffic is generated.
 */
internal class LocalAudioPipelinePrimer(
    private val factory: PeerConnectionFactory,
    private val logger: SerenadaLogger? = null,
) {
    /** Sender side — holds the local audio track; queried for `media-source` stat. */
    private var sender: PeerConnection? = null

    /** Receiver side — completes the loopback so the sender's transport activates. */
    private var receiver: PeerConnection? = null

    fun start(localAudioTrack: AudioTrack) {
        if (sender != null) return
        val config = PeerConnection.RTCConfiguration(emptyList())
        // Default ICE policy gathers host (loopback) candidates only since we
        // pass no STUN/TURN servers — enough for the pair to connect locally.

        val senderObserver = forwardingObserver { candidate -> receiver?.addIceCandidate(candidate) }
        val receiverObserver = forwardingObserver { candidate -> sender?.addIceCandidate(candidate) }

        val s = factory.createPeerConnection(config, senderObserver) ?: run {
            logger?.log(SerenadaLogLevel.WARNING, TAG, "createPeerConnection (sender) returned null; indicator will be silent while alone")
            return
        }
        val r = factory.createPeerConnection(config, receiverObserver) ?: run {
            logger?.log(SerenadaLogLevel.WARNING, TAG, "createPeerConnection (receiver) returned null; indicator will be silent while alone")
            s.close()
            return
        }
        sender = s
        receiver = r

        s.addTrack(localAudioTrack, listOf(STREAM_ID))
        s.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription) {
                s.setLocalDescription(FailureCleanupSdpObserver("setLocal[sender]"), offer)
                r.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        r.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                r.setLocalDescription(FailureCleanupSdpObserver("setLocal[receiver]"), answer)
                                s.setRemoteDescription(FailureCleanupSdpObserver("setRemote[sender]"), answer)
                            }
                            override fun onCreateFailure(error: String?) {
                                cleanupAfterFailure("createAnswer", error)
                            }
                            override fun onSetSuccess() {}
                            override fun onSetFailure(error: String?) {}
                        }, MediaConstraints())
                    }
                    override fun onSetFailure(error: String?) {
                        cleanupAfterFailure("setRemote[receiver]", error)
                    }
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, offer)
            }
            override fun onCreateFailure(error: String?) {
                cleanupAfterFailure("createOffer", error)
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    /**
     * Releases both PCs after a negotiation failure so a subsequent
     * `start()` can retry. Without this the `if (sender != null) return`
     * guard wedges the primer in a half-initialized state.
     */
    private fun cleanupAfterFailure(op: String, error: String?) {
        logger?.log(SerenadaLogLevel.WARNING, TAG, "$op failed: $error — closing primer")
        stop()
    }

    fun stop() {
        receiver?.close()
        receiver = null
        sender?.close()
        sender = null
    }

    /**
     * Queries the sender PC's `media-source.audioLevel` stat. Result is in
     * [0, 1] or null if the stat isn't yet populated (e.g., the loopback
     * is still negotiating — typically the first ~100 ms after start).
     */
    fun collectAudioLevel(onComplete: (Float?) -> Unit) {
        val pc = sender ?: return onComplete(null)
        pc.getStats { report ->
            var level: Float? = null
            for (stat in report.statsMap.values) {
                if (stat.type != "media-source") continue
                if (getMediaKind(stat) != "audio") continue
                memberDouble(stat, "audioLevel")?.let { raw ->
                    // Drop non-finite values rather than letting them poison
                    // downstream smoothing — `coerceIn` propagates NaN.
                    val f = raw.toFloat()
                    if (f.isFinite()) level = f.coerceIn(0f, 1f)
                }
            }
            onComplete(level)
        }
    }

    private fun forwardingObserver(onIceCandidate: (IceCandidate) -> Unit) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let(onIceCandidate)
        }
        // The receiver PC will be handed an incoming audio track from the
        // sender — silence it so the loopback doesn't echo through the
        // speaker. Without this the user hears their own voice on a ~50 ms
        // delay.
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            silenceIfAudio(receiver?.track())
        }
        override fun onAddStream(stream: MediaStream?) {
            stream?.audioTracks?.forEach { silenceIfAudio(it) }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun silenceIfAudio(track: MediaStreamTrack?) {
        val audio = track as? AudioTrack ?: return
        audio.setVolume(0.0)
        audio.setEnabled(false)
    }

    private inner class FailureCleanupSdpObserver(private val opLabel: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            cleanupAfterFailure("$opLabel createFailure", error)
        }
        override fun onSetFailure(error: String?) {
            cleanupAfterFailure("$opLabel setFailure", error)
        }
    }

    companion object {
        private const val TAG = "AudioPrimer"
        private const val STREAM_ID = "serenada-primer-stream"
    }
}
