import Foundation

internal struct OutboundMediaSample: Equatable {
    let expectsAudio: Bool
    let expectsVideo: Bool
    let audioBytesSent: Int64
    let videoBytesSent: Int64
    let videoFramesSent: Int64
}

/// Cumulative inbound VIDEO `bytesReceived` for a peer, split by the bound
/// transceiver role (camera vs content). The per-role counterpart to
/// ``PeerConnectionSlotProtocol/collectInboundBytes(onComplete:)`` (which sums
/// ALL inbound RTP, audio-inclusive, for the server eviction-deferral signal).
///
/// Role attribution matches each `inbound-rtp` video stat's `trackIdentifier`
/// against the slot's bound CONTENT receiver track id; anything not positively
/// attributable to content (the camera role, a legacy single video track, or an
/// unattributable stat) is counted as camera. Legacy / flag-off peers therefore
/// route their one inbound video to `cameraBytes` and `contentBytes` stays 0.
/// Audio is excluded here.
internal struct RoleInboundBytes: Equatable {
    var cameraBytes: Int64
    var contentBytes: Int64
}

/// Combined inbound liveness sample from one WebRTC stats report.
internal struct InboundLivenessSample: Equatable {
    var inboundBytes: Int64
    var roleBytes: RoleInboundBytes
}

/// Per-peer, per-role inbound liveness derived by the session from successive
/// ``RoleInboundBytes`` samples: `true` for a role when its inbound video bytes
/// advanced since the previous sample (that role's video is flowing). Surfaced
/// as `cameraReceiving` / `contentReceiving` on the public remote participant.
/// Both `false` before the first sample (conservative) and for legacy/flag-off
/// peers' `content` role.
internal struct RoleLiveness: Equatable {
    var camera: Bool
    var content: Bool

    static let none = RoleLiveness(camera: false, content: false)
}

@MainActor
internal protocol PeerConnectionSlotProtocol: AnyObject {
    // Identity
    var remoteCid: String { get }

    /// Per-peer independent-content gate. `false` ⇒ legacy single-video path,
    /// byte-identical to today. See ``attachLocalTracks(audioTrack:cameraTrack:contentTrack:supportsIndependentContentVideo:)``.
    var supportsIndependentContentVideo: Bool { get }

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
    /// Route the current local tracks to this peer per its per-peer capability.
    ///
    /// - Legacy peers (``supportsIndependentContentVideo`` false): a SINGLE
    ///   video track on the single video transceiver — exactly today's path.
    ///   The engine passes the camera track normally, or the content (display)
    ///   track on the same `cameraTrack` parameter while a share is active so
    ///   the legacy single sender carries content with precedence over camera.
    /// - Independent-capable peers (``supportsIndependentContentVideo`` true):
    ///   the camera track rides the bound camera transceiver and the content
    ///   track rides the bound content transceiver (camera + screen share at
    ///   once). `contentTrack == nil` detaches the content sender.
    ///
    /// `legacyVideoCarriesContent` (FIX 2) is meaningful only on the legacy path:
    /// when true the single video track is the screen-share (display) track during
    /// an independent share, so the single sender gets the conservative content
    /// encoding profile instead of the camera default; restored to camera params
    /// when false. Ignored for capable peers (the content transceiver always gets
    /// the content profile).
    func attachLocalTracks(
        audioTrack: AnyObject?,
        cameraTrack: AnyObject?,
        contentTrack: AnyObject?,
        supportsIndependentContentVideo: Bool,
        legacyVideoCarriesContent: Bool
    )
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

    /// Attach a renderer to this peer's CONTENT (screen share) video track
    /// specifically. For independent-capable peers this is the content-role
    /// track bound by m-line order. Camera renderers continue to use
    /// ``attachRemoteRenderer(_:)``.
    func attachRemoteContentRenderer(_ renderer: AnyObject)
    func detachRemoteContentRenderer(_ renderer: AnyObject)

    // Stats
    func collectRealtimeCallStats(onComplete: @escaping (RealtimeCallStats) -> Void)
    func collectRealtimeCallStatsAndSummary(onComplete: @escaping (RealtimeCallStats, String) -> Void)

    /// Asynchronously samples cumulative inbound liveness from one stats report:
    /// all inbound RTP bytes for server `media_liveness` plus role-split
    /// inbound video bytes for camera/content stall diagnostics.
    func collectInboundLiveness(onComplete: @escaping (InboundLivenessSample) -> Void)

    /// Asynchronously samples cumulative inbound `bytesReceived` across all
    /// inbound-rtp stats for this peer. Kept for focused callers; the session
    /// uses ``collectInboundLiveness(onComplete:)`` to avoid duplicate stats
    /// collection on each media-liveness tick.
    func collectInboundBytes(onComplete: @escaping (Int64) -> Void)

    /// Asynchronously samples cumulative inbound VIDEO `bytesReceived` SPLIT by
    /// the bound transceiver role (camera vs content) — see ``RoleInboundBytes``.
    /// Kept for focused callers; the session uses
    /// ``collectInboundLiveness(onComplete:)`` to avoid duplicate stats
    /// collection on each media-liveness tick. Audio is excluded.
    func collectInboundRoleBytes(onComplete: @escaping (RoleInboundBytes) -> Void)

    /// Asynchronously samples cumulative outbound media counters and whether
    /// local enabled tracks are expected to be flowing on this peer.
    func collectOutboundMediaSample(onComplete: @escaping (OutboundMediaSample?) -> Void)

    /// Lightweight stats fetch for voice-activity indicators. Extracts only
    /// `inbound-rtp.audioLevel` (the remote peer's audio) and
    /// `media-source.audioLevel` (the locally captured mic). Either may be
    /// `nil` if stats haven't populated yet. Callback fires on the main actor.
    func collectAudioLevels(onComplete: @escaping (_ inboundLevel: Float?, _ mediaSourceLevel: Float?) -> Void)
}

extension PeerConnectionSlotProtocol {
    /// Default: legacy single-video path (byte-identical to today). Real slots
    /// override; fakes that don't care about independent routing inherit `false`.
    var supportsIndependentContentVideo: Bool { false }
}
