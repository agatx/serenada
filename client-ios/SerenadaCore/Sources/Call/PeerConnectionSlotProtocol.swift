import Foundation

@MainActor
public protocol PeerConnectionSlotProtocol: AnyObject {
    // Identity
    var remoteCid: String { get }

    // Offer state
    var sentOffer: Bool { get }
    var isMakingOffer: Bool { get }
    var pendingIceRestart: Bool { get }
    var lastIceRestartAt: TimeInterval { get }

    // Timer tasks
    var offerTimeoutTask: Task<Void, Never>? { get }
    var iceRestartTask: Task<Void, Never>? { get }
    var nonHostFallbackTask: Task<Void, Never>? { get }
    var nonHostFallbackAttempts: Int { get }

    // Offer lifecycle
    func beginOffer()
    func completeOffer()
    func markOfferSent()

    // ICE restart lifecycle
    func markPendingIceRestart()
    func clearPendingIceRestart()
    func recordIceRestart(nowMs: Int64)

    // Task management
    func setOfferTimeoutTask(_ task: Task<Void, Never>)
    func cancelOfferTimeout()
    func setIceRestartTask(_ task: Task<Void, Never>)
    func cancelIceRestartTask()
    func setNonHostFallbackTask(_ task: Task<Void, Never>)
    func cancelNonHostFallbackTask()
    func clearNonHostFallbackTask()
    func incrementNonHostFallbackAttempts()

    // WebRTC operations
    func setIceServers(_ servers: [IceServerConfig])
    @discardableResult func ensurePeerConnection() -> Bool
    func attachLocalTracks(audioTrack: AnyObject?, videoTrack: AnyObject?)
    func closePeerConnection()
    @discardableResult func createOffer(iceRestart: Bool, onSdp: @escaping (String) -> Void, onComplete: ((Bool) -> Void)?) -> Bool
    func createAnswer(onSdp: @escaping (String) -> Void, onComplete: ((Bool) -> Void)?)
    func setRemoteDescription(type: SessionDescriptionType, sdp: String, onComplete: ((Bool) -> Void)?)
    func rollbackLocalDescription(onComplete: ((Bool) -> Void)?)
    func addIceCandidate(_ candidate: IceCandidatePayload)

    // State queries
    func isReady() -> Bool
    func getConnectionState() -> SerenadaPeerConnectionState
    func getIceConnectionState() -> String
    func getSignalingState() -> String
    func hasRemoteDescription() -> Bool
    func isRemoteVideoTrackEnabled() -> Bool

    // Renderer management
    func attachRemoteRenderer(_ renderer: AnyObject)
    func detachRemoteRenderer(_ renderer: AnyObject)

    // Stats
    func collectRealtimeCallStats(onComplete: @escaping (RealtimeCallStats) -> Void)
    func collectRealtimeCallStatsAndSummary(onComplete: @escaping (RealtimeCallStats, String) -> Void)
}
