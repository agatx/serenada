@testable import SerenadaCore
import XCTest
#if canImport(WebRTC)
import WebRTC
#endif

/// Candidate B (iOS): hold must NOT flip a sender's transceiver direction.
///
/// `attachTrackToTransceiver` used to set `direction = .recvOnly` whenever it
/// detached a track (nil track). Hold is the FIRST-EVER nil-track caller (via
/// `attachLocalTracks` for audio on every hold, and the legacy-peer video path),
/// so that flip fired `peerConnectionShouldNegotiate` -> an offer on EVERY hold
/// and again on EVERY resume, violating the branch's "no renegotiation across
/// hold" contract (and risking glare near a switch). The fix only ever sets the
/// direction when ATTACHING a track (ensure `.sendRecv`); detach clears the
/// sender track and leaves the direction untouched — matching the web
/// `MediaEngine` baseline (`replaceTrack(null)` without a direction flip).
///
/// The load-bearing, DETERMINISTIC assertions are that the direction stays
/// `.sendRecv` across hold/resume and NO transceiver is added/removed: a
/// `.sendRecv -> .recvOnly` `setDirection` (and adding an m-line) are the only
/// renegotiation triggers on detach, so a stable direction + transceiver count
/// proves no renegotiation-forcing change was made (a bare `sender.track` swap
/// does not fire `peerConnectionShouldNegotiate`).
///
/// NOTE: identity/track are compared by VALUE (transceiver count, `trackId`), not
/// `===`: `RTCPeerConnection.transceivers` and `sender.track` hand back a FRESH
/// wrapper object per access over the same native object (see
/// `RemoteVideoTransceiverRoutingTests`), so `===` across accesses is meaningless.
///
/// Only reachable where WebRTC is linked (the `SerenadaiOSTests` app target); the
/// pure-SPM `swift test` build links no WebRTC framework and compiles this out.
#if canImport(WebRTC)
@MainActor
final class PeerConnectionSlotHoldDirectionTests: XCTestCase {

    private func makeFactory() -> RTCPeerConnectionFactory {
        RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
    }

    /// A legacy (single-video) slot with a live audio + camera track attached.
    private func makeAttachedSlot(
        factory: RTCPeerConnectionFactory,
        audioTrack: RTCAudioTrack,
        videoTrack: RTCVideoTrack
    ) -> PeerConnectionSlot {
        let slot = PeerConnectionSlot(
            remoteCid: "remote-cid-1",
            factory: factory,
            iceServers: [],
            localAudioTrack: audioTrack,
            localVideoTrack: videoTrack,
            supportsIndependentContentVideo: false,
            onLocalIceCandidate: { _, _ in },
            onRemoteVideoTrack: { _, _ in },
            onConnectionStateChange: { _, _ in },
            onIceConnectionStateChange: { _, _ in },
            onSignalingStateChange: { _, _ in },
            onRenegotiationNeeded: { _ in }
        )
        // Creates the peer connection and attaches the initial audio + camera
        // tracks (both send-capable) through `attachTrackToTransceiver`.
        XCTAssertTrue(slot.ensurePeerConnection(), "peer connection should be created")
        return slot
    }

    private func transceiver(
        _ slot: PeerConnectionSlot,
        _ mediaType: RTCRtpMediaType
    ) -> RTCRtpTransceiver? {
        slot._test_peerConnection?.transceivers.first { $0.mediaType == mediaType }
    }

