@testable import SerenadaCallUI
import SerenadaCore
import XCTest

final class CallScreenStateTests: XCTestCase {
    func testOnlyHostTerminatesRoomOnEndTap() {
        XCTAssertTrue(shouldTerminateRoomOnEndTap(isHost: true))
        XCTAssertFalse(shouldTerminateRoomOnEndTap(isHost: false))
    }

    func testPrimaryLocalVideoContentModeUsesFitForWorldAndComposite() {
        XCTAssertEqual(primaryLocalVideoContentMode(localCameraMode: .world), .scaleAspectFit)
        XCTAssertEqual(primaryLocalVideoContentMode(localCameraMode: .composite), .scaleAspectFit)
        XCTAssertEqual(primaryLocalVideoContentMode(localCameraMode: .selfie), .scaleAspectFill)
        XCTAssertEqual(primaryLocalVideoContentMode(localCameraMode: .screenShare), .scaleAspectFill)
    }

    func testWaitingStateShowsSingleWaitingMessagePath() {
        XCTAssertFalse(
            shouldShowCallStatusLabel(
                phase: .waiting,
                connectionStatus: .connected
            )
        )
        XCTAssertTrue(shouldShowWaitingOverlay(phase: .waiting))
    }

    func testStatusLabelShownOnlyWhenInCallAndReconnecting() {
        XCTAssertFalse(
            shouldShowCallStatusLabel(
                phase: .inCall,
                connectionStatus: .connected
            )
        )

        XCTAssertTrue(
            shouldShowCallStatusLabel(
                phase: .inCall,
                connectionStatus: .recovering
            )
        )

        XCTAssertTrue(
            shouldShowCallStatusLabel(
                phase: .inCall,
                connectionStatus: .retrying
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

    func testBroadcastPickerShownOnlyWhenExtensionShareCanStart() {
        XCTAssertTrue(
            shouldUseBroadcastPicker(
                isScreenSharing: false,
                screenShareExtensionBundleId: "app.serenada.ios.broadcast"
            )
        )
        XCTAssertFalse(
            shouldUseBroadcastPicker(
                isScreenSharing: true,
                screenShareExtensionBundleId: "app.serenada.ios.broadcast"
            )
        )
        XCTAssertFalse(
            shouldUseBroadcastPicker(
                isScreenSharing: false,
                screenShareExtensionBundleId: nil
            )
        )
        XCTAssertFalse(
            shouldUseBroadcastPicker(
                isScreenSharing: false,
                screenShareExtensionBundleId: ""
            )
        )
    }
}
