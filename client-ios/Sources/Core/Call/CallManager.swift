import AVFoundation
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
    guard currentPhase == .joining || currentPhase == .creatingRoom else { return nil }

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
final class CallManager: ObservableObject {
    private let permissionRequestTimeoutNs: UInt64 = 2_000_000_000

    #if DEBUG
    private enum DebugTrace {
        static let key = "debug_join_trace"
        static let maxEntries = 400
    }
    #endif

    private struct MediaPermissions {
        let cameraGranted: Bool
        let microphoneGranted: Bool
    }

    @Published private(set) var uiState = CallUiState()
    @Published private(set) var serverHost: String
    @Published private(set) var selectedLanguage: String
    @Published private(set) var isDefaultCameraEnabled: Bool
    @Published private(set) var isDefaultMicrophoneEnabled: Bool
    @Published private(set) var isHdVideoExperimentalEnabled: Bool
    @Published private(set) var areSavedRoomsShownFirst: Bool
    @Published private(set) var areRoomInviteNotificationsEnabled: Bool
    @Published private(set) var appVersion: String
    @Published private(set) var recentCalls: [RecentCall] = []
    @Published private(set) var savedRooms: [SavedRoom] = []
    @Published private(set) var roomStatuses: [String: Int] = [:]

    var locale: Locale {
        if selectedLanguage == AppConstants.languageAuto {
            return .autoupdatingCurrent
        }
        return Locale(identifier: selectedLanguage)
    }

    private let apiClient: APIClient
    private let settingsStore: SettingsStore
    private let recentCallStore: RecentCallStore
    private let savedRoomStore: SavedRoomStore
    private let pushSubscriptionManager: PushSubscriptionManager
    private let signalingClient: SignalingClient
    private let pathMonitor = NWPathMonitor()
    private let pathMonitorQueue = DispatchQueue(label: "CallManager.PathMonitor")

    private var callAudioSessionController: CallAudioSessionController!
    private var webRtcEngine: WebRtcEngine!
    private var joinSnapshotFeature: JoinSnapshotFeature!

    private var currentRoomId: String?
    private var activeCallHostOverride: String?
    private var clientId: String?
    private var hostCid: String?
    private var callStartTimeMs: Int64?

    private var watchedRoomIds: [String] = []
    private var pendingJoinRoom: String?
    private var pendingJoinSnapshotId: String?

    private var joinAttemptSerial: Int64 = 0
    private var reconnectAttempts = 0
    private var sentOffer = false
    private var isMakingOffer = false
    private var pendingIceRestart = false
    private var lastIceRestartAt: TimeInterval = 0

    private var reconnectTask: Task<Void, Never>?
    private var joinTimeoutTask: Task<Void, Never>?
    private var joinConnectKickstartTask: Task<Void, Never>?
    private var joinRecoveryTask: Task<Void, Never>?
    private var iceRestartTask: Task<Void, Never>?
    private var offerTimeoutTask: Task<Void, Never>?
    private var nonHostOfferFallbackTask: Task<Void, Never>?
    private var nonHostOfferFallbackAttempts = 0
    private var turnRefreshTask: Task<Void, Never>?
    private var remoteVideoPollTimer: Timer?

    private var lastWebRtcStatsPollAtMs: Int64 = 0
    private var webrtcStatsRequestInFlight = false
    private var pendingMessages: [SignalingMessage] = []
    private var hasJoinSignalStartedForAttempt = false
    private var hasJoinAcknowledgedCurrentAttempt = false
    private var hasInitializedIceSetupForAttempt = false
    private var lastTurnTokenForAttempt: String?
    private var reconnectToken: String?
    private var turnTokenTTLMs: Int64?

    private var userPreferredVideoEnabled = true
    private var isVideoPausedByProximity = false

    #if DEBUG
    private lazy var debugTraceDefaults: UserDefaults = UserDefaults(suiteName: AppConstants.appGroupIdentifier) ?? .standard
    #endif

    init(
        apiClient: APIClient = APIClient(),
        settingsStore: SettingsStore = SettingsStore(),
        recentCallStore: RecentCallStore = RecentCallStore(),
        savedRoomStore: SavedRoomStore = SavedRoomStore(),
        signalingClient: SignalingClient? = nil
    ) {
        self.apiClient = apiClient
        self.settingsStore = settingsStore
        self.recentCallStore = recentCallStore
        self.savedRoomStore = savedRoomStore
        self.pushSubscriptionManager = PushSubscriptionManager(
            apiClient: apiClient,
            settingsStore: settingsStore
        )
        self.signalingClient = signalingClient ?? SignalingClient()

        self.serverHost = settingsStore.host
        self.selectedLanguage = settingsStore.language
        self.isDefaultCameraEnabled = settingsStore.isDefaultCameraEnabled
        self.isDefaultMicrophoneEnabled = settingsStore.isDefaultMicrophoneEnabled
        self.isHdVideoExperimentalEnabled = settingsStore.isHdVideoExperimentalEnabled
        self.areSavedRoomsShownFirst = settingsStore.areSavedRoomsShownFirst
        self.areRoomInviteNotificationsEnabled = settingsStore.areRoomInviteNotificationsEnabled
        self.appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "-"

        self.callAudioSessionController = CallAudioSessionController(
            onProximityChanged: { _ in },
            onAudioEnvironmentChanged: { [weak self] in
                Task { @MainActor in
                    self?.applyLocalVideoPreference()
                }
            }
        )

        self.webRtcEngine = Self.buildWebRtcEngine(
            isHdVideoExperimentalEnabled: settingsStore.isHdVideoExperimentalEnabled,
            eventSink: self
        )
        self.joinSnapshotFeature = JoinSnapshotFeature(
            apiClient: apiClient,
            attachLocalRenderer: { [weak self] renderer in
                self?.webRtcEngine.attachLocalRenderer(renderer)
            },
            detachLocalRenderer: { [weak self] renderer in
                self?.webRtcEngine.detachLocalRenderer(renderer)
            }
        )

        self.signalingClient.listener = self

        startNetworkMonitoring()
        refreshRecentCalls()
        refreshSavedRooms()
    }

