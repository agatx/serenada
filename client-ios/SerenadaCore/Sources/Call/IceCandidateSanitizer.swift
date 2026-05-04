import Foundation

/// Drops blank-SDP candidates and normalizes blank `sdpMid` to nil so native
/// WebRTC falls back to `sdpMLineIndex`. Synthesizing a numeric mid from the
/// m-line index would mismatch remote SDPs that use named mids (e.g. `audio`),
/// so we preserve a missing mid rather than fabricate one.
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
        sdpMid: nil,
        sdpMLineIndex: candidate.sdpMLineIndex,
        candidate: candidate.candidate
    )
}
