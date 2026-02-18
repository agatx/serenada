import XCTest
@testable import SerenadaiOS

final class CallScreenStateTests: XCTestCase {
    func testWaitingStateShowsSingleWaitingMessagePath() {
        XCTAssertFalse(
            shouldShowCallStatusLabel(
                phase: .waiting,
                isSignalingConnected: true,
                iceConnectionState: "CONNECTED",
                connectionState: "CONNECTED"
            )
        )
        XCTAssertTrue(shouldShowWaitingOverlay(phase: .waiting))
    }

    func testStatusLabelShownOnlyWhenInCallAndReconnecting() {
        XCTAssertFalse(
            shouldShowCallStatusLabel(
                phase: .inCall,
                isSignalingConnected: true,
                iceConnectionState: "CONNECTED",
                connectionState: "CONNECTED"
            )
        )

        XCTAssertTrue(
            shouldShowCallStatusLabel(
                phase: .inCall,
                isSignalingConnected: false,
                iceConnectionState: "CONNECTED",
                connectionState: "CONNECTED"
            )
        )
    }

    func testLocalPlaceholderShownWhenLocalVideoDisabled() {
        XCTAssertTrue(shouldShowLocalVideoPlaceholder(localVideoEnabled: false))
        XCTAssertFalse(shouldShowLocalVideoPlaceholder(localVideoEnabled: true))
    }

    func testRemotePlaceholderShownOnlyDuringInCallWithoutRemoteTrack() {
        XCTAssertFalse(shouldShowRemoteVideoPlaceholder(phase: .waiting, remoteVideoEnabled: false))
        XCTAssertTrue(shouldShowRemoteVideoPlaceholder(phase: .inCall, remoteVideoEnabled: false))

        XCTAssertFalse(shouldShowRemoteVideoPlaceholder(phase: .idle, remoteVideoEnabled: false))
        XCTAssertFalse(shouldShowRemoteVideoPlaceholder(phase: .joining, remoteVideoEnabled: false))
        XCTAssertFalse(shouldShowRemoteVideoPlaceholder(phase: .inCall, remoteVideoEnabled: true))
    }
}
