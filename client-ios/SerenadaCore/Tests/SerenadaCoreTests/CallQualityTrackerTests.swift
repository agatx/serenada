@testable import SerenadaCore
import XCTest

@MainActor
final class CallQualityTrackerTests: XCTestCase {
    private var events: [ConnectionEvent] = []

    override func setUp() {
        super.setUp()
        events = []
    }

    private func makeTracker() -> CallQualityTracker {
        CallQualityTracker { [weak self] event in self?.events.append(event) }
    }

    private func stats(
        rttMs: Double? = nil,
        audioJitterMs: Double? = nil,
        audioPacketsLost: Int64? = nil,
        audioPacketsReceived: Int64? = nil
    ) -> RealtimeCallStats {
        RealtimeCallStats(
            rttMs: rttMs,
            audioJitterMs: audioJitterMs,
            audioPacketsLost: audioPacketsLost,
            audioPacketsReceived: audioPacketsReceived
        )
    }

    func testNoSummaryBeforeFirstInCall() {
        let t = makeTracker()
        t.onStatsSample(stats(rttMs: 100, audioJitterMs: 10), nowMs: 1000)
        XCTAssertNil(t.summarize())
        XCTAssertFalse(t.hasStartedSampling())
    }

    func testIgnoresPreInCallSamples() {
        let t = makeTracker()
        t.onStatsSample(stats(rttMs: 9999, audioJitterMs: 9999), nowMs: 500)
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onStatsSample(stats(rttMs: 100, audioJitterMs: 10), nowMs: 1100)
        let s = t.summarize()!
        XCTAssertEqual(s.medianLatencyMs, 100)
        XCTAssertEqual(s.medianJitterMs, 10)
        XCTAssertEqual(s.qualitySampleCount, 1)
    }

