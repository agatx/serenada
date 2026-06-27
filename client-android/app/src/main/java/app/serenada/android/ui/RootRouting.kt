package app.serenada.android.ui

import app.serenada.core.CallRegistryState
import app.serenada.core.call.CallPhase

/**
 * Pure routing helpers for [SerenadaAppRoot] (multi-call session, P5-4 fix).
 *
 * After the active call ends with held calls still in the registry, the registry
 * and foreground service stay alive and do NOT auto-promote a held call (Core
 * Invariant 5). The host must still surface those held calls so the user can
 * resume one via `switchToCall`; otherwise they are unreachable and the app
 * silently falls back to the Join screen (the P5-4 regression).
 *
 * Single-call UX is preserved exactly: when the last call ends with NO held call
 * remaining the registry has zero live calls, so [hasLiveCalls] is false and the
 * host falls back to Join as before.
 */
object RootRouting {
    /**
     * A registry call is terminal for routing once it reaches [CallPhase.Idle] or
     * [CallPhase.Error] (it holds no media and does not keep a surface alive).
     * [CallPhase.Ending] is still live (teardown in flight). Mirrors the
     * foreground-service keep-alive predicate so the holding surface and the
     * service agree on "any live call remains".
     */
    fun isTerminal(phase: CallPhase): Boolean =
        phase == CallPhase.Idle || phase == CallPhase.Error

    /** True when the registry has at least one non-terminal (live) managed call. */
    fun hasLiveCalls(state: CallRegistryState): Boolean =
        state.calls.any { !isTerminal(it.membershipPhase) }

    /**
     * Whether to render the "calls on hold" surface instead of falling back to
     * Join. True only when there is NO active call screen to show but the registry
     * still has live (held) calls to resume. Callers handle the higher-priority
     * overlays (settings, diagnostics, join-with-code, the active call) first; this
     * decides the bottom of the stack between the holding surface and Join.
     *
     * @param showingActiveCall whether the active-call screen is already being shown
     *   (`uiState.phase` is Waiting/InCall) — when true there is a foreground call,
     *   so the holding surface is never the fallback.
     */
    fun shouldShowHoldingSurface(
        state: CallRegistryState,
        showingActiveCall: Boolean,
    ): Boolean =
        !showingActiveCall && state.activeCallId == null && hasLiveCalls(state)
}
