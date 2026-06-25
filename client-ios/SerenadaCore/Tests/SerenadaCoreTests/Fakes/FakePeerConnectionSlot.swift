import Foundation
@testable import SerenadaCore

@MainActor
final class FakePeerConnectionSlot: PeerConnectionSlotProtocol {
    let remoteCid: String
    let supportsIndependentContentVideo: Bool

    private(set) var sentOffer = false
    private(set) var isMakingOffer = false
    private(set) var pendingIceRestart = false
    private(set) var lastIceRestartAt: TimeInterval = 0
    private(set) var offerTimeoutTask: Task<Void, Never>?
    private(set) var iceRestartTask: Task<Void, Never>?

    // State machine
    private(set) var signalingState = "STABLE"
    private(set) var connectionState: SerenadaPeerConnectionState = .new
    private(set) var iceConnectionState = "NEW"
    private(set) var ready = true
    private(set) var remoteDescriptionSet = false

    // Call tracking
    private(set) var createOfferCalls = 0
    private(set) var createOfferIceRestartFlags: [Bool] = []
    private(set) var createAnswerCalls = 0
    private(set) var setRemoteDescriptionCalls: [(type: SessionDescriptionType, sdp: String)] = []
    private(set) var addedIceCandidates: [IceCandidatePayload] = []
    private(set) var appliedIceServerUrls: [[String]] = []
    private(set) var rollbackCalls = 0
    private(set) var closePeerConnectionCalled = false
    private(set) var ensurePeerConnectionCalls = 0
    var failNextRemoteOffer = false
    var failNextRemoteAnswer = false
    var failNextRollback = false

    // Callbacks for driving state changes
    private let onConnectionStateChange: ((String, String) -> Void)?
    private let onIceConnectionStateChange: ((String, String) -> Void)?
    private let onSignalingStateChange: ((String, String) -> Void)?
    private let onRenegotiationNeeded: ((String) -> Void)?

    init(
        remoteCid: String,
        supportsIndependentContentVideo: Bool = false,
        onConnectionStateChange: ((String, String) -> Void)? = nil,
        onIceConnectionStateChange: ((String, String) -> Void)? = nil,
        onSignalingStateChange: ((String, String) -> Void)? = nil,
        onRenegotiationNeeded: ((String) -> Void)? = nil
    ) {
        self.remoteCid = remoteCid
        self.supportsIndependentContentVideo = supportsIndependentContentVideo
        self.onConnectionStateChange = onConnectionStateChange
        self.onIceConnectionStateChange = onIceConnectionStateChange
        self.onSignalingStateChange = onSignalingStateChange
        self.onRenegotiationNeeded = onRenegotiationNeeded
    }

    // MARK: - Offer Lifecycle

    func beginOffer() { isMakingOffer = true }
    func completeOffer() { isMakingOffer = false }
    func markOfferSent() { sentOffer = true }

    // MARK: - ICE Restart Lifecycle

    func markPendingIceRestart() { pendingIceRestart = true }
    func clearPendingIceRestart() { pendingIceRestart = false }
    func recordIceRestart(nowMs: Int64) {
        lastIceRestartAt = TimeInterval(nowMs)
        pendingIceRestart = false
    }

    // MARK: - Task Management

    func setOfferTimeoutTask(_ task: Task<Void, Never>) {
        offerTimeoutTask?.cancel()
        offerTimeoutTask = task
    }

    func cancelOfferTimeout() {
        offerTimeoutTask?.cancel()
        offerTimeoutTask = nil
    }

    func setIceRestartTask(_ task: Task<Void, Never>) {
        iceRestartTask?.cancel()
        iceRestartTask = task
    }

    func cancelIceRestartTask() {
        iceRestartTask?.cancel()
        iceRestartTask = nil
    }

    // MARK: - WebRTC Operations

    func setIceServers(_ servers: [IceServerConfig]) {
        appliedIceServerUrls.append(servers.flatMap(\.urls))
        ready = true
    }

    @discardableResult
    func ensurePeerConnection() -> Bool {
        ensurePeerConnectionCalls += 1
        return ready
    }

