package app.serenada.core.call

import kotlin.math.ln

/**
 * Stateful smoothing pipeline that turns a raw audio level (0..1, e.g. as
 * reported by WebRTC's `audioLevel` stat) into a perceptual 0..1 value
 * suitable for driving a voice-activity indicator.
 *
 * Mirrors the web SDK's `AudioLevelMonitor`:
 *  - Map RMS to dBFS, then linearly map [-60, -15] dBFS → [0, 1].
 *  - Apply asymmetric EMA: fast attack so bars react instantly to speech,
 *    slow release so they don't twitch off between syllables.
 *
 * Not thread-safe. Call [update] from a single thread (the same one that
 * reads [level]).
 */
internal class AudioLevelMonitor {
    private var smoothedLevel: Float = 0f

    val level: Float get() = smoothedLevel

    /** Submit a raw 0..1 level and return the smoothed value. */
    fun update(rawLevel: Float): Float {
        val raw = rawLevel.coerceIn(0f, 1f)
        val dbfs = if (raw > 0f) (20.0 * ln(raw.toDouble()) / LN_10).toFloat() else NOISE_FLOOR_DB
        val target = ((dbfs - NOISE_FLOOR_DB) / (SPEECH_PEAK_DB - NOISE_FLOOR_DB))
            .coerceIn(0f, 1f)
        val smoothing = if (target > smoothedLevel) ATTACK_SMOOTHING else RELEASE_SMOOTHING
        smoothedLevel = smoothedLevel * smoothing + target * (1f - smoothing)
        return smoothedLevel
    }

    fun reset() { smoothedLevel = 0f }

    companion object {
        /** Update interval that pairs with the indicator's CSS-equivalent transition. */
        const val UPDATE_INTERVAL_MS: Long = 100L
        /** dBFS at or below which the indicator reads zero. */
        const val NOISE_FLOOR_DB: Float = -60f
        /** dBFS at which the indicator reads full. Speech peaks around -20 to -15 dBFS. */
        const val SPEECH_PEAK_DB: Float = -15f
        /** Smoothing factor when level is rising. Lower = snappier attack. */
        const val ATTACK_SMOOTHING: Float = 0.4f
        /** Smoothing factor when level is falling. Higher = slower release. */
        const val RELEASE_SMOOTHING: Float = 0.7f
        private val LN_10 = ln(10.0)
    }
}
