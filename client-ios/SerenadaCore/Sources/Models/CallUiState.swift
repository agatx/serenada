import Foundation

public enum ConnectionStatus: String, Equatable {
    case connected
    case recovering
    case retrying
}

public struct CallUiState: Equatable {
    public var phase: CallPhase = .idle
    public var roomId: String?
    public var localCid: String?
    public var statusMessage: String?
    public var errorMessage: String?
    public var isHost: Bool = false
    public var participantCount: Int = 0
    public var localAudioEnabled: Bool = true
    public var localVideoEnabled: Bool = true
    public var remoteParticipants: [RemoteParticipant] = []
    public var connectionStatus: ConnectionStatus = .connected
    public var isSignalingConnected: Bool = false
    public var iceConnectionState: String = "NEW"
    public var connectionState: String = "NEW"
    public var signalingState: String = "STABLE"
    public var activeTransport: String?
    public var webrtcStatsSummary: String = ""
    public var realtimeStats: RealtimeCallStats = .empty
    public var isFrontCamera: Bool = true
    public var isScreenSharing: Bool = false
    public var localCameraMode: LocalCameraMode = .selfie
    public var cameraZoomFactor: Double = 1
    public var isFlashAvailable: Bool = false
    public var isFlashEnabled: Bool = false
    public var remoteContentCid: String?
    public var remoteContentType: String?

    public var remoteVideoEnabled: Bool {
        remoteParticipants.first?.videoEnabled ?? false
    }

    public init() {}

    public init(phase: CallPhase = .idle, roomId: String? = nil, errorMessage: String? = nil) {
        self.phase = phase
        self.roomId = roomId
        self.errorMessage = errorMessage
    }
}
