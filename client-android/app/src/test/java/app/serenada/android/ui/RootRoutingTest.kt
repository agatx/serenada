package app.serenada.android.ui

import app.serenada.core.CallId
import app.serenada.core.CallRegistryState
import app.serenada.core.ManagedCallState
import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.CallPhase
import app.serenada.core.call.MediaActivationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure root-routing decision (multi-call session, P5-4 fix):
 * choose the "calls on hold" surface vs Join when there is no active call. Verifies
 * held calls stay reachable after the active call ends (Core Invariant 5, no
 * auto-promote) while preserving single-call UX (last call → Join).
 */
class RootRoutingTest {

    private fun managedCall(
        id: CallId,
        phase: CallPhase,
        role: CallMediaRole = CallMediaRole.HELD,
    ) = ManagedCallState(
        callId = id,
        roomId = "room-$id",
        roomUrl = null,
        membershipPhase = phase,
        mediaRole = role,
        mediaActivationState = MediaActivationState.INACTIVE,
        desiredAudioEnabled = true,
        desiredVideoMode = null,
        actualAudioPublished = false,
        actualVideoPublished = false,
        participantCount = 1,
        localCid = null,
        held = role == CallMediaRole.HELD,
        displayName = null,
        activationError = null,
        qualitySummary = null,
    )

    private fun state(
        calls: List<ManagedCallState>,
        activeCallId: CallId? = null,
    ) = CallRegistryState(calls = calls, activeCallId = activeCallId)

    @Test
    fun noCalls_doesNotShowHolding() {
        // Single-call UX: last call ended, registry empty → fall back to Join.
        assertFalse(
            RootRouting.shouldShowHoldingSurface(state(emptyList()), showingActiveCall = false),
        )
    }

    @Test
    fun onlyTerminalCalls_doesNotShowHolding() {
        // All calls reached Idle/Error: zero live calls → Join, not holding.
        val s = state(
            listOf(
                managedCall("a", CallPhase.Idle),
                managedCall("b", CallPhase.Error),
            ),
        )
        assertFalse(RootRouting.hasLiveCalls(s))
        assertFalse(RootRouting.shouldShowHoldingSurface(s, showingActiveCall = false))
    }

    @Test
    fun activeCallEndedWithHeldRemaining_showsHolding() {
        // The active call ended (dropped to Idle), a held call is still connected,
        // and there is no foreground call (no auto-promote). Show the holding surface
        // so the held call is reachable via switchToCall.
        val s = state(
            listOf(
                managedCall("ended", CallPhase.Idle, role = CallMediaRole.FOREGROUND),
                managedCall("held", CallPhase.InCall, role = CallMediaRole.HELD),
            ),
            activeCallId = null,
        )
        assertTrue(RootRouting.hasLiveCalls(s))
        assertTrue(RootRouting.shouldShowHoldingSurface(s, showingActiveCall = false))
    }

    @Test
    fun heldCallStillEnding_isLive_showsHolding() {
        // CallPhase.Ending is still live (teardown in flight), so the held surface
        // stays up until the call reaches Idle/Error.
        val s = state(
            listOf(managedCall("held", CallPhase.Ending)),
            activeCallId = null,
        )
        assertTrue(RootRouting.hasLiveCalls(s))
        assertTrue(RootRouting.shouldShowHoldingSurface(s, showingActiveCall = false))
    }

    @Test
    fun activeCallPresent_doesNotShowHolding() {
        // There IS a foreground call: the active call screen renders, never holding.
        val s = state(
            listOf(
                managedCall("active", CallPhase.InCall, role = CallMediaRole.FOREGROUND),
                managedCall("held", CallPhase.InCall, role = CallMediaRole.HELD),
            ),
            activeCallId = "active",
        )
        assertFalse(RootRouting.shouldShowHoldingSurface(s, showingActiveCall = true))
    }

    @Test
    fun activeCallScreenShowing_doesNotShowHolding_evenWithoutActiveId() {
        // Defensive: while the active-call screen is being shown (uiState Waiting/
        // InCall), the holding surface is never the fallback regardless of registry
        // activeCallId timing.
        val s = state(
            listOf(managedCall("held", CallPhase.InCall)),
            activeCallId = null,
        )
        assertFalse(RootRouting.shouldShowHoldingSurface(s, showingActiveCall = true))
    }
}
