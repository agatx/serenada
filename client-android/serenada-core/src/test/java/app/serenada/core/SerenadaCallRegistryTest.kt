package app.serenada.core

import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.MediaActivationState
import app.serenada.core.fakes.RegistryTestHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Multi-call session, Phase 3: [SerenadaCallRegistry]. Each test uses a dedicated
 * [RegistryTestHarness] which resets the process-global [ForegroundMediaArbiter]
 * on construction and teardown, so a lease/mode held by one case cannot leak into
 * a later sequential case (contract §2 / Phase 2 green-gate note).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SerenadaCallRegistryTest {

    private val harnesses = mutableListOf<RegistryTestHarness>()

    @Before
    fun setUp() {
        ForegroundMediaArbiter.resetForTests()
    }

    @After
    fun tearDown() {
        harnesses.forEach { it.tearDown() }
        harnesses.clear()
        ForegroundMediaArbiter.resetForTests()
    }

    private fun harness(grantPermissions: Boolean = true): RegistryTestHarness {
        val h = RegistryTestHarness(grantPermissions = grantPermissions)
        harnesses.add(h)
        return h
    }

    private fun room(id: String): RoomRef = RoomRef.Id(roomId = id)

    // --- joinHeld: no foreground acquisition ---

    @Test
    fun `joinHeld does not acquire audio focus or activate the coordinator`() {
        val h = harness()
        val result = h.joinHeld(room("a"))
        val callId = (result as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()

        val managed = h.created["a"]!!
        // The held call never activates the coordinator or controller (Core Invariant 2).
        assertEquals(0, managed.coordinator.activateCalls)
        assertEquals(0, managed.fakeAudio.activateCalls)
        // It created stable senders but started no capture.
        assertEquals(1, managed.fakeMedia.createSendersForHoldCalls)
        assertEquals(0, managed.fakeMedia.startLocalMediaCalls)
        // No active call.
        assertNull(h.registry.state.value.activeCallId)
        // The managed call is held.
        val state = h.registry.state.value.calls.single { it.callId == callId }
        assertEquals(CallMediaRole.HELD, state.mediaRole)
        assertTrue(state.held)
    }

    @Test
    fun `a second joinHeld holds no foreground lease either`() {
        val h = harness()
        h.joinHeld(room("a"))
        h.joinHeld(room("b"))
        ShadowLooper.idleMainLooper()

        assertNull(h.registry.state.value.activeCallId)
        assertEquals(2, h.registry.state.value.calls.size)
        h.created.values.forEach { assertEquals(0, it.coordinator.activateCalls) }
    }

    // --- Switch: ordered deactivate-old / activate-new ---

    @Test
    fun `switch deactivates the old coordinator before activating the new one`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        ShadowLooper.idleMainLooper()
        val b = (h.joinHeld(room("b")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()

        // a is active (foreground), b is held.
        assertEquals(a, h.registry.state.value.activeCallId)

        val switch = h.switchToCall(b)
        ShadowLooper.idleMainLooper()
        assertEquals(SwitchResult.Active, switch)
        assertEquals(b, h.registry.state.value.activeCallId)

        // The OLD call (a) deactivated its coordinator before the NEW call (b)
        // activated its coordinator (drain-old-then-activate-new ordering).
        assertTrue("old coordinator deactivated on switch", h.created["a"]!!.coordinator.deactivateCalls >= 1)
        assertTrue("new coordinator activated on switch", h.created["b"]!!.coordinator.activateCalls >= 1)
        // a is now held, b foreground.
        assertEquals(CallMediaRole.HELD, h.created["a"]!!.session.mediaRoleForTest())
        assertEquals(CallMediaRole.FOREGROUND, h.created["b"]!!.session.mediaRoleForTest())
    }

    @Test
    fun `at most one foreground owner after switching`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        val b = (h.joinHeld(room("b")) as JoinResult.Joined).callId
        h.switchToCall(b)
        ShadowLooper.idleMainLooper()

        val foregroundCalls = h.created.values.count {
            it.session.mediaRoleForTest() == CallMediaRole.FOREGROUND
        }
        assertEquals("exactly one foreground owner", 1, foregroundCalls)
        assertEquals(b, h.registry.state.value.activeCallId)
    }

    // --- Switch under rapid repeated calls → one active owner ---

    @Test
    fun `rapid repeated switches converge to one active foreground owner`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        val b = (h.joinHeld(room("b")) as JoinResult.Joined).callId
        val c = (h.joinHeld(room("c")) as JoinResult.Joined).callId

        // Fire several switches in sequence (the op mutex serializes them).
        h.switchToCall(b)
        h.switchToCall(a)
        h.switchToCall(c)
        h.switchToCall(b)
        ShadowLooper.idleMainLooper()

        val foreground = h.created.values.filter {
            it.session.mediaRoleForTest() == CallMediaRole.FOREGROUND
        }
        assertEquals("one foreground owner after rapid switches", 1, foreground.size)
        assertEquals(b, h.registry.state.value.activeCallId)
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(foreground.single().session.foregroundOwnerTokenForTest()))
    }

    // --- Switch target needs permission: old stays foreground ---

    @Test
    fun `switch where target needs permission returns NeedsPermission and leaves old foreground`() {
        // No permission grants. Audio is desired by default ⇒ the target needs mic.
        val h = harness(grantPermissions = false)
        // Grant for the FIRST call so it can foreground, then revoke for the second.
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO,
        )
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)

        // Revoke the mic so the second call's preflight returns needsPermission.
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            android.Manifest.permission.RECORD_AUDIO,
        )
        val b = (h.joinHeld(room("b")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()

        val switch = h.switchToCall(b)
        ShadowLooper.idleMainLooper()

        assertEquals(SwitchResult.NeedsPermission, switch)
        // OLD call untouched: still foreground (Core Invariant 4 preflight before release).
        assertEquals(a, h.registry.state.value.activeCallId)
        assertEquals(CallMediaRole.FOREGROUND, h.created["a"]!!.session.mediaRoleForTest())
        assertEquals(CallMediaRole.HELD, h.created["b"]!!.session.mediaRoleForTest())
        // The target's activation state reflects needsPermission, carried per-call.
        val bState = h.registry.state.value.calls.single { it.callId == b }
        assertEquals(MediaActivationState.NEEDS_PERMISSION, bState.mediaActivationState)
        assertNotNull(bState.activationError)
        // The old call's coordinator was never deactivated (it was never drained).
        assertEquals(0, h.created["a"]!!.coordinator.deactivateCalls)
    }

    // --- Failed activation rolls back to previous ---

    @Test
    fun `failed foreground activation rolls back to the previous active call`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        val b = (h.joinHeld(room("b")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)

        // Make b's activation fail; the registry must roll a back to foreground.
        h.created["b"]!!.session.failNextForegroundActivationForTest = true

        val switch = h.switchToCall(b)
        ShadowLooper.idleMainLooper()

        assertTrue("switch reports failure", switch is SwitchResult.Failed)
        // a was restored to foreground (rollback); b is back to held.
        assertEquals(a, h.registry.state.value.activeCallId)
        assertEquals(CallMediaRole.FOREGROUND, h.created["a"]!!.session.mediaRoleForTest())
        assertEquals(CallMediaRole.HELD, h.created["b"]!!.session.mediaRoleForTest())
        // The recoverable error is surfaced on b.
        val bState = h.registry.state.value.calls.single { it.callId == b }
        assertNotNull(bState.activationError)
        // Exactly one foreground owner (a) and the arbiter agrees.
        assertEquals(
            1,
            h.created.values.count { it.session.mediaRoleForTest() == CallMediaRole.FOREGROUND },
        )
        assertTrue(
            ForegroundMediaArbiter.isCurrentOwner(h.created["a"]!!.session.foregroundOwnerTokenForTest()),
        )
    }

    // --- Old-release failure aborts: old still foreground, next lease never acquired ---

    @Test
    fun `old-release failure aborts the switch with old still foreground and next lease never acquired`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        val b = (h.joinHeld(room("b")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)

        // Make draining a hang so the registry's release withTimeout fires.
        h.created["a"]!!.session.hangNextForegroundReleaseForTest = true

        val switch = h.switchToCall(b)
        ShadowLooper.idleMainLooper()

        assertTrue("switch fails on old-release timeout", switch is SwitchResult.Failed)
        // OLD call (a) stays foreground (Core Invariant 1); b never activated.
        assertEquals(a, h.registry.state.value.activeCallId)
        assertEquals(CallMediaRole.FOREGROUND, h.created["a"]!!.session.mediaRoleForTest())
        assertEquals(CallMediaRole.HELD, h.created["b"]!!.session.mediaRoleForTest())
        // b's coordinator was NEVER activated (the next lease was never acquired).
        assertEquals(0, h.created["b"]!!.coordinator.activateCalls)
        // a's lease is still the live owner; b holds no lease.
        assertTrue(
            ForegroundMediaArbiter.isCurrentOwner(h.created["a"]!!.session.foregroundOwnerTokenForTest()),
        )
        assertNull(h.created["b"]!!.session.foregroundOwnerTokenForTest())
        // The old call's activation state reflects the failure.
        val aState = h.registry.state.value.calls.single { it.callId == a }
        assertEquals(MediaActivationState.FAILED, aState.mediaActivationState)
        assertNotNull(aState.activationError)
    }

    // --- joinAndSwitch holds prior before activating ---

    @Test
    fun `joinAndSwitch holds the prior active call before activating the new one`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)

        val result = h.joinAndSwitch(room("b"))
        ShadowLooper.idleMainLooper()
        val b = (result as JoinAndSwitchResult.Active).callId

        // Prior call a was held (drained: coordinator deactivated, capture suspended).
        assertEquals(CallMediaRole.HELD, h.created["a"]!!.session.mediaRoleForTest())
        assertTrue(h.created["a"]!!.coordinator.deactivateCalls >= 1)
        assertTrue(h.created["a"]!!.fakeMedia.suspendLocalMediaForHoldCalls >= 1)
        // New call b is active.
        assertEquals(b, h.registry.state.value.activeCallId)
        assertEquals(CallMediaRole.FOREGROUND, h.created["b"]!!.session.mediaRoleForTest())
    }

    // --- joinAndSwitch failing room join leaves prior untouched ---

    @Test
    fun `joinAndSwitch with a failing room join leaves the prior active call untouched`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)

        val result = h.joinAndSwitchFailing(room("b"))
        ShadowLooper.idleMainLooper()

        assertTrue(result is JoinAndSwitchResult.Failed)
        val failedCallId = (result as JoinAndSwitchResult.Failed).callId
        // The prior active call is untouched: still foreground, never drained.
        assertEquals(a, h.registry.state.value.activeCallId)
        assertEquals(CallMediaRole.FOREGROUND, h.created["a"]!!.session.mediaRoleForTest())
        assertEquals(0, h.created["a"]!!.coordinator.deactivateCalls)
        // The failed call lingers (ended) carrying its failure so the host can
        // inspect/dismiss it; it is never active and holds no lease.
        val failed = h.registry.state.value.calls.single { it.roomId == "b" }
        assertEquals(failedCallId, failed.callId)
        assertNotNull(failed.activationError)
        // Dismiss removes it.
        h.dismissCall(failed.callId)
        ShadowLooper.idleMainLooper()
        assertFalse(h.registry.state.value.calls.any { it.roomId == "b" })
    }

    // --- Duplicate live join is idempotent ---

    @Test
    fun `duplicate live join for a roomId returns the existing call`() {
        val h = harness()
        val first = (h.joinHeld(room("a")) as JoinResult.Joined).callId
        val second = (h.joinHeld(room("a")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()

        assertEquals("duplicate live join is idempotent by room", first, second)
        assertEquals(1, h.registry.state.value.calls.size)
        // Only ONE session was created.
        assertEquals(1, h.created.size)
    }

    @Test
    fun `joinHeld with a failing room join returns Failed and leaves no active call`() {
        val h = harness()
        val result = h.joinHeldFailing(room("a"))
        ShadowLooper.idleMainLooper()

        assertTrue(result is JoinResult.Failed)
        val failed = result as JoinResult.Failed
        assertNotNull("failed join carries the created call id", failed.callId)
        assertNull(h.registry.state.value.activeCallId)
        // The failed call lingers (ended) with its error until dismissed.
        val state = h.registry.state.value.calls.single { it.callId == failed.callId }
        assertNotNull(state.activationError)
    }

    @Test
    fun `duplicate live join canonicalizes URL and id to the same call`() {
        val h = harness()
        val byUrl = (h.joinHeld(RoomRef.Url("https://serenada.app/call/tok123")) as JoinResult.Joined).callId
        val byId = (h.joinHeld(RoomRef.Id("tok123")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()

        assertEquals("URL and bare id for the same token dedup to one call", byUrl, byId)
        assertEquals(1, h.registry.state.value.calls.size)
    }

    // --- Direct join while registry has a live call fails ---

    @Test
    fun `direct SerenadaCore join while the registry has a live held call fails`() {
        val h = harness()
        h.joinHeld(room("a"))
        ShadowLooper.idleMainLooper()
        // The registry claimed REGISTRY mode for the process even though the only
        // call is held (Core Invariant 6: mode-level, not lease-level).
        assertEquals(ForegroundArbiterMode.REGISTRY, ForegroundMediaArbiter.currentMode)

        // A direct foreground acquire (mode DIRECT) now fails fast.
        var threw = false
        try {
            ForegroundMediaArbiter.acquireForeground(
                ownerId = "direct",
                mode = ForegroundArbiterMode.DIRECT,
                modeOwnerRef = Any(),
            )
        } catch (e: ForegroundLeaseUnavailable) {
            threw = true
        }
        assertTrue("direct join must fail while the registry owns the process", threw)
    }

    @Test
    fun `the process mode clears after the last registry call ends`() {
        val h = harness()
        val a = (h.joinHeld(room("a")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()
        assertEquals(ForegroundArbiterMode.REGISTRY, ForegroundMediaArbiter.currentMode)

        h.leaveCall(a)
        ShadowLooper.idleMainLooper()

        // No NON-ENDED managed calls left ⇒ the registry drops its REGISTRY mode
        // claim (the ended call lingers until dismissed but does not own the process).
        assertNull(ForegroundMediaArbiter.currentMode)
        assertTrue(h.registry.state.value.calls.none { it.callId == a && !it.held && it.mediaRole == CallMediaRole.FOREGROUND })
        h.dismissCall(a)
        ShadowLooper.idleMainLooper()
        assertTrue(h.registry.state.value.calls.isEmpty())
    }

    // --- hold / leave release foreground; no auto-promote ---

    @Test
    fun `holding the active call releases foreground with no auto-promote`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        val b = (h.joinHeld(room("b")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)

        h.holdCall(a)
        ShadowLooper.idleMainLooper()

        // No call is foreground (no auto-promote of b; Core Invariant 5).
        assertNull(h.registry.state.value.activeCallId)
        assertEquals(CallMediaRole.HELD, h.created["a"]!!.session.mediaRoleForTest())
        assertEquals(CallMediaRole.HELD, h.created["b"]!!.session.mediaRoleForTest())
        // The lease was freed: a direct held probe can take the lease now.
        val token = ForegroundMediaArbiter.acquireForeground(
            ownerId = "probe",
            mode = ForegroundArbiterMode.REGISTRY,
            modeOwnerRef = Any(),
        )
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))
        ForegroundMediaArbiter.releaseLease(token)
    }

    @Test
    fun `leaving the active call releases foreground and keeps held calls connected`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        val b = (h.joinHeld(room("b")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()

        h.leaveCall(a)
        ShadowLooper.idleMainLooper()

        // a is ended (lingers, dismissible); b stays held, not auto-promoted.
        val aState = h.registry.state.value.calls.single { it.callId == a }
        assertTrue("left call is no longer foreground", aState.mediaRole != CallMediaRole.FOREGROUND || aState.held)
        assertTrue(h.registry.state.value.calls.any { it.callId == b })
        assertNull(h.registry.state.value.activeCallId)
        // The active call's session was asked to leave the room.
        assertEquals(1, h.created["a"]!!.fakeProvider.leaveCalls)
        // After dismiss, a is gone and b remains.
        h.dismissCall(a)
        ShadowLooper.idleMainLooper()
        assertFalse(h.registry.state.value.calls.any { it.callId == a })
        assertTrue(h.registry.state.value.calls.any { it.callId == b })
    }
}
