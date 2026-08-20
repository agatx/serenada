package app.serenada.core.call

import app.serenada.core.ConnectionEvent
import app.serenada.core.DropoutTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallQualityTrackerTest {
    private val events = mutableListOf<ConnectionEvent>()
    private fun tracker() = CallQualityTracker { events.add(it) }

    private fun stats(
        rttMs: Double? = null,
        audioJitterMs: Double? = null,
        audioPacketsLost: Long? = null,
        audioPacketsReceived: Long? = null,
    ) = RealtimeCallStats(
        rttMs = rttMs,
        audioJitterMs = audioJitterMs,
        audioPacketsLost = audioPacketsLost,
        audioPacketsReceived = audioPacketsReceived,
    )

    @Test
    fun `no summary before first inCall`() {
        val t = tracker()
        t.onStatsSample(stats(rttMs = 100.0, audioJitterMs = 10.0), 1000)
        assertNull(t.summarize())
        assertTrue(!t.hasStartedSampling())
    }

    @Test
    fun `ignores pre-inCall samples`() {
        val t = tracker()
        t.onStatsSample(stats(rttMs = 9999.0, audioJitterMs = 9999.0), 500)
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onStatsSample(stats(rttMs = 100.0, audioJitterMs = 10.0), 1100)
        val s = t.summarize()!!
        assertEquals(100, s.medianLatencyMs)
        assertEquals(10, s.medianJitterMs)
        assertEquals(1, s.qualitySampleCount)
    }

    @Test
    fun `point-in-time medians odd and even`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onStatsSample(stats(rttMs = 30.0), 1100)
        t.onStatsSample(stats(rttMs = 10.0), 1200)
        t.onStatsSample(stats(rttMs = 20.0), 1300)
        assertEquals(20, t.summarize()!!.medianLatencyMs)
        t.onStatsSample(stats(rttMs = 41.0), 1400) // 10,20,30,41 -> (20+30)/2=25
        assertEquals(25, t.summarize()!!.medianLatencyMs)
    }

    @Test
    fun `even-count median rounds to nearest int`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onStatsSample(stats(audioJitterMs = 5.0), 1100)
        t.onStatsSample(stats(audioJitterMs = 8.0), 1200) // (5+8)/2 = 6.5 -> 7
        assertEquals(7, t.summarize()!!.medianJitterMs)
    }

    @Test
    fun `packet loss from counter deltas`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onStatsSample(stats(audioPacketsLost = 100, audioPacketsReceived = 900), 1100)
        t.onStatsSample(stats(audioPacketsLost = 105, audioPacketsReceived = 995), 1200) // +5/+95
        assertEquals(5.0, t.summarize()!!.packetLossPct!!, 0.0001)
    }

    @Test
    fun `rebaselines on counter reset`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onStatsSample(stats(audioPacketsLost = 50, audioPacketsReceived = 950), 1100)
        t.onStatsSample(stats(audioPacketsLost = 60, audioPacketsReceived = 1940), 1200) // +10/+990
        t.onStatsSample(stats(audioPacketsLost = 2, audioPacketsReceived = 100), 1300) // reset, skipped
        t.onStatsSample(stats(audioPacketsLost = 4, audioPacketsReceived = 300), 1400) // +2/+200
        // lost 12, received 1190 -> 12/1202
        assertEquals(12.0 / 1202.0 * 100.0, t.summarize()!!.packetLossPct!!, 0.0001)
    }

    @Test
    fun `null MOS unless all three inputs present`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onStatsSample(stats(rttMs = 50.0, audioJitterMs = 5.0), 1100)
        assertNull(t.summarize()!!.mosScore)
        t.onStatsSample(stats(audioPacketsLost = 0, audioPacketsReceived = 1000), 1200)
        t.onStatsSample(stats(rttMs = 50.0, audioJitterMs = 5.0, audioPacketsLost = 0, audioPacketsReceived = 2000), 1300)
        val s = t.summarize()!!
        assertEquals(0.0, s.packetLossPct!!, 0.0001)
        assertEquals(4.39, s.mosScore!!, 0.01)
    }

    @Test
    fun `counts disconnects reconnects downtime and emits reconnected with trigger`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onConnectionStatusTransition(ConnectionStatus.Recovering, DropoutTrigger.NETWORK_LOST, 2000)
        t.onConnectionStatusTransition(ConnectionStatus.Connected, DropoutTrigger.NETWORK_LOST, 5000)
        t.onConnectionStatusTransition(ConnectionStatus.Recovering, DropoutTrigger.UNKNOWN, 6000)
        t.onConnectionStatusTransition(ConnectionStatus.Connected, DropoutTrigger.UNKNOWN, 6500)
        val s = t.summarize()!!
        assertEquals(2, s.countDisconnects)
        assertEquals(2, s.countReconnects)
        assertEquals(3500L, s.totalDropoutDurationMs)
        assertEquals(
            listOf(
                ConnectionEvent.Reconnected(3000L, DropoutTrigger.NETWORK_LOST),
                ConnectionEvent.Reconnected(500L, DropoutTrigger.UNKNOWN),
            ),
            events,
        )
    }

    @Test
    fun `recovering to retrying is one continuous dropout`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onConnectionStatusTransition(ConnectionStatus.Recovering, DropoutTrigger.UNKNOWN, 2000)
        t.onConnectionStatusTransition(ConnectionStatus.Retrying, DropoutTrigger.UNKNOWN, 12000)
        t.onConnectionStatusTransition(ConnectionStatus.Connected, DropoutTrigger.UNKNOWN, 13000)
        val s = t.summarize()!!
        assertEquals(1, s.countDisconnects)
        assertEquals(1, s.countReconnects)
        assertEquals(11000L, s.totalDropoutDurationMs)
        assertEquals(listOf(ConnectionEvent.Reconnected(11000L, DropoutTrigger.UNKNOWN)), events)
    }

    @Test
    fun `unrecovered dropout at finalize counts toward downtime`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onConnectionStatusTransition(ConnectionStatus.Recovering, DropoutTrigger.NETWORK_LOST, 2000)
        t.finalize(7000)
        val s = t.summarize()!!
        assertEquals(1, s.countDisconnects)
        assertEquals(0, s.countReconnects)
        assertEquals(5000L, s.totalDropoutDurationMs)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `reconnectFailed only emits before finalize`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.reportReconnectFailed(ConnectionEvent.ReconnectFailedReason.TIMEOUT)
        assertEquals(listOf(ConnectionEvent.ReconnectFailed(ConnectionEvent.ReconnectFailedReason.TIMEOUT)), events)
        t.finalize(2000)
        t.reportReconnectFailed(ConnectionEvent.ReconnectFailedReason.NETWORK_CONNECTIVITY)
        assertEquals(1, events.size)
    }

    @Test
    fun `ignores inputs after finalize`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onStatsSample(stats(rttMs = 100.0), 1100)
        t.finalize(2000)
        val before = t.summarize()
        t.onStatsSample(stats(rttMs = 5000.0), 3000)
        t.onConnectionStatusTransition(ConnectionStatus.Recovering, DropoutTrigger.UNKNOWN, 3100)
        assertEquals(before, t.summarize())
    }

    @Test
    fun `no phantom reconnected or disconnected when peer leaves mid-dropout`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onConnectionStatusTransition(ConnectionStatus.Recovering, DropoutTrigger.NETWORK_LOST, 2000)
        // Peer-left: phase leaves InCall, THEN the forced status reset to Connected.
        t.onPhaseTransition(CallPhase.Waiting, 3000)
        t.onConnectionStatusTransition(ConnectionStatus.Connected, DropoutTrigger.UNKNOWN, 3000)
        val s = t.summarize()!!
        // Peer-departure teardown is not a link failure and must not
        // contribute to the disconnect or reconnect counts.
        assertEquals(0, s.countDisconnects)
        assertEquals(0, s.countReconnects)
        assertEquals(1000L, s.totalDropoutDurationMs)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `unresolved dropout still open at finalize counts as a disconnect`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onConnectionStatusTransition(ConnectionStatus.Recovering, DropoutTrigger.NETWORK_LOST, 2000)
        t.finalize(10_000)
        val s = t.summarize()!!
        assertEquals(1, s.countDisconnects)
        assertEquals(0, s.countReconnects)
        assertEquals(8_000, s.totalDropoutDurationMs)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `skips stats samples while a dropout is open`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onStatsSample(stats(rttMs = 40.0, audioJitterMs = 4.0), 1100)
        t.onConnectionStatusTransition(ConnectionStatus.Recovering, DropoutTrigger.NETWORK_LOST, 1500)
        t.onStatsSample(stats(rttMs = 5000.0, audioJitterMs = 800.0), 1600)
        t.onStatsSample(stats(rttMs = 9000.0, audioJitterMs = 900.0), 1700)
        t.onConnectionStatusTransition(ConnectionStatus.Connected, DropoutTrigger.NETWORK_LOST, 2000)
        t.onStatsSample(stats(rttMs = 60.0, audioJitterMs = 6.0), 2100)
        val s = t.summarize()!!
        assertEquals(50, s.medianLatencyMs)
        assertEquals(5, s.medianJitterMs)
        assertEquals(2, s.qualitySampleCount)
    }

    // Baseline-only loss samples and reset-skipped samples contribute nothing.
    @Test
    fun `counts only samples with a real quality contribution`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        t.onStatsSample(stats(audioPacketsLost = 100, audioPacketsReceived = 900), 1100) // baseline only -> NOT counted
        t.onStatsSample(stats(audioPacketsLost = 105, audioPacketsReceived = 995), 1200) // +delta -> counted
        t.onStatsSample(stats(audioPacketsLost = 1, audioPacketsReceived = 10), 1300) // reset, no gauges
        assertEquals(1, t.summarize()!!.qualitySampleCount)
    }

    @Test
    fun `streaming median matches sort-based median for a long run`() {
        val t = tracker()
        t.onPhaseTransition(CallPhase.InCall, 1000)
        val values = listOf(37.0, 5.0, 91.0, 12.0, 88.0, 3.0, 64.0, 22.0, 41.0, 7.0, 70.0, 19.0)
        values.forEachIndexed { i, v -> t.onStatsSample(stats(rttMs = v), 1100L + i) }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val expected = Math.round((sorted[mid - 1] + sorted[mid]) / 2.0).toInt()
        assertEquals(expected, t.summarize()!!.medianLatencyMs)
    }
}
