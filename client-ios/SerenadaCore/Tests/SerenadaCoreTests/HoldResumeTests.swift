@testable import SerenadaCore
import XCTest

/// Phase 1 session-internal hold/resume primitives for the multi-call session
/// feature. These exercise `applyHeldRoleInternal()` / `applyForegroundRoleInternal()`
/// through the fake media engine + fake audio coordinator/controller, plus the
/// additive `held` field on `participant_media_state`.
@MainActor
final class HoldResumeTests: XCTestCase {

    private func yieldToMainActor() async {
        await Task.yield()
        await Task.yield()
        await Task.yield()
        await Task.yield()
    }

    private func waitUntil(attempts: Int = 64, condition: () -> Bool) async {
        for _ in 0..<attempts {
            if condition() { return }
            await yieldToMainActor()
        }
    }

    /// Drive a harness to in-call as host (so a peer slot exists). The default
    /// harness config joins with audio on; video depends on camera availability.
    private func makeInCallHarness() async -> SessionTestHarness {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        return harness
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

    // MARK: - Default role

    func testFreshlyJoinedCallIsForeground() async {
        let harness = await makeInCallHarness()
        XCTAssertEqual(harness.session.mediaRole, .foreground)
        XCTAssertEqual(harness.session.mediaActivationState, .active)
        harness.tearDown()
    }

    // MARK: - Hold

    func testHoldSuspendsMediaAndDeactivatesAudioOwnership() async {
        let harness = await makeInCallHarness()
        let deactivateControllerBefore = harness.fakeAudio.deactivateCalls
        let deactivateCoordinatorBefore = harness.fakeAudioCoordinator.deactivateCalls

        harness.session.applyHeldRoleInternal()
        await yieldToMainActor()

        XCTAssertEqual(harness.fakeMedia.suspendLocalMediaForHoldCalls, 1,
                       "Hold must release local capture via suspendLocalMediaForHold")
        XCTAssertEqual(harness.fakeMedia.detachRenderersForHoldCalls, 1,
                       "Hold must detach renderers")
        XCTAssertEqual(harness.fakeAudio.deactivateCalls, deactivateControllerBefore + 1,
                       "Hold must deactivate the audio controller")
        await waitUntil {
            harness.fakeAudioCoordinator.deactivateCalls > deactivateCoordinatorBefore
        }
        XCTAssertGreaterThan(harness.fakeAudioCoordinator.deactivateCalls, deactivateCoordinatorBefore,
                             "Hold must deactivate the audio coordinator")

        XCTAssertEqual(harness.session.mediaRole, .held)
        XCTAssertEqual(harness.session.mediaActivationState, .inactive)
        XCTAssertFalse(harness.session.actualAudioPublished)
        XCTAssertFalse(harness.session.actualVideoPublished)
        harness.tearDown()
    }

    func testHoldBroadcastsHeldTrueAfterCaptureStops() async {
        let harness = await makeInCallHarness()
        harness.session.applyHeldRoleInternal()
        await yieldToMainActor()

        let last = lastBroadcastMediaState(harness)
        XCTAssertEqual(last?.held, true, "Held broadcast must carry held:true")
        XCTAssertEqual(last?.audioEnabled, false, "Held broadcast must report audio off")
        XCTAssertEqual(last?.videoEnabled, false, "Held broadcast must report video off")
        XCTAssertEqual(harness.fakeMedia.suspendLocalMediaForHoldCalls, 1,
                       "Capture must be released before the held broadcast")
        harness.tearDown()
    }

    func testHoldIsIdempotent() async {
        let harness = await makeInCallHarness()
        harness.session.applyHeldRoleInternal()
        await yieldToMainActor()
        harness.session.applyHeldRoleInternal()
        await yieldToMainActor()

        XCTAssertEqual(harness.fakeMedia.suspendLocalMediaForHoldCalls, 1,
                       "A second hold must be a no-op (already held)")
        XCTAssertEqual(harness.session.mediaRole, .held)
        harness.tearDown()
    }

    // MARK: - Resume

    func testResumeReactivatesAndRestoresDesiredIntent() async {
        let harness = await makeInCallHarness()
        let activateCoordinatorBefore = harness.fakeAudioCoordinator.activateCalls
        let activateControllerBefore = harness.fakeAudio.activateCalls
        // Capture desired intent at the moment of hold (audio on by default;
        // video depends on camera availability in the test environment).
        let videoDesiredAtHold = harness.session.state.localParticipant.videoEnabled

        harness.session.applyHeldRoleInternal()
        await yieldToMainActor()
        await waitUntil { harness.session.mediaRole == .held }

        harness.session.applyForegroundRoleInternal()
        await waitUntil { harness.session.mediaRole == .foreground }

        XCTAssertGreaterThan(harness.fakeAudioCoordinator.activateCalls, activateCoordinatorBefore,
                             "Resume must re-activate the audio coordinator")
        XCTAssertGreaterThan(harness.fakeAudio.activateCalls, activateControllerBefore,
                             "Resume must re-activate the audio controller")
        XCTAssertEqual(harness.fakeMedia.resumeLocalMediaFromHoldCalls.count, 1,
                       "Resume must reacquire local media")
        // Desired intent restored: audio on, video matches pre-hold state.
        let resume = harness.fakeMedia.resumeLocalMediaFromHoldCalls.first
        XCTAssertEqual(resume?.audioEnabled, true, "Resume must restore desired mic intent")
        XCTAssertEqual((resume?.videoMode ?? nil) != nil, videoDesiredAtHold,
                       "Resume must restore the desired camera intent from before hold")

        XCTAssertEqual(harness.session.mediaRole, .foreground)
        XCTAssertEqual(harness.session.mediaActivationState, .active)
        harness.tearDown()
    }

    func testResumeBroadcastsHeldFalseAfterMediaFlows() async {
        let harness = await makeInCallHarness()
        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }

        harness.session.applyForegroundRoleInternal()
        await waitUntil { harness.session.mediaRole == .foreground }
        await yieldToMainActor()

        let last = lastBroadcastMediaState(harness)
        XCTAssertEqual(last?.held, false, "Resume must broadcast held:false")
        harness.tearDown()
    }

