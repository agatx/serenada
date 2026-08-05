@testable import SerenadaCore
import XCTest

final class RtcStatsHelpersTests: XCTestCase {
    func testUsesNegotiatedREDWhenStatsReportInnerOpus() {
        let answerSdp = """
        v=0
        m=audio 9 UDP/TLS/RTP/SAVPF 63 111
        a=rtpmap:63 red/48000/2
        a=rtpmap:111 opus/48000/2
        m=video 9 UDP/TLS/RTP/SAVPF 96
        """

        let negotiatedCodec = firstAudioCodecMimeType(in: answerSdp)

        XCTAssertEqual(negotiatedCodec, "audio/red")
        XCTAssertEqual(
            effectiveAudioCodecMimeType(
                statsCodecMimeType: "audio/opus",
                negotiatedAnswerCodecMimeType: negotiatedCodec
            ),
            "audio/red"
        )
    }

    func testKeepsOpusWhenNegotiatedAnswerPrefersOpus() {
        let answerSdp = """
        v=0
        m=audio 9 UDP/TLS/RTP/SAVPF 111 63
        a=rtpmap:111 opus/48000/2
        a=rtpmap:63 red/48000/2
        """

        let negotiatedCodec = firstAudioCodecMimeType(in: answerSdp)

        XCTAssertEqual(negotiatedCodec, "audio/opus")
        XCTAssertEqual(
            effectiveAudioCodecMimeType(
                statsCodecMimeType: "audio/opus",
                negotiatedAnswerCodecMimeType: negotiatedCodec
            ),
            "audio/opus"
        )
    }
}
