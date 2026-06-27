package app.serenada.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure foreground-service decision logic (multi-call session,
 * design "Foreground Service" + Core Invariant 5). [CallServicePlan] is the
 * unit-testable core: the service merely applies it, so the start/stop,
 * keep-alive, no-auto-promote, and `mediaProjection` FGS-type invariants are
 * verified here without the Android framework.
 */
class CallServicePlanTest {

    private fun call(
        id: String,
        label: String = "Room $id",
        foreground: Boolean = false,
        ended: Boolean = false,
        screenSharing: Boolean = false,
    ) = CallServiceCall(
        callId = id,
        label = label,
        isForeground = foreground,
        isEnded = ended,
        isScreenSharing = screenSharing,
    )

    @Test
    fun emptyCallList_serviceStops() {
        val plan = CallServicePlan.from(emptyList())
        assertFalse(plan.shouldRun)
        assertNull(plan.activeCallId)
        assertEquals(0, plan.heldCallCount)
        assertFalse(plan.includeMediaProjection)
    }

    @Test
    fun allEnded_serviceStops() {
        val plan = CallServicePlan.from(
            listOf(call("a", ended = true), call("b", ended = true)),
        )
        assertFalse(plan.shouldRun)
        assertNull(plan.activeCallId)
    }

    @Test
    fun singleForegroundCall_serviceRuns_activeMapped() {
        val plan = CallServicePlan.from(listOf(call("a", foreground = true)))
        assertTrue(plan.shouldRun)
        assertEquals("a", plan.activeCallId)
        assertEquals("Room a", plan.activeLabel)
        assertEquals(0, plan.heldCallCount)
    }

    @Test
    fun activeEndsWithHeldRemaining_serviceStaysUp_noAutoPromote() {
        // Active call ended; a held call remains. Service keeps running, but there
        // is NO active call (no auto-promote, Core Invariant 5): activeCallId is
        // null and the notification reflects "calls on hold".
        val plan = CallServicePlan.from(
            listOf(
                call("active", foreground = true, ended = true),
                call("held", foreground = false),
            ),
        )
        assertTrue(plan.shouldRun)
        assertNull(plan.activeCallId)
        assertNull(plan.activeLabel)
        assertEquals(1, plan.heldCallCount)
        assertEquals("held", plan.heldCalls.single().callId)
    }

    @Test
    fun holdingOneCallWithAnotherActive_serviceStaysUp() {
        // Two calls: one foreground, one held. The service runs; the foreground
        // call owns the primary actions, the held call is summary/switch.
        val plan = CallServicePlan.from(
            listOf(call("a", foreground = true), call("b", foreground = false)),
        )
        assertTrue(plan.shouldRun)
        assertEquals("a", plan.activeCallId)
        assertEquals(1, plan.heldCallCount)
    }

    @Test
    fun endingOneCallWhileAnotherExists_serviceStaysUp() {
        // Ending one call must not stop the service while another exists.
        val plan = CallServicePlan.from(
            listOf(call("a", foreground = true), call("b", ended = true)),
        )
        assertTrue(plan.shouldRun)
        assertEquals("a", plan.activeCallId)
        // The ended call drops out of the held summary.
        assertEquals(0, plan.heldCallCount)
    }

    @Test
    fun allHeldNoForeground_serviceRuns_noActive() {
        // A registry with only held calls (e.g. after holding the only call): the
        // service stays up but there is no foreground call to mute/end.
        val plan = CallServicePlan.from(
            listOf(call("a", foreground = false), call("b", foreground = false)),
        )
        assertTrue(plan.shouldRun)
        assertNull(plan.activeCallId)
        assertEquals(2, plan.heldCallCount)
    }

    @Test
    fun mediaProjection_includedOnlyWhileSomeCallShares() {
        val sharing = CallServicePlan.from(
            listOf(call("a", foreground = true, screenSharing = true)),
        )
        assertTrue(sharing.includeMediaProjection)

        val notSharing = CallServicePlan.from(
            listOf(call("a", foreground = true, screenSharing = false)),
        )
        assertFalse(notSharing.includeMediaProjection)
    }

    @Test
    fun mediaProjection_droppedWhenSharingCallEnds_serviceNotTornDown() {
        // The screen-sharing call ended; another live call remains. The service
        // stays up (shouldRun) but the mediaProjection type is shed.
        val plan = CallServicePlan.from(
            listOf(
                call("shared", foreground = true, ended = true, screenSharing = true),
                call("other", foreground = false),
            ),
        )
        assertTrue(plan.shouldRun)
        assertFalse(plan.includeMediaProjection)
    }

    @Test
    fun heldCalls_excludeForegroundAndEnded() {
        val plan = CallServicePlan.from(
            listOf(
                call("active", foreground = true),
                call("held1", foreground = false),
                call("held2", foreground = false),
                call("ended", foreground = false, ended = true),
            ),
        )
        assertEquals(2, plan.heldCallCount)
        assertEquals(setOf("held1", "held2"), plan.heldCalls.map { it.callId }.toSet())
    }

    // --- MediaProjection FGS-type derivation (P5-1: must not go sticky) ---

    @Test
    fun mediaProjectionDecision_armBeforeRegistryConfirms_includesAndStaysArmed() {
        // Share requested: pending armed, but the registry has not yet reported
        // isScreenSharing. The type must be included (the OS needs it before capture
        // starts) and the arm stays until the registry confirms.
        val plan = CallServicePlan.from(listOf(call("a", foreground = true, screenSharing = false)))
        val decision = MediaProjectionDecision.resolve(plan, pending = true)
        assertTrue(decision.include)
        assertTrue(decision.nextPending)
    }

    @Test
    fun mediaProjectionDecision_armRetiredOnceLiveShareConfirmed() {
        // Registry now reports a live share: the type is included AND the transient
        // arm is retired so the plan alone drives the type from here on.
        val plan = CallServicePlan.from(listOf(call("a", foreground = true, screenSharing = true)))
        val decision = MediaProjectionDecision.resolve(plan, pending = true)
        assertTrue(decision.include)
        assertFalse(decision.nextPending)
    }

    @Test
    fun mediaProjectionDecision_droppedWhenNoLiveShare_evenIfFlagNeverCleared() {
        // The sticky-flag regression: after a share started, pending was retired
        // (false). When the share later stops IMPLICITLY (switch/hold/end) the live
        // call list reports no sharing, so the type is NOT included — it does not
        // linger because of a stale OR'd pending flag.
        val plan = CallServicePlan.from(
            listOf(
                call("a", foreground = true, screenSharing = false),
                call("b", foreground = false),
            ),
        )
        val decision = MediaProjectionDecision.resolve(plan, pending = false)
        assertFalse(decision.include)
        assertFalse(decision.nextPending)
    }

    @Test
    fun mediaProjectionDecision_planDrivenSharePersistsWithoutArm() {
        // With pending already retired, an actively-sharing live call still carries
        // the type purely from the plan.
        val plan = CallServicePlan.from(listOf(call("a", foreground = true, screenSharing = true)))
        val decision = MediaProjectionDecision.resolve(plan, pending = false)
        assertTrue(decision.include)
        assertFalse(decision.nextPending)
    }
}
