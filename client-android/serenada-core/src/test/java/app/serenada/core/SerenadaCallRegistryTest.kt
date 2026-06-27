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
        // The held target stays INACTIVE (cross-platform contract); the needed
        // permission is surfaced ONLY on the per-call activationError.
        val bState = h.registry.state.value.calls.single { it.callId == b }
        assertEquals(MediaActivationState.INACTIVE, bState.mediaActivationState)
        assertTrue(bState.activationError is CallRegistryError.NeedsPermission)
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
        // The release failure is surfaced ONLY on the per-call activationError
        // (cross-platform contract); mediaActivationState is NOT forced to FAILED.
        val aState = h.registry.state.value.calls.single { it.callId == a }
        assertTrue(aState.activationError is CallRegistryError.ReleaseFailed)
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

    // --- FIX A: a real coordinator-activation failure rolls back to the previous call ---

    @Test
    fun `coordinator activation failure on switch rolls back to the previous call`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        val b = (h.joinHeld(room("b")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)

        // Make b's AUDIO COORDINATOR activation throw (the real coordinator
        // boundary, not the short-circuit injection). FIX A: activateForeground
        // must NOT silently commit foreground; it must surface the failure so the
        // registry rolls a back.
        h.created["b"]!!.coordinator.failNextActivation = true

        val switch = h.switchToCall(b)
        ShadowLooper.idleMainLooper()

        assertTrue("switch reports failure on coordinator-activation failure", switch is SwitchResult.Failed)
        // a was restored to foreground (rollback); b is back to held.
        assertEquals(a, h.registry.state.value.activeCallId)
        assertEquals(CallMediaRole.FOREGROUND, h.created["a"]!!.session.mediaRoleForTest())
        assertEquals(CallMediaRole.HELD, h.created["b"]!!.session.mediaRoleForTest())
        // b never committed foreground media: role held, no lease, error surfaced.
        assertNull(h.created["b"]!!.session.foregroundOwnerTokenForTest())
        val bState = h.registry.state.value.calls.single { it.callId == b }
        assertNotNull(bState.activationError)
        // Exactly one foreground owner (a) and the arbiter agrees it owns the lease.
        assertEquals(
            1,
            h.created.values.count { it.session.mediaRoleForTest() == CallMediaRole.FOREGROUND },
        )
        assertTrue(
            ForegroundMediaArbiter.isCurrentOwner(h.created["a"]!!.session.foregroundOwnerTokenForTest()),
        )
    }

    // --- FIX E: hold-timeout keeps the lease; leave/end-timeout releases it ---

    @Test
    fun `hold on a drain timeout keeps the lease and activeCallId and surfaces a failure`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)
        val aToken = h.created["a"]!!.session.foregroundOwnerTokenForTest()

        // Make a's foreground drain hang so hold's bounded release times out.
        h.created["a"]!!.session.hangNextForegroundReleaseForTest = true

        h.holdCall(a)
        ShadowLooper.idleMainLooper()

        // Invariant 1: the user KEEPS the call they were on. The lease + activeCallId
        // stay; the release failure is surfaced ONLY on the per-call activationError
        // (cross-platform contract — mediaActivationState is NOT forced to FAILED).
        // Do NOT release the lease (two owners must never exist).
        assertEquals(a, h.registry.state.value.activeCallId)
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(aToken))
        val aState = h.registry.state.value.calls.single { it.callId == a }
        assertTrue(aState.activationError is CallRegistryError.ReleaseFailed)
        // A fresh acquire still fails (release pending stays set; lease held).
        var threw = false
        try {
            ForegroundMediaArbiter.acquireForeground(
                ownerId = "probe",
                mode = ForegroundArbiterMode.REGISTRY,
                modeOwnerRef = Any(),
            )
        } catch (e: ForegroundLeaseUnavailable) {
            threw = true
        }
        assertTrue("no new lease granted while hold drain failed", threw)
    }

    @Test
    fun `leave on a drain timeout releases the lease (call is going away)`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)

        // Same hung drain, but on the LEAVE path: the call is going away, so the
        // lease is released UNCONDITIONALLY after the bounded drain (FIX E).
        h.created["a"]!!.session.hangNextForegroundReleaseForTest = true

        h.leaveCall(a)
        ShadowLooper.idleMainLooper()

        assertNull(h.registry.state.value.activeCallId)
        // The lease was freed despite the drain timeout: a fresh acquire succeeds.
        val token = ForegroundMediaArbiter.acquireForeground(
            ownerId = "probe",
            mode = ForegroundArbiterMode.REGISTRY,
            modeOwnerRef = Any(),
        )
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))
        ForegroundMediaArbiter.releaseLease(token)
    }

    // --- FIX B: active teardown keys off the token, not the session role ---

    @Test
    fun `leave releases the lease even when a partial release reset the session role`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)
        val aToken = h.created["a"]!!.session.foregroundOwnerTokenForTest()
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(aToken))

        // Model a session-side partial release: the session drives ITSELF to HELD
        // (mediaRole no longer FOREGROUND) while the registry still holds the lease
        // token. The OLD code keyed leave/end off session.mediaRole and would skip
        // the release, wedging the process. FIX B keys off the token instead.
        h.runOnMain { h.created["a"]!!.session.applyHeldRoleInternal() }
        ShadowLooper.idleMainLooper()
        assertEquals(CallMediaRole.HELD, h.created["a"]!!.session.mediaRoleForTest())
        // Registry still holds the lease (token not yet released by the registry).
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(aToken))

        h.leaveCall(a)
        ShadowLooper.idleMainLooper()

        // The lease was released (keyed off the token, not the reset role): a fresh
        // acquire succeeds. Without FIX B this would throw (lease still held).
        assertNull(h.registry.state.value.activeCallId)
        val token = ForegroundMediaArbiter.acquireForeground(
            ownerId = "probe",
            mode = ForegroundArbiterMode.REGISTRY,
            modeOwnerRef = Any(),
        )
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))
        ForegroundMediaArbiter.releaseLease(token)
    }

    // --- VERIFY F: a failed held join releases registry mode so a direct join succeeds ---

    @Test
    fun `a failed held join releases registry mode so a subsequent direct join succeeds`() {
        val h = harness()
        val result = h.joinHeldFailing(room("a"))
        ShadowLooper.idleMainLooper()
        assertTrue(result is JoinResult.Failed)

        // No NON-ENDED managed call remains ⇒ the registry dropped its REGISTRY mode
        // claim, even though the failed call lingers (ended) until dismissed.
        assertNull("registry mode released after the only (failed) call ended", ForegroundMediaArbiter.currentMode)

        // A later DIRECT SerenadaCore.join() (mode DIRECT) now succeeds: the process
        // is free. Web+iOS were flagged for wedging here; android releases the mode.
        val token = ForegroundMediaArbiter.acquireForeground(
            ownerId = "direct",
            mode = ForegroundArbiterMode.DIRECT,
            modeOwnerRef = Any(),
        )
        assertEquals(ForegroundArbiterMode.DIRECT, ForegroundMediaArbiter.currentMode)
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))
        ForegroundMediaArbiter.releaseLease(token)
    }

    // --- Round-2 [P1]: a session reaching terminal ON ITS OWN releases the
    //     registry lease + clears active + marks ended (the collector terminal op) ---

    @Test
    fun `active call ended on its own releases the lease clears active and frees the process`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)
        assertEquals(ForegroundArbiterMode.REGISTRY, ForegroundMediaArbiter.currentMode)
        val aToken = h.created["a"]!!.session.foregroundOwnerTokenForTest()
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(aToken))

        // The session reaches a terminal phase on its OWN (room_ended / remote end),
        // NOT via a registry leave/end. The session reset releases only a DIRECT
        // lease (registry sessions have none); the registry's collector terminal op
        // must release the registry-owned lease + active slot + REGISTRY mode.
        h.simulateRoomEnded("a")

        // Active slot cleared; NO auto-promote (only call anyway).
        assertNull(h.registry.state.value.activeCallId)
        // The call is marked ended (dismissable; excluded from live counts).
        val aState = h.registry.state.value.calls.single { it.callId == a }
        assertEquals(CallMediaRole.HELD, aState.mediaRole)
        // REGISTRY mode released (no live call remains) so a subsequent DIRECT join
        // succeeds — without the collector terminal op the lease + mode leak forever.
        assertNull("registry mode released after the only call ended on its own", ForegroundMediaArbiter.currentMode)
        val token = ForegroundMediaArbiter.acquireForeground(
            ownerId = "direct",
            mode = ForegroundArbiterMode.DIRECT,
            modeOwnerRef = Any(),
        )
        assertEquals(ForegroundArbiterMode.DIRECT, ForegroundMediaArbiter.currentMode)
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))
        ForegroundMediaArbiter.releaseLease(token)
    }

    @Test
    fun `remote-ended held call marks ended and releases mode when it was the last live call`() {
        val h = harness()
        val a = (h.joinHeld(room("a")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()
        assertEquals(ForegroundArbiterMode.REGISTRY, ForegroundMediaArbiter.currentMode)
        // A held call holds no foreground lease.
        assertNull(h.created["a"]!!.session.foregroundOwnerTokenForTest())
        assertNull(h.registry.state.value.activeCallId)

        // The held call is remote-ended on its own.
        h.simulateRoomEnded("a")

        // It is marked ended; still no active call; REGISTRY mode released since it
        // was the last live call (so a direct join can proceed).
        val aState = h.registry.state.value.calls.single { it.callId == a }
        assertEquals(CallMediaRole.HELD, aState.mediaRole)
        assertNull(h.registry.state.value.activeCallId)
        assertNull("mode released after last live call remote-ended", ForegroundMediaArbiter.currentMode)
        // Dismiss removes the lingering ended call.
        h.dismissCall(a)
        ShadowLooper.idleMainLooper()
        assertFalse(h.registry.state.value.calls.any { it.callId == a })
    }

    @Test
    fun `no double-release when registry endCall drove termination`() {
        val h = harness()
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        ShadowLooper.idleMainLooper()
        assertEquals(a, h.registry.state.value.activeCallId)

        // Registry-initiated end: drains + releases the lease, marks ended, and the
        // session's own teardown emits terminal phases (Ending/Idle). The collector
        // terminal op must be a queue-safe no-op (call already ended, lease already
        // released) — NOT a double-release that would throw inside the arbiter.
        h.endCall(a)
        ShadowLooper.idleMainLooper()

        assertNull(h.registry.state.value.activeCallId)
        // The session was asked to end the room for all participants.
        assertEquals(1, h.created["a"]!!.fakeProvider.endCalls)
        // No lease leaked and no double-release wedged the arbiter: a fresh acquire
        // succeeds cleanly (would throw if the collector double-released).
        assertNull("mode released after registry end", ForegroundMediaArbiter.currentMode)
        val token = ForegroundMediaArbiter.acquireForeground(
            ownerId = "probe",
            mode = ForegroundArbiterMode.REGISTRY,
            modeOwnerRef = Any(),
        )
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))
        ForegroundMediaArbiter.releaseLease(token)
        // The ended call lingers until dismissed.
        assertTrue(h.registry.state.value.calls.any { it.callId == a })
    }
}
