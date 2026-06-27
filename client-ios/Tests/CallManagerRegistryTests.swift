import SerenadaCore
import XCTest
@testable import SerenadaiOS

/// Host-app coverage for the registry-backed `CallManager` derivations that drive
/// the multi-call switcher (Phase 5 iOS integration). These exercise the pure
/// selection/mapping logic without spinning up a real `SerenadaCallRegistry`, so
/// the "which calls show as chips" and per-call error mapping are regression-safe.
@MainActor
final class CallManagerRegistryTests: XCTestCase {

    private func makeCall(
        id: CallId,
        roomId: String,
        phase: SerenadaCallPhase = .waiting,
        mediaRole: CallMediaRole = .held,
        activationError: CallActivationError? = nil
    ) -> ManagedCallState {
        ManagedCallState(
            id: id,
            roomId: roomId,
            roomUrl: nil,
            membershipPhase: phase,
            mediaRole: mediaRole,
            mediaActivationState: mediaRole == .foreground ? .active : .inactive,
            desiredAudioEnabled: true,
            desiredVideoMode: nil,
            actualAudioPublished: mediaRole == .foreground,
            actualVideoPublished: false,
            participantCount: 1,
            localCid: nil,
            held: mediaRole == .held,
            displayName: nil,
            activationError: activationError,
            qualitySummary: nil
        )
    }

    // MARK: heldCalls selection

    func testHeldCallsExcludesActiveCall() {
        let active = makeCall(id: "a", roomId: "room-a", mediaRole: .foreground)
        let held = makeCall(id: "b", roomId: "room-b", mediaRole: .held)

        let result = CallManager.heldCalls(from: [active, held], activeCallId: "a")

        XCTAssertEqual(result.map(\.id), ["b"])
    }

    func testHeldCallsExcludesEndedAndFailedCalls() {
        let held = makeCall(id: "live", roomId: "room-live")
        let endedIdle = makeCall(id: "ended", roomId: "room-ended", phase: .idle)
        let endedError = makeCall(id: "err", roomId: "room-err", phase: .error)
        let failed = makeCall(
            id: "failed",
            roomId: "room-failed",
            activationError: .joinFailed("boom")
        )

        let result = CallManager.heldCalls(
            from: [held, endedIdle, endedError, failed],
            activeCallId: nil
        )

        XCTAssertEqual(result.map(\.id), ["live"])
    }

    func testHeldCallsEmptyForCommonSingleCallCase() {
        // One foreground call, no held calls -> switcher source is empty.
        let active = makeCall(id: "only", roomId: "room", mediaRole: .foreground)

        let result = CallManager.heldCalls(from: [active], activeCallId: "only")

        XCTAssertTrue(result.isEmpty)
    }

    func testHeldCallsPreservesRegistryOrder() {
        let a = makeCall(id: "a", roomId: "room-a")
        let active = makeCall(id: "b", roomId: "room-b", mediaRole: .foreground)
        let c = makeCall(id: "c", roomId: "room-c")

        let result = CallManager.heldCalls(from: [a, active, c], activeCallId: "b")

        XCTAssertEqual(result.map(\.id), ["a", "c"])
    }

    // MARK: isEndedPhase

    func testIsEndedPhase() {
        XCTAssertTrue(CallManager.isEndedPhase(.idle))
        XCTAssertTrue(CallManager.isEndedPhase(.error))
        XCTAssertFalse(CallManager.isEndedPhase(.joining))
        XCTAssertFalse(CallManager.isEndedPhase(.awaitingPermissions))
        XCTAssertFalse(CallManager.isEndedPhase(.waiting))
        XCTAssertFalse(CallManager.isEndedPhase(.inCall))
        XCTAssertFalse(CallManager.isEndedPhase(.ending))
    }

    // MARK: callActivationErrorMessage

    func testActivationErrorMessageUsesProvidedDetailWhenPresent() {
        XCTAssertEqual(
            CallManager.callActivationErrorMessage(.activationFailed("custom detail")),
            "custom detail"
        )
        XCTAssertEqual(
            CallManager.callActivationErrorMessage(.joinFailed("join detail")),
            "join detail"
        )
    }

    func testActivationErrorMessageFallsBackForEmptyDetail() {
        XCTAssertEqual(
            CallManager.callActivationErrorMessage(.activationFailed("")),
            L10n.errorUnknown
        )
        XCTAssertEqual(
            CallManager.callActivationErrorMessage(.joinFailed("")),
            L10n.callStatusConnectionFailed
        )
    }

    func testActivationErrorMessageForConnectionStyleErrors() {
        XCTAssertEqual(
            CallManager.callActivationErrorMessage(.needsPermission([.microphone])),
            L10n.callStatusConnectionFailed
        )
        XCTAssertEqual(
            CallManager.callActivationErrorMessage(.releaseTimedOut),
            L10n.callStatusConnectionFailed
        )
        XCTAssertEqual(
            CallManager.callActivationErrorMessage(.leaseUnavailable),
            L10n.callStatusConnectionFailed
        )
    }

    // MARK: callSurvivesFailure (FIX P5-2)

    func testSwitchFailureKeepsActiveUiWhenActiveCallSurvives() {
        // Registry rolled the previous active call back to foreground: a live call
        // still exists, so the host must KEEP the active UI (banner, not error).
        XCTAssertTrue(
            CallManager.callSurvivesFailure(activeCallId: "old", liveHeldCount: 0)
        )
        XCTAssertTrue(
            CallManager.callSurvivesFailure(activeCallId: "old", liveHeldCount: 2)
        )
    }

    func testSwitchFailureKeepsUiWhenOnlyHeldCallsSurvive() {
        // No active call, but live held calls remain (e.g. a join-and-switch that
        // failed activation with no prior active call but other held calls present):
        // keep the held surface, do not blow the app to the error screen.
        XCTAssertTrue(
            CallManager.callSurvivesFailure(activeCallId: nil, liveHeldCount: 1)
        )
    }

    func testSwitchFailureFallsToErrorOnlyWhenNothingSurvives() {
        // No active call AND no live held calls: a genuine whole-app failure.
        XCTAssertFalse(
            CallManager.callSurvivesFailure(activeCallId: nil, liveHeldCount: 0)
        )
    }
}
