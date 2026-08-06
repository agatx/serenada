@testable import SerenadaCore
import XCTest

final class OpusDtxSdpTests: XCTestCase {
    func testAddsDtxToOpusFmtpAndPreservesCrLf() {
        let sdp = [
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 63 111",
            "a=rtpmap:63 red/48000/2",
            "a=fmtp:63 111/111",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=rtpmap:96 VP8/90000",
            "",
        ].joined(separator: "\r\n")

        XCTAssertEqual(
            enableOpusDtxInSdp(sdp),
            sdp.replacingOccurrences(
                of: "a=fmtp:111 minptime=10;useinbandfec=1",
                with: "a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1"
            )
        )
    }

    func testReplacesDisabledDtxAndIsIdempotent() {
        let sdp = "m=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=0;minptime=10\n"
        let enabled = enableOpusDtxInSdp(sdp)

        XCTAssertTrue(enabled.contains("a=fmtp:111 usedtx=1;minptime=10"))
        XCTAssertEqual(enableOpusDtxInSdp(enabled), enabled)
    }

    func testCreatesMissingOpusFmtp() {
        let sdp = "m=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\n"

        XCTAssertEqual(
            enableOpusDtxInSdp(sdp),
            "m=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=1\n"
        )
    }
}
