import XCTest
@testable import SerenadaCore

/// Failure-mode #4 from `docs/resilience-failure-modes.md`: SDK must defer ICE
/// restart on signaling reconnect until the authoritative post-reconnect
/// `room_state` snapshot lands. On a 5s timeout, the SDK falls back to firing
/// against the last-known peer map (graceful degradation to pre-#4 behavior).
@MainActor
final class SessionPostReconnectGateTests: XCTestCase {

    private var harness: SessionTestHarness!

    override func setUp() async throws {
        harness = SessionTestHarness(handlesReconnection: true)
    }

    override func tearDown() async throws {
        harness.tearDown()
        harness = nil
    }

    func testReconnectArmsGateButDoesNotFireImmediately() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        let baselineFires = harness.session.postReconnectResyncFireCount

        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()
        harness.fakeProvider.simulateConnected()
        await harness.yieldToMainActor()

        XCTAssertTrue(
            harness.session.isPostReconnectResyncPending,
            "Gate should be armed after reconnect"
        )
        XCTAssertEqual(
            harness.session.postReconnectResyncFireCount,
            baselineFires,
            "ICE restart should not fire before snapshot"
        )
    }

    func testPostReconnectRoomStateSnapshotFlushesGate() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        let baselineFires = harness.session.postReconnectResyncFireCount

        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()
        harness.fakeProvider.simulateConnected()
        await harness.yieldToMainActor()

        harness.simulateRoomState(
            participants: [(cid: "alpha", joinedAt: 1), (cid: "remote", joinedAt: 2)],
            hostCid: "alpha"
        )
        await harness.yieldToMainActor()

        XCTAssertFalse(
            harness.session.isPostReconnectResyncPending,
            "Gate should clear after snapshot"
        )
        XCTAssertEqual(
            harness.session.postReconnectResyncFireCount,
            baselineFires + 1,
            "Snapshot should fire exactly one ICE restart"
        )
    }

    func testGateFallsBackOnEpochResyncTimeout() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        let baselineFires = harness.session.postReconnectResyncFireCount

        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()
        harness.fakeProvider.simulateConnected()
        // Yield enough to let the Task body register its sleep on FakeSessionClock.
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        XCTAssertTrue(harness.session.isPostReconnectResyncPending)
        XCTAssertEqual(harness.session.postReconnectResyncFireCount, baselineFires)

        // Advance past the 5s resync timeout.
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.epochResyncTimeoutMs) + 1)
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        XCTAssertFalse(
            harness.session.isPostReconnectResyncPending,
            "Gate should clear after timeout"
        )
        XCTAssertEqual(
            harness.session.postReconnectResyncFireCount,
            baselineFires + 1,
            "Timeout should fire ICE restart fallback"
        )
    }

    func testSubsequentRoomStateUpdatesDoNotDoubleFire() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        let baselineFires = harness.session.postReconnectResyncFireCount

        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()
        harness.fakeProvider.simulateConnected()
        await harness.yieldToMainActor()

        harness.simulateRoomState(
            participants: [(cid: "alpha", joinedAt: 1), (cid: "remote", joinedAt: 2)],
            hostCid: "alpha"
        )
        await harness.yieldToMainActor()
        let afterFirst = harness.session.postReconnectResyncFireCount

        // A later room_state (e.g. peer mute) should not retrigger.
        harness.simulateRoomState(
            participants: [(cid: "alpha", joinedAt: 1), (cid: "remote", joinedAt: 2)],
            hostCid: "alpha"
        )
        await harness.yieldToMainActor()

        XCTAssertEqual(
            harness.session.postReconnectResyncFireCount,
            afterFirst,
            "Only the first post-reconnect snapshot fires the gated restart"
        )
        XCTAssertEqual(harness.session.postReconnectResyncFireCount, baselineFires + 1)
    }
}
