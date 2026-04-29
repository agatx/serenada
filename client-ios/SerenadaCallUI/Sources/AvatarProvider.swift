import Foundation
import UIKit

/// Resolves an avatar for a remote participant's host-supplied `peerId`
/// (passed to `SerenadaCore.join`). The call UI renders the returned avatar
/// cover-fit, cropped to a circle above the participant's name when their
/// remote video track is off.
///
/// Behavior:
/// - Each `peerId` is resolved at most once per call and cached for the call's lifetime.
/// - Called lazily on the first frame the placeholder is needed — silent peers don't
///   trigger a fetch.
/// - Returning `nil` or throwing falls back to an initials placeholder.
/// - The call UI never blocks on the resolver; it shows initials immediately and
///   swaps in the avatar when the async function returns.
public protocol AvatarProvider: Sendable {
    func resolve(peerId: String) async -> AvatarSource?
}

/// Image payload returned by an `AvatarProvider`.
public enum AvatarSource: Sendable {
    /// A remote URL that the call UI will fetch with `URLSession`.
    case url(URL)
    /// Encoded image bytes (e.g. JPEG, PNG); decoded once at render time.
    case data(Data)
    /// A pre-decoded `UIImage`.
    case image(UIImage)
}
