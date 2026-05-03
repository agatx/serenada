@testable import SerenadaCallUI
import SerenadaCore
import XCTest

final class CallScreenStateTests: XCTestCase {
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
        XCTAssertTrue(shouldShowRemoteFitButton(phase: .inCall, remoteVideoEnabled: true, isLocalLarge: false, localVideoEnabled: true))
        XCTAssertFalse(shouldShowRemoteFitButton(phase: .inCall, remoteVideoEnabled: false, isLocalLarge: false, localVideoEnabled: true))
        XCTAssertFalse(shouldShowRemoteFitButton(phase: .inCall, remoteVideoEnabled: true, isLocalLarge: true, localVideoEnabled: true))
        XCTAssertFalse(shouldShowRemoteFitButton(phase: .waiting, remoteVideoEnabled: true, isLocalLarge: false, localVideoEnabled: true))
        // Local camera off forces remote-as-primary, so the fit button should appear even if isLocalLarge is true.
        XCTAssertTrue(shouldShowRemoteFitButton(phase: .inCall, remoteVideoEnabled: true, isLocalLarge: true, localVideoEnabled: false))
    }

    func testWaitingAndInCallRenderLocalAsPrimarySurfaceWhenExpanded() {
        XCTAssertFalse(shouldRenderLocalAsPrimarySurface(phase: .waiting, isLocalLarge: false, localVideoEnabled: true))
        XCTAssertTrue(shouldRenderLocalAsPrimarySurface(phase: .waiting, isLocalLarge: true, localVideoEnabled: true))
        XCTAssertFalse(shouldRenderLocalAsPrimarySurface(phase: .inCall, isLocalLarge: false, localVideoEnabled: true))
        XCTAssertTrue(shouldRenderLocalAsPrimarySurface(phase: .inCall, isLocalLarge: true, localVideoEnabled: true))
        // Local camera off should never render local as primary regardless of swap preference.
        XCTAssertFalse(shouldRenderLocalAsPrimarySurface(phase: .waiting, isLocalLarge: true, localVideoEnabled: false))
        XCTAssertFalse(shouldRenderLocalAsPrimarySurface(phase: .inCall, isLocalLarge: true, localVideoEnabled: false))
    }

    func testLargeLocalPreviewPreferenceFollowsCameraMode() {
        XCTAssertTrue(shouldPreferLargeLocalPreview(localCameraMode: .world))
        XCTAssertTrue(shouldPreferLargeLocalPreview(localCameraMode: .composite))
        XCTAssertFalse(shouldPreferLargeLocalPreview(localCameraMode: .selfie))
        XCTAssertFalse(shouldPreferLargeLocalPreview(localCameraMode: .screenShare))
    }

    func testPinchZoomAllowedForLargeWorldAndCompositePreview() {
        XCTAssertTrue(
            shouldEnablePinchZoom(
                phase: .waiting,
                isScreenSharing: false,
                showLocalAsPrimarySurface: true,
                localCameraMode: .world
            )
        )
        XCTAssertTrue(
            shouldEnablePinchZoom(
                phase: .inCall,
                isScreenSharing: false,
                showLocalAsPrimarySurface: true,
                localCameraMode: .composite
            )
        )
        XCTAssertFalse(
            shouldEnablePinchZoom(
                phase: .waiting,
                isScreenSharing: false,
                showLocalAsPrimarySurface: true,
                localCameraMode: .selfie
            )
        )
        XCTAssertFalse(
            shouldEnablePinchZoom(
                phase: .inCall,
                isScreenSharing: false,
                showLocalAsPrimarySurface: false,
                localCameraMode: .world
            )
        )
        XCTAssertFalse(
            shouldEnablePinchZoom(
                phase: .inCall,
                isScreenSharing: true,
                showLocalAsPrimarySurface: true,
                localCameraMode: .world
            )
        )
    }

    func testPipBottomPaddingUsesLowerOffsetsInLandscape() {
        XCTAssertEqual(pipBottomPadding(isLandscape: true, areControlsVisible: true), 80)
        XCTAssertEqual(pipBottomPadding(isLandscape: true, areControlsVisible: false), 24)
        XCTAssertEqual(pipBottomPadding(isLandscape: false, areControlsVisible: true), 140)
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
