import Foundation

/// The mode of a call — video (default) or voice-only.
public enum CallMode: String, Equatable, Sendable {
    case video
    case voice
}
