import type { ManagedCallState } from '@agatx/serenada-core';

/**
 * What `CallRoom` should render for the current registry snapshot. Pure decision
 * derived from the registry state so it is unit-testable without a DOM.
 *
 * Active-call-only rendering contract (multi-call session contract §5, §7, §11;
 * design "Web Implementation Shape → Remote Playback" / "React UI"): the audible
 * `SerenadaCallFlow` is mounted with the foreground (active) call's session and
 * NOTHING ELSE. A held / no-lease session must never be mounted as the active
 * flow — only the foreground call owns audible media elements.
 *
 * - `'active'`   — render `<SerenadaCallFlow session={activeCall.session} />`.
 * - `'joining'`  — a join is in progress (no active session yet); show a joining
 *                  placeholder, NOT the in-flight (still-held) session.
 * - `'held'`     — the active call ended with no auto-promote (Core Invariant 5)
 *                  but live held calls remain; show a "calls on hold" placeholder
 *                  / switcher the user can `switchTo` from. The held session is
 *                  NOT mounted as the active flow.
 * - `'idle'`     — no live calls; show the normal idle/empty (prejoin) state.
 */
export type CallView = 'active' | 'joining' | 'held' | 'idle';

/**
 * A managed call has live room membership when it is past `idle` and not yet
 * torn down. Held calls in `waiting`/`inCall` count as live; `ending`/`error`/
 * `idle` do not. (`CallPhase` has no `ended` value — teardown ends at `ending`
 * before the call leaves the snapshot.)
 */
function isLiveCall(call: ManagedCallState): boolean {
    return (
        call.membershipPhase !== 'idle' &&
        call.membershipPhase !== 'ending' &&
        call.membershipPhase !== 'error'
    );
}

/**
 * A call whose foreground join/activation has terminally failed (contract §11):
 * the registry surfaced an `activationError` (e.g. `joinFailed` on a held-join
 * timeout, where the session phase can still read `joining`) or the session
 * itself landed in the `error` phase. A failed call must NOT be treated as a
 * genuinely in-flight join — otherwise a failed/timeout second join (P5-6) is
 * counted as "joining" and masks a surviving held call behind a stuck
 * "Joining…" placeholder.
 */
function isFailedCall(call: ManagedCallState): boolean {
    return call.activationError !== null || call.membershipPhase === 'error';
}

/**
 * A call whose join/activation is still genuinely in flight: its room membership
 * has not reached a stable `waiting`/`inCall` yet AND it has not terminally
 * failed. These are the "join in progress" calls — they must not be mounted as
 * the active flow either. A failed call (`activationError`/`error` phase) is
 * excluded so it cannot pose as a live join.
 */
function isJoinInFlight(call: ManagedCallState): boolean {
    if (isFailedCall(call)) return false;
    return call.membershipPhase === 'joining' || call.membershipPhase === 'awaitingPermissions';
}

export interface SelectCallViewInput {
    /** True once the active call's foreground session is available to render. */
    hasActiveSession: boolean;
    /** The reactive registry snapshot of all managed calls. */
    calls: ManagedCallState[];
    /** True while a queued registry operation (join/switch/hold/...) is running. */
    registryOperationInProgress: boolean;
}

/**
 * Decide what `CallRoom` renders. Precedence (active-call-only rendering):
 *
 *   active  > genuine-joining > held > idle
 *
 * The foreground session always wins. Otherwise a *genuine* in-flight join (or a
 * running registry op with a still-settling live call) shows a joining
 * placeholder; a settled live held call (active ended, Invariant 5) shows the
 * held placeholder; nothing live shows idle. A held session is NEVER selected as
 * the active flow.
 *
 * P5-6 unifying rule (parity with iOS/Android this round): a failed/errored call
 * must NOT mask a surviving held call. A failed join (`activationError`, or the
 * `error` phase — incl. a `joinFailed` timeout that left the session reading
 * `joining`) does NOT count as a live "joining" call, so held takes precedence
 * over it. Only a genuinely in-flight join (joining/awaitingPermissions WITHOUT
 * a failure) yields 'joining'.
 */
export function selectCallView(input: SelectCallViewInput): CallView {
    if (input.hasActiveSession) return 'active';

    const liveCalls = input.calls.filter(isLiveCall);
    if (liveCalls.length === 0) return 'idle';

    // A brand-new join is still settling (creating/awaiting/activating). Show a
    // joining placeholder rather than the in-flight (held) session. A running
    // registry op with a still-settling call (e.g. the foreground activation of
    // an initial join) is treated the same so single-call join shows "joining".
    // A FAILED call is excluded from this (isJoinInFlight returns false for it),
    // so a failed/timeout second join no longer poses as "joining".
    const hasInFlightJoin = liveCalls.some(isJoinInFlight);
    if (hasInFlightJoin || input.registryOperationInProgress) return 'joining';

    // No genuine join is in flight. If any settled, non-failed live call remains,
    // the active call ended with no auto-promote but a live held call survives
    // (Core Invariant 5): offer them as on-hold, resumable via switchTo. A failed
    // call must not mask such a held call, nor should a lone failed call (no held
    // sibling) be shown as 'held' — fall through to idle for that.
    const hasSettledHeld = liveCalls.some((call) => !isFailedCall(call));
    if (hasSettledHeld) return 'held';

    // Only failed call(s) remain live (e.g. a lone failed/timeout join with no
    // held sibling): nothing genuine to show. CallRoom surfaces the error and
    // routes to idle/prejoin.
    return 'idle';
}