    /// Records each role-aware attach so routing tests can assert on the per-peer
    /// camera/content track wiring without a real peer connection.
    struct AttachLocalTracksCall {
        let hasAudio: Bool
        let hasCamera: Bool
        let hasContent: Bool
        let supportsIndependentContentVideo: Bool
        let legacyVideoCarriesContent: Bool
    }
    private(set) var attachLocalTracksCalls: [AttachLocalTracksCall] = []
    /// When true, the NEXT `attachLocalTracks` carrying a content track models a
    /// per-peer content `replaceTrack` REJECT: the real slot's
    /// `replaceContentTrackWithFallback` verifies the assignment, and on a
    /// mismatch fires `onRenegotiationNeeded` while leaving `localContentTrack`
    /// set (durable retry) — it never throws. This lets a multi-peer attach test
    /// confirm the failing peer goes to recovery (renegotiation) WITHOUT being
    /// torn down, while a sibling peer's attach is unaffected. Auto-resets.
    var failNextContentAttachWithRenegotiation = false
    /// Count of content-attach rejections this fake simulated (for assertions).
    private(set) var contentAttachRejectionCount = 0
    func attachLocalTracks(
        audioTrack: AnyObject?,
        cameraTrack: AnyObject?,
        contentTrack: AnyObject?,
        supportsIndependentContentVideo: Bool,
        legacyVideoCarriesContent: Bool
    ) {
        attachLocalTracksCalls.append(
            AttachLocalTracksCall(
                hasAudio: audioTrack != nil,
                hasCamera: cameraTrack != nil,
                hasContent: contentTrack != nil,
                supportsIndependentContentVideo: supportsIndependentContentVideo,
                legacyVideoCarriesContent: legacyVideoCarriesContent
            )
        )
        if failNextContentAttachWithRenegotiation, contentTrack != nil {
            failNextContentAttachWithRenegotiation = false
            contentAttachRejectionCount += 1
            // Mirror the real slot's reject path: fall back to renegotiation,
            // do NOT throw, do NOT close the peer connection.
            onRenegotiationNeeded?(remoteCid)
        }
    }

    func closePeerConnection() {
        closePeerConnectionCalled = true
        cancelOfferTimeout()
        cancelIceRestartTask()
    }

    @discardableResult
    func createOffer(
        iceRestart: Bool = false,
        onSdp: @escaping (String) -> Void,
        onComplete: ((Bool) -> Void)? = nil
    ) -> Bool {
        createOfferCalls += 1
        createOfferIceRestartFlags.append(iceRestart)
        guard signalingState == "STABLE" else {
            onComplete?(false)
            return false
        }
        signalingState = "HAVE_LOCAL_OFFER"
        onSdp("fake-offer-sdp")
        onComplete?(true)
        return true
    }

    func createAnswer(onSdp: @escaping (String) -> Void, onComplete: ((Bool) -> Void)? = nil) {
        createAnswerCalls += 1
        onSdp("fake-answer-sdp")
        signalingState = "STABLE"
        onComplete?(true)
    }

    func setRemoteDescription(type: SessionDescriptionType, sdp: String, onComplete: ((Bool) -> Void)? = nil) {
        setRemoteDescriptionCalls.append((type: type, sdp: sdp))
        if type == .offer, failNextRemoteOffer {
            failNextRemoteOffer = false
            onComplete?(false)
            return
        }
        if type == .answer, failNextRemoteAnswer {
            failNextRemoteAnswer = false
            onComplete?(false)
            return
        }
        remoteDescriptionSet = true
        switch type {
        case .offer:
            signalingState = "HAVE_REMOTE_OFFER"
        case .answer:
            signalingState = "STABLE"
        case .rollback:
            signalingState = "STABLE"
        }
        onComplete?(true)
    }

    func rollbackLocalDescription(onComplete: ((Bool) -> Void)? = nil) {
        rollbackCalls += 1
        if failNextRollback {
            failNextRollback = false
            onComplete?(false)
            return
        }
        signalingState = "STABLE"
        onComplete?(true)
    }

    func addIceCandidate(_ candidate: IceCandidatePayload) {
        addedIceCandidates.append(candidate)
    }

    // MARK: - State Queries

    func isReady() -> Bool { ready }
    var pathDirectOverride: Bool?
    func isPathDirect() -> Bool? { pathDirectOverride }
    func getConnectionState() -> SerenadaPeerConnectionState { connectionState }
    func getIceConnectionState() -> String { iceConnectionState }
    func getSignalingState() -> String { signalingState }
    func hasRemoteDescription() -> Bool { remoteDescriptionSet }
    var remoteVideoTrackEnabledOverride = false
    func isRemoteVideoTrackEnabled() -> Bool { remoteVideoTrackEnabledOverride }
    private(set) var duckedPlayback = false
    func duckPlayback(ducked: Bool) {
        duckedPlayback = ducked
    }

