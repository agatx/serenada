package app.serenada.core.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusDtxSdpTest {
    @Test
    fun addsDtxToOpusFmtpAndPreservesCrLf() {
        val sdp = listOf(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 63 111",
            "a=rtpmap:63 red/48000/2",
            "a=fmtp:63 111/111",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=rtpmap:96 VP8/90000",
            "",
        ).joinToString("\r\n")

        assertEquals(
            sdp.replace(
                "a=fmtp:111 minptime=10;useinbandfec=1",
                "a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1",
            ),
            enableOpusDtxInSdp(sdp),
        )
    }

    @Test
    fun replacesDisabledDtxAndIsIdempotent() {
        val sdp = "m=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=0;minptime=10\n"
        val enabled = enableOpusDtxInSdp(sdp)

        assertTrue(enabled.contains("a=fmtp:111 usedtx=1;minptime=10"))
        assertEquals(enabled, enableOpusDtxInSdp(enabled))
    }

    @Test
    fun createsMissingOpusFmtp() {
        val sdp = "m=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\n"

        assertEquals(
            "m=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=1\n",
            enableOpusDtxInSdp(sdp),
        )
    }
}
