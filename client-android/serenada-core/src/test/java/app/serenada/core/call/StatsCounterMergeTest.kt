package app.serenada.core.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the new telemetry counters (videoFramesDecoded/Dropped,
 * audioPacketsLost/Received) are summed across peer slots in the existing
 * merge step.
 */
class StatsCounterMergeTest {
    @Test
    fun `sums new counters across slots`() {
        val a = RealtimeCallStats(
            audioRxCodec = "audio/red", audioTxCodec = "audio/opus",
            videoRxCodec = "video/VP8", videoTxCodec = "video/VP8",
            videoFramesDecoded = 600, videoFramesDropped = 12,
            audioPacketsLost = 30, audioPacketsReceived = 2000,
            updatedAtMs = 100,
        )
        val b = RealtimeCallStats(
            audioRxCodec = "audio/opus", audioTxCodec = "audio/opus",
            videoRxCodec = "video/H264", videoTxCodec = "video/VP8",
            videoFramesDecoded = 400, videoFramesDropped = 8,
            audioPacketsLost = 10, audioPacketsReceived = 1000,
            updatedAtMs = 200,
        )
        val merged = StatsPoller.mergeRealtimeStats(listOf(a, b))!!
        assertEquals("audio/opus | audio/red", merged.audioRxCodec)
        assertEquals("audio/opus", merged.audioTxCodec)
        assertEquals("video/H264 | video/VP8", merged.videoRxCodec)
        assertEquals("video/VP8", merged.videoTxCodec)
        assertEquals(1000L, merged.videoFramesDecoded)
        assertEquals(20L, merged.videoFramesDropped)
        assertEquals(40L, merged.audioPacketsLost)
        assertEquals(3000L, merged.audioPacketsReceived)
    }

    @Test
    fun `null when no slot reports a counter`() {
        val merged = StatsPoller.mergeRealtimeStats(
            listOf(RealtimeCallStats(updatedAtMs = 1), RealtimeCallStats(updatedAtMs = 2)),
        )!!
        assertNull(merged.videoFramesDecoded)
        assertNull(merged.videoFramesDropped)
        assertNull(merged.audioPacketsLost)
        assertNull(merged.audioPacketsReceived)
    }
}
