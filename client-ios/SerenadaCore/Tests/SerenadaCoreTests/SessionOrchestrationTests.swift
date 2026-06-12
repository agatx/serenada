import XCTest
@testable import SerenadaCore

@MainActor
final class SessionOrchestrationTests: XCTestCase {

    private var harness: SessionTestHarness!

    override func setUp() async throws {
        harness = SessionTestHarness()
    }

    override func tearDown() async throws {
        harness.tearDown()
        harness = nil
    }

    private func waitForIceServersSet(
        attempts: Int = 8
    ) async {
        for _ in 0..<attempts {
            if harness.fakeMedia.iceServersSet {
                return
            }
            await harness.fakeClock.advance(byMs: 10)
            await harness.yieldToMainActor()
        }
    }

    private func waitUntil(
        attempts: Int = 8,
        condition: @escaping () -> Bool
    ) async {
        for _ in 0..<attempts {
            if condition() {
                return
            }
            await harness.yieldToMainActor()
        }
    }

    // MARK: - Test 1: Permission Gating

    func testPermissionGating() async {
        // Session init fires a Task that checks permissions.
        // Depending on test environment, permissions may be .authorized (skip gating)
        // or .notDetermined (enter awaitingPermissions).
        await harness.yieldToMainActor()

        let phase = harness.session.state.phase
        if phase == .awaitingPermissions {
            // Permissions not yet granted — verify gating then resume
            XCTAssertNotNil(harness.session.state.requiredPermissions)
            harness.session.resumeJoin()
            await harness.yieldToMainActor()
        }
        await harness.waitForJoinStartup()

        // After either direct start or resumeJoin, should be in joining
        XCTAssertEqual(harness.session.state.phase, .joining)
        XCTAssertTrue(harness.fakeMedia.startLocalMediaCalls.count > 0, "Media engine should be started")
        XCTAssertTrue(harness.fakeAudio.activateCalls > 0, "Audio session should be activated")
        XCTAssertGreaterThan(harness.fakeProvider.connectCalls, 0, "Provider should connect")
    }

    // MARK: - Test 2: Join -> Joined -> Waiting

    func testJoinJoinedWaiting() async {
        await harness.advancePastPermissions()

        harness.openSignaling()
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.fakeProvider.joinCalls.first?.roomId, harness.session.roomId)

