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

private final class SignalingProviderDelegateProxy: SignalingProviderDelegate {
    weak var session: SerenadaSession?

    init(session: SerenadaSession? = nil) {
        self.session = session
    }

    func signalingProviderDidConnect(_ info: ConnectionInfo) {
        Task { @MainActor [weak session] in
            session?.handleProviderConnected(info)
        }
    }

    func signalingProviderDidDisconnect(reason: String?) {
        Task { @MainActor [weak session] in
            session?.handleProviderDisconnected(reason: reason)
        }
    }

    func signalingProviderDidJoin(_ event: JoinedEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderJoined(event)
        }
    }

    func signalingProviderDidUpdateRoomState(_ event: RoomStateEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderRoomStateUpdated(event)
        }
    }

    func signalingProviderDidJoinPeer(_ event: PeerEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderPeerJoined(event)
        }
    }

    func signalingProviderDidLeavePeer(_ event: PeerEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderPeerLeft(event)
        }
    }

    func signalingProviderDidReceiveMessage(_ message: PeerMessage) {
        Task { @MainActor [weak session] in
            session?.handleProviderMessage(message)
        }
    }

    func signalingProviderDidEndRoom(_ event: RoomEndedEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderRoomEnded(event)
        }
    }

    func signalingProviderDidReceiveError(_ event: ErrorEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderError(event)
        }
    }

    func signalingProviderDidChangeIceServers(_ iceServers: [IceServerConfig]) {
        Task { @MainActor [weak session] in
            session?.handleProviderIceServersChanged(iceServers)
        }
    }
}

/// Represents an active call session. Created via ``SerenadaCore/join(url:)`` or ``SerenadaCore/createRoom()``.
/// Publishes state via `@Published` properties for SwiftUI integration.
@MainActor
public final class SerenadaSession: ObservableObject {
    /// Current call state. Observe with SwiftUI or Combine for UI updates.
    @Published public private(set) var state = CallState()
    /// Real-time connection diagnostics.
    @Published public private(set) var diagnostics = CallDiagnostics()

    /// Room identifier for this session.
    public let roomId: String
    /// Full URL for this room, if available.
    public let roomUrl: URL?
    /// Server host used for signaling.
    public var serverHost: String {
        guard let serverHost = resolvedConfig.serverHost else {
            preconditionFailure("requires serverHost")
        }
        return serverHost
    }
    /// Bundle ID for the broadcast upload extension used in screen sharing.
    public var screenShareExtensionBundleId: String? {
        #if BROADCAST_EXTENSION
        BroadcastShared.extensionBundleId
        #else
        nil
        #endif
    }

    /// Callback invoked when camera/microphone permissions are needed before joining.
    public var onPermissionsRequired: (([MediaCapability]) -> Void)?

    // Core dependencies
    private let signalingProvider: SignalingProvider
    private let providerDelegateProxy: SignalingProviderDelegateProxy
    private let webRtcEngine: SessionMediaEngine
    private let callAudioSessionController: SessionAudioController
    private let apiClient: SessionAPIClient
    private let clock: SessionClock
    private let config: SerenadaConfig
    private let resolvedConfig: ResolvedSerenadaConfig
    private let displayName: String?
    private let delegateProvider: (() -> SerenadaCoreDelegate?)?
    private let logger: SerenadaLogger?

    // Sub-engines
    private var signalingMessageRouter: SignalingMessageRouter?
    private var joinFlowCoordinator: JoinFlowCoordinator?
    private var peerNegotiationEngine: PeerNegotiationEngine?
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
    private var reconnectCid: String?
    private var hasBegunJoin = false
    private var hasJoinSignalStartedForAttempt = false
    private var hasJoinAcknowledgedCurrentAttempt = false
    private var userPreferredVideoEnabled = true
    private var isVideoPausedByProximity = false
    private var reconnectRecoveryPending = false
    private var iceFetchGeneration = 0
    private var reconnectTask: Task<Void, Never>?