    // MARK: - Renderer Management

    private(set) var attachRemoteRendererCalls: [AnyObject] = []
    private(set) var detachRemoteRendererCalls: [AnyObject] = []
    func attachRemoteRenderer(_ renderer: AnyObject) {
        attachRemoteRendererCalls.append(renderer)
    }
    func detachRemoteRenderer(_ renderer: AnyObject) {
        detachRemoteRendererCalls.append(renderer)
    }

    private(set) var attachRemoteContentRendererCalls: [AnyObject] = []
    private(set) var detachRemoteContentRendererCalls: [AnyObject] = []
    func attachRemoteContentRenderer(_ renderer: AnyObject) {
        attachRemoteContentRendererCalls.append(renderer)
    }
    func detachRemoteContentRenderer(_ renderer: AnyObject) {
        detachRemoteContentRendererCalls.append(renderer)
    }

    // MARK: - Stats

    /// Stats returned from the next `collectRealtimeCallStats()` call. Tests
    /// can set this to drive the StatsPoller merge + CallQualityTracker.
    /// Defaults to `.empty`, matching the previous behavior.
    var realtimeStatsSample: RealtimeCallStats = .empty
    var collectRealtimeCallStatsCalls = 0
    func collectRealtimeCallStats(onComplete: @escaping (RealtimeCallStats) -> Void) {
        collectRealtimeCallStatsCalls += 1
        onComplete(realtimeStatsSample)
    }

    func collectRealtimeCallStatsAndSummary(onComplete: @escaping (RealtimeCallStats, String) -> Void) {
        onComplete(.empty, "fake")
    }

    /// Cumulative inbound bytes returned from the next `collectInboundBytes()` call.
    var inboundBytesSample: Int64 = 0
    var collectInboundLivenessCalls = 0
    func collectInboundLiveness(onComplete: @escaping (InboundLivenessSample) -> Void) {
        collectInboundLivenessCalls += 1
        onComplete(InboundLivenessSample(
            inboundBytes: inboundBytesSample,
            roleBytes: roleInboundBytesSample
        ))
    }

    var collectInboundBytesCalls = 0
    func collectInboundBytes(onComplete: @escaping (Int64) -> Void) {
        collectInboundBytesCalls += 1
        onComplete(inboundBytesSample)
    }

    /// Per-role cumulative inbound video bytes returned from the next
    /// `collectInboundRoleBytes()` call. Drives the session's per-role liveness
    /// derivation (`cameraReceiving` / `contentReceiving`) without a real PC's
    /// inbound-rtp stats — tests advance these to model camera/content flow.
    var roleInboundBytesSample = RoleInboundBytes(cameraBytes: 0, contentBytes: 0)
    var collectInboundRoleBytesCalls = 0
    func collectInboundRoleBytes(onComplete: @escaping (RoleInboundBytes) -> Void) {
        collectInboundRoleBytesCalls += 1
        onComplete(roleInboundBytesSample)
    }
    var outboundMediaSample: OutboundMediaSample? = OutboundMediaSample(
        expectsAudio: true,
        expectsVideo: true,
        audioBytesSent: 0,
        videoBytesSent: 0,
        videoFramesSent: 0
    )
    private(set) var collectOutboundMediaSampleCalls = 0
    func collectOutboundMediaSample(onComplete: @escaping (OutboundMediaSample?) -> Void) {
        collectOutboundMediaSampleCalls += 1
        onComplete(outboundMediaSample)
    }
    func collectAudioLevels(onComplete: @escaping (_ inboundLevel: Float?, _ mediaSourceLevel: Float?) -> Void) {
        onComplete(nil, nil)
    }

    // MARK: - Test Drivers

    func simulateConnectionStateChange(_ state: SerenadaPeerConnectionState) {
        connectionState = state
        onConnectionStateChange?(remoteCid, state.rawValue)
    }

    func simulateIceConnectionStateChange(_ state: String) {
        iceConnectionState = state
        onIceConnectionStateChange?(remoteCid, state)
    }

    func simulateSignalingStateChange(_ state: String) {
        signalingState = state
        onSignalingStateChange?(remoteCid, state)
    }

    func simulateRenegotiationNeeded() {
        onRenegotiationNeeded?(remoteCid)
    }
}
