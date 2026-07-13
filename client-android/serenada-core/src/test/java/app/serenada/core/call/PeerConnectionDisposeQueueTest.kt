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

        queue.post {
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
}
