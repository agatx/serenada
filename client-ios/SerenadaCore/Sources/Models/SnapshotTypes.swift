import Foundation

/// Identifies which video stream a snapshot should capture.
public enum SnapshotSource: Equatable, Sendable {
    /// The local camera/screen stream.
    case local
    /// A specific remote participant's stream, addressed by their per-call CID.
    case remote(cid: String)
}

/// A single decoded JPEG frame plus its metadata.
public struct SnapshotResult: Equatable, Sendable {
    /// Encoded JPEG bytes at the source video track's full intrinsic resolution.
    public let jpegData: Data
    public let width: Int
    public let height: Int
    /// Wall-clock time the frame was captured, in milliseconds since epoch.
    public let timestampMs: Int64
    public let source: SnapshotSource

    public init(jpegData: Data, width: Int, height: Int, timestampMs: Int64, source: SnapshotSource) {
        self.jpegData = jpegData
        self.width = width
        self.height = height
        self.timestampMs = timestampMs
        self.source = source
    }
}

/// Errors thrown by `SerenadaSession.captureSnapshot`.
public enum SnapshotError: Error, Equatable, Sendable {
    /// The session has no active session, or the chosen stream has no track.
    case streamNotActive
    /// The track exists but has no video component.
    case noVideoTrack
    /// No frame arrived within the configured timeout.
    case captureTimeout
    /// Frame encoding failed (e.g., zero dimensions, no pixel buffer).
    case captureFailed(String)
    /// The source was malformed (currently unused; reserved for future variants).
    case unsupportedSource
}
