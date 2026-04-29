import Foundation

/// Signaling transport status for a participant as reported by the server.
/// `.active` means the participant's signaling transport is attached.
/// `.suspended` means the transport dropped but the server is holding their
/// room slot open for reconnect — peers MUST keep existing peer connections
/// alive during the suspend window so established media survives arbitrary-
/// length signaling outages.
public enum ParticipantSignalingStatus: String, Codable, Equatable {
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

    public init(
        active: Bool,
        contentType: String? = nil,
        updatedAtMs: Int64? = nil,
        epoch: Int64? = nil
    ) {
        self.active = active
        self.contentType = contentType
        self.updatedAtMs = updatedAtMs
        self.epoch = epoch
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

    public init(
        cid: String,
        joinedAt: Int64?,
        displayName: String? = nil,
        peerId: String? = nil,
        audioEnabled: Bool? = nil,
        videoEnabled: Bool? = nil,
        signalingStatus: ParticipantSignalingStatus = .active,
        contentState: ParticipantContentState? = nil
    ) {
        self.cid = cid
        self.joinedAt = joinedAt
        self.displayName = displayName
        self.peerId = peerId
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.signalingStatus = signalingStatus
        self.contentState = contentState
    }
}