    func testResumePreservesDesiredMuteIntentAcrossHold() async {
        let harness = await makeInCallHarness()
        // User mutes before holding; desired intent must survive the hold.
        harness.session.setMicMuted(true)
        await yieldToMainActor()

        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }

        harness.session.applyForegroundRoleInternal()
        await waitUntil { harness.session.mediaRole == .foreground }

        let resume = harness.fakeMedia.resumeLocalMediaFromHoldCalls.first
        XCTAssertEqual(resume?.audioEnabled, false,
                       "Resume must restore the muted intent set before hold")
        harness.tearDown()
    }

    /// FIX I1: a MUTED held call must resume MUTED. The resume must NOT request
    /// the engine to reacquire the mic (audioEnabled:false, so the engine keeps
    /// the audio sender track nil and the OS mic indicator stays off), and the
    /// resumed `participant_media_state` broadcast must report `audioEnabled:false`
    /// (derived from desired intent + route, not from track presence).
    func testMutedHeldCallResumesMutedWithoutReacquiringMic() async {
        let harness = await makeInCallHarness()
        harness.session.setMicMuted(true)
        await yieldToMainActor()

        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }

        harness.session.applyForegroundRoleInternal()
        await waitUntil { harness.session.mediaRole == .foreground }
        await yieldToMainActor()

        // The engine is told NOT to reacquire the mic on resume.
        let resume = harness.fakeMedia.resumeLocalMediaFromHoldCalls.first
        XCTAssertEqual(resume?.audioEnabled, false,
                       "A muted held call must resume with audio NOT reacquired")
        // And the resumed broadcast reports audio off, not a stale live state.
        let last = lastBroadcastMediaState(harness)
        XCTAssertEqual(last?.audioEnabled, false,
                       "Resumed broadcast must report audioEnabled:false for a muted call")
        XCTAssertEqual(last?.held, false, "Resume must broadcast held:false")
        harness.tearDown()
    }

    // MARK: - Remote deafen primitive (slot level)

    func testRemoteDeafenTogglesIndependentlyOfDuck() {
        let slot = FakePeerConnectionSlot(remoteCid: "remote")
        XCTAssertTrue(slot.remotePlaybackEnabled, "Remote playback starts enabled")

        slot.setRemotePlaybackEnabled(false)
        XCTAssertFalse(slot.remotePlaybackEnabled, "Deafen disables remote playback")
        XCTAssertEqual(slot.setRemotePlaybackEnabledCalls, [false])

        slot.setRemotePlaybackEnabled(true)
        XCTAssertTrue(slot.remotePlaybackEnabled, "Re-enable restores remote playback")
        XCTAssertEqual(slot.setRemotePlaybackEnabledCalls, [false, true])
    }

    /// FIX I3: the remote-playout deafen must be STICKY across slots created AFTER
    /// hold. A peer that joins (or renegotiates) while this session is held must
    /// get a deafened slot, not a default-enabled one — otherwise the deafen leaks
    /// the moment a new peer arrives.
    func testDeafenIsStickyForSlotsCreatedWhileHeld() async {
        let harness = await makeInCallHarness()
        // Slot for the original peer exists and is enabled before hold.
        let original = harness.fakeMedia.fakeSlots["remote"]
        XCTAssertEqual(original?.remotePlaybackEnabled, true)

        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }

        // Hold deafened the existing slot.
        XCTAssertEqual(original?.remotePlaybackEnabled, false,
                       "Hold must deafen the existing peer's slot")

        // A new peer joins while held: a fresh slot is created via the negotiation
        // engine (room_state -> getOrCreateSlot -> engine.createSlot).
        harness.simulateRoomState(
            participants: [
                (cid: "local", joinedAt: 1),
                (cid: "remote", joinedAt: 2),
                (cid: "remote2", joinedAt: 3)
            ],
            hostCid: "local"
        )
        await yieldToMainActor()
        await waitUntil { harness.fakeMedia.fakeSlots["remote2"] != nil }

        let lateSlot = harness.fakeMedia.fakeSlots["remote2"]
        XCTAssertNotNil(lateSlot, "A new peer joining while held must get a slot")
        XCTAssertEqual(lateSlot?.remotePlaybackEnabled, false,
                       "A slot created while held must inherit the deafen (sticky)")
        harness.tearDown()
    }

    // MARK: - Post-reconnect resync re-broadcast (FIX I2)

    /// FIX I2: a transport that reconnects WITHOUT a fresh `joined` runs the
    /// post-reconnect resync (`flushPostReconnectResync`), not `handleJoined`. The
    /// resync must re-broadcast the current media state INCLUDING `held` so a peer
    /// that missed the original `held:true` broadcast converges. Mirrors Web/Android.
    func testPostReconnectResyncReBroadcastsHeldState() async {
        let harness = SessionTestHarness(handlesReconnection: true)
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        // Hold the call (broadcasts held:true once, locally).
        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }
        let broadcastsAfterHold = harness.fakeProvider
            .broadcastMessages(ofType: "participant_media_state").count

        // Transport drops then reconnects without a fresh `joined` (the provider
        // handles reconnection). This arms the post-reconnect resync gate.
        harness.fakeProvider.simulateDisconnected(reason: "test-drop")
        await yieldToMainActor()
        harness.fakeProvider.simulateConnected()
        await yieldToMainActor()
        XCTAssertTrue(harness.session.isPostReconnectResyncPending,
                      "Reconnect without a fresh joined must arm the resync gate")

        // The room_state snapshot flushes the resync.
        harness.simulateRoomState(
            participants: [
                (cid: "local", joinedAt: 1),
                (cid: "remote", joinedAt: 2)
            ],
            hostCid: "local"
        )
        await yieldToMainActor()
        await waitUntil { !harness.session.isPostReconnectResyncPending }

        let broadcasts = harness.fakeProvider.broadcastMessages(ofType: "participant_media_state")
        XCTAssertGreaterThan(broadcasts.count, broadcastsAfterHold,
                             "Post-reconnect resync must re-broadcast media state")
        let last = lastBroadcastMediaState(harness)
        XCTAssertEqual(last?.held, true,
                       "The resync re-broadcast must carry the current held:true")
        XCTAssertEqual(last?.audioEnabled, false, "Held re-broadcast reports audio off")
        XCTAssertEqual(last?.videoEnabled, false, "Held re-broadcast reports video off")
        harness.tearDown()
    }

    // MARK: - Inbound participant_media_state with held

    func testInboundHeldUpdatesRemoteParticipant() async {
        let harness = await makeInCallHarness()
        harness.fakeProvider.simulateMessage(
            from: "remote",
            type: "participant_media_state",
            payload: [
                "from": .string("remote"),
                "audioEnabled": .bool(false),
                "videoEnabled": .bool(false),
                "held": .bool(true)
            ]
        )
        await yieldToMainActor()

        let remote = harness.session.state.remoteParticipants.first(where: { $0.cid == "remote" })
        XCTAssertEqual(remote?.held, true, "Inbound held:true must surface on the remote participant")
        XCTAssertEqual(remote?.audioEnabled, false)
        harness.tearDown()
    }

    // MARK: - Unknown-field decode (lenient)

    func testMediaStateDecodesWithHeldField() {
        let payload = JSONValue.object([
            "from": .string("peer-1"),
            "audioEnabled": .bool(false),
            "videoEnabled": .bool(false),
            "held": .bool(true),
            "someFutureField": .string("ignored")
        ])
        let decoded = MediaStatePayload(from: payload)
        XCTAssertEqual(decoded.fromCid, "peer-1")
        XCTAssertEqual(decoded.audioEnabled, false)
        XCTAssertEqual(decoded.videoEnabled, false)
        XCTAssertEqual(decoded.held, true)
    }

    func testMediaStateDecodesWithoutHeldField() {
        let payload = JSONValue.object([
            "from": .string("peer-1"),
            "audioEnabled": .bool(true),
            "videoEnabled": .bool(true)
        ])
        let decoded = MediaStatePayload(from: payload)
        XCTAssertEqual(decoded.fromCid, "peer-1")
        XCTAssertEqual(decoded.audioEnabled, true)
        XCTAssertEqual(decoded.videoEnabled, true)
        XCTAssertNil(decoded.held, "Absent held must decode as nil (no change), not a crash")
    }

    // MARK: - FIX N1: held-toggle guard (Core Invariant 2: a held call owns NO capture)

    /// While held, mic/video toggles must update desired intent ONLY — no capturer
    /// restart (`toggleVideo`/`toggleAudio` on the engine), no
    /// `participant_media_state` broadcast — and the intent must be applied on
    /// resume. This is the iOS round-2 fix for the missing held guard.
    func testTogglingAudioAndVideoWhileHeldUpdatesDesiredOnlyAndAppliesOnResume() async {
        let harness = await makeInCallHarness()

        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }

        // Snapshot the engine + broadcast counters AFTER hold settled.
        let toggleVideoBefore = harness.fakeMedia.toggleVideoCalls.count
        let toggleAudioBefore = harness.fakeMedia.toggleAudioCalls.count
        let micMutedCoordinatorBefore = harness.fakeAudioCoordinator.micMutedValues.count
        let broadcastsBefore = harness.fakeProvider
            .broadcastMessages(ofType: "participant_media_state").count

        // User toggles while held: mute the mic, and turn video OFF (off needs no
        // permission, so the assertion is camera-availability independent).
        harness.session.setMicMuted(true)
        harness.session.setVideoEnabled(false)
        await yieldToMainActor()

        // No capturer was touched and nothing was broadcast.
        XCTAssertEqual(harness.fakeMedia.toggleVideoCalls.count, toggleVideoBefore,
                       "A video toggle while held must NOT restart/stop the capturer")
        XCTAssertEqual(harness.fakeMedia.toggleAudioCalls.count, toggleAudioBefore,
                       "A mute toggle while held must NOT touch the audio track")
        XCTAssertEqual(harness.fakeAudioCoordinator.micMutedValues.count, micMutedCoordinatorBefore,
                       "A mute toggle while held must NOT call the coordinator")
        XCTAssertEqual(
            harness.fakeProvider.broadcastMessages(ofType: "participant_media_state").count,
            broadcastsBefore,
            "Toggles while held must NOT broadcast participant_media_state")

        // actual* stays false while held regardless of desired intent.
        XCTAssertFalse(harness.session.actualAudioPublished)
        XCTAssertFalse(harness.session.actualVideoPublished)
        XCTAssertEqual(harness.session.mediaRole, .held,
                       "Toggles while held must not leave the held role")

        // The desired intent is applied on resume: mic stays muted, video stays off.
        harness.session.applyForegroundRoleInternal()
        await waitUntil { harness.session.mediaRole == .foreground }

        let resume = harness.fakeMedia.resumeLocalMediaFromHoldCalls.first
        XCTAssertEqual(resume?.audioEnabled, false,
                       "Resume must honor the mute requested while held")
        XCTAssertNil(resume?.videoMode ?? nil,
                     "Resume must honor video-off requested while held")
        harness.tearDown()
    }

    /// A camera-mode flip while held must advance the DESIRED mode (so resume
    /// reacquires in the chosen mode) without engaging the capturer.
    func testFlipCameraWhileHeldUpdatesDesiredModeWithoutCapture() async {
        let harness = await makeInCallHarness()
        // This call needs at least two camera modes to cycle; skip cleanly if the
        // test environment exposes a single mode.
        let modes = harness.session.state.localParticipant.availableCameraModes
        guard modes.count >= 2 else {
            harness.tearDown()
            return
        }

        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }
        let toggleVideoBefore = harness.fakeMedia.toggleVideoCalls.count
        let modeBefore = harness.session.state.localParticipant.cameraMode

        harness.session.flipCamera()
        await yieldToMainActor()

        XCTAssertNotEqual(harness.session.state.localParticipant.cameraMode, modeBefore,
                          "Flip while held must advance the desired camera mode in state")
        XCTAssertEqual(harness.fakeMedia.toggleVideoCalls.count, toggleVideoBefore,
                       "Flip while held must not restart the capturer")
        harness.tearDown()
    }

    // MARK: - FIX N2: stale-activation race fence

    /// A hold requested while a resume is in flight (the audio coordinator is
    /// activating) must win: the session ends `.held`, foreground is never
    /// committed, and no `held:false` broadcast is sent. The stale activation
    /// completion must bail (generation mismatch), not clobber the requested hold.
    func testHoldDuringInFlightResumeStaysHeldAndDoesNotBroadcastHeldFalse() async {
        let gated = GatedAudioCoordinator()
        let config = SerenadaConfig(
            signalingProvider: FakeSignalingProvider(),
            audioCoordinator: gated
        )
        let harness = SessionTestHarness(config: config)
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        // Hold first (settles to held; the initial-join activation already passed
        // through the gate ungated).
        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }

        let broadcastsAfterHold = harness.fakeProvider
            .broadcastMessages(ofType: "participant_media_state").count

        // Arm the gate so the NEXT activation blocks, then start a resume. The
        // resume sets `.activating` and awaits the coordinator inside the gate.
        gated.blockNextActivation = true
        harness.session.applyForegroundRoleInternal()
        await waitUntil { gated.activationInFlight }
        XCTAssertEqual(harness.session.mediaActivationState, .activating,
                       "Resume must be mid-activation before the hold races in")
        XCTAssertEqual(harness.session.mediaRole, .held,
                       "Role stays held until activation commits foreground")

        // A hold lands DURING activation. It bumps the op generation and drives to
        // held; the in-flight resume must then bail when the gate releases.
        harness.session.applyHeldRoleInternal()
        await yieldToMainActor()

        // Release the blocked activation; the stale completion should bail.
        gated.releaseActivation()
        await yieldToMainActor()
        await yieldToMainActor()

        XCTAssertEqual(harness.session.mediaRole, .held,
                       "A hold during an in-flight resume must leave the session held")
        XCTAssertEqual(harness.session.mediaActivationState, .inactive,
                       "Superseded activation must not leave the call .active/.activating")
        XCTAssertFalse(harness.session.actualAudioPublished)
        XCTAssertFalse(harness.session.actualVideoPublished)

        // No `held:false` broadcast: the last media-state broadcast must be held:true.
        let last = lastBroadcastMediaState(harness)
        XCTAssertEqual(last?.held, true,
                       "Superseded resume must NOT broadcast held:false")
        // The resume must not have reacquired local media (it bailed pre-commit).
        XCTAssertTrue(harness.fakeMedia.resumeLocalMediaFromHoldCalls.isEmpty,
                      "Superseded resume must not reacquire local media")
        XCTAssertGreaterThanOrEqual(
            harness.fakeProvider.broadcastMessages(ofType: "participant_media_state").count,
            broadcastsAfterHold,
            "No spurious held:false broadcast from the superseded resume")
        harness.tearDown()
    }

    // MARK: - FIX M1: screen share refused while held (Core Invariant 2)

    /// A held call owns NO screen share. `startScreenShare()` while held must be a
    /// pure no-op: no ReplayKit/engine start, no `content_state`, and no
    /// `participant_media_state` broadcast. Screen share is foreground-only and is
    /// not auto-restored on resume.
    func testStartScreenShareWhileHeldIsNoOp() async {
        // Independent-content mode keeps the camera preference untouched, so the
        // assertion is camera-availability independent.
        let config = SerenadaConfig(
            signalingProvider: FakeSignalingProvider(),
            enableIndependentContentVideo: true
        )
        let harness = SessionTestHarness(config: config)
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }

        let startCallsBefore = harness.fakeMedia.startScreenShareCalls
        let broadcastsBefore = harness.fakeProvider.broadcastMessages(ofType: "participant_media_state").count
        let contentBroadcastsBefore = harness.fakeProvider.broadcastMessages(ofType: "content_state").count

        harness.session.startScreenShare()
        await yieldToMainActor()

        XCTAssertEqual(harness.fakeMedia.startScreenShareCalls, startCallsBefore,
                       "startScreenShare while held must NOT start ReplayKit capture")
        XCTAssertFalse(harness.session.diagnostics.isScreenSharing,
                       "startScreenShare while held must NOT flip the screen-sharing flag")
        XCTAssertEqual(
            harness.fakeProvider.broadcastMessages(ofType: "participant_media_state").count,
            broadcastsBefore,
            "startScreenShare while held must NOT broadcast participant_media_state")
        XCTAssertEqual(
            harness.fakeProvider.broadcastMessages(ofType: "content_state").count,
            contentBroadcastsBefore,
            "startScreenShare while held must NOT broadcast content_state")
        XCTAssertEqual(harness.session.mediaRole, .held,
                       "startScreenShare while held must leave the role held")
        harness.tearDown()
    }

    // MARK: - FIX M2: toggleVideo is desired-relative while held

    /// `toggleVideo()` while held must derive its target from DESIRED intent, not
    /// from `localParticipant.videoEnabled` (forced false while held). A held call
    /// whose desired video is ON must toggle desired video OFF — not redundantly
    /// turn it back on. Matches Web/Android.
    func testToggleVideoWhileHeldWithDesiredVideoOnFlipsDesiredOff() async {
        let harness = await makeInCallHarness()
        // This fix only matters when the call's desired video is ON before hold.
        // The join default is video-on when a camera is available; if the test
        // environment has no camera (desired video off), the desired-relative
        // toggle is not exercised, so skip cleanly.
        guard harness.session.state.localParticipant.videoEnabled else {
            harness.tearDown()
            return
        }
        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }
        let toggleVideoEngineBefore = harness.fakeMedia.toggleVideoCalls.count

        // Toggle while held: desired video should flip OFF.
        harness.session.toggleVideo()
        await yieldToMainActor()

        XCTAssertEqual(harness.fakeMedia.toggleVideoCalls.count, toggleVideoEngineBefore,
                       "toggleVideo while held must NOT touch the capturer")
        XCTAssertEqual(harness.session.mediaRole, .held,
                       "toggleVideo while held must not leave the held role")

        // Resume: the desired-off intent must be honored (camera not reacquired).
        harness.session.applyForegroundRoleInternal()
        await waitUntil { harness.session.mediaRole == .foreground }

        let resume = harness.fakeMedia.resumeLocalMediaFromHoldCalls.first
        XCTAssertNil(resume?.videoMode ?? nil,
                     "toggleVideo while held (desired-on) must flip desired video OFF, so resume reacquires no camera")
        harness.tearDown()
    }

    // MARK: - FIX M3: resume emits a single held:false (no intermediate held:true)

    /// Resume must emit exactly ONE `participant_media_state` broadcast, with
    /// `held:false`, AFTER media has resumed and the role is committed
    /// `.foreground`. No intermediate `held:true` may be broadcast after capture
    /// is reacquired.
    func testResumeEmitsSingleHeldFalseWithNoIntermediateHeldTrue() async {
        let harness = await makeInCallHarness()
        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }
        let broadcastsBeforeResume = harness.fakeProvider
            .broadcastMessages(ofType: "participant_media_state").count

        harness.session.applyForegroundRoleInternal()
        await waitUntil { harness.session.mediaRole == .foreground }
        await yieldToMainActor()
        await harness.fakeClock.advance(byMs: 50)
        await yieldToMainActor()

        let all = harness.fakeProvider.broadcastMessages(ofType: "participant_media_state")
        let resumeBroadcasts = Array(all.suffix(from: broadcastsBeforeResume))
        let heldValues = resumeBroadcasts.map { $0.payload?["held"]?.boolValue }

        XCTAssertEqual(resumeBroadcasts.count, 1,
                       "Resume must emit exactly one media-state broadcast, got held sequence \(heldValues)")
        XCTAssertEqual(heldValues, [false],
                       "Resume's single broadcast must be held:false (no intermediate held:true after capture reacquired)")
        harness.tearDown()
    }

    // MARK: - FIX N4: media-SINK held guard (non-toggle reacquire while held)

    /// Core Invariant 2: a held call owns NO capture via ANY path. The user
    /// toggles (`setMicMuted`/`setVideoEnabled`) are guarded, but the engine SINKS
    /// (`updateEffectiveMicState` → `toggleAudio`, `applyLocalVideoPreference` →
    /// `toggleVideo`) are ALSO reached from audio-environment callbacks (proximity
    /// / route change / external-audio start-end / audio-session restart) that fire
    /// OUTSIDE the toggle guards. Those must NOT re-enable mic capture or restart
    /// camera capture on a held call. The video sink already guards at the sink;
    /// this asserts the mic sink does too (the iOS equivalent of the Android gap).
    /// Drives both sinks directly, mirroring the Android `audio-environment callback
    /// while held` test (the natural callback path is `sessionActivated`-gated, so a
    /// faithful held-guard test invokes the media-applying sink itself).
    func testMediaSinksDoNotReacquireCaptureWhileHeld() async {
        let harness = await makeInCallHarness()

        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }

        // Snapshot engine + broadcast counters AFTER hold settled.
        let toggleAudioBefore = harness.fakeMedia.toggleAudioCalls.count
        let toggleVideoBefore = harness.fakeMedia.toggleVideoCalls.count
        let broadcastsBefore = harness.fakeProvider
            .broadcastMessages(ofType: "participant_media_state").count

        // Simulate an audio-environment callback reaching BOTH media sinks while
        // held. An unguarded mic sink would call toggleAudio(true) (desired audio
        // is on by join default) and reacquire mic capture on the held call.
        harness.session.applyLocalVideoPreference()
        harness.session.updateEffectiveMicState()
        await yieldToMainActor()

        XCTAssertEqual(harness.fakeMedia.toggleAudioCalls.count, toggleAudioBefore,
                       "A media-sink invocation while held must NOT touch the mic track (no capture reacquire)")
        XCTAssertEqual(harness.fakeMedia.toggleVideoCalls.count, toggleVideoBefore,
                       "A media-sink invocation while held must NOT restart camera capture")
        XCTAssertEqual(
            harness.fakeProvider.broadcastMessages(ofType: "participant_media_state").count,
            broadcastsBefore,
            "A media-sink invocation while held must NOT broadcast participant_media_state")
        XCTAssertFalse(harness.session.actualAudioPublished,
                       "A held call must keep actualAudioPublished false through a sink invocation")
        XCTAssertFalse(harness.session.actualVideoPublished,
                       "A held call must keep actualVideoPublished false through a sink invocation")
        XCTAssertFalse(harness.session.state.localParticipant.audioEnabled,
                       "A held call must keep local audioEnabled false through a sink invocation")
        XCTAssertEqual(harness.session.mediaRole, .held,
                       "A media-sink invocation while held must not leave the held role")

        // Desired intent survives: resume reacquires the mic per the preserved
        // desired-audio-on intent (the sink did not clobber desiredAudioEnabled).
        harness.session.applyForegroundRoleInternal()
        await waitUntil { harness.session.mediaRole == .foreground }
        let resume = harness.fakeMedia.resumeLocalMediaFromHoldCalls.first
        XCTAssertEqual(resume?.audioEnabled, true,
                       "Resume must reacquire mic per the preserved desired-audio-on intent")
        harness.tearDown()
    }

    // MARK: - FIX N5: hold of a screen-sharing call broadcasts content_state:false

    /// Hold stops an active screen share. Stopping must route through the normal
    /// stop-screenshare path so peers receive `content_state` active:false and
    /// clear the stale active content (the Android-flagged gap; iOS already routes
    /// hold's stop through `stopScreenShare()` — this locks that in).
    func testHoldOfScreenSharingCallBroadcastsContentStateFalse() async {
        let config = SerenadaConfig(
            signalingProvider: FakeSignalingProvider(),
            screenShareMode: .inAppOnly
        )
        let harness = SessionTestHarness(config: config)
        await harness.advanceToInCallWithTurn(
            localCid: "local",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        // The fake confirms the share start (flips `isScreenSharing` true and emits
        // content_state active:true), modeling a real in-progress screen share.
        harness.fakeMedia.startScreenShareResult = true
        harness.session.startScreenShare()
        await waitUntil { harness.session.diagnostics.isScreenSharing }
        XCTAssertTrue(harness.session.diagnostics.isScreenSharing,
                      "Screen share must be active before hold")
        let contentBefore = harness.fakeProvider.broadcastMessages(ofType: "content_state").count

        harness.session.applyHeldRoleInternal()
        await waitUntil { harness.session.mediaRole == .held }
        // Hold's `stopScreenShare()` fires the engine's `onScreenShareStopped`
        // callback on a `@MainActor` Task, which clears `isScreenSharing` and emits
        // the content_state:false broadcast — wait for that async stop to land.
        await waitUntil { !harness.session.diagnostics.isScreenSharing }

        let contentStates = harness.fakeProvider.broadcastMessages(ofType: "content_state")
        XCTAssertGreaterThan(contentStates.count, contentBefore,
                             "Hold of a screen-sharing call must broadcast content_state")
        XCTAssertEqual(contentStates.last?.payload?["active"]?.boolValue, false,
                       "Hold must broadcast content_state active:false so peers clear stale content")
        XCTAssertFalse(harness.session.diagnostics.isScreenSharing,
                       "Hold must clear the screen-sharing diagnostic")
        harness.tearDown()
    }
}

