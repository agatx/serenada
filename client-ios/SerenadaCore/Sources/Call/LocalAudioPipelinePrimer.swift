import Foundation
@preconcurrency import WebRTC

/// Holds a self-loopback pair of `RTCPeerConnection`s with the local audio
/// track attached so WebRTC's full audio pipeline (capture → AEC/NS/AGC →
/// encode → media-source stat) stays active continuously while the user is
/// in a room — including the Waiting phase before any peer joins. The
/// audio-level poller can then read the same `media-source.audioLevel`
/// stat regardless of whether real peers exist, giving consistent
/// indicator sensitivity throughout the session.
///
/// Two PCs are required because `AudioSendStream` only starts pulling
/// samples once both descriptions are set and the transport is established
/// — without an answer the source-level stat reads zero (verified
/// experimentally on Android). The pair negotiates over loopback host
/// candidates with default DTLS, so no real network traffic is generated.
@MainActor
final class LocalAudioPipelinePrimer {
    private let logger: SerenadaLogger?

    private let factory: RTCPeerConnectionFactory
    private var senderPc: RTCPeerConnection?
    private var receiverPc: RTCPeerConnection?
    private var senderObserver: PrimerPeerConnectionObserver?
    private var receiverObserver: PrimerPeerConnectionObserver?

    init(factory: RTCPeerConnectionFactory, logger: SerenadaLogger?) {
        self.factory = factory
        self.logger = logger
    }

    func start(localAudioTrack: RTCAudioTrack) {
        guard senderPc == nil else { return }
        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        // Default ICE policy + no servers → host candidates only, enough to
        // connect the pair locally without touching the network.
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)

        let senderObs = PrimerPeerConnectionObserver()
        let receiverObs = PrimerPeerConnectionObserver()
        guard let sPc = factory.peerConnection(with: config, constraints: constraints, delegate: senderObs) else {
            logger?.log(.warning, tag: "AudioPrimer", "factory.peerConnection (sender) returned nil; indicator will be silent while alone")
            return
        }
        guard let rPc = factory.peerConnection(with: config, constraints: constraints, delegate: receiverObs) else {
            logger?.log(.warning, tag: "AudioPrimer", "factory.peerConnection (receiver) returned nil; indicator will be silent while alone")
            sPc.close()
            return
        }
        // Wire ICE candidate forwarding now that both PCs exist.
        senderObs.onIceCandidate = { candidate in
            Task { @MainActor in
                rPc.add(candidate, completionHandler: { _ in })
            }
        }
        receiverObs.onIceCandidate = { candidate in
            Task { @MainActor in
                sPc.add(candidate, completionHandler: { _ in })
            }
        }
        senderPc = sPc
        receiverPc = rPc
        senderObserver = senderObs
        receiverObserver = receiverObs

        _ = sPc.add(localAudioTrack, streamIds: ["serenada-primer-stream"])

        let mediaConstraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        sPc.offer(for: mediaConstraints) { [weak self] offer, error in
            guard let offer, error == nil else {
                self?.cleanupAfterFailure("createOffer", error?.localizedDescription)
                return
            }
            sPc.setLocalDescription(offer) { [weak self] err in
                if let err { self?.cleanupAfterFailure("setLocal[sender]", err.localizedDescription) }
            }
            rPc.setRemoteDescription(offer) { [weak self] err in
                if let err {
                    self?.cleanupAfterFailure("setRemote[receiver]", err.localizedDescription)
                    return
                }
                rPc.answer(for: mediaConstraints) { [weak self] answer, error in
                    guard let answer, error == nil else {
                        self?.cleanupAfterFailure("createAnswer", error?.localizedDescription)
                        return
                    }
                    rPc.setLocalDescription(answer) { [weak self] err in
                        if let err { self?.cleanupAfterFailure("setLocal[receiver]", err.localizedDescription) }
                    }
                    sPc.setRemoteDescription(answer) { [weak self] err in
                        if let err { self?.cleanupAfterFailure("setRemote[sender]", err.localizedDescription) }
                    }
                }
            }
        }
    }

    /// Releases both PCs after a negotiation failure so a subsequent
    /// `start()` can retry. Without this the `senderPc != nil` guard
    /// wedges the primer in a half-initialized state. WebRTC's SDP
    /// callbacks fire on a non-main thread, so hop back to MainActor.
    nonisolated private func cleanupAfterFailure(_ op: String, _ message: String?) {
        Task { @MainActor [weak self] in
            self?.logger?.log(.warning, tag: "AudioPrimer", "\(op) failed: \(message ?? "nil") — closing primer")
            self?.stop()
        }
    }

    func stop() {
        receiverPc?.close()
        receiverPc = nil
        senderPc?.close()
        senderPc = nil
        senderObserver = nil
        receiverObserver = nil
    }

    /// Queries the sender PC's `media-source.audioLevel` stat. Result is
    /// in [0, 1] or `nil` if the stat isn't yet populated (e.g., the
    /// loopback is still negotiating — typically the first ~100 ms after
    /// start).
    func collectAudioLevel(_ onComplete: @escaping @Sendable (Float?) -> Void) {
        guard let senderPc else {
            onComplete(nil)
            return
        }
        senderPc.statistics { report in
            var level: Float?
            for stat in report.statistics.values {
                guard stat.type == "media-source", mediaKind(for: stat) == "audio" else { continue }
                if let raw = memberDouble(stat, key: "audioLevel") {
                    // Drop non-finite values rather than letting them poison
                    // downstream smoothing — `min`/`max` propagate NaN.
                    let f = Float(raw)
                    if f.isFinite { level = max(0, min(1, f)) }
                }
            }
            onComplete(level)
        }
    }
}

private final class PrimerPeerConnectionObserver: NSObject, RTCPeerConnectionDelegate {
    var onIceCandidate: ((RTCIceCandidate) -> Void)?

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    // The receiver PC will be handed an incoming audio track from the sender —
    // silence it so the loopback doesn't echo through the speaker. Without
    // this the user hears their own voice on a ~50 ms delay.
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        for track in stream.audioTracks { silenceAudio(track) }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams mediaStreams: [RTCMediaStream]) {
        if let audio = rtpReceiver.track as? RTCAudioTrack { silenceAudio(audio) }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        onIceCandidate?(candidate)
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    private func silenceAudio(_ track: RTCAudioTrack) {
        track.source.volume = 0
        track.isEnabled = false
    }
}
