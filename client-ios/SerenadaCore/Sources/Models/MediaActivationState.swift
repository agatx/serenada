import Foundation

/// How foreground-media activation is progressing for a session.
///
/// One of the three orthogonal call-state axes (membership phase, media role,
/// media activation state) — see the multi-call session design. Permission
/// state lives here, not in the membership phase: needing a mic/camera grant is
/// not a room-membership condition. A call can be `connected + held` and still
/// need permission before it can become foreground.
///
/// `mediaActivationState` is only meaningful for the call being foregrounded;
/// held calls sit at `inactive`.
public enum MediaActivationState: String, Equatable, Sendable {
    /// Not attempting to own foreground media (the normal state for a held call).
    case inactive
    /// Foreground activation requested; lease/media acquisition in progress.
    case activating
    /// Foreground lease and local media are active.
    case active
    /// A required mic/camera grant for the desired media is missing.
    case needsPermission
    /// Activation failed for a non-permission reason (for example, no audio route).
    case failed
}
