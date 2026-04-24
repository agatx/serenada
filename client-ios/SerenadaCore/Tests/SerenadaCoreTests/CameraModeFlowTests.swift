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
}
