package app.serenada.callui

import app.serenada.core.call.RealtimeCallStats
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugPanelSectionsTest {
    @Test
    fun `shows inbound and outbound audio and video codecs`() {
        val sections = buildDebugPanelSections(
            isConnected = true,
            activeTransport = "ws",
            iceConnectionState = "CONNECTED",
            connectionState = "CONNECTED",
            signalingState = "STABLE",
            roomParticipantCount = 2,
            showReconnecting = false,
            realtimeStats = RealtimeCallStats(
                audioRxCodec = "audio/red",
                audioTxCodec = "audio/opus",
                videoRxCodec = "video/VP8",
                videoTxCodec = "video/H264",
            ),
        )

        val audioCodec = sections.first { it.title == "Audio Quality" }.metrics.first { it.label == "Codec \u21F5" }
        val videoCodec = sections.first { it.title == "Video Quality" }.metrics.first { it.label == "Codec \u21F5" }

        assertEquals("audio/red / audio/opus", audioCodec.value)
        assertEquals("video/VP8 / video/H264", videoCodec.value)
    }
}
