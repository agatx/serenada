@testable import SerenadaCore
import XCTest

/// Unit coverage for the `toggleVideo` screen-share gate
/// (``isLegacyScreenSharingGate``). The bug (BUG 2): `WebRtcEngine.toggleVideo`
/// gated its camera-capturer special-cases on RAW `isScreenSharing`, so during
/// an INDEPENDENT share (where the screen rides a separate content track) the
/// camera could not be turned ON from OFF (capturer restart skipped) and OFF
/// left the capturer running. The fix routes those gates through the
/// legacy-only gate, which is false during an independent share so the camera
/// toggles normally — and stays true (== raw `isScreenSharing`) in legacy/
/// flag-off mode so behavior is byte-identical.
///
/// The full `toggleVideo` requires a live WebRTC capturer (device-only), so the
/// gate is extracted as a pure helper and tested here (mirrors
/// ``choosePreferredCaptureResolution`` / `CaptureResolutionSelectionTests`).
final class ToggleVideoGateTests: XCTestCase {

    // MARK: - Independent share: camera toggles normally (gate is FALSE)

    func testIndependentShareDoesNotSuppressCameraToggle() {
        // Flag on + sharing: the screen is on its own content track, so the
        // camera-capturer logic must NOT be bypassed (gate false). This is the
        // case the old raw-`isScreenSharing` gate got wrong.
        XCTAssertFalse(
            isLegacyScreenSharingGate(isScreenSharing: true, enableIndependentContentVideo: true)
        )
    }

    func testIndependentNotSharingGateFalse() {
        XCTAssertFalse(
            isLegacyScreenSharingGate(isScreenSharing: false, enableIndependentContentVideo: true)
        )
    }

    // MARK: - Legacy / flag-off: byte-identical to raw isScreenSharing

    func testLegacyShareSuppressesCameraToggle() {
        // Flag off + sharing: the single video sender carries the display track,
        // so the camera-capturer logic IS bypassed (gate true), exactly as the
        // raw `isScreenSharing` gate did before — byte-identical legacy behavior.
        XCTAssertTrue(
            isLegacyScreenSharingGate(isScreenSharing: true, enableIndependentContentVideo: false)
        )
    }

    func testLegacyNotSharingGateFalse() {
        XCTAssertFalse(
            isLegacyScreenSharingGate(isScreenSharing: false, enableIndependentContentVideo: false)
        )
    }

    func testGateEqualsRawScreenSharingWhenFlagOff() {
        // Defense: with the flag off the gate must equal raw `isScreenSharing`
        // for every input (the legacy path must be byte-identical).
        for sharing in [true, false] {
            XCTAssertEqual(
                isLegacyScreenSharingGate(isScreenSharing: sharing, enableIndependentContentVideo: false),
                sharing
            )
        }
    }
}
