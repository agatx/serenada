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
}
