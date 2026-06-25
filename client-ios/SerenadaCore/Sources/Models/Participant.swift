import Foundation

/// Signaling transport status for a participant as reported by the server.
/// `.active` means the participant's signaling transport is attached.
/// `.suspended` means the transport dropped but the server is holding their
/// room slot open for reconnect — peers MUST keep existing peer connections
/// alive during the suspend window so established media survives arbitrary-
/// length signaling outages.
public enum ParticipantSignalingStatus: String, Codable, Equatable, Sendable {
    case active
    case suspended
}

/// Latest ephemeral content metadata for a participant (screen share,
/// content camera mode, etc.). Persisted on the server's participant record
/// so a peer reconnecting after a suspension reconstructs UI without waiting
/// for the sender to toggle again.
public struct ParticipantContentState: Codable, Equatable {
    public let active: Bool
    public let contentType: String?
    public let updatedAtMs: Int64?
    public let epoch: Int64?
    /// Per-`(cid, sid)` monotonically increasing generation marker for the
    /// owning participant's content state. Used by receivers to keep the
    /// highest revision and discard out-of-order/stale updates. `nil` when
    /// the sender/server is too old to emit it.
    public let revision: Int64?

    public init(
        active: Bool,
        contentType: String? = nil,
        updatedAtMs: Int64? = nil,
        epoch: Int64? = nil,
        revision: Int64? = nil
    ) {
        self.active = active
        self.contentType = contentType
        self.updatedAtMs = updatedAtMs
        self.epoch = epoch
        self.revision = revision
    }
}

/// Static build capabilities a participant advertised at `join`. Forwarded
/// verbatim by the server in `joined`/`room_state`. Missing keys take their
/// documented defaults when read through the typed accessors.
public struct ParticipantCapabilities: Codable, Equatable {
    /// Whether the participant's build can negotiate, send, receive, classify,
    /// expose, and render an independent content (screen-share) video stream.
    /// Absent on the wire → treated as `false`.
    public let independentContentVideo: Bool?

    public init(independentContentVideo: Bool? = nil) {
        self.independentContentVideo = independentContentVideo
    }
}

/// Per-session media policy a participant advertised at `join`. Immutable for
/// the session lifetime. Forwarded verbatim by the server.
public struct ParticipantMediaPolicy: Codable, Equatable {
    /// Whether this participant accepts any video media (camera or content).
    /// Absent on the wire → treated as `true` (audio-only compatibility
    /// boundary: only strict audio-only clients omit video, and those are new
    /// enough to always emit this field).
    public let videoMediaEnabled: Bool?

    public init(videoMediaEnabled: Bool? = nil) {
        self.videoMediaEnabled = videoMediaEnabled
    }
}

/// Disposition of a join, surfaced by the server in `joined.reconnect`.
/// Drives whether the SDK preserves media-active peer connections,
/// schedules dirty-pair renegotiation, or starts ground-up.
public enum ReconnectOutcome: String, Codable, Equatable, Sendable {
    case fresh
    case reattached
    case recovered
}

public struct Participant: Codable, Equatable {
    public let cid: String
    public let joinedAt: Int64?
    public let displayName: String?
    /// Host-supplied stable identity; opaque to the SDK, surfaced for avatar lookup.
    public let peerId: String?
    public let audioEnabled: Bool?
    public let videoEnabled: Bool?
    public let signalingStatus: ParticipantSignalingStatus
    public let contentState: ParticipantContentState?
    /// Static build capabilities advertised at `join`, forwarded by the server.
    public let capabilities: ParticipantCapabilities?
    /// Per-session media policy advertised at `join`, forwarded by the server.
    public let mediaPolicy: ParticipantMediaPolicy?

    public init(
        cid: String,
        joinedAt: Int64?,
        displayName: String? = nil,
        peerId: String? = nil,
        audioEnabled: Bool? = nil,
        videoEnabled: Bool? = nil,
        signalingStatus: ParticipantSignalingStatus = .active,
        contentState: ParticipantContentState? = nil,
        capabilities: ParticipantCapabilities? = nil,
        mediaPolicy: ParticipantMediaPolicy? = nil
    ) {
        self.cid = cid
        self.joinedAt = joinedAt
        self.displayName = displayName
        self.peerId = peerId
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.signalingStatus = signalingStatus
        self.contentState = contentState
        self.capabilities = capabilities
        self.mediaPolicy = mediaPolicy
    }
}
