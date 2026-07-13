package app.serenada.core.call

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTeardownFenceTest {

    @Test
    fun `pending handoff waits until reserved teardown completes`() = runBlocking {
        val fence = ProcessTeardownFence()
        val ticket = fence.begin()
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)

        val worker = Thread {
            started.countDown()
            Thread.sleep(50)
            ticket.complete()
            completed.countDown()
        }.apply { start() }

        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(fence.awaitPending(1_000))
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        worker.join()
    }

    @Test
    fun `handoff times out while teardown remains pending`() = runBlocking {
        val fence = ProcessTeardownFence()
        val ticket = fence.begin()

        assertFalse(fence.awaitPending(1))

        ticket.complete()
        assertTrue(fence.awaitPending(1_000))
    }

    @Test
    fun `teardown tickets preserve fifo order`() {
        val fence = ProcessTeardownFence()
        val first = fence.begin()
        val second = fence.begin()

        assertFalse(second.awaitTurnBlocking(1))
        first.complete()
        assertTrue(second.awaitTurnBlocking(1_000))
        second.complete()
    }
}
