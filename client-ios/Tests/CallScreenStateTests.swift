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

    func testRemoteFitButtonShownOnlyWhenRemoteIsMainSurface() {
        XCTAssertTrue(shouldShowRemoteFitButton(phase: .inCall, remoteVideoEnabled: true, isLocalLarge: false))
        XCTAssertFalse(shouldShowRemoteFitButton(phase: .inCall, remoteVideoEnabled: false, isLocalLarge: false))
        XCTAssertFalse(shouldShowRemoteFitButton(phase: .inCall, remoteVideoEnabled: true, isLocalLarge: true))
        XCTAssertFalse(shouldShowRemoteFitButton(phase: .waiting, remoteVideoEnabled: true, isLocalLarge: false))
    }

    func testOnlyInCallRendersLocalAsPrimarySurfaceWhenExpanded() {
        XCTAssertFalse(shouldRenderLocalAsPrimarySurface(phase: .waiting, isLocalLarge: false))
        XCTAssertFalse(shouldRenderLocalAsPrimarySurface(phase: .waiting, isLocalLarge: true))
        XCTAssertFalse(shouldRenderLocalAsPrimarySurface(phase: .inCall, isLocalLarge: false))
        XCTAssertTrue(shouldRenderLocalAsPrimarySurface(phase: .inCall, isLocalLarge: true))
    }

    func testPipBottomPaddingUsesLowerOffsetsInLandscape() {
        XCTAssertEqual(pipBottomPadding(isLandscape: true, areControlsVisible: true), 92)
        XCTAssertEqual(pipBottomPadding(isLandscape: true, areControlsVisible: false), 24)
        XCTAssertEqual(pipBottomPadding(isLandscape: false, areControlsVisible: true), 170)
        XCTAssertEqual(pipBottomPadding(isLandscape: false, areControlsVisible: false), 52)
    }
}
