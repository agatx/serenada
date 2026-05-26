import XCTest
@testable import SerenadaCore

@MainActor
final class SessionNegotiationTests: XCTestCase {

    private var harness: SessionTestHarness!

    private struct SharedNegotiationFixture: Decodable {
        let scenarios: [SharedNegotiationScenario]
    }

    private struct SharedNegotiationScenario: Decodable {
        let id: String
        let localCid: String
        let remoteCid: String
    }

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

    private func resetHarness() {
        harness.tearDown()
        harness = SessionTestHarness()
    }

    private func sharedNegotiationScenarios() throws -> [SharedNegotiationScenario] {
        let repoRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let candidates = [
            repoRoot.appendingPathComponent("test-fixtures/peer-negotiation-scenarios.json").path,
            "test-fixtures/peer-negotiation-scenarios.json",
            "../test-fixtures/peer-negotiation-scenarios.json",
            "../../test-fixtures/peer-negotiation-scenarios.json",
            "../../../test-fixtures/peer-negotiation-scenarios.json"
        ]
        let currentDirectory = FileManager.default.currentDirectoryPath
        let candidatePaths = candidates.map { candidate in
            URL(fileURLWithPath: candidate, relativeTo: URL(fileURLWithPath: currentDirectory + "/")).path
        }
        guard let path = candidatePaths.first(where: { FileManager.default.fileExists(atPath: $0) }) else {
            XCTFail("Missing shared peer negotiation scenarios")
            return []
        }
        let data = try Data(contentsOf: URL(fileURLWithPath: path))
        return try JSONDecoder().decode(SharedNegotiationFixture.self, from: data).scenarios
    }

    private func latestOfferId() throws -> String {
        let offerId = harness.fakeProvider.sentPeerMessages(ofType: "offer").last?.payload?["offerId"]?.stringValue
        return try XCTUnwrap(offerId)
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

        let fakeSlot = harness.fakeMedia.fakeSlots["alpha"]
        await waitUntil {
            (fakeSlot?.createAnswerCalls ?? 0) > 0
        }
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

    func testRemoteIceCandidateWithoutSdpMidIsForwardedWithNilMid() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot)

        harness.simulateIceCandidateFromRemote(
            fromCid: "remote",
            candidate: "candidate:test-ice",
            sdpMid: nil,
            sdpMLineIndex: 1
        )
        await waitUntil {
            fakeSlot?.addedIceCandidates.count == 1
        }

