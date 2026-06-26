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
}
