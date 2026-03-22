import AVFoundation
import Combine
import CoreGraphics
import Foundation
import Network
import UIKit

struct JoinRecoveryState: Equatable {
    let phase: CallPhase
    let participantCount: Int
}

func resolveJoinRecoveryState(
    currentPhase: CallPhase,
    participantHint: Int?,
    preferInCall: Bool
) -> JoinRecoveryState? {
    guard currentPhase == .joining else { return nil }
    let normalizedHint = max(1, participantHint ?? 1)
    if preferInCall { return JoinRecoveryState(phase: .inCall, participantCount: max(2, normalizedHint)) }
    if normalizedHint > 1 { return JoinRecoveryState(phase: .inCall, participantCount: normalizedHint) }
    return JoinRecoveryState(phase: .waiting, participantCount: 1)
}

@MainActor
public final class SerenadaSession: ObservableObject {
    @Published public private(set) var state = CallState()
    @Published public private(set) var diagnostics = CallDiagnostics()

    public let roomId: String
    public let roomUrl: URL?
    public let serverHost: String
    public var screenShareExtensionBundleId: String? {
        #if BROADCAST_EXTENSION
        BroadcastShared.extensionBundleId
        #else
        nil
        #endif
    }

    public var onPermissionsRequired: (([MediaCapability]) -> Void)?

    // Core dependencies
    private let signalingClient: SessionSignaling
    private let webRtcEngine: SessionMediaEngine
    private let callAudioSessionController: SessionAudioController
    private let apiClient: SessionAPIClient
    private let clock: SessionClock
    private let config: SerenadaConfig
    private let delegateProvider: (() -> SerenadaCoreDelegate?)?
    private let logger: SerenadaLogger?

    // Sub-engines
    private var signalingMessageRouter: SignalingMessageRouter?
    private var joinFlowCoordinator: JoinFlowCoordinator?
    private var peerNegotiationEngine: PeerNegotiationEngine?
    private var turnManager: TurnManager?
    private var connectionStatusTracker: ConnectionStatusTracker?
    private var statsPoller: StatsPoller?

    // Network
    private let pathMonitor = NWPathMonitor()
    private let pathMonitorQueue = DispatchQueue(label: "SerenadaSession.PathMonitor")

    // Session state
    private var internalPhase: CallPhase = .joining
    private var participantCount = 0
    private var currentRequiredPermissions: [MediaCapability]?
    private var currentError: CallError?
    private var clientId: String?
    private var hostCid: String?
    private var currentRoomState: RoomState?
    private var peerSlots: [String: any PeerConnectionSlotProtocol] = [:]
    private var pendingMessages: [SignalingMessage] = []
    private var pendingJoinRoom: String?
    private var joinAttemptSerial: Int64 = 0
    private var reconnectAttempts = 0
    private var reconnectToken: String?
    private var reconnectCid: String?
    private var hasBegunJoin = false
    private var hasJoinSignalStartedForAttempt = false
    private var hasJoinAcknowledgedCurrentAttempt = false
    private var userPreferredVideoEnabled = true
    private var isVideoPausedByProximity = false
    private var reconnectTask: Task<Void, Never>?

    public convenience init(
        roomId: String,
        roomUrl: URL? = nil,
        serverHost: String,
        config: SerenadaConfig,
        delegateProvider: (() -> SerenadaCoreDelegate?)? = nil,
        logger: SerenadaLogger? = nil
    ) {
        self.init(
            roomId: roomId, roomUrl: roomUrl, serverHost: serverHost, config: config,
            delegateProvider: delegateProvider, logger: logger,
            signaling: nil, apiClient: nil, audioController: nil, mediaEngine: nil, clock: nil
        )
    }

