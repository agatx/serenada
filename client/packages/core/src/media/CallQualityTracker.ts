import type {
    CallPhase,
    CallQualitySummary,
    CallStats,
    ConnectionEvent,
    ConnectionStatus,
    DropoutTrigger,
} from '../types.js';
import { computeMos } from './mos.js';
import { StreamingMedian } from './streamingMedian.js';

/**
 * Owns the cross-platform call-quality algorithm. Driven by
 * **explicit inputs** — never by reading session state after the fact:
 * `onStatsSample`, `onConnectionStatusTransition`, `onPhaseTransition`,
 * `finalize`. Produces an immutable {@link CallQualitySummary}, live during
 * the call and finalized at end, and raises {@link ConnectionEvent}s.
 *
 * Sampling begins only at the first `inCall` transition — pre-call samples
 * (during joining/waiting) must not contaminate MOS or the medians. Samples
 * arriving while a dropout is open are **skipped** so the degraded shoulder
 * RTT/jitter don't skew the steady-state medians + MOS.
 *
 * Interval math (dropout downtime) uses a **monotonic** `now` fed by the
 * session, so a wall-clock step doesn't record a real outage as 0ms.
 *
 * @internal
 */
export class CallQualityTracker {
    // Sampling only begins once we observe the first inCall transition.
    private inCallStartedAtMs: number | null = null;

    // Point-in-time gauges accumulated across the in-call window. Streaming
    // medians keep cost O(log n) per sample instead of re-sorting the full
    // history on every recompute.
    private readonly latency = new StreamingMedian();
    private readonly jitter = new StreamingMedian();
    private qualitySampleCount = 0;

    // Counter-delta packet loss. `null` baselines reset on counter
    // resets / peer-connection replacement (delta < 0 → new baseline).
    private lastAudioPacketsLost: number | null = null;
    private lastAudioPacketsReceived: number | null = null;
    private deltaLostTotal = 0;
    private deltaReceivedTotal = 0;
    private hasLossSample = false;

    // Dropout state machine.
    private dropoutOpenSinceMs: number | null = null;
    private dropoutTrigger: DropoutTrigger = 'unknown';
    private countDisconnects = 0;
    private countReconnects = 0;
    private totalDropoutDurationMs = 0;

    private finalized = false;
    private summary: CallQualitySummary | null = null;

    private readonly emit: (event: ConnectionEvent) => void;

    constructor(emit: (event: ConnectionEvent) => void) {
        this.emit = emit;
    }

    /**
     * Feed a fresh stats snapshot. Ignored until the first `inCall`
     * transition, after `finalize()`, and while a dropout is open (the
     * degraded-window RTT/jitter would skew the steady-state medians + MOS).
     */
    onStatsSample(stats: CallStats, now: number): void {
        if (this.finalized || this.inCallStartedAtMs === null) return;
        if (now < this.inCallStartedAtMs) return;
        // Skip samples while a dropout is open — degraded-shoulder RTT/jitter
        // must not pull the steady-state medians + MOS.
        if (this.dropoutOpenSinceMs !== null) return;

        let usable = false;

        if (stats.rttMs !== null) {
            this.latency.add(stats.rttMs);
            usable = true;
        }
        if (stats.audioJitterMs !== null) {
            this.jitter.add(stats.audioJitterMs);
            usable = true;
        }

        const lost = stats.audioPacketsLost;
        const received = stats.audioPacketsReceived;
        if (lost !== null && received !== null) {
            if (this.lastAudioPacketsLost !== null && this.lastAudioPacketsReceived !== null) {
                const dLost = lost - this.lastAudioPacketsLost;
                const dReceived = received - this.lastAudioPacketsReceived;
                if (dLost >= 0 && dReceived >= 0) {
                    this.deltaLostTotal += dLost;
                    this.deltaReceivedTotal += dReceived;
                    this.hasLossSample = true;
                    // Count only samples that contributed a real quality value:
                    // an accumulated loss interval (here), or an rtt/jitter
                    // gauge above. A baseline-only or reset-skipped sample
                    // contributes nothing to the medians/loss and is not counted.
                    usable = true;
                }
                // delta < 0 → counter reset / slot replacement: skip this
                // interval and rebaseline below.
            }
            // First loss-carrying sample only establishes the baseline (no
            // interval accumulated yet) → not counted unless it also carried
            // an rtt/jitter gauge.
            this.lastAudioPacketsLost = lost;
            this.lastAudioPacketsReceived = received;
        }

        if (usable) this.qualitySampleCount += 1;
        this.recompute();
    }

