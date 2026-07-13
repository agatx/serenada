package app.serenada.core.call

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerConnectionDisposeQueueTest {

    @Test
    fun `flush drains posted terminal teardown on the dispose thread`() {
        val queue = PeerConnectionDisposeQueue(Handler(Looper.getMainLooper()))
        val drained = CountDownLatch(1)
        val calls = mutableListOf<String>()
        var teardownThread = ""
        var completionThread = ""

        queue.enqueueForFlush {
            teardownThread = Thread.currentThread().name
            calls += "teardown"
        }
        queue.flush(shutdownAfterDrain = true) {
            completionThread = Thread.currentThread().name
            calls += "complete"
            drained.countDown()
        }

        assertTrue("dispose queue did not drain", drained.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("teardown", "complete"), calls)
        assertEquals("serenada-pc-dispose", teardownThread)
        assertEquals(teardownThread, completionThread)
    }

    @Test
    fun `flush preserves delayed disposal before terminal teardown`() {
        val queue = PeerConnectionDisposeQueue(Handler(Looper.getMainLooper()))
        val drained = CountDownLatch(1)
        val calls = mutableListOf<String>()

        queue.postDelayed(Runnable { calls += "deferred" }, 60_000)
        queue.enqueueForFlush { calls += "terminal" }
        queue.flush(shutdownAfterDrain = true) { drained.countDown() }

        assertTrue("dispose queue did not drain", drained.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("deferred", "terminal"), calls)
    }

    @Test
    fun `throwing disposal does not prevent remaining work or drain`() {
        val queue = PeerConnectionDisposeQueue(Handler(Looper.getMainLooper()))
        val drained = CountDownLatch(1)
        val calls = mutableListOf<String>()

        queue.enqueueForFlush { error("dispose failed") }
        queue.enqueueForFlush { calls += "after-error" }
        queue.flush(shutdownAfterDrain = true) { drained.countDown() }

        assertTrue("dispose queue did not drain", drained.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("after-error"), calls)
    }
}
