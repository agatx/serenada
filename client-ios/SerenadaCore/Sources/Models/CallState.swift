import Foundation

/// Phase of the call lifecycle.
public enum SerenadaCallPhase: String, Equatable, Sendable {
    /// No active call.
    case idle
    /// Waiting for the user to grant camera/microphone permissions.
    case awaitingPermissions
    /// Connecting to the signaling server and joining the room.
    case joining
    /// Connected and waiting for another participant to join.
    case waiting
    /// Active call with at least one remote participant.
    case inCall
    /// Call is ending (brief transition before returning to idle).
    case ending
    /// An error occurred; check ``CallState/error``.
    case error
}

/// The local participant in a call.
public struct LocalParticipant: Equatable {
    /// Client identifier assigned by the server.
    public var cid: String?
    /// Display name shown to other participants.
    public var displayName: String?
    /// Whether local audio is enabled.
    public var audioEnabled: Bool = true
    /// Whether local video is enabled.
    public var videoEnabled: Bool = true
    /// Current camera mode (selfie, world, or composite).
    public var cameraMode: LocalCameraMode = .selfie
    /// Whether this participant is the room host.
    public var isHost: Bool = false

    public init() {}

    public init(cid: String?, displayName: String? = nil, audioEnabled: Bool = true, videoEnabled: Bool = true, cameraMode: LocalCameraMode = .selfie, isHost: Bool = false) {
        self.cid = cid
        self.displayName = displayName
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.cameraMode = cameraMode
        self.isHost = isHost
    }
}

/// A remote participant in the call.
public struct SerenadaRemoteParticipant: Identifiable, Equatable {
    /// Client identifier.
    public let cid: String
    /// Display name of the remote participant.
    public var displayName: String?
    /// Whether remote audio is enabled.
    public var audioEnabled: Bool
    /// Whether remote video is enabled.
    public var videoEnabled: Bool
    /// WebRTC peer connection state for this participant.
    public var connectionState: SerenadaPeerConnectionState
    /// Signaling transport status as reported by the server. `.suspended`
    /// means the participant's signaling transport dropped and the server
    /// is holding their slot open for reconnect — the peer connection to
    /// them is intentionally kept alive. UIs should show a "reconnecting"
    /// indicator instead of rendering them as gone.
    public var signalingStatus: ParticipantSignalingStatus

    public var id: String { cid }

    public init(
        cid: String,
        displayName: String? = nil,
        audioEnabled: Bool = true,
        videoEnabled: Bool = true,
        connectionState: SerenadaPeerConnectionState = .new,
        signalingStatus: ParticipantSignalingStatus = .active
    ) {
        self.cid = cid
        self.displayName = displayName
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.connectionState = connectionState
        self.signalingStatus = signalingStatus
    }
}

/// Overall connection health status.
public enum SerenadaConnectionStatus: String, Equatable, Sendable {
    /// Fully connected.
    case connected
    /// Temporarily degraded, attempting automatic recovery.
    case recovering
    /// Connection lost, actively retrying.
    case retrying
}

/// A media capability that may require user permission.
public enum MediaCapability: String, Equatable, Sendable {
    case camera
    case microphone
}

/// Errors that can occur during a call.
public enum CallError: Equatable, Sendable {
    /// Signaling connection timed out.
    case signalingTimeout
    /// WebRTC connection failed.
    case connectionFailed
    /// Room is at capacity.
    case roomFull
    /// Room was ended by another participant or the server.
    case roomEnded
    /// Required media permissions were denied.
    case permissionDenied
    /// Server returned an error.
    case serverError(String)
    /// An unknown error occurred.
    case unknown(String)
}

/// Primary observable state for SDK consumers. Contains everything needed to render a call UI.
public struct CallState: Equatable {
    /// Current call phase.
    public var phase: SerenadaCallPhase = .idle
    /// Room identifier, if joined.
    public var roomId: String?
    /// Full room URL, if available.
    public var roomUrl: URL?
    /// The local participant.
    public var localParticipant = LocalParticipant()
    /// Remote participants currently in the call.
    public var remoteParticipants: [SerenadaRemoteParticipant] = []
    /// Overall connection health.
    public var connectionStatus: SerenadaConnectionStatus = .connected
    /// Permissions that must be granted before joining, if any.
    public var requiredPermissions: [MediaCapability]?
    /// Current error, if the phase is `.error`.
    public var error: CallError?

    public init() {}
}