    deinit {
        pathMonitor.cancel()
        reconnectTask?.cancel()
        joinTimeoutTask?.cancel()
        joinConnectKickstartTask?.cancel()
        joinRecoveryTask?.cancel()
        iceRestartTask?.cancel()
        offerTimeoutTask?.cancel()
        nonHostOfferFallbackTask?.cancel()
        turnRefreshTask?.cancel()
        remoteVideoPollTimer?.invalidate()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func updateServerHost(_ host: String) {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = trimmed.isEmpty ? AppConstants.defaultHost : trimmed
        let changed = normalized != serverHost

        settingsStore.host = normalized
        serverHost = normalized

        if changed && currentRoomId == nil && !watchedRoomIds.isEmpty {
            signalingClient.close()
            syncSavedRoomPushSubscriptions(savedRooms)
            refreshWatchedRooms()
        }
    }

    func validateServerHost(_ host: String) async -> Result<String, Error> {
        let normalized = host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? AppConstants.defaultHost
            : host.trimmingCharacters(in: .whitespacesAndNewlines)

        do {
            try await apiClient.validateServerHost(normalized)
            return .success(normalized)
        } catch {
            return .failure(error)
        }
    }

    func updateLanguage(_ language: String) {
        let normalized = settingsStore.normalizeLanguage(language)
        guard normalized != selectedLanguage else { return }
        settingsStore.language = normalized
        selectedLanguage = normalized
    }

    func updateDefaultCamera(_ enabled: Bool) {
        settingsStore.isDefaultCameraEnabled = enabled
        isDefaultCameraEnabled = enabled
    }

    func updateDefaultMicrophone(_ enabled: Bool) {
        settingsStore.isDefaultMicrophoneEnabled = enabled
        isDefaultMicrophoneEnabled = enabled
    }

    func updateHdVideoExperimental(_ enabled: Bool) {
        settingsStore.isHdVideoExperimentalEnabled = enabled
        isHdVideoExperimentalEnabled = enabled
        webRtcEngine.setHdVideoExperimentalEnabled(enabled)
    }

    func updateSavedRoomsShownFirst(_ enabled: Bool) {
        settingsStore.areSavedRoomsShownFirst = enabled
        areSavedRoomsShownFirst = enabled
    }

    func updateRoomInviteNotifications(_ enabled: Bool) {
        settingsStore.areRoomInviteNotificationsEnabled = enabled
        areRoomInviteNotificationsEnabled = enabled
    }

    func inviteToCurrentRoom() async -> Result<Void, Error> {
        let roomId = currentRoomId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !roomId.isEmpty else {
            return .failure(NSError(domain: "CallManager", code: 11, userInfo: [NSLocalizedDescriptionKey: "No active room"]))
        }

        do {
            try await apiClient.sendPushInvite(
                host: currentSignalingHost(),
                roomId: roomId,
                endpoint: pushSubscriptionManager.cachedEndpoint()
            )
            return .success(())
        } catch {
            return .failure(error)
        }
    }

    func handleDeepLink(_ url: URL) {
        guard let target = DeepLinkParser.parseTarget(from: url) else { return }
        let roomId = target.roomId

        let isSameActiveRoom =
            (uiState.roomId == roomId || currentRoomId == roomId) &&
            uiState.phase != .idle &&
            uiState.phase != .error &&
            uiState.phase != .ending

        if isSameActiveRoom {
            return
        }

        let hostPolicy = DeepLinkParser.resolveHostPolicy(host: target.host)
        if let persisted = hostPolicy.persistedHost {
            updateServerHost(persisted)
        }

        if target.action == .saveRoom {
            saveRoom(
                roomId: target.roomId,
                name: target.savedRoomName ?? target.roomId,
                host: hostPolicy.oneOffHost
            )
            return
        }

        joinRoom(roomId, oneOffHost: hostPolicy.oneOffHost)
    }

    func joinFromInput(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            uiState.phase = .error
            uiState.errorMessage = L10n.errorEnterRoomOrId
            return
        }

        if let url = URL(string: trimmed), let target = DeepLinkParser.parseTarget(from: url) {
            let hostPolicy = DeepLinkParser.resolveHostPolicy(host: target.host)
            if let persisted = hostPolicy.persistedHost {
                updateServerHost(persisted)
            }

            if target.action == .saveRoom {
                saveRoom(
                    roomId: target.roomId,
                    name: target.savedRoomName ?? target.roomId,
                    host: hostPolicy.oneOffHost
                )
            } else {
                joinRoom(target.roomId, oneOffHost: hostPolicy.oneOffHost)
            }
            return
        }

        joinRoom(trimmed)
    }

    func startNewCall() {
        guard uiState.phase == .idle else { return }

        updateState {
            $0.phase = .creatingRoom
            $0.statusMessage = L10n.callStatusCreatingRoom
            $0.errorMessage = nil
        }

        Task {
            do {
                let roomId = try await apiClient.createRoomId(host: serverHost)
                joinRoom(roomId)
            } catch {
                updateState {
                    $0.phase = .error
                    $0.errorMessage = error.localizedDescription.isEmpty ? L10n.errorFailedCreateRoom : error.localizedDescription
                }
            }
        }
    }

    func joinRoom(_ roomId: String, oneOffHost: String? = nil) {
        let trimmed = roomId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            updateState {
                $0.phase = .error
                $0.errorMessage = L10n.errorInvalidRoomId
            }
            return
        }

        if savedRoomStore.markRoomJoined(roomId: trimmed) {
            refreshSavedRooms()
        }
        activeCallHostOverride = DeepLinkParser.normalizeHostValue(oneOffHost)
        currentRoomId = trimmed
        debugTraceReset("joinRoom rid=\(trimmed) host=\(currentSignalingHost()) oneOff=\(activeCallHostOverride ?? "-")")
        joinAttemptSerial += 1
        callStartTimeMs = Int64(Date().timeIntervalSince1970 * 1000)

        sentOffer = false
        pendingMessages.removeAll()
        pendingJoinSnapshotId = nil
        hasJoinSignalStartedForAttempt = false
        hasJoinAcknowledgedCurrentAttempt = false
        hasInitializedIceSetupForAttempt = false
        lastTurnTokenForAttempt = nil

        recreateWebRtcEngineForNewCall()

        let defaultAudio = settingsStore.isDefaultMicrophoneEnabled
        let defaultVideo = settingsStore.isDefaultCameraEnabled
        userPreferredVideoEnabled = defaultVideo

        updateState {
            $0.phase = .joining
            $0.roomId = trimmed
            $0.statusMessage = L10n.callStatusJoiningRoom
            $0.errorMessage = nil
            $0.localAudioEnabled = defaultAudio
            $0.localVideoEnabled = defaultVideo
            $0.localCameraMode = .selfie
            $0.cameraZoomFactor = 1
            $0.webrtcStatsSummary = ""
            $0.realtimeStats = .empty
            $0.isFlashAvailable = false
            $0.isFlashEnabled = false
        }

