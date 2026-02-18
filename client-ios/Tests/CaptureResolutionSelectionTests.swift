import XCTest
@testable import SerenadaiOS

final class CaptureResolutionSelectionTests: XCTestCase {
    func testNonHdPrefers480pWhenAvailable() {
        let selected = choosePreferredCaptureResolution(
            from: [
                CaptureResolution(width: 320, height: 240),
                CaptureResolution(width: 640, height: 360),
                CaptureResolution(width: 640, height: 480),
                CaptureResolution(width: 1280, height: 720)
            ],
            isHdVideoExperimentalEnabled: false
        )

        XCTAssertEqual(selected, CaptureResolution(width: 640, height: 480))
    }

    func testNonHdFallsBackToClosestWhen480pIsUnavailable() {
        let selected = choosePreferredCaptureResolution(
            from: [
                CaptureResolution(width: 352, height: 288),
                CaptureResolution(width: 640, height: 360),
                CaptureResolution(width: 960, height: 540)
            ],
            isHdVideoExperimentalEnabled: false
        )

        XCTAssertEqual(selected, CaptureResolution(width: 640, height: 360))
    }

    func testHdPrefersLargestResolution() {
        let selected = choosePreferredCaptureResolution(
            from: [
                CaptureResolution(width: 640, height: 480),
                CaptureResolution(width: 1280, height: 720),
                CaptureResolution(width: 1920, height: 1080)
            ],
            isHdVideoExperimentalEnabled: true
        )

        XCTAssertEqual(selected, CaptureResolution(width: 1920, height: 1080))
    }
}
