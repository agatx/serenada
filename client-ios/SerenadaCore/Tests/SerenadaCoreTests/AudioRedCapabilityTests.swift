@testable import SerenadaCore
import WebRTC
import XCTest

@MainActor
final class AudioRedCapabilityTests: XCTestCase {
    func testFieldTrialMakesAudioRedAvailableToCodecPreferences() {
        initializeSerenadaWebRtcFieldTrialsIfNeeded()
        let factory = RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )

        let codecs = factory.rtpSenderCapabilities(forKind: kRTCMediaStreamTrackKindAudio).codecs

        XCTAssertTrue(codecs.contains { $0.mimeType.caseInsensitiveCompare("audio/red") == .orderedSame })
    }

    func testRedPreferenceProducesRedFirstAudioOffer() throws {
        initializeSerenadaWebRtcFieldTrialsIfNeeded()
        let factory = RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
        let configuration = RTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        let peerConnection = try XCTUnwrap(
            factory.peerConnection(
                with: configuration,
                constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil),
                delegate: nil
            )
        )
        let transceiverInit = RTCRtpTransceiverInit()
        transceiverInit.direction = .sendRecv
        let transceiver = try XCTUnwrap(peerConnection.addTransceiver(of: .audio, init: transceiverInit))
        let codecs = factory.rtpSenderCapabilities(forKind: kRTCMediaStreamTrackKindAudio).codecs
        let redCodec = try XCTUnwrap(codecs.first {
            $0.mimeType.caseInsensitiveCompare("audio/red") == .orderedSame
        })
        transceiver.setCodecPreferences([redCodec] + codecs.filter { $0 != redCodec })

        let offerExpectation = expectation(description: "RED-first offer")
        peerConnection.offer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { offer, error in
            XCTAssertNil(error)
            XCTAssertEqual(firstAudioCodecMimeType(in: offer?.sdp), "audio/red")
            offerExpectation.fulfill()
        }

        wait(for: [offerExpectation], timeout: 5)
    }
}
