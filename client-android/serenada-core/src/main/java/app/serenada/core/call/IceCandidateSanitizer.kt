package app.serenada.core.call

import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import org.webrtc.IceCandidate

/**
 * Drops blank SDP candidates and synthesizes `sdpMid` from `sdpMLineIndex` when
 * the remote omitted it. Native WebRTC rejects candidates with blank or
 * mismatched mid metadata, so we filter at the boundary before they reach the
 * peer connection (or the pending buffer).
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
    if (sdpMid != null) return candidate
    return IceCandidate(candidate.sdpMLineIndex.toString(), candidate.sdpMLineIndex, candidateSdp)
}