        let currentJoinAttempt = joinAttemptSerial
        scheduleJoinTimeout(roomId: trimmed, joinAttempt: currentJoinAttempt)
        scheduleJoinConnectKickstart(roomId: trimmed, joinAttempt: currentJoinAttempt)
        Task { [weak self] in
            guard let self else { return }
            self.debugTrace("joinTask begin rid=\(trimmed) attempt=\(currentJoinAttempt)")
            let permissions = await self.resolveMediaPermissions()
            self.debugTrace(
                "joinTask permissions rid=\(trimmed) attempt=\(currentJoinAttempt) cam=\(permissions.cameraGranted) mic=\(permissions.microphoneGranted)"
            )
            await self.prepareMediaAndConnect(
                roomId: trimmed,
                joinAttempt: currentJoinAttempt,
                defaultAudioEnabled: defaultAudio,
                defaultVideoEnabled: defaultVideo,
                permissions: permissions
            )
        }
    }

    func leaveCall() {
        guard uiState.phase != .idle else { return }
        sendMessage(type: "leave")
        cleanupCall(message: L10n.callStatusLeftRoom)
    }

    func dismissError() {
        if uiState.phase == .error {
            setUiState(CallUiState())
            refreshRecentCalls()
            refreshSavedRooms()
        }
    }

    func removeRecentCall(roomId: String) {
        recentCallStore.removeCall(roomId: roomId)
        refreshRecentCalls()
    }

    func saveRoom(roomId: String, name: String, host: String? = nil) {
        let normalizedRoomId = roomId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedRoomId.isEmpty else { return }
        guard let normalizedName = DeepLinkParser.normalizeSavedRoomName(name) else { return }

        let normalizedHost = DeepLinkParser.normalizeHostValue(host)
        let hostOverride = normalizedHost.flatMap { DeepLinkParser.isTrustedHost($0) ? nil : $0 }

        let room = SavedRoom(
            roomId: normalizedRoomId,
            name: normalizedName,
            createdAt: Int64(Date().timeIntervalSince1970 * 1000),
            host: hostOverride,
            lastJoinedAt: nil
        )
        savedRoomStore.saveRoom(room)
        refreshSavedRooms()
    }

    func joinSavedRoom(_ room: SavedRoom) {
        joinRoom(room.roomId, oneOffHost: room.host)
    }

    func removeSavedRoom(roomId: String) {
        savedRoomStore.removeRoom(roomId: roomId)
        refreshSavedRooms()
    }

    func createSavedRoomInviteLink(roomName: String, hostInput: String) async -> Result<String, Error> {
        guard let normalizedName = DeepLinkParser.normalizeSavedRoomName(roomName) else {
            return .failure(NSError(domain: "CallManager", code: 1, userInfo: [NSLocalizedDescriptionKey: L10n.errorInvalidSavedRoomName]))
        }

        let targetHostInput = hostInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? serverHost
            : hostInput
        guard let normalizedHost = DeepLinkParser.normalizeHostValue(targetHostInput) else {
            return .failure(NSError(domain: "CallManager", code: 2, userInfo: [NSLocalizedDescriptionKey: L10n.settingsErrorInvalidServerHost]))
        }

        do {
            let roomId = try await apiClient.createRoomId(host: normalizedHost)
            let roomHostOverride = DeepLinkParser.isTrustedHost(normalizedHost) ? nil : normalizedHost
            saveRoom(roomId: roomId, name: normalizedName, host: roomHostOverride)
            return .success(buildSavedRoomInviteLink(host: normalizedHost, roomId: roomId, roomName: normalizedName))
        } catch {
            return .failure(error)
        }
    }

    func endCall() {
        guard uiState.phase != .idle else { return }
        debugTrace("ui endCall phase=\(uiState.phase.rawValue) room=\(currentRoomId ?? "-")")
        sendMessage(type: "leave")
        cleanupCall(message: L10n.callStatusLeftRoom)
    }

    func toggleAudio() {
        debugTrace("ui toggleAudio from=\(uiState.localAudioEnabled)")
        let enabled = !uiState.localAudioEnabled
        webRtcEngine.toggleAudio(enabled)
        updateState { $0.localAudioEnabled = enabled }
    }

    func toggleVideo() {
        debugTrace("ui toggleVideo from=\(uiState.localVideoEnabled)")
        userPreferredVideoEnabled = !uiState.localVideoEnabled
        applyLocalVideoPreference()
    }

    @discardableResult
    func toggleFlashlight() -> Bool {
        webRtcEngine.toggleFlashlight()
    }

    func flipCamera() {
        if !uiState.isScreenSharing {
            debugTrace("ui flipCamera mode=\(uiState.localCameraMode.rawValue)")
            webRtcEngine.flipCamera()
        }
    }

    func toggleScreenShare() {
        if uiState.isScreenSharing {
            debugTrace("ui stopScreenShare")
            _ = webRtcEngine.stopScreenShare()
            return
        }

        debugTrace("ui startScreenShare")
        _ = webRtcEngine.startScreenShare { [weak self] started in
            Task { @MainActor in
                guard let self else { return }
                guard started else { return }
                self.updateState {
                    $0.isScreenSharing = true
                    $0.localCameraMode = .screenShare
                    $0.cameraZoomFactor = 1
                }
                self.applyLocalVideoPreference()
            }
        }
    }

    func adjustCameraZoom(scaleDelta: CGFloat) {
        guard uiState.phase == .inCall else { return }
        guard uiState.localCameraMode == .world || uiState.localCameraMode == .composite else { return }
        if let zoom = webRtcEngine.adjustCaptureZoom(by: scaleDelta) {
            updateState { $0.cameraZoomFactor = zoom }
        }
    }

    func resetCameraZoom() {
        let zoom = webRtcEngine.resetCaptureZoom()
        updateState { $0.cameraZoomFactor = zoom }
    }

    func attachLocalRenderer(_ renderer: AnyObject) {
        webRtcEngine.attachLocalRenderer(renderer)
    }

    func detachLocalRenderer(_ renderer: AnyObject) {
        webRtcEngine.detachLocalRenderer(renderer)
    }

    func attachRemoteRenderer(_ renderer: AnyObject) {
        webRtcEngine.attachRemoteRenderer(renderer)
    }

    func detachRemoteRenderer(_ renderer: AnyObject) {
        webRtcEngine.detachRemoteRenderer(renderer)
    }

    private func ensureSignalingConnection() {
        hasJoinSignalStartedForAttempt = true
        let roomToJoin = currentRoomId
        debugTrace("ensureSignalingConnection connected=\(signalingClient.isConnected()) room=\(roomToJoin ?? "-") pending=\(pendingJoinRoom ?? "-")")
        if signalingClient.isConnected() {
            if let roomToJoin {
                pendingJoinRoom = nil
                sendJoin(roomId: roomToJoin, snapshotId: pendingJoinSnapshotId)
            }
            sendWatchRoomsIfNeeded()
            return
        }

        pendingJoinRoom = roomToJoin
        signalingClient.connect(host: currentSignalingHost())
    }

    private func sendJoin(roomId: String, snapshotId: String? = nil) {
        debugTrace("sendJoin begin rid=\(roomId) connected=\(signalingClient.isConnected())")
        let trimmedSnapshotId = snapshotId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let effectiveSnapshotId: String? = {
            if let trimmedSnapshotId, !trimmedSnapshotId.isEmpty {
                return trimmedSnapshotId
            }
            if let pending = pendingJoinSnapshotId?.trimmingCharacters(in: .whitespacesAndNewlines), !pending.isEmpty {
                return pending
            }
            return nil
        }()

        Task { @MainActor [weak self] in
            guard let self else { return }
            let endpoint = await self.fetchJoinPushEndpointWithTimeout()
            self.debugTrace("sendJoin endpointReady rid=\(roomId) endpoint=\(endpoint?.isEmpty == false ? "yes" : "no")")
            guard self.currentRoomId == roomId else { return }
            guard self.signalingClient.isConnected() else {
                self.debugTrace("sendJoin deferred rid=\(roomId) reason=disconnected")
                self.pendingJoinRoom = roomId
                self.pendingJoinSnapshotId = effectiveSnapshotId
                self.ensureSignalingConnection()
                return
            }

            var payload: [String: JSONValue] = [
                "device": .string("ios"),
                "capabilities": .object(["trickleIce": .bool(true)])
            ]

            let reconnectCid = self.clientId ?? self.settingsStore.reconnectCid
            if let reconnectCid {
                payload["reconnectCid"] = .string(reconnectCid)
            }
            if let reconnectToken = self.reconnectToken {
                payload["reconnectToken"] = .string(reconnectToken)
            }
            if let endpoint, !endpoint.isEmpty {
                payload["pushEndpoint"] = .string(endpoint)
            }
            if let effectiveSnapshotId {
                payload["snapshotId"] = .string(effectiveSnapshotId)
            }

            let message = SignalingMessage(
                type: "join",
                rid: roomId,
                payload: .object(payload)
            )

            self.signalingClient.send(message)
            self.debugTrace(
                "sendJoin sent rid=\(roomId) reconnectCid=\(self.clientId ?? self.settingsStore.reconnectCid ?? "-") " +
                    "snapshot=\(effectiveSnapshotId == nil ? "no" : "yes")"
            )
            self.pendingJoinSnapshotId = nil
            self.scheduleJoinRecovery(for: roomId)
        }
    }

    private func fetchJoinPushEndpointWithTimeout() async -> String? {
        if let cached = pushSubscriptionManager.cachedEndpoint() {
            return cached
        }

        return await withTaskGroup(of: String?.self) { group in
            group.addTask { @MainActor [weak self] in
                guard let self else { return nil }
                return await self.pushSubscriptionManager.refreshPushEndpoint()
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: WebRtcResilience.joinPushEndpointWaitNs)
                return nil
            }

            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    private func sendMessage(type: String, payload: JSONValue? = nil, to: String? = nil) {
        let message = SignalingMessage(
            type: type,
            rid: currentRoomId,
            cid: clientId,
            to: to,
            payload: payload
        )
        signalingClient.send(message)
    }

    private func sendWatchRoomsIfNeeded() {
        guard !watchedRoomIds.isEmpty else { return }
        guard signalingClient.isConnected() else { return }

        let payload: JSONValue = .object([
            "rids": .array(watchedRoomIds.map { .string($0) })
        ])

        signalingClient.send(
            SignalingMessage(type: "watch_rooms", payload: payload)
        )
    }

    private func handleSignalingMessage(_ message: SignalingMessage) {
        switch message.type {
        case "joined":
            handleJoined(message)
        case "room_state":
            handleRoomState(message)
        case "room_ended":
            handleRoomEnded()
        case "room_statuses":
            roomStatuses = RoomStatuses.mergeStatusesPayload(previous: roomStatuses, payload: message.payload)
        case "room_status_update":
            roomStatuses = RoomStatuses.mergeStatusUpdatePayload(previous: roomStatuses, payload: message.payload)
        case "pong":
            signalingClient.recordPong()
        case "turn-refreshed":
            handleTurnRefreshed(message)
        case "offer", "answer", "ice":
            handleSignalingPayload(message)
        case "error":
            handleError(message)
        default:
            break
        }
    }

    private func handleJoined(_ message: SignalingMessage) {
        if let messageRoomId = message.rid, let activeRoomId = currentRoomId, messageRoomId != activeRoomId {
            return
        }

        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
        debugTrace("rx joined rid=\(message.rid ?? "-") cid=\(message.cid ?? "-")")

        hasJoinAcknowledgedCurrentAttempt = true
        clientId = message.cid
        settingsStore.reconnectCid = message.cid

        if let token = message.payload?.objectValue?["reconnectToken"]?.stringValue {
            reconnectToken = token
        }
        if let ttl = message.payload?.objectValue?["turnTokenTTLMs"]?.intValue {
            turnTokenTTLMs = Int64(ttl)
            scheduleTurnRefresh(ttlMs: Int64(ttl))
        }

        if let joinedRoomId = message.rid ?? currentRoomId {
            pushSubscriptionManager.subscribeRoom(roomId: joinedRoomId, host: currentSignalingHost())
        }

        ensureIceSetupIfNeeded(
            turnToken: turnToken(from: message.payload),
            source: "joined"
        )

        if let roomState = parseRoomState(payload: message.payload) {
            debugTrace(
                "handleJoined parsedRoomState hostCid=\(roomState.hostCid) participants=\(roomState.participants.count)"
            )
            hostCid = roomState.hostCid
            updateParticipants(roomState)
            debugTrace("handleJoined afterUpdateParticipants")
        } else {
            debugTrace("handleJoined parseRoomState=nil participantsHint=\(participantCountHint(payload: message.payload)?.description ?? "-")")
            recoverFromJoiningIfNeeded(participantHint: participantCountHint(payload: message.payload))
        }
        debugTrace("handleJoined end")
    }

    private func handleRoomState(_ message: SignalingMessage) {
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
        debugTrace("rx room_state rid=\(message.rid ?? "-")")
        hasJoinAcknowledgedCurrentAttempt = true
        ensureIceSetupIfNeeded(
            turnToken: turnToken(from: message.payload),
            source: "room_state"
        )

        guard let roomState = parseRoomState(payload: message.payload) else {
            debugTrace("handleRoomState parseRoomState=nil participantsHint=\(participantCountHint(payload: message.payload)?.description ?? "-")")
            recoverFromJoiningIfNeeded(participantHint: participantCountHint(payload: message.payload))
            return
        }
        debugTrace(
            "handleRoomState parsedRoomState hostCid=\(roomState.hostCid) participants=\(roomState.participants.count)"
        )
        hostCid = roomState.hostCid
        updateParticipants(roomState)
    }

    private func handleRoomEnded() {
        cleanupCall(message: L10n.callStatusRoomEnded)
    }

    private func turnToken(from payload: JSONValue?) -> String? {
        payload?.objectValue?["turnToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func ensureIceSetupIfNeeded(turnToken: String?, source: String) {
        let normalizedToken = turnToken?.trimmingCharacters(in: .whitespacesAndNewlines)

        if !hasInitializedIceSetupForAttempt {
            hasInitializedIceSetupForAttempt = true
            debugTrace("iceSetup init source=\(source) turn=\(normalizedToken?.isEmpty == false ? "yes" : "no")")
            // Start with default STUN immediately; TURN credentials are applied when available.
            applyDefaultIceServers()
        } else {
            debugTrace("iceSetup reuse source=\(source) turn=\(normalizedToken?.isEmpty == false ? "yes" : "no")")
        }

        guard let normalizedToken, !normalizedToken.isEmpty else { return }
        guard lastTurnTokenForAttempt != normalizedToken else {
            debugTrace("turn fetch skipped source=\(source) reason=duplicate-token")
            return
        }

        lastTurnTokenForAttempt = normalizedToken
        debugTrace("turn fetch queued source=\(source)")
        fetchTurnCredentials(token: normalizedToken, applyDefaultOnFailure: false)
    }

    private func handleError(_ message: SignalingMessage) {
        let rawMessage = message.payload?.objectValue?["message"]?.stringValue
        debugTrace("rx error rid=\(message.rid ?? "-") message=\(rawMessage ?? "-")")
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
        resetResources()
        setUiState(CallUiState(
            phase: .error,
            errorMessage: rawMessage?.isEmpty == false ? rawMessage : L10n.errorUnknown
        ))
    }

    private func handleSignalingPayload(_ message: SignalingMessage) {
        if message.type == "offer" || message.type == "answer" || message.type == "ice" {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint(payload: message.payload), preferInCall: true)
        }
        if message.type == "answer" {
            clearNonHostOfferFallback()
        }

        if !webRtcEngine.isReady() {
            debugTrace("handleSignalingPayload ensurePc type=\(message.type)")
            ensureIceSetupIfNeeded(turnToken: nil, source: "payload-\(message.type)")
            webRtcEngine.ensurePeerConnection()
            if !webRtcEngine.isReady() {
                debugTrace("handleSignalingPayload queued type=\(message.type) reason=pc-not-ready")
                pendingMessages.append(message)
                return
            }
        }

        if message.type == "offer" || message.type == "answer" {
            debugTrace("handleSignalingPayload process type=\(message.type)")
        }
        processSignalingPayload(message)
    }

    private func processSignalingPayload(_ message: SignalingMessage) {
        switch message.type {
        case "offer":
            guard let sdp = message.payload?.objectValue?["sdp"]?.stringValue, !sdp.isEmpty else { return }
            webRtcEngine.setRemoteDescription(type: .offer, sdp: sdp) { [weak self] success in
                guard let self else { return }
                guard success else {
                    self.debugTrace("offer apply failed rid=\(message.rid ?? "-")")
                    self.maybeScheduleNonHostOfferFallback(reason: "offer-apply-failed")
                    return
                }
                self.clearNonHostOfferFallback()
                self.webRtcEngine.createAnswer(onSdp: { answerSdp in
                    self.debugTrace("answer send rid=\(self.currentRoomId ?? "-")")
                    self.sendMessage(type: "answer", payload: .object(["sdp": .string(answerSdp)]))
                }, onComplete: { [weak self] answerSuccess in
                    Task { @MainActor in
                        guard let self else { return }
                        if !answerSuccess {
                            self.debugTrace("answer create failed rid=\(self.currentRoomId ?? "-")")
                            self.maybeScheduleNonHostOfferFallback(reason: "answer-create-failed")
                        }
                    }
                })
            }

        case "answer":
            guard let sdp = message.payload?.objectValue?["sdp"]?.stringValue, !sdp.isEmpty else { return }
            webRtcEngine.setRemoteDescription(type: .answer, sdp: sdp) { [weak self] success in
                guard let self else { return }
                if success {
                    self.clearOfferTimeout()
                    self.pendingIceRestart = false
                } else {
                    self.debugTrace("answer apply failed rid=\(message.rid ?? "-")")
                    self.scheduleIceRestart(reason: "answer-apply-failed", delayMs: 0)
                }
            }

        case "ice":
            guard let candidateObject = message.payload?.objectValue?["candidate"]?.objectValue else { return }
            guard let candidate = candidateObject["candidate"]?.stringValue else { return }
            let sdpMid = candidateObject["sdpMid"]?.stringValue
            let sdpMLineIndex = Int32(candidateObject["sdpMLineIndex"]?.intValue ?? 0)

            webRtcEngine.addIceCandidate(
                IceCandidatePayload(
                    sdpMid: sdpMid,
                    sdpMLineIndex: sdpMLineIndex,
                    candidate: candidate
                )
            )

        default:
            break
        }
    }

    private func updateParticipants(_ roomState: RoomState) {
        let count = max(1, roomState.participants.count)
        let isHostNow = clientId != nil && clientId == roomState.hostCid

        let phase: CallPhase = (count <= 1) ? .waiting : .inCall
        debugTrace(
            "updateParticipants count=\(count) hostCid=\(roomState.hostCid) isHost=\(isHostNow) nextPhase=\(phase.rawValue)"
        )
        if phase != .joining {
            clearJoinTimeout()
        }

        if count <= 1 {
            sentOffer = false
            clearOfferTimeout()
            clearIceRestartTimer()
            clearNonHostOfferFallback()
            pendingIceRestart = false
            isMakingOffer = false
            if webRtcEngine.isReady() {
                webRtcEngine.closePeerConnection()
            }
        }

        updateState {
            $0.phase = phase
            $0.isHost = isHostNow
            $0.participantCount = count
            $0.statusMessage = count <= 1 ? L10n.callStatusWaitingForJoin : L10n.callStatusInCall
        }
        debugTrace("updateParticipants stateApplied count=\(count) phase=\(phase.rawValue)")

        if count > 1 {
            debugTrace("updateParticipants ensurePeerConnection begin")
            webRtcEngine.ensurePeerConnection()
            debugTrace("updateParticipants ensurePeerConnection end")
        }

        if count > 1 && isHostNow {
            clearNonHostOfferFallback()
            debugTrace("updateParticipants maybeSendOffer host=true")
            maybeSendOffer()
        } else if count > 1 {
            debugTrace("updateParticipants maybeScheduleFallback host=false")
            DispatchQueue.main.async { [weak self] in
                self?.maybeScheduleNonHostOfferFallback(reason: "participants")
            }
        }
    }

    private func maybeSendOffer(force: Bool = false, iceRestart: Bool = false) {
        if isMakingOffer {
            if iceRestart {
                pendingIceRestart = true
            }
            return
        }

        if !force && sentOffer {
            return
        }

        if !canOffer() {
            return
        }

        if webRtcEngine.signalingStateRaw() != "STABLE" {
            if iceRestart {
                pendingIceRestart = true
            }
            return
        }

        isMakingOffer = true

        let started = webRtcEngine.createOffer(
            iceRestart: iceRestart,
            onSdp: { [weak self] sdp in
                self?.sendMessage(type: "offer", payload: .object(["sdp": .string(sdp)]))
                self?.scheduleOfferTimeout()
            },
            onComplete: { [weak self] success in
                Task { @MainActor in
                    guard let self else { return }
                    self.isMakingOffer = false
                    if !success && iceRestart {
                        self.scheduleIceRestart(reason: "offer-failed", delayMs: 500)
                    }
                }
            }
        )

        if !started {
            isMakingOffer = false
            if iceRestart {
                pendingIceRestart = true
            }
            return
        }

        if !force {
            sentOffer = true
        }
    }

    private func canOffer() -> Bool {
        if !uiState.isHost || uiState.participantCount <= 1 { return false }
        if !webRtcEngine.isReady() { return false }
        if !signalingClient.isConnected() { return false }
        return true
    }

    private func scheduleOfferTimeout(triggerIceRestart: Bool = true, onTimedOut: (() -> Void)? = nil) {
        clearOfferTimeout()

        offerTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.offerTimeoutNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }

            if self.webRtcEngine.signalingStateRaw() == "HAVE_LOCAL_OFFER" {
                if triggerIceRestart {
                    self.pendingIceRestart = true
                }
                self.webRtcEngine.rollbackLocalDescription { [weak self] _ in
                    Task { @MainActor in
                        guard let self else { return }
                        if triggerIceRestart {
                            self.scheduleIceRestart(reason: "offer-timeout", delayMs: 0)
                        } else {
                            onTimedOut?()
                        }
                    }
                }
            }
        }
    }

    private func clearOfferTimeout() {
        offerTimeoutTask?.cancel()
        offerTimeoutTask = nil
    }

    private func maybeScheduleNonHostOfferFallback(reason: String) {
        guard let roomId = currentRoomId else { return }
        guard uiState.participantCount > 1 else {
            clearNonHostOfferFallback()
            return
        }
        guard !uiState.isHost else {
            clearNonHostOfferFallback()
            return
        }
        guard signalingClient.isConnected() else { return }
        guard nonHostOfferFallbackTask == nil else { return }
        guard nonHostOfferFallbackAttempts < WebRtcResilience.nonHostFallbackMaxAttempts else { return }

        debugTrace("nonHostOfferFallback scheduled rid=\(roomId) reason=\(reason)")
        nonHostOfferFallbackTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.nonHostFallbackDelayNs)
            guard !Task.isCancelled else { return }
            await MainActor.run { [weak self] in
                guard let self else { return }
                self.nonHostOfferFallbackTask = nil
                guard self.currentRoomId == roomId else { return }
                self.nonHostOfferFallbackAttempts += 1
                self.debugTrace("nonHostOfferFallback trigger (attempt \(self.nonHostOfferFallbackAttempts))")
                self.maybeSendNonHostFallbackOffer()
            }
        }
    }

    private func clearNonHostOfferFallback() {
        nonHostOfferFallbackTask?.cancel()
        nonHostOfferFallbackTask = nil
    }

    private func maybeSendNonHostFallbackOffer() {
        guard uiState.participantCount > 1 else { return }
        guard !uiState.isHost else { return }
        guard signalingClient.isConnected() else { return }
        guard webRtcEngine.isReady() else { return }
        guard webRtcEngine.signalingStateRaw() == "STABLE" else {
            maybeScheduleNonHostOfferFallback(reason: "signaling-not-stable")
            return
        }
        guard !webRtcEngine.hasRemoteDescription() else { return }
        guard !isMakingOffer else {
            maybeScheduleNonHostOfferFallback(reason: "already-making-offer")
            return
        }

        debugTrace(
            "nonHostOfferFallback trigger rid=\(currentRoomId ?? "-") cid=\(clientId ?? "-") hostCid=\(hostCid ?? "-")"
        )

        isMakingOffer = true

        let started = webRtcEngine.createOffer(
            onSdp: { [weak self] sdp in
                self?.sendMessage(type: "offer", payload: .object(["sdp": .string(sdp)]))
                self?.scheduleOfferTimeout(triggerIceRestart: false, onTimedOut: { [weak self] in
                    self?.maybeScheduleNonHostOfferFallback(reason: "offer-timeout")
                })
            },
            onComplete: { [weak self] success in
                Task { @MainActor in
                    guard let self else { return }
                    self.isMakingOffer = false
                    if !success {
                        self.maybeScheduleNonHostOfferFallback(reason: "offer-failed")
                    }
                }
            }
        )

        if !started {
            isMakingOffer = false
            maybeScheduleNonHostOfferFallback(reason: "offer-not-started")
        }
    }

    private func scheduleJoinTimeout(roomId: String, joinAttempt: Int64) {
        clearJoinTimeout()
        debugTrace("scheduleJoinTimeout rid=\(roomId) attempt=\(joinAttempt)")

        joinTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.joinHardTimeoutNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }

            let isStillJoining =
                self.uiState.phase == .joining &&
                self.currentRoomId == roomId &&
                self.joinAttemptSerial == joinAttempt
            guard isStillJoining else { return }

            self.debugTrace("joinTimeoutFired rid=\(roomId) attempt=\(joinAttempt)")
            self.failJoinWithError(message: L10n.callStatusConnectionFailed)
        }
    }

    private func clearJoinTimeout() {
        joinTimeoutTask?.cancel()
        joinTimeoutTask = nil
    }

    private func scheduleJoinConnectKickstart(roomId: String, joinAttempt: Int64) {
        clearJoinConnectKickstart()
        debugTrace("scheduleJoinConnectKickstart rid=\(roomId) attempt=\(joinAttempt)")

        joinConnectKickstartTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.joinConnectKickstartNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }

            guard self.uiState.phase == .joining else { return }
            guard self.currentRoomId == roomId else { return }
            guard self.joinAttemptSerial == joinAttempt else { return }
            guard !self.hasJoinSignalStartedForAttempt else { return }

            self.debugTrace("joinConnectKickstartFired rid=\(roomId) attempt=\(joinAttempt)")
            self.ensureSignalingConnection()
        }
    }

    private func clearJoinConnectKickstart() {
        joinConnectKickstartTask?.cancel()
        joinConnectKickstartTask = nil
    }

    private func failJoinWithError(message: String) {
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearNonHostOfferFallback()
        resetResources()
        setUiState(CallUiState(
            phase: .error,
            errorMessage: message
        ))
    }

    private func scheduleIceRestart(reason: String, delayMs: Int) {
        if !canOffer() {
            pendingIceRestart = true
            return
        }

        if iceRestartTask != nil {
            return
        }

        let now = Date().timeIntervalSince1970 * 1000
        if now - lastIceRestartAt < Double(WebRtcResilience.iceRestartCooldownMs) {
            return
        }

        iceRestartTask = Task { [weak self] in
            if delayMs > 0 {
                try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
            }
            guard !Task.isCancelled else { return }
            await self?.triggerIceRestart(reason: reason)
        }
    }

    private func clearIceRestartTimer() {
        iceRestartTask?.cancel()
        iceRestartTask = nil
    }

    private func triggerIceRestart(reason: String) {
        iceRestartTask?.cancel()
        iceRestartTask = nil

        if !canOffer() {
            pendingIceRestart = true
            return
        }

        if isMakingOffer {
            pendingIceRestart = true
            return
        }

        _ = reason
        lastIceRestartAt = Date().timeIntervalSince1970 * 1000
        pendingIceRestart = false
        maybeSendOffer(force: true, iceRestart: true)
    }

    private func fetchTurnCredentials(token: String, applyDefaultOnFailure: Bool = true) {
        debugTrace("turn fetch start host=\(currentSignalingHost())")
        enum TurnFetchOutcome {
            case success(TurnCredentials)
            case failed
            case timedOut
        }

        let roomIdAtFetchStart = currentRoomId
        let joinAttemptAtFetchStart = joinAttemptSerial
        Task {
            let outcome = await withTaskGroup(of: TurnFetchOutcome.self) { group in
                let host = self.currentSignalingHost()
                group.addTask { [apiClient] in
                    do {
                        let credentials = try await apiClient.fetchTurnCredentials(host: host, token: token)
                        return .success(credentials)
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

            guard currentRoomId == roomIdAtFetchStart else { return }
            guard joinAttemptSerial == joinAttemptAtFetchStart else { return }

            switch outcome {
            case .success(let credentials):
                debugTrace("turn fetch success uris=\(credentials.uris.count)")
                applyTurnCredentials(credentials)
            case .timedOut:
                debugTrace("turn fetch timeout fallback=default")
                if applyDefaultOnFailure {
                    applyDefaultIceServers()
                }
            case .failed:
                debugTrace("turn fetch failed fallback=default")
                if applyDefaultOnFailure {
                    applyDefaultIceServers()
                }
            }
        }
    }

    private func applyTurnCredentials(_ credentials: TurnCredentials) {
        let servers: [IceServerConfig] = credentials.uris.map {
            IceServerConfig(urls: [$0], username: credentials.username, credential: credentials.password)
        }

        webRtcEngine.setIceServers(servers)
        debugTrace("turn apply custom servers=\(servers.count)")
        flushPendingMessages()
        maybeSendOffer()
        maybeScheduleNonHostOfferFallback(reason: "turn-ready")
    }

    private func handleTurnRefreshed(_ message: SignalingMessage) {
        guard currentRoomId != nil else { return }
        debugTrace("rx turn-refreshed")
        if let ttl = message.payload?.objectValue?["turnTokenTTLMs"]?.intValue {
            turnTokenTTLMs = Int64(ttl)
            scheduleTurnRefresh(ttlMs: Int64(ttl))
        }
        ensureIceSetupIfNeeded(
            turnToken: turnToken(from: message.payload),
            source: "turn-refreshed"
        )
    }

    private func scheduleTurnRefresh(ttlMs: Int64) {
        clearTurnRefresh()
        guard ttlMs > 0 else { return }
        let delayNs = UInt64(Double(ttlMs) * WebRtcResilience.turnRefreshTriggerRatio * 1_000_000)
        let roomId = currentRoomId

        turnRefreshTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delayNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.currentRoomId == roomId, self.currentRoomId != nil else { return }
            guard self.signalingClient.isConnected() else { return }
            self.debugTrace("turn-refresh send")
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
        debugTrace("turn apply default")
        flushPendingMessages()
        maybeSendOffer()
        maybeScheduleNonHostOfferFallback(reason: "default-ice-ready")
    }

    private func flushPendingMessages() {
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
                debugTrace(
                    "parseRoomState hostFallback from=\(currentHostCid) to=\(resolvedHostCid ?? "-") participants=\(participants.count)"
                )
            }
        }

        guard let resolvedHostCid, !resolvedHostCid.isEmpty else { return nil }
        return RoomState(hostCid: resolvedHostCid, participants: participants)
    }

    private func refreshRemoteVideoEnabled() {
        let enabled = webRtcEngine.isRemoteVideoTrackEnabled()
        if uiState.remoteVideoEnabled != enabled {
            updateState { $0.remoteVideoEnabled = enabled }
        }
    }

    private func startRemoteVideoStatePolling() {
        stopRemoteVideoStatePolling()

        remoteVideoPollTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.refreshRemoteVideoEnabled()
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
        if uiState.phase != .inCall && uiState.phase != .waiting && uiState.phase != .joining {
            return
        }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if webrtcStatsRequestInFlight { return }
        if now - lastWebRtcStatsPollAtMs < 2000 { return }

        webrtcStatsRequestInFlight = true

        webRtcEngine.collectRealtimeCallStats { [weak self] realtimeStats in
            Task { @MainActor in
                guard let self else { return }
                if self.uiState.realtimeStats != realtimeStats {
                    self.updateState { $0.realtimeStats = realtimeStats }
                }

                self.webRtcEngine.collectWebRtcStatsSummary { [weak self] summary in
                    Task { @MainActor in
                        guard let self else { return }
                        self.webrtcStatsRequestInFlight = false
                        self.lastWebRtcStatsPollAtMs = Int64(Date().timeIntervalSince1970 * 1000)
                        if self.uiState.webrtcStatsSummary != summary {
                            self.updateState { $0.webrtcStatsSummary = summary }
                        }
                    }
                }
            }
        }
    }

    private func cleanupCall(message: String) {
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
        clearNonHostOfferFallback()
        updateState {
            $0.phase = .ending
            $0.statusMessage = message
            $0.localVideoEnabled = false
            $0.remoteVideoEnabled = false
        }

        saveCurrentCallToHistoryIfNeeded()

        if uiState.isScreenSharing {
            _ = webRtcEngine.stopScreenShare()
        }

        settingsStore.reconnectCid = nil
        resetResources()

        setUiState(CallUiState(phase: .idle))
        watchRoomsIfNeeded()
    }

    private func resetResources() {
        stopRemoteVideoStatePolling()

        signalingClient.close()
        webRtcEngine.release()
        deactivateAudioSession()

        currentRoomId = nil
        activeCallHostOverride = nil
        hostCid = nil
        clientId = nil
        callStartTimeMs = nil

        pendingJoinRoom = nil
        pendingJoinSnapshotId = nil
        pendingMessages.removeAll()

        reconnectAttempts = 0
        sentOffer = false
        isMakingOffer = false
        pendingIceRestart = false

        reconnectTask?.cancel()
        reconnectTask = nil
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
        clearOfferTimeout()
        clearNonHostOfferFallback()
        nonHostOfferFallbackAttempts = 0
        clearIceRestartTimer()
        clearTurnRefresh()

        userPreferredVideoEnabled = true
        isVideoPausedByProximity = false
        hasJoinSignalStartedForAttempt = false
        hasJoinAcknowledgedCurrentAttempt = false
        hasInitializedIceSetupForAttempt = false
        lastTurnTokenForAttempt = nil
        reconnectToken = nil
        turnTokenTTLMs = nil
    }

    private func applyLocalVideoPreference() {
        let shouldPauseForProximity = callAudioSessionController.shouldPauseVideoForProximity(
            isScreenSharing: uiState.isScreenSharing
        )

        if shouldPauseForProximity != isVideoPausedByProximity {
            isVideoPausedByProximity = shouldPauseForProximity
        }

        let preferredEnabled = userPreferredVideoEnabled && !shouldPauseForProximity
        let effectiveEnabled = webRtcEngine.toggleVideo(preferredEnabled)
        if uiState.localVideoEnabled != effectiveEnabled {
            updateState { $0.localVideoEnabled = effectiveEnabled }
        }
    }

    private func prepareMediaAndConnect(
        roomId: String,
        joinAttempt: Int64,
        defaultAudioEnabled: Bool,
        defaultVideoEnabled: Bool,
        permissions: MediaPermissions
    ) async {
        debugTrace("prepareMediaAndConnect begin rid=\(roomId) attempt=\(joinAttempt)")
        guard joinAttempt == joinAttemptSerial else { return }
        guard currentRoomId == roomId else { return }
        guard uiState.phase == .joining || uiState.phase == .creatingRoom else { return }

        let hasMicPermission = permissions.microphoneGranted
        let hasCameraPermission = permissions.cameraGranted
        let shouldEnableAudio = defaultAudioEnabled && hasMicPermission
        let shouldEnableVideo = defaultVideoEnabled && hasCameraPermission

        updateState {
            $0.localAudioEnabled = shouldEnableAudio
            $0.localVideoEnabled = shouldEnableVideo
        }
        debugTrace("prepareMediaAndConnect resolved rid=\(roomId) audio=\(shouldEnableAudio) video=\(shouldEnableVideo)")

        activateAudioSession()
        webRtcEngine.startLocalMedia(preferVideo: shouldEnableVideo)

        if !shouldEnableAudio {
            webRtcEngine.toggleAudio(false)
        }

        userPreferredVideoEnabled = shouldEnableVideo
        applyLocalVideoPreference()

        startRemoteVideoStatePolling()

        clearJoinConnectKickstart()
        prepareJoinSnapshotAndConnect(roomId: roomId, joinAttempt: joinAttempt)
    }

    private func prepareJoinSnapshotAndConnect(roomId: String, joinAttempt: Int64) {
        joinSnapshotFeature.prepareSnapshotId(
            host: currentSignalingHost(),
            roomId: roomId,
            isVideoEnabled: { [weak self] in
                self?.uiState.localVideoEnabled ?? false
            },
            isJoinAttemptActive: { [weak self] in
                self?.isJoinAttemptActive(roomId: roomId, joinAttempt: joinAttempt) ?? false
            },
            onReady: { [weak self] snapshotId in
                guard let self else { return }
                guard self.isJoinAttemptActive(roomId: roomId, joinAttempt: joinAttempt) else { return }
                self.debugTrace("joinSnapshot ready rid=\(roomId) hasSnapshot=\(snapshotId == nil ? "no" : "yes")")
                self.pendingJoinSnapshotId = snapshotId
                self.ensureSignalingConnection()
            }
        )
    }

    private func isJoinAttemptActive(roomId: String, joinAttempt: Int64) -> Bool {
        joinAttemptSerial == joinAttempt &&
            currentRoomId == roomId &&
            uiState.phase == .joining
    }

    private func resolveMediaPermissions() async -> MediaPermissions {
        debugTrace("resolveMediaPermissions begin")
        async let cameraGranted = requestCameraPermission()
        async let microphoneGranted = requestMicrophonePermission()
        let permissions = await MediaPermissions(
            cameraGranted: cameraGranted,
            microphoneGranted: microphoneGranted
        )
        debugTrace("resolveMediaPermissions done cam=\(permissions.cameraGranted) mic=\(permissions.microphoneGranted)")
        return permissions
    }

    private func requestCameraPermission() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return true
        case .notDetermined:
            return await requestPermissionWithTimeout(
                kind: "camera",
                fallback: false
            ) { completion in
                AVCaptureDevice.requestAccess(for: .video, completionHandler: completion)
            }
        case .denied, .restricted:
            return false
        @unknown default:
            return false
        }
    }

    private func requestMicrophonePermission() async -> Bool {
        let audioSession = AVAudioSession.sharedInstance()

        switch audioSession.recordPermission {
        case .granted:
            return true
        case .undetermined:
            return await requestPermissionWithTimeout(
                kind: "microphone",
                fallback: false
            ) { completion in
                audioSession.requestRecordPermission(completion)
            }
        case .denied:
            return false
        @unknown default:
            return false
        }
    }

    private func requestPermissionWithTimeout(
        kind: String,
        fallback: Bool,
        request: @escaping (@escaping (Bool) -> Void) -> Void
    ) async -> Bool {
        await withCheckedContinuation { continuation in
            let lock = NSLock()
            var resolved = false

            func resolve(_ value: Bool, timedOut: Bool) {
                lock.lock()
                let shouldResume = !resolved
                if shouldResume {
                    resolved = true
                }
                lock.unlock()

                guard shouldResume else { return }
                if timedOut {
                    Task { @MainActor [weak self] in
                        self?.debugTrace("permissionTimeout kind=\(kind) fallback=\(fallback)")
                    }
                }
                continuation.resume(returning: value)
            }

            request { granted in
                resolve(granted, timedOut: false)
            }

            Task {
                try? await Task.sleep(nanoseconds: permissionRequestTimeoutNs)
                resolve(fallback, timedOut: true)
            }
        }
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
            guard self.currentRoomId == roomId else { return }
            guard self.uiState.isSignalingConnected else { return }
            guard self.hasJoinAcknowledgedCurrentAttempt else {
                self.debugTrace("joinRecovery skipped rid=\(roomId) reason=no-ack")
                if self.uiState.phase == .joining {
                    self.pendingJoinRoom = roomId
                    self.ensureSignalingConnection()
                }
                return
            }

            let occupancyHint = self.roomStatuses[roomId]
            self.recoverFromJoiningIfNeeded(participantHint: occupancyHint)
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
            currentPhase: uiState.phase,
            participantHint: participantHint ?? uiState.participantCount,
            preferInCall: preferInCall
        ) else { return }

        clearJoinTimeout()
        debugTrace(
            "recoverFromJoining phase=\(uiState.phase.rawValue) -> \(recovered.phase.rawValue) participants=\(recovered.participantCount)"
        )
        updateState {
            $0.phase = recovered.phase
            $0.participantCount = recovered.participantCount
            $0.statusMessage = recovered.phase == .inCall
                ? L10n.callStatusInCall
                : L10n.callStatusWaitingForJoin
        }
    }

    private func scheduleReconnect() {
        let roomId = currentRoomId
        if roomId == nil && watchedRoomIds.isEmpty { return }

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

            if let roomId, self.currentRoomId == roomId {
                self.pendingJoinRoom = roomId
                self.signalingClient.connect(host: self.currentSignalingHost())
                return
            }

            if roomId == nil && self.currentRoomId == nil && !self.watchedRoomIds.isEmpty {
                self.signalingClient.connect(host: self.currentSignalingHost())
            }
        }
    }

    private func refreshRecentCalls() {
        let calls = recentCallStore.getRecentCalls()
        recentCalls = calls
        refreshWatchedRooms()
    }

    private func refreshSavedRooms() {
        let rooms = savedRoomStore.getSavedRooms()
        savedRooms = rooms
        syncSavedRoomPushSubscriptions(rooms)
        refreshWatchedRooms()
    }

    private func syncSavedRoomPushSubscriptions(_ rooms: [SavedRoom]) {
        let host = serverHost
        for room in rooms where shouldWatchSavedRoom(room) {
            pushSubscriptionManager.subscribeRoom(roomId: room.roomId, host: host)
        }
    }

    private func refreshWatchedRooms() {
        var merged = [String]()
        var seen = Set<String>()

        for room in savedRooms where shouldWatchSavedRoom(room) {
            if seen.insert(room.roomId).inserted {
                merged.append(room.roomId)
            }
        }

        for call in recentCalls {
            if seen.insert(call.roomId).inserted {
                merged.append(call.roomId)
            }
        }

        watchedRoomIds = merged
        let watchedSet = Set(watchedRoomIds)
        roomStatuses = roomStatuses.filter { watchedSet.contains($0.key) }

        watchRoomsIfNeeded()
    }

    private func shouldWatchSavedRoom(_ room: SavedRoom) -> Bool {
        guard let host = room.host else { return true }
        return host.compare(serverHost, options: .caseInsensitive) == .orderedSame
    }

    private func watchRoomsIfNeeded() {
        if watchedRoomIds.isEmpty {
            if currentRoomId == nil && signalingClient.isConnected() {
                signalingClient.close()
            }
            return
        }

        if signalingClient.isConnected() {
            sendWatchRoomsIfNeeded()
        } else {
            signalingClient.connect(host: currentSignalingHost())
        }
    }

    private func saveCurrentCallToHistoryIfNeeded() {
        guard let roomId = currentRoomId, let startTime = callStartTimeMs else { return }

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let duration = max(0, Int((nowMs - startTime) / 1000))

        recentCallStore.saveCall(
            RecentCall(roomId: roomId, startTime: startTime, durationSeconds: duration)
        )

        callStartTimeMs = nil
        refreshRecentCalls()
    }

    private func currentSignalingHost() -> String {
        if currentRoomId != nil {
            return activeCallHostOverride ?? serverHost
        }
        return serverHost
    }

    private func buildSavedRoomInviteLink(host: String, roomId: String, roomName: String) -> String {
        let normalizedHost = DeepLinkParser.normalizeHostValue(host) ?? host
        let appLinkHost = normalizedHost == AppConstants.ruHost ? AppConstants.ruHost : AppConstants.defaultHost

        var components = URLComponents()
        components.scheme = "https"
        components.host = appLinkHost
        components.path = "/call/\(roomId)"
        components.queryItems = [
            URLQueryItem(name: "host", value: normalizedHost),
            URLQueryItem(name: "name", value: roomName)
        ]
        return components.url?.absoluteString ?? "https://\(appLinkHost)/call/\(roomId)"
    }

    private func isHost() -> Bool {
        clientId != nil && clientId == hostCid
    }

    private func shouldReconnectSignaling() -> Bool {
        currentRoomId != nil || !watchedRoomIds.isEmpty
    }

    private func updateState(_ mutate: (inout CallUiState) -> Void) {
        var next = uiState
        mutate(&next)
        setUiState(next)
    }

    private func setUiState(_ state: CallUiState) {
        #if DEBUG
        if uiState.phase != state.phase ||
            uiState.roomId != state.roomId ||
            uiState.participantCount != state.participantCount ||
            uiState.statusMessage != state.statusMessage {
            debugTrace(
                "setUiState phase=\(uiState.phase.rawValue)->\(state.phase.rawValue) " +
                    "room=\(state.roomId ?? "-") participants=\(state.participantCount) status=\(state.statusMessage ?? "-")"
            )
        }
        #endif
        uiState = state
        syncIdleTimerPolicy(for: state.phase)
    }

    private func syncIdleTimerPolicy(for phase: CallPhase) {
        switch phase {
        case .creatingRoom, .joining, .waiting, .inCall:
            UIApplication.shared.isIdleTimerDisabled = true
        default:
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private static func buildWebRtcEngine(isHdVideoExperimentalEnabled: Bool, eventSink: CallManager) -> WebRtcEngine {
        WebRtcEngine(
            onLocalIceCandidate: { [weak eventSink] candidate in
                Task { @MainActor in
                    guard let eventSink else { return }
                    let payload: JSONValue = .object([
                        "candidate": .object([
                            "candidate": .string(candidate.candidate),
                            "sdpMid": candidate.sdpMid.map(JSONValue.string) ?? .null,
                            "sdpMLineIndex": .number(Double(candidate.sdpMLineIndex))
                        ])
                    ])
                    eventSink.sendMessage(type: "ice", payload: payload)
                }
            },
            onConnectionState: { [weak eventSink] state in
                Task { @MainActor in
                    guard let eventSink else { return }
                    eventSink.updateState {
                        $0.connectionState = state
                        switch state {
                        case "CONNECTED":
                            $0.statusMessage = L10n.callStatusConnected
                        case "CONNECTING":
                            $0.statusMessage = L10n.callStatusConnecting
                        case "DISCONNECTED":
                            $0.statusMessage = L10n.callStatusDisconnected
                        case "FAILED":
                            $0.statusMessage = L10n.callStatusConnectionFailed
                        case "CLOSED":
                            $0.statusMessage = L10n.callStatusCallEnded
                        default:
                            break
                        }
                    }

                    switch state {
                    case "CONNECTED":
                        eventSink.recoverFromJoiningIfNeeded(participantHint: nil, preferInCall: true)
                        eventSink.clearIceRestartTimer()
                        eventSink.pendingIceRestart = false
                    case "DISCONNECTED":
                        eventSink.scheduleIceRestart(reason: "conn-disconnected", delayMs: 2000)
                    case "FAILED":
                        eventSink.scheduleIceRestart(reason: "conn-failed", delayMs: 0)
                    default:
                        break
                    }
                }
            },
            onIceConnectionState: { [weak eventSink] state in
                Task { @MainActor in
                    guard let eventSink else { return }
                    eventSink.updateState { $0.iceConnectionState = state }

                    switch state {
                    case "DISCONNECTED":
                        eventSink.scheduleIceRestart(reason: "ice-disconnected", delayMs: 2000)
                    case "FAILED":
                        eventSink.scheduleIceRestart(reason: "ice-failed", delayMs: 0)
                    case "CONNECTED", "COMPLETED":
                        eventSink.clearIceRestartTimer()
                        eventSink.pendingIceRestart = false
                    default:
                        break
                    }
                }
            },
            onSignalingState: { [weak eventSink] state in
                Task { @MainActor in
                    guard let eventSink else { return }
                    if state == "STABLE" {
                        eventSink.clearOfferTimeout()
                        if eventSink.pendingIceRestart {
                            eventSink.pendingIceRestart = false
                            eventSink.triggerIceRestart(reason: "pending-retry")
                        }
                    }
                    eventSink.updateState { $0.signalingState = state }
                }
            },
            onRenegotiationNeededCallback: { [weak eventSink] in
                Task { @MainActor in
                    eventSink?.maybeSendOffer(force: true, iceRestart: false)
                }
            },
            onRemoteVideoTrack: { [weak eventSink] _ in
                Task { @MainActor in
                    eventSink?.refreshRemoteVideoEnabled()
                }
            },
            onCameraFacingChanged: { [weak eventSink] isFront in
                Task { @MainActor in
                    eventSink?.updateState { $0.isFrontCamera = isFront }
                }
            },
            onCameraModeChanged: { [weak eventSink] mode in
                Task { @MainActor in
                    eventSink?.updateState { $0.localCameraMode = mode }
                }
            },
            onFlashlightStateChanged: { [weak eventSink] available, enabled in
                Task { @MainActor in
                    eventSink?.updateState {
                        $0.isFlashAvailable = available
                        $0.isFlashEnabled = enabled
                    }
                }
            },
            onScreenShareStopped: { [weak eventSink] in
                Task { @MainActor in
                    eventSink?.updateState {
                        $0.isScreenSharing = false
                        $0.cameraZoomFactor = 1
                    }
                    eventSink?.applyLocalVideoPreference()
                }
            },
            onZoomFactorChanged: { [weak eventSink] zoomFactor in
                Task { @MainActor in
                    eventSink?.updateState { $0.cameraZoomFactor = zoomFactor }
                }
            },
            isHdVideoExperimentalEnabled: isHdVideoExperimentalEnabled
        )
    }

    private func recreateWebRtcEngineForNewCall() {
        webRtcEngine.release()
        webRtcEngine = Self.buildWebRtcEngine(
            isHdVideoExperimentalEnabled: settingsStore.isHdVideoExperimentalEnabled,
            eventSink: self
        )
    }

    private func startNetworkMonitoring() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            Task { @MainActor in
                if path.status == .satisfied && self.uiState.phase == .inCall {
                    self.scheduleIceRestart(reason: "network-online", delayMs: 0)
                }
            }
        }
        pathMonitor.start(queue: pathMonitorQueue)
    }
}

