package app.serenada.core.call

import org.junit.Assert.assertEquals
import org.junit.Test

class RtcStatsHelpersTest {
    @Test
    fun `uses negotiated RED when stats report inner Opus`() {
        val answerSdp = """
            v=0
            m=audio 9 UDP/TLS/RTP/SAVPF 63 111
            a=rtpmap:63 red/48000/2
            a=rtpmap:111 opus/48000/2
            m=video 9 UDP/TLS/RTP/SAVPF 96
        """.trimIndent()

        val negotiatedCodec = firstAudioCodecMimeType(answerSdp)

        assertEquals("audio/red", negotiatedCodec)
        assertEquals("audio/red", effectiveAudioCodecMimeType("audio/opus", negotiatedCodec))
    }

    @Test
    fun `keeps Opus when negotiated answer prefers Opus`() {
        val answerSdp = """
            v=0
            m=audio 9 UDP/TLS/RTP/SAVPF 111 63
            a=rtpmap:111 opus/48000/2
            a=rtpmap:63 red/48000/2
        """.trimIndent()

        val negotiatedCodec = firstAudioCodecMimeType(answerSdp)

        assertEquals("audio/opus", negotiatedCodec)
        assertEquals("audio/opus", effectiveAudioCodecMimeType("audio/opus", negotiatedCodec))
    }
}
