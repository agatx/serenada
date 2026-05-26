@testable import SerenadaCallUI
import SerenadaCore
import XCTest

final class CallScreenStateTests: XCTestCase {
    func testRouteOptionsKeepActiveEarpieceWhenBluetoothIsOnlyAvailable() {
        let earpiece = AudioDevice(
            id: "earpiece",
            displayName: "Phone",
            kind: .earpiece,
            direction: .output,
            status: .active
        )
        let bluetooth = AudioDevice(
            id: "airpods",
            displayName: "AirPods",
            kind: .bluetooth(profile: .a2dp),
            direction: .output,
            status: .available
        )

        let options = callAudioRouteOptions(
            currentAudioDevice: earpiece,
            availableAudioDevices: [earpiece, bluetooth, speakerDevice()]
        )

        XCTAssertTrue(options.contains { $0.kind == .earpiece && $0.status == .active })
    }

    func testRouteOptionsHideEarpieceWhenBluetoothIsActive() {
        let earpiece = AudioDevice(
            id: "earpiece",
            displayName: "Phone",
            kind: .earpiece,
            direction: .output,
            status: .available
        )
        let bluetooth = AudioDevice(
            id: "airpods",
            displayName: "AirPods",
            kind: .bluetooth(profile: .a2dp),
            direction: .output,
            status: .active
        )

        let options = callAudioRouteOptions(
            currentAudioDevice: bluetooth,
            availableAudioDevices: [earpiece, bluetooth, speakerDevice()]
        )

        XCTAssertFalse(options.contains { $0.kind == .earpiece })
        XCTAssertTrue(options.contains { $0.kind == .bluetooth(profile: .a2dp) && $0.status == .active })
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

    func testFrontlineWaitingOnlyWhenCallSurfaceHasNoRemoteParticipants() {
        var state = CallUiState()
        state.phase = .waiting
        XCTAssertTrue(frontlineIsWaitingForRemote(state))

        state.remoteParticipants = [
            RemoteParticipant(cid: "r1", videoEnabled: false, connectionState: .new)
        ]
        XCTAssertFalse(frontlineIsWaitingForRemote(state))

        state.phase = .joining
        state.remoteParticipants = []
        XCTAssertFalse(frontlineIsWaitingForRemote(state))
    }

    func testFrontlineLargeLocalPreviewMatchesContentModeAndPipSwap() {
        XCTAssertTrue(frontlineUsesLargeLocalPreview(localVideoEnabled: true, localCameraMode: .world, isScreenSharing: false, pipSwapped: false))
        XCTAssertFalse(frontlineUsesLargeLocalPreview(localVideoEnabled: true, localCameraMode: .world, isScreenSharing: false, pipSwapped: true))
        XCTAssertFalse(frontlineUsesLargeLocalPreview(localVideoEnabled: true, localCameraMode: .selfie, isScreenSharing: false, pipSwapped: false))
        XCTAssertTrue(frontlineUsesLargeLocalPreview(localVideoEnabled: true, localCameraMode: .selfie, isScreenSharing: false, pipSwapped: true))
        XCTAssertTrue(frontlineUsesLargeLocalPreview(localVideoEnabled: true, localCameraMode: .selfie, isScreenSharing: true, pipSwapped: false))
        XCTAssertFalse(frontlineUsesLargeLocalPreview(localVideoEnabled: false, localCameraMode: .world, isScreenSharing: false, pipSwapped: false))
    }

    func testFrontlineZoomEligibilityAllowsWaitingAndInCallContentCameraOnly() {
        XCTAssertTrue(frontlineAllowsLocalCameraZoom(phase: .waiting, localVideoEnabled: true, localCameraMode: .world, isScreenSharing: false))
        XCTAssertTrue(frontlineAllowsLocalCameraZoom(phase: .inCall, localVideoEnabled: true, localCameraMode: .composite, isScreenSharing: false))
        XCTAssertFalse(frontlineAllowsLocalCameraZoom(phase: .joining, localVideoEnabled: true, localCameraMode: .world, isScreenSharing: false))
        XCTAssertFalse(frontlineAllowsLocalCameraZoom(phase: .inCall, localVideoEnabled: true, localCameraMode: .selfie, isScreenSharing: false))
        XCTAssertFalse(frontlineAllowsLocalCameraZoom(phase: .inCall, localVideoEnabled: false, localCameraMode: .world, isScreenSharing: false))
        XCTAssertFalse(frontlineAllowsLocalCameraZoom(phase: .inCall, localVideoEnabled: true, localCameraMode: .world, isScreenSharing: true))
    }

    func testFrontlineStageOmitsNormalLocalTileWhenLocalContentIsInFilmstrip() {
        XCTAssertFalse(
            frontlineIncludesNormalLocalStageTile(
                localSpotlightId: "local",
                activeContentOwnerId: "local",
                contentTileIsSpotlight: false
            )
        )
        XCTAssertTrue(
            frontlineIncludesNormalLocalStageTile(
                localSpotlightId: "local",
                activeContentOwnerId: "local",
                contentTileIsSpotlight: true
            )
        )
        XCTAssertTrue(
            frontlineIncludesNormalLocalStageTile(
                localSpotlightId: "local",
                activeContentOwnerId: "remote",
                contentTileIsSpotlight: false
            )
        )
        XCTAssertTrue(
            frontlineIncludesNormalLocalStageTile(
                localSpotlightId: "local",
                activeContentOwnerId: nil,
                contentTileIsSpotlight: false
            )
        )
    }

    func testFrontlineSnapshotPrefersLocalThenFirstRemoteVideo() {
        let remotes = [
            RemoteParticipant(cid: "r1", videoEnabled: false, connectionState: .new),
            RemoteParticipant(cid: "r2", videoEnabled: true, connectionState: .new)
        ]

        XCTAssertNil(frontlineSnapshotSource(snapshotEnabled: false, localVideoEnabled: true, remoteParticipants: remotes))
        XCTAssertEqual(frontlineSnapshotSource(snapshotEnabled: true, localVideoEnabled: true, remoteParticipants: remotes), .local)
        XCTAssertEqual(frontlineSnapshotSource(snapshotEnabled: true, localVideoEnabled: false, remoteParticipants: remotes), .remote(cid: "r2"))
        XCTAssertNil(frontlineSnapshotSource(snapshotEnabled: true, localVideoEnabled: false, remoteParticipants: []))
    }
}

private func speakerDevice() -> AudioDevice {
    AudioDevice(
        id: "speaker",
        displayName: "Speaker",
        kind: .speakerphone,
        direction: .output,
        status: .available
    )
}