/// Test audio coordinator whose `activateCallSession` can be PAUSED so a test can
/// interpose a hold while a resume is mid-activation (FIX N2 race fence). The
/// initial join activation runs ungated; set `blockNextActivation` before the
/// resume to pause exactly that one, then `releaseActivation()` to let it finish.
@MainActor
final class GatedAudioCoordinator: SerenadaAudioCoordinator, @unchecked Sendable {
    var blockNextActivation = false
    private(set) var activationInFlight = false
    private var continuation: CheckedContinuation<Void, Never>?
    private(set) var activateCalls = 0
    private(set) var deactivateCalls = 0

    func activateCallSession(intent: AudioIntent) async throws {
        activateCalls += 1
        guard blockNextActivation else { return }
        blockNextActivation = false
        activationInFlight = true
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            self.continuation = c
        }
        activationInFlight = false
    }

    func releaseActivation() {
        let c = continuation
        continuation = nil
        c?.resume()
    }

    func deactivateCallSession() async { deactivateCalls += 1 }
    func applyRouting(_ device: AudioDevice) async throws {}
    func setMicMuted(_ muted: Bool) async throws {}

    var availableDevices: AsyncStream<[AudioDevice]> { AsyncStream { _ in } }
    var effectiveInputDevice: AsyncStream<AudioDevice?> { AsyncStream { _ in } }
    var effectiveOutputDevice: AsyncStream<AudioDevice?> { AsyncStream { _ in } }
    var events: AsyncStream<AudioCoordinatorEvent> { AsyncStream { _ in } }
}
