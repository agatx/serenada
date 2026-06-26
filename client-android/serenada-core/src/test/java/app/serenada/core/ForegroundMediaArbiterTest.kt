package app.serenada.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Process-wide foreground media arbiter (multi-call session, contract §2).
 *
 * Robolectric so the calls run on the main looper (the arbiter asserts the main
 * thread). Each test resets the process-global singleton before and after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundMediaArbiterTest {

    @Before
    fun setUp() {
        ForegroundMediaArbiter.resetForTests()
    }

    @After
    fun tearDown() {
        ForegroundMediaArbiter.resetForTests()
    }

    @Test
    fun `acquire grants a token and a second acquire while live fails`() {
        val token = ForegroundMediaArbiter.acquireForeground("call-a")
        assertEquals("call-a", token.ownerId)
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))

        try {
            ForegroundMediaArbiter.acquireForeground("call-b")
            fail("second acquire while a lease is live should throw")
        } catch (_: ForegroundLeaseUnavailable) {
            // expected
        }
    }

    @Test
    fun `release then reacquire works and mints a fresh token`() {
        val first = ForegroundMediaArbiter.acquireForeground("call-a")
        ForegroundMediaArbiter.releaseLease(first)
        assertFalse(ForegroundMediaArbiter.isCurrentOwner(first))

        val second = ForegroundMediaArbiter.acquireForeground("call-a")
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(second))
        assertFalse("a new token is not the released one", first === second)
    }

    @Test
    fun `release with a wrong token throws`() {
        // Build a token that is neither the current owner nor the last-released:
        // acquire A, release A; acquire B, release B (B becomes last-released);
        // acquire C (now live). A is now a genuine "foreign" token.
        val tokenA = ForegroundMediaArbiter.acquireForeground("call-a")
        ForegroundMediaArbiter.releaseLease(tokenA)
        val tokenB = ForegroundMediaArbiter.acquireForeground("call-b")
        ForegroundMediaArbiter.releaseLease(tokenB)
        ForegroundMediaArbiter.acquireForeground("call-c") // live owner

        try {
            ForegroundMediaArbiter.releaseLease(tokenA)
            fail("releasing a non-current, non-last token should throw")
        } catch (_: ForegroundLeaseUnavailable) {
            // expected
        }
    }

    @Test
    fun `releasing the same token twice is idempotent`() {
        val token = ForegroundMediaArbiter.acquireForeground("call-a")
        ForegroundMediaArbiter.releaseLease(token)
        // Second release of the same token, nothing granted since: a safe no-op.
        ForegroundMediaArbiter.releaseLease(token)
    }

    @Test
    fun `nextOperationGeneration is monotonic`() {
        val g1 = ForegroundMediaArbiter.nextOperationGeneration()
        val g2 = ForegroundMediaArbiter.nextOperationGeneration()
        val g3 = ForegroundMediaArbiter.nextOperationGeneration()
        assertTrue(g2 > g1)
        assertTrue(g3 > g2)
    }

    @Test
    fun `cross-mode acquire fails while the other mode has live owners`() {
        // Registry claims REGISTRY mode (a held-only registry: mode but no lease).
        val registryRef = Any()
        ForegroundMediaArbiter.claimMode(ForegroundArbiterMode.REGISTRY, registryRef)
        assertEquals(ForegroundArbiterMode.REGISTRY, ForegroundMediaArbiter.currentMode)

        // A direct join (DIRECT mode) must fail while the registry owns the process.
        try {
            ForegroundMediaArbiter.acquireForeground(
                ownerId = "direct-call",
                mode = ForegroundArbiterMode.DIRECT,
                modeOwnerRef = Any(),
            )
            fail("direct acquire should fail while registry mode is owned")
        } catch (_: ForegroundLeaseUnavailable) {
            // expected
        }
        // The registry still owns the process (failed acquire had no side effects).
        assertEquals(ForegroundArbiterMode.REGISTRY, ForegroundMediaArbiter.currentMode)

        // Once the registry releases its mode, a direct acquire succeeds.
        ForegroundMediaArbiter.releaseMode(registryRef)
        assertNull(ForegroundMediaArbiter.currentMode)
        val token = ForegroundMediaArbiter.acquireForeground(
            ownerId = "direct-call",
            mode = ForegroundArbiterMode.DIRECT,
            modeOwnerRef = Any(),
        )
        assertEquals(ForegroundArbiterMode.DIRECT, ForegroundMediaArbiter.currentMode)
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))
    }

    @Test
    fun `acquire while a prior release is pending fails`() {
        val owner = ForegroundMediaArbiter.acquireForeground("call-a")
        // The registry switch has begun draining the old owner.
        ForegroundMediaArbiter.markReleasePending()
        try {
            ForegroundMediaArbiter.acquireForeground("call-b")
            fail("acquire while a release is pending should throw")
        } catch (_: ForegroundLeaseUnavailable) {
            // expected
        }
        // Confirming the release clears pending and frees the lease for the next.
        ForegroundMediaArbiter.releaseLease(owner)
        val next = ForegroundMediaArbiter.acquireForeground("call-b")
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(next))
    }
}
