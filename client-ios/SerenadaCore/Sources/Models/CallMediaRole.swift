import Foundation

/// Which foreground-media role a session currently holds.
///
/// `mediaRole` is one of the three orthogonal call-state axes (membership phase,
/// media role, media activation state) — see the multi-call session design.
/// `held` is **not** a membership phase: a held session stays connected and
/// signaled, it just owns no local capture, screen share, audio routing, or
/// audible remote playout.
///
/// Phase 1 toggles this internally via `applyForegroundRoleInternal()` /
/// `applyHeldRoleInternal()`. The token-gated registry-owned API
/// (`activateForeground`/`releaseForeground`) wraps these in Phase 2.
public enum CallMediaRole: String, Equatable, Sendable {
    /// Owns microphone/camera/screen-share capture, OS audio routing, audible
    /// remote playout, and the primary renderers. At most one session per
    /// process is `foreground`.
    case foreground
    /// Stays connected and signaled but owns no local capture or audible
    /// playout. Desired media intent is preserved for resume.
    case held
}
