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

        // After either direct start or resumeJoin, should be in joining
        XCTAssertEqual(harness.session.state.phase, .joining)
        XCTAssertTrue(harness.fakeMedia.startLocalMediaCalls.count > 0, "Media engine should be started")
        XCTAssertTrue(harness.fakeAudio.activateCalls > 0, "Audio session should be activated")
        XCTAssertTrue(harness.fakeSignaling.connectCalls.count > 0, "Signaling should connect")
    }

    // MARK: - Test 2: Join -> Joined -> Waiting

    func testJoinJoinedWaiting() async {
        await harness.advancePastPermissions()

        harness.openSignaling()
        // After open, a "join" message should have been sent (pending join room)
        let joinMessages = harness.fakeSignaling.sentMessages(ofType: "join")
        XCTAssertFalse(joinMessages.isEmpty, "Should send join message after signaling opens")

        // Simulate joined response with single participant (only self)
        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.phase, .waiting)
        XCTAssertEqual(harness.session.state.localParticipant.cid, "my-cid")
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
        XCTAssertTrue(harness.fakeSignaling.closeCalls > 0, "Signaling should be closed on error")
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

    // MARK: - Test 6: Reconnect on Close

    func testReconnectOnClose() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.simulateJoinedResponse(cid: "my-cid")
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.phase, .waiting)

        // Signaling closes while in waiting state
        harness.fakeSignaling.simulateClosed(reason: "connection lost")
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

        // "leave" message should have been sent
        let leaveMessages = harness.fakeSignaling.sentMessages(ofType: "leave")
        XCTAssertFalse(leaveMessages.isEmpty, "Should send leave message")

        XCTAssertEqual(harness.session.state.phase, .idle)
        XCTAssertTrue(harness.fakeSignaling.closeCalls > 0, "Signaling should be closed")
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

        let endMessages = harness.fakeSignaling.sentMessages(ofType: "end_room")
        XCTAssertFalse(endMessages.isEmpty, "Should send end_room message")
        XCTAssertEqual(harness.session.state.phase, .idle)
    }

    // MARK: - Test 9: TURN Credential Fetch

    func testTurnCredentialFetch() async {
        await harness.advancePastPermissions()
        harness.openSignaling()

        // Simulate joined with a TURN token
        harness.simulateJoinedResponse(cid: "my-cid", turnToken: "test-turn-token")
        await harness.yieldToMainActor()
        // Give async TURN fetch time to complete
        await harness.yieldToMainActor()
        // Advance clock past TURN fetch timeout
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.turnFetchTimeoutMs) + 100)

        XCTAssertFalse(harness.fakeAPI.fetchTurnCredentialsCalls.isEmpty, "Should fetch TURN credentials")
        let call = harness.fakeAPI.fetchTurnCredentialsCalls.first
        XCTAssertEqual(call?.token, "test-turn-token")
        XCTAssertTrue(harness.fakeMedia.iceServersSet, "ICE servers should be set on engine")
    }

    // MARK: - Test 10: TURN Credential Failure

    func testTurnCredentialFailure() async {
        harness.fakeAPI.turnCredentialsResult = .failure(NSError(domain: "test", code: -1))

        await harness.advancePastPermissions()
        harness.openSignaling()

        harness.simulateJoinedResponse(cid: "my-cid", turnToken: "bad-token")
        await harness.yieldToMainActor()
        // Advance clock past TURN fetch timeout
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.turnFetchTimeoutMs) + 100)

        // Even on TURN failure, default STUN servers should be applied
        // (the session calls applyDefaultIceServers() first in ensureIceSetupIfNeeded,
        // then tries TURN asynchronously; TURN failure shouldn't prevent ICE setup)
        XCTAssertTrue(harness.fakeMedia.iceServersSet, "Default STUN servers should be applied")
    }

    // MARK: - Timer Tests (via FakeSessionClock)

    func testJoinHardTimeout() async {
        await harness.advancePastPermissions()
        XCTAssertEqual(harness.session.state.phase, .joining)

        // Advance clock past the join hard timeout
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.joinHardTimeoutMs))

        XCTAssertEqual(harness.session.state.phase, .error)
    }

    func testJoinKickstartIsNoOpIfSignalingAlreadyStarted() async {
        // After advancePastPermissions, signaling connect is already triggered.
        // The kickstart timer should be a no-op (hasJoinSignalStarted is already true).
        await harness.advancePastPermissions()
        let connectCallsBefore = harness.fakeSignaling.connectCalls.count

        // Advance past kickstart delay — should NOT trigger another connect
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.joinConnectKickstartMs))

        XCTAssertEqual(harness.fakeSignaling.connectCalls.count, connectCallsBefore,
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
        harness.fakeSignaling.simulateClosed(reason: "test")
        await harness.yieldToMainActor()

        let connectCallsBefore = harness.fakeSignaling.connectCalls.count

        // Before backoff elapses, should NOT reconnect
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.reconnectBackoffBaseMs) - 1)
        XCTAssertEqual(harness.fakeSignaling.connectCalls.count, connectCallsBefore,
                        "Should not reconnect before backoff")

        // After backoff elapses, should reconnect
        await harness.fakeClock.advance(byMs: 2)
        XCTAssertTrue(harness.fakeSignaling.connectCalls.count > connectCallsBefore,
                       "Should reconnect after backoff")
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
        harness.fakeSignaling.simulateClosed(reason: "test")
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.state.connectionStatus, .recovering)

        // Advance past the 10-second retrying delay
        await harness.fakeClock.advance(byMs: 10_000)

        XCTAssertEqual(harness.session.state.connectionStatus, .retrying)
    }
}
