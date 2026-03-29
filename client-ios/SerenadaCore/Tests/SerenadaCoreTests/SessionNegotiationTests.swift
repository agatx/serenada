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

    private func waitUntil(
        attempts: Int = 32,
        condition: () -> Bool
    ) async {
        for _ in 0..<attempts {
            if condition() {
                return
            }
            await harness.yieldToMainActor()
        }
    }

    private func waitForIceRestartTask(
        _ fakeSlot: FakePeerConnectionSlot?,
        attempts: Int = 32
    ) async {
        await waitUntil(attempts: attempts) {
            fakeSlot?.iceRestartTask != nil
        }
    }

    private func waitForNonHostFallbackTask(
        _ fakeSlot: FakePeerConnectionSlot?,
        attempts: Int = 32
    ) async {
        await waitUntil(attempts: attempts) {
            fakeSlot?.nonHostFallbackTask != nil
        }
    }

    // MARK: - Group 1: Offer/Answer Exchange

    func testHostSendsOffer() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot, "Slot should be created for remote peer")
        XCTAssertGreaterThan(fakeSlot?.createOfferCalls ?? 0, 0, "Host should create an offer")

        let offerMessages = harness.fakeProvider.sentPeerMessages(ofType: "offer")
        XCTAssertFalse(offerMessages.isEmpty, "Host should send offer message")
    }

    func testNonHostWaitsThenAnswers() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "zeta",
            remoteCid: "alpha",
            localJoinedAt: 2,
            remoteJoinedAt: 1
        )

        let offerMessages = harness.fakeProvider.sentPeerMessages(ofType: "offer")
        XCTAssertTrue(offerMessages.isEmpty, "Non-host should not send offer proactively")

        // Simulate receiving an offer from the remote (host)
        harness.simulateOfferFromRemote(fromCid: "alpha")
        await harness.yieldToMainActor()

        let fakeSlot = harness.fakeMedia.fakeSlots["alpha"]
        XCTAssertNotNil(fakeSlot)
        XCTAssertEqual(fakeSlot?.setRemoteDescriptionCalls.count, 1, "Should set remote description for offer")
        XCTAssertEqual(fakeSlot?.setRemoteDescriptionCalls.first?.type, .offer)
        XCTAssertGreaterThan(fakeSlot?.createAnswerCalls ?? 0, 0, "Should create answer")

        let answerMessages = harness.fakeProvider.sentPeerMessages(ofType: "answer")
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
        await waitUntil {
            fakeSlot?.addedIceCandidates.count == 1
        }

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
        let answersBefore = harness.fakeProvider.sentPeerMessages(ofType: "answer").count

        // If ICE servers are not ready, the offer should be buffered
        // If they are ready (default STUN), it should be processed immediately
        harness.simulateOfferFromRemote(fromCid: "remote")
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()

        // Either way, after yielding, the answer should eventually be sent
        let answersAfter = harness.fakeProvider.sentPeerMessages(ofType: "answer").count
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
        await waitForIceRestartTask(fakeSlot)

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
        await waitForIceRestartTask(fakeSlot)
        XCTAssertNotNil(fakeSlot?.iceRestartTask, "ICE restart should be scheduled")

        // Simulate CONNECTED → should clear the restart task
        fakeSlot?.simulateConnectionStateChange(.connected)
        for _ in 0..<8 {
            if fakeSlot?.iceRestartTask == nil {
                break
            }
            await harness.yieldToMainActor()
        }

        XCTAssertNil(fakeSlot?.iceRestartTask, "ICE restart task should be cleared on CONNECTED")
        XCTAssertEqual(fakeSlot?.pendingIceRestart, false, "pendingIceRestart should be cleared on CONNECTED")
    }

    // MARK: - Group 6: shouldIOffer Logic

    func testLexicographicallyLowerPeerIdOffers() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "zeta",
            localJoinedAt: 10,
            remoteJoinedAt: 1
        )

        let offerMessages = harness.fakeProvider.sentPeerMessages(ofType: "offer")
        XCTAssertFalse(offerMessages.isEmpty, "Lower peer ID should send offer")
    }

    func testLexicographicallyHigherPeerIdWaits() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "zeta",
            remoteCid: "alpha",
            localJoinedAt: 1,
            remoteJoinedAt: 10
        )

        let offerMessages = harness.fakeProvider.sentPeerMessages(ofType: "offer")
        XCTAssertTrue(offerMessages.isEmpty, "Higher peer ID should not send offer")
    }

    // MARK: - Group 7: Non-Host Fallback Recovery

    func testNonHostFallbackOfferRetriesAfterOfferTimeout() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "zeta",
            remoteCid: "alpha",
            localJoinedAt: 2,
            remoteJoinedAt: 1
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["alpha"]
        XCTAssertNotNil(fakeSlot)

        await waitForNonHostFallbackTask(fakeSlot)
        XCTAssertNotNil(fakeSlot?.nonHostFallbackTask, "Fallback timer should be scheduled for the non-host peer")
        XCTAssertTrue(harness.fakeProvider.sentPeerMessages(ofType: "offer").isEmpty)

        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.nonHostFallbackDelayMs) + 1)
        await waitUntil {
            (fakeSlot?.createOfferCalls ?? 0) >= 1
        }

        XCTAssertEqual(fakeSlot?.createOfferCalls, 1, "First fallback offer should be created after the delay")
        XCTAssertEqual(fakeSlot?.nonHostFallbackAttempts, 1)
        XCTAssertEqual(harness.fakeProvider.sentPeerMessages(ofType: "offer").count, 1)
        XCTAssertNotNil(fakeSlot?.offerTimeoutTask, "Fallback offer should arm the offer-timeout watchdog")
        await harness.yieldToMainActor()

        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.offerTimeoutMs) + 1)
        await waitUntil {
            (fakeSlot?.rollbackCalls ?? 0) >= 1 && fakeSlot?.nonHostFallbackTask != nil
        }

        XCTAssertEqual(fakeSlot?.rollbackCalls, 1, "Timed out fallback offers should roll back before retrying")
        XCTAssertNotNil(fakeSlot?.nonHostFallbackTask, "Offer timeout should schedule the next fallback attempt")
        await harness.yieldToMainActor()

        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.nonHostFallbackDelayMs) + 1)
        await waitUntil {
            (fakeSlot?.createOfferCalls ?? 0) >= 2
        }

        XCTAssertEqual(fakeSlot?.createOfferCalls, 2, "Fallback should retry after the timeout path")
        XCTAssertEqual(fakeSlot?.nonHostFallbackAttempts, 2)
        XCTAssertEqual(harness.fakeProvider.sentPeerMessages(ofType: "offer").count, 2)
    }

    // MARK: - Group 8: Signaling Reconnect

    func testSignalingReconnectDuringInCallTriggersIceRestart() async throws {
        harness.tearDown()
        harness = SessionTestHarness(handlesReconnection: true)
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
        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()

        harness.fakeProvider.simulateConnected()
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
            hostCid: "local"
        )
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()

        XCTAssertTrue(harness.fakeMedia.createdSlotCids.contains("remote-a"))
        XCTAssertTrue(harness.fakeMedia.createdSlotCids.contains("remote-b"))
    }
}
