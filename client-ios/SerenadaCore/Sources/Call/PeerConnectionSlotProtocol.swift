import Foundation

internal struct OutboundMediaSample: Equatable {
    let expectsAudio: Bool
    let expectsVideo: Bool
    let audioBytesSent: Int64
    let videoBytesSent: Int64
    let videoFramesSent: Int64
}

@MainActor
internal protocol PeerConnectionSlotProtocol: AnyObject {
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
    func duckPlayback(ducked: Bool)

    /// Last observed path type for the selected ICE candidate pair: `true`
    /// for direct (host/srflx/prflx), `false` for relayed through TURN,
    /// `nil` if no stats sample has been collected yet. Used by the TURN
    /// refresh gate so a purely-P2P call can suppress refreshes.
    func isPathDirect() -> Bool?

    // Renderer management
    func attachRemoteRenderer(_ renderer: AnyObject)
    func detachRemoteRenderer(_ renderer: AnyObject)

    // Stats
    func collectRealtimeCallStats(onComplete: @escaping (RealtimeCallStats) -> Void)
    func collectRealtimeCallStatsAndSummary(onComplete: @escaping (RealtimeCallStats, String) -> Void)

    /// Asynchronously samples cumulative inbound `bytesReceived` across all
    /// inbound-rtp stats for this peer. Used by the media-liveness emitter
    /// (see SerenadaSession.startMediaLivenessTimer); a CID is "flowing"
    /// when its sample advances over the previous one. Reports `0` when
    /// the peer connection is not yet established.
    func collectInboundBytes(onComplete: @escaping (Int64) -> Void)

    /// Asynchronously samples cumulative outbound media counters and whether
    /// local enabled tracks are expected to be flowing on this peer.
    func collectOutboundMediaSample(onComplete: @escaping (OutboundMediaSample?) -> Void)

    /// Lightweight stats fetch for voice-activity indicators. Extracts only
    /// `inbound-rtp.audioLevel` (the remote peer's audio) and
    /// `media-source.audioLevel` (the locally captured mic). Either may be
    /// `nil` if stats haven't populated yet. Callback fires on the main actor.
    func collectAudioLevels(onComplete: @escaping (_ inboundLevel: Float?, _ mediaSourceLevel: Float?) -> Void)
}
