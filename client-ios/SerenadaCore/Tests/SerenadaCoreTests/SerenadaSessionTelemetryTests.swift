@testable import SerenadaCore
import XCTest

/// Exercises the `CallQualityTracker` feed from phase and connection-status
/// transitions, `ConnectionEvent` dispatch, and finalize-before-teardown
/// ordering through `SerenadaSession`.
@MainActor
final class SerenadaSessionTelemetryTests: XCTestCase {

    /// Recording delegate that captures connection events + terminal callbacks.
    final class RecordingDelegate: SerenadaCoreDelegate {
        var connectionEvents: [ConnectionEvent] = []
        var endReasons: [EndReason] = []
        func sessionDidEmitConnectionEvent(_ session: SerenadaSession, event: ConnectionEvent) {
            connectionEvents.append(event)
        }
        func sessionDidEnd(_ session: SerenadaSession, reason: EndReason) {
            endReasons.append(reason)
        }
    }

    func testDropoutThenRecoveryEmitsReconnectedAndCounts() async {
        let delegate = RecordingDelegate()
        let harness = SessionTestHarness(delegate: delegate)
        await harness.advanceToInCallWithTurn(localCid: "alpha", remoteCid: "remote")
        XCTAssertEqual(harness.session.state.phase, .inCall)

        // Signaling drops while in-call -> dropout opens (status .recovering).
        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.connectionStatus, .recovering)

        // Reconnect -> recovery closes the dropout, emits reconnected.
        harness.fakeProvider.simulateConnected()
        await harness.yieldToMainActor()

        let reconnects = delegate.connectionEvents.filter {
            if case .reconnected = $0 { return true }
            return false
        }
        XCTAssertEqual(reconnects.count, 1)
        let summary = harness.session.qualitySummary
        XCTAssertNotNil(summary)
        XCTAssertEqual(summary?.countDisconnects, 1)
        XCTAssertEqual(summary?.countReconnects, 1)
    }

    func testPeerLeavingMidDropoutDoesNotEmitPhantomReconnected() async {
        let delegate = RecordingDelegate()
        let harness = SessionTestHarness(delegate: delegate)
        await harness.advanceToInCallWithTurn(localCid: "alpha", remoteCid: "remote")
        XCTAssertEqual(harness.session.state.phase, .inCall)

        // A dropout opens and never recovers; the peer departs while it is open.
        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.connectionStatus, .recovering)

        harness.fakeProvider.simulatePeerLeft(peerId: "remote")
        await harness.yieldToMainActor()

        // Phase left inCall -> the forced status reset is a peer-departure, not
        // a recovery. No phantom reconnected.
        let reconnects = delegate.connectionEvents.filter {
            if case .reconnected = $0 { return true }
            return false
        }
        XCTAssertTrue(reconnects.isEmpty)
        let summary = harness.session.qualitySummary
        // Peer-departure teardown is not a link failure.
        XCTAssertEqual(summary?.countDisconnects, 0)
        XCTAssertEqual(summary?.countReconnects, 0)
    }

    func testSummaryIsFinalizedAndReadableAfterTeardown() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "alpha", remoteCid: "remote")
        await harness.fakeClock.advance(byMs: 1000)
        harness.session.leave()
        await harness.yieldToMainActor()
        // Finalized snapshot survives teardown.
        XCTAssertNotNil(harness.session.qualitySummary)
    }
}