    /**
     * Notify a phase transition. Sampling starts on the first transition
     * into `inCall`. Leaving `inCall` (e.g. the remote peer departs) closes
     * any open dropout *silently* — that forced `→ connected` reset is a
     * peer-departure, not a link recovery, so it must not emit a phantom
     * `reconnected` or inflate `countReconnects`.
     */
    onPhaseTransition(next: CallPhase, now: number): void {
        if (this.finalized) return;
        if (next === 'inCall') {
            if (this.inCallStartedAtMs === null) {
                this.inCallStartedAtMs = now;
            }
            return;
        }
        // Leaving inCall: count an in-flight dropout toward downtime but do
        // not treat the subsequent forced status reset as a recovery.
        if (this.inCallStartedAtMs !== null && this.dropoutOpenSinceMs !== null) {
            this.closeDropoutSilently(now);
            this.recompute();
        }
    }

    /**
     * Notify a connection-status transition with the trigger that caused a
     * degradation, so `networkLost` vs `unknown` is accurate. A dropout
     * opens when the path leaves `connected` while in-call and closes when
     * it returns to `connected`.
     */
    onConnectionStatusTransition(
        next: ConnectionStatus,
        trigger: DropoutTrigger,
        now: number,
    ): void {
        if (this.finalized || this.inCallStartedAtMs === null) return;

        const degraded = next !== 'connected';
        if (degraded) {
            if (this.dropoutOpenSinceMs === null) {
                this.dropoutOpenSinceMs = now;
                this.dropoutTrigger = trigger;
                this.recompute();
            }
            return;
        }

        // next === 'connected' → recovery. countDisconnects is credited here,
        // alongside countReconnects, rather than at dropout-open, so a live
        // read mid-blip doesn't show a premature count.
        if (this.dropoutOpenSinceMs !== null) {
            const downtimeMs = Math.max(0, now - this.dropoutOpenSinceMs);
            this.totalDropoutDurationMs += downtimeMs;
            this.countDisconnects += 1;
            this.countReconnects += 1;
            const reason = this.dropoutTrigger;
            this.dropoutOpenSinceMs = null;
            this.dropoutTrigger = 'unknown';
            this.recompute();
            this.emit({ kind: 'reconnected', downtimeMs, reason });
        }
    }

    /**
     * Report that recovery was abandoned on a concrete terminal path
     * (join hard-timeout / server hard-eviction / invalid token / transport
     * exhaustion). Never call for user hangup or remote-ended.
     */
    reportReconnectFailed(reason: 'timeout' | 'networkConnectivity'): void {
        if (this.finalized) return;
        this.emit({ kind: 'reconnectFailed', reason });
    }

    /**
     * Finalize the summary. After this, all inputs are ignored and the
     * snapshot returned by {@link summarize} no longer changes. If a dropout
     * is still open it is counted toward total downtime (an unrecovered
     * disconnect at termination).
     */
    finalize(now: number): void {
        if (this.finalized) return;
        if (this.dropoutOpenSinceMs !== null) {
            // Unresolved at finalize (call ended while still disconnected) —
            // the dropout's fate is now certain: a real, unrecovered link
            // failure, so unlike a peer-departure close, it is still counted.
            this.closeDropoutSilently(now);
            this.countDisconnects += 1;
        }
        this.recompute();
        this.finalized = true;
    }

    /** The current (live or finalized) summary, or `null` before sampling begins. */
    summarize(): CallQualitySummary | null {
        return this.summary;
    }

    /** `true` once the first `inCall` transition has been observed. */
    hasStartedSampling(): boolean {
        return this.inCallStartedAtMs !== null;
    }

    /**
     * Close an open dropout, counting its downtime, without emitting a
     * `reconnected` event or incrementing `countReconnects` — used for an
     * unrecovered disconnect at finalize and for a peer-departure-driven
     * reset (the link never recovered).
     */
    private closeDropoutSilently(now: number): void {
        if (this.dropoutOpenSinceMs === null) return;
        this.totalDropoutDurationMs += Math.max(0, now - this.dropoutOpenSinceMs);
        this.dropoutOpenSinceMs = null;
        this.dropoutTrigger = 'unknown';
    }

    private recompute(): void {
        if (this.inCallStartedAtMs === null) {
            this.summary = null;
            return;
        }

        const medianLatencyMs = this.latency.median();
        const medianJitterMs = this.jitter.median();
        const packetLossPct = this.hasLossSample
            ? (this.deltaReceivedTotal + this.deltaLostTotal > 0
                ? (this.deltaLostTotal / (this.deltaLostTotal + this.deltaReceivedTotal)) * 100
                : 0)
            : null;

        // MOS null policy: require all three inputs to be defined; never
        // substitute 0 for a missing input (that would inflate MOS).
        const mosScore = (medianLatencyMs !== null && medianJitterMs !== null && packetLossPct !== null)
            ? computeMos(medianLatencyMs, medianJitterMs, packetLossPct)
            : null;

        this.summary = {
            mosScore,
            packetLossPct,
            medianLatencyMs,
            medianJitterMs,
            countDisconnects: this.countDisconnects,
            countReconnects: this.countReconnects,
            totalDropoutDurationMs: this.totalDropoutDurationMs,
            qualitySampleCount: this.qualitySampleCount,
        };
    }
}
