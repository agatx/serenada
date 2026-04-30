import Foundation

/// Stateful smoothing pipeline that turns a raw audio level (0..1, e.g. as
/// reported by WebRTC's `audioLevel` stat) into a perceptual 0..1 value
/// suitable for driving a voice-activity indicator.
///
/// Mirrors the web SDK's `AudioLevelMonitor` and the Android SDK's
/// `AudioLevelMonitor.kt`:
///  - Map RMS to dBFS, then linearly map [-60, -15] dBFS → [0, 1].
///  - Apply asymmetric EMA: fast attack so bars react instantly to speech,
///    slow release so they don't twitch off between syllables.
///
/// Not thread-safe. Use from a single thread (`@MainActor` in the SDK).
final class AudioLevelMonitor {
    /// Update interval that pairs with the indicator's animation duration.
    static let updateIntervalSeconds: Double = 0.1
    /// dBFS at or below which the indicator reads zero.
    static let noiseFloorDb: Float = -60
    /// dBFS at which the indicator reads full. Speech peaks around -20 to -15 dBFS.
    static let speechPeakDb: Float = -15
    /// Smoothing factor when level is rising. Lower = snappier attack.
    static let attackSmoothing: Float = 0.4
    /// Smoothing factor when level is falling. Higher = slower release.
    static let releaseSmoothing: Float = 0.7

    private(set) var level: Float = 0

    /// Submit a raw 0..1 level and return the smoothed value.
    @discardableResult
    func update(rawLevel: Float) -> Float {
        // `max`/`min` use `<` comparisons that propagate NaN unchanged, so a
        // non-finite input would slip through the clamp and pin the indicator
        // to a garbage value. Treat anything non-finite as silence.
        let sanitized = rawLevel.isFinite ? rawLevel : 0
        let raw = max(0, min(1, sanitized))
        let dbfs: Float = raw > 0 ? 20 * log10f(raw) : Self.noiseFloorDb
        let span = Self.speechPeakDb - Self.noiseFloorDb
        let target = max(0, min(1, (dbfs - Self.noiseFloorDb) / span))
        let smoothing = target > level ? Self.attackSmoothing : Self.releaseSmoothing
        level = level * smoothing + target * (1 - smoothing)
        return level
    }

    func reset() { level = 0 }
}
