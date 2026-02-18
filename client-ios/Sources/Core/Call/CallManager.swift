import AVFoundation
import Foundation
import Network

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
    @Published private(set) var recentCalls: [RecentCall] = []
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
    private let signalingClient: SignalingClient
    private let pathMonitor = NWPathMonitor()
    private let pathMonitorQueue = DispatchQueue(label: "CallManager.PathMonitor")

    private var callAudioSessionController: CallAudioSessionController!
    private var webRtcEngine: WebRtcEngine!

    private var currentRoomId: String?
    private var clientId: String?
    private var hostCid: String?
    private var callStartTimeMs: Int64?

    private var watchedRoomIds: [String] = []
    private var pendingJoinRoom: String?

    private var joinAttemptSerial: Int64 = 0
    private var reconnectAttempts = 0
    private var sentOffer = false
    private var isMakingOffer = false
    private var pendingIceRestart = false
    private var lastIceRestartAt: TimeInterval = 0

    private var reconnectTask: Task<Void, Never>?
    private var joinRecoveryTask: Task<Void, Never>?
    private var iceRestartTask: Task<Void, Never>?
    private var offerTimeoutTask: Task<Void, Never>?
    private var remoteVideoPollTimer: Timer?

    private var lastWebRtcStatsPollAtMs: Int64 = 0
    private var webrtcStatsRequestInFlight = false
    private var pendingMessages: [SignalingMessage] = []

    private var userPreferredVideoEnabled = true
    private var isVideoPausedByProximity = false

    init(
        apiClient: APIClient = APIClient(),
        settingsStore: SettingsStore = SettingsStore(),
        recentCallStore: RecentCallStore = RecentCallStore(),
        signalingClient: SignalingClient? = nil
    ) {
        self.apiClient = apiClient
        self.settingsStore = settingsStore
        self.recentCallStore = recentCallStore
        self.signalingClient = signalingClient ?? SignalingClient()

        self.serverHost = settingsStore.host
        self.selectedLanguage = settingsStore.language
        self.isDefaultCameraEnabled = settingsStore.isDefaultCameraEnabled
        self.isDefaultMicrophoneEnabled = settingsStore.isDefaultMicrophoneEnabled
        self.isHdVideoExperimentalEnabled = settingsStore.isHdVideoExperimentalEnabled

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

        self.signalingClient.listener = self

        startNetworkMonitoring()
        refreshRecentCalls()
    }

    deinit {
        pathMonitor.cancel()
        reconnectTask?.cancel()
        joinRecoveryTask?.cancel()
        iceRestartTask?.cancel()
        offerTimeoutTask?.cancel()
        remoteVideoPollTimer?.invalidate()
    }

    func updateServerHost(_ host: String) {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = trimmed.isEmpty ? AppConstants.defaultHost : trimmed
        let changed = normalized != serverHost

        settingsStore.host = normalized
        serverHost = normalized

        if changed && currentRoomId == nil && !watchedRoomIds.isEmpty {
            signalingClient.close()
            watchRecentRoomsIfNeeded()
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

    func handleDeepLink(_ url: URL) {
        guard let roomId = DeepLinkParser.extractRoomId(from: url) else { return }

        let isSameActiveRoom =
            (uiState.roomId == roomId || currentRoomId == roomId) &&
            uiState.phase != .idle &&
            uiState.phase != .error &&
            uiState.phase != .ending

        if isSameActiveRoom {
            return
        }

        if let host = url.host, !host.isEmpty {
            updateServerHost(host)
        }

        joinRoom(roomId)
    }

    func joinFromInput(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            uiState.phase = .error
            uiState.errorMessage = L10n.errorEnterRoomOrId
            return
        }

        if let url = URL(string: trimmed), let host = url.host, let roomId = DeepLinkParser.extractRoomId(from: url) {
            updateServerHost(host)
            joinRoom(roomId)
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

    func joinRoom(_ roomId: String) {
        let trimmed = roomId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            updateState {
                $0.phase = .error
                $0.errorMessage = L10n.errorInvalidRoomId
            }
            return
        }

        currentRoomId = trimmed
        joinAttemptSerial += 1
        callStartTimeMs = Int64(Date().timeIntervalSince1970 * 1000)

        sentOffer = false
        pendingMessages.removeAll()

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
            $0.webrtcStatsSummary = ""
            $0.isFlashAvailable = false
            $0.isFlashEnabled = false
        }

        let currentJoinAttempt = joinAttemptSerial
        Task { [weak self] in
            guard let self else { return }
            let permissions = await self.resolveMediaPermissions()
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
            uiState = CallUiState()
            refreshRecentCalls()
        }
    }

    func removeRecentCall(roomId: String) {
        recentCallStore.removeCall(roomId: roomId)
        refreshRecentCalls()
    }

    func endCall() {
        guard uiState.phase != .idle else { return }
        sendMessage(type: "leave")
        cleanupCall(message: L10n.callStatusLeftRoom)
    }

    func toggleAudio() {
        let enabled = !uiState.localAudioEnabled
        webRtcEngine.toggleAudio(enabled)
        updateState { $0.localAudioEnabled = enabled }
    }

    func toggleVideo() {
        userPreferredVideoEnabled = !uiState.localVideoEnabled
        applyLocalVideoPreference()
    }

    @discardableResult
    func toggleFlashlight() -> Bool {
        webRtcEngine.toggleFlashlight()
    }

    func flipCamera() {
        if !uiState.isScreenSharing {
            webRtcEngine.flipCamera()
        }
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
        let roomToJoin = currentRoomId
        if signalingClient.isConnected() {
            if let roomToJoin {
                pendingJoinRoom = nil
                sendJoin(roomId: roomToJoin)
            }
            sendWatchRoomsIfNeeded()
            return
        }

        pendingJoinRoom = roomToJoin
        signalingClient.connect(host: serverHost)
    }

    private func sendJoin(roomId: String) {
        var payload: [String: JSONValue] = [
            "device": .string("ios"),
            "capabilities": .object(["trickleIce": .bool(true)])
        ]

        let reconnectCid = clientId ?? settingsStore.reconnectCid
        if let reconnectCid {
            payload["reconnectCid"] = .string(reconnectCid)
        }

        let message = SignalingMessage(
            type: "join",
            rid: roomId,
            payload: .object(payload)
        )

        signalingClient.send(message)
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

        clearJoinRecovery()

        clientId = message.cid
        settingsStore.reconnectCid = message.cid

        if let roomState = parseRoomState(payload: message.payload) {
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        } else {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint(payload: message.payload))
        }

        if let turnToken = message.payload?.objectValue?["turnToken"]?.stringValue, !turnToken.isEmpty {
            fetchTurnCredentials(token: turnToken)
        } else {
            applyDefaultIceServers()
        }
    }

    private func handleRoomState(_ message: SignalingMessage) {
        clearJoinRecovery()

        guard let roomState = parseRoomState(payload: message.payload) else {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint(payload: message.payload))
            return
        }
        hostCid = roomState.hostCid
        updateParticipants(roomState)
    }

    private func handleRoomEnded() {
        cleanupCall(message: L10n.callStatusRoomEnded)
    }

    private func handleError(_ message: SignalingMessage) {
        let rawMessage = message.payload?.objectValue?["message"]?.stringValue
        clearJoinRecovery()
        resetResources()
        uiState = CallUiState(
            phase: .error,
            errorMessage: rawMessage?.isEmpty == false ? rawMessage : L10n.errorUnknown
        )
    }

    private func handleSignalingPayload(_ message: SignalingMessage) {
        if message.type == "offer" || message.type == "answer" || message.type == "ice" {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint(payload: message.payload), preferInCall: true)
        }

        if !webRtcEngine.isReady() {
            webRtcEngine.ensurePeerConnection()
            if !webRtcEngine.isReady() {
                pendingMessages.append(message)
                return
            }
        }

        processSignalingPayload(message)
    }

    private func processSignalingPayload(_ message: SignalingMessage) {
        switch message.type {
        case "offer":
            guard let sdp = message.payload?.objectValue?["sdp"]?.stringValue, !sdp.isEmpty else { return }
            webRtcEngine.setRemoteDescription(type: .offer, sdp: sdp) { [weak self] in
                guard let self else { return }
                self.webRtcEngine.createAnswer(onSdp: { answerSdp in
                    self.sendMessage(type: "answer", payload: .object(["sdp": .string(answerSdp)]))
                })
            }

        case "answer":
            guard let sdp = message.payload?.objectValue?["sdp"]?.stringValue, !sdp.isEmpty else { return }
            webRtcEngine.setRemoteDescription(type: .answer, sdp: sdp) { [weak self] in
                guard let self else { return }
                self.clearOfferTimeout()
                self.pendingIceRestart = false
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

        if count <= 1 {
            sentOffer = false
            clearOfferTimeout()
            clearIceRestartTimer()
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

        if count > 1 {
            webRtcEngine.ensurePeerConnection()
        }

        if count > 1 && isHostNow {
            maybeSendOffer()
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

    private func scheduleOfferTimeout() {
        clearOfferTimeout()

        offerTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard !Task.isCancelled else { return }
            guard let self else { return }

            if self.webRtcEngine.signalingStateRaw() == "HAVE_LOCAL_OFFER" {
                self.pendingIceRestart = true
                self.webRtcEngine.rollbackLocalDescription { [weak self] _ in
                    Task { @MainActor in
                        self?.scheduleIceRestart(reason: "offer-timeout", delayMs: 0)
                    }
                }
            }
        }
    }

    private func clearOfferTimeout() {
        offerTimeoutTask?.cancel()
        offerTimeoutTask = nil
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
        if now - lastIceRestartAt < 10_000 {
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

    private func fetchTurnCredentials(token: String) {
        Task {
            do {
                let credentials = try await apiClient.fetchTurnCredentials(host: serverHost, token: token)
                applyTurnCredentials(credentials)
            } catch {
                applyDefaultIceServers()
            }
        }
    }

    private func applyTurnCredentials(_ credentials: TurnCredentials) {
        let servers: [IceServerConfig] = credentials.uris.map {
            IceServerConfig(urls: [$0], username: credentials.username, credential: credentials.password)
        }

        webRtcEngine.setIceServers(servers)
        flushPendingMessages()
        maybeSendOffer()
    }

    private func applyDefaultIceServers() {
        webRtcEngine.setIceServers([
            IceServerConfig(urls: ["stun:stun.l.google.com:19302"], username: nil, credential: nil)
        ])
        flushPendingMessages()
        maybeSendOffer()
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
        let resolvedHostCid = (parsedHostCid?.isEmpty == false ? parsedHostCid : nil) ?? hostCid ?? clientId

        var participants: [Participant] = []
        if let values = payload["participants"]?.arrayValue {
            for value in values {
                guard let participantObject = value.objectValue else { continue }
                guard let cid = participantObject["cid"]?.stringValue, !cid.isEmpty else { continue }
                let joinedAt = participantObject["joinedAt"]?.intValue.map(Int64.init)
                participants.append(Participant(cid: cid, joinedAt: joinedAt))
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

        webRtcEngine.collectWebRtcStatsSummary { [weak self] summary in
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

    private func cleanupCall(message: String) {
        clearJoinRecovery()
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

        uiState = CallUiState(phase: .idle)
        watchRecentRoomsIfNeeded()
    }

    private func resetResources() {
        stopRemoteVideoStatePolling()

        signalingClient.close()
        webRtcEngine.release()
        deactivateAudioSession()

        currentRoomId = nil
        hostCid = nil
        clientId = nil
        callStartTimeMs = nil

        pendingJoinRoom = nil
        pendingMessages.removeAll()

        reconnectAttempts = 0
        sentOffer = false
        isMakingOffer = false
        pendingIceRestart = false

        reconnectTask?.cancel()
        reconnectTask = nil
        clearJoinRecovery()
        clearOfferTimeout()
        clearIceRestartTimer()

        userPreferredVideoEnabled = true
        isVideoPausedByProximity = false
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

        activateAudioSession()
        webRtcEngine.startLocalMedia(preferVideo: shouldEnableVideo)

        if !shouldEnableAudio {
            webRtcEngine.toggleAudio(false)
        }

        userPreferredVideoEnabled = shouldEnableVideo
        applyLocalVideoPreference()

        startRemoteVideoStatePolling()

        pendingJoinRoom = roomId
        ensureSignalingConnection()
    }

    private func resolveMediaPermissions() async -> MediaPermissions {
        async let cameraGranted = requestCameraPermission()
        async let microphoneGranted = requestMicrophonePermission()
        return await MediaPermissions(
            cameraGranted: cameraGranted,
            microphoneGranted: microphoneGranted
        )
    }

    private func requestCameraPermission() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return true
        case .notDetermined:
            return await withCheckedContinuation { continuation in
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    continuation.resume(returning: granted)
                }
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
            return await withCheckedContinuation { continuation in
                audioSession.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        case .denied:
            return false
        @unknown default:
            return false
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
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.currentRoomId == roomId else { return }
            guard self.uiState.isSignalingConnected else { return }

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
                self.signalingClient.connect(host: self.serverHost)
                return
            }

            if roomId == nil && self.currentRoomId == nil && !self.watchedRoomIds.isEmpty {
                self.signalingClient.connect(host: self.serverHost)
            }
        }
    }

    private func refreshRecentCalls() {
        let calls = recentCallStore.getRecentCalls()
        recentCalls = calls

        watchedRoomIds = calls.map { $0.roomId }
        let watchedSet = Set(watchedRoomIds)
        roomStatuses = roomStatuses.filter { watchedSet.contains($0.key) }

        watchRecentRoomsIfNeeded()
    }

    private func watchRecentRoomsIfNeeded() {
        if watchedRoomIds.isEmpty {
            if currentRoomId == nil && signalingClient.isConnected() {
                signalingClient.close()
            }
            return
        }

        if signalingClient.isConnected() {
            sendWatchRoomsIfNeeded()
        } else {
            signalingClient.connect(host: serverHost)
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

    private func isHost() -> Bool {
        clientId != nil && clientId == hostCid
    }

    private func shouldReconnectSignaling() -> Bool {
        currentRoomId != nil || !watchedRoomIds.isEmpty
    }

    private func updateState(_ mutate: (inout CallUiState) -> Void) {
        var next = uiState
        mutate(&next)
        uiState = next
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
                    eventSink?.updateState { $0.isScreenSharing = false }
                    eventSink?.applyLocalVideoPreference()
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

        updateState {
            $0.isSignalingConnected = true
            $0.activeTransport = activeTransport
            $0.isReconnecting = false
        }

        if let join = pendingJoinRoom {
            pendingJoinRoom = nil
            sendJoin(roomId: join)
        }

        sendWatchRoomsIfNeeded()

        if pendingIceRestart {
            triggerIceRestart(reason: "signaling-reconnect")
        }
    }

    func onMessage(_ message: SignalingMessage) {
        handleSignalingMessage(message)
    }

    func onClosed(reason: String) {
        _ = reason
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