extension CallManager: SignalingClientListener {
    func onOpen(activeTransport: String) {
        reconnectAttempts = 0
        debugTrace("signaling open transport=\(activeTransport)")

        updateState {
            $0.isSignalingConnected = true
            $0.activeTransport = activeTransport
            $0.isReconnecting = false
        }

        if let join = pendingJoinRoom {
            pendingJoinRoom = nil
            sendJoin(roomId: join, snapshotId: pendingJoinSnapshotId)
        }

        sendWatchRoomsIfNeeded()

        if pendingIceRestart {
            triggerIceRestart(reason: "signaling-reconnect")
        }
    }

    func onMessage(_ message: SignalingMessage) {
        if message.type != "ice" {
            debugTrace("signaling message type=\(message.type) rid=\(message.rid ?? "-")")
        }
        handleSignalingMessage(message)
    }

    func onClosed(reason: String) {
        _ = reason
        debugTrace("signaling closed reason=\(reason)")
        updateState {
            $0.isSignalingConnected = false
            $0.activeTransport = nil
            $0.isReconnecting = shouldReconnectSignaling()
        }

        if shouldReconnectSignaling() {
            scheduleReconnect()
        }
    }
}

#if DEBUG
private extension CallManager {
    func debugTraceReset(_ message: String) {
        debugTraceDefaults.set([], forKey: DebugTrace.key)
        debugTrace(message)
    }

    func debugTrace(_ message: String) {
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        let entry = "\(timestamp) \(message)"
        var entries = debugTraceDefaults.stringArray(forKey: DebugTrace.key) ?? []
        entries.append(entry)
        if entries.count > DebugTrace.maxEntries {
            entries.removeFirst(entries.count - DebugTrace.maxEntries)
        }
        debugTraceDefaults.set(entries, forKey: DebugTrace.key)
    }
}
#endif
