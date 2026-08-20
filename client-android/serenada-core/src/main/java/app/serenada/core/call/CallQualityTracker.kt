package app.serenada.core.call

import app.serenada.core.CallQualitySummary
import app.serenada.core.ConnectionEvent
import app.serenada.core.DropoutTrigger
import kotlin.math.max

/**
 * Owns the cross-platform call-quality algorithm. Driven by
 * **explicit inputs** — never by reading session state after the fact:
 * [onStatsSample], [onConnectionStatusTransition], [onPhaseTransition],
 * [finalize]. Produces an immutable [CallQualitySummary], live during the
 * call and finalized at end, and raises [ConnectionEvent]s.
 *
 * Sampling begins only at the first `InCall` transition — pre-call samples
 * (during joining/waiting) must not contaminate MOS or the medians. Samples
 * arriving while a dropout is open are **skipped** so the degraded shoulder
 * RTT/jitter don't skew the steady-state medians + MOS.
 *
 * Port of the web reference `CallQualityTracker.ts`; behavior locked by the
 * shared dropout/median unit tests + the MOS golden vector.
 */
internal class CallQualityTracker(
    private val emit: (ConnectionEvent) -> Unit,
) {
    private var inCallStartedAtMs: Long? = null

    // Streaming medians keep cost O(log n) per sample instead of re-sorting
    // the full history on every recompute.
    private val latency = StreamingMedian()
    private val jitter = StreamingMedian()
    private var qualitySampleCount = 0

    private var lastAudioPacketsLost: Long? = null
    private var lastAudioPacketsReceived: Long? = null
    private var deltaLostTotal = 0L
    private var deltaReceivedTotal = 0L
    private var hasLossSample = false

    private var dropoutOpenSinceMs: Long? = null
    private var dropoutTrigger: DropoutTrigger = DropoutTrigger.UNKNOWN
    private var countDisconnects = 0
    private var countReconnects = 0
    private var totalDropoutDurationMs = 0L

    private var finalized = false
    private var summary: CallQualitySummary? = null

    /**
     * Feed a fresh stats sample. Ignored before first InCall, after finalize,
     * and while a dropout is open (the degraded-window RTT/jitter would skew
     * the steady-state medians + MOS).
     */
    fun onStatsSample(stats: RealtimeCallStats, nowMs: Long) {
        val startedAt = inCallStartedAtMs
        if (finalized || startedAt == null || nowMs < startedAt) return
        if (dropoutOpenSinceMs != null) return

        var usable = false

        stats.rttMs?.let { latency.add(it); usable = true }
        stats.audioJitterMs?.let { jitter.add(it); usable = true }

        val lost = stats.audioPacketsLost
        val received = stats.audioPacketsReceived
        if (lost != null && received != null) {
            val prevLost = lastAudioPacketsLost
            val prevReceived = lastAudioPacketsReceived
            if (prevLost != null && prevReceived != null) {
                val dLost = lost - prevLost
                val dReceived = received - prevReceived
                if (dLost >= 0 && dReceived >= 0) {
                    deltaLostTotal += dLost
                    deltaReceivedTotal += dReceived
                    hasLossSample = true
                    // Count only samples that contributed a real quality value:
                    // an accumulated loss interval (here), or an rtt/jitter
                    // gauge above. A baseline-only or reset-skipped sample
                    // contributes nothing to the medians/loss and is not counted.
                    usable = true
                }
                // delta < 0 -> counter reset / slot replacement: skip and rebaseline.
            }
            // First loss-carrying sample only establishes the baseline (no
            // interval accumulated yet) -> not counted unless it also carried
            // an rtt/jitter gauge.
            lastAudioPacketsLost = lost
            lastAudioPacketsReceived = received
        }

        if (usable) qualitySampleCount += 1
        recompute()
    }

    /**
     * Sampling starts on the first transition into InCall. Leaving InCall
     * (e.g. the remote peer departs) closes any open dropout *silently* —
     * that forced `-> Connected` reset is a peer-departure, not a link
     * recovery, so it must not emit a phantom `reconnected` or inflate
     * `countReconnects`.
     */
    fun onPhaseTransition(next: CallPhase, nowMs: Long) {
        if (finalized) return
        if (next == CallPhase.InCall) {
            if (inCallStartedAtMs == null) {
                inCallStartedAtMs = nowMs
            }
            return
        }
        // Leaving InCall: count an in-flight dropout toward downtime but do
        // not treat the subsequent forced status reset as a recovery.
        if (inCallStartedAtMs != null && dropoutOpenSinceMs != null) {
            closeDropoutSilently(nowMs)
            recompute()
        }
    }

    /**
     * A dropout opens when the path leaves Connected while in-call, and
     * closes when it returns to Connected. The [trigger] is captured at the
     * transition so `networkLost` vs `unknown` stays accurate.
     */
    fun onConnectionStatusTransition(
        next: ConnectionStatus,
        trigger: DropoutTrigger,
        nowMs: Long,
    ) {
        if (finalized || inCallStartedAtMs == null) return

        if (next != ConnectionStatus.Connected) {
            if (dropoutOpenSinceMs == null) {
                dropoutOpenSinceMs = nowMs
                dropoutTrigger = trigger
                recompute()
            }
            return
        }

        // A recovered dropout contributes one disconnect and one reconnect.
        val openSince = dropoutOpenSinceMs ?: return
        val downtimeMs = max(0L, nowMs - openSince)
        totalDropoutDurationMs += downtimeMs
        countDisconnects += 1
        countReconnects += 1
        val reason = dropoutTrigger
        dropoutOpenSinceMs = null
        dropoutTrigger = DropoutTrigger.UNKNOWN
        recompute()
        emit(ConnectionEvent.Reconnected(downtimeMs = downtimeMs, reason = reason))
    }

    /**
     * Report that recovery was abandoned on a concrete terminal path
     * (join hard-timeout / server hard-eviction / invalid token / transport
     * exhaustion). Never call for user hangup or remote-ended.
     */
    fun reportReconnectFailed(reason: ConnectionEvent.ReconnectFailedReason) {
        if (finalized) return
        emit(ConnectionEvent.ReconnectFailed(reason = reason))
    }

    /**
     * Finalize the summary. After this, all inputs are ignored. An open
     * dropout at finalize counts toward total downtime.
     */
    fun finalize(nowMs: Long) {
        if (finalized) return
        if (dropoutOpenSinceMs != null) {
            // A dropout still open at finalization is an unrecovered disconnect.
            closeDropoutSilently(nowMs)
            countDisconnects += 1
        }
        recompute()
        finalized = true
    }

    /** The current (live or finalized) summary, or null before sampling begins. */
    fun summarize(): CallQualitySummary? = summary

    /** True once the first InCall transition has been observed. */
    fun hasStartedSampling(): Boolean = inCallStartedAtMs != null

    /**
     * Close an open dropout, counting its downtime, without emitting a
     * `reconnected` event or incrementing `countReconnects` — used for an
     * unrecovered disconnect at finalize and for a peer-departure-driven
     * reset (the link never recovered).
     */
    private fun closeDropoutSilently(nowMs: Long) {
        val openSince = dropoutOpenSinceMs ?: return
        totalDropoutDurationMs += max(0L, nowMs - openSince)
        dropoutOpenSinceMs = null
        dropoutTrigger = DropoutTrigger.UNKNOWN
    }

    private fun recompute() {
        if (inCallStartedAtMs == null) {
            summary = null
            return
        }

        val medianLatencyMs = latency.median()
        val medianJitterMs = jitter.median()
        val packetLossPct: Double? = if (hasLossSample) {
            val denom = deltaLostTotal + deltaReceivedTotal
            if (denom > 0L) deltaLostTotal.toDouble() / denom.toDouble() * 100.0 else 0.0
        } else {
            null
        }

        // MOS null policy: require all three inputs; never substitute 0.
        val mosScore = if (medianLatencyMs != null && medianJitterMs != null && packetLossPct != null) {
            Mos.compute(medianLatencyMs.toDouble(), medianJitterMs.toDouble(), packetLossPct)
        } else {
            null
        }

        summary = CallQualitySummary(
            mosScore = mosScore,
            packetLossPct = packetLossPct,
            medianLatencyMs = medianLatencyMs,
            medianJitterMs = medianJitterMs,
            countDisconnects = countDisconnects,
            countReconnects = countReconnects,
            totalDropoutDurationMs = totalDropoutDurationMs,
            qualitySampleCount = qualitySampleCount,
        )
    }
}