    func testPointInTimeMediansOddAndEven() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onStatsSample(stats(rttMs: 30), nowMs: 1100)
        t.onStatsSample(stats(rttMs: 10), nowMs: 1200)
        t.onStatsSample(stats(rttMs: 20), nowMs: 1300)
        XCTAssertEqual(t.summarize()!.medianLatencyMs, 20)
        t.onStatsSample(stats(rttMs: 41), nowMs: 1400) // 10,20,30,41 -> (20+30)/2=25
        XCTAssertEqual(t.summarize()!.medianLatencyMs, 25)
    }

    func testEvenCountMedianRoundsToNearestInt() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onStatsSample(stats(audioJitterMs: 5), nowMs: 1100)
        t.onStatsSample(stats(audioJitterMs: 8), nowMs: 1200) // (5+8)/2 = 6.5 -> 7
        XCTAssertEqual(t.summarize()!.medianJitterMs, 7)
    }

    func testPacketLossFromCounterDeltas() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onStatsSample(stats(audioPacketsLost: 100, audioPacketsReceived: 900), nowMs: 1100)
        t.onStatsSample(stats(audioPacketsLost: 105, audioPacketsReceived: 995), nowMs: 1200) // +5/+95
        XCTAssertEqual(t.summarize()!.packetLossPct!, 5.0, accuracy: 0.0001)
    }

    func testRebaselinesOnCounterReset() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onStatsSample(stats(audioPacketsLost: 50, audioPacketsReceived: 950), nowMs: 1100)
        t.onStatsSample(stats(audioPacketsLost: 60, audioPacketsReceived: 1940), nowMs: 1200) // +10/+990
        t.onStatsSample(stats(audioPacketsLost: 2, audioPacketsReceived: 100), nowMs: 1300) // reset, skipped
        t.onStatsSample(stats(audioPacketsLost: 4, audioPacketsReceived: 300), nowMs: 1400) // +2/+200
        XCTAssertEqual(t.summarize()!.packetLossPct!, 12.0 / 1202.0 * 100.0, accuracy: 0.0001)
    }

    func testNullMosUnlessAllThreeInputsPresent() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onStatsSample(stats(rttMs: 50, audioJitterMs: 5), nowMs: 1100)
        XCTAssertNil(t.summarize()!.mosScore)
        t.onStatsSample(stats(audioPacketsLost: 0, audioPacketsReceived: 1000), nowMs: 1200)
        t.onStatsSample(stats(rttMs: 50, audioJitterMs: 5, audioPacketsLost: 0, audioPacketsReceived: 2000), nowMs: 1300)
        let s = t.summarize()!
        XCTAssertEqual(s.packetLossPct!, 0.0, accuracy: 0.0001)
        XCTAssertEqual(s.mosScore!, 4.39, accuracy: 0.01)
    }

    func testCountsDisconnectsReconnectsDowntimeAndEmitsReconnected() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onConnectionStatusTransition(.recovering, trigger: .networkLost, nowMs: 2000)
        t.onConnectionStatusTransition(.connected, trigger: .networkLost, nowMs: 5000)
        t.onConnectionStatusTransition(.recovering, trigger: .unknown, nowMs: 6000)
        t.onConnectionStatusTransition(.connected, trigger: .unknown, nowMs: 6500)
        let s = t.summarize()!
        XCTAssertEqual(s.countDisconnects, 2)
        XCTAssertEqual(s.countReconnects, 2)
        XCTAssertEqual(s.totalDropoutDurationMs, 3500)
        XCTAssertEqual(events, [
            .reconnected(downtimeMs: 3000, reason: .networkLost),
            .reconnected(downtimeMs: 500, reason: .unknown),
        ])
    }

    func testRecoveringToRetryingIsOneContinuousDropout() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onConnectionStatusTransition(.recovering, trigger: .unknown, nowMs: 2000)
        t.onConnectionStatusTransition(.retrying, trigger: .unknown, nowMs: 12000)
        t.onConnectionStatusTransition(.connected, trigger: .unknown, nowMs: 13000)
        let s = t.summarize()!
        XCTAssertEqual(s.countDisconnects, 1)
        XCTAssertEqual(s.countReconnects, 1)
        XCTAssertEqual(s.totalDropoutDurationMs, 11000)
        XCTAssertEqual(events, [.reconnected(downtimeMs: 11000, reason: .unknown)])
    }

    func testUnrecoveredDropoutAtFinalizeCountsTowardDowntime() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onConnectionStatusTransition(.recovering, trigger: .networkLost, nowMs: 2000)
        t.finalize(nowMs: 7000)
        let s = t.summarize()!
        XCTAssertEqual(s.countDisconnects, 1)
        XCTAssertEqual(s.countReconnects, 0)
        XCTAssertEqual(s.totalDropoutDurationMs, 5000)
        XCTAssertTrue(events.isEmpty)
    }

    func testReconnectFailedOnlyEmitsBeforeFinalize() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.reportReconnectFailed(.timeout)
        XCTAssertEqual(events, [.reconnectFailed(reason: .timeout)])
        t.finalize(nowMs: 2000)
        t.reportReconnectFailed(.networkConnectivity)
        XCTAssertEqual(events.count, 1)
    }

    func testIgnoresInputsAfterFinalize() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onStatsSample(stats(rttMs: 100), nowMs: 1100)
        t.finalize(nowMs: 2000)
        let before = t.summarize()
        t.onStatsSample(stats(rttMs: 5000), nowMs: 3000)
        t.onConnectionStatusTransition(.recovering, trigger: .unknown, nowMs: 3100)
        XCTAssertEqual(t.summarize(), before)
    }

    func testNoPhantomReconnectedOrDisconnectedWhenPeerLeavesMidDropout() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onConnectionStatusTransition(.recovering, trigger: .networkLost, nowMs: 2000)
        // Peer-left: phase leaves inCall, THEN the forced status reset to connected.
        t.onPhaseTransition(.waiting, nowMs: 3000)
        t.onConnectionStatusTransition(.connected, trigger: .unknown, nowMs: 3000)
        let s = t.summarize()!
        // Peer-departure teardown is not a link failure and must not
        // contribute to the disconnect or reconnect counts.
        XCTAssertEqual(s.countDisconnects, 0)
        XCTAssertEqual(s.countReconnects, 0)
        XCTAssertEqual(s.totalDropoutDurationMs, 1000)
        XCTAssertTrue(events.isEmpty)
    }

    func testUnresolvedDropoutStillOpenAtFinalizeCountsAsADisconnect() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onConnectionStatusTransition(.recovering, trigger: .networkLost, nowMs: 2000)
        t.finalize(nowMs: 10_000)
        let s = t.summarize()!
        XCTAssertEqual(s.countDisconnects, 1)
        XCTAssertEqual(s.countReconnects, 0)
        XCTAssertEqual(s.totalDropoutDurationMs, 8_000)
        XCTAssertTrue(events.isEmpty)
    }

    func testSkipsStatsSamplesWhileDropoutOpen() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onStatsSample(stats(rttMs: 40, audioJitterMs: 4), nowMs: 1100)
        t.onConnectionStatusTransition(.recovering, trigger: .networkLost, nowMs: 1500)
        t.onStatsSample(stats(rttMs: 5000, audioJitterMs: 800), nowMs: 1600)
        t.onStatsSample(stats(rttMs: 9000, audioJitterMs: 900), nowMs: 1700)
        t.onConnectionStatusTransition(.connected, trigger: .networkLost, nowMs: 2000)
        t.onStatsSample(stats(rttMs: 60, audioJitterMs: 6), nowMs: 2100)
        let s = t.summarize()!
        XCTAssertEqual(s.medianLatencyMs, 50)
        XCTAssertEqual(s.medianJitterMs, 5)
        XCTAssertEqual(s.qualitySampleCount, 2)
    }

    // Baseline-only loss samples and reset-skipped samples contribute nothing.
    func testCountsOnlySamplesWithARealQualityContribution() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        t.onStatsSample(stats(audioPacketsLost: 100, audioPacketsReceived: 900), nowMs: 1100) // baseline only -> NOT counted
        t.onStatsSample(stats(audioPacketsLost: 105, audioPacketsReceived: 995), nowMs: 1200) // +delta -> counted
        t.onStatsSample(stats(audioPacketsLost: 1, audioPacketsReceived: 10), nowMs: 1300) // reset, no gauges
        XCTAssertEqual(t.summarize()!.qualitySampleCount, 1)
    }

    func testStreamingMedianMatchesSortBasedMedian() {
        let t = makeTracker()
        t.onPhaseTransition(.inCall, nowMs: 1000)
        let values: [Double] = [37, 5, 91, 12, 88, 3, 64, 22, 41, 7, 70, 19]
        for (i, v) in values.enumerated() {
            t.onStatsSample(stats(rttMs: v), nowMs: 1100 + Int64(i))
        }
        let sorted = values.sorted()
        let mid = sorted.count / 2
        let expected = Int(((sorted[mid - 1] + sorted[mid]) / 2.0).rounded())
        XCTAssertEqual(t.summarize()!.medianLatencyMs, expected)
    }
}
