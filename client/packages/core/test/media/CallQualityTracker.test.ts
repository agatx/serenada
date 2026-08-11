import { describe, expect, it } from 'vitest';
import { CallQualityTracker } from '../../src/media/CallQualityTracker.js';
import type { CallStats, ConnectionEvent } from '../../src/types.js';

function makeStats(partial: Partial<CallStats>): CallStats {
    return {
        transportPath: null,
        rttMs: null,
        availableOutgoingKbps: null,
        audioRxCodec: null,
        audioTxCodec: null,
        audioRxPacketLossPct: null,
        audioTxPacketLossPct: null,
        audioJitterMs: null,
        audioPlayoutDelayMs: null,
        audioConcealedPct: null,
        audioRxKbps: null,
        audioTxKbps: null,
        videoRxCodec: null,
        videoTxCodec: null,
        videoRxPacketLossPct: null,
        videoTxPacketLossPct: null,
        videoRxKbps: null,
        videoTxKbps: null,
        videoFps: null,
        videoResolution: null,
        videoFreezeCount60s: null,
        videoFreezeDuration60s: null,
        videoRetransmitPct: null,
        videoFramesDecoded: null,
        videoFramesDropped: null,
        audioPacketsLost: null,
        audioPacketsReceived: null,
        updatedAtMs: 0,
        ...partial,
    };
}

function makeTracker(): { tracker: CallQualityTracker; events: ConnectionEvent[] } {
    const events: ConnectionEvent[] = [];
    const tracker = new CallQualityTracker((e) => events.push(e));
    return { tracker, events };
}

