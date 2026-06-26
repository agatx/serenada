package app.serenada.core.call

/**
 * Progress of foreground-media activation for a call.
 *
 * Orthogonal to [CallPhase] (room membership) and [CallMediaRole] (lease
 * ownership). Permission state lives here, not in [CallPhase]: needing a
 * mic/camera grant is not a room-membership condition. Only meaningful for the
 * call being foregrounded; held calls sit at [INACTIVE].
 */
enum class MediaActivationState {
    /** No foreground media; the call is held or has not activated. */
    INACTIVE,

    /** Foreground media is being acquired (lease + capture + audio). */
    ACTIVATING,

    /** Foreground media is fully active. */
    ACTIVE,

    /** A required mic/camera permission for the desired media is missing. */
    NEEDS_PERMISSION,

    /** Foreground activation failed for a non-permission reason. */
    FAILED,
}