    public convenience init(
        roomId: String,
        roomUrl: URL? = nil,
        serverHost: String,
        config: SerenadaConfig,
        delegateProvider: (() -> SerenadaCoreDelegate?)? = nil,
        logger: SerenadaLogger? = nil,
        displayName: String? = nil
    ) {
        let sessionConfig = config.signalingProvider == nil
            ? SerenadaConfig(
                serverHost: serverHost,
                signalingProvider: nil,
                defaultAudioEnabled: config.defaultAudioEnabled,
                defaultVideoEnabled: config.defaultVideoEnabled,
                transports: config.transports
            )
            : config
        self.init(
            roomId: roomId, roomUrl: roomUrl, config: sessionConfig,
            delegateProvider: delegateProvider, logger: logger,
            initialSignalingProvider: nil, signaling: nil, apiClient: nil, audioController: nil, mediaEngine: nil, clock: nil,
            displayName: displayName
        )
    }

    init(
        roomId: String,
        roomUrl: URL? = nil,
        config: SerenadaConfig,
        delegateProvider: (() -> SerenadaCoreDelegate?)? = nil,
        logger: SerenadaLogger? = nil,
        initialSignalingProvider: SignalingProvider? = nil,
        signaling: SessionSignaling? = nil,
        apiClient: SessionAPIClient? = nil,
        audioController: SessionAudioController? = nil,
        mediaEngine: SessionMediaEngine? = nil,
        clock: SessionClock? = nil,
        displayName: String? = nil
    ) {
        self.roomId = roomId
        self.roomUrl = roomUrl
        self.config = config
        self.displayName = displayName
        self.delegateProvider = delegateProvider
        self.logger = logger
        self.clock = clock ?? LiveSessionClock()
        self.apiClient = apiClient ?? CoreAPIClient()
        do {
            self.resolvedConfig = try resolveSerenadaConfig(config)
        } catch {
            preconditionFailure(error.localizedDescription)
        }
        if let initialSignalingProvider {
            self.signalingProvider = initialSignalingProvider
        } else if let signalingProvider = resolvedConfig.signalingProvider {
            self.signalingProvider = signalingProvider
        } else if let serverHost = resolvedConfig.serverHost {
            self.signalingProvider = SerenadaServerProvider(
                serverHost: serverHost,
                apiClient: self.apiClient,
                signaling: signaling,
                transports: config.transports,
                clock: self.clock,
                logger: logger
            )
        } else {
            preconditionFailure("Provide exactly one of serverHost or signalingProvider")
        }
        self.providerDelegateProxy = SignalingProviderDelegateProxy(session: nil)
        self.callAudioSessionController = audioController ?? CallAudioSessionController(
            onProximityChanged: { _ in }, onAudioEnvironmentChanged: {}, logger: logger
        )
        self.webRtcEngine = mediaEngine ?? WebRtcEngine(
            onCameraFacingChanged: { _ in }, onCameraModeChanged: { _ in },
            onFlashlightStateChanged: { _, _ in }, onScreenShareStopped: {},
            onZoomFactorChanged: { _ in }, onFeatureDegradation: { _ in },
            logger: logger, isHdVideoExperimentalEnabled: false
        )

        providerDelegateProxy.session = self
        signalingProvider.delegate = providerDelegateProxy
        configureRuntimeBridges()
        buildSubEngines()

        internalPhase = .joining
        commitSnapshot { s, _ in
            s.localParticipant.displayName = self.displayName
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

    /// Leave the call gracefully. The other participant stays connected.
    public func leave() {
        guard state.phase != .idle else { return }
        if currentRoomState != nil || diagnostics.isSignalingConnected { signalingProvider.leaveRoom() }
        cleanupCall(reason: .localLeft, transitionToEnding: false)
    }

    /// End the call for all participants.
    public func end() {
        guard state.phase != .idle else { return }
        signalingProvider.endRoom()
        leave()
    }

    /// Toggle local audio on or off.
    public func toggleAudio() {
        let enabled = !state.localParticipant.audioEnabled
        webRtcEngine.toggleAudio(enabled)
        commitSnapshot { s, _ in s.localParticipant.audioEnabled = enabled }
    }

    /// Toggle local video on or off.
    public func toggleVideo() {
        userPreferredVideoEnabled = !state.localParticipant.videoEnabled
        applyLocalVideoPreference()
    }

    /// Cycle to the next camera mode (selfie -> world -> composite).
    public func flipCamera() {
        guard !diagnostics.isScreenSharing else { return }
        if state.localParticipant.cameraMode.isContentMode {
            signalingMessageRouter?.broadcastContentState(active: false)
        }
        webRtcEngine.flipCamera()
    }

    /// Set a specific camera mode.
    public func setCameraMode(_ mode: LocalCameraMode) {
        guard mode != state.localParticipant.cameraMode else { return }
        for _ in 0..<4 where state.localParticipant.cameraMode != mode { flipCamera() }
    }

    /// Set local audio enabled state.
    public func setAudioEnabled(_ enabled: Bool) {
        webRtcEngine.toggleAudio(enabled)
        commitSnapshot { s, _ in s.localParticipant.audioEnabled = enabled }
    }

    /// Set local video enabled state.
    public func setVideoEnabled(_ enabled: Bool) {
        userPreferredVideoEnabled = enabled
        applyLocalVideoPreference()
    }

    /// Start screen sharing via the broadcast upload extension.
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

    /// Stop screen sharing.
    public func stopScreenShare() { _ = webRtcEngine.stopScreenShare() }
    public func setHdVideoExperimentalEnabled(_ enabled: Bool) { webRtcEngine.setHdVideoExperimentalEnabled(enabled) }
    /// Toggle the device flashlight. Returns whether the flashlight is now on.
    @discardableResult public func toggleFlashlight() -> Bool { webRtcEngine.toggleFlashlight() }

    /// Adjust camera zoom by a relative scale delta. Returns the new zoom factor, or nil if inactive.
    @discardableResult
    public func adjustCameraZoom(by scaleDelta: CGFloat) -> Double? {
        guard internalPhase == .inCall, state.localParticipant.cameraMode.isContentMode else { return nil }
        return webRtcEngine.adjustCaptureZoom(by: scaleDelta)
    }

    /// Reset camera zoom to 1x.
    @discardableResult public func resetCameraZoom() -> Double { webRtcEngine.resetCaptureZoom() }

    /// Resume joining after camera/microphone permissions have been granted.
    public func resumeJoin() {
        currentRequiredPermissions = nil
        currentError = nil
        internalPhase = .joining
        commitSnapshot()
        Task { @MainActor [weak self] in
            await self?.prepareMediaAndConnect()
        }
    }

    /// Cancel an in-progress join and return to idle.
    public func cancelJoin() {
        currentRequiredPermissions = nil
        resetResources()
        internalPhase = .idle
        commitSnapshot()
    }

    /// Attach a view for rendering local video.
    public func attachLocalRenderer(_ renderer: AnyObject) { webRtcEngine.attachLocalRenderer(renderer) }
    /// Detach a previously attached local video renderer.
    public func detachLocalRenderer(_ renderer: AnyObject) { webRtcEngine.detachLocalRenderer(renderer) }

    /// Attach a view for rendering remote video.
    public func attachRemoteRenderer(_ renderer: AnyObject) {
        let cid = currentRoomState?.participants.first(where: { $0.cid != clientId })?.cid ?? peerSlots.keys.first
        guard let cid else { return }
        attachRemoteRenderer(renderer, forParticipant: cid)
    }

    /// Detach a previously attached remote video renderer.
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
                cid: nil, displayName: self.displayName,
                audioEnabled: self.config.defaultAudioEnabled,
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
        if diagnostics.isSignalingConnected {
            pendingJoinRoom = nil
            sendJoin(roomId: roomId)
            return
        }
        pendingJoinRoom = roomId
        signalingProvider.connect()
    }

    private func sendJoin(roomId: String) {
        guard diagnostics.isSignalingConnected else {
            pendingJoinRoom = roomId
            ensureSignalingConnection()
            return
        }
        signalingProvider.joinRoom(
            roomId,
            options: JoinOptions(reconnectPeerId: reconnectCid, maxParticipants: 4, displayName: self.displayName)
        )
        joinFlowCoordinator?.scheduleJoinRecovery(for: roomId)
    }

    // MARK: - Signaling Message Handling

    private func handleJoined(cid: String?, roomState: RoomState?, participantCountHint: Int?) {
        joinFlowCoordinator?.clearAllTimers()
        hasJoinAcknowledgedCurrentAttempt = true

        if let cid { clientId = cid; reconnectCid = cid }
        commitSnapshot { s, _ in s.localParticipant.cid = self.clientId }

        if let roomState {
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        } else {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint)
        }
        loadInitialIceServers()
    }

