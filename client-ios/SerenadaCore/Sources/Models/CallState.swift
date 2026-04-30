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
    /// Host-supplied stable identity passed via ``SerenadaCore/join(url:displayName:peerId:)``.
    /// Distinct from ``cid`` (per-call, server-issued).
    public var peerId: String?
    /// Whether local audio is enabled.
    public var audioEnabled: Bool = true
    /// Whether local video is enabled.
    public var videoEnabled: Bool = true
    /// Current camera mode (selfie, world, or composite).
    public var cameraMode: LocalCameraMode = .selfie
    /// Camera modes the user can cycle through, in preference order.
    /// Derived from `SerenadaConfig.cameraModes` minus modes unsupported on
    /// this device. Empty means video is unavailable — call UIs should hide
    /// the video toggle.
    public var availableCameraModes: [LocalCameraMode] = defaultCameraModes
    /// Whether this participant is the room host.
    public var isHost: Bool = false
    /// Smoothed voice activity level (0..1) for the locally captured mic.
    /// Updated at ~10 Hz while the call is active; intended to drive UI
    /// activity indicators. Always 0 when ``audioEnabled`` is false.
    public var audioLevel: Float = 0

    public init() {}

    public init(
        cid: String?,
        displayName: String? = nil,
        peerId: String? = nil,
        audioEnabled: Bool = true,
        videoEnabled: Bool = true,
        cameraMode: LocalCameraMode = .selfie,
        availableCameraModes: [LocalCameraMode] = defaultCameraModes,
        isHost: Bool = false,
        audioLevel: Float = 0
    ) {
        self.cid = cid
        self.displayName = displayName
        self.peerId = peerId
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.cameraMode = cameraMode
        self.availableCameraModes = availableCameraModes
        self.isHost = isHost
        self.audioLevel = audioLevel
    }
}

/// A remote participant in the call.
public struct SerenadaRemoteParticipant: Identifiable, Equatable {
    /// Client identifier.
    public let cid: String
    /// Display name of the remote participant.
    public var displayName: String?
    /// Host-supplied stable identity passed via the remote peer's
    /// ``SerenadaCore/join(url:displayName:peerId:)``. Distinct from ``cid``
    /// (per-call, server-issued) — call UIs use this to look up avatars or
    /// correlate to host-side records.
    public var peerId: String?
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
    /// `true` when this peer has been suspended longer than
    /// ``WebRtcResilience/peerSuspendedUiTimeoutMs`` and the SDK has flipped
    /// its UI presentation to "presumed lost." The peer connection is
    /// intentionally left open so media can resume immediately if the peer
    /// reattaches; this flag is purely a UI hint that call shells can use
    /// to move the participant out of the active grid or show a "connection
    /// lost" badge. Cleared when the peer transitions back to `.active`.
    public var presumedLost: Bool
    /// Smoothed voice activity level (0..1) for this peer's inbound audio.
    /// Updated at ~10 Hz while the call is active; intended to drive UI
    /// activity indicators. Always 0 when ``audioEnabled`` is false.
    public var audioLevel: Float = 0

    public var id: String { cid }

    public init(
        cid: String,
        displayName: String? = nil,
        peerId: String? = nil,
        audioEnabled: Bool = true,
        videoEnabled: Bool = true,
        connectionState: SerenadaPeerConnectionState = .new,
        signalingStatus: ParticipantSignalingStatus = .active,
        presumedLost: Bool = false,
        audioLevel: Float = 0
    ) {
        self.cid = cid
        self.displayName = displayName
        self.peerId = peerId
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.connectionState = connectionState
        self.signalingStatus = signalingStatus
        self.presumedLost = presumedLost
        self.audioLevel = audioLevel
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
    /// The persisted reconnect credential is no longer valid (expired or
    /// rejected). The SDK must clear stored reconnect state and surface a
    /// dedicated terminal error so the host app can route the user back to
    /// a fresh start instead of looping reconnects.
    case sessionExpired
    /// Required media permissions were denied.
    case permissionDenied
    /// Server returned an error.
    case serverError(String)
    /// An unknown error occurred.
    case unknown(String)
}

/// Richer view of the local signaling transport state. Apps can use this to
/// render reconnect spinners, "you have been disconnected" UI, and a hard-
/// eviction countdown when applicable. ``CallState/connectionStatus`` remains
/// the simpler tri-value summary.
public enum SignalingState: Equatable, Sendable {
    case connected
    /// Actively retrying to (re)connect.
    /// - Parameter attempt: consecutive reconnect attempts since the transport last dropped.
    /// - Parameter nextRetryAtMs: wall-clock ms for the next scheduled retry, or `nil` if a retry is in flight.
    case reconnecting(attempt: Int, nextRetryAtMs: Int64?)
    /// Mid-call transport drop. The server is holding the participant slot
    /// for `suspendHardEvictionTimeout` (10 min); apps can render a countdown
    /// using ``estimatedHardEvictionAtMs``.
    /// - Parameter suspendedSinceMs: wall-clock ms when the local transport last dropped.
    /// - Parameter estimatedHardEvictionAtMs: computed locally from
    ///   `suspendedSinceMs + WebRtcResilience.suspendHardEvictionTimeoutMs`.
    ///   Best-effort — server media-liveness hints can extend retention.
    case suspended(suspendedSinceMs: Int64, estimatedHardEvictionAtMs: Int64)
    /// Terminal failure; see `reason`.
    case failed(reason: CallError)
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
    /// Richer signaling-transport state with timing details. Apps that don't
    /// need the extra detail can stick with ``connectionStatus``.
    public var signalingState: SignalingState = .connected
    /// Permissions that must be granted before joining, if any.
    public var requiredPermissions: [MediaCapability]?
    /// Current error, if the phase is `.error`.
    public var error: CallError?

    public init() {}
}
