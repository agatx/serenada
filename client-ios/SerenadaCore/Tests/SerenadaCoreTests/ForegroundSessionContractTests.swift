@testable import SerenadaCore
import XCTest

/// Phase 2 session-level foreground contract (contract §3): held-initial join,
/// token-gated preflight/activate/release, the token+generation double fence, and
/// routing the direct single-call `join()` through the arbiter.
@MainActor
final class ForegroundSessionContractTests: XCTestCase {

    override func tearDown() {
        // Defensive: clear the singleton between cases even for tests that build a
        // session without a harness teardown.
        ForegroundMediaArbiter.shared.resetForTests()
        super.tearDown()
    }

    private func yieldToMainActor() async {
        for _ in 0..<4 { await Task.yield() }
    }

    private func waitUntil(attempts: Int = 64, condition: () -> Bool) async {
        for _ in 0..<attempts {
            if condition() { return }
            await yieldToMainActor()
        }
    }

    private func lastBroadcastMediaState(
        _ harness: SessionTestHarness
    ) -> (audioEnabled: Bool?, videoEnabled: Bool?, held: Bool?)? {
        guard let payload = harness.fakeProvider
            .broadcastMessages(ofType: "participant_media_state").last?.payload
        else { return nil }
        return (
            audioEnabled: payload["audioEnabled"]?.boolValue,
            videoEnabled: payload["videoEnabled"]?.boolValue,
            held: payload["held"]?.boolValue
        )
    }

    // MARK: - Held-initial join (Core Invariant 3)

    func testHeldInitialJoinCreatesStableSendersWithoutCaptureOrCoordinatorActivation() async {
        let harness = SessionTestHarness(initialMediaRole: .held)
        await harness.advanceToInCallHeld(localCid: "local", remoteCid: "remote")

        // Role/activation: held + inactive from the start.
        XCTAssertEqual(harness.session.mediaRole, .held)
        XCTAssertEqual(harness.session.mediaActivationState, .inactive)

        // No capture: startLocalMedia never ran on a held join.
        XCTAssertTrue(harness.fakeMedia.startLocalMediaCalls.isEmpty,
                      "A held join must NOT start local capture")
        XCTAssertFalse(harness.fakeMedia.hasLocalAudioTrack,
                       "A held join owns no audio capture track")
        XCTAssertFalse(harness.fakeMedia.hasLocalVideoTrack,
                       "A held join owns no video capture track")

        // No coordinator activation: the FakeAudioCoordinator saw no activateCallSession.
        XCTAssertEqual(harness.fakeAudioCoordinator.activateCalls, 0,
                       "A held join must NOT activate the audio coordinator")
        XCTAssertEqual(harness.fakeAudio.activateCalls, 0,
                       "A held join must NOT activate the audio controller")

        // No lease: the held session never self-acquired a direct foreground lease.
        XCTAssertNil(harness.arbiter.currentOwnerToken,
                     "A held join must NOT hold the foreground lease")

        // Stable senders without capture (Invariant 3): a peer slot was created
        // during negotiation (the real `WebRtcEngine.createSlot` materializes the
        // stable audio/video transceivers+senders for it via `ensureReceiveTransceivers`
        // / `ensureOwnerVideoTransceivers`, carrying nil tracks because the held
        // session owns none). At the fake-engine level we assert the slot exists
        // (negotiation ran) while NO local capture track was ever acquired.
        let slot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(slot, "A peer slot must be created for the held session (stable senders)")
        XCTAssertEqual(harness.session.mediaRole, .held,
                       "The session must still be held after the peer slot is created")
        XCTAssertFalse(harness.fakeMedia.hasLocalAudioTrack)
        XCTAssertFalse(harness.fakeMedia.hasLocalVideoTrack)

        harness.tearDown()
    }