    func testHoldAndResumeKeepSendRecvAndStableSenders() throws {
        let factory = makeFactory()
        let audioTrack = factory.audioTrack(with: factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)), trackId: "audio-0")
        let videoTrack = factory.videoTrack(with: factory.videoSource(), trackId: "video-0")
        let slot = makeAttachedSlot(
            factory: factory, audioTrack: audioTrack, videoTrack: videoTrack
        )

        // Capture the transceiver/sender wrappers ONCE (re-accessing churns the
        // wrapper) and read subsequent native-state changes through them.
        let audioTx = try XCTUnwrap(transceiver(slot, .audio), "audio transceiver")
        let videoTx = try XCTUnwrap(transceiver(slot, .video), "video transceiver")
        let audioSender = audioTx.sender
        let videoSender = videoTx.sender
        let audioCount = { slot._test_peerConnection?.transceivers.filter { $0.mediaType == .audio }.count }
        let videoCount = { slot._test_peerConnection?.transceivers.filter { $0.mediaType == .video }.count }
        let initialAudioCount = audioCount()
        let initialVideoCount = videoCount()

        // Baseline: both senders carry their track, both send-capable.
        XCTAssertEqual(audioTx.direction, .sendRecv)
        XCTAssertEqual(videoTx.direction, .sendRecv)
        XCTAssertEqual(audioSender.track?.trackId, "audio-0")
        XCTAssertEqual(videoSender.track?.trackId, "video-0")

        // HOLD: detach both tracks (nil). Direction must stay .sendRecv and no
        // transceiver may be added/removed (either would renegotiate).
        slot.attachLocalTracks(audioTrack: nil, cameraTrack: nil)

        XCTAssertEqual(audioTx.direction, .sendRecv, "hold must NOT flip audio to .recvOnly (would renegotiate)")
        XCTAssertEqual(videoTx.direction, .sendRecv, "hold must NOT flip legacy video to .recvOnly")
        XCTAssertEqual(audioCount(), initialAudioCount, "hold must not add/remove the audio transceiver")
        XCTAssertEqual(videoCount(), initialVideoCount, "hold must not add/remove the video transceiver")
        XCTAssertNil(audioSender.track, "hold clears the audio sender track")
        XCTAssertNil(videoSender.track, "hold clears the video sender track")

        // RESUME: reattach the tracks. Direction still .sendRecv, no churn, tracks
        // restored (same native tracks, matched by trackId).
        slot.attachLocalTracks(audioTrack: audioTrack, cameraTrack: videoTrack)

        XCTAssertEqual(audioTx.direction, .sendRecv)
        XCTAssertEqual(videoTx.direction, .sendRecv)
        XCTAssertEqual(audioCount(), initialAudioCount, "resume must not add/remove the audio transceiver")
        XCTAssertEqual(videoCount(), initialVideoCount, "resume must not add/remove the video transceiver")
        XCTAssertEqual(audioSender.track?.trackId, "audio-0", "resume restores the audio track")
        XCTAssertEqual(videoSender.track?.trackId, "video-0", "resume restores the video track")

        slot.closePeerConnection()
    }

    /// Direct evidence that a full hold/resume cycle triggers no
    /// direction-forced renegotiation: with the fix the transceiver direction
    /// never changes on detach, so no `setDirection` fires
    /// `peerConnectionShouldNegotiate`. We drain the main actor after each op and
    /// assert the direction stayed `.sendRecv` throughout (the deterministic
    /// proxy — see the type doc).
    func testRepeatedHoldResumeNeverLeavesSendRecv() throws {
        let factory = makeFactory()
        let audioTrack = factory.audioTrack(with: factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)), trackId: "audio-1")
        let videoTrack = factory.videoTrack(with: factory.videoSource(), trackId: "video-1")
        let slot = makeAttachedSlot(
            factory: factory, audioTrack: audioTrack, videoTrack: videoTrack
        )

        for _ in 0..<3 {
            slot.attachLocalTracks(audioTrack: nil, cameraTrack: nil)
            XCTAssertEqual(transceiver(slot, .audio)?.direction, .sendRecv)
            XCTAssertEqual(transceiver(slot, .video)?.direction, .sendRecv)
            slot.attachLocalTracks(audioTrack: audioTrack, cameraTrack: videoTrack)
            XCTAssertEqual(transceiver(slot, .audio)?.direction, .sendRecv)
            XCTAssertEqual(transceiver(slot, .video)?.direction, .sendRecv)
        }

        slot.closePeerConnection()
    }

    /// P2 reviewer finding: `PeerConnectionSlot.attachRemote(Content)Renderer`
    /// appended to its bookkeeping WITHOUT dedup, so the resume replay onto a slot
    /// whose registration was never detached ACCUMULATED duplicate boxes across
    /// every hold/resume cycle (leak + duplicate frame delivery). The fix dedups by
    /// renderer identity. Assert against the REAL slot bookkeeping with real tracks.
    func testRemoteRendererBookkeepingDedupsAcrossHoldResumeCycles() throws {
        let factory = makeFactory()
        let audioTrack = factory.audioTrack(with: factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)), trackId: "audio-2")
        let videoTrack = factory.videoTrack(with: factory.videoSource(), trackId: "video-2")
        let slot = makeAttachedSlot(
            factory: factory, audioTrack: audioTrack, videoTrack: videoTrack
        )
        // Bind real remote camera + content tracks so attach exercises track.add.
        let cameraTrack = factory.videoTrack(with: factory.videoSource(), trackId: "remote-camera-2")
        let contentTrack = factory.videoTrack(with: factory.videoSource(), trackId: "remote-content-2")
        slot._test_setRemoteTracks(camera: cameraTrack, content: contentTrack)

        let cameraRenderer = NoopVideoRenderer()
        let contentRenderer = NoopVideoRenderer()

        // Pre-fix accumulation reproducer: repeated attach WITHOUT a detach (as the
        // buggy resume replay did on a never-detached slot) must NOT accumulate.
        for _ in 0..<3 {
            slot.attachRemoteRenderer(cameraRenderer)
            slot.attachRemoteContentRenderer(contentRenderer)
        }
        XCTAssertEqual(slot._test_remoteRendererCount, 1,
                       "repeated attach of the same remote camera renderer must not accumulate")
        XCTAssertEqual(slot._test_remoteContentRendererCount, 1,
                       "repeated attach of the same remote content renderer must not accumulate")

        // Full detach/attach (hold then resume) cycles net exactly one each.
        for cycle in 1...3 {
            slot.detachRemoteRenderer(cameraRenderer)
            slot.detachRemoteContentRenderer(contentRenderer)
            XCTAssertEqual(slot._test_remoteRendererCount, 0, "hold #\(cycle) detaches the camera renderer")
            XCTAssertEqual(slot._test_remoteContentRendererCount, 0, "hold #\(cycle) detaches the content renderer")

            slot.attachRemoteRenderer(cameraRenderer)
            slot.attachRemoteContentRenderer(contentRenderer)
            XCTAssertEqual(slot._test_remoteRendererCount, 1, "resume #\(cycle) re-attaches exactly one camera renderer")
            XCTAssertEqual(slot._test_remoteContentRendererCount, 1, "resume #\(cycle) re-attaches exactly one content renderer")
        }

        slot.closePeerConnection()
    }
}

/// Minimal real `RTCVideoRenderer` for the bookkeeping/dedup test — conforms to
/// the protocol so `RTCVideoTrack.add`/`remove` accept it, and drops frames.
private final class NoopVideoRenderer: NSObject, RTCVideoRenderer {
    func setSize(_ size: CGSize) {}
    func renderFrame(_ frame: RTCVideoFrame?) {}
}
#endif
