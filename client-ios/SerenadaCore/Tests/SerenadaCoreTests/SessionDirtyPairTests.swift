import XCTest
@testable import SerenadaCore

/// Failure-mode #1 from `docs/resilience-failure-modes.md`: when the server
/// sends `negotiation_dirty{with: cid}` after a previously-suspended peer
/// reattaches, the SDK must schedule glare-safe ICE restart for that peer.
/// `relay_failed` is informational — the SDK should not act on it directly
/// (the same dirty-pair condition will surface as `negotiation_dirty` after
/// the target reattaches).
@MainActor
final class SessionDirtyPairTests: XCTestCase {

    private var harness: SessionTestHarness!

    override func setUp() async throws {
        harness = SessionTestHarness(handlesReconnection: true)
    }

    override func tearDown() async throws {
        harness.tearDown()
        harness = nil
    }

    func testNegotiationDirtySchedulesIceRestartForNamedPeer() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        let slot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(slot)
        let baselineOffers = slot?.createOfferCalls ?? 0

        harness.fakeProvider.simulateNegotiationDirty(withCid: "remote")
        await harness.yieldToMainActor()

        let didFire = (slot?.createOfferCalls ?? 0) > baselineOffers
            || slot?.iceRestartTask != nil
            || slot?.pendingIceRestart == true
        XCTAssertTrue(didFire, "negotiation_dirty should trigger ICE restart for the named peer")
    }

    func testNegotiationDirtyIsNoOpWhenLocalIsNotOfferer() async {
        await harness.advanceToInCallWithTurn(
            localCid: "zeta",
            remoteCid: "alpha",
            localJoinedAt: 2,
            remoteJoinedAt: 1
        )
        let slot = harness.fakeMedia.fakeSlots["alpha"]
        XCTAssertNotNil(slot)
        let baselineOffers = slot?.createOfferCalls ?? 0

        harness.fakeProvider.simulateNegotiationDirty(withCid: "alpha")
        await harness.yieldToMainActor()

        XCTAssertEqual(slot?.createOfferCalls ?? 0, baselineOffers, "Non-offerer must not create recovery offers")
        XCTAssertEqual(slot?.pendingIceRestart, false, "Non-offerer must not wedge on a pending ICE restart it cannot send")
    }

    func testNegotiationDirtyForUnknownPeerIsNoOp() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        let slot = harness.fakeMedia.fakeSlots["remote"]
        let baselineOffers = slot?.createOfferCalls ?? 0
        let baselinePending = slot?.pendingIceRestart ?? false

        harness.fakeProvider.simulateNegotiationDirty(withCid: "stranger")
        await harness.yieldToMainActor()

        XCTAssertEqual(slot?.createOfferCalls ?? 0, baselineOffers)
        XCTAssertEqual(slot?.pendingIceRestart ?? false, baselinePending)
    }

    func testRelayFailedIsInformationalAndDoesNotScheduleIceRestart() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        let slot = harness.fakeMedia.fakeSlots["remote"]
        let baselineOffers = slot?.createOfferCalls ?? 0
        let baselinePending = slot?.pendingIceRestart ?? false

        harness.fakeProvider.simulateRelayFailed(
            reason: "target_suspended",
            targets: ["remote"],
            of: "offer"
        )
        await harness.yieldToMainActor()

        XCTAssertEqual(slot?.createOfferCalls ?? 0, baselineOffers,
                       "relay_failed should be logged only — no immediate ICE restart")
        XCTAssertEqual(slot?.pendingIceRestart ?? false, baselinePending,
                       "relay_failed should not mark the slot pending")
    }
}
