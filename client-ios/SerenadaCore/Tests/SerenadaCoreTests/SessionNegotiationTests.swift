import XCTest
@testable import SerenadaCore

@MainActor
final class SessionNegotiationTests: XCTestCase {

    private var harness: SessionTestHarness!

    override func setUp() async throws {
        harness = SessionTestHarness()
    }

    override func tearDown() async throws {
        harness.tearDown()
        harness = nil
    }

    // MARK: - Group 1: Offer/Answer Exchange

    func testHostSendsOffer() async throws {
        // Local joinedAt=1 < remote joinedAt=2 → local is host and should offer
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot, "Slot should be created for remote peer")
        XCTAssertGreaterThan(fakeSlot?.createOfferCalls ?? 0, 0, "Host should create an offer")

        let offerMessages = harness.fakeSignaling.sentMessages(ofType: "offer")
        XCTAssertFalse(offerMessages.isEmpty, "Host should send offer message")
    }

    func testNonHostWaitsThenAnswers() async throws {
        // Local joinedAt=2 > remote joinedAt=1 → local is non-host, should NOT offer
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 2,
            remoteJoinedAt: 1
        )

        let offerMessages = harness.fakeSignaling.sentMessages(ofType: "offer")
        XCTAssertTrue(offerMessages.isEmpty, "Non-host should not send offer proactively")

        // Simulate receiving an offer from the remote (host)
        harness.simulateOfferFromRemote(fromCid: "remote")
        await harness.yieldToMainActor()

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot)
        XCTAssertEqual(fakeSlot?.setRemoteDescriptionCalls.count, 1, "Should set remote description for offer")
        XCTAssertEqual(fakeSlot?.setRemoteDescriptionCalls.first?.type, .offer)
        XCTAssertGreaterThan(fakeSlot?.createAnswerCalls ?? 0, 0, "Should create answer")

        let answerMessages = harness.fakeSignaling.sentMessages(ofType: "answer")
        XCTAssertFalse(answerMessages.isEmpty, "Should send answer message")
    }

    func testAnswerClearsPendingState() async throws {
        // Host sends offer, then receives answer
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot)

        // Verify offer was sent
        XCTAssertGreaterThan(fakeSlot?.createOfferCalls ?? 0, 0)

        // Simulate answer from remote
        harness.simulateAnswerFromRemote(fromCid: "remote")
        await harness.yieldToMainActor()

        XCTAssertEqual(fakeSlot?.setRemoteDescriptionCalls.last?.type, .answer, "Should set answer as remote desc")
        XCTAssertEqual(fakeSlot?.pendingIceRestart, false, "pendingIceRestart should be cleared after answer")
    }

    // MARK: - Group 2: ICE Candidate Relay

    func testRemoteIceCandidateAddedToSlot() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot)

        harness.simulateIceCandidateFromRemote(fromCid: "remote", candidate: "candidate:test")
        await harness.yieldToMainActor()

        XCTAssertEqual(fakeSlot?.addedIceCandidates.count, 1, "ICE candidate should be added to slot")
        XCTAssertEqual(fakeSlot?.addedIceCandidates.first?.candidate, "candidate:test")
    }

    // MARK: - Group 3: Peer Departure

    func testPeerLeavesViaRoomState() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        XCTAssertEqual(harness.session.state.phase, .inCall)

        // Remote leaves — room_state with only local
        harness.simulateRoomState(
            participants: [(cid: "local", joinedAt: 1)],
            hostCid: "local"
        )
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .waiting, "Should transition to waiting when peer leaves")
        XCTAssertFalse(harness.fakeMedia.removedSlots.isEmpty, "Slot should be removed for departed peer")
    }

    // MARK: - Group 4: Pending Message Buffering

    func testOffersBufferBeforeIceServers() async throws {
        // Join without TURN token → no ICE servers yet
        await harness.advancePastPermissions()
        harness.openSignaling()
        harness.simulateJoinedResponse(
            cid: "local",
            participants: [
                (cid: "local", joinedAt: 2),
                (cid: "remote", joinedAt: 1)
            ],
            hostCid: "remote"
            // No turnToken
        )
        await harness.yieldToMainActor()

        // At this point ICE servers may or may not be set (default STUN gets applied).
        // Simulate an offer from remote before TURN completes
        let answersBefore = harness.fakeSignaling.sentMessages(ofType: "answer").count

        // If ICE servers are not ready, the offer should be buffered
        // If they are ready (default STUN), it should be processed immediately
        harness.simulateOfferFromRemote(fromCid: "remote")
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()

        // Either way, after yielding, the answer should eventually be sent
        let answersAfter = harness.fakeSignaling.sentMessages(ofType: "answer").count
        XCTAssertGreaterThan(answersAfter, answersBefore, "Answer should be sent after offer processing")
    }

    // MARK: - Group 5: ICE Restart Triggers

    func testDisconnectedSchedulesIceRestart() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot)

        // Simulate connection DISCONNECTED
        fakeSlot?.simulateConnectionStateChange(.disconnected)
        await harness.yieldToMainActor()

        // ICE restart should be scheduled (task set)
        XCTAssertNotNil(fakeSlot?.iceRestartTask, "ICE restart task should be scheduled on DISCONNECTED")
    }

    func testFailedTriggersIceRestart() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot)
        let offersBefore = fakeSlot?.createOfferCalls ?? 0

        // Simulate connection FAILED (delay=0 → immediate)
        fakeSlot?.simulateConnectionStateChange(.failed)
        await harness.yieldToMainActor()
        // Advance clock to let any delayed tasks fire, plus yields for MainActor scheduling
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()

        // Either iceRestartTask is set, a new offer was already made, or pendingIceRestart was set
        let offersAfter = fakeSlot?.createOfferCalls ?? 0
        let hasTask = fakeSlot?.iceRestartTask != nil
        let hasPending = fakeSlot?.pendingIceRestart == true
        XCTAssertTrue(offersAfter > offersBefore || hasTask || hasPending, "FAILED should trigger ICE restart")
    }

    func testConnectedClearsIceRestart() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot)

        // Schedule an ICE restart
        fakeSlot?.simulateConnectionStateChange(.disconnected)
        await harness.yieldToMainActor()
        XCTAssertNotNil(fakeSlot?.iceRestartTask, "ICE restart should be scheduled")

        // Simulate CONNECTED → should clear the restart task
        fakeSlot?.simulateConnectionStateChange(.connected)
        await harness.yieldToMainActor()

        XCTAssertNil(fakeSlot?.iceRestartTask, "ICE restart task should be cleared on CONNECTED")
        XCTAssertEqual(fakeSlot?.pendingIceRestart, false, "pendingIceRestart should be cleared on CONNECTED")
    }

    // MARK: - Group 6: shouldIOffer Logic

    func testLowerJoinedAtOffers() async throws {
        // local joinedAt=1 < remote joinedAt=2 → local offers
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let offerMessages = harness.fakeSignaling.sentMessages(ofType: "offer")
        XCTAssertFalse(offerMessages.isEmpty, "Lower joinedAt should send offer")
    }

    func testHigherJoinedAtWaits() async throws {
        // local joinedAt=2 > remote joinedAt=1 → local does NOT offer
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 2,
            remoteJoinedAt: 1
        )

        let offerMessages = harness.fakeSignaling.sentMessages(ofType: "offer")
        XCTAssertTrue(offerMessages.isEmpty, "Higher joinedAt should not send offer")
    }

    // MARK: - Group 8: Signaling Reconnect

    func testSignalingReconnectDuringInCallTriggersIceRestart() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot)
        let offersBefore = fakeSlot?.createOfferCalls ?? 0

        // Simulate signaling disconnect + reconnect
        harness.fakeSignaling.simulateClosed(reason: "test")
        await harness.yieldToMainActor()

        harness.openSignaling()
        await harness.yieldToMainActor()
        // Give ICE restart task time to fire
        await harness.fakeClock.advance(byMs: 5000)
        await harness.yieldToMainActor()

        let offersAfter = fakeSlot?.createOfferCalls ?? 0
        let hasTask = fakeSlot?.iceRestartTask != nil
        let hasPending = fakeSlot?.pendingIceRestart == true
        XCTAssertTrue(offersAfter > offersBefore || hasTask || hasPending,
                       "Signaling reconnect during inCall should trigger ICE restart")
    }

    // MARK: - Additional: Slot Creation

    func testSlotCreatedForRemoteParticipant() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        XCTAssertTrue(harness.fakeMedia.createdSlotCids.contains("remote"), "Slot created for remote")
        XCTAssertNotNil(harness.fakeMedia.fakeSlots["remote"], "FakeSlot should be accessible")
    }

    func testMultipleRemotePeersGetSlots() async throws {
        await harness.advancePastPermissions()
        harness.openSignaling()
        harness.simulateJoinedResponse(
            cid: "local",
            participants: [
                (cid: "local", joinedAt: 1),
                (cid: "remote-a", joinedAt: 2),
                (cid: "remote-b", joinedAt: 3)
            ],
            hostCid: "local",
            turnToken: "test-turn-token"
        )
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()

        XCTAssertTrue(harness.fakeMedia.createdSlotCids.contains("remote-a"))
        XCTAssertTrue(harness.fakeMedia.createdSlotCids.contains("remote-b"))
    }
}