describe('CallQualityTracker', () => {
    it('produces no summary before the first inCall transition', () => {
        const { tracker } = makeTracker();
        tracker.onStatsSample(makeStats({ rttMs: 100, audioJitterMs: 10 }), 1000);
        expect(tracker.summarize()).toBeNull();
        expect(tracker.hasStartedSampling()).toBe(false);
    });

    it('ignores pre-inCall samples (no contamination of medians/MOS)', () => {
        const { tracker } = makeTracker();
        // Pre-call sample during joining/waiting.
        tracker.onStatsSample(makeStats({ rttMs: 9999, audioJitterMs: 9999 }), 500);
        tracker.onPhaseTransition('inCall', 1000);
        tracker.onStatsSample(makeStats({ rttMs: 100, audioJitterMs: 10 }), 1100);
        const s = tracker.summarize();
        expect(s?.medianLatencyMs).toBe(100);
        expect(s?.medianJitterMs).toBe(10);
        expect(s?.qualitySampleCount).toBe(1);
    });

    it('computes point-in-time medians (odd and even counts)', () => {
        const { tracker } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        // odd count
        tracker.onStatsSample(makeStats({ rttMs: 30 }), 1100);
        tracker.onStatsSample(makeStats({ rttMs: 10 }), 1200);
        tracker.onStatsSample(makeStats({ rttMs: 20 }), 1300);
        expect(tracker.summarize()?.medianLatencyMs).toBe(20);
        // even count → mean of middle two, rounded
        tracker.onStatsSample(makeStats({ rttMs: 41 }), 1400); // sorted 10,20,30,41 → (20+30)/2=25
        expect(tracker.summarize()?.medianLatencyMs).toBe(25);
    });

    it('rounds even-count medians to nearest int', () => {
        const { tracker } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        tracker.onStatsSample(makeStats({ audioJitterMs: 5 }), 1100);
        tracker.onStatsSample(makeStats({ audioJitterMs: 8 }), 1200); // (5+8)/2 = 6.5 → 7
        expect(tracker.summarize()?.medianJitterMs).toBe(7);
    });

    it('computes packet loss from counter DELTAS, not the cumulative %', () => {
        const { tracker } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        // First sample establishes the baseline (no delta yet).
        tracker.onStatsSample(makeStats({ audioPacketsLost: 100, audioPacketsReceived: 900 }), 1100);
        // Delta: +5 lost, +95 received over the window → 5/100 = 5%.
        tracker.onStatsSample(makeStats({ audioPacketsLost: 105, audioPacketsReceived: 995 }), 1200);
        expect(tracker.summarize()?.packetLossPct).toBeCloseTo(5, 5);
    });

    it('rebaselines on a counter reset / slot replacement (delta < 0)', () => {
        const { tracker } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        tracker.onStatsSample(makeStats({ audioPacketsLost: 50, audioPacketsReceived: 950 }), 1100);
        tracker.onStatsSample(makeStats({ audioPacketsLost: 60, audioPacketsReceived: 1940 }), 1200); // +10 lost / +990
        // Counter reset (new peer connection): values drop below baseline.
        tracker.onStatsSample(makeStats({ audioPacketsLost: 2, audioPacketsReceived: 100 }), 1300); // skipped
        tracker.onStatsSample(makeStats({ audioPacketsLost: 4, audioPacketsReceived: 300 }), 1400); // +2 lost / +200
        // Accumulated deltas: lost 10+2=12, received 990+200=1190 → 12/1202 ≈ 0.998%.
        expect(tracker.summarize()?.packetLossPct).toBeCloseTo((12 / 1202) * 100, 4);
    });

    it('null MOS unless all three inputs are present', () => {
        const { tracker } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        // Only latency + jitter, no loss samples → MOS null.
        tracker.onStatsSample(makeStats({ rttMs: 50, audioJitterMs: 5 }), 1100);
        expect(tracker.summarize()?.mosScore).toBeNull();
        // Add loss baseline + delta → all three present → MOS computed.
        tracker.onStatsSample(makeStats({ audioPacketsLost: 0, audioPacketsReceived: 1000 }), 1200);
        tracker.onStatsSample(makeStats({ rttMs: 50, audioJitterMs: 5, audioPacketsLost: 0, audioPacketsReceived: 2000 }), 1300);
        const s = tracker.summarize();
        expect(s?.packetLossPct).toBe(0);
        expect(s?.mosScore).not.toBeNull();
        expect(s?.mosScore).toBeCloseTo(4.39, 2);
    });

    it('counts disconnects/reconnects and total downtime; emits reconnected with trigger', () => {
        const { tracker, events } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        tracker.onConnectionStatusTransition('recovering', 'networkLost', 2000);
        tracker.onConnectionStatusTransition('connected', 'networkLost', 5000); // 3000ms downtime
        tracker.onConnectionStatusTransition('recovering', 'unknown', 6000);
        tracker.onConnectionStatusTransition('connected', 'unknown', 6500); // 500ms downtime
        const s = tracker.summarize();
        expect(s?.countDisconnects).toBe(2);
        expect(s?.countReconnects).toBe(2);
        expect(s?.totalDropoutDurationMs).toBe(3500);
        expect(events).toEqual([
            { kind: 'reconnected', downtimeMs: 3000, reason: 'networkLost' },
            { kind: 'reconnected', downtimeMs: 500, reason: 'unknown' },
        ]);
    });

    it('treats recovering→retrying as one continuous dropout (no double count)', () => {
        const { tracker, events } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        tracker.onConnectionStatusTransition('recovering', 'unknown', 2000);
        tracker.onConnectionStatusTransition('retrying', 'unknown', 12000); // still degraded
        tracker.onConnectionStatusTransition('connected', 'unknown', 13000);
        const s = tracker.summarize();
        expect(s?.countDisconnects).toBe(1);
        expect(s?.countReconnects).toBe(1);
        expect(s?.totalDropoutDurationMs).toBe(11000);
        expect(events).toEqual([{ kind: 'reconnected', downtimeMs: 11000, reason: 'unknown' }]);
    });

    it('counts an unrecovered dropout at finalize toward downtime', () => {
        const { tracker, events } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        tracker.onConnectionStatusTransition('recovering', 'networkLost', 2000);
        tracker.finalize(7000);
        const s = tracker.summarize();
        expect(s?.countDisconnects).toBe(1);
        expect(s?.countReconnects).toBe(0);
        expect(s?.totalDropoutDurationMs).toBe(5000);
        // No reconnected event for an unrecovered dropout.
        expect(events).toEqual([]);
    });

    it('reportReconnectFailed only emits while sampling and before finalize', () => {
        const { tracker, events } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        tracker.reportReconnectFailed('timeout');
        expect(events).toEqual([{ kind: 'reconnectFailed', reason: 'timeout' }]);
        tracker.finalize(2000);
        tracker.reportReconnectFailed('networkConnectivity');
        expect(events).toHaveLength(1); // ignored after finalize
    });

    it('ignores inputs after finalize and keeps the snapshot stable', () => {
        const { tracker } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        tracker.onStatsSample(makeStats({ rttMs: 100 }), 1100);
        tracker.finalize(2000);
        const before = tracker.summarize();
        tracker.onStatsSample(makeStats({ rttMs: 5000 }), 3000);
        tracker.onConnectionStatusTransition('recovering', 'unknown', 3100);
        expect(tracker.summarize()).toEqual(before);
    });

    // #1 / ANDROID-3835 bug 2 — phantom reconnect AND phantom disconnect on remote-leave.
    it('does NOT emit a phantom reconnected or count a disconnect when the peer leaves mid-dropout', () => {
        const { tracker, events } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        // Link degrades (peer drops off the network).
        tracker.onConnectionStatusTransition('recovering', 'networkLost', 2000);
        // Peer-left arrives: phase leaves inCall, THEN the connection-status
        // machine forces status back to connected (call no longer in-call).
        tracker.onPhaseTransition('waiting', 3000);
        tracker.onConnectionStatusTransition('connected', 'unknown', 3000);
        const s = tracker.summarize();
        // The departing peer's own connection tearing down is a teardown
        // artifact, not a real link failure — it must not count as a
        // disconnect (this was the off-by-one: every call ends via someone
        // leaving, so this inflated countDisconnects on every clean call).
        expect(s?.countDisconnects).toBe(0);
        expect(s?.countReconnects).toBe(0);
        expect(s?.totalDropoutDurationMs).toBe(1000);
        expect(events).toEqual([]);
    });

    // Contrast with the teardown-artifact case above: here the dropout is
    // still open when the call ends directly at finalize() — no
    // onPhaseTransition close in between — so its fate is certain: a real,
    // unrecovered link failure.
    it('counts a disconnect when the dropout is still open at finalize (no peer-departure close)', () => {
        const { tracker, events } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        tracker.onConnectionStatusTransition('recovering', 'networkLost', 2000);
        tracker.finalize(10_000);
        const s = tracker.summarize();
        expect(s?.countDisconnects).toBe(1);
        expect(s?.countReconnects).toBe(0);
        expect(s?.totalDropoutDurationMs).toBe(8_000);
        expect(events).toEqual([]);
    });

    // #9 — skip samples while a dropout is open.
    it('skips stats samples while a dropout is open (no degraded-shoulder skew)', () => {
        const { tracker } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        tracker.onStatsSample(makeStats({ rttMs: 40, audioJitterMs: 4 }), 1100);
        // Dropout opens; the degraded-window samples must be ignored.
        tracker.onConnectionStatusTransition('recovering', 'networkLost', 1500);
        tracker.onStatsSample(makeStats({ rttMs: 5000, audioJitterMs: 800 }), 1600);
        tracker.onStatsSample(makeStats({ rttMs: 9000, audioJitterMs: 900 }), 1700);
        // Recovery; steady-state sampling resumes.
        tracker.onConnectionStatusTransition('connected', 'networkLost', 2000);
        tracker.onStatsSample(makeStats({ rttMs: 60, audioJitterMs: 6 }), 2100);
        const s = tracker.summarize();
        // Only the two non-degraded samples (40, 60) and (4, 6) count.
        expect(s?.medianLatencyMs).toBe(50);
        expect(s?.medianJitterMs).toBe(5);
        expect(s?.qualitySampleCount).toBe(2);
    });

    // #14 — only samples with a real quality contribution count. A
    // baseline-only loss sample (no delta yet) and a reset-skipped sample
    // (delta < 0) with no rtt/jitter gauges contribute nothing.
    it('counts only samples with a real quality contribution', () => {
        const { tracker } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        tracker.onStatsSample(makeStats({ audioPacketsLost: 100, audioPacketsReceived: 900 }), 1100); // baseline only → NOT counted
        tracker.onStatsSample(makeStats({ audioPacketsLost: 105, audioPacketsReceived: 995 }), 1200); // +delta → counted
        // Counter reset with no rtt/jitter gauges → contributes nothing.
        tracker.onStatsSample(makeStats({ audioPacketsLost: 1, audioPacketsReceived: 10 }), 1300);
        expect(tracker.summarize()?.qualitySampleCount).toBe(1);
    });

    it('streaming median matches sort-based median for a long run', () => {
        const { tracker } = makeTracker();
        tracker.onPhaseTransition('inCall', 1000);
        const values = [37, 5, 91, 12, 88, 3, 64, 22, 41, 7, 70, 19];
        values.forEach((v, i) => tracker.onStatsSample(makeStats({ rttMs: v }), 1100 + i));
        const sorted = [...values].sort((a, b) => a - b);
        const mid = sorted.length >> 1;
        const expected = Math.round((sorted[mid - 1]! + sorted[mid]!) / 2);
        expect(tracker.summarize()?.medianLatencyMs).toBe(expected);
    });
});
