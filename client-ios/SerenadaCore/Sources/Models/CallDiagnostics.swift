import Foundation

public enum IceConnectionState: String, Equatable, Sendable {
    case new = "NEW"
    case checking = "CHECKING"
    case connected = "CONNECTED"
    case completed = "COMPLETED"
    case disconnected = "DISCONNECTED"
    case failed = "FAILED"
    case closed = "CLOSED"
    case count = "COUNT"
    case unknown = "UNKNOWN"

    init(rawValueOrUnknown rawValue: String) {
        self = IceConnectionState(rawValue: rawValue) ?? .unknown
    }
}

public enum PeerConnectionState: String, Equatable, Sendable {
    case new = "NEW"
    case connecting = "CONNECTING"
    case connected = "CONNECTED"
    case disconnected = "DISCONNECTED"
    case failed = "FAILED"
    case closed = "CLOSED"
    case unknown = "UNKNOWN"

    init(rawValueOrUnknown rawValue: String) {
        self = PeerConnectionState(rawValue: rawValue) ?? .unknown
    }
}

public enum SignalingState: String, Equatable, Sendable {
    case stable = "STABLE"
    case haveLocalOffer = "HAVE_LOCAL_OFFER"
    case haveRemoteOffer = "HAVE_REMOTE_OFFER"
    case haveLocalPranswer = "HAVE_LOCAL_PRANSWER"
    case haveRemotePranswer = "HAVE_REMOTE_PRANSWER"
    case closed = "CLOSED"
    case unknown = "UNKNOWN"

    init(rawValueOrUnknown rawValue: String) {
        self = SignalingState(rawValue: rawValue) ?? .unknown
    }
}

public enum FeatureDegradation: String, Equatable, Sendable {
    case compositeCameraUnavailable
}

public struct FeatureDegradationState: Equatable, Sendable {
    public var kind: FeatureDegradation
    public var reason: String?

    public init(kind: FeatureDegradation, reason: String? = nil) {
        self.kind = kind
        self.reason = reason
    }
}

public struct CallDiagnostics: Equatable {
    public var isSignalingConnected = false
    public var iceConnectionState: IceConnectionState = .new
    public var peerConnectionState: PeerConnectionState = .new
    public var rtcSignalingState: SignalingState = .stable
    public var activeTransport: String?
    public var realtimeStats: RealtimeCallStats = .empty
    public var callStats = CallStats()
    public var isFrontCamera = true
    public var isScreenSharing = false
    public var cameraZoomFactor: Double = 1
    public var isFlashAvailable = false
    public var isFlashEnabled = false
    public var remoteContentParticipantId: String?
    public var remoteContentType: String?
    public var featureDegradations: [FeatureDegradationState] = []

    public init() {}
}
