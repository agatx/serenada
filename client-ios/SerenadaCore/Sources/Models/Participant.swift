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

public struct Participant: Codable, Equatable {
    public let cid: String
    public let joinedAt: Int64?
    public let displayName: String?
    /// Host-supplied stable identity; opaque to the SDK, surfaced for avatar lookup.
    public let peerId: String?
    public let audioEnabled: Bool?
    public let videoEnabled: Bool?
    public let signalingStatus: ParticipantSignalingStatus

    public init(
        cid: String,
        joinedAt: Int64?,
        displayName: String? = nil,
        peerId: String? = nil,
        audioEnabled: Bool? = nil,
        videoEnabled: Bool? = nil,
        signalingStatus: ParticipantSignalingStatus = .active
    ) {
        self.cid = cid
        self.joinedAt = joinedAt
        self.displayName = displayName
        self.peerId = peerId
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.signalingStatus = signalingStatus
    }
}
