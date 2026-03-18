import XCTest
@testable import SerenadaiOS

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

    func testBroadcastSharedMemoryTimestampRoundTripsAtUnalignedOffset() {
        let buffer = UnsafeMutableRawPointer.allocate(byteCount: 64, alignment: 8)
        defer { buffer.deallocate() }
        buffer.initializeMemory(as: UInt8.self, repeating: 0, count: 64)

        let timestamp = Int64(0x0102_0304_0506_0708)
        BroadcastSharedMemoryIO.storeInt64(
            timestamp,
            to: buffer,
            byteOffset: BroadcastHeaderOffset.timestampNs
        )

        let decoded = BroadcastSharedMemoryIO.loadInt64(
            from: UnsafeRawPointer(buffer),
            byteOffset: BroadcastHeaderOffset.timestampNs
        )

        XCTAssertEqual(decoded, timestamp)
    }
}