        // Simulate joined response with single participant (only self)
        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .waiting)
        XCTAssertEqual(harness.session.state.localParticipant.cid, "my-cid")
    }

    func testJoinJoinedWithoutHostPeerIdFallsBackToLocalHost() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.fakeProvider.simulateJoinedWithoutHost(
            peerId: "my-cid",
            participants: [SignalingProviderParticipant(peerId: "my-cid", joinedAt: 1)]
        )
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .waiting)
        XCTAssertEqual(harness.session.state.localParticipant.cid, "my-cid")
        XCTAssertTrue(harness.session.state.localParticipant.isHost)
    }

    // MARK: - Test 3: Join -> Joined -> InCall

    func testJoinJoinedInCall() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        // Simulate joined with a remote participant
        harness.simulateJoinedResponse(
            cid: "my-cid",
            participants: [
                (cid: "my-cid", joinedAt: 1),
                (cid: "remote-cid", joinedAt: 2)
            ],
            hostCid: "my-cid"
        )
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .inCall)
        XCTAssertEqual(harness.session.state.remoteParticipants.count, 1)
        XCTAssertEqual(harness.session.state.remoteParticipants.first?.cid, "remote-cid")
        XCTAssertTrue(harness.fakeMedia.createdSlotCids.contains("remote-cid"), "Should create slot for remote participant")
    }

    // MARK: - Test 4: Server Error

    func testServerError() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.simulateError(code: "ROOM_CAPACITY_UNSUPPORTED", message: "Room is full")
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .error)
        XCTAssertNotNil(harness.session.state.error)
        // Resources should be cleaned up
        XCTAssertTrue(harness.fakeMedia.releaseCalls > 0, "Engine should be released on error")
        XCTAssertGreaterThan(harness.fakeProvider.disconnectCalls, 0, "Provider should be disconnected on error")
    }

    func testTurnRefreshFailedErrorIsNonFatal() async {
        await harness.advancePastPermissions()
        harness.openSignaling()
        harness.simulateJoinedResponse(
            cid: "my-cid",
            participants: [
                (cid: "my-cid", joinedAt: 1),
                (cid: "remote-cid", joinedAt: 2)
            ],
            hostCid: "my-cid"
        )
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .inCall)

        // Built-in providers no longer emit this code, but custom
        // SignalingProviders may; web and Android already survive it.
        harness.simulateError(code: "TURN_REFRESH_FAILED", message: "credential fetch failed")
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .inCall, "Call must survive a TURN refresh failure")
        XCTAssertNil(harness.session.state.error)

        harness.simulateError(code: "ROOM_CAPACITY_UNSUPPORTED", message: "boom")
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .error, "Other error codes must still terminate")
    }

    // MARK: - Test 5: Room State Update

    func testRoomStateUpdate() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        // Start with single participant -> waiting
        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .waiting)

        // Remote participant joins via room_state
        harness.simulateRoomState(
            participants: [
                (cid: "my-cid", joinedAt: 1),
                (cid: "remote-cid", joinedAt: 2)
            ],
            hostCid: "my-cid"
        )
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .inCall)
        XCTAssertEqual(harness.session.state.remoteParticipants.count, 1)
    }

    func testIncrementalPeerJoinAndLeaveWorkWithoutRoomStateSnapshots() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .waiting)

        harness.fakeProvider.simulatePeerJoined(peerId: "remote-cid", joinedAt: 2)
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .inCall)
        XCTAssertEqual(harness.session.state.remoteParticipants.map(\.cid), ["remote-cid"])

        harness.fakeProvider.simulatePeerLeft(peerId: "remote-cid", joinedAt: 2)
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .waiting)
        XCTAssertTrue(harness.session.state.remoteParticipants.isEmpty)
    }

    // MARK: - Test 6: Reconnect on Close

    func testReconnectOnClose() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .waiting)

        // Signaling closes while in waiting state
        harness.fakeProvider.simulateDisconnected(reason: "connection lost")
        await harness.yieldToMainActor()

        // Session should NOT go to idle -- it should remain in waiting and try to reconnect
        XCTAssertNotEqual(harness.session.state.phase, .idle)
        // Diagnostics should show disconnected
        XCTAssertFalse(harness.session.diagnostics.isSignalingConnected)
    }

    // MARK: - Test 7: Leave Cleanup

    func testLeaveCleanup() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.simulateJoinedResponse(
            cid: "my-cid",
            participants: [
                (cid: "my-cid", joinedAt: 1),
                (cid: "remote-cid", joinedAt: 2)
            ],
            hostCid: "my-cid"
        )
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .inCall)

        let releaseBefore = harness.fakeMedia.releaseCalls
        let deactivateBefore = harness.fakeAudio.deactivateCalls

        harness.session.leave()
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.fakeProvider.leaveCalls, 1, "Should send leave via provider")

        XCTAssertEqual(harness.session.state.phase, .idle)
        XCTAssertTrue(harness.fakeProvider.disconnectCalls > 0, "Provider should be disconnected")
        XCTAssertTrue(harness.fakeMedia.releaseCalls > releaseBefore, "Engine should be released")
        XCTAssertTrue(harness.fakeAudio.deactivateCalls > deactivateBefore, "Audio should be deactivated")
    }

    // MARK: - Test 8: End Cleanup

    func testEndCleanup() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()

        harness.session.end()
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.fakeProvider.endCalls, 1, "Should send end_room via provider")
        XCTAssertEqual(harness.session.state.phase, .idle)
    }

    // MARK: - Test 8b: Leave as host must not send end_room (regression)

    func testLeaveAsHostDoesNotSendEndRoom() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.simulateJoinedResponse(
            cid: "host-cid",
            participants: [
                (cid: "host-cid", joinedAt: 1),
                (cid: "remote-cid", joinedAt: 2)
            ],
            hostCid: "host-cid"
        )
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .inCall)
        XCTAssertTrue(harness.session.state.localParticipant.isHost, "Local participant should be host")

        harness.session.leave()
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.fakeProvider.leaveCalls, 1, "Should send leave")
        XCTAssertEqual(harness.fakeProvider.endCalls, 0, "leave() must never send end_room — even for the host")
        XCTAssertEqual(harness.session.state.phase, .idle)
    }

    // MARK: - Test 9: ICE Server Fetch

    func testIceServerFetch() async {
        harness.fakeProvider.iceServerResults = [
            .success([IceServerConfig(urls: ["turn:turn.example.com:3478"], username: "user", credential: "pass")])
        ]
        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()
        await waitForIceServersSet()

        XCTAssertEqual(harness.fakeProvider.getIceServersCallCount, 1, "Should fetch ICE servers from provider")
        XCTAssertTrue(harness.fakeMedia.iceServersSet, "ICE servers should be set on engine")
    }

    // MARK: - Test 10: Empty ICE Server List Falls Back To STUN

    func testEmptyIceServerListFallsBackToDefaultStun() async {
        harness.fakeProvider.iceServerResults = [.success([])]
        await harness.advancePastPermissions()
        harness.openSignaling()
        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()
        await waitForIceServersSet()
        XCTAssertTrue(harness.fakeMedia.iceServersSet, "Default STUN servers should be applied when provider returns []")
    }

    func testIceServersChangedUpdatesExistingAndFuturePeerSlots() async {
        harness.fakeProvider.iceServerResults = [
            .success([IceServerConfig(urls: ["turn:initial.example.com:3478"], username: "user", credential: "pass")])
        ]
        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.simulateJoinedResponse(
            cid: "my-cid",
            participants: [
                (cid: "my-cid", joinedAt: 1),
                (cid: "remote-a", joinedAt: 2)
            ],
            hostCid: "my-cid"
        )
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()

        let existingSlot = harness.fakeMedia.fakeSlots["remote-a"]
        XCTAssertEqual(existingSlot?.appliedIceServerUrls.last, ["turn:initial.example.com:3478"])

        harness.fakeProvider.simulateIceServersChanged([
            IceServerConfig(urls: ["turn:refreshed.example.com:3478"], username: "next-user", credential: "next-pass")
        ])
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()

        XCTAssertEqual(existingSlot?.appliedIceServerUrls.last, ["turn:refreshed.example.com:3478"])

        harness.fakeProvider.simulatePeerJoined(peerId: "remote-b", joinedAt: 3)
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()

        let futureSlot = harness.fakeMedia.fakeSlots["remote-b"]
        XCTAssertEqual(futureSlot?.appliedIceServerUrls.last, ["turn:refreshed.example.com:3478"])
    }

    // MARK: - Test 11: ICE Server Retry Exhaustion

    func testIceServerRetryExhaustionTransitionsToError() async {
        harness.fakeProvider.iceServerResults = [
            .failure(NSError(domain: "test", code: 1)),
            .failure(NSError(domain: "test", code: 2)),
            .failure(NSError(domain: "test", code: 3)),
            .failure(NSError(domain: "test", code: 4))
        ]

        await harness.advancePastPermissions()
        harness.openSignaling()
        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 10_000)
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .error)
        XCTAssertNotNil(harness.session.state.error)
    }

    // MARK: - Timer Tests (via FakeSessionClock)

    func testJoinHardTimeout() async {
        await harness.advancePastPermissions()
        // Extra yield: resumeJoin() dispatches prepareMediaAndConnect() in a Task;
        // we need it to complete so scheduleJoinTimeout() has fired before we advance the clock.
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .joining)

        // Advance clock past the join hard timeout
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.joinHardTimeoutMs))

        XCTAssertEqual(harness.session.state.phase, .error)
    }

    func testJoinKickstartIsNoOpIfSignalingAlreadyStarted() async {
        // After advancePastPermissions, signaling connect is already triggered.
        // The kickstart timer should be a no-op (hasJoinSignalStarted is already true).
        await harness.advancePastPermissions()
        let connectCallsBefore = harness.fakeProvider.connectCalls

        // Advance past kickstart delay — should NOT trigger another connect
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.joinConnectKickstartMs))

        XCTAssertEqual(harness.fakeProvider.connectCalls, connectCallsBefore,
                        "Kickstart should be no-op since signaling already started")
    }

    func testJoinRecoveryAfterJoinSent() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        // After signaling opens, a join is sent and recovery is scheduled.
        // Simulate joined so hasJoinAcknowledged is true.
        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()

        // Already transitioned to waiting, recovery won't fire (phase != .joining).
        XCTAssertEqual(harness.session.state.phase, .waiting)
    }

    func testReconnectBackoffTiming() async {
        await harness.advancePastPermissions()
        harness.openSignaling()
        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .waiting)

        // Close signaling to trigger reconnect
        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        let connectCallsBefore = harness.fakeProvider.connectCalls

        // Before backoff elapses, should NOT reconnect
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.reconnectBackoffBaseMs) - 1)
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.fakeProvider.connectCalls, connectCallsBefore,
                        "Should not reconnect before backoff")

        // After backoff elapses, should reconnect
        await harness.fakeClock.advance(byMs: 2)
        await harness.yieldToMainActor()
        XCTAssertTrue(harness.fakeProvider.connectCalls > connectCallsBefore,
                       "Should reconnect after backoff")
    }

    func testReconnectWithoutProviderManagedReconnectionRejoinsWithReconnectPeerId() async {
        await harness.advancePastPermissions()
        harness.openSignaling()
        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .waiting)

        harness.fakeProvider.simulateDisconnected(reason: "connection lost")
        await harness.yieldToMainActor()
        await waitUntil { [self] in
            harness.fakeClock.pendingSleepCount > 0
        }

        let connectCallsBefore = harness.fakeProvider.connectCalls
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.reconnectBackoffBaseMs) - 1)
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.fakeProvider.connectCalls, connectCallsBefore)

        await harness.fakeClock.advance(byMs: 2)
        await harness.yieldToMainActor()
        XCTAssertGreaterThan(harness.fakeProvider.connectCalls, connectCallsBefore)

        harness.fakeProvider.simulateConnected()
        await waitUntil { [self] in
            harness.fakeProvider.joinCalls.count > 1
        }

        XCTAssertEqual(harness.fakeProvider.joinCalls.last?.options.reconnectPeerId, "my-cid")
    }

    func testRoomEndedTransitionsSessionToEndingAndClearsRemoteState() async {
        await harness.advanceToInCallWithTurn(
            localCid: "my-cid",
            remoteCid: "remote-cid",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        harness.fakeProvider.simulateRoomEnded(by: "remote-cid", reason: "host ended")
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .ending)
        XCTAssertTrue(harness.session.state.remoteParticipants.isEmpty)
    }

    func testConnectionStatusRetryingDelay() async {
        await harness.advancePastPermissions()
        harness.openSignaling()
        harness.simulateJoinedResponse(
            cid: "my-cid",
            participants: [
                (cid: "my-cid", joinedAt: 1),
                (cid: "remote-cid", joinedAt: 2)
            ],
            hostCid: "my-cid"
        )
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .inCall)

        // Close signaling while in-call to trigger connection degraded
        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.connectionStatus, .recovering)

        // Advance past the 10-second retrying delay
        await harness.fakeClock.advance(byMs: 10_000)
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.connectionStatus, .retrying)
    }
}
