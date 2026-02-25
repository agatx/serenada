import Foundation

struct CallUiState: Equatable {
    var phase: CallPhase = .idle
    var roomId: String?
    var statusMessage: String?
    var errorMessage: String?
    var isHost: Bool = false
    var participantCount: Int = 0
    var localAudioEnabled: Bool = true
    var localVideoEnabled: Bool = true
    var remoteVideoEnabled: Bool = false
    var isReconnecting: Bool = false
    var isSignalingConnected: Bool = false
    var iceConnectionState: String = "NEW"
    var connectionState: String = "NEW"
    var signalingState: String = "STABLE"
    var activeTransport: String?
    var webrtcStatsSummary: String = ""
    var realtimeStats: RealtimeCallStats = .empty
    var isFrontCamera: Bool = true
    var isScreenSharing: Bool = false
    var localCameraMode: LocalCameraMode = .selfie
    var cameraZoomFactor: Double = 1
    var isFlashAvailable: Bool = false
    var isFlashEnabled: Bool = false
}
