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
    fun `passes through null sdpMid so WebRTC uses sdpMLineIndex`() {
        val input = IceCandidate(null, 1, "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host")
        val result = sanitizeIceCandidate(input, remoteCid = "remote-1")
        assertSame(input, result)
    }

    @Test
    fun `normalizes blank sdpMid to null`() {
        val input = IceCandidate("", 2, "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host")
        val result = sanitizeIceCandidate(input, remoteCid = "remote-1")
        assertNull(result?.sdpMid)
        assertEquals(2, result?.sdpMLineIndex)
        assertEquals(input.sdp, result?.sdp)
    }

    @Test
    fun `normalizes whitespace sdpMid to null`() {
        val input = IceCandidate("  ", 0, "candidate:1 1 udp 2113937151 192.168.1.1 54321 typ host")
        val result = sanitizeIceCandidate(input, remoteCid = "remote-1")
        assertNull(result?.sdpMid)
    }
}
