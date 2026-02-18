import XCTest
@testable import SerenadaiOS

final class RootViewRoutingTests: XCTestCase {
    func testActiveCallScreenShownOnlyForWaitingOrInCall() {
        var state = CallUiState()

        state.phase = .waiting
        XCTAssertTrue(shouldShowActiveCallScreen(for: state))

        state.phase = .inCall
        XCTAssertTrue(shouldShowActiveCallScreen(for: state))

        state.phase = .joining
        state.connectionState = "CONNECTED"
        XCTAssertFalse(shouldShowActiveCallScreen(for: state))

        state.phase = .ending
        state.connectionState = "CONNECTED"
        XCTAssertFalse(shouldShowActiveCallScreen(for: state))

        state.phase = .idle
        state.connectionState = "CONNECTED"
        XCTAssertFalse(shouldShowActiveCallScreen(for: state))
    }
}
