package app.serenada.core.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelMonitorTest {

    @Test
    fun reportsZeroForSilence() {
        val monitor = AudioLevelMonitor()
        repeat(10) { monitor.update(0f) }
        assertEquals(0f, monitor.level, 0f)
    }

    @Test
    fun convergesTowardOneForLoudSignals() {
        // RMS ≈ 1.0 → ~0 dBFS → target 1.0. With attack 0.4, ~5 ticks gets close.
        val monitor = AudioLevelMonitor()
        repeat(8) { monitor.update(1.0f) }
        assertTrue("expected level near 1, got ${monitor.level}", monitor.level > 0.95f)
        assertTrue(monitor.level <= 1f)
    }

    @Test
    fun producesNonZeroLevelForMidSpeech() {
        // RMS = 0.39 → ~ -8 dBFS → target ≈ 1 (above SPEECH_PEAK_DB).
        val monitor = AudioLevelMonitor()
        val first = monitor.update(0.39f)
        assertNotEquals(0f, first)
        assertTrue(first > 0f && first <= 1f)
    }

    @Test
    fun clampsRawInputToZeroOneRange() {
        val monitor = AudioLevelMonitor()
        // Negative + over-1 inputs are coerced; should not blow up.
        monitor.update(-0.5f)
        monitor.update(2.0f)
        assertTrue(monitor.level in 0f..1f)
    }

    @Test
    fun treatsNonFiniteInputAsSilence() {
        // `coerceIn` propagates NaN/Infinity unchanged, which would pin the
        // smoothed level to a garbage value. The monitor must sanitize.
        val monitor = AudioLevelMonitor()
        repeat(5) { monitor.update(1.0f) }
        val rampedUp = monitor.level
        assertTrue("expected level to ramp up before the NaN injection", rampedUp > 0f)
        monitor.update(Float.NaN)
        assertTrue("expected level to remain finite after NaN, got ${monitor.level}", monitor.level.isFinite())
        assertTrue("expected level to stay in [0, 1] after NaN, got ${monitor.level}", monitor.level in 0f..1f)
        monitor.update(Float.POSITIVE_INFINITY)
        assertTrue("expected level finite after +Inf, got ${monitor.level}", monitor.level.isFinite())
        monitor.update(Float.NEGATIVE_INFINITY)
        assertTrue("expected level finite after -Inf, got ${monitor.level}", monitor.level.isFinite())
    }

    @Test
    fun releasesSlowerThanItAttacks() {
        val attack = AudioLevelMonitor()
        attack.update(1.0f)
        val afterAttackTick = attack.level

        val release = AudioLevelMonitor()
        repeat(20) { release.update(1.0f) }
        val attackedFully = release.level
        release.update(0f)
        val afterReleaseTick = release.level

        // After one tick of strong signal, attack should have moved a lot.
        // After one tick of silence from full, release should still leave a substantial level.
        assertTrue(afterAttackTick > 0.5f)
        assertTrue("release left ${afterReleaseTick}, attacked to ${attackedFully}", afterReleaseTick > 0.5f * attackedFully)
    }

    @Test
    fun resetReturnsToZero() {
        val monitor = AudioLevelMonitor()
        repeat(5) { monitor.update(1.0f) }
        assertTrue(monitor.level > 0f)
        monitor.reset()
        assertEquals(0f, monitor.level, 0f)
    }
}