    /// Q4: a held-initial slot must carry SEND-capable (`.sendRecv`) audio +
    /// legacy-video senders with NIL tracks (contract §5 / Core Invariant 3), so
    /// resume attaches fresh tracks via `replaceTrack` with NO renegotiation.
    /// At the fake level: the engine entered held-senders mode and the slot
    /// created during negotiation is `sendCapableForHold`, while NO local capture
    /// was ever acquired.
    func testHeldInitialSlotHasSendCapableSendersAndResumeAttachesWithoutRenegotiation() async {
        let harness = SessionTestHarness(initialMediaRole: .held, isCapabilityGranted: { _ in true })
        await harness.advanceToInCallHeld(localCid: "local", remoteCid: "remote")

        // The held join entered held-senders mode (createSendersForHold) BEFORE
        // negotiation, so the slot's senders are send-capable without capture.
        XCTAssertEqual(harness.fakeMedia.createSendersForHoldCalls, 1,
                       "A held join must enter held-senders mode before negotiation")
        XCTAssertTrue(harness.fakeMedia.heldSendersMode,
                      "A held session stays in held-senders mode until foregrounded")
        let slot = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertNotNil(slot, "A peer slot must be created for the held session")
        XCTAssertTrue(slot?.sendCapableForHold == true,
                      "A held-initial slot's audio+video senders must be SEND-capable (.sendRecv)")
        // No capture: the send-capable senders carry NIL tracks.
        XCTAssertTrue(harness.fakeMedia.startLocalMediaCalls.isEmpty)
        XCTAssertFalse(harness.fakeMedia.hasLocalAudioTrack)
        XCTAssertFalse(harness.fakeMedia.hasLocalVideoTrack)

        // Resume: tracks attach to the EXISTING send-capable senders via the
        // resume path (replaceTrack), with NO call to startLocalMedia (which would
        // be a fresh-negotiation capture path). The single resume call proves the
        // attach rode the existing senders.
        let token = try! harness.arbiter.acquireForeground(ownerId: "test-room-id")
        let gen = harness.arbiter.nextOperationGeneration()
        try! harness.session.activateForeground(token, generation: gen)
        await waitUntil { harness.session.mediaRole == .foreground }

        XCTAssertEqual(harness.fakeMedia.resumeLocalMediaFromHoldCalls.count, 1,
                       "Resume must attach via resumeLocalMediaFromHold (replaceTrack), not renegotiate")
        XCTAssertTrue(harness.fakeMedia.startLocalMediaCalls.isEmpty,
                      "Resume must NOT go through startLocalMedia (no fresh-negotiation capture)")
        harness.tearDown()
    }

    func testHeldInitialJoinDoesNotWriteRecoveryUntilForegrounded() async {
        // A held join connects signaling but owns no foreground media; it must not
        // publish actual* state.
        let harness = SessionTestHarness(initialMediaRole: .held)
        await harness.advanceToInCallHeld(localCid: "local", remoteCid: "remote")
        XCTAssertFalse(harness.session.actualAudioPublished)
        XCTAssertFalse(harness.session.actualVideoPublished)
        XCTAssertFalse(harness.session.state.localParticipant.audioEnabled)
        XCTAssertFalse(harness.session.state.localParticipant.videoEnabled)
        harness.tearDown()
    }

    // MARK: - Direct join routes through the arbiter (Phase 2, §2)

