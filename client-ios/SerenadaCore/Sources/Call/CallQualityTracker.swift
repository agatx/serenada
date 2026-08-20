import Foundation

/// Owns the cross-platform call-quality algorithm. Driven by
/// **explicit inputs** — never by reading session state after the fact:
/// `onStatsSample`, `onConnectionStatusTransition`, `onPhaseTransition`,
/// `finalize`. Produces an immutable ``CallQualitySummary``, live during the
/// call and finalized at end, and raises ``ConnectionEvent``s.
///
/// Sampling begins only at the first `.inCall` transition — pre-call samples
/// (during joining/waiting) must not contaminate MOS or the medians. Samples
/// arriving while a dropout is open are **skipped** so the degraded shoulder
/// RTT/jitter don't skew the steady-state medians + MOS.
///
/// Port of the web reference `CallQualityTracker.ts`; behavior locked by the
/// shared dropout/median unit tests + the MOS golden vector.
@MainActor
final class CallQualityTracker {
    private let emit: (ConnectionEvent) -> Void

    private var inCallStartedAtMs: Int64?

    // Streaming medians keep cost O(log n) per sample instead of re-sorting
    // the full history on every recompute.
    private var latency = StreamingMedian()
    private var jitter = StreamingMedian()
    private var qualitySampleCount = 0

    private var lastAudioPacketsLost: Int64?
    private var lastAudioPacketsReceived: Int64?
    private var deltaLostTotal: Int64 = 0
    private var deltaReceivedTotal: Int64 = 0
    private var hasLossSample = false

    private var dropoutOpenSinceMs: Int64?
    private var dropoutTrigger: DropoutTrigger = .unknown
    private var countDisconnects = 0
    private var countReconnects = 0
    private var totalDropoutDurationMs: Int64 = 0

    private var finalized = false
    private var summary: CallQualitySummary?

    init(emit: @escaping (ConnectionEvent) -> Void) {
        self.emit = emit
    }

