@testable import SerenadaCore
import XCTest

final class CameraModeFlowTests: XCTestCase {
    // MARK: - resolveCameraModes

    func testResolveDefaultsToAllModesWhenNil() {
        XCTAssertEqual(resolveCameraModes(nil), [.selfie, .world, .composite])
    }

    func testResolvePreservesConfiguredOrder() {
        XCTAssertEqual(resolveCameraModes([.world, .selfie]), [.world, .selfie])
    }

    func testResolveDropsScreenShare() {
        XCTAssertEqual(resolveCameraModes([.selfie, .screenShare, .world]), [.selfie, .world])
    }

    func testResolveKeepsEmptyListEmpty() {
        XCTAssertEqual(resolveCameraModes([]), [])
    }

    func testResolveDeduplicates() {
        XCTAssertEqual(resolveCameraModes([.world, .selfie, .world]), [.world, .selfie])
    }

    func testResolveDropsCompositeWhenUnsupported() {
        XCTAssertEqual(
            resolveCameraModes([.selfie, .composite, .world], compositeAvailable: false),
            [.selfie, .world]
        )
    }

    // MARK: - nextCameraMode (configured list)

    func testNextCameraModeNilForSingletonList() {
        XCTAssertNil(nextCameraMode(modes: [.selfie], current: .selfie, compositeAvailable: true))
    }

    func testNextCameraModeCyclesInConfiguredOrder() {
        XCTAssertEqual(
            nextCameraMode(modes: [.world, .selfie], current: .world, compositeAvailable: true),
            .selfie
        )
        XCTAssertEqual(
            nextCameraMode(modes: [.world, .selfie], current: .selfie, compositeAvailable: true),
            .world
        )
    }

    func testNextCameraModeSkipsCompositeWhenDeviceLacksIt() {
        XCTAssertEqual(
            nextCameraMode(modes: [.selfie, .world, .composite], current: .world, compositeAvailable: false),
            .selfie
        )
    }

    func testNextCameraModeFallsBackToFirstWhenCurrentMissing() {
        XCTAssertEqual(
            nextCameraMode(modes: [.world, .selfie], current: .composite, compositeAvailable: true),
            .world
        )
    }

    // MARK: - restartVideoCapturer(preferring:) source ordering (D-native-1)

    /// The resume-from-hold path asks the capturer to restart PREFERRING the
    /// desired mode's source (which may have been chosen while held), falling back
    /// to the available-modes scan only if that source is unavailable. This asserts
    /// the observable attempt ORDER: preferred source first, remaining available
    /// sources after (deduped, configured order) — mirrors Android's
    /// `restartVideoCapturerWithFallback`.
    @MainActor
    private func makeController(modes: [LocalCameraMode]) -> CameraCaptureController {
#if canImport(WebRTC)
        return CameraCaptureController(
            localVideoSource: nil,
            isHdVideoExperimentalEnabled: false,
            availableCameraModes: modes,
            onCameraFacingChanged: { _ in },
            onCameraModeChanged: { _ in },
            onFlashlightStateChanged: { _, _ in },
            onZoomFactorChanged: { _ in },
            onFeatureDegradation: { _ in }
        )
#else
        return CameraCaptureController(
            isHdVideoExperimentalEnabled: false,
            availableCameraModes: modes,
            onCameraFacingChanged: { _ in },
            onCameraModeChanged: { _ in },
            onFlashlightStateChanged: { _, _ in },
            onZoomFactorChanged: { _ in },
            onFeatureDegradation: { _ in }
        )
#endif
    }

    @MainActor
    func testPreferredCameraSourceIsAttemptedFirstThenFallbackOrder() {
        let controller = makeController(modes: [.selfie, .world, .composite])
        XCTAssertEqual(
            controller.cameraSourceCandidates(preferring: .world),
            [.world, .selfie, .composite],
            "The preferred mode's source must be attempted first, then the remaining available sources")
        XCTAssertEqual(
            controller.cameraSourceCandidates(preferring: .composite),
            [.composite, .selfie, .world])
    }

    @MainActor
    func testPreferredCameraSourceDedupesAndHonorsRestrictedModeList() {
        let controller = makeController(modes: [.selfie, .world])
        // Preferred already-first: no duplicate, order preserved.
        XCTAssertEqual(controller.cameraSourceCandidates(preferring: .selfie), [.selfie, .world])
        // Preferred is second in the list: it jumps to the front, fallback follows.
        XCTAssertEqual(controller.cameraSourceCandidates(preferring: .world), [.world, .selfie])
        // A preferred mode outside the available list still leads; fallback is the
        // available sources (so an unavailable preferred source degrades cleanly).
        XCTAssertEqual(controller.cameraSourceCandidates(preferring: .composite), [.composite, .selfie, .world])
    }
}
