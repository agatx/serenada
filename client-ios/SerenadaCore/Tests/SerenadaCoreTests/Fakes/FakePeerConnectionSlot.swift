import Foundation
@testable import SerenadaCore

@MainActor
final class FakePeerConnectionSlot: PeerConnectionSlotProtocol {
    let remoteCid: String

    private(set) var sentOffer = false
    private(set) var isMakingOffer = false
    private(set) var pendingIceRestart = false
    private(set) var lastIceRestartAt: TimeInterval = 0
    private(set) var offerTimeoutTask: Task<Void, Never>?
    private(set) var iceRestartTask: Task<Void, Never>?
    private(set) var nonHostFallbackTask: Task<Void, Never>?
    private(set) var nonHostFallbackAttempts = 0

    // State machine
    private(set) var signalingState = "STABLE"
    private(set) var connectionState: SerenadaPeerConnectionState = .new
    private(set) var iceConnectionState = "NEW"
    private(set) var ready = true
    private(set) var remoteDescriptionSet = false

    // Call tracking
    private(set) var createOfferCalls = 0
    private(set) var createAnswerCalls = 0
    private(set) var setRemoteDescriptionCalls: [(type: SessionDescriptionType, sdp: String)] = []
    private(set) var addedIceCandidates: [IceCandidatePayload] = []
    private(set) var rollbackCalls = 0
    private(set) var closePeerConnectionCalled = false
    private(set) var ensurePeerConnectionCalls = 0

    // Callbacks for driving state changes
    private let onConnectionStateChange: ((String, String) -> Void)?
    private let onIceConnectionStateChange: ((String, String) -> Void)?
    private let onSignalingStateChange: ((String, String) -> Void)?

    init(
        remoteCid: String,
        onConnectionStateChange: ((String, String) -> Void)? = nil,
        onIceConnectionStateChange: ((String, String) -> Void)? = nil,
        onSignalingStateChange: ((String, String) -> Void)? = nil
    ) {
        self.remoteCid = remoteCid
        self.onConnectionStateChange = onConnectionStateChange
        self.onIceConnectionStateChange = onIceConnectionStateChange
        self.onSignalingStateChange = onSignalingStateChange
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

    func setNonHostFallbackTask(_ task: Task<Void, Never>) {
        nonHostFallbackTask?.cancel()
        nonHostFallbackTask = task
    }

    func cancelNonHostFallbackTask() {
        nonHostFallbackTask?.cancel()
        nonHostFallbackTask = nil
    }

    func clearNonHostFallbackTask() { nonHostFallbackTask = nil }
    func incrementNonHostFallbackAttempts() { nonHostFallbackAttempts += 1 }

    // MARK: - WebRTC Operations

    func setIceServers(_ servers: [IceServerConfig]) {
        ready = true
    }

    @discardableResult
    func ensurePeerConnection() -> Bool {
        ensurePeerConnectionCalls += 1
        return ready
    }

    func attachLocalTracks(audioTrack: AnyObject?, videoTrack: AnyObject?) {}

    func closePeerConnection() {
        closePeerConnectionCalled = true
        cancelOfferTimeout()
        cancelIceRestartTask()
        cancelNonHostFallbackTask()
    }

    @discardableResult
    func createOffer(
        iceRestart: Bool = false,
        onSdp: @escaping (String) -> Void,
        onComplete: ((Bool) -> Void)? = nil
    ) -> Bool {
        createOfferCalls += 1
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
        signalingState = "STABLE"
        onComplete?(true)
    }

    func addIceCandidate(_ candidate: IceCandidatePayload) {
        addedIceCandidates.append(candidate)
    }

    // MARK: - State Queries

    func isReady() -> Bool { ready }
    func getConnectionState() -> SerenadaPeerConnectionState { connectionState }
    func getIceConnectionState() -> String { iceConnectionState }
    func getSignalingState() -> String { signalingState }
    func hasRemoteDescription() -> Bool { remoteDescriptionSet }
    func isRemoteVideoTrackEnabled() -> Bool { false }

    // MARK: - Renderer Management

    func attachRemoteRenderer(_ renderer: AnyObject) {}
    func detachRemoteRenderer(_ renderer: AnyObject) {}

    // MARK: - Stats

    func collectRealtimeCallStats(onComplete: @escaping (RealtimeCallStats) -> Void) {
        onComplete(.empty)
    }

    func collectRealtimeCallStatsAndSummary(onComplete: @escaping (RealtimeCallStats, String) -> Void) {
        onComplete(.empty, "fake")
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
}
