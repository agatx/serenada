import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { TestSessionHarness } from './helpers/TestSessionHarness.js';
import type { CallStats } from '../src/types.js';

// Timer/navigator shims (mirrors SerenadaSession.test.ts).
if (typeof globalThis.window === 'undefined') {
    const handler: ProxyHandler<Record<string, unknown>> = {
        get(_target, prop) {
            if (prop === 'setTimeout') return globalThis.setTimeout.bind(globalThis);
            if (prop === 'clearTimeout') return globalThis.clearTimeout.bind(globalThis);
            if (prop === 'setInterval') return globalThis.setInterval.bind(globalThis);
            if (prop === 'clearInterval') return globalThis.clearInterval.bind(globalThis);
            return undefined;
        },
    };
    (globalThis as Record<string, unknown>).window = new Proxy({}, handler);
}
if (typeof globalThis.navigator === 'undefined') {
    (globalThis as Record<string, unknown>).navigator = {};
}

function makeStats(partial: Partial<CallStats>): CallStats {
    return {
        transportPath: null, rttMs: null, availableOutgoingKbps: null,
        audioRxCodec: null, audioTxCodec: null,
        audioRxPacketLossPct: null, audioTxPacketLossPct: null, audioJitterMs: null,
        audioPlayoutDelayMs: null, audioConcealedPct: null, audioRxKbps: null, audioTxKbps: null,
        videoRxCodec: null, videoTxCodec: null,
        videoRxPacketLossPct: null, videoTxPacketLossPct: null, videoRxKbps: null, videoTxKbps: null,
        videoFps: null, videoResolution: null, videoFreezeCount60s: null, videoFreezeDuration60s: null,
        videoRetransmitPct: null, videoFramesDecoded: null, videoFramesDropped: null,
        audioPacketsLost: null, audioPacketsReceived: null, updatedAtMs: 0,
        ...partial,
    };
}

describe('SerenadaSession telemetry surface', () => {
    let harness: TestSessionHarness;

    beforeEach(() => {
        harness = new TestSessionHarness();
    });

    afterEach(() => {
        harness.destroy();
    });

    function enterInCall(): void {
        harness.simulateJoined({
            clientId: 'me',
            participants: [{ cid: 'me' }, { cid: 'peer-1' }],
        });
    }

    it('callQualitySummary is null before reaching inCall', () => {
        harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
        expect(harness.session.callQualitySummary).toBeNull();
    });

    it('feeds stats into the live summary once inCall', () => {
        enterInCall();
        // Stats collection started by ensureStatsCollection().
        expect(harness.statsCollector.started).toBe(true);
        harness.statsCollector.emit(makeStats({ rttMs: 40, audioJitterMs: 5 }));
        harness.statsCollector.emit(makeStats({ rttMs: 60, audioJitterMs: 7 }));
        const summary = harness.session.callQualitySummary;
        expect(summary).not.toBeNull();
        expect(summary!.medianLatencyMs).toBe(50);
        expect(summary!.medianJitterMs).toBe(6);
        expect(summary!.qualitySampleCount).toBe(2);
    });

    it('finalizes the summary on leave() and keeps it readable after teardown', () => {
        enterInCall();
        harness.statsCollector.emit(makeStats({ rttMs: 100, audioJitterMs: 10 }));
        harness.session.leave();
        const summary = harness.session.callQualitySummary;
        expect(summary).not.toBeNull();
        expect(summary!.medianLatencyMs).toBe(100);
        // Stats collector stopped, but the finalized snapshot survives.
        expect(harness.statsCollector.stats).toBeNull();
    });

    it('routes ConnectionEvents to onConnectionEvent subscribers', () => {
        enterInCall();
        // Drive a dropout via media connection status, then recovery.
        harness.media.emit({ connectionStatus: 'recovering' });
        harness.media.emit({ connectionStatus: 'connected' });
        const reconnects = harness.connectionEvents.filter((e) => e.kind === 'reconnected');
        expect(reconnects.length).toBeGreaterThanOrEqual(1);
    });

    // #1 — phantom reconnect on remote-leave (full session wiring).
    it('does not emit a phantom reconnected when the peer leaves mid-dropout', () => {
        enterInCall();
        // Link degrades while in-call (peer drops off the network).
        harness.media.emit({ connectionStatus: 'recovering' });
        // Peer-left: the real MediaEngine forces status back to connected
        // (call no longer in-call); mimic that forced reset here.
        harness.media.connectionStatus = 'connected';
        harness.simulatePeerLeft('peer-1');
        const reconnects = harness.connectionEvents.filter((e) => e.kind === 'reconnected');
        expect(reconnects).toHaveLength(0);
        const summary = harness.session.callQualitySummary;
        expect(summary?.countDisconnects).toBe(1);
        expect(summary?.countReconnects).toBe(0);
    });

    // #11 — host that destroys from its reconnected handler doesn't get a
    // post-teardown state delivered.
    it('survives leave()/destroy() called from a reconnected handler', () => {
        enterInCall();
        const statesAfter: number[] = [];
        harness.session.onConnectionEvent((e) => {
            if (e.kind === 'reconnected') harness.session.leave();
        });
        harness.session.subscribe(() => statesAfter.push(1));
        harness.media.emit({ connectionStatus: 'recovering' });
        // Recovery synchronously fires reconnected → handler calls leave().
        expect(() => harness.media.emit({ connectionStatus: 'connected' })).not.toThrow();
        // Summary is finalized and readable; no crash from post-teardown state.
        expect(harness.session.callQualitySummary).not.toBeNull();
    });

    // codex P2 — destroy() finalizes the summary before stats/media teardown.
    it('finalizes the summary on destroy() even without leave()', () => {
        enterInCall();
        harness.statsCollector.emit(makeStats({ rttMs: 80, audioJitterMs: 8 }));
        harness.session.destroy();
        const summary = harness.session.callQualitySummary;
        expect(summary).not.toBeNull();
        expect(summary!.medianLatencyMs).toBe(80);
    });
});
