import SerenadaCallUI
import SerenadaCore
import XCTest
@testable import SerenadaiOS

final class RootViewRoutingTests: XCTestCase {
    func testActiveCallScreenShownOnlyForActiveSessionCallPhases() {
        var state = CallUiState()

        XCTAssertFalse(shouldShowActiveCallScreen(sessionPhase: nil, fallbackUiState: state))

        state.phase = .waiting
        XCTAssertTrue(shouldShowActiveCallScreen(sessionPhase: nil, fallbackUiState: state))

        state.phase = .inCall
        XCTAssertTrue(shouldShowActiveCallScreen(sessionPhase: nil, fallbackUiState: state))

        XCTAssertFalse(shouldShowActiveCallScreen(sessionPhase: .joining, fallbackUiState: state))
        XCTAssertTrue(shouldShowActiveCallScreen(sessionPhase: .awaitingPermissions, fallbackUiState: state))
        XCTAssertTrue(shouldShowActiveCallScreen(sessionPhase: .waiting, fallbackUiState: state))
        XCTAssertTrue(shouldShowActiveCallScreen(sessionPhase: .inCall, fallbackUiState: state))
        XCTAssertTrue(shouldShowActiveCallScreen(sessionPhase: .ending, fallbackUiState: state))
        XCTAssertFalse(shouldShowActiveCallScreen(sessionPhase: .error, fallbackUiState: state))
        XCTAssertFalse(shouldShowActiveCallScreen(sessionPhase: .idle, fallbackUiState: state))
    }

    func testFallbackUiStateHidesCallScreenForNonCallPhases() {
        var state = CallUiState()

        state.phase = .joining
        state.connectionState = "CONNECTED"
        XCTAssertFalse(shouldShowActiveCallScreen(sessionPhase: nil, fallbackUiState: state))

        state.phase = .ending
        state.connectionState = "CONNECTED"
        XCTAssertFalse(shouldShowActiveCallScreen(sessionPhase: nil, fallbackUiState: state))

        state.phase = .idle
        state.connectionState = "CONNECTED"
        XCTAssertFalse(shouldShowActiveCallScreen(sessionPhase: nil, fallbackUiState: state))
    }

    // MARK: rootScreen routing (FIX P5-3)

    func testActiveCallScreenTakesPrecedence() {
        // Even with held calls and an error phase, an active call screen wins.
        XCTAssertEqual(
            rootScreen(showActiveCallScreen: true, uiPhase: .error, hasLiveHeldCalls: true),
            .call
        )
        XCTAssertEqual(
            rootScreen(showActiveCallScreen: true, uiPhase: .inCall, hasLiveHeldCalls: false),
            .call
        )
    }

    func testErrorScreenWhenNoActiveCallAndNoHeldCalls() {
        XCTAssertEqual(
            rootScreen(showActiveCallScreen: false, uiPhase: .error, hasLiveHeldCalls: false),
            .error
        )
    }

    func testHeldSurfaceShownWhenActiveCallEndsWithLiveHeldCalls() {
        // Active call ended (idle) but live held calls remain: show the held
        // surface so they stay reachable, NOT Join (Invariant 5: no auto-promote).
        XCTAssertEqual(
            rootScreen(showActiveCallScreen: false, uiPhase: .idle, hasLiveHeldCalls: true),
            .heldOnly
        )
        XCTAssertEqual(
            rootScreen(showActiveCallScreen: false, uiPhase: .ending, hasLiveHeldCalls: true),
            .heldOnly
        )
    }

    func testErrorTakesPrecedenceOverHeldSurface() {
        // A whole-app error still wins over the held surface.
        XCTAssertEqual(
            rootScreen(showActiveCallScreen: false, uiPhase: .error, hasLiveHeldCalls: true),
            .error
        )
    }

    func testJoinWhenNoActiveCallNoHeldCallsNoError() {
        // Single-call UX preserved: one call ends, no held calls -> Join/idle.
        XCTAssertEqual(
            rootScreen(showActiveCallScreen: false, uiPhase: .idle, hasLiveHeldCalls: false),
            .join
        )
        XCTAssertEqual(
            rootScreen(showActiveCallScreen: false, uiPhase: .joining, hasLiveHeldCalls: false),
            .join
        )
    }
}
