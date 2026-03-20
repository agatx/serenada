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

    let signalingClient: SignalingClient
    let webRtcEngine: WebRtcEngine
    let callAudioSessionController: CallAudioSessionController
    let apiClient: CoreAPIClient

    private let config: SerenadaConfig
    private let delegateProvider: (() -> SerenadaCoreDelegate?)?
    private let pathMonitor = NWPathMonitor()
    private let pathMonitorQueue = DispatchQueue(label: "SerenadaSession.PathMonitor")

    private var internalPhase: CallPhase = .joining
    private var participantCount = 0
    private var currentRequiredPermissions: [MediaCapability]?
    private var currentError: CallError?

    private var clientId: String?
    private var hostCid: String?
    private var currentRoomState: RoomState?
    private var peerSlots: [String: PeerConnectionSlot] = [:]
    private var pendingMessages: [SignalingMessage] = []

    private var pendingJoinRoom: String?
    private var joinAttemptSerial: Int64 = 0
    private var reconnectAttempts = 0
    private var reconnectToken: String?
    private var reconnectCid: String?
    private var turnTokenTTLMs: Int64?

    private var hasBegunJoin = false
    private var hasJoinSignalStartedForAttempt = false
    private var hasJoinAcknowledgedCurrentAttempt = false
    private var hasInitializedIceSetupForAttempt = false
    private var lastTurnTokenForAttempt: String?
    private var userPreferredVideoEnabled = true
    private var isVideoPausedByProximity = false

    private var reconnectTask: Task<Void, Never>?
    private var joinTimeoutTask: Task<Void, Never>?
    private var joinConnectKickstartTask: Task<Void, Never>?
    private var joinRecoveryTask: Task<Void, Never>?
    private var connectionStatusRetryingTask: Task<Void, Never>?
    private var turnRefreshTask: Task<Void, Never>?
    private var remoteVideoPollTimer: Timer?

    private var lastWebRtcStatsPollAtMs: Int64 = 0
    private var webrtcStatsRequestInFlight = false

    private let permissionRequestTimeoutNs: UInt64 = 2_000_000_000
    private let connectionStatusRetryingDelayNs: UInt64 = 10_000_000_000

    public init(
        roomId: String,
        roomUrl: URL? = nil,
        serverHost: String,
        config: SerenadaConfig,
        delegateProvider: (() -> SerenadaCoreDelegate?)? = nil
    ) {
        self.roomId = roomId
        self.roomUrl = roomUrl
        self.serverHost = serverHost
        self.config = config
        self.delegateProvider = delegateProvider
        self.signalingClient = SignalingClient(forceSseSignaling: !config.transports.contains(.ws))
        self.apiClient = CoreAPIClient()
        self.callAudioSessionController = CallAudioSessionController(
            onProximityChanged: { _ in },
            onAudioEnvironmentChanged: {}
        )
        self.webRtcEngine = WebRtcEngine(
            onCameraFacingChanged: { _ in },
            onCameraModeChanged: { _ in },
            onFlashlightStateChanged: { _, _ in },
            onScreenShareStopped: {},
            onZoomFactorChanged: { _ in },
            onFeatureDegradation: { _ in },
            onDebugTrace: nil,
            isHdVideoExperimentalEnabled: false
        )

        signalingClient.listener = self
        configureRuntimeBridges()

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
        joinTimeoutTask?.cancel()
        joinConnectKickstartTask?.cancel()
        joinRecoveryTask?.cancel()
        connectionStatusRetryingTask?.cancel()
        turnRefreshTask?.cancel()
        remoteVideoPollTimer?.invalidate()
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
            turnTokenTTLMs = Int64(ttl)
            scheduleTurnRefresh(ttlMs: Int64(ttl))
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
        let normalizedToken = turnToken?.trimmingCharacters(in: .whitespacesAndNewlines)

        if !hasInitializedIceSetupForAttempt {
            hasInitializedIceSetupForAttempt = true
            applyDefaultIceServers()
        }

        guard let normalizedToken, !normalizedToken.isEmpty else { return }
        guard lastTurnTokenForAttempt != normalizedToken else { return }

        lastTurnTokenForAttempt = normalizedToken
        fetchTurnCredentials(token: normalizedToken, applyDefaultOnFailure: false)
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

        if message.type == "answer",
           let fromCid = message.payload?.objectValue?["from"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
           !fromCid.isEmpty {
            clearNonHostOfferFallback(remoteCid: fromCid)
        }

        guard webRtcEngine.hasIceServers() else {
            pendingMessages.append(message)
            return
        }
        processSignalingPayload(message)
    }

    private func getOrCreateSlot(remoteCid: String) -> PeerConnectionSlot {
        if let slot = peerSlots[remoteCid] {
            return slot
        }

        guard let slot = webRtcEngine.createSlot(
            remoteCid: remoteCid,
            onLocalIceCandidate: { [weak self] cid, candidate in
                Task { @MainActor in
                    guard let self else { return }
                    self.sendMessage(
                        type: "ice",
                        payload: .object([
                            "candidate": .object([
                                "candidate": .string(candidate.candidate),
                                "sdpMid": candidate.sdpMid.map(JSONValue.string) ?? .null,
                                "sdpMLineIndex": .number(Double(candidate.sdpMLineIndex))
                            ])
                        ]),
                        to: cid
                    )
                }
            },
            onRemoteVideoTrack: { [weak self] _, _ in
                Task { @MainActor in
                    self?.refreshRemoteParticipants()
                }
            },
            onConnectionStateChange: { [weak self] cid, state in
                Task { @MainActor in
                    guard let self else { return }
                    switch state {
                    case "CONNECTED":
                        self.clearIceRestartTimer(remoteCid: cid)
                        self.peerSlots[cid]?.clearPendingIceRestart()
                    case "DISCONNECTED":
                        self.scheduleIceRestart(remoteCid: cid, reason: "conn-disconnected", delayMs: 2000)
                    case "FAILED":
                        self.scheduleIceRestart(remoteCid: cid, reason: "conn-failed", delayMs: 0)
                    default:
                        break
                    }
                    self.refreshRemoteParticipants()
                    self.updateAggregatePeerState()
                    self.updateConnectionStatusFromSignals()
                }
            },
            onIceConnectionStateChange: { [weak self] cid, state in
                Task { @MainActor in
                    guard let self else { return }
                    switch state {
                    case "CONNECTED", "COMPLETED":
                        self.clearIceRestartTimer(remoteCid: cid)
                        self.peerSlots[cid]?.clearPendingIceRestart()
                    case "DISCONNECTED":
                        self.scheduleIceRestart(remoteCid: cid, reason: "ice-disconnected", delayMs: 2000)
                    case "FAILED":
                        self.scheduleIceRestart(remoteCid: cid, reason: "ice-failed", delayMs: 0)
                    default:
                        break
                    }
                    self.refreshRemoteParticipants()
                    self.updateAggregatePeerState()
                    self.updateConnectionStatusFromSignals()
                }
            },
            onSignalingStateChange: { [weak self] cid, state in
                Task { @MainActor in
                    guard let self else { return }
                    if state == "STABLE" {
                        self.clearOfferTimeout(remoteCid: cid)
                        if self.peerSlots[cid]?.pendingIceRestart == true {
                            self.peerSlots[cid]?.clearPendingIceRestart()
                            self.triggerIceRestart(remoteCid: cid, reason: "pending-retry")
                        }
                    }
                    self.updateAggregatePeerState()
                    self.updateConnectionStatusFromSignals()
                }
            },
            onRenegotiationNeeded: { [weak self] cid in
                Task { @MainActor in
                    guard let self, let slot = self.peerSlots[cid] else { return }
                    self.maybeSendOffer(slot: slot, force: true, iceRestart: false)
                }
            }
        ) else {
            preconditionFailure("WebRTC peer slot factory is unavailable")
        }

        peerSlots[remoteCid] = slot
        return slot
    }

    private func removePeerSlot(remoteCid: String) {
        guard let slot = peerSlots.removeValue(forKey: remoteCid) else { return }
        clearOfferTimeout(remoteCid: remoteCid)
        clearIceRestartTimer(remoteCid: remoteCid)
        clearNonHostOfferFallback(remoteCid: remoteCid)
        webRtcEngine.removeSlot(slot)
        slot.closePeerConnection()
    }

    private func updateParticipants(_ roomState: RoomState) {
        currentRoomState = roomState

        let count = max(1, roomState.participants.count)
        let isHostNow = clientId != nil && clientId == roomState.hostCid
        let phase: CallPhase = count <= 1 ? .waiting : .inCall
        let remoteParticipants = roomState.participants.filter { $0.cid != clientId }
        let remoteCids = Set(remoteParticipants.map(\.cid))

        if phase != .joining {
            clearJoinTimeout()
        }

        let departing = Set(peerSlots.keys).subtracting(remoteCids)
        for remoteCid in departing {
            removePeerSlot(remoteCid: remoteCid)
        }

        if count <= 1 {
            clearOfferTimeout()
            clearIceRestartTimer()
            clearNonHostOfferFallback()
        }

        internalPhase = phase
        participantCount = count
        commitSnapshot { s, _ in
            s.localParticipant.isHost = isHostNow
        }

        if count > 1 {
            for participant in remoteParticipants {
                let slot = getOrCreateSlot(remoteCid: participant.cid)
                _ = slot.ensurePeerConnection()
                if shouldIOffer(remoteCid: participant.cid, roomState: roomState) {
                    clearNonHostOfferFallback(remoteCid: participant.cid)
                    maybeSendOffer(slot: slot)
                } else {
                    maybeScheduleNonHostOfferFallback(remoteCid: participant.cid, reason: "participants")
                }
            }
        }

        refreshRemoteParticipants()
        updateAggregatePeerState()
        updateConnectionStatusFromSignals()
    }

    private func shouldIOffer(remoteCid: String, roomState: RoomState? = nil) -> Bool {
        let roomState = roomState ?? currentRoomState
        guard let roomState, let myCid = clientId else { return false }
        let myJoinedAt = roomState.participants.first(where: { $0.cid == myCid })?.joinedAt ?? 0
        let theirJoinedAt = roomState.participants.first(where: { $0.cid == remoteCid })?.joinedAt ?? 0
        return myJoinedAt < theirJoinedAt || (myJoinedAt == theirJoinedAt && myCid < remoteCid)
    }

    private func processSignalingPayload(_ message: SignalingMessage) {
        guard let fromCid = message.payload?.objectValue?["from"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
              !fromCid.isEmpty else {
            return
        }

        let slot = getOrCreateSlot(remoteCid: fromCid)
        if !slot.isReady(), !slot.ensurePeerConnection() {
            pendingMessages.append(message)
            return
        }

        switch message.type {
        case "offer":
            clearNonHostOfferFallback(remoteCid: fromCid)
            guard let sdp = message.payload?.objectValue?["sdp"]?.stringValue, !sdp.isEmpty else { return }
            slot.setRemoteDescription(type: .offer, sdp: sdp) { [weak self] success in
                guard let self else { return }
                guard success else {
                    self.maybeScheduleNonHostOfferFallback(remoteCid: fromCid, reason: "offer-apply-failed")
                    return
                }
                self.clearNonHostOfferFallback(remoteCid: fromCid)
                slot.createAnswer(onSdp: { [weak self] answerSdp in
                    self?.sendMessage(
                        type: "answer",
                        payload: .object(["sdp": .string(answerSdp)]),
                        to: fromCid
                    )
                }, onComplete: { [weak self] answerSuccess in
                    Task { @MainActor in
                        guard let self else { return }
                        if !answerSuccess {
                            self.maybeScheduleNonHostOfferFallback(remoteCid: fromCid, reason: "answer-create-failed")
                        }
                    }
                })
            }

        case "answer":
            clearNonHostOfferFallback(remoteCid: fromCid)
            guard let sdp = message.payload?.objectValue?["sdp"]?.stringValue, !sdp.isEmpty else { return }
            slot.setRemoteDescription(type: .answer, sdp: sdp) { [weak self] success in
                guard let self else { return }
                if success {
                    self.clearOfferTimeout(remoteCid: fromCid)
                    self.peerSlots[fromCid]?.clearPendingIceRestart()
                    self.updateAggregatePeerState()
                    self.updateConnectionStatusFromSignals()
                } else if self.shouldIOffer(remoteCid: fromCid) {
                    self.scheduleIceRestart(remoteCid: fromCid, reason: "answer-apply-failed", delayMs: 0)
                } else {
                    self.maybeScheduleNonHostOfferFallback(remoteCid: fromCid, reason: "answer-apply-failed")
                }
            }

        case "ice":
            guard let candidateObject = message.payload?.objectValue?["candidate"]?.objectValue,
                  let candidate = candidateObject["candidate"]?.stringValue else {
                return
            }
            slot.addIceCandidate(
                IceCandidatePayload(
                    sdpMid: candidateObject["sdpMid"]?.stringValue,
                    sdpMLineIndex: Int32(candidateObject["sdpMLineIndex"]?.intValue ?? 0),
                    candidate: candidate
                )
            )

        default:
            break
        }
    }

    private static let icePriority: [String: Int] = [
        "FAILED": 0, "DISCONNECTED": 1, "CHECKING": 2, "NEW": 3, "CONNECTED": 4, "COMPLETED": 5, "CLOSED": 6, "COUNT": 7, "UNKNOWN": 8,
    ]
    private static let connectionPriority: [String: Int] = [
        "FAILED": 0, "DISCONNECTED": 1, "CONNECTING": 2, "NEW": 3, "CONNECTED": 4, "CLOSED": 5, "UNKNOWN": 6,
    ]
    private static let signalingPriority: [String: Int] = [
        "HAVE_LOCAL_OFFER": 0, "HAVE_REMOTE_OFFER": 1, "HAVE_LOCAL_PRANSWER": 2, "HAVE_REMOTE_PRANSWER": 3, "STABLE": 4, "CLOSED": 5, "UNKNOWN": 6,
    ]

    private func updateAggregatePeerState() {
        var bestIcePri = Int.max
        var nextIceState = "NEW"
        var bestConnPri = Int.max
        var nextConnectionState = "NEW"
        var bestSigPri = Int.max
        var nextSignalingState = "STABLE"

        for slot in peerSlots.values {
            let icePri = Self.icePriority[slot.getIceConnectionState()] ?? .max
            if icePri < bestIcePri {
                bestIcePri = icePri
                nextIceState = slot.getIceConnectionState()
            }

            let connPri = Self.connectionPriority[slot.getConnectionState()] ?? .max
            if connPri < bestConnPri {
                bestConnPri = connPri
                nextConnectionState = slot.getConnectionState()
            }

            let sigPri = Self.signalingPriority[slot.getSignalingState()] ?? .max
            if sigPri < bestSigPri {
                bestSigPri = sigPri
                nextSignalingState = slot.getSignalingState()
            }
        }

        commitSnapshot { _, d in
            d.iceConnectionState = IceConnectionState(rawValueOrUnknown: nextIceState)
            d.peerConnectionState = PeerConnectionState(rawValueOrUnknown: nextConnectionState)
            d.rtcSignalingState = SignalingState(rawValueOrUnknown: nextSignalingState)
        }
    }

    private func maybeSendOffer(force: Bool = false, iceRestart: Bool = false) {
        for slot in peerSlots.values where shouldIOffer(remoteCid: slot.remoteCid) {
            maybeSendOffer(slot: slot, force: force, iceRestart: iceRestart)
        }
    }

    private func maybeSendOffer(slot: PeerConnectionSlot, force: Bool = false, iceRestart: Bool = false) {
        if slot.isMakingOffer {
            if iceRestart {
                slot.markPendingIceRestart()
            }
            return
        }

        if !force && slot.sentOffer {
            return
        }

        if !canOffer(slot: slot) {
            return
        }

        if slot.getSignalingState() != "STABLE" {
            if iceRestart {
                slot.markPendingIceRestart()
            }
            return
        }

        slot.beginOffer()
        let started = slot.createOffer(
            iceRestart: iceRestart,
            onSdp: { [weak self] sdp in
                self?.sendMessage(
                    type: "offer",
                    payload: .object(["sdp": .string(sdp)]),
                    to: slot.remoteCid
                )
                self?.scheduleOfferTimeout(remoteCid: slot.remoteCid)
            },
            onComplete: { [weak self] success in
                Task { @MainActor in
                    guard let self else { return }
                    slot.completeOffer()
                    if !success {
                        if iceRestart {
                            self.scheduleIceRestart(remoteCid: slot.remoteCid, reason: "offer-failed", delayMs: 500)
                        } else if self.shouldIOffer(remoteCid: slot.remoteCid) {
                            self.maybeSendOffer(slot: slot)
                        }
                    }
                }
            }
        )

        if !started {
            slot.completeOffer()
            if iceRestart {
                slot.markPendingIceRestart()
            }
            return
        }

        if !force {
            slot.markOfferSent()
        }
    }

    private func canOffer(slot: PeerConnectionSlot) -> Bool {
        guard participantCount > 1 else { return false }
        guard signalingClient.isConnected() else { return false }
        guard shouldIOffer(remoteCid: slot.remoteCid) else { return false }
        return slot.isReady() || slot.ensurePeerConnection()
    }

    private func scheduleOfferTimeout(
        remoteCid: String,
        triggerIceRestart: Bool = true,
        onTimedOut: (() -> Void)? = nil
    ) {
        clearOfferTimeout(remoteCid: remoteCid)
        guard let slot = peerSlots[remoteCid] else { return }

        slot.setOfferTimeoutTask(Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.offerTimeoutNs)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, let slot = self.peerSlots[remoteCid] else { return }
                guard slot.getSignalingState() == "HAVE_LOCAL_OFFER" else { return }
                if triggerIceRestart {
                    slot.markPendingIceRestart()
                }
                slot.rollbackLocalDescription { _ in
                    Task { @MainActor in
                        if triggerIceRestart {
                            if self.shouldIOffer(remoteCid: remoteCid) {
                                self.scheduleIceRestart(remoteCid: remoteCid, reason: "offer-timeout", delayMs: 0)
                            } else {
                                self.maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "offer-timeout")
                            }
                        } else {
                            onTimedOut?()
                        }
                    }
                }
            }
        })
    }

    private func clearOfferTimeout(remoteCid: String? = nil) {
        if let remoteCid {
            peerSlots[remoteCid]?.cancelOfferTimeout()
            return
        }

        for slot in peerSlots.values {
            slot.cancelOfferTimeout()
        }
    }

    private func maybeScheduleNonHostOfferFallback(reason: String) {
        for slot in peerSlots.values where !shouldIOffer(remoteCid: slot.remoteCid) {
            maybeScheduleNonHostOfferFallback(remoteCid: slot.remoteCid, reason: reason)
        }
    }

    private func maybeScheduleNonHostOfferFallback(remoteCid: String, reason: String) {
        guard let slot = peerSlots[remoteCid] else { return }
        guard participantCount > 1 else {
            clearNonHostOfferFallback(remoteCid: remoteCid)
            return
        }
        guard !shouldIOffer(remoteCid: remoteCid) else {
            clearNonHostOfferFallback(remoteCid: remoteCid)
            return
        }
        guard signalingClient.isConnected() else { return }
        guard slot.nonHostFallbackTask == nil else { return }
        guard slot.nonHostFallbackAttempts < WebRtcResilience.nonHostFallbackMaxAttempts else { return }

        slot.setNonHostFallbackTask(Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.nonHostFallbackDelayNs)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, let slot = self.peerSlots[remoteCid] else { return }
                slot.clearNonHostFallbackTask()
                slot.incrementNonHostFallbackAttempts()
                self.maybeSendNonHostFallbackOffer(remoteCid: remoteCid)
            }
        })
    }

    private func clearNonHostOfferFallback(remoteCid: String? = nil) {
        if let remoteCid {
            peerSlots[remoteCid]?.cancelNonHostFallbackTask()
            return
        }

        for slot in peerSlots.values {
            slot.cancelNonHostFallbackTask()
        }
    }

    private func maybeSendNonHostFallbackOffer(remoteCid: String) {
        guard let slot = peerSlots[remoteCid] else { return }
        guard participantCount > 1 else { return }
        guard !shouldIOffer(remoteCid: remoteCid) else { return }
        guard signalingClient.isConnected() else { return }
        guard slot.isReady() || slot.ensurePeerConnection() else { return }
        guard slot.getSignalingState() == "STABLE" else {
            maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "signaling-not-stable")
            return
        }
        guard !slot.hasRemoteDescription() else { return }
        guard !slot.isMakingOffer else {
            maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "already-making-offer")
            return
        }

        slot.beginOffer()
        let started = slot.createOffer(
            onSdp: { [weak self] sdp in
                self?.sendMessage(
                    type: "offer",
                    payload: .object(["sdp": .string(sdp)]),
                    to: remoteCid
                )
                self?.scheduleOfferTimeout(
                    remoteCid: remoteCid,
                    triggerIceRestart: false,
                    onTimedOut: { [weak self] in
                        self?.maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "offer-timeout")
                    }
                )
            },
            onComplete: { [weak self] success in
                Task { @MainActor in
                    guard let self else { return }
                    slot.completeOffer()
                    if !success {
                        self.maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "offer-failed")
                    }
                }
            }
        )

        if !started {
            slot.completeOffer()
            maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "offer-not-started")
        }
    }

    private func scheduleJoinTimeout(roomId: String, joinAttempt: Int64) {
        clearJoinTimeout()

        joinTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.joinHardTimeoutNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.internalPhase == .joining else { return }
            guard self.roomId == roomId else { return }
            guard self.joinAttemptSerial == joinAttempt else { return }
            self.failJoinWithError(.connectionFailed)
        }
    }

    private func clearJoinTimeout() {
        joinTimeoutTask?.cancel()
        joinTimeoutTask = nil
    }

    private func scheduleJoinConnectKickstart(roomId: String, joinAttempt: Int64) {
        clearJoinConnectKickstart()

        joinConnectKickstartTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.joinConnectKickstartNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.internalPhase == .joining else { return }
            guard self.roomId == roomId else { return }
            guard self.joinAttemptSerial == joinAttempt else { return }
            guard !self.hasJoinSignalStartedForAttempt else { return }
            self.ensureSignalingConnection()
        }
    }

    private func clearJoinConnectKickstart() {
        joinConnectKickstartTask?.cancel()
        joinConnectKickstartTask = nil
    }

    private func failJoinWithError(_ error: CallError) {
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearNonHostOfferFallback()
        currentError = error
        resetResources()
        internalPhase = .error
        commitSnapshot()
    }

    private func scheduleIceRestart(reason: String, delayMs: Int) {
        for slot in peerSlots.values where shouldIOffer(remoteCid: slot.remoteCid) {
            scheduleIceRestart(remoteCid: slot.remoteCid, reason: reason, delayMs: delayMs)
        }
    }

    private func scheduleIceRestart(remoteCid: String, reason: String, delayMs: Int) {
        guard let slot = peerSlots[remoteCid] else { return }
        if !canOffer(slot: slot) {
            slot.markPendingIceRestart()
            return
        }

        guard slot.iceRestartTask == nil else { return }

        let now = Date().timeIntervalSince1970 * 1000
        guard now - slot.lastIceRestartAt >= Double(WebRtcResilience.iceRestartCooldownMs) else { return }

        slot.setIceRestartTask(Task { [weak self] in
            if delayMs > 0 {
                try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
            }
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self?.triggerIceRestart(remoteCid: remoteCid, reason: reason)
            }
        })
    }

    private func clearIceRestartTimer(remoteCid: String? = nil) {
        if let remoteCid {
            peerSlots[remoteCid]?.cancelIceRestartTask()
            return
        }

        for slot in peerSlots.values {
            slot.cancelIceRestartTask()
        }
    }

    private func triggerIceRestart(reason: String) {
        for slot in peerSlots.values where shouldIOffer(remoteCid: slot.remoteCid) {
            triggerIceRestart(remoteCid: slot.remoteCid, reason: reason)
        }
    }

    private func triggerIceRestart(remoteCid: String, reason: String) {
        guard let slot = peerSlots[remoteCid] else { return }
        slot.cancelIceRestartTask()

        guard canOffer(slot: slot) else {
            slot.markPendingIceRestart()
            return
        }

        if slot.isMakingOffer {
            slot.markPendingIceRestart()
            return
        }

        slot.recordIceRestart()
        maybeSendOffer(slot: slot, force: true, iceRestart: true)
    }

    private func fetchTurnCredentials(token: String, applyDefaultOnFailure: Bool = true) {
        let roomIdAtFetchStart = roomId
        let joinAttemptAtFetchStart = joinAttemptSerial

        enum TurnFetchOutcome {
            case success(TurnCredentials)
            case failed
            case timedOut
        }

        Task {
            let outcome = await withTaskGroup(of: TurnFetchOutcome.self) { group in
                group.addTask { [apiClient] in
                    do {
                        return .success(try await apiClient.fetchTurnCredentials(host: self.serverHost, token: token))
                    } catch {
                        return .failed
                    }
                }
                group.addTask {
                    try? await Task.sleep(nanoseconds: WebRtcResilience.turnFetchTimeoutNs)
                    return .timedOut
                }
                let first = await group.next() ?? .failed
                group.cancelAll()
                return first
            }

            guard self.roomId == roomIdAtFetchStart else { return }
            guard self.joinAttemptSerial == joinAttemptAtFetchStart else { return }

            switch outcome {
            case .success(let credentials):
                self.applyTurnCredentials(credentials)
            case .timedOut, .failed:
                if applyDefaultOnFailure {
                    self.applyDefaultIceServers()
                }
            }
        }
    }

    private func applyTurnCredentials(_ credentials: TurnCredentials) {
        let servers = credentials.uris.map {
            IceServerConfig(urls: [$0], username: credentials.username, credential: credentials.password)
        }
        webRtcEngine.setIceServers(servers)
        flushPendingMessages()
        maybeSendOffer()
        maybeScheduleNonHostOfferFallback(reason: "turn-ready")
    }

    private func handleTurnRefreshed(_ message: SignalingMessage) {
        guard state.phase != .idle else { return }
        if let ttl = message.payload?.objectValue?["turnTokenTTLMs"]?.intValue {
            turnTokenTTLMs = Int64(ttl)
            scheduleTurnRefresh(ttlMs: Int64(ttl))
        }
        ensureIceSetupIfNeeded(turnToken: turnToken(from: message.payload))
    }

    private func scheduleTurnRefresh(ttlMs: Int64) {
        clearTurnRefresh()
        guard ttlMs > 0 else { return }
        let delayNs = UInt64(Double(ttlMs) * WebRtcResilience.turnRefreshTriggerRatio * 1_000_000)

        turnRefreshTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delayNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.state.phase == .waiting || self.state.phase == .inCall || self.state.phase == .joining else { return }
            guard self.signalingClient.isConnected() else { return }
            self.sendMessage(type: "turn-refresh")
        }
    }

    private func clearTurnRefresh() {
        turnRefreshTask?.cancel()
        turnRefreshTask = nil
    }

    private func applyDefaultIceServers() {
        webRtcEngine.setIceServers([
            IceServerConfig(urls: ["stun:stun.l.google.com:19302"], username: nil, credential: nil)
        ])
        flushPendingMessages()
        maybeSendOffer()
        maybeScheduleNonHostOfferFallback(reason: "default-ice-ready")
    }

    private func flushPendingMessages() {
        guard webRtcEngine.hasIceServers() else { return }
        let pending = pendingMessages
        pendingMessages.removeAll()
        for message in pending {
            processSignalingPayload(message)
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
        stopRemoteVideoStatePolling()

        remoteVideoPollTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.refreshRemoteParticipants()
                self.pollWebRtcStats()
            }
        }
    }

    private func stopRemoteVideoStatePolling() {
        remoteVideoPollTimer?.invalidate()
        remoteVideoPollTimer = nil
        webrtcStatsRequestInFlight = false
        lastWebRtcStatsPollAtMs = 0
    }

    private func pollWebRtcStats() {
        if internalPhase != .inCall && internalPhase != .waiting && internalPhase != .joining {
            return
        }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if webrtcStatsRequestInFlight { return }
        if now - lastWebRtcStatsPollAtMs < 2000 { return }

        webrtcStatsRequestInFlight = true

        let slots = Array(peerSlots.values)
        guard !slots.isEmpty else {
            webrtcStatsRequestInFlight = false
            lastWebRtcStatsPollAtMs = now
            commitSnapshot { _, d in d.realtimeStats = .empty }
            return
        }

        let group = DispatchGroup()
        var stats: [RealtimeCallStats] = []
        let lock = NSLock()

        for slot in slots {
            group.enter()
            slot.collectRealtimeCallStats { realtimeStats in
                lock.lock()
                stats.append(realtimeStats)
                lock.unlock()
                group.leave()
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            self.webrtcStatsRequestInFlight = false
            self.lastWebRtcStatsPollAtMs = Int64(Date().timeIntervalSince1970 * 1000)
            self.commitSnapshot { _, d in
                d.realtimeStats = self.mergeRealtimeStats(stats)
            }
        }
    }

    private func mergeRealtimeStats(_ stats: [RealtimeCallStats]) -> RealtimeCallStats {
        guard !stats.isEmpty else { return .empty }

        func sumNonNil(_ values: [Double]) -> Double? {
            guard !values.isEmpty else { return nil }
            return values.reduce(0, +)
        }

        var merged = RealtimeCallStats.empty
        merged.transportPath = Array(Set(stats.compactMap(\.transportPath))).sorted().joined(separator: " | ")
        if merged.transportPath?.isEmpty == true {
            merged.transportPath = nil
        }
        merged.rttMs = stats.compactMap(\.rttMs).max()
        merged.availableOutgoingKbps = stats.compactMap(\.availableOutgoingKbps).min()
        merged.audioRxPacketLossPct = stats.compactMap(\.audioRxPacketLossPct).max()
        merged.audioTxPacketLossPct = stats.compactMap(\.audioTxPacketLossPct).max()
        merged.audioJitterMs = stats.compactMap(\.audioJitterMs).max()
        merged.audioPlayoutDelayMs = stats.compactMap(\.audioPlayoutDelayMs).max()
        merged.audioConcealedPct = stats.compactMap(\.audioConcealedPct).max()
        merged.audioRxKbps = sumNonNil(stats.compactMap(\.audioRxKbps))
        merged.audioTxKbps = sumNonNil(stats.compactMap(\.audioTxKbps))
        merged.videoRxPacketLossPct = stats.compactMap(\.videoRxPacketLossPct).max()
        merged.videoTxPacketLossPct = stats.compactMap(\.videoTxPacketLossPct).max()
        merged.videoRxKbps = sumNonNil(stats.compactMap(\.videoRxKbps))
        merged.videoTxKbps = sumNonNil(stats.compactMap(\.videoTxKbps))
        merged.videoFps = stats.compactMap(\.videoFps).min()
        let resolutions = Array(Set(stats.compactMap(\.videoResolution))).sorted()
        merged.videoResolution = resolutions.isEmpty ? nil : resolutions.joined(separator: " | ")
        merged.videoFreezeCount60s = stats.compactMap(\.videoFreezeCount60s).reduce(0, +)
        merged.videoFreezeDuration60s = sumNonNil(stats.compactMap(\.videoFreezeDuration60s))
        merged.videoRetransmitPct = stats.compactMap(\.videoRetransmitPct).max()
        merged.videoNackPerMin = sumNonNil(stats.compactMap(\.videoNackPerMin))
        merged.videoPliPerMin = sumNonNil(stats.compactMap(\.videoPliPerMin))
        merged.videoFirPerMin = sumNonNil(stats.compactMap(\.videoFirPerMin))
        merged.updatedAtMs = stats.map(\.updatedAtMs).max() ?? 0
        return merged
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
                try? await Task.sleep(nanoseconds: 1_500_000_000)
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
        clearOfferTimeout()
        clearNonHostOfferFallback()
        clearIceRestartTimer()
        clearConnectionStatusRetryingTimer()
        clearTurnRefresh()

        userPreferredVideoEnabled = config.defaultVideoEnabled
        isVideoPausedByProximity = false
        hasJoinSignalStartedForAttempt = false
        hasJoinAcknowledgedCurrentAttempt = false
        hasInitializedIceSetupForAttempt = false
        lastTurnTokenForAttempt = nil
        reconnectToken = nil
        turnTokenTTLMs = nil

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
        clearJoinRecovery()

        joinRecoveryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.joinRecoveryNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.roomId == roomId else { return }
            guard self.diagnostics.isSignalingConnected else { return }
            guard self.hasJoinAcknowledgedCurrentAttempt else {
                if self.internalPhase == .joining {
                    self.pendingJoinRoom = roomId
                    self.ensureSignalingConnection()
                }
                return
            }

            self.recoverFromJoiningIfNeeded(participantHint: self.currentRoomState?.participants.count)
        }
    }

    private func clearJoinRecovery() {
        joinRecoveryTask?.cancel()
        joinRecoveryTask = nil
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
            try? await Task.sleep(nanoseconds: UInt64(backoff) * 1_000_000)
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
        connectionStatusRetryingTask?.cancel()
        connectionStatusRetryingTask = nil
    }

    private func setConnectionStatus(_ status: SerenadaConnectionStatus) {
        guard state.connectionStatus != status else { return }
        commitSnapshot { s, _ in s.connectionStatus = status }
    }

    private func resetConnectionStatusMachine() {
        clearConnectionStatusRetryingTimer()
        setConnectionStatus(.connected)
    }

    private func scheduleConnectionStatusRetryingTimer() {
        guard connectionStatusRetryingTask == nil else { return }

        connectionStatusRetryingTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: self.connectionStatusRetryingDelayNs)
            guard !Task.isCancelled else { return }
            guard self.internalPhase == .inCall else {
                self.resetConnectionStatusMachine()
                return
            }
            guard self.state.connectionStatus == .recovering else { return }
            self.connectionStatusRetryingTask = nil
            self.setConnectionStatus(.retrying)
        }
    }

    private func markConnectionDegraded() {
        guard internalPhase == .inCall else {
            resetConnectionStatusMachine()
            return
        }

        switch state.connectionStatus {
        case .connected:
            setConnectionStatus(.recovering)
            scheduleConnectionStatusRetryingTimer()
        case .recovering:
            scheduleConnectionStatusRetryingTimer()
        case .retrying:
            break
        }
    }

    private func updateConnectionStatusFromSignals() {
        guard internalPhase == .inCall else {
            resetConnectionStatusMachine()
            return
        }

        if isConnectionDegraded() {
            markConnectionDegraded()
            return
        }

        resetConnectionStatusMachine()
    }

    private func isConnectionDegraded() -> Bool {
        !diagnostics.isSignalingConnected ||
        diagnostics.iceConnectionState == .disconnected ||
        diagnostics.iceConnectionState == .failed ||
        diagnostics.peerConnectionState == .disconnected ||
        diagnostics.peerConnectionState == .failed
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
                    self.markConnectionDegraded()
                }

                if path.status == .satisfied {
                    self.scheduleIceRestart(reason: "network-online", delayMs: 0)
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
            triggerIceRestart(reason: "signaling-reconnect")
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