    init(
        roomId: String,
        roomUrl: URL? = nil,
        serverHost: String,
        config: SerenadaConfig,
        delegateProvider: (() -> SerenadaCoreDelegate?)? = nil,
        logger: SerenadaLogger? = nil,
        signaling: SessionSignaling? = nil,
        apiClient: SessionAPIClient? = nil,
        audioController: SessionAudioController? = nil,
        mediaEngine: SessionMediaEngine? = nil,
        clock: SessionClock? = nil
    ) {
        self.roomId = roomId
        self.roomUrl = roomUrl
        self.serverHost = serverHost
        self.config = config
        self.delegateProvider = delegateProvider
        self.logger = logger
        self.clock = clock ?? LiveSessionClock()
        self.signalingClient = signaling ?? SignalingClient(forceSseSignaling: !config.transports.contains(.ws))
        self.apiClient = apiClient ?? CoreAPIClient()
        self.callAudioSessionController = audioController ?? CallAudioSessionController(
            onProximityChanged: { _ in }, onAudioEnvironmentChanged: {}, logger: logger
        )
        self.webRtcEngine = mediaEngine ?? WebRtcEngine(
            onCameraFacingChanged: { _ in }, onCameraModeChanged: { _ in },
            onFlashlightStateChanged: { _, _ in }, onScreenShareStopped: {},
            onZoomFactorChanged: { _ in }, onFeatureDegradation: { _ in },
            logger: logger, isHdVideoExperimentalEnabled: false
        )

        signalingClient.listener = self
        configureRuntimeBridges()
        buildSubEngines()

        internalPhase = .joining
        commitSnapshot { s, _ in
            s.localParticipant.audioEnabled = config.defaultAudioEnabled
            s.localParticipant.videoEnabled = config.defaultVideoEnabled
        }
        startNetworkMonitoring()

        Task { @MainActor [weak self] in
            await self?.beginJoinIfNeeded()
        }
    }

    deinit {
        pathMonitor.cancel()
        reconnectTask?.cancel()
    }

    // MARK: - Public API

    public func leave() {
        if currentRoomState != nil || signalingClient.isConnected() { sendMessage(type: "leave") }
        cleanupCall(reason: .localLeft, transitionToEnding: false)
    }

    public func end() {
        if currentRoomState != nil || signalingClient.isConnected() { sendMessage(type: "end_room") }
        cleanupCall(reason: .localLeft, transitionToEnding: false)
    }

    public func toggleAudio() {
        let enabled = !state.localParticipant.audioEnabled
        webRtcEngine.toggleAudio(enabled)
        commitSnapshot { s, _ in s.localParticipant.audioEnabled = enabled }
    }

    public func toggleVideo() {
        userPreferredVideoEnabled = !state.localParticipant.videoEnabled
        applyLocalVideoPreference()
    }

    public func flipCamera() {
        guard !diagnostics.isScreenSharing else { return }
        if state.localParticipant.cameraMode.isContentMode {
            signalingMessageRouter?.broadcastContentState(active: false)
        }
        webRtcEngine.flipCamera()
    }

    public func setCameraMode(_ mode: LocalCameraMode) {
        guard mode != state.localParticipant.cameraMode else { return }
        for _ in 0..<4 where state.localParticipant.cameraMode != mode { flipCamera() }
    }

    public func setAudioEnabled(_ enabled: Bool) {
        webRtcEngine.toggleAudio(enabled)
        commitSnapshot { s, _ in s.localParticipant.audioEnabled = enabled }
    }

    public func setVideoEnabled(_ enabled: Bool) {
        userPreferredVideoEnabled = enabled
        applyLocalVideoPreference()
    }

    public func startScreenShare() {
        guard !diagnostics.isScreenSharing else { return }
        _ = webRtcEngine.startScreenShare { [weak self] started in
            Task { @MainActor in
                guard let self, started else { return }
                self.commitSnapshot { s, d in
                    d.isScreenSharing = true; s.localParticipant.cameraMode = .screenShare; d.cameraZoomFactor = 1
                }
                self.signalingMessageRouter?.broadcastContentState(active: true, contentType: ContentTypeWire.screenShare)
                self.applyLocalVideoPreference()
            }
        }
    }

    public func stopScreenShare() { _ = webRtcEngine.stopScreenShare() }
    public func setHdVideoExperimentalEnabled(_ enabled: Bool) { webRtcEngine.setHdVideoExperimentalEnabled(enabled) }
    @discardableResult public func toggleFlashlight() -> Bool { webRtcEngine.toggleFlashlight() }

    @discardableResult
    public func adjustCameraZoom(by scaleDelta: CGFloat) -> Double? {
        guard internalPhase == .inCall, state.localParticipant.cameraMode.isContentMode else { return nil }
        return webRtcEngine.adjustCaptureZoom(by: scaleDelta)
    }

