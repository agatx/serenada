package app.serenada.core.call

import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import org.webrtc.IceCandidate

/**
 * Drops blank-SDP candidates and normalizes blank `sdpMid` to null so native
 * WebRTC falls back to `sdpMLineIndex`. Synthesizing a numeric mid from the
 * m-line index would mismatch remote SDPs that use named mids (e.g. `audio`),
 * so we preserve a missing mid rather than fabricate one.
 */
internal fun sanitizeIceCandidate(
    candidate: IceCandidate,
    remoteCid: String,
    logger: SerenadaLogger? = null,
): IceCandidate? {
    val candidateSdp = candidate.sdp?.takeIf { it.isNotBlank() }
    if (candidateSdp == null) {
        logger?.log(SerenadaLogLevel.WARNING, "PeerConnection", "[$remoteCid] Dropping blank ICE candidate")
        return null
    }
    val sdpMid = candidate.sdpMid?.takeIf { it.isNotBlank() }
    if (sdpMid == candidate.sdpMid && candidateSdp == candidate.sdp) return candidate
    return IceCandidate(sdpMid, candidate.sdpMLineIndex, candidateSdp)
}
