import XCTest
@testable import SerenadaCore

final class IceCandidateSanitizerTests: XCTestCase {

    func testPassesThroughCandidateWithValidSdpMid() {
        let input = IceCandidatePayload(
            sdpMid: "0",
            sdpMLineIndex: 0,
            candidate: "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host"
        )
        let result = sanitizeIceCandidate(input, remoteCid: "remote-1")
        XCTAssertEqual(result, input)
    }

    func testDropsCandidateWithBlankSdp() {
        let input = IceCandidatePayload(sdpMid: "0", sdpMLineIndex: 0, candidate: "")
        let result = sanitizeIceCandidate(input, remoteCid: "remote-1")
        XCTAssertNil(result)
    }

    func testDropsCandidateWithWhitespaceOnlySdp() {
        let input = IceCandidatePayload(sdpMid: "0", sdpMLineIndex: 0, candidate: "   ")
        let result = sanitizeIceCandidate(input, remoteCid: "remote-1")
        XCTAssertNil(result)
    }

    func testPassesThroughNilSdpMidSoWebRTCUsesMLineIndex() {
        let input = IceCandidatePayload(
            sdpMid: nil,
            sdpMLineIndex: 1,
            candidate: "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host"
        )
        let result = sanitizeIceCandidate(input, remoteCid: "remote-1")
        XCTAssertEqual(result, input)
    }

    func testNormalizesBlankSdpMidToNil() {
        let input = IceCandidatePayload(
            sdpMid: "",
            sdpMLineIndex: 2,
            candidate: "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host"
        )
        let result = sanitizeIceCandidate(input, remoteCid: "remote-1")
        XCTAssertNil(result?.sdpMid)
        XCTAssertEqual(result?.sdpMLineIndex, 2)
        XCTAssertEqual(result?.candidate, input.candidate)
    }

    func testNormalizesWhitespaceSdpMidToNil() {
        let input = IceCandidatePayload(
            sdpMid: "  ",
            sdpMLineIndex: 0,
            candidate: "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host"
        )
        let result = sanitizeIceCandidate(input, remoteCid: "remote-1")
        XCTAssertNil(result?.sdpMid)
    }
}
