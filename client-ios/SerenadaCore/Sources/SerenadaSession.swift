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

    if preferInCall {
        return JoinRecoveryState(phase: .inCall, participantCount: max(2, normalizedHint))
    }

    if normalizedHint > 1 {
        return JoinRecoveryState(phase: .inCall, participantCount: normalizedHint)
    }

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

    private let signalingClient: SessionSignaling
    private let webRtcEngine: SessionMediaEngine
    private let callAudioSessionController: SessionAudioController
    private let apiClient: SessionAPIClient
    private let clock: SessionClock

    private let config: SerenadaConfig
    private let delegateProvider: (() -> SerenadaCoreDelegate?)?
    private let logger: SerenadaLogger?
    private let pathMonitor = NWPathMonitor()
    private let pathMonitorQueue = DispatchQueue(label: "SerenadaSession.PathMonitor")

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
    private var turnManager: TurnManager?

    private var hasBegunJoin = false
    private var hasJoinSignalStartedForAttempt = false
    private var hasJoinAcknowledgedCurrentAttempt = false
    private var userPreferredVideoEnabled = true
    private var isVideoPausedByProximity = false

    private var reconnectTask: Task<Void, Never>?
    private var joinTimer: JoinTimer?
    private var connectionStatusTracker: ConnectionStatusTracker?
    private var statsPoller: StatsPoller?
    private var peerNegotiationEngine: PeerNegotiationEngine?

    private let permissionRequestTimeoutNs: UInt64 = 2_000_000_000

    public convenience init(
        roomId: String,
        roomUrl: URL? = nil,
        serverHost: String,
        config: SerenadaConfig,
        delegateProvider: (() -> SerenadaCoreDelegate?)? = nil,
        logger: SerenadaLogger? = nil
    ) {
        self.init(
            roomId: roomId,
            roomUrl: roomUrl,
            serverHost: serverHost,
            config: config,
            delegateProvider: delegateProvider,
            logger: logger,
            signaling: nil,
            apiClient: nil,
            audioController: nil,
            mediaEngine: nil,
            clock: nil
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
            onProximityChanged: { _ in },
            onAudioEnvironmentChanged: {},
            logger: logger
        )
        self.webRtcEngine = mediaEngine ?? WebRtcEngine(
            onCameraFacingChanged: { _ in },
            onCameraModeChanged: { _ in },
            onFlashlightStateChanged: { _, _ in },
            onScreenShareStopped: {},
            onZoomFactorChanged: { _ in },
            onFeatureDegradation: { _ in },
            logger: logger,
            isHdVideoExperimentalEnabled: false
        )

        signalingClient.listener = self
        configureRuntimeBridges()

        joinTimer = JoinTimer(
            clock: self.clock,
            getRoomId: { [weak self] in self?.roomId ?? "" },
            getJoinAttemptSerial: { [weak self] in self?.joinAttemptSerial ?? 0 },
            getInternalPhase: { [weak self] in self?.internalPhase ?? .idle },
            hasJoinSignalStarted: { [weak self] in self?.hasJoinSignalStartedForAttempt ?? false },
            hasJoinAcknowledged: { [weak self] in self?.hasJoinAcknowledgedCurrentAttempt ?? false },
            isSignalingConnected: { [weak self] in self?.diagnostics.isSignalingConnected ?? false },
            onJoinTimeout: { [weak self] in self?.failJoinWithError(.connectionFailed) },
            ensureSignalingConnection: { [weak self] in self?.ensureSignalingConnection() },
            onRecovery: { [weak self] participantHint, preferInCall in
                self?.recoverFromJoiningIfNeeded(participantHint: participantHint ?? self?.currentRoomState?.participants.count, preferInCall: preferInCall)
            }
        )

        turnManager = TurnManager(
            clock: self.clock,
            serverHost: serverHost,
            apiClient: self.apiClient,
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
            clock: self.clock,
            getInternalPhase: { [weak self] in self?.internalPhase ?? .idle },
            getDiagnostics: { [weak self] in self?.diagnostics ?? CallDiagnostics() },
            getCurrentStatus: { [weak self] in self?.state.connectionStatus ?? .connected },
            setConnectionStatus: { [weak self] status in
                guard let self, self.state.connectionStatus != status else { return }
                self.commitSnapshot { s, _ in s.connectionStatus = status }
            }
        )

        statsPoller = StatsPoller(
            clock: self.clock,
            isActivePhase: { [weak self] in
                guard let self else { return false }
                return self.internalPhase == .inCall || self.internalPhase == .waiting || self.internalPhase == .joining
            },
            getPeerSlots: { [weak self] in
                guard let self else { return [] }
                return Array(self.peerSlots.values)
            },
            onStatsUpdated: { [weak self] merged in
                self?.commitSnapshot { _, d in d.realtimeStats = merged }
            },
            onRefreshRemoteParticipants: { [weak self] in
                self?.refreshRemoteParticipants()
            }
        )

        peerNegotiationEngine = PeerNegotiationEngine(
            clock: self.clock,
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
                    remoteCid: remoteCid,
                    onLocalIceCandidate: onLocalIce,
                    onRemoteVideoTrack: onRemoteVideo,
                    onConnectionStateChange: onConnState,
                    onIceConnectionStateChange: onIceConnState,
                    onSignalingStateChange: onSigState,
                    onRenegotiationNeeded: onRenegotiation
                )
            },
            engineRemoveSlot: { [weak self] slot in self?.webRtcEngine.removeSlot(slot) },
            sendMessage: { [weak self] type, payload, to in self?.sendMessage(type: type, payload: payload, to: to) },
            onRemoteParticipantsChanged: { [weak self] in self?.refreshRemoteParticipants() },
            onAggregatePeerStateChanged: { [weak self] ice, conn, sig in
                self?.commitSnapshot { _, d in
                    d.iceConnectionState = ice
                    d.peerConnectionState = conn
                    d.rtcSignalingState = sig
                }
            },
            onConnectionStatusUpdate: { [weak self] in self?.updateConnectionStatusFromSignals() }
        )

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

    public func leave() {
        if currentRoomState != nil || signalingClient.isConnected() {
            sendMessage(type: "leave")
        }
        cleanupCall(reason: .localLeft, transitionToEnding: false)
    }

    public func end() {
        if currentRoomState != nil || signalingClient.isConnected() {
            sendMessage(type: "end_room")
        }
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
            broadcastContentState(active: false)
        }
        webRtcEngine.flipCamera()
    }

    public func setCameraMode(_ mode: LocalCameraMode) {
        guard mode != state.localParticipant.cameraMode else { return }
        let attempts = 4
        for _ in 0..<attempts where state.localParticipant.cameraMode != mode {
            flipCamera()
        }
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
                    d.isScreenSharing = true
                    s.localParticipant.cameraMode = .screenShare
                    d.cameraZoomFactor = 1
                }
                self.broadcastContentState(active: true, contentType: ContentTypeWire.screenShare)
                self.applyLocalVideoPreference()
            }
        }
    }

    public func stopScreenShare() {
        _ = webRtcEngine.stopScreenShare()
    }

    public func setHdVideoExperimentalEnabled(_ enabled: Bool) {
        webRtcEngine.setHdVideoExperimentalEnabled(enabled)
    }

    @discardableResult
    public func toggleFlashlight() -> Bool {
        webRtcEngine.toggleFlashlight()
    }

    @discardableResult
    public func adjustCameraZoom(by scaleDelta: CGFloat) -> Double? {
        guard internalPhase == .inCall else { return nil }
        guard state.localParticipant.cameraMode.isContentMode else { return nil }
        return webRtcEngine.adjustCaptureZoom(by: scaleDelta)
    }

    @discardableResult
    public func resetCameraZoom() -> Double {
        webRtcEngine.resetCaptureZoom()
    }

    public func resumeJoin() {
        currentRequiredPermissions = nil
        currentError = nil
        internalPhase = .joining
        commitSnapshot()
        Task { @MainActor [weak self] in
            await self?.prepareMediaAndConnect(
                roomId: self?.roomId ?? "",
                joinAttempt: self?.joinAttemptSerial ?? 0,
                defaultAudioEnabled: self?.config.defaultAudioEnabled ?? true,
                defaultVideoEnabled: self?.config.defaultVideoEnabled ?? true,
                permissions: MediaPermissions(cameraGranted: true, microphoneGranted: true)
            )
        }
    }

    public func cancelJoin() {
        currentRequiredPermissions = nil
        resetResources()
        internalPhase = .idle
        commitSnapshot()
    }

    public func attachLocalRenderer(_ renderer: AnyObject) {
        webRtcEngine.attachLocalRenderer(renderer)
    }

    public func detachLocalRenderer(_ renderer: AnyObject) {
        webRtcEngine.detachLocalRenderer(renderer)
    }

    public func attachRemoteRenderer(_ renderer: AnyObject) {
        let remoteCid = currentRoomState?.participants.first(where: { $0.cid != clientId })?.cid ?? peerSlots.keys.first
        guard let remoteCid else { return }
        attachRemoteRenderer(renderer, forParticipant: remoteCid)
    }

    public func detachRemoteRenderer(_ renderer: AnyObject) {
        peerSlots.values.forEach { $0.detachRemoteRenderer(renderer) }
    }

    public func attachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String) {
        peerSlots[cid]?.attachRemoteRenderer(renderer)
    }

    public func detachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String) {
        peerSlots[cid]?.detachRemoteRenderer(renderer)
    }

    private struct MediaPermissions {
        let cameraGranted: Bool
        let microphoneGranted: Bool
    }

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
                cid: nil,
                audioEnabled: self.config.defaultAudioEnabled,
                videoEnabled: self.config.defaultVideoEnabled,
                cameraMode: .selfie
            )
            s.remoteParticipants = []
            s.connectionStatus = .connected
            d.activeTransport = nil
            d.isSignalingConnected = false
            d.iceConnectionState = .new
            d.peerConnectionState = .new
            d.rtcSignalingState = .stable
            d.cameraZoomFactor = 1
            d.isFlashAvailable = false
            d.isFlashEnabled = false
            d.remoteContentParticipantId = nil
            d.remoteContentType = nil
            d.realtimeStats = .empty
        }

        let required = missingPermissions()
        if !required.isEmpty {
            currentRequiredPermissions = required
            internalPhase = .idle
            commitSnapshot()
            onPermissionsRequired?(required)
            delegateProvider?()?.sessionRequiresPermissions(self, permissions: required)
            return
        }

        await prepareMediaAndConnect(
            roomId: roomId,
            joinAttempt: joinAttemptSerial,
            defaultAudioEnabled: config.defaultAudioEnabled,
            defaultVideoEnabled: config.defaultVideoEnabled,
            permissions: MediaPermissions(cameraGranted: true, microphoneGranted: true)
        )
    }

    private func missingPermissions() -> [MediaCapability] {
        var required: [MediaCapability] = []

        if AVCaptureDevice.authorizationStatus(for: .video) != .authorized {
            required.append(.camera)
        }

        if AVAudioSession.sharedInstance().recordPermission != .granted {
            required.append(.microphone)
        }

        return required
    }

    private func configureRuntimeBridges() {
        callAudioSessionController.setOnAudioEnvironmentChanged { [weak self] in
            Task { @MainActor in
                self?.applyLocalVideoPreference()
            }
        }

        webRtcEngine.setOnCameraFacingChanged { [weak self] isFront in
            Task { @MainActor in
                self?.commitSnapshot { _, d in d.isFrontCamera = isFront }
            }
        }
        webRtcEngine.setOnCameraModeChanged { [weak self] mode in
            Task { @MainActor in
                guard let self else { return }
                let previousMode = self.state.localParticipant.cameraMode
                self.commitSnapshot { s, _ in s.localParticipant.cameraMode = mode }
                let isContent = mode.isContentMode
                let wasContent = previousMode.isContentMode
                if isContent {
                    let type = mode == .world ? ContentTypeWire.worldCamera : ContentTypeWire.compositeCamera
                    self.broadcastContentState(active: true, contentType: type)
                } else if wasContent {
                    self.broadcastContentState(active: false)
                }
            }
        }
        webRtcEngine.setOnFlashlightStateChanged { [weak self] available, enabled in
            Task { @MainActor in
                self?.commitSnapshot { _, d in
                    d.isFlashAvailable = available
                    d.isFlashEnabled = enabled
                }
            }
        }
        webRtcEngine.setOnScreenShareStopped { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.commitSnapshot { _, d in
                    d.isScreenSharing = false
                    d.cameraZoomFactor = 1
                }
                self.broadcastContentState(active: false)
                self.applyLocalVideoPreference()
            }
        }
        webRtcEngine.setOnZoomFactorChanged { [weak self] zoomFactor in
            Task { @MainActor in
                self?.commitSnapshot { _, d in d.cameraZoomFactor = zoomFactor }
            }
        }
        webRtcEngine.setOnFeatureDegradation { [weak self] degradation in
            Task { @MainActor in
                self?.setFeatureDegradation(degradation)
            }
        }
    }

    private func ensureSignalingConnection() {
        hasJoinSignalStartedForAttempt = true
        let roomToJoin = roomId

        if signalingClient.isConnected() {
            pendingJoinRoom = nil
            sendJoin(roomId: roomToJoin)
            return
        }

        pendingJoinRoom = roomToJoin
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
            "capabilities": .object([
                "trickleIce": .bool(true),
                "maxParticipants": .number(4)
            ]),
            "createMaxParticipants": .number(4)
        ]

        if let reconnectCid {
            payload["reconnectCid"] = .string(reconnectCid)
        }
        if let reconnectToken {
            payload["reconnectToken"] = .string(reconnectToken)
        }

        signalingClient.send(
            SignalingMessage(
                type: "join",
                rid: roomId,
                payload: .object(payload)
            )
        )
        scheduleJoinRecovery(for: roomId)
    }

    private func sendMessage(type: String, payload: JSONValue? = nil, to: String? = nil) {
        signalingClient.send(
            SignalingMessage(
                type: type,
                rid: roomId,
                cid: clientId,
                to: to,
                payload: payload
            )
        )
    }

    private func handleSignalingMessage(_ message: SignalingMessage) {
        switch message.type {
        case "joined":
            handleJoined(message)
        case "room_state":
            handleRoomState(message)
        case "room_ended":
            cleanupCall(reason: .remoteEnded, transitionToEnding: true)
        case "pong":
            signalingClient.recordPong()
        case "turn-refreshed":
            handleTurnRefreshed(message)
        case "offer", "answer", "ice":
            handleSignalingPayload(message)
        case "content_state":
            handleContentState(message)
        case "error":
            handleError(message)
        default:
            break
        }
    }

    private func handleJoined(_ message: SignalingMessage) {
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
        hasJoinAcknowledgedCurrentAttempt = true

        if let cid = message.cid {
            clientId = cid
            reconnectCid = cid
        }

        commitSnapshot { s, _ in s.localParticipant.cid = self.clientId }

        if let token = message.payload?.objectValue?["reconnectToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
           !token.isEmpty {
            reconnectToken = token
        }
        if let ttl = message.payload?.objectValue?["turnTokenTTLMs"]?.intValue {
            turnManager?.handleJoinedTTL(ttlMs: Int64(ttl))
        }

        ensureIceSetupIfNeeded(turnToken: turnToken(from: message.payload))

        if let roomState = parseRoomState(payload: message.payload) {
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        } else {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint(payload: message.payload))
        }
    }

    private func handleRoomState(_ message: SignalingMessage) {
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
        hasJoinAcknowledgedCurrentAttempt = true
        ensureIceSetupIfNeeded(turnToken: turnToken(from: message.payload))

        guard let roomState = parseRoomState(payload: message.payload) else {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint(payload: message.payload))
            return
        }

        hostCid = roomState.hostCid
        updateParticipants(roomState)
    }

    private func turnToken(from payload: JSONValue?) -> String? {
        payload?.objectValue?["turnToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func ensureIceSetupIfNeeded(turnToken: String?) {
        turnManager?.ensureIceSetupIfNeeded(turnToken: turnToken)
    }

    private func handleError(_ message: SignalingMessage) {
        let code = message.payload?.objectValue?["code"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        let rawMessage = message.payload?.objectValue?["message"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        currentError = {
            switch code {
            case "ROOM_CAPACITY_UNSUPPORTED":
                return .roomFull
            case "CONNECTION_FAILED":
                return .connectionFailed
            case "JOIN_TIMEOUT":
                return .signalingTimeout
            case .some:
                return .serverError(rawMessage ?? code ?? "Server error")
            default:
                return .unknown(rawMessage ?? "Unknown error")
            }
        }()
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
        resetResources()
        internalPhase = .error
        commitSnapshot()
    }

    private func handleContentState(_ message: SignalingMessage) {
        guard let fromCid = message.payload?.objectValue?["from"]?.stringValue,
              !fromCid.isEmpty else { return }
        let active = message.payload?.objectValue?["active"]?.boolValue == true
        let contentType = active ? message.payload?.objectValue?["contentType"]?.stringValue : nil
        commitSnapshot { _, d in
            d.remoteContentParticipantId = active ? fromCid : nil
            d.remoteContentType = contentType
        }
    }

    private func broadcastContentState(active: Bool, contentType: String? = nil) {
        var payload: [String: JSONValue] = ["active": .bool(active)]
        if active, let contentType {
            payload["contentType"] = .string(contentType)
        }
        sendMessage(type: "content_state", payload: .object(payload))
    }

    private func handleSignalingPayload(_ message: SignalingMessage) {
        if message.type == "offer" || message.type == "answer" || message.type == "ice" {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint(payload: message.payload), preferInCall: true)
        }

        guard webRtcEngine.hasIceServers() else {
            pendingMessages.append(message)
            return
        }
        peerNegotiationEngine?.processSignalingPayload(message)
    }


    private func updateParticipants(_ roomState: RoomState) {
        currentRoomState = roomState

        let count = max(1, roomState.participants.count)
        let isHostNow = clientId != nil && clientId == roomState.hostCid
        let phase: CallPhase = count <= 1 ? .waiting : .inCall

        if phase != .joining {
            clearJoinTimeout()
        }

        internalPhase = phase
        participantCount = count
        commitSnapshot { s, _ in
            s.localParticipant.isHost = isHostNow
        }

        peerNegotiationEngine?.syncPeers(roomState: roomState)
        refreshRemoteParticipants()
        updateConnectionStatusFromSignals()
    }


    private func scheduleJoinTimeout(roomId: String, joinAttempt: Int64) {
        joinTimer?.scheduleTimeout(roomId: roomId, joinAttempt: joinAttempt)
    }

    private func clearJoinTimeout() {
        joinTimer?.clearTimeout()
    }

    private func scheduleJoinConnectKickstart(roomId: String, joinAttempt: Int64) {
        joinTimer?.scheduleKickstart(roomId: roomId, joinAttempt: joinAttempt)
    }

    private func clearJoinConnectKickstart() {
        joinTimer?.clearKickstart()
    }

    private func failJoinWithError(_ error: CallError) {
        clearJoinTimeout()
        clearJoinConnectKickstart()
        currentError = error
        resetResources()
        internalPhase = .error
        commitSnapshot()
    }

    private func handleTurnRefreshed(_ message: SignalingMessage) {
        turnManager?.handleTurnRefreshed(payload: message.payload)
    }

    private func clearTurnRefresh() {
        turnManager?.cancelRefresh()
    }

    private func flushPendingMessages() {
        guard webRtcEngine.hasIceServers() else { return }
        let pending = pendingMessages
        pendingMessages.removeAll()
        for message in pending {
            peerNegotiationEngine?.processSignalingPayload(message)
        }
    }

    private func parseRoomState(payload: JSONValue?) -> RoomState? {
        guard let payload = payload?.objectValue else { return nil }
        let parsedHostCid = payload["hostCid"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)

        var participants: [Participant] = []
        if let values = payload["participants"]?.arrayValue {
            for value in values {
                guard let participantObject = value.objectValue else { continue }
                guard let cid = participantObject["cid"]?.stringValue, !cid.isEmpty else { continue }
                let joinedAt = participantObject["joinedAt"]?.intValue.map(Int64.init)
                participants.append(Participant(cid: cid, joinedAt: joinedAt))
            }
        }

        var resolvedHostCid = (parsedHostCid?.isEmpty == false ? parsedHostCid : nil) ?? hostCid ?? clientId
        if let currentHostCid = resolvedHostCid, !participants.isEmpty {
            let participantCids = Set(participants.map(\.cid))
            if !participantCids.contains(currentHostCid) {
                resolvedHostCid = participants.first?.cid
            }
        }

        guard let resolvedHostCid, !resolvedHostCid.isEmpty else { return nil }
        let maxParticipants = payload["maxParticipants"]?.intValue
        return RoomState(hostCid: resolvedHostCid, participants: participants, maxParticipants: maxParticipants)
    }

    private func refreshRemoteParticipants() {
        guard let roomState = currentRoomState else {
            commitSnapshot { s, _ in s.remoteParticipants = [] }
            return
        }

        let participants = roomState.participants
            .filter { $0.cid != clientId }
            .map { participant in
                let slot = peerSlots[participant.cid]
                return SerenadaRemoteParticipant(
                    cid: participant.cid,
                    audioEnabled: true,
                    videoEnabled: slot?.isRemoteVideoTrackEnabled() ?? false,
                    connectionState: slot?.getConnectionState() ?? "NEW"
                )
            }

        let activeCids = Set(participants.map(\.cid))
        let clearContent = diagnostics.remoteContentParticipantId != nil && !activeCids.contains(diagnostics.remoteContentParticipantId!)

        commitSnapshot { s, d in
            s.remoteParticipants = participants
            if clearContent {
                d.remoteContentParticipantId = nil
                d.remoteContentType = nil
            }
        }
    }

    private func startRemoteVideoStatePolling() {
        statsPoller?.start()
    }

    private func stopRemoteVideoStatePolling() {
        statsPoller?.stop()
    }

    private func cleanupCall(reason: EndReason, transitionToEnding: Bool) {
        if transitionToEnding {
            internalPhase = .ending
            commitSnapshot { s, _ in
                s.localParticipant.videoEnabled = false
                s.remoteParticipants = []
            }
        }

        resetResources()

        if transitionToEnding {
            delegateProvider?()?.sessionDidEnd(self, reason: reason)
            Task { @MainActor [weak self] in
                guard let clock = self?.clock else { return }
                try? await clock.sleep(nanoseconds: 1_500_000_000)
                guard let self else { return }
                guard self.state.phase == .ending else { return }
                self.internalPhase = .idle
                self.commitSnapshot()
            }
        } else {
            internalPhase = .idle
            commitSnapshot()
            delegateProvider?()?.sessionDidEnd(self, reason: reason)
        }
    }

    private func resetResources() {
        stopRemoteVideoStatePolling()
        peerNegotiationEngine?.resetAll()
        signalingClient.close()
        peerSlots.values.forEach { $0.closePeerConnection() }
        peerSlots.removeAll()
        webRtcEngine.release()
        deactivateAudioSession()

        currentRoomState = nil
        clientId = nil
        hostCid = nil
        pendingJoinRoom = nil
        pendingMessages.removeAll()
        reconnectAttempts = 0

        reconnectTask?.cancel()
        reconnectTask = nil
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
        clearConnectionStatusRetryingTimer()
        clearTurnRefresh()

        userPreferredVideoEnabled = config.defaultVideoEnabled
        isVideoPausedByProximity = false
        hasJoinSignalStartedForAttempt = false
        hasJoinAcknowledgedCurrentAttempt = false
        reconnectToken = nil
        turnManager?.reset()

        participantCount = 0
        commitSnapshot { s, d in
            s.localParticipant = LocalParticipant(cid: nil, cameraMode: .selfie)
            s.remoteParticipants = []
            s.connectionStatus = .connected
            d.isSignalingConnected = false
            d.activeTransport = nil
            d.iceConnectionState = .new
            d.peerConnectionState = .new
            d.rtcSignalingState = .stable
            d.isScreenSharing = false
            d.cameraZoomFactor = 1
            d.isFlashAvailable = false
            d.isFlashEnabled = false
            d.remoteContentParticipantId = nil
            d.remoteContentType = nil
            d.realtimeStats = .empty
            d.featureDegradations = []
        }
    }

    private func applyLocalVideoPreference() {
        let shouldPauseForProximity = callAudioSessionController.shouldPauseVideoForProximity(
            isScreenSharing: diagnostics.isScreenSharing
        )

        if shouldPauseForProximity != isVideoPausedByProximity {
            isVideoPausedByProximity = shouldPauseForProximity
        }

        let preferredEnabled = userPreferredVideoEnabled && !shouldPauseForProximity
        let effectiveEnabled = webRtcEngine.toggleVideo(preferredEnabled)
        commitSnapshot { s, _ in s.localParticipant.videoEnabled = effectiveEnabled }
    }

    private func prepareMediaAndConnect(
        roomId: String,
        joinAttempt: Int64,
        defaultAudioEnabled: Bool,
        defaultVideoEnabled: Bool,
        permissions: MediaPermissions
    ) async {
        guard joinAttempt == joinAttemptSerial else { return }
        guard self.roomId == roomId else { return }
        guard state.phase == .joining || state.phase == .awaitingPermissions || internalPhase == .joining else { return }

        let hasMicPermission = permissions.microphoneGranted
        let hasCameraPermission = permissions.cameraGranted
        let shouldEnableAudio = defaultAudioEnabled && hasMicPermission
        let shouldEnableVideo = defaultVideoEnabled && hasCameraPermission

        commitSnapshot { s, _ in
            s.localParticipant.audioEnabled = shouldEnableAudio
            s.localParticipant.videoEnabled = shouldEnableVideo
        }

        activateAudioSession()
        webRtcEngine.startLocalMedia(preferVideo: shouldEnableVideo)

        if !shouldEnableAudio {
            webRtcEngine.toggleAudio(false)
        }

        userPreferredVideoEnabled = shouldEnableVideo
        applyLocalVideoPreference()
        startRemoteVideoStatePolling()

        clearJoinConnectKickstart()
        scheduleJoinTimeout(roomId: roomId, joinAttempt: joinAttempt)
        scheduleJoinConnectKickstart(roomId: roomId, joinAttempt: joinAttempt)
        ensureSignalingConnection()
    }

    private func activateAudioSession() {
        callAudioSessionController.activate()
    }

    private func deactivateAudioSession() {
        callAudioSessionController.deactivate()
    }

    private func scheduleJoinRecovery(for roomId: String) {
        joinTimer?.scheduleRecovery(for: roomId)
    }

    private func clearJoinRecovery() {
        joinTimer?.clearRecovery()
    }

    private func participantCountHint(payload: JSONValue?) -> Int? {
        guard let participants = payload?.objectValue?["participants"]?.arrayValue else { return nil }
        return max(1, participants.count)
    }

    private func recoverFromJoiningIfNeeded(participantHint: Int?, preferInCall: Bool = false) {
        guard let recovered = resolveJoinRecoveryState(
            currentPhase: internalPhase,
            participantHint: participantHint ?? participantCount,
            preferInCall: preferInCall
        ) else { return }

        clearJoinTimeout()
        internalPhase = recovered.phase
        participantCount = recovered.participantCount
        commitSnapshot()
        updateConnectionStatusFromSignals()
    }

    private func scheduleReconnect() {
        reconnectAttempts += 1
        let backoff = Backoff.reconnectDelayMs(attempt: reconnectAttempts)

        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: UInt64(backoff) * 1_000_000)
            guard !Task.isCancelled else { return }
            guard let self else { return }

            if self.signalingClient.isConnected() {
                return
            }

            self.pendingJoinRoom = self.roomId
            self.signalingClient.connect(host: self.serverHost)
        }
    }

    private func shouldReconnectSignaling() -> Bool {
        let phase = state.phase
        return phase == .joining || phase == .waiting || phase == .inCall
    }

    private func clearConnectionStatusRetryingTimer() {
        connectionStatusTracker?.cancelTimer()
    }

    private func updateConnectionStatusFromSignals() {
        connectionStatusTracker?.update()
    }

    private func isConnectionDegraded() -> Bool {
        connectionStatusTracker?.isConnectionDegraded() ?? false
    }

    // MARK: - Snapshot Management

    private func commitSnapshot(
        _ mutate: (_ state: inout CallState, _ diagnostics: inout CallDiagnostics) -> Void = { _, _ in }
    ) {
        var nextState = state
        var nextDiag = diagnostics
        mutate(&nextState, &nextDiag)

        nextState.phase = currentRequiredPermissions != nil ? .awaitingPermissions : mapPhase(internalPhase)
        nextState.roomId = roomId
        nextState.roomUrl = roomUrl
        nextState.error = currentError
        nextState.requiredPermissions = currentRequiredPermissions
        nextDiag.callStats = CallStats(from: nextDiag.realtimeStats)

        if nextState != state { state = nextState }
        if nextDiag != diagnostics { diagnostics = nextDiag }

        syncIdleTimerPolicy(for: internalPhase)
        delegateProvider?()?.sessionDidChangeState(self, state: state)
    }

    private func setFeatureDegradation(_ degradation: FeatureDegradationState) {
        var nextDiagnostics = diagnostics
        if let index = nextDiagnostics.featureDegradations.firstIndex(where: { $0.kind == degradation.kind }) {
            nextDiagnostics.featureDegradations[index] = degradation
        } else {
            nextDiagnostics.featureDegradations.append(degradation)
        }
        if nextDiagnostics != diagnostics {
            diagnostics = nextDiagnostics
        }
    }

    private func mapPhase(_ phase: CallPhase) -> SerenadaCallPhase {
        switch phase {
        case .idle: return .idle
        case .creatingRoom, .joining: return .joining
        case .waiting: return .waiting
        case .inCall: return .inCall
        case .ending: return .ending
        case .error: return .error
        }
    }

    private func syncIdleTimerPolicy(for phase: CallPhase) {
        switch phase {
        case .creatingRoom, .joining, .waiting, .inCall:
            UIApplication.shared.isIdleTimerDisabled = true
        default:
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private func startNetworkMonitoring() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            Task { @MainActor in
                guard self.internalPhase == .inCall else { return }

                if self.isConnectionDegraded() {
                    self.updateConnectionStatusFromSignals()
                }

                if path.status == .satisfied {
                    self.peerNegotiationEngine?.scheduleIceRestart(reason: "network-online", delayMs: 0)
                }
            }
        }
        pathMonitor.start(queue: pathMonitorQueue)
    }
}

extension SerenadaSession: SignalingClientListener {
    func onOpen(activeTransport: String) {
        reconnectAttempts = 0
        commitSnapshot { _, d in
            d.isSignalingConnected = true
            d.activeTransport = activeTransport
        }
        updateConnectionStatusFromSignals()

        if let join = pendingJoinRoom {
            pendingJoinRoom = nil
            sendJoin(roomId: join)
        }

        if internalPhase == .inCall {
            peerNegotiationEngine?.triggerIceRestart(reason: "signaling-reconnect")
        }
    }

    func onMessage(_ message: SignalingMessage) {
        handleSignalingMessage(message)
    }

    func onClosed(reason: String) {
        _ = reason
        commitSnapshot { _, d in
            d.isSignalingConnected = false
            d.activeTransport = nil
        }
        updateConnectionStatusFromSignals()

        if shouldReconnectSignaling() {
            scheduleReconnect()
        }
    }
}