    @discardableResult public func resetCameraZoom() -> Double { webRtcEngine.resetCaptureZoom() }

    public func resumeJoin() {
        currentRequiredPermissions = nil
        currentError = nil
        internalPhase = .joining
        commitSnapshot()
        Task { @MainActor [weak self] in
            await self?.prepareMediaAndConnect()
        }
    }

    public func cancelJoin() {
        currentRequiredPermissions = nil
        resetResources()
        internalPhase = .idle
        commitSnapshot()
    }

    public func attachLocalRenderer(_ renderer: AnyObject) { webRtcEngine.attachLocalRenderer(renderer) }
    public func detachLocalRenderer(_ renderer: AnyObject) { webRtcEngine.detachLocalRenderer(renderer) }

    public func attachRemoteRenderer(_ renderer: AnyObject) {
        let cid = currentRoomState?.participants.first(where: { $0.cid != clientId })?.cid ?? peerSlots.keys.first
        guard let cid else { return }
        attachRemoteRenderer(renderer, forParticipant: cid)
    }

    public func detachRemoteRenderer(_ renderer: AnyObject) { peerSlots.values.forEach { $0.detachRemoteRenderer(renderer) } }
    public func attachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String) { peerSlots[cid]?.attachRemoteRenderer(renderer) }
    public func detachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String) { peerSlots[cid]?.detachRemoteRenderer(renderer) }

    // MARK: - Join Flow

    private func beginJoinIfNeeded() async {
        guard !hasBegunJoin else { return }
        hasBegunJoin = true
        joinAttemptSerial += 1
        currentError = nil
        currentRequiredPermissions = nil
        userPreferredVideoEnabled = config.defaultVideoEnabled
        internalPhase = .joining
        participantCount = 0
        commitSnapshot { s, d in
            s.localParticipant = LocalParticipant(
                cid: nil, audioEnabled: self.config.defaultAudioEnabled,
                videoEnabled: self.config.defaultVideoEnabled, cameraMode: .selfie
            )
            s.remoteParticipants = []; s.connectionStatus = .connected
            d.activeTransport = nil; d.isSignalingConnected = false
            d.iceConnectionState = .new; d.peerConnectionState = .new; d.rtcSignalingState = .stable
            d.cameraZoomFactor = 1; d.isFlashAvailable = false; d.isFlashEnabled = false
            d.remoteContentParticipantId = nil; d.remoteContentType = nil; d.realtimeStats = .empty
        }

        let required = JoinFlowCoordinator.missingPermissions()
        if !required.isEmpty {
            currentRequiredPermissions = required
            internalPhase = .idle
            commitSnapshot()
            onPermissionsRequired?(required)
            delegateProvider?()?.sessionRequiresPermissions(self, permissions: required)
            return
        }
        await prepareMediaAndConnect()
    }

    private func prepareMediaAndConnect() async {
        guard state.phase == .joining || state.phase == .awaitingPermissions || internalPhase == .joining else { return }

        let shouldEnableAudio = config.defaultAudioEnabled
        let shouldEnableVideo = config.defaultVideoEnabled
        commitSnapshot { s, _ in
            s.localParticipant.audioEnabled = shouldEnableAudio
            s.localParticipant.videoEnabled = shouldEnableVideo
        }

        callAudioSessionController.activate()
        webRtcEngine.startLocalMedia(preferVideo: shouldEnableVideo)
        if !shouldEnableAudio { webRtcEngine.toggleAudio(false) }

        userPreferredVideoEnabled = shouldEnableVideo
        applyLocalVideoPreference()
        statsPoller?.start()

        joinFlowCoordinator?.clearJoinConnectKickstart()
        joinFlowCoordinator?.scheduleJoinTimeout(roomId: roomId, joinAttempt: joinAttemptSerial)
        joinFlowCoordinator?.scheduleJoinConnectKickstart(roomId: roomId, joinAttempt: joinAttemptSerial)
        ensureSignalingConnection()
    }

    private func ensureSignalingConnection() {
        hasJoinSignalStartedForAttempt = true
        if signalingClient.isConnected() {
            pendingJoinRoom = nil
            sendJoin(roomId: roomId)
            return
        }
        pendingJoinRoom = roomId
        signalingClient.connect(host: serverHost)
    }

    private func sendJoin(roomId: String) {
        guard signalingClient.isConnected() else {
            pendingJoinRoom = roomId
            ensureSignalingConnection()
            return
        }

        var payload: [String: JSONValue] = [
            "device": .string("ios"),
            "capabilities": .object(["trickleIce": .bool(true), "maxParticipants": .number(4)]),
            "createMaxParticipants": .number(4)
        ]
        if let reconnectCid { payload["reconnectCid"] = .string(reconnectCid) }
        if let reconnectToken { payload["reconnectToken"] = .string(reconnectToken) }

        signalingClient.send(SignalingMessage(type: "join", rid: roomId, payload: .object(payload)))
        joinFlowCoordinator?.scheduleJoinRecovery(for: roomId)
    }

    // MARK: - Signaling Message Handling

    private func handleJoined(cid: String?, payload: JoinedPayload, rawPayload: JSONValue?) {
        joinFlowCoordinator?.clearAllTimers()
        hasJoinAcknowledgedCurrentAttempt = true

        if let cid { clientId = cid; reconnectCid = cid }
        commitSnapshot { s, _ in s.localParticipant.cid = self.clientId }

        if let token = payload.reconnectToken, !token.isEmpty { reconnectToken = token }
        if let ttl = payload.turnTokenTTLMs { turnManager?.handleJoinedTTL(ttlMs: Int64(ttl)) }
        turnManager?.ensureIceSetupIfNeeded(turnToken: payload.turnToken)

        if let roomState = signalingMessageRouter?.parseRoomState(payload: rawPayload, fallbackHostCid: hostCid) {
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        } else {
            recoverFromJoiningIfNeeded(participantHint: payload.participantCount)
        }
    }

    private func handleRoomState(payload: JSONValue?) {
        joinFlowCoordinator?.clearAllTimers()
        hasJoinAcknowledgedCurrentAttempt = true
        turnManager?.ensureIceSetupIfNeeded(turnToken: SignalingMessageRouter.turnToken(from: payload))

        guard let roomState = signalingMessageRouter?.parseRoomState(payload: payload, fallbackHostCid: hostCid) else {
            recoverFromJoiningIfNeeded(participantHint: SignalingMessageRouter.participantCountHint(payload: payload))
            return
        }
        hostCid = roomState.hostCid
        updateParticipants(roomState)
    }

    private func handleSignalingPayload(_ message: SignalingMessage) {
        recoverFromJoiningIfNeeded(
            participantHint: SignalingMessageRouter.participantCountHint(payload: message.payload),
            preferInCall: true
        )
        guard webRtcEngine.hasIceServers() else { pendingMessages.append(message); return }
        peerNegotiationEngine?.processSignalingPayload(message)
    }

    private func handleContentState(_ payload: ContentStatePayload) {
        guard let fromCid = payload.fromCid, !fromCid.isEmpty else { return }
        commitSnapshot { _, d in
            d.remoteContentParticipantId = payload.active ? fromCid : nil
            d.remoteContentType = payload.contentType
        }
    }

    private func handleError(_ error: CallError) {
        currentError = error
        joinFlowCoordinator?.clearAllTimers()
        resetResources()
        internalPhase = .error
        commitSnapshot()
    }

    // MARK: - Participants

    private func updateParticipants(_ roomState: RoomState) {
        currentRoomState = roomState
        let count = max(1, roomState.participants.count)
        let phase: CallPhase = count <= 1 ? .waiting : .inCall
        if phase != .joining { joinFlowCoordinator?.clearJoinTimeout() }

        internalPhase = phase
        participantCount = count
        commitSnapshot { s, _ in s.localParticipant.isHost = self.clientId != nil && self.clientId == roomState.hostCid }

        peerNegotiationEngine?.syncPeers(roomState: roomState)
        refreshRemoteParticipants()
        connectionStatusTracker?.update()
    }

    private func refreshRemoteParticipants() {
        guard let roomState = currentRoomState else {
            commitSnapshot { s, _ in s.remoteParticipants = [] }
            return
        }
        let participants = roomState.participants.filter { $0.cid != clientId }.map { p in
            let slot = peerSlots[p.cid]
            return SerenadaRemoteParticipant(
                cid: p.cid, audioEnabled: true,
                videoEnabled: slot?.isRemoteVideoTrackEnabled() ?? false,
                connectionState: slot?.getConnectionState() ?? .new
            )
        }
        let activeCids = Set(participants.map(\.cid))
        let clearContent = diagnostics.remoteContentParticipantId != nil && !activeCids.contains(diagnostics.remoteContentParticipantId!)
        commitSnapshot { s, d in
            s.remoteParticipants = participants
            if clearContent { d.remoteContentParticipantId = nil; d.remoteContentType = nil }
        }
    }

    // MARK: - Recovery

    private func failJoinWithError(_ error: CallError) {
        joinFlowCoordinator?.clearAllTimers()
        currentError = error
        resetResources()
        internalPhase = .error
        commitSnapshot()
    }

    private func recoverFromJoiningIfNeeded(participantHint: Int?, preferInCall: Bool = false) {
        guard let recovered = resolveJoinRecoveryState(
            currentPhase: internalPhase, participantHint: participantHint ?? participantCount, preferInCall: preferInCall
        ) else { return }
        joinFlowCoordinator?.clearJoinTimeout()
        internalPhase = recovered.phase
        participantCount = recovered.participantCount
        commitSnapshot()
        connectionStatusTracker?.update()
    }

    // MARK: - Messaging

    private func sendMessage(type: String, payload: JSONValue? = nil, to: String? = nil) {
        signalingClient.send(SignalingMessage(type: type, rid: roomId, cid: clientId, to: to, payload: payload))
    }

    private func flushPendingMessages() {
        guard webRtcEngine.hasIceServers() else { return }
        let pending = pendingMessages
        pendingMessages.removeAll()
        for message in pending { peerNegotiationEngine?.processSignalingPayload(message) }
    }

    // MARK: - Cleanup

    private func cleanupCall(reason: EndReason, transitionToEnding: Bool) {
        if transitionToEnding {
            internalPhase = .ending
            commitSnapshot { s, _ in s.localParticipant.videoEnabled = false; s.remoteParticipants = [] }
        }
        resetResources()
        if transitionToEnding {
            delegateProvider?()?.sessionDidEnd(self, reason: reason)
            Task { @MainActor [weak self] in
                guard let clock = self?.clock else { return }
                try? await clock.sleep(nanoseconds: 1_500_000_000)
                guard let self, self.state.phase == .ending else { return }
                self.internalPhase = .idle; self.commitSnapshot()
            }
        } else {
            internalPhase = .idle; commitSnapshot()
            delegateProvider?()?.sessionDidEnd(self, reason: reason)
        }
    }

    private func resetResources() {
        statsPoller?.stop()
        peerNegotiationEngine?.resetAll()
        signalingClient.close()
        peerSlots.values.forEach { $0.closePeerConnection() }
        peerSlots.removeAll()
        webRtcEngine.release()
        callAudioSessionController.deactivate()

        currentRoomState = nil; clientId = nil; hostCid = nil
        pendingJoinRoom = nil; pendingMessages.removeAll(); reconnectAttempts = 0

        reconnectTask?.cancel(); reconnectTask = nil
        joinFlowCoordinator?.clearAllTimers()
        connectionStatusTracker?.cancelTimer()
        turnManager?.cancelRefresh()

        userPreferredVideoEnabled = config.defaultVideoEnabled
        isVideoPausedByProximity = false
        hasJoinSignalStartedForAttempt = false
        hasJoinAcknowledgedCurrentAttempt = false
        reconnectToken = nil
        turnManager?.reset()
        participantCount = 0

        commitSnapshot { s, d in
            s.localParticipant = LocalParticipant(cid: nil, cameraMode: .selfie)
            s.remoteParticipants = []; s.connectionStatus = .connected
            d.isSignalingConnected = false; d.activeTransport = nil
            d.iceConnectionState = .new; d.peerConnectionState = .new; d.rtcSignalingState = .stable
            d.isScreenSharing = false; d.cameraZoomFactor = 1
            d.isFlashAvailable = false; d.isFlashEnabled = false
            d.remoteContentParticipantId = nil; d.remoteContentType = nil
            d.realtimeStats = .empty; d.featureDegradations = []
        }
    }

    // MARK: - Video & Audio

    private func applyLocalVideoPreference() {
        let shouldPause = callAudioSessionController.shouldPauseVideoForProximity(isScreenSharing: diagnostics.isScreenSharing)
        if shouldPause != isVideoPausedByProximity { isVideoPausedByProximity = shouldPause }
        let effectiveEnabled = webRtcEngine.toggleVideo(userPreferredVideoEnabled && !shouldPause)
        commitSnapshot { s, _ in s.localParticipant.videoEnabled = effectiveEnabled }
    }

    // MARK: - Reconnection

    private func scheduleReconnect() {
        reconnectAttempts += 1
        let backoff = Backoff.reconnectDelayMs(attempt: reconnectAttempts)
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: UInt64(backoff) * 1_000_000)
            guard !Task.isCancelled, let self, !self.signalingClient.isConnected() else { return }
            self.pendingJoinRoom = self.roomId
            self.signalingClient.connect(host: self.serverHost)
        }
    }

    // MARK: - Snapshot Management

    private func commitSnapshot(
        _ mutate: (_ state: inout CallState, _ diagnostics: inout CallDiagnostics) -> Void = { _, _ in }
    ) {
        var nextState = state; var nextDiag = diagnostics
        mutate(&nextState, &nextDiag)
        nextState.phase = currentRequiredPermissions != nil ? .awaitingPermissions : mapPhase(internalPhase)
        nextState.roomId = roomId; nextState.roomUrl = roomUrl
        nextState.error = currentError; nextState.requiredPermissions = currentRequiredPermissions
        nextDiag.callStats = CallStats(from: nextDiag.realtimeStats)
        if nextState != state { state = nextState }
        if nextDiag != diagnostics { diagnostics = nextDiag }
        syncIdleTimerPolicy(for: internalPhase)
        delegateProvider?()?.sessionDidChangeState(self, state: state)
    }

    private func setFeatureDegradation(_ degradation: FeatureDegradationState) {
        var nextDiag = diagnostics
        if let idx = nextDiag.featureDegradations.firstIndex(where: { $0.kind == degradation.kind }) {
            nextDiag.featureDegradations[idx] = degradation
        } else {
            nextDiag.featureDegradations.append(degradation)
        }
        if nextDiag != diagnostics { diagnostics = nextDiag }
    }

    private func mapPhase(_ phase: CallPhase) -> SerenadaCallPhase {
        switch phase {
        case .idle: .idle
        case .creatingRoom, .joining: .joining
        case .waiting: .waiting
        case .inCall: .inCall
        case .ending: .ending
        case .error: .error
        }
    }

    private func syncIdleTimerPolicy(for phase: CallPhase) {
        switch phase {
        case .creatingRoom, .joining, .waiting, .inCall: UIApplication.shared.isIdleTimerDisabled = true
        default: UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    // MARK: - Sub-Engine Setup

    private func buildSubEngines() {
        signalingMessageRouter = SignalingMessageRouter(
            getClientId: { [weak self] in self?.clientId },
            onJoined: { [weak self] cid, payload, rawPayload in self?.handleJoined(cid: cid, payload: payload, rawPayload: rawPayload) },
            onRoomState: { [weak self] payload in self?.handleRoomState(payload: payload) },
            onRoomEnded: { [weak self] in self?.cleanupCall(reason: .remoteEnded, transitionToEnding: true) },
            onPong: { [weak self] in self?.signalingClient.recordPong() },
            onTurnRefreshed: { [weak self] payload in self?.turnManager?.handleTurnRefreshed(payload: payload) },
            onSignalingPayload: { [weak self] message in self?.handleSignalingPayload(message) },
            onContentState: { [weak self] payload in self?.handleContentState(payload) },
            onError: { [weak self] error in self?.handleError(error) },
            sendMessage: { [weak self] type, payload, to in self?.sendMessage(type: type, payload: payload, to: to) }
        )

        joinFlowCoordinator = JoinFlowCoordinator(
            clock: clock,
            getRoomId: { [weak self] in self?.roomId ?? "" },
            getJoinAttemptSerial: { [weak self] in self?.joinAttemptSerial ?? 0 },
            getInternalPhase: { [weak self] in self?.internalPhase ?? .idle },
            hasJoinSignalStarted: { [weak self] in self?.hasJoinSignalStartedForAttempt ?? false },
            hasJoinAcknowledged: { [weak self] in self?.hasJoinAcknowledgedCurrentAttempt ?? false },
            isSignalingConnected: { [weak self] in self?.diagnostics.isSignalingConnected ?? false },
            onJoinTimeout: { [weak self] in self?.failJoinWithError(.connectionFailed) },
            onEnsureSignalingConnection: { [weak self] in self?.ensureSignalingConnection() },
            onRecovery: { [weak self] hint, preferInCall in
                self?.recoverFromJoiningIfNeeded(
                    participantHint: hint ?? self?.currentRoomState?.participants.count, preferInCall: preferInCall
                )
            }
        )

        turnManager = TurnManager(
            clock: clock, serverHost: serverHost, apiClient: apiClient,
            getJoinAttemptSerial: { [weak self] in self?.joinAttemptSerial ?? 0 },
            getRoomId: { [weak self] in self?.roomId ?? "" },
            getPhase: { [weak self] in self?.mapPhase(self?.internalPhase ?? .idle) ?? .idle },
            isSignalingConnected: { [weak self] in self?.signalingClient.isConnected() ?? false },
            setIceServers: { [weak self] servers in self?.webRtcEngine.setIceServers(servers) },
            onIceServersReady: { [weak self] in
                self?.flushPendingMessages()
                self?.peerNegotiationEngine?.onIceServersReady()
            },
            sendTurnRefresh: { [weak self] in self?.sendMessage(type: "turn-refresh") }
        )

        connectionStatusTracker = ConnectionStatusTracker(
            clock: clock,
            getInternalPhase: { [weak self] in self?.internalPhase ?? .idle },
            getDiagnostics: { [weak self] in self?.diagnostics ?? CallDiagnostics() },
            getCurrentStatus: { [weak self] in self?.state.connectionStatus ?? .connected },
            setConnectionStatus: { [weak self] status in
                guard let self, self.state.connectionStatus != status else { return }
                self.commitSnapshot { s, _ in s.connectionStatus = status }
            }
        )

        statsPoller = StatsPoller(
            clock: clock,
            isActivePhase: { [weak self] in
                guard let p = self?.internalPhase else { return false }
                return p == .inCall || p == .waiting || p == .joining
            },
            getPeerSlots: { [weak self] in
                guard let self else { return [] }
                return Array(self.peerSlots.values)
            },
            onStatsUpdated: { [weak self] merged in self?.commitSnapshot { _, d in d.realtimeStats = merged } },
            onRefreshRemoteParticipants: { [weak self] in self?.refreshRemoteParticipants() }
        )

        peerNegotiationEngine = PeerNegotiationEngine(
            clock: clock,
            getClientId: { [weak self] in self?.clientId },
            getHostCid: { [weak self] in self?.hostCid },
            getInternalPhase: { [weak self] in self?.internalPhase ?? .idle },
            getParticipantCount: { [weak self] in self?.participantCount ?? 0 },
            getCurrentRoomState: { [weak self] in self?.currentRoomState },
            isSignalingConnected: { [weak self] in self?.signalingClient.isConnected() ?? false },
            hasIceServers: { [weak self] in self?.webRtcEngine.hasIceServers() ?? false },
            getSlot: { [weak self] cid in self?.peerSlots[cid] },
            getAllSlots: { [weak self] in self?.peerSlots ?? [:] },
            setSlot: { [weak self] cid, slot in self?.peerSlots[cid] = slot },
            removeSlotEntry: { [weak self] cid in self?.peerSlots.removeValue(forKey: cid) },
            createSlotViaEngine: { [weak self] remoteCid, onLocalIce, onRemoteVideo, onConnState, onIceConnState, onSigState, onRenegotiation in
                self?.webRtcEngine.createSlot(
                    remoteCid: remoteCid, onLocalIceCandidate: onLocalIce,
                    onRemoteVideoTrack: onRemoteVideo, onConnectionStateChange: onConnState,
                    onIceConnectionStateChange: onIceConnState, onSignalingStateChange: onSigState,
                    onRenegotiationNeeded: onRenegotiation
                )
            },
            engineRemoveSlot: { [weak self] slot in self?.webRtcEngine.removeSlot(slot) },
            sendMessage: { [weak self] type, payload, to in self?.sendMessage(type: type, payload: payload, to: to) },
            onRemoteParticipantsChanged: { [weak self] in self?.refreshRemoteParticipants() },
            onAggregatePeerStateChanged: { [weak self] ice, conn, sig in
                self?.commitSnapshot { _, d in d.iceConnectionState = ice; d.peerConnectionState = conn; d.rtcSignalingState = sig }
            },
            onConnectionStatusUpdate: { [weak self] in self?.connectionStatusTracker?.update() }
        )
    }

    // MARK: - Runtime Bridges

    private func configureRuntimeBridges() {
        callAudioSessionController.setOnAudioEnvironmentChanged { [weak self] in
            Task { @MainActor in self?.applyLocalVideoPreference() }
        }
        webRtcEngine.setOnCameraFacingChanged { [weak self] isFront in
            Task { @MainActor in self?.commitSnapshot { _, d in d.isFrontCamera = isFront } }
        }
        webRtcEngine.setOnCameraModeChanged { [weak self] mode in
            Task { @MainActor in
                guard let self else { return }
                let prev = self.state.localParticipant.cameraMode
                self.commitSnapshot { s, _ in s.localParticipant.cameraMode = mode }
                if mode.isContentMode {
                    let type = mode == .world ? ContentTypeWire.worldCamera : ContentTypeWire.compositeCamera
                    self.signalingMessageRouter?.broadcastContentState(active: true, contentType: type)
                } else if prev.isContentMode {
                    self.signalingMessageRouter?.broadcastContentState(active: false)
                }
            }
        }
        webRtcEngine.setOnFlashlightStateChanged { [weak self] available, enabled in
            Task { @MainActor in self?.commitSnapshot { _, d in d.isFlashAvailable = available; d.isFlashEnabled = enabled } }
        }
        webRtcEngine.setOnScreenShareStopped { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.commitSnapshot { _, d in d.isScreenSharing = false; d.cameraZoomFactor = 1 }
                self.signalingMessageRouter?.broadcastContentState(active: false)
                self.applyLocalVideoPreference()
            }
        }
        webRtcEngine.setOnZoomFactorChanged { [weak self] z in
            Task { @MainActor in self?.commitSnapshot { _, d in d.cameraZoomFactor = z } }
        }
        webRtcEngine.setOnFeatureDegradation { [weak self] degradation in
            Task { @MainActor in self?.setFeatureDegradation(degradation) }
        }
    }

    // MARK: - Network Monitoring

    private func startNetworkMonitoring() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            Task { @MainActor in
                guard self.internalPhase == .inCall else { return }
                if self.connectionStatusTracker?.isConnectionDegraded() == true { self.connectionStatusTracker?.update() }
                if path.status == .satisfied { self.peerNegotiationEngine?.scheduleIceRestart(reason: "network-online", delayMs: 0) }
            }
        }
        pathMonitor.start(queue: pathMonitorQueue)
    }
}

// MARK: - SignalingClientListener

extension SerenadaSession: SignalingClientListener {
    func onOpen(activeTransport: String) {
        reconnectAttempts = 0
        commitSnapshot { _, d in d.isSignalingConnected = true; d.activeTransport = activeTransport }
        connectionStatusTracker?.update()
        if let join = pendingJoinRoom { pendingJoinRoom = nil; sendJoin(roomId: join) }
        if internalPhase == .inCall { peerNegotiationEngine?.triggerIceRestart(reason: "signaling-reconnect") }
    }

    func onMessage(_ message: SignalingMessage) {
        signalingMessageRouter?.processMessage(message)
    }

    func onClosed(reason: String) {
        _ = reason
        commitSnapshot { _, d in d.isSignalingConnected = false; d.activeTransport = nil }
        connectionStatusTracker?.update()
        let phase = state.phase
        if phase == .joining || phase == .waiting || phase == .inCall { scheduleReconnect() }
    }
}
