import Foundation

public enum SerenadaCallPhase: String, Equatable, Sendable {
    case idle
    case awaitingPermissions
    case joining
    case waiting
    case inCall
    case ending
    case error
}

public struct LocalParticipant: Equatable {
    public var cid: String?
    public var audioEnabled: Bool = true
    public var videoEnabled: Bool = true
    public var cameraMode: LocalCameraMode = .selfie
    public var isHost: Bool = false

    public init() {}

    public init(cid: String?, audioEnabled: Bool = true, videoEnabled: Bool = true, cameraMode: LocalCameraMode = .selfie, isHost: Bool = false) {
        self.cid = cid
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.cameraMode = cameraMode
        self.isHost = isHost
    }
}

public struct SerenadaRemoteParticipant: Identifiable, Equatable {
    public let cid: String
    public var audioEnabled: Bool
    public var videoEnabled: Bool
    public var connectionState: String

    public var id: String { cid }

    public init(cid: String, audioEnabled: Bool = true, videoEnabled: Bool = true, connectionState: String = "NEW") {
        self.cid = cid
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.connectionState = connectionState
    }
}

public enum SerenadaConnectionStatus: String, Equatable, Sendable {
    case connected
    case recovering
    case retrying
}

public enum MediaCapability: String, Equatable, Sendable {
    case camera
    case microphone
}

public enum CallError: Equatable, Sendable {
    case signalingTimeout
    case connectionFailed
    case roomFull
    case serverError(String)
    case unknown(String)
}

public struct CallState: Equatable {
    public var phase: SerenadaCallPhase = .idle
    public var roomId: String?
    public var roomUrl: URL?
    public var localParticipant = LocalParticipant()
    public var remoteParticipants: [SerenadaRemoteParticipant] = []
    public var connectionStatus: SerenadaConnectionStatus = .connected
    public var requiredPermissions: [MediaCapability]?
    public var error: CallError?

    public init() {}
}
