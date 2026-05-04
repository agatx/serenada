import Foundation

/// Drops blank SDP candidates and synthesizes `sdpMid` from `sdpMLineIndex`
/// when the remote omitted it. Native WebRTC rejects candidates with blank or
/// mismatched mid metadata, so we filter at the boundary before they reach the
/// peer connection (or the pending buffer).
internal func sanitizeIceCandidate(
    _ candidate: IceCandidatePayload,
    remoteCid: String,
    logger: SerenadaLogger? = nil
) -> IceCandidatePayload? {
    let trimmedCandidate = candidate.candidate.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmedCandidate.isEmpty {
        logger?.log(.warning, tag: "PeerConnection", "[\(remoteCid)] Dropping blank ICE candidate")
        return nil
    }

    let trimmedMid = candidate.sdpMid?.trimmingCharacters(in: .whitespacesAndNewlines)
    if let mid = trimmedMid, !mid.isEmpty {
        return candidate
    }
    return IceCandidatePayload(
        sdpMid: String(candidate.sdpMLineIndex),
        sdpMLineIndex: candidate.sdpMLineIndex,
        candidate: candidate.candidate
    )
}
