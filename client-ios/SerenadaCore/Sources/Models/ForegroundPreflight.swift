import Foundation

/// Result of ``SerenadaSession/preflightForeground()`` — a PURE check of whether
/// a session can take the foreground media lease with its desired media, run
/// before the registry releases the current foreground call (Core Invariant 4).
///
/// Preflight opens no permission prompt and starts no capture; the host owns the
/// prompt. See the multi-call session contract §3.
public enum ForegroundPreflight: String, Equatable, Sendable {
    /// The session can foreground: every required device permission for its
    /// desired media is already granted (or the desired media needs no device:
    /// `desiredAudioEnabled == false && desiredVideoMode == off`).
    case ok
    /// A required device permission for the desired media is not granted. The
    /// host should prompt and retry.
    case needsPermission
    /// A non-permission precondition blocks foreground (for example, no audio
    /// route).
    case failed
}