        XCTAssertNil(fakeSlot?.addedIceCandidates.first?.sdpMid,
                     "Missing sdpMid should be preserved as nil so WebRTC uses sdpMLineIndex")
        XCTAssertEqual(fakeSlot?.addedIceCandidates.first?.sdpMLineIndex, 1)
    }

    func testRemoteIceCandidateWithBlankSdpIsDropped() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(fakeSlot)

        harness.simulateIceCandidateFromRemote(fromCid: "remote", candidate: "")
        harness.simulateIceCandidateFromRemote(fromCid: "remote", candidate: "   ")
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        XCTAssertEqual(fakeSlot?.addedIceCandidates.count, 0,
                       "Blank ICE candidates must not reach the slot")
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
            cid: "zeta",
            participants: [
                (cid: "zeta", joinedAt: 2),
                (cid: "alpha", joinedAt: 1)
            ],
            hostCid: "alpha"
            // No turnToken
        )
        await harness.yieldToMainActor()

        // At this point ICE servers may or may not be set (default STUN gets applied).
        // Simulate an offer from remote before TURN completes
        let answersBefore = harness.fakeProvider.sentPeerMessages(ofType: "answer").count

        // If ICE servers are not ready, the offer should be buffered
        // If they are ready (default STUN), it should be processed immediately
        harness.simulateOfferFromRemote(fromCid: "alpha")
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await waitUntil {
            harness.fakeProvider.sentPeerMessages(ofType: "answer").count > answersBefore
        }

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

    // MARK: - Group 7: Deterministic Offer Ownership

    func testNonOffererDoesNotSendFallbackOfferAfterDelay() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "zeta",
            remoteCid: "alpha",
            localJoinedAt: 2,
            remoteJoinedAt: 1
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["alpha"]
        XCTAssertNotNil(fakeSlot)
        let baselineOffers = fakeSlot?.createOfferCalls ?? 0

        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.offerTimeoutMs) + 1)
        await harness.yieldToMainActor()

        XCTAssertEqual(fakeSlot?.createOfferCalls, baselineOffers, "Non-offerer must not create fallback offers")
        XCTAssertTrue(harness.fakeProvider.sentPeerMessages(ofType: "offer").isEmpty)
    }

    func testDesignatedOffererRestartsWhenPeerReattaches() async throws {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "zeta",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        let slot = try XCTUnwrap(harness.fakeMedia.fakeSlots["zeta"])
        let offerId = try XCTUnwrap(harness.fakeProvider.sentPeerMessages(ofType: "offer").last?.payload?["offerId"]?.stringValue)
        harness.simulateAnswerFromRemote(fromCid: "zeta", offerId: offerId)
        await harness.yieldToMainActor()
        let offersBefore = slot.createOfferCalls

        harness.simulateRoomStateWith(
            participants: [
                SignalingProviderParticipant(peerId: "alpha", joinedAt: 1),
                SignalingProviderParticipant(peerId: "zeta", joinedAt: 2, signalingStatus: .suspended),
            ],
            hostCid: "alpha"
        )
        await harness.yieldToMainActor()

        harness.simulateRoomStateWith(
            participants: [
                SignalingProviderParticipant(peerId: "alpha", joinedAt: 1),
                SignalingProviderParticipant(peerId: "zeta", joinedAt: 2, signalingStatus: .active),
            ],
            hostCid: "alpha"
        )
        await waitUntil {
            slot.createOfferCalls > offersBefore
        }

        XCTAssertGreaterThan(slot.createOfferCalls, offersBefore, "Designated offerer should restart after peer reattaches")
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

        // Per #4, ICE restart now waits for the authoritative post-reconnect
        // `room_state` snapshot. Provide it so the gate flushes.
        harness.simulateRoomState(
            participants: [(cid: "local", joinedAt: 1), (cid: "remote", joinedAt: 2)],
            hostCid: "local"
        )
        await harness.yieldToMainActor()

        let offersAfter = fakeSlot?.createOfferCalls ?? 0
        let hasTask = fakeSlot?.iceRestartTask != nil
        let hasPending = fakeSlot?.pendingIceRestart == true
        XCTAssertTrue(offersAfter > offersBefore || hasTask || hasPending,
                       "Signaling reconnect (after post-reconnect snapshot) should trigger ICE restart")
    }

    func testIceRestartRollsBackStaleLocalOfferBeforeRetrying() async throws {
        harness.tearDown()
        harness = SessionTestHarness(handlesReconnection: true)
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "zeta",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        let fakeSlot = harness.fakeMedia.fakeSlots["zeta"]
        XCTAssertEqual(fakeSlot?.getSignalingState(), "HAVE_LOCAL_OFFER")
        let offersBefore = fakeSlot?.createOfferCalls ?? 0

        // Simulate a watchdog that was lost while the app/signaling transport
        // was suspended. A dirty-pair restart must still recover the slot.
        fakeSlot?.cancelOfferTimeout()
        harness.fakeProvider.simulateNegotiationDirty(withCid: "zeta")
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 1)
        await waitUntil {
            (fakeSlot?.rollbackCalls ?? 0) >= 1 && (fakeSlot?.createOfferCalls ?? 0) > offersBefore
        }

        XCTAssertEqual(fakeSlot?.rollbackCalls, 1, "Stale local offers should be rolled back before retrying ICE restart")
        XCTAssertGreaterThan(fakeSlot?.createOfferCalls ?? 0, offersBefore, "ICE restart should retry from STABLE")
        XCTAssertEqual(fakeSlot?.getSignalingState(), "HAVE_LOCAL_OFFER", "Retry should leave a fresh local offer waiting for answer")
    }

    func testSharedPerfectNegotiationScenarios() async throws {
        var handled = Set<String>()
        let scenarios = try sharedNegotiationScenarios()

        for scenario in scenarios {
            resetHarness()
            handled.insert(scenario.id)

            switch scenario.id {
            case "impolite-offer-collision-ignores-offer-and-ice":
                await harness.advanceToInCallWithTurn(
                    localCid: scenario.localCid,
                    remoteCid: scenario.remoteCid,
                    localJoinedAt: 1,
                    remoteJoinedAt: 2
                )
                let slot = try XCTUnwrap(harness.fakeMedia.fakeSlots[scenario.remoteCid])
                XCTAssertEqual(slot.getSignalingState(), "HAVE_LOCAL_OFFER")

                harness.simulateOfferFromRemote(fromCid: scenario.remoteCid, sdp: "colliding-offer", offerId: "remote-offer-1")
                harness.simulateIceCandidateFromRemote(fromCid: scenario.remoteCid, candidate: "candidate:ignored", offerId: "remote-offer-1")
                await harness.yieldToMainActor()

                XCTAssertFalse(slot.setRemoteDescriptionCalls.contains { $0.type == .offer }, "Impolite peer must not apply a colliding offer")
                XCTAssertTrue(slot.addedIceCandidates.isEmpty, "ICE for the ignored offer must be dropped")
                XCTAssertTrue(harness.fakeProvider.sentPeerMessages(ofType: "answer").isEmpty, "Ignored offer must not be answered")

            case "polite-offer-collision-rolls-back-and-answers":
                await harness.advanceToInCallWithTurn(
                    localCid: scenario.localCid,
                    remoteCid: scenario.remoteCid,
                    localJoinedAt: 2,
                    remoteJoinedAt: 1
                )
                let slot = try XCTUnwrap(harness.fakeMedia.fakeSlots[scenario.remoteCid])
                _ = slot.createOffer(iceRestart: false, onSdp: { _ in }, onComplete: nil)
                XCTAssertEqual(slot.getSignalingState(), "HAVE_LOCAL_OFFER")

                harness.simulateOfferFromRemote(fromCid: scenario.remoteCid, sdp: "remote-offer", offerId: "remote-offer-1")
                await waitUntil { slot.rollbackCalls == 1 && slot.createAnswerCalls > 0 }

                XCTAssertEqual(slot.rollbackCalls, 1, "Polite peer must roll back its local offer")
                XCTAssertEqual(slot.setRemoteDescriptionCalls.last?.type, .offer)
                XCTAssertTrue(harness.fakeProvider.sentPeerMessages(ofType: "answer").contains {
                    $0.payload?["offerId"]?.stringValue == "remote-offer-1"
                }, "Polite peer must answer the accepted remote offer")

            case "stale-answer-in-stable-is-dropped":
                await harness.advanceToInCallWithTurn(
                    localCid: scenario.localCid,
                    remoteCid: scenario.remoteCid,
                    localJoinedAt: 1,
                    remoteJoinedAt: 2
                )
                let slot = try XCTUnwrap(harness.fakeMedia.fakeSlots[scenario.remoteCid])
                let offerId = try latestOfferId()
                harness.simulateAnswerFromRemote(fromCid: scenario.remoteCid, offerId: offerId)
                await waitUntil { slot.getSignalingState() == "STABLE" }
                let answerApplies = slot.setRemoteDescriptionCalls.filter { $0.type == .answer }.count

                harness.simulateAnswerFromRemote(fromCid: scenario.remoteCid, sdp: "late-answer", offerId: offerId)
                await harness.yieldToMainActor()

                XCTAssertEqual(slot.setRemoteDescriptionCalls.filter { $0.type == .answer }.count, answerApplies, "Stale answer in STABLE must be dropped")

            case "stale-answer-wrong-offer-id-is-dropped":
                await harness.advanceToInCallWithTurn(
                    localCid: scenario.localCid,
                    remoteCid: scenario.remoteCid,
                    localJoinedAt: 1,
                    remoteJoinedAt: 2
                )
                let slot = try XCTUnwrap(harness.fakeMedia.fakeSlots[scenario.remoteCid])

                harness.simulateAnswerFromRemote(fromCid: scenario.remoteCid, sdp: "wrong-answer", offerId: "wrong-offer-id")
                await harness.yieldToMainActor()

                XCTAssertFalse(slot.setRemoteDescriptionCalls.contains { $0.type == .answer }, "Wrong-offer answer must not reach the peer connection")
                XCTAssertEqual(slot.getSignalingState(), "HAVE_LOCAL_OFFER")

            case "early-ice-for-eventual-offer-is-buffered-and-flushed":
                await harness.advanceToInCallWithTurn(
                    localCid: scenario.localCid,
                    remoteCid: scenario.remoteCid,
                    localJoinedAt: 2,
                    remoteJoinedAt: 1
                )
                let slot = try XCTUnwrap(harness.fakeMedia.fakeSlots[scenario.remoteCid])

                harness.simulateIceCandidateFromRemote(fromCid: scenario.remoteCid, candidate: "candidate:future", offerId: "remote-offer-1")
                await harness.yieldToMainActor()
                XCTAssertTrue(slot.addedIceCandidates.isEmpty, "Future-offer ICE must be buffered")

                harness.simulateOfferFromRemote(fromCid: scenario.remoteCid, sdp: "remote-offer", offerId: "remote-offer-1")
                await waitUntil { slot.addedIceCandidates.count == 1 }

                XCTAssertEqual(slot.addedIceCandidates.first?.candidate, "candidate:future")

            case "departed-peer-signaling-is-ignored":
                await harness.advanceToInCallWithTurn(
                    localCid: scenario.localCid,
                    remoteCid: scenario.remoteCid,
                    localJoinedAt: 1,
                    remoteJoinedAt: 2
                )
                let createdSlotsBefore = harness.fakeMedia.createdSlotCids.count
                let answersBefore = harness.fakeProvider.sentPeerMessages(ofType: "answer").count

                harness.simulateRoomState(participants: [(cid: scenario.localCid, joinedAt: 1)], hostCid: scenario.localCid)
                await harness.yieldToMainActor()
                harness.simulateOfferFromRemote(fromCid: scenario.remoteCid, sdp: "late-offer", offerId: "late-offer-id")
                harness.simulateAnswerFromRemote(fromCid: scenario.remoteCid, sdp: "late-answer", offerId: "late-offer-id")
                harness.simulateIceCandidateFromRemote(fromCid: scenario.remoteCid, candidate: "candidate:late", offerId: "late-offer-id")
                await harness.yieldToMainActor()

                XCTAssertTrue(
                    harness.fakeMedia.removedSlots.contains { $0.remoteCid == scenario.remoteCid },
                    "Departed peer slot should be removed"
                )
                XCTAssertEqual(harness.fakeMedia.createdSlotCids.count, createdSlotsBefore)
                XCTAssertEqual(harness.fakeProvider.sentPeerMessages(ofType: "answer").count, answersBefore)

            case "self-signaling-is-ignored":
                await harness.advanceToInCallWithTurn(
                    localCid: scenario.localCid,
                    remoteCid: scenario.remoteCid,
                    localJoinedAt: 1,
                    remoteJoinedAt: 2
                )
                let createdSlotsBefore = harness.fakeMedia.createdSlotCids.count
                let answersBefore = harness.fakeProvider.sentPeerMessages(ofType: "answer").count

                harness.simulateOfferFromRemote(fromCid: scenario.localCid, sdp: "self-offer", offerId: "self-offer-id")
                harness.simulateAnswerFromRemote(fromCid: scenario.localCid, sdp: "self-answer", offerId: "self-offer-id")
                harness.simulateIceCandidateFromRemote(fromCid: scenario.localCid, candidate: "candidate:self", offerId: "self-offer-id")
                await harness.yieldToMainActor()

                XCTAssertNil(harness.fakeMedia.fakeSlots[scenario.localCid])
                XCTAssertEqual(harness.fakeMedia.createdSlotCids.count, createdSlotsBefore)
                XCTAssertEqual(harness.fakeProvider.sentPeerMessages(ofType: "answer").count, answersBefore)

            case "remote-offer-apply-failure-recreates-peer-and-answers":
                await harness.advanceToInCallWithTurn(
                    localCid: scenario.localCid,
                    remoteCid: scenario.remoteCid,
                    localJoinedAt: 2,
                    remoteJoinedAt: 1
                )
                let oldSlot = try XCTUnwrap(harness.fakeMedia.fakeSlots[scenario.remoteCid])
                oldSlot.failNextRemoteOffer = true

                harness.simulateIceCandidateFromRemote(fromCid: scenario.remoteCid, candidate: "candidate:recovered", offerId: "remote-offer-1")
                await harness.yieldToMainActor()
                harness.simulateOfferFromRemote(fromCid: scenario.remoteCid, sdp: "remote-offer", offerId: "remote-offer-1")
                await waitUntil {
                    harness.fakeMedia.createdSlotCids.filter { $0 == scenario.remoteCid }.count >= 2 &&
                        harness.fakeProvider.sentPeerMessages(ofType: "answer").contains {
                            $0.payload?["offerId"]?.stringValue == "remote-offer-1"
                        }
                }

                let newSlot = try XCTUnwrap(harness.fakeMedia.fakeSlots[scenario.remoteCid])
                XCTAssertFalse(newSlot === oldSlot, "Failed remote offer should recreate the peer slot")
                XCTAssertTrue(harness.fakeMedia.removedSlots.contains { ($0 as AnyObject) === oldSlot }, "Old peer slot should be removed")
                XCTAssertTrue(oldSlot.closePeerConnectionCalled, "Old peer slot should be closed")
                XCTAssertEqual(newSlot.setRemoteDescriptionCalls.last?.type, .offer)
                XCTAssertEqual(newSlot.addedIceCandidates.count, 1)
                XCTAssertEqual(newSlot.addedIceCandidates.first?.candidate, "candidate:recovered")

            default:
                XCTFail("Unhandled shared negotiation scenario: \(scenario.id)")
            }
        }

        XCTAssertEqual(handled, Set(scenarios.map(\.id)))
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
