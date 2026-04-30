@testable import SerenadaCore
import XCTest

final class AudioLevelMonitorTests: XCTestCase {
    func testReportsZeroForSilence() {
        let monitor = AudioLevelMonitor()
        for _ in 0..<10 { _ = monitor.update(rawLevel: 0) }
        XCTAssertEqual(monitor.level, 0, accuracy: 1e-6)
    }

    func testConvergesTowardOneForLoudSignals() {
        // RMS = 1.0 → ~0 dBFS → target 1.0. With attack 0.4, ~5 ticks gets close.
        let monitor = AudioLevelMonitor()
        for _ in 0..<8 { _ = monitor.update(rawLevel: 1.0) }
        XCTAssertGreaterThan(monitor.level, 0.95)
        XCTAssertLessThanOrEqual(monitor.level, 1.0)
    }

    func testProducesNonZeroLevelForMidSpeech() {
        // RMS = 0.39 → ~ -8 dBFS → above SPEECH_PEAK_DB → target ≈ 1.
        let monitor = AudioLevelMonitor()
        let first = monitor.update(rawLevel: 0.39)
        XCTAssertGreaterThan(first, 0)
        XCTAssertLessThanOrEqual(first, 1)
    }

    func testClampsRawInputToZeroOneRange() {
        let monitor = AudioLevelMonitor()
        _ = monitor.update(rawLevel: -0.5)
        _ = monitor.update(rawLevel: 2.0)
        XCTAssertGreaterThanOrEqual(monitor.level, 0)
        XCTAssertLessThanOrEqual(monitor.level, 1)
    }

    func testTreatsNonFiniteInputAsSilence() {
        let monitor = AudioLevelMonitor()
        // NaN/inf would otherwise slip through the clamp (min/max use `<`
        // comparisons that propagate NaN) and pin the indicator to garbage.
        _ = monitor.update(rawLevel: .nan)
        XCTAssertEqual(monitor.level, 0, accuracy: 1e-6)
        _ = monitor.update(rawLevel: .infinity)
        XCTAssertEqual(monitor.level, 0, accuracy: 1e-6)
    }

    func testReleasesSlowerThanItAttacks() {
        let attack = AudioLevelMonitor()
        _ = attack.update(rawLevel: 1.0)
        let afterAttackTick = attack.level

        let release = AudioLevelMonitor()
        for _ in 0..<20 { _ = release.update(rawLevel: 1.0) }
        let attackedFully = release.level
        _ = release.update(rawLevel: 0)
        let afterReleaseTick = release.level

        XCTAssertGreaterThan(afterAttackTick, 0.5)
        XCTAssertGreaterThan(afterReleaseTick, 0.5 * attackedFully)
    }

    func testResetReturnsToZero() {
        let monitor = AudioLevelMonitor()
        for _ in 0..<5 { _ = monitor.update(rawLevel: 1.0) }
        XCTAssertGreaterThan(monitor.level, 0)
        monitor.reset()
        XCTAssertEqual(monitor.level, 0, accuracy: 1e-6)
    }
}