    /// Feed a fresh stats sample. Ignored before first inCall, after finalize,
    /// and while a dropout is open (the degraded-window RTT/jitter would skew
    /// the steady-state medians + MOS).
    func onStatsSample(_ stats: RealtimeCallStats, nowMs: Int64) {
        guard !finalized, let startedAt = inCallStartedAtMs, nowMs >= startedAt else { return }
        guard dropoutOpenSinceMs == nil else { return }

        var usable = false

        if let rtt = stats.rttMs { latency.add(rtt); usable = true }
        if let jit = stats.audioJitterMs { jitter.add(jit); usable = true }

        if let lost = stats.audioPacketsLost, let received = stats.audioPacketsReceived {
            if let prevLost = lastAudioPacketsLost, let prevReceived = lastAudioPacketsReceived {
                let dLost = lost - prevLost
                let dReceived = received - prevReceived
                if dLost >= 0 && dReceived >= 0 {
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

        if usable { qualitySampleCount += 1 }
        recompute()
    }

    /// Sampling starts on the first transition into `.inCall`. Leaving
    /// `.inCall` (e.g. the remote peer departs) closes any open dropout
    /// *silently* — that forced `-> .connected` reset is a peer-departure, not
    /// a link recovery, so it must not emit a phantom `reconnected` or inflate
    /// `countReconnects`.
    func onPhaseTransition(_ next: CallPhase, nowMs: Int64) {
        guard !finalized else { return }
        if next == .inCall {
            if inCallStartedAtMs == nil {
                inCallStartedAtMs = nowMs
            }
            return
        }
        // Leaving inCall: count an in-flight dropout toward downtime but do
        // not treat the subsequent forced status reset as a recovery.
        if inCallStartedAtMs != nil, dropoutOpenSinceMs != nil {
            closeDropoutSilently(nowMs: nowMs)
            recompute()
        }
    }

    /// A dropout opens when the path leaves `.connected` while in-call, and
    /// closes when it returns to `.connected`. The `trigger` is captured at
    /// the transition so `networkLost` vs `unknown` stays accurate.
    func onConnectionStatusTransition(
        _ next: SerenadaConnectionStatus,
        trigger: DropoutTrigger,
        nowMs: Int64
    ) {
        guard !finalized, inCallStartedAtMs != nil else { return }

        if next != .connected {
            if dropoutOpenSinceMs == nil {
                dropoutOpenSinceMs = nowMs
                dropoutTrigger = trigger
                recompute()
            }
            return
        }

        // A recovered dropout contributes one disconnect and one reconnect.
        guard let openSince = dropoutOpenSinceMs else { return }
        let downtimeMs = max(0, nowMs - openSince)
        totalDropoutDurationMs += downtimeMs
        countDisconnects += 1
        countReconnects += 1
        let reason = dropoutTrigger
        dropoutOpenSinceMs = nil
        dropoutTrigger = .unknown
        recompute()
        emit(.reconnected(downtimeMs: downtimeMs, reason: reason))
    }

    /// Report that recovery was abandoned on a concrete terminal path
    /// (join hard-timeout / server hard-eviction / invalid token / transport
    /// exhaustion). Never call for user hangup or remote-ended.
    func reportReconnectFailed(_ reason: ConnectionEvent.ReconnectFailedReason) {
        guard !finalized else { return }
        emit(.reconnectFailed(reason: reason))
    }

    /// Finalize the summary. After this, all inputs are ignored. An open
    /// dropout at finalize counts toward total downtime.
    func finalize(nowMs: Int64) {
        guard !finalized else { return }
        if dropoutOpenSinceMs != nil {
            // A dropout still open at finalization is an unrecovered disconnect.
            closeDropoutSilently(nowMs: nowMs)
            countDisconnects += 1
        }
        recompute()
        finalized = true
    }

    /// The current (live or finalized) summary, or nil before sampling begins.
    func summarize() -> CallQualitySummary? { summary }

    /// True once the first inCall transition has been observed.
    func hasStartedSampling() -> Bool { inCallStartedAtMs != nil }

    /// Close an open dropout, counting its downtime, without emitting a
    /// `reconnected` event or incrementing `countReconnects` — used for an
    /// unrecovered disconnect at finalize and for a peer-departure-driven
    /// reset (the link never recovered).
    private func closeDropoutSilently(nowMs: Int64) {
        guard let openSince = dropoutOpenSinceMs else { return }
        totalDropoutDurationMs += max(0, nowMs - openSince)
        dropoutOpenSinceMs = nil
        dropoutTrigger = .unknown
    }

    private func recompute() {
        guard inCallStartedAtMs != nil else {
            summary = nil
            return
        }

        let medianLatencyMs = latency.median()
        let medianJitterMs = jitter.median()
        let packetLossPct: Double?
        if hasLossSample {
            let denom = deltaLostTotal + deltaReceivedTotal
            packetLossPct = denom > 0 ? Double(deltaLostTotal) / Double(denom) * 100.0 : 0.0
        } else {
            packetLossPct = nil
        }

        // MOS null policy: require all three inputs; never substitute 0.
        let mosScore: Double?
        if let lat = medianLatencyMs, let jit = medianJitterMs, let loss = packetLossPct {
            mosScore = Mos.compute(rttMs: Double(lat), jitterMs: Double(jit), lossPct: loss)
        } else {
            mosScore = nil
        }

        summary = CallQualitySummary(
            mosScore: mosScore,
            packetLossPct: packetLossPct,
            medianLatencyMs: medianLatencyMs,
            medianJitterMs: medianJitterMs,
            countDisconnects: countDisconnects,
            countReconnects: countReconnects,
            totalDropoutDurationMs: totalDropoutDurationMs,
            qualitySampleCount: qualitySampleCount
        )
    }
}
