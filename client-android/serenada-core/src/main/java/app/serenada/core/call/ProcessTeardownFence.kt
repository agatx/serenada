package app.serenada.core.call

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-wide FIFO handoff for resources that outlive a single [app.serenada.core.SerenadaSession].
 *
 * A teardown reserves its place synchronously before `leave()` returns. The next session can then
 * suspend without blocking Main until every teardown that was already reserved has completed.
 */
internal class ProcessTeardownFence {
    private val lock = Any()
    private var tail = CountDownLatch(0)

    fun begin(): Ticket {
        val completion = CountDownLatch(1)
        val predecessor = synchronized(lock) {
            tail.also { tail = completion }
        }
        return Ticket(predecessor, completion)
    }

    suspend fun awaitPending(timeoutMs: Long): Boolean {
        val pending = synchronized(lock) { tail }
        if (pending.count == 0L) return true
        return withContext(Dispatchers.Default) {
            pending.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    internal fun hasPending(): Boolean = synchronized(lock) {
        tail.count != 0L
    }

    internal class Ticket(
        private val predecessor: CountDownLatch,
        private val completion: CountDownLatch,
    ) {
        suspend fun awaitTurn(timeoutMs: Long): Boolean {
            if (predecessor.count == 0L) return true
            return withContext(Dispatchers.Default) {
                awaitTurnBlocking(timeoutMs)
            }
        }

        fun awaitTurnBlocking(timeoutMs: Long): Boolean =
            predecessor.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun complete() {
            completion.countDown()
        }
    }
}

internal val terminalMediaTeardownFence = ProcessTeardownFence()
internal val audioCoordinatorTeardownFence = ProcessTeardownFence()
internal const val PROCESS_TEARDOWN_HANDOFF_TIMEOUT_MS = 10_000L
