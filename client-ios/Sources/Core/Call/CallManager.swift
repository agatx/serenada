import AVFoundation
import Foundation
import Network
import os.log
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
    private static let log = OSLog(subsystem: "app.serenada.ios", category: "CallManager")
    private let permissionRequestTimeoutNs: UInt64 = 2_000_000_000
    private let connectionStatusRetryingDelayNs: UInt64 = 10_000_000_000

    #if DEBUG
    private enum DebugTrace {
        static let key = "debug_join_trace"
        static let maxEntries = 400
    }
    #endif

    private static let iceConnectionPriority: [String: Int] = [
        "FAILED": 0,
        "DISCONNECTED": 1,
        "CHECKING": 2,
        "NEW": 3,
        "CONNECTED": 4,
        "COMPLETED": 5,
        "CLOSED": 6,
        "COUNT": 7,
        "UNKNOWN": 8,
    ]

    private static let connectionPriority: [String: Int] = [
        "FAILED": 0,
        "DISCONNECTED": 1,
        "CONNECTING": 2,
        "NEW": 3,
        "CONNECTED": 4,
        "CLOSED": 5,
        "UNKNOWN": 6,
    ]

    private static let signalingPriority: [String: Int] = [
        "HAVE_LOCAL_OFFER": 0,
        "HAVE_REMOTE_OFFER": 1,
        "HAVE_LOCAL_PRANSWER": 2,
        "HAVE_REMOTE_PRANSWER": 3,
        "STABLE": 4,
        "CLOSED": 5,
        "UNKNOWN": 6,
    ]

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
    @Published private(set) var roomStatuses: [String: RoomStatus] = [:]

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
    private var currentRoomState: RoomState?
    private var callStartTimeMs: Int64?
    private var peerSlots: [String: PeerConnectionSlot] = [:]

    private var watchedRoomIds: [String] = []
    private var pendingJoinRoom: String?
    private var hasNotifiedPushForJoin = false

    private var joinAttemptSerial: Int64 = 0
    private var reconnectAttempts = 0

    private var reconnectTask: Task<Void, Never>?
    private var joinTimeoutTask: Task<Void, Never>?
    private var joinConnectKickstartTask: Task<Void, Never>?
    private var joinRecoveryTask: Task<Void, Never>?
    private var connectionStatusRetryingTask: Task<Void, Never>?
    private var turnRefreshTask: Task<Void, Never>?
    private var remoteVideoPollTimer: Timer?
    private var pushEndpointObserver: NSObjectProtocol?

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
        self.pushEndpointObserver = NotificationCenter.default.addObserver(
            forName: .serenadaPushEndpointDidChange,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            let endpoint = notification.userInfo?[PushEndpointNotification.endpointUserInfoKey] as? String
            Task { @MainActor [weak self] in
                self?.syncPushSubscriptionsAfterEndpointChange(endpoint)
            }
        }

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
        connectionStatusRetryingTask?.cancel()
        turnRefreshTask?.cancel()
        remoteVideoPollTimer?.invalidate()
        if let pushEndpointObserver {
            NotificationCenter.default.removeObserver(pushEndpointObserver)
        }
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
                host: target.host
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
                    host: target.host
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
        if activeCallHostOverride != nil && signalingClient.isConnected() {
            signalingClient.close()
        }
        currentRoomId = trimmed
        debugTraceReset("joinRoom rid=\(trimmed) host=\(currentSignalingHost()) oneOff=\(activeCallHostOverride ?? "-")")
        joinAttemptSerial += 1
        callStartTimeMs = Int64(Date().timeIntervalSince1970 * 1000)

        pendingMessages.removeAll()
        peerSlots.removeAll()
        currentRoomState = nil
        hasNotifiedPushForJoin = false
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
            $0.remoteParticipants = []
            $0.localCameraMode = .selfie
            $0.cameraZoomFactor = 1
            $0.connectionStatus = .connected
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

        let existingHost = savedRooms.first(where: { $0.roomId == normalizedRoomId })?.host
        let recentHost = recentCalls.first(where: { $0.roomId == normalizedRoomId })?.host
        let resolvedHost = DeepLinkParser.normalizeHostValue(host)
            ?? existingHost
            ?? recentHost
            ?? serverHost

        let room = SavedRoom(
            roomId: normalizedRoomId,
            name: normalizedName,
            createdAt: Int64(Date().timeIntervalSince1970 * 1000),
            host: resolvedHost,
            lastJoinedAt: nil
        )
        savedRoomStore.saveRoom(room)
        refreshSavedRooms()
    }

    func joinSavedRoom(_ room: SavedRoom) {
        joinRoom(room.roomId, oneOffHost: hostOverrideOrNull(room.host))
    }

    func joinRecentCall(_ call: RecentCall) {
        joinRoom(call.roomId, oneOffHost: hostOverrideOrNull(call.host))
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
            saveRoom(roomId: roomId, name: normalizedName, host: normalizedHost)
            return .success(buildSavedRoomInviteLink(host: normalizedHost, roomId: roomId, roomName: normalizedName))
        } catch {
            return .failure(error)
        }
    }

    func endCall() {
        guard uiState.phase != .idle else { return }
        debugTrace("ui endCall phase=\(uiState.phase.rawValue) room=\(currentRoomId ?? "-")")
        leaveCall()
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
            // If currently in content mode (world/composite), broadcast deactivation
            // before the flip. The onCameraModeChanged callback will broadcast
            // activation if the new mode is also a content mode.
            if uiState.localCameraMode.isContentMode {
                broadcastContentState(active: false)
            }
            webRtcEngine.flipCamera()
        }
    }

    func toggleScreenShare() {
        if uiState.isScreenSharing {
            debugTrace("ui stopScreenShare")
            os_log("toggleScreenShare: stopping screen share", log: Self.log, type: .info)
            _ = webRtcEngine.stopScreenShare()
            return
        }

        debugTrace("ui startScreenShare")
        os_log("toggleScreenShare: starting screen share", log: Self.log, type: .info)
        _ = webRtcEngine.startScreenShare { [weak self] started in
            Task { @MainActor in
                os_log("toggleScreenShare: onComplete started=%{public}d", log: CallManager.log, type: .info, started)
                guard let self else { return }
                guard started else { return }
                os_log("toggleScreenShare: updating state — isScreenSharing=true, localCameraMode=screenShare", log: CallManager.log, type: .info)
                self.updateState {
                    $0.isScreenSharing = true
                    $0.localCameraMode = .screenShare
                    $0.cameraZoomFactor = 1
                }
                self.broadcastContentState(active: true, contentType: ContentTypeWire.screenShare)
                self.applyLocalVideoPreference()
            }
        }
    }

    func adjustCameraZoom(scaleDelta: CGFloat) {
        guard uiState.phase == .inCall else { return }
        guard uiState.localCameraMode.isContentMode else { return }
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
        let remoteCid = currentRoomState?
            .participants
            .first(where: { $0.cid != clientId })?
            .cid ?? peerSlots.keys.first
        guard let remoteCid else { return }
        attachRemoteRenderer(renderer, forCid: remoteCid)
    }

    func detachRemoteRenderer(_ renderer: AnyObject) {
        peerSlots.values.forEach { $0.detachRemoteRenderer(renderer) }
    }

    func attachRemoteRenderer(_ renderer: AnyObject, forCid cid: String) {
        peerSlots[cid]?.attachRemoteRenderer(renderer)
    }

    func detachRemoteRenderer(_ renderer: AnyObject, forCid cid: String) {
        peerSlots[cid]?.detachRemoteRenderer(renderer)
    }

    private func ensureSignalingConnection() {
        hasJoinSignalStartedForAttempt = true
        let roomToJoin = currentRoomId
        debugTrace("ensureSignalingConnection connected=\(signalingClient.isConnected()) room=\(roomToJoin ?? "-") pending=\(pendingJoinRoom ?? "-")")
        if signalingClient.isConnected() {
            if let roomToJoin {
                pendingJoinRoom = nil
                sendJoin(roomId: roomToJoin)
            }
            sendWatchRoomsIfNeeded()
            return
        }

        pendingJoinRoom = roomToJoin
        signalingClient.connect(host: currentSignalingHost())
    }

    private func sendJoin(roomId: String) {
        debugTrace("sendJoin begin rid=\(roomId) connected=\(signalingClient.isConnected())")
        guard signalingClient.isConnected() else {
            debugTrace("sendJoin deferred rid=\(roomId) reason=disconnected")
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

        let reconnectCid = clientId ?? settingsStore.reconnectCid
        if let reconnectCid {
            payload["reconnectCid"] = .string(reconnectCid)
        }
        if let reconnectToken {
            payload["reconnectToken"] = .string(reconnectToken)
        }

        let message = SignalingMessage(
            type: "join",
            rid: roomId,
            payload: .object(payload)
        )

        signalingClient.send(message)
        debugTrace(
            "sendJoin sent rid=\(roomId) reconnectCid=\(clientId ?? settingsStore.reconnectCid ?? "-")"
        )
        scheduleJoinRecovery(for: roomId)
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
        case "content_state":
            handleContentState(message)
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
        uiState.localCid = clientId

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
        // Fire async post-join push notification (fresh joins only)
        if !hasNotifiedPushForJoin {
            hasNotifiedPushForJoin = true
            let notifyRoomId = message.rid ?? currentRoomId ?? ""
            let notifyCid = clientId ?? ""
            let notifyHost = currentSignalingHost()
            let notifyJoinAttempt = joinAttemptSerial
            if notifyRoomId.isEmpty || notifyCid.isEmpty {
                debugTrace("handleJoined pushNotify skipped: missing roomId or cid")
            } else {
                joinSnapshotFeature.prepareSnapshotId(
                    host: notifyHost,
                    roomId: notifyRoomId,
                    isVideoEnabled: { [weak self] in
                        self?.uiState.localVideoEnabled ?? false
                    },
                    isJoinAttemptActive: { [weak self] in
                        self?.joinAttemptSerial == notifyJoinAttempt && self?.currentRoomId == notifyRoomId
                    },
                    onReady: { [weak self] snapshotId in
                        guard let self else { return }
                        let endpoint = self.pushSubscriptionManager.cachedEndpoint()
                        Task {
                            do {
                                try await self.apiClient.notifyRoom(
                                    host: notifyHost,
                                    roomId: notifyRoomId,
                                    cid: notifyCid,
                                    snapshotId: snapshotId,
                                    pushEndpoint: endpoint
                                )
                            } catch {
                                self.debugTrace("Post-join push notify failed: \(error)")
                            }
                        }
                    }
                )
            }
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
        let code = message.payload?.objectValue?["code"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        let rawMessage = message.payload?.objectValue?["message"]?.stringValue
        let resolvedMessage: String?
        switch code {
        case "ROOM_CAPACITY_UNSUPPORTED":
            resolvedMessage = rawMessage?.isEmpty == false ? rawMessage : L10n.errorRoomCapacityUnsupported
        default:
            resolvedMessage = rawMessage
        }
        debugTrace("rx error rid=\(message.rid ?? "-") message=\(rawMessage ?? "-")")
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
        resetResources()
        setUiState(CallUiState(
            phase: .error,
            errorMessage: resolvedMessage?.isEmpty == false ? resolvedMessage : L10n.errorUnknown
        ))
    }

    private func handleContentState(_ message: SignalingMessage) {
        guard let fromCid = message.payload?.objectValue?["from"]?.stringValue,
              !fromCid.isEmpty else { return }
        let active = message.payload?.objectValue?["active"]?.boolValue == true
        let contentType = active ? message.payload?.objectValue?["contentType"]?.stringValue : nil
        updateState {
            $0.remoteContentCid = active ? fromCid : nil
            $0.remoteContentType = contentType
        }
    }

    private func broadcastContentState(active: Bool, contentType: String? = nil) {
        var payload: [String: JSONValue] = ["active": .bool(active)]
        if active, let contentType { payload["contentType"] = .string(contentType) }
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
            debugTrace("handleSignalingPayload queued type=\(message.type) reason=ice-not-ready")
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
                        self.peerSlots[cid]?.pendingIceRestart = false
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
                        self.peerSlots[cid]?.pendingIceRestart = false
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
                            self.peerSlots[cid]?.pendingIceRestart = false
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

    private func updateAggregatePeerState() {
        var bestIcePri = Int.max, nextIceState = "NEW"
        var bestConnPri = Int.max, nextConnectionState = "NEW"
        var bestSigPri = Int.max, nextSignalingState = "STABLE"
        for slot in peerSlots.values {
            let icePri = Self.iceConnectionPriority[slot.getIceConnectionState()] ?? .max
            if icePri < bestIcePri { bestIcePri = icePri; nextIceState = slot.getIceConnectionState() }
            let connPri = Self.connectionPriority[slot.getConnectionState()] ?? .max
            if connPri < bestConnPri { bestConnPri = connPri; nextConnectionState = slot.getConnectionState() }
            let sigPri = Self.signalingPriority[slot.getSignalingState()] ?? .max
            if sigPri < bestSigPri { bestSigPri = sigPri; nextSignalingState = slot.getSignalingState() }
        }

        if uiState.iceConnectionState == nextIceState &&
            uiState.connectionState == nextConnectionState &&
            uiState.signalingState == nextSignalingState {
            return
        }

        updateState {
            $0.iceConnectionState = nextIceState
            $0.connectionState = nextConnectionState
            $0.signalingState = nextSignalingState
        }
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
                    self.peerSlots[fromCid]?.pendingIceRestart = false
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

        updateState {
            $0.phase = phase
            $0.isHost = isHostNow
            $0.participantCount = count
            $0.statusMessage = count <= 1 ? L10n.callStatusWaitingForJoin : L10n.callStatusInCall
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

    private func maybeSendOffer(force: Bool = false, iceRestart: Bool = false) {
        for slot in peerSlots.values where shouldIOffer(remoteCid: slot.remoteCid) {
            maybeSendOffer(slot: slot, force: force, iceRestart: iceRestart)
        }
    }

    private func maybeSendOffer(slot: PeerConnectionSlot, force: Bool = false, iceRestart: Bool = false) {
        if slot.isMakingOffer {
            if iceRestart {
                slot.pendingIceRestart = true
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
                slot.pendingIceRestart = true
            }
            return
        }

        slot.isMakingOffer = true
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
                    slot.isMakingOffer = false
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
            slot.isMakingOffer = false
            if iceRestart {
                slot.pendingIceRestart = true
            }
            return
        }

        if !force {
            slot.sentOffer = true
        }
    }

    private func canOffer(slot: PeerConnectionSlot) -> Bool {
        guard uiState.participantCount > 1 else { return false }
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

        slot.offerTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.offerTimeoutNs)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, let slot = self.peerSlots[remoteCid] else { return }
                guard slot.getSignalingState() == "HAVE_LOCAL_OFFER" else { return }
                if triggerIceRestart {
                    slot.pendingIceRestart = true
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
        }
    }

    private func clearOfferTimeout(remoteCid: String? = nil) {
        if let remoteCid {
            peerSlots[remoteCid]?.offerTimeoutTask?.cancel()
            peerSlots[remoteCid]?.offerTimeoutTask = nil
            return
        }

        for slot in peerSlots.values {
            slot.offerTimeoutTask?.cancel()
            slot.offerTimeoutTask = nil
        }
    }

    private func maybeScheduleNonHostOfferFallback(reason: String) {
        for slot in peerSlots.values where !shouldIOffer(remoteCid: slot.remoteCid) {
            maybeScheduleNonHostOfferFallback(remoteCid: slot.remoteCid, reason: reason)
        }
    }

    private func maybeScheduleNonHostOfferFallback(remoteCid: String, reason: String) {
        guard let roomId = currentRoomId, let slot = peerSlots[remoteCid] else { return }
        guard uiState.participantCount > 1 else {
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

        debugTrace("nonHostOfferFallback scheduled rid=\(roomId) remote=\(remoteCid) reason=\(reason)")
        slot.nonHostFallbackTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: WebRtcResilience.nonHostFallbackDelayNs)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, let slot = self.peerSlots[remoteCid] else { return }
                slot.nonHostFallbackTask = nil
                guard self.currentRoomId == roomId else { return }
                slot.nonHostFallbackAttempts += 1
                self.maybeSendNonHostFallbackOffer(remoteCid: remoteCid)
            }
        }
    }

    private func clearNonHostOfferFallback(remoteCid: String? = nil) {
        if let remoteCid {
            peerSlots[remoteCid]?.nonHostFallbackTask?.cancel()
            peerSlots[remoteCid]?.nonHostFallbackTask = nil
            return
        }

        for slot in peerSlots.values {
            slot.nonHostFallbackTask?.cancel()
            slot.nonHostFallbackTask = nil
        }
    }

    private func maybeSendNonHostFallbackOffer(remoteCid: String) {
        guard let slot = peerSlots[remoteCid] else { return }
        guard uiState.participantCount > 1 else { return }
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

        slot.isMakingOffer = true
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
                    slot.isMakingOffer = false
                    if !success {
                        self.maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "offer-failed")
                    }
                }
            }
        )

        if !started {
            slot.isMakingOffer = false
            maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "offer-not-started")
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
        for slot in peerSlots.values where shouldIOffer(remoteCid: slot.remoteCid) {
            scheduleIceRestart(remoteCid: slot.remoteCid, reason: reason, delayMs: delayMs)
        }
    }

    private func scheduleIceRestart(remoteCid: String, reason: String, delayMs: Int) {
        guard let slot = peerSlots[remoteCid] else { return }
        if !canOffer(slot: slot) {
            slot.pendingIceRestart = true
            return
        }

        guard slot.iceRestartTask == nil else { return }

        let now = Date().timeIntervalSince1970 * 1000
        guard now - slot.lastIceRestartAt >= Double(WebRtcResilience.iceRestartCooldownMs) else { return }

        slot.iceRestartTask = Task { [weak self] in
            if delayMs > 0 {
                try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
            }
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self?.triggerIceRestart(remoteCid: remoteCid, reason: reason)
            }
        }
    }

    private func clearIceRestartTimer(remoteCid: String? = nil) {
        if let remoteCid {
            peerSlots[remoteCid]?.iceRestartTask?.cancel()
            peerSlots[remoteCid]?.iceRestartTask = nil
            return
        }

        for slot in peerSlots.values {
            slot.iceRestartTask?.cancel()
            slot.iceRestartTask = nil
        }
    }

    private func triggerIceRestart(reason: String) {
        for slot in peerSlots.values where shouldIOffer(remoteCid: slot.remoteCid) {
            triggerIceRestart(remoteCid: slot.remoteCid, reason: reason)
        }
    }

    private func triggerIceRestart(remoteCid: String, reason: String) {
        guard let slot = peerSlots[remoteCid] else { return }
        slot.iceRestartTask?.cancel()
        slot.iceRestartTask = nil

        guard canOffer(slot: slot) else {
            slot.pendingIceRestart = true
            return
        }

        debugTrace("triggerIceRestart cid=\(remoteCid) reason=\(reason)")
        if slot.isMakingOffer {
            slot.pendingIceRestart = true
            return
        }

        slot.lastIceRestartAt = Date().timeIntervalSince1970 * 1000
        slot.pendingIceRestart = false
        maybeSendOffer(slot: slot, force: true, iceRestart: true)
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
                debugTrace(
                    "parseRoomState hostFallback from=\(currentHostCid) to=\(resolvedHostCid ?? "-") participants=\(participants.count)"
                )
            }
        }

        guard let resolvedHostCid, !resolvedHostCid.isEmpty else { return nil }
        let maxParticipants = payload["maxParticipants"]?.intValue
        return RoomState(hostCid: resolvedHostCid, participants: participants, maxParticipants: maxParticipants)
    }

    private func refreshRemoteParticipants() {
        guard let roomState = currentRoomState else {
            if uiState.remoteParticipants.isEmpty {
                return
            }
            updateState {
                $0.remoteParticipants = []
            }
            return
        }

        let participants = roomState.participants
            .filter { $0.cid != clientId }
            .map { participant in
                let slot = peerSlots[participant.cid]
                return RemoteParticipant(
                    cid: participant.cid,
                    videoEnabled: slot?.isRemoteVideoTrackEnabled() ?? false,
                    connectionState: slot?.getConnectionState() ?? "NEW"
                )
            }

        let activeCids = Set(participants.map(\.cid))
        let clearContent = uiState.remoteContentCid != nil && !activeCids.contains(uiState.remoteContentCid!)

        if uiState.remoteParticipants == participants {
            if clearContent {
                updateState {
                    $0.remoteContentCid = nil
                    $0.remoteContentType = nil
                }
            }
            return
        }

        updateState {
            $0.remoteParticipants = participants
            if clearContent {
                $0.remoteContentCid = nil
                $0.remoteContentType = nil
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
        if uiState.phase != .inCall && uiState.phase != .waiting && uiState.phase != .joining {
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
            if uiState.realtimeStats != .empty || uiState.webrtcStatsSummary != "pc=none" {
                updateState {
                    $0.realtimeStats = .empty
                    $0.webrtcStatsSummary = "pc=none"
                }
            }
            return
        }

        let group = DispatchGroup()
        var stats: [RealtimeCallStats] = []
        var summaries: [String] = []
        let lock = NSLock()

        for slot in slots {
            group.enter()
            slot.collectRealtimeCallStatsAndSummary { realtimeStats, summary in
                lock.lock()
                stats.append(realtimeStats)
                summaries.append("\(slot.remoteCid):\(summary)")
                lock.unlock()
                group.leave()
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            self.webrtcStatsRequestInFlight = false
            self.lastWebRtcStatsPollAtMs = Int64(Date().timeIntervalSince1970 * 1000)

            let mergedStats = self.mergeRealtimeStats(stats)
            let mergedSummary = summaries.sorted().joined(separator: " | ")
            if self.uiState.realtimeStats != mergedStats || self.uiState.webrtcStatsSummary != mergedSummary {
                self.updateState {
                    $0.realtimeStats = mergedStats
                    $0.webrtcStatsSummary = mergedSummary
                }
            }
        }
    }

    private func mergeRealtimeStats(_ stats: [RealtimeCallStats]) -> RealtimeCallStats {
        guard !stats.isEmpty else { return .empty }
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

    private func sumNonNil(_ values: [Double]) -> Double? {
        guard !values.isEmpty else { return nil }
        return values.reduce(0, +)
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
            $0.remoteParticipants = []
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
        peerSlots.values.forEach { $0.closePeerConnection() }
        peerSlots.removeAll()
        webRtcEngine.release()
        deactivateAudioSession()

        currentRoomId = nil
        activeCallHostOverride = nil
        hostCid = nil
        currentRoomState = nil
        clientId = nil
        callStartTimeMs = nil

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

        userPreferredVideoEnabled = true
        isVideoPausedByProximity = false
        hasNotifiedPushForJoin = false
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
        ensureSignalingConnection()
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

            let occupancyHint = self.roomStatuses[roomId]?.count
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
        updateConnectionStatusFromSignals()
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
        if calls.contains(where: { $0.host == nil }) {
            let host = serverHost
            let patched = calls.map { call in
                call.host == nil
                    ? RecentCall(roomId: call.roomId, startTime: call.startTime, durationSeconds: call.durationSeconds, host: host)
                    : call
            }
            patched.forEach { recentCallStore.saveCall($0) }
            recentCalls = patched
        } else {
            recentCalls = calls
        }
        refreshWatchedRooms()
    }

    private func refreshSavedRooms() {
        let rooms = savedRoomStore.getSavedRooms()
        if rooms.contains(where: { $0.host == nil }) {
            let host = serverHost
            for room in rooms where room.host == nil {
                savedRoomStore.saveRoom(SavedRoom(roomId: room.roomId, name: room.name, createdAt: room.createdAt, host: host, lastJoinedAt: room.lastJoinedAt))
            }
            savedRooms = savedRoomStore.getSavedRooms()
        } else {
            savedRooms = rooms
        }
        syncSavedRoomPushSubscriptions(savedRooms)
        refreshWatchedRooms()
    }

    private func syncSavedRoomPushSubscriptions(_ rooms: [SavedRoom]) {
        let host = serverHost
        for room in rooms where isCurrentServerHost(room.host) {
            pushSubscriptionManager.subscribeRoom(roomId: room.roomId, host: host)
        }
    }

    private func syncPushSubscriptionsAfterEndpointChange(_ endpoint: String?) {
        let cleanEndpoint = endpoint?.trimmingCharacters(in: .whitespacesAndNewlines)
        pushSubscriptionManager.updateCachedEndpoint(cleanEndpoint?.isEmpty == false ? cleanEndpoint : nil)

        if let roomId = currentRoomId {
            pushSubscriptionManager.subscribeRoom(roomId: roomId, host: currentSignalingHost())
        }
        syncSavedRoomPushSubscriptions(savedRooms)
    }

    private func refreshWatchedRooms() {
        var merged = [String]()
        var seen = Set<String>()

        for room in savedRooms where isCurrentServerHost(room.host) {
            if seen.insert(room.roomId).inserted {
                merged.append(room.roomId)
            }
        }

        for call in recentCalls where isCurrentServerHost(call.host) {
            if seen.insert(call.roomId).inserted {
                merged.append(call.roomId)
            }
        }

        watchedRoomIds = merged
        let watchedSet = Set(watchedRoomIds)
        roomStatuses = roomStatuses.filter { watchedSet.contains($0.key) }

        watchRoomsIfNeeded()
    }

    private func isCurrentServerHost(_ host: String?) -> Bool {
        guard let h = host else { return true }
        return h.compare(serverHost, options: .caseInsensitive) == .orderedSame
    }

    private func hostOverrideOrNull(_ host: String?) -> String? {
        DeepLinkParser.normalizeHostValue(host).flatMap { isCurrentServerHost($0) ? nil : $0 }
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
            RecentCall(roomId: roomId, startTime: startTime, durationSeconds: duration, host: currentSignalingHost())
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

    private func clearConnectionStatusRetryingTimer() {
        connectionStatusRetryingTask?.cancel()
        connectionStatusRetryingTask = nil
    }

    private func setConnectionStatus(_ status: ConnectionStatus) {
        if uiState.connectionStatus == status {
            return
        }
        let previous = uiState.connectionStatus
        updateState { $0.connectionStatus = status }
        if previous != .retrying && status == .retrying {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
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
            guard self.uiState.phase == .inCall else {
                self.resetConnectionStatusMachine()
                return
            }
            guard self.uiState.connectionStatus == .recovering else { return }
            self.connectionStatusRetryingTask = nil
            self.setConnectionStatus(.retrying)
        }
    }

    private func markConnectionDegraded() {
        guard uiState.phase == .inCall else {
            resetConnectionStatusMachine()
            return
        }

        switch uiState.connectionStatus {
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
        guard uiState.phase == .inCall else {
            resetConnectionStatusMachine()
            return
        }

        if isConnectionDegraded(uiState) {
            markConnectionDegraded()
            return
        }

        resetConnectionStatusMachine()
    }

    private func isConnectionDegraded(_ state: CallUiState) -> Bool {
        !state.isSignalingConnected ||
        state.iceConnectionState == "DISCONNECTED" ||
        state.iceConnectionState == "FAILED" ||
        state.connectionState == "DISCONNECTED" ||
        state.connectionState == "FAILED"
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
            onCameraFacingChanged: { [weak eventSink] isFront in
                Task { @MainActor in
                    eventSink?.updateState { $0.isFrontCamera = isFront }
                }
            },
            onCameraModeChanged: { [weak eventSink] mode in
                Task { @MainActor in
                    let previousMode = eventSink?.uiState.localCameraMode
                    eventSink?.updateState { $0.localCameraMode = mode }
                    // Broadcast content state for world/composite camera
                    let isContent = mode.isContentMode
                    let wasContent = previousMode?.isContentMode ?? false
                    if isContent {
                        let type = mode == .world ? ContentTypeWire.worldCamera : ContentTypeWire.compositeCamera
                        eventSink?.broadcastContentState(active: true, contentType: type)
                    } else if wasContent {
                        eventSink?.broadcastContentState(active: false)
                    }
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
                    eventSink?.broadcastContentState(active: false)
                    eventSink?.applyLocalVideoPreference()
                }
            },
            onZoomFactorChanged: { [weak eventSink] zoomFactor in
                Task { @MainActor in
                    eventSink?.updateState { $0.cameraZoomFactor = zoomFactor }
                }
            },
            onDebugTrace: { [weak eventSink] message in
#if DEBUG
                eventSink?.debugTrace(message)
#endif
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
                guard self.uiState.phase == .inCall else { return }

                if self.isConnectionDegraded(self.uiState) {
                    self.markConnectionDegraded()
                }

                if path.status == .satisfied {
                    // Keep opportunistic ICE restart on network transitions so the call can
                    // migrate to a better path without forcing degraded UI when healthy.
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
        }
        updateConnectionStatusFromSignals()

        if let join = pendingJoinRoom {
            pendingJoinRoom = nil
            sendJoin(roomId: join)
        }

        sendWatchRoomsIfNeeded()

        if uiState.phase == .inCall {
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
        }
        updateConnectionStatusFromSignals()

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
