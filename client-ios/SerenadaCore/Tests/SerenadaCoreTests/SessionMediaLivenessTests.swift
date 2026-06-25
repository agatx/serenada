import XCTest
@testable import SerenadaCore

/// Failure mode #3 — periodic `media_liveness{cids}` emission so the server
/// can defer hard-eviction of suspended peers whose media is still flowing
/// locally.
@MainActor
final class SessionMediaLivenessTests: XCTestCase {

    private var harness: SessionTestHarness!

    override func setUp() async throws {
        harness = SessionTestHarness(handlesReconnection: true)
    }

    override func tearDown() async throws {
        harness.tearDown()
        harness = nil
    }

    private func livenessBroadcasts() -> [(type: String, payload: SignalingPayload?)] {
        harness.fakeProvider.broadcasts.filter { $0.type == "media_liveness" }
    }

    private func tickInterval() async {
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.mediaLivenessIntervalMs) + 50)
        // Allow async slot.collectInboundBytes callbacks to land back on MainActor.
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
    }

    func testBroadcastsMediaLivenessWhenInboundBytesAdvance() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        // Yield enough that startMediaLivenessTimer reaches its first sleep.
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        // Baseline tick: bytes still 0 -> empty heartbeat.
        let slot = harness.fakeMedia.fakeSlots["remote"]
        slot?.inboundBytesSample = 0
        await tickInterval()
        let baseline = livenessBroadcasts().count

        // Bytes advance → flow detected → broadcast.
        slot?.inboundBytesSample = 5_000
        await tickInterval()

        let broadcasts = livenessBroadcasts()
        XCTAssertEqual(broadcasts.count, baseline + 1)
        if case let .array(items) = broadcasts.last?.payload?["cids"] {
            XCTAssertEqual(items.count, 1)
            if case let .string(cid) = items.first {
                XCTAssertEqual(cid, "remote")
            } else {
                XCTFail("Expected first cid to be a string")
            }
        } else {
            XCTFail("Expected `cids` array in payload")
        }
    }

    func testBroadcastsEmptyHeartbeatWhenNoPeerIsCurrentlyFlowing() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        harness.fakeMedia.fakeSlots["remote"]?.inboundBytesSample = 0
        await tickInterval()
        await tickInterval()
        await tickInterval()

        let broadcasts = livenessBroadcasts()
        XCTAssertFalse(broadcasts.isEmpty)
        if case let .array(items) = broadcasts.last?.payload?["cids"] {
            XCTAssertEqual(items.count, 0)
        } else {
            XCTFail("Expected `cids` array in payload")
        }
        let slot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertGreaterThan(slot?.collectInboundLivenessCalls ?? 0, 0)
        XCTAssertEqual(slot?.collectInboundBytesCalls ?? 0, 0, "timer should not run a second bytes-only stats pass")
        XCTAssertEqual(slot?.collectInboundRoleBytesCalls ?? 0, 0, "timer should not run a second role-only stats pass")
    }

    func testPausesWhileTransportDisconnectedAndResumesAfterReconnect() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        // Establish a baseline so the first broadcast can fire.
        let slot = harness.fakeMedia.fakeSlots["remote"]
        slot?.inboundBytesSample = 1_000
        await tickInterval()
        slot?.inboundBytesSample = 5_000
        await tickInterval()
        let beforeDisconnect = livenessBroadcasts().count
        XCTAssertGreaterThanOrEqual(beforeDisconnect, 1)

        // Drop transport — subsequent ticks must not broadcast.
        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()
        slot?.inboundBytesSample = 10_000
        await tickInterval()
        await tickInterval()
        XCTAssertEqual(livenessBroadcasts().count, beforeDisconnect)

        // Reconnect — next tick should broadcast again.
        harness.fakeProvider.simulateConnected()
        await harness.yieldToMainActor()
        slot?.inboundBytesSample = 20_000
        await tickInterval()
        XCTAssertGreaterThan(livenessBroadcasts().count, beforeDisconnect)
    }
}
