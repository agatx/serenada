package app.serenada.core.call

import android.os.Handler
import android.os.Looper
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Verifies the poller drives `localMonitor` from `collectLocalLevel` (the
 * primer PC's `media-source.audioLevel` stat) rather than from peer slots.
 * This is what gives the indicator consistent sensitivity in Waiting and
 * InCall — the level source is the same WebRTC pipeline either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioLevelPollerFallbackTest {

    private lateinit var poller: AudioLevelPoller
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = SameThreadExecutorService()

    private var primerCalls = 0
    private var nextPrimerLevel: Float? = null
    private var lastLocalLevel: Float? = null

    @Before
    fun setUp() {
        poller = AudioLevelPoller(
            handler = handler,
            statsExecutorProvider = { executor },
            isActivePhase = { true },
            getPeerSlots = { emptyList() },
            collectLocalLevel = { onComplete ->
                primerCalls += 1
                onComplete(nextPrimerLevel)
            },
            onLevelsUpdated = { local, _ -> lastLocalLevel = local },
        )
    }

    @After
    fun tearDown() {
        poller.stop()
        executor.shutdownNow()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun usesPrimerLevelWhenNoSlots() {
        nextPrimerLevel = 0.5f  // mid-speech level
        poller.start()
        ShadowLooper.idleMainLooper()
        assertTrue("expected the primer to be queried", primerCalls > 0)
        val level = lastLocalLevel ?: error("onLevelsUpdated never fired")
        assertTrue("expected a non-zero local level fed by the primer, got $level", level > 0f)
    }

    @Test
    fun nullPrimerLevelDecaysToSilence() {
        nextPrimerLevel = null
        poller.start()
        repeat(10) {
            ShadowLooper.runMainLooperToNextTask()
        }
        val level = lastLocalLevel ?: error("onLevelsUpdated never fired")
        assertEquals(0f, level, 0.001f)
    }

    /**
     * Runs submitted tasks inline on the calling thread so the test can
     * proceed deterministically without race conditions between the main
     * looper and a background stats executor.
     */
    private class SameThreadExecutorService : AbstractExecutorService() {
        @Volatile private var shutdown = false
        override fun execute(command: Runnable) { command.run() }
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }
}
