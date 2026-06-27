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

    /**
     * The unifying P5-7 rule: a whole-app error screen is shown ONLY when there is
     * no active call AND no live (held) call survives. If a live held call remains
     * after a join/switch failure or an active call's terminal error (Core
     * Invariant 5: no auto-promote), the user must land on the holding surface so
     * the surviving call stays reachable — the error is surfaced per-call/transient,
     * never as a whole-app screen that masks the held call.
     *
     * This is the single source of truth, shared by [SerenadaAppRoot] routing
     * (Holding takes precedence over Error) and by `CallManager` (do not set the
     * whole-app [CallPhase.Error] phase while live held calls remain).
     *
     * Single-call UX is preserved exactly: a lone call that errors with no held
     * call leaves zero live calls, so this returns true and the whole-app error
     * screen shows as before.
     */
    fun allowsWholeAppError(state: CallRegistryState): Boolean =
        state.activeCallId == null && !hasLiveCalls(state)

    /**
     * Root routing precedence for the bottom of the stack, below the higher-priority
     * overlays (settings, diagnostics, join-with-code) and the active-call screen:
     *
     *   active call → Holding (live held remain) → Error (only when nothing
     *   survives) → Join.
     *
     * Holding MUST beat Error: when no call is active but a live held call remains,
     * route to Holding even if an error is pending (the error is transient/per-call,
     * not a whole-app screen). Error wins only when [allowsWholeAppError] holds.
     *
     * @param hasError whether a whole-app error message is currently set.
     * @param showingActiveCall whether the active-call screen is already being shown.
     */
    fun resolveFallback(
        state: CallRegistryState,
        hasError: Boolean,
        showingActiveCall: Boolean,
    ): Fallback =
        when {
            showingActiveCall -> Fallback.CALL
            shouldShowHoldingSurface(state, showingActiveCall) -> Fallback.HOLDING
            hasError && allowsWholeAppError(state) -> Fallback.ERROR
            else -> Fallback.JOIN
        }

    /** Bottom-of-stack destination chosen by [resolveFallback]. */
    enum class Fallback { CALL, HOLDING, ERROR, JOIN }
}
