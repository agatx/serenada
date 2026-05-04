package app.serenada.core.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.webrtc.IceCandidate

class IceCandidateSanitizerTest {

    @Test
    fun `passes through candidate with valid sdpMid`() {
        val input = IceCandidate("0", 0, "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host")
        val result = sanitizeIceCandidate(input, remoteCid = "remote-1")
        assertSame("Should return the same instance when nothing to sanitize", input, result)
    }

    @Test
    fun `drops candidate with blank sdp`() {
        val input = IceCandidate("0", 0, "")
        val result = sanitizeIceCandidate(input, remoteCid = "remote-1")
        assertNull(result)
    }

    @Test
    fun `drops candidate with whitespace-only sdp`() {
        val input = IceCandidate("0", 0, "   ")
        val result = sanitizeIceCandidate(input, remoteCid = "remote-1")
        assertNull(result)
    }

    @Test
    fun `synthesizes sdpMid from sdpMLineIndex when sdpMid is null`() {
        val input = IceCandidate(null, 1, "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host")
        val result = sanitizeIceCandidate(input, remoteCid = "remote-1")
        assertEquals("1", result?.sdpMid)
        assertEquals(1, result?.sdpMLineIndex)
        assertEquals(input.sdp, result?.sdp)
    }

    @Test
    fun `synthesizes sdpMid from sdpMLineIndex when sdpMid is blank`() {
        val input = IceCandidate("", 2, "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host")
        val result = sanitizeIceCandidate(input, remoteCid = "remote-1")
        assertEquals("2", result?.sdpMid)
        assertEquals(2, result?.sdpMLineIndex)
        assertEquals(input.sdp, result?.sdp)
    }

    @Test
    fun `synthesizes sdpMid from sdpMLineIndex when sdpMid is whitespace`() {
        val input = IceCandidate("  ", 0, "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host")
        val result = sanitizeIceCandidate(input, remoteCid = "remote-1")
        assertEquals("0", result?.sdpMid)
    }
}
