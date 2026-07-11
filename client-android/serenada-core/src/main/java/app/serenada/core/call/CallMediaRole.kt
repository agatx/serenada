package app.serenada.core.call

/**
 * Which call holds the single process-wide foreground media lease.
 *
 * Orthogonal to [CallPhase] (room membership) and [MediaActivationState]
 * (activation progress). A call may be `connected` (membership) while `HELD`
 * (role): the room stays signaled but the call owns no mic, camera, screen
 * share, or audible remote playout.
 *
 * Phase 1 mutates this only via the session-internal hold/resume mechanics
 * ([app.serenada.core.SerenadaSession.applyHeldRoleInternal] /
 * [app.serenada.core.SerenadaSession.applyForegroundRoleInternal]). Phase 2
 * wraps those in token-gated entry points owned by the registry/arbiter.
 */
enum class CallMediaRole {
    /** This call owns local capture, screen share, audio routing, and renderers. */
    FOREGROUND,

    /** This call stays signaled but owns no local capture or audible playout. */
    HELD,
}
