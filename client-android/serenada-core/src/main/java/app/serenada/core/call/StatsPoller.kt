package app.serenada.core.call

import android.os.Handler
import java.util.concurrent.ExecutorService

class StatsPoller(
    private val handler: Handler,
    private val clock: SessionClock,
    private val statsExecutorProvider: () -> ExecutorService?,
    private val isActivePhase: () -> Boolean,
    private val getPeerSlots: () -> List<PeerConnectionSlotProtocol>,
    private val onStatsUpdated: (RealtimeCallStats) -> Unit,
    private val onRefreshRemoteParticipants: () -> Unit,
) {
    private var remoteVideoStatePollRunnable: Runnable? = null
    private var webrtcStatsRequestInFlight = false
    private var lastWebRtcStatsPollAtMs = 0L

    fun start() {
        if (remoteVideoStatePollRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                onRefreshRemoteParticipants()
                pollWebRtcStats()
                handler.postDelayed(this, 500)
            }
        }
        remoteVideoStatePollRunnable = runnable
        handler.post(runnable)
    }

    fun stop() {
        remoteVideoStatePollRunnable?.let { handler.removeCallbacks(it) }
        remoteVideoStatePollRunnable = null
        webrtcStatsRequestInFlight = false
        lastWebRtcStatsPollAtMs = 0L
    }

    private fun pollWebRtcStats() {
        if (!isActivePhase()) return
        val now = clock.nowMs()
        if (webrtcStatsRequestInFlight) return
        if (now - lastWebRtcStatsPollAtMs < WEBRTC_STATS_POLL_INTERVAL_MS) return
        val slots = getPeerSlots()
        if (slots.isEmpty()) return
        webrtcStatsRequestInFlight = true
        val executor = statsExecutorProvider()?.takeIf { !it.isShutdown }
        if (executor == null) { webrtcStatsRequestInFlight = false; return }
        try {
            executor.execute {
                val stats = mutableListOf<RealtimeCallStats>()
                var remaining = slots.size
                slots.forEach { slot ->
                    slot.collectWebRtcStats { _, realtimeStats ->
                        synchronized(stats) {
                            realtimeStats?.let(stats::add)
                            remaining -= 1
                            if (remaining == 0) {
                                val merged = mergeRealtimeStats(stats)
                                handler.post {
                                    webrtcStatsRequestInFlight = false
                                    lastWebRtcStatsPollAtMs = clock.nowMs()
                                    if (merged != null) {
                                        onStatsUpdated(merged)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            webrtcStatsRequestInFlight = false
        }
    }

    companion object {
        private const val WEBRTC_STATS_POLL_INTERVAL_MS = 2000L

        fun mergeRealtimeStats(stats: List<RealtimeCallStats>): RealtimeCallStats? {
            if (stats.isEmpty()) return null
            fun sumN(sel: (RealtimeCallStats) -> Double?) = stats.mapNotNull(sel).sum().takeIf { stats.any { s -> sel(s) != null } }
            fun maxN(sel: (RealtimeCallStats) -> Double?) = stats.mapNotNull(sel).maxOrNull()
            fun minN(sel: (RealtimeCallStats) -> Double?) = stats.mapNotNull(sel).minOrNull()
            return RealtimeCallStats(
                transportPath = stats.mapNotNull { it.transportPath }.distinct().joinToString().ifBlank { null },
                rttMs = maxN { it.rttMs }, availableOutgoingKbps = minN { it.availableOutgoingKbps },
                audioRxPacketLossPct = maxN { it.audioRxPacketLossPct }, audioTxPacketLossPct = maxN { it.audioTxPacketLossPct },
                audioJitterMs = maxN { it.audioJitterMs }, audioPlayoutDelayMs = maxN { it.audioPlayoutDelayMs },
                audioConcealedPct = maxN { it.audioConcealedPct },
                audioRxKbps = sumN { it.audioRxKbps }, audioTxKbps = sumN { it.audioTxKbps },
                videoRxPacketLossPct = maxN { it.videoRxPacketLossPct }, videoTxPacketLossPct = maxN { it.videoTxPacketLossPct },
                videoRxKbps = sumN { it.videoRxKbps }, videoTxKbps = sumN { it.videoTxKbps },
                videoFps = maxN { it.videoFps }, videoResolution = stats.asReversed().firstNotNullOfOrNull { it.videoResolution },
                videoFreezeCount60s = stats.mapNotNull { it.videoFreezeCount60s }.sum().takeIf { it > 0 },
                videoFreezeDuration60s = sumN { it.videoFreezeDuration60s },
                videoRetransmitPct = maxN { it.videoRetransmitPct }, videoNackPerMin = sumN { it.videoNackPerMin },
                videoPliPerMin = sumN { it.videoPliPerMin }, videoFirPerMin = sumN { it.videoFirPerMin },
                updatedAtMs = stats.maxOf { it.updatedAtMs },
            )
        }
    }
}