    private func handleRoomState(_ roomState: RoomState?, participantCountHint: Int?) {
        joinFlowCoordinator?.clearAllTimers()
        hasJoinAcknowledgedCurrentAttempt = true

        guard let roomState else {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint)
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

    // MARK: - Provider Events

    fileprivate func handleProviderConnected(_ info: ConnectionInfo) {
        let wasConnected = diagnostics.isSignalingConnected
        reconnectAttempts = 0
        reconnectTask?.cancel()
        reconnectTask = nil
        commitSnapshot { _, d in
            d.isSignalingConnected = true
            d.activeTransport = info.transport
        }
        connectionStatusTracker?.update()
        if let join = pendingJoinRoom {
            pendingJoinRoom = nil
            sendJoin(roomId: join)
        } else if !wasConnected, signalingProvider.capabilities.handlesReconnection, reconnectRecoveryPending, currentRoomState != nil {
            reconnectRecoveryPending = false
            peerNegotiationEngine?.triggerIceRestart(reason: "signaling-reconnect")
        }
    }

    fileprivate func handleProviderDisconnected(reason: String?) {
        _ = reason
        commitSnapshot { _, d in
            d.isSignalingConnected = false
            d.activeTransport = nil
        }
        connectionStatusTracker?.update()
        let phase = state.phase
        if phase == .joining || phase == .waiting || phase == .inCall {
            if signalingProvider.capabilities.handlesReconnection {
                reconnectRecoveryPending = currentRoomState != nil
            } else {
                scheduleReconnect()
            }
        }
    }

    fileprivate func handleProviderJoined(_ event: JoinedEvent) {
        currentError = nil
        signalingMessageRouter?.processJoinedEvent(event)
    }

    fileprivate func handleProviderRoomStateUpdated(_ event: RoomStateEvent) {
        currentError = nil
        signalingMessageRouter?.processRoomStateEvent(event)
    }

    fileprivate func handleProviderPeerJoined(_ event: PeerEvent) {
        currentError = nil
        currentRoomState = upsertParticipant(roomState: currentRoomState, event: event, localPeerId: clientId, displayName: event.displayName)
        if let roomState = currentRoomState {
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        }
    }

    fileprivate func handleProviderPeerLeft(_ event: PeerEvent) {
        currentRoomState = removeParticipant(roomState: currentRoomState, peerId: event.peerId, localPeerId: clientId)
        if let roomState = currentRoomState {
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        } else {
            refreshRemoteParticipants()
        }
    }

    fileprivate func handleProviderMessage(_ message: PeerMessage) {
        guard ["content_state", "offer", "answer", "ice"].contains(message.type) else { return }
        signalingMessageRouter?.processPeerMessage(message)
    }

    fileprivate func handleProviderRoomEnded(_ event: RoomEndedEvent) {
        _ = event
        cleanupCall(reason: .remoteEnded, transitionToEnding: true)
    }

    fileprivate func handleProviderError(_ event: ErrorEvent) {
        signalingMessageRouter?.processErrorEvent(event)
    }

    fileprivate func handleProviderIceServersChanged(_ iceServers: [IceServerConfig]) {
        applyIceServers(iceServers)
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
                cid: p.cid, displayName: p.displayName,
                audioEnabled: true,
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
        let objectPayload = payload?.objectValue
        if let to {
            signalingProvider.sendToPeer(to, type: type, payload: objectPayload)
        } else {
            signalingProvider.broadcast(type: type, payload: objectPayload)
        }
    }

    private func flushPendingMessages() {
        guard webRtcEngine.hasIceServers() else { return }
        let pending = pendingMessages
        pendingMessages.removeAll()
        for message in pending { peerNegotiationEngine?.processSignalingPayload(message) }
    }

    // Adapter functions (joinedMessageFromEvent, roomStateMessageFromEvent,
    // signalingMessageFromPeerMessage, errorMessageFromEvent, participantsJSONValue,
    // resolveHostPeerId) removed — the router now accepts provider events directly.

    private func upsertParticipant(
        roomState: RoomState?,
        event: PeerEvent,
        localPeerId: String?,
        displayName: String? = nil
    ) -> RoomState? {
        let participants = dedupeParticipants(
            participants: (roomState?.participants ?? []) + [Participant(cid: event.peerId, joinedAt: event.joinedAt, displayName: displayName)],
            localPeerId: localPeerId,
            makeLocalParticipant: { Participant(cid: $0, joinedAt: nil) }
        )
        let nextHost = roomState?.hostCid ?? localPeerId ?? participants.first?.cid
        guard let nextHost else { return nil }
        let resolvedHost = participants.contains(where: { $0.cid == nextHost }) ? nextHost : participants.first?.cid
        guard let resolvedHost else { return nil }
        return RoomState(hostCid: resolvedHost, participants: participants, maxParticipants: roomState?.maxParticipants)
    }

    private func removeParticipant(
        roomState: RoomState?,
        peerId: String,
        localPeerId: String?
    ) -> RoomState? {
        guard let roomState else { return nil }
        let participants = dedupeParticipants(
            participants: roomState.participants.filter { $0.cid != peerId },
            localPeerId: localPeerId,
            makeLocalParticipant: { Participant(cid: $0, joinedAt: nil) }
        )
        guard !participants.isEmpty else { return nil }
        let nextHost: String
        if roomState.hostCid != peerId, participants.contains(where: { $0.cid == roomState.hostCid }) {
            nextHost = roomState.hostCid
        } else if let localPeerId, participants.contains(where: { $0.cid == localPeerId }) {
            nextHost = localPeerId
        } else {
            nextHost = participants[0].cid
        }
        return RoomState(hostCid: nextHost, participants: participants, maxParticipants: roomState.maxParticipants)
    }

    private func defaultIceServers() -> [IceServerConfig] {
        [IceServerConfig(urls: ["stun:stun.l.google.com:19302"], username: nil, credential: nil)]
    }

    private func applyIceServers(_ iceServers: [IceServerConfig]) {
        let resolvedIceServers = iceServers.isEmpty ? defaultIceServers() : iceServers
        webRtcEngine.setIceServers(resolvedIceServers)
        flushPendingMessages()
        peerNegotiationEngine?.onIceServersReady()
    }

    private func loadInitialIceServers() {
        iceFetchGeneration += 1
        let fetchGeneration = iceFetchGeneration
        Task { @MainActor [weak self] in
            guard let self else { return }
            var lastError: Error?
            for delayMs in WebRtcResilience.iceFetchRetryDelaysMs {
                if delayMs > 0 {
                    try? await clock.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
                }
                guard fetchGeneration == iceFetchGeneration else { return }
                do {
                    let iceServers = try await signalingProvider.getIceServers()
                    guard fetchGeneration == iceFetchGeneration else { return }
                    applyIceServers(iceServers)
                    return
                } catch {
                    lastError = error
                }
            }

            guard fetchGeneration == iceFetchGeneration else { return }
            let message = lastError?.localizedDescription ?? "Failed to fetch ICE servers"
            currentError = .serverError(message)
            joinFlowCoordinator?.clearAllTimers()
            resetResources()
            internalPhase = .error
            commitSnapshot()
            delegateProvider?()?.sessionDidEnd(self, reason: .error(message))
        }
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
        signalingProvider.disconnect()
        peerSlots.values.forEach { $0.closePeerConnection() }
        peerSlots.removeAll()
        webRtcEngine.release()
        callAudioSessionController.deactivate()

        currentRoomState = nil; clientId = nil; hostCid = nil
        pendingJoinRoom = nil; pendingMessages.removeAll(); reconnectAttempts = 0

        reconnectTask?.cancel(); reconnectTask = nil
        joinFlowCoordinator?.clearAllTimers()
        connectionStatusTracker?.cancelTimer()
        iceFetchGeneration += 1

        userPreferredVideoEnabled = config.defaultVideoEnabled
        isVideoPausedByProximity = false
        hasJoinSignalStartedForAttempt = false
        hasJoinAcknowledgedCurrentAttempt = false
        reconnectRecoveryPending = false
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
            guard !Task.isCancelled, let self, !self.diagnostics.isSignalingConnected else { return }
            self.pendingJoinRoom = self.roomId
            self.signalingProvider.connect()
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
            getHostCid: { [weak self] in self?.hostCid },
            getRoomId: { [weak self] in self?.roomId },
            onJoined: { [weak self] cid, roomState, hint in self?.handleJoined(cid: cid, roomState: roomState, participantCountHint: hint) },
            onRoomState: { [weak self] roomState, hint in self?.handleRoomState(roomState, participantCountHint: hint) },
            onRoomEnded: { [weak self] in self?.cleanupCall(reason: .remoteEnded, transitionToEnding: true) },
            onPong: {},
            onTurnRefreshed: { _ in },
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
            isSignalingConnected: { [weak self] in self?.diagnostics.isSignalingConnected ?? false },
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
