import XCTest
@testable import SerenadaiOS

final class CameraModeFlowTests: XCTestCase {
    func testNextFlipCameraModeCyclesWithComposite() {
        XCTAssertEqual(nextFlipCameraMode(current: .selfie, compositeAvailable: true), .world)
        XCTAssertEqual(nextFlipCameraMode(current: .world, compositeAvailable: true), .composite)
        XCTAssertEqual(nextFlipCameraMode(current: .composite, compositeAvailable: true), .selfie)
    }

    func testNextFlipCameraModeSkipsCompositeWhenUnavailable() {
        XCTAssertEqual(nextFlipCameraMode(current: .world, compositeAvailable: false), .selfie)
    }
}