    func testDirectJoinAcquiresLease() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local", remoteCid: "remote")
        XCTAssertNotNil(harness.arbiter.currentOwnerToken,
                        "A direct foreground join must acquire the arbiter lease")
        XCTAssertEqual(harness.arbiter.owningMode, .direct,
                       "A direct join claims direct owning mode")
        harness.tearDown()
    }

    func testSecondConcurrentDirectJoinFailsLeaseUnavailable() async {
        // Two direct sessions sharing ONE dedicated arbiter. The first acquires;
        // the second must fail to activate (lease unavailable -> error phase).
        let sharedArbiter = ForegroundMediaArbiter()
        let first = SessionTestHarness(roomId: "room-a", arbiter: sharedArbiter)
        await first.advanceToInCallWithTurn(localCid: "a-local", remoteCid: "a-remote")
        XCTAssertNotNil(sharedArbiter.currentOwnerToken, "First direct join holds the lease")

        let second = SessionTestHarness(roomId: "room-b", arbiter: sharedArbiter)
        // Drive the second join past the permission gate so it reaches the
        // lease-acquire in prepareMediaAndConnect; it should hit the lease guard
        // and error (the lease is already held by the first session).
        await second.advancePastPermissions()
        await waitUntil { second.session.state.phase == .error }

        XCTAssertEqual(second.session.state.phase, .error,
                       "A second concurrent direct join must fail when the lease is held")
        XCTAssertTrue(second.fakeMedia.startLocalMediaCalls.isEmpty,
                      "A lease-blocked join must NOT start local capture")
        // The first session still holds the (single) lease.
        XCTAssertNotNil(sharedArbiter.currentOwnerToken)

        first.tearDown()
        second.tearDown()
    }

    func testSingleCallJoinLeaveReleasesLeaseSoReacquireWorks() async {
        let arbiter = ForegroundMediaArbiter()
        let first = SessionTestHarness(roomId: "room-a", arbiter: arbiter)
        await first.advanceToInCallWithTurn(localCid: "a-local", remoteCid: "a-remote")
        XCTAssertNotNil(arbiter.currentOwnerToken)

        // Leaving releases the lease (resetResources).
        first.session.leave()
        await waitUntil { arbiter.currentOwnerToken == nil }
        XCTAssertNil(arbiter.currentOwnerToken, "leave() must release the direct lease")
        XCTAssertNil(arbiter.owningMode, "leave() must clear the direct owning mode")

        // A fresh direct join now succeeds (reacquire works after leave).
        let second = SessionTestHarness(roomId: "room-b", arbiter: arbiter)
        await second.advanceToInCallWithTurn(localCid: "b-local", remoteCid: "b-remote")
        XCTAssertNotNil(arbiter.currentOwnerToken,
                        "A new direct join must reacquire the lease after the prior call left")
        XCTAssertEqual(second.session.mediaRole, .foreground)

        first.tearDown()
        second.tearDown()
    }

    // MARK: - preflightForeground (pure)

    func testPreflightOkForMutedCameraOffDesired() async {
        // A muted, camera-off held call needs no device, so preflight is .ok even
        // with NO grants.
        let harness = SessionTestHarness(
            config: SerenadaConfig(
                signalingProvider: FakeSignalingProvider(),
                defaultAudioEnabled: false,
                defaultVideoEnabled: false
            ),
            initialMediaRole: .held,
            isCapabilityGranted: { _ in false } // nothing granted
        )
        await harness.advanceToInCallHeld(localCid: "local", remoteCid: "remote")

        XCTAssertEqual(harness.session.preflightForeground(), .ok,
                       "A muted + camera-off desired state must preflight .ok with no grants")
        harness.tearDown()
    }

    func testPreflightNeedsPermissionWhenDesiredAudioNeedsUngrantedMic() async {
        // Desired audio ON but the mic grant is missing -> needsPermission.
        let harness = SessionTestHarness(
            config: SerenadaConfig(
                signalingProvider: FakeSignalingProvider(),
                defaultAudioEnabled: true,
                defaultVideoEnabled: false
            ),
            initialMediaRole: .held,
            isCapabilityGranted: { capability in capability != .microphone } // mic NOT granted
        )
        await harness.advanceToInCallHeld(localCid: "local", remoteCid: "remote")

        XCTAssertEqual(harness.session.preflightForeground(), .needsPermission,
                       "Desired audio with an ungranted mic must preflight .needsPermission")
        harness.tearDown()
    }

    func testPreflightDoesNotStartCaptureOrPrompt() async {
        let harness = SessionTestHarness(
            config: SerenadaConfig(
                signalingProvider: FakeSignalingProvider(),
                defaultAudioEnabled: true,
                defaultVideoEnabled: false
            ),
            initialMediaRole: .held,
            isCapabilityGranted: { _ in true }
        )
        await harness.advanceToInCallHeld(localCid: "local", remoteCid: "remote")
        let startCallsBefore = harness.fakeMedia.startLocalMediaCalls.count

        _ = harness.session.preflightForeground()
        await yieldToMainActor()

        XCTAssertEqual(harness.fakeMedia.startLocalMediaCalls.count, startCallsBefore,
                       "preflightForeground must be PURE: no capture")
        XCTAssertEqual(harness.fakeMedia.resumeLocalMediaFromHoldCalls.count, 0)
        harness.tearDown()
    }

    // MARK: - Token-gated activate / release

    func testActivateForegroundUnderTokenDrivesToForeground() async {
        let harness = SessionTestHarness(initialMediaRole: .held, isCapabilityGranted: { _ in true })
        await harness.advanceToInCallHeld(localCid: "local", remoteCid: "remote")

        let token = try! harness.arbiter.acquireForeground(ownerId: "test-room-id")
        let gen = harness.arbiter.nextOperationGeneration()
        try! harness.session.activateForeground(token, generation: gen)
        await waitUntil { harness.session.mediaRole == .foreground }

        XCTAssertEqual(harness.session.mediaRole, .foreground)
        XCTAssertEqual(harness.session.mediaActivationState, .active)
        XCTAssertGreaterThan(harness.fakeAudioCoordinator.activateCalls, 0,
                             "activateForeground must activate the coordinator")
        XCTAssertEqual(harness.fakeMedia.resumeLocalMediaFromHoldCalls.count, 1,
                       "activateForeground must reacquire local media")
        harness.tearDown()
    }

    func testReleaseForegroundIsIdempotentAndDoesNotReleaseLease() async {
        let harness = SessionTestHarness(initialMediaRole: .held, isCapabilityGranted: { _ in true })
        await harness.advanceToInCallHeld(localCid: "local", remoteCid: "remote")

        let token = try! harness.arbiter.acquireForeground(ownerId: "test-room-id")
        let gen = harness.arbiter.nextOperationGeneration()
        try! harness.session.activateForeground(token, generation: gen)
        await waitUntil { harness.session.mediaRole == .foreground }

        // Release drains the session to held; it must NOT release the arbiter lease
        // (the registry owns that).
        harness.session.releaseForeground(token)
        await waitUntil { harness.session.mediaRole == .held }
        // A second release is idempotent and must not throw.
        harness.session.releaseForeground(token)
        await yieldToMainActor()

        XCTAssertEqual(harness.session.mediaRole, .held)
        XCTAssertEqual(harness.session.mediaActivationState, .inactive)
        XCTAssertNotNil(harness.arbiter.currentOwnerToken,
                        "releaseForeground must NOT release the arbiter lease (registry owns it)")
        XCTAssertEqual(harness.arbiter.currentOwnerToken, token)
        harness.tearDown()
    }

    // MARK: - Double fence: stale generation OR stale token

    /// A late activation completion must be dropped when the session is superseded
    /// mid-coordinator-activation by a registry `releaseForeground`. The release
    /// both bumps the operation generation AND rotates the fence token away, so the
    /// stale completion bails (combined fence: generation + owner token, §3).
    /// (The pure generation fence is covered by the Phase-1 HoldResume race test.)
    func testStaleOwnerTokenActivationCallbackIsDropped() async {
        let gated = GatedAudioCoordinator()
        let arbiter = ForegroundMediaArbiter()
        let config = SerenadaConfig(signalingProvider: FakeSignalingProvider(), audioCoordinator: gated)
        let harness = SessionTestHarness(
            config: config,
            initialMediaRole: .held,
            isCapabilityGranted: { _ in true },
            arbiter: arbiter
        )
        await harness.advanceToInCallHeld(localCid: "local", remoteCid: "remote")

        // Begin a token-gated activation that BLOCKS in the coordinator.
        let token = try! arbiter.acquireForeground(ownerId: "test-room-id")
        let gen = arbiter.nextOperationGeneration()
        gated.blockNextActivation = true
        try! harness.session.activateForeground(token, generation: gen)
        await waitUntil { gated.activationInFlight }
        XCTAssertEqual(harness.session.mediaActivationState, .activating)

        // The registry supersedes this activation by releasing foreground (which
        // clears the session's fence token) WITHOUT bumping the generation via a
        // newer activate — proving the owner-token fence is independent.
        harness.session.releaseForeground(token)
        await yieldToMainActor()

        // Release the blocked activation; its completion must fail the owner-token
        // fence and NOT commit foreground.
        gated.releaseActivation()
        await yieldToMainActor()
        await yieldToMainActor()

        XCTAssertEqual(harness.session.mediaRole, .held,
                       "A stale-token activation completion must NOT commit foreground")
        XCTAssertEqual(harness.session.mediaActivationState, .inactive)
        XCTAssertFalse(harness.session.actualAudioPublished)
        XCTAssertNotEqual(lastBroadcastMediaState(harness)?.held, false,
                          "The superseded activation must not broadcast held:false")
        harness.tearDown()
    }

    /// Q5: the late-activation fence must check the ARBITER's live owner, not the
    /// session's local `foregroundOwnerToken` field. Here the arbiter's owner is
    /// ROTATED to a different token while the activation is in flight, WITHOUT
    /// bumping the operation generation and WITHOUT clearing the session's local
    /// token field (the field still equals the original token). A local-field-only
    /// fence would WRONGLY commit foreground; the arbiter-owner fence drops it.
    func testLateActivationDroppedWhenArbiterOwnerRotatedEvenIfLocalTokenMatches() async {
        let gated = GatedAudioCoordinator()
        let arbiter = ForegroundMediaArbiter()
        let config = SerenadaConfig(signalingProvider: FakeSignalingProvider(), audioCoordinator: gated)
        let harness = SessionTestHarness(
            config: config,
            initialMediaRole: .held,
            isCapabilityGranted: { _ in true },
            arbiter: arbiter
        )
        await harness.advanceToInCallHeld(localCid: "local", remoteCid: "remote")

        // Begin a token-gated activation under token A that BLOCKS in the
        // coordinator. The session stores A in its local foregroundOwnerToken.
        let tokenA = try! arbiter.acquireForeground(ownerId: "test-room-id")
        let gen = arbiter.nextOperationGeneration()
        gated.blockNextActivation = true
        try! harness.session.activateForeground(tokenA, generation: gen)
        await waitUntil { gated.activationInFlight }
        XCTAssertEqual(harness.session.mediaActivationState, .activating)

        // Rotate the ARBITER's owner WITHOUT bumping the operation generation and
        // WITHOUT touching the session's local token field: release A and acquire a
        // fresh token B. The session still locally believes it owns A (its field is
        // unchanged), but the arbiter now reports B as the current owner.
        arbiter.releaseLease(tokenA)
        let tokenB = try! arbiter.acquireForeground(ownerId: "other-room")
        XCTAssertEqual(arbiter.currentOwnerToken, tokenB)
        XCTAssertNotEqual(tokenA, tokenB)

        // Release the blocked activation. Generation still matches and the
        // session's local field still equals A, but the arbiter's owner is B, so
        // the arbiter-owner fence must drop this completion: foreground is never
        // committed and no media is reacquired. (A bare fence-drop leaves the
        // activation state where it was; the registry's rollback/abort, not this
        // unit, drives it back to inactive — that path is covered elsewhere.)
        gated.releaseActivation()
        await yieldToMainActor()
        await yieldToMainActor()

        XCTAssertEqual(harness.session.mediaRole, .held,
                       "A completion whose arbiter owner rotated must NOT commit foreground")
        XCTAssertFalse(harness.session.actualAudioPublished)
        XCTAssertTrue(harness.fakeMedia.resumeLocalMediaFromHoldCalls.isEmpty,
                      "The fenced-out completion must NOT reacquire local media")
        XCTAssertNotEqual(lastBroadcastMediaState(harness)?.held, false,
                          "The fenced-out activation must not broadcast held:false")

        // Clean up the dedicated arbiter's lease so teardown is tidy.
        arbiter.releaseLease(tokenB)
        harness.tearDown()
    }
}
