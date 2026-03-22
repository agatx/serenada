import Combine
import Foundation
import SerenadaCallUI
import SerenadaCore

@MainActor
final class CallManager: ObservableObject {
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
    @Published private(set) var roomStatuses: [String: RoomOccupancy] = [:]
    @Published private(set) var activeSession: SerenadaSession?

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
    private let roomWatcher: RoomWatcher
    private lazy var joinSnapshotFeature = JoinSnapshotFeature(
        apiClient: apiClient,
        attachLocalRenderer: { [weak self] renderer in
            self?.activeSession?.attachLocalRenderer(renderer)
        },
        detachLocalRenderer: { [weak self] renderer in
            self?.activeSession?.detachLocalRenderer(renderer)
        }
    )

    private var watchedRoomIds: [String] = []
    private var pushEndpointObserver: NSObjectProtocol?
    private var activeSessionStateCancellable: AnyCancellable?
    private var activeSessionJoinCid: String?
    private var callStartTimeMs: Int64?
    private var hasNotifiedPushForJoin = false

    init(
        apiClient: APIClient = APIClient(),
        settingsStore: SettingsStore = SettingsStore(),
        recentCallStore: RecentCallStore = RecentCallStore(),
        savedRoomStore: SavedRoomStore = SavedRoomStore(),
        roomWatcher: RoomWatcher? = nil
    ) {
        self.apiClient = apiClient
        self.settingsStore = settingsStore
        self.recentCallStore = recentCallStore
        self.savedRoomStore = savedRoomStore
        self.pushSubscriptionManager = PushSubscriptionManager(
            apiClient: apiClient,
            settingsStore: settingsStore
        )
        self.roomWatcher = roomWatcher ?? RoomWatcher()

        self.serverHost = settingsStore.host
        self.selectedLanguage = settingsStore.language
        self.isDefaultCameraEnabled = settingsStore.isDefaultCameraEnabled
        self.isDefaultMicrophoneEnabled = settingsStore.isDefaultMicrophoneEnabled
        self.isHdVideoExperimentalEnabled = settingsStore.isHdVideoExperimentalEnabled
        self.areSavedRoomsShownFirst = settingsStore.areSavedRoomsShownFirst
        self.areRoomInviteNotificationsEnabled = settingsStore.areRoomInviteNotificationsEnabled
        self.appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "-"

        self.roomWatcher.delegate = self
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

        refreshRecentCalls()
        refreshSavedRooms()
        // Both refresh methods above call refreshWatchedRooms individually;
        // since they run back-to-back at init, the first call is a no-op
        // superseded by the second. This is harmless but noted for clarity.
    }

    deinit {
        activeSessionStateCancellable?.cancel()
        if let pushEndpointObserver {
            NotificationCenter.default.removeObserver(pushEndpointObserver)
        }
    }

    func updateServerHost(_ host: String) {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = trimmed.isEmpty ? AppConstants.defaultHost : trimmed
        let changed = normalized != serverHost

        settingsStore.host = normalized
        serverHost = normalized

        if changed {
            roomWatcher.stop()
            syncSavedRoomPushSubscriptions(savedRooms)
            refreshWatchedRooms()
        }
    }

    func validateServerHost(_ host: String) async -> Result<String, Error> {
        let normalized = host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? AppConstants.defaultHost
            : host.trimmingCharacters(in: .whitespacesAndNewlines)

        do {
            let diag = SerenadaDiagnostics(config: SerenadaConfig(serverHost: normalized))
            try await diag.validateServerHost()
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
        activeSession?.setHdVideoExperimentalEnabled(enabled)
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
        let roomId = activeSession?.roomId.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !roomId.isEmpty else {
            return .failure(NSError(domain: "CallManager", code: 11, userInfo: [NSLocalizedDescriptionKey: "No active room"]))
        }

        do {
            try await apiClient.sendPushInvite(
                host: activeSession?.serverHost ?? serverHost,
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
            (activeSession?.roomId == roomId || uiState.roomId == roomId) &&
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
            uiState = CallUiState(phase: .error, errorMessage: L10n.errorEnterRoomOrId)
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
        guard activeSession == nil else { return }
        guard uiState.phase == .idle else { return }

        uiState = CallUiState(
            phase: .creatingRoom,
            roomId: nil,
            errorMessage: nil
        )
        uiState.statusMessage = L10n.callStatusCreatingRoom

        let core = makeSerenadaCore(host: serverHost)
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let created = try await core.createRoom()
                self.activateSession(created.session)
            } catch {
                self.uiState = CallUiState(
                    phase: .error,
                    errorMessage: error.localizedDescription.isEmpty ? L10n.errorFailedCreateRoom : error.localizedDescription
                )
            }
        }
    }

    func joinRoom(_ roomId: String, oneOffHost: String? = nil) {
        let trimmed = roomId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            uiState = CallUiState(phase: .error, errorMessage: L10n.errorInvalidRoomId)
            return
        }

        if savedRoomStore.markRoomJoined(roomId: trimmed) {
            refreshSavedRooms()
        }

        let targetHost = DeepLinkParser.normalizeHostValue(oneOffHost) ?? serverHost
        let session = makeSerenadaCore(host: targetHost).join(roomId: trimmed)
        activateSession(session)
    }

    func dismissActiveCall() {
        guard let session = activeSession else { return }

        switch session.state.phase {
        case .awaitingPermissions:
            session.cancelJoin()
        case .joining, .waiting, .inCall:
            session.leave()
        case .idle, .ending, .error:
            break
        }

        saveSessionToHistoryIfNeeded(session)
        clearActiveSession(resetUiState: true)
    }

    func dismissError() {
        guard uiState.phase == .error else { return }
        uiState = CallUiState()
        refreshRecentCalls()
        refreshSavedRooms()
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

        let core = makeSerenadaCore(host: normalizedHost)
        do {
            let roomId = try await core.createRoomId()
            saveRoom(roomId: roomId, name: normalizedName, host: normalizedHost)
            let link = buildSavedRoomInviteLink(host: normalizedHost, roomId: roomId, roomName: normalizedName)
            return .success(link)
        } catch {
            return .failure(error)
        }
    }

    private func makeSerenadaCore(host: String) -> SerenadaCore {
        let core = SerenadaCore(
            config: SerenadaConfig(
                serverHost: host,
                defaultAudioEnabled: settingsStore.isDefaultMicrophoneEnabled,
                defaultVideoEnabled: settingsStore.isDefaultCameraEnabled
            )
        )
        core.logger = PrintSerenadaLogger()
        return core
    }

    private func activateSession(_ session: SerenadaSession) {
        activeSessionStateCancellable?.cancel()

        activeSession = session
        activeSessionJoinCid = nil
        callStartTimeMs = Int64(Date().timeIntervalSince1970 * 1000)
        hasNotifiedPushForJoin = false

        session.onPermissionsRequired = { [weak self, weak session] permissions in
            Task { @MainActor in
                guard let self, let session else { return }
                guard self.activeSession === session else { return }

                let granted = await SerenadaPermissions.request(permissions)
                guard self.activeSession === session else { return }

                if granted {
                    session.resumeJoin()
                } else {
                    session.cancelJoin()
                }
            }
        }

        if settingsStore.isHdVideoExperimentalEnabled {
            session.setHdVideoExperimentalEnabled(true)
        }

        activeSessionStateCancellable = session.$state
            .combineLatest(session.$diagnostics)
            .sink { [weak self, weak session] state, diagnostics in
                guard let self, let session else { return }
                self.handleActiveSessionStateChange(session: session, state: state, diagnostics: diagnostics)
            }

        handleActiveSessionStateChange(session: session, state: session.state, diagnostics: session.diagnostics)
    }

    private func handleActiveSessionStateChange(session: SerenadaSession, state: CallState, diagnostics: CallDiagnostics) {
        guard activeSession === session else { return }

        let cid = state.localParticipant.cid?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let cid, !cid.isEmpty, activeSessionJoinCid != cid {
            activeSessionJoinCid = cid
            pushSubscriptionManager.subscribeRoom(roomId: session.roomId, host: session.serverHost)
        }

        let participantCount = max(1, 1 + state.remoteParticipants.count)
        var next = uiState
        next.phase = mapSessionPhase(state.phase)
        next.roomId = state.roomId
        next.localCid = state.localParticipant.cid
        next.statusMessage = statusMessage(for: state.phase, participantCount: participantCount)
        next.errorMessage = errorMessage(for: state.error)
        next.isHost = state.localParticipant.isHost
        next.participantCount = participantCount
        next.localAudioEnabled = state.localParticipant.audioEnabled
        next.localVideoEnabled = state.localParticipant.videoEnabled
        next.remoteParticipants = state.remoteParticipants.map {
            RemoteParticipant(
                cid: $0.cid,
                videoEnabled: $0.videoEnabled,
                connectionState: $0.connectionState
            )
        }
        next.connectionStatus = mapSessionConnectionStatus(state.connectionStatus)
        next.isSignalingConnected = diagnostics.isSignalingConnected
        next.iceConnectionState = diagnostics.iceConnectionState.rawValue
        next.connectionState = diagnostics.peerConnectionState.rawValue
        next.signalingState = diagnostics.rtcSignalingState.rawValue
        next.activeTransport = diagnostics.activeTransport
        next.realtimeStats = diagnostics.realtimeStats
        next.isFrontCamera = diagnostics.isFrontCamera
        next.isScreenSharing = diagnostics.isScreenSharing
        next.localCameraMode = state.localParticipant.cameraMode
        next.cameraZoomFactor = diagnostics.cameraZoomFactor
        next.isFlashAvailable = diagnostics.isFlashAvailable
        next.isFlashEnabled = diagnostics.isFlashEnabled
        next.remoteContentCid = diagnostics.remoteContentParticipantId
        next.remoteContentType = diagnostics.remoteContentType
        uiState = next

        if state.phase == .error {
            let errorUiState = CallUiState(
                phase: .error,
                roomId: state.roomId,
                errorMessage: errorMessage(for: state.error)
            )
            clearActiveSession(resetUiState: false)
            uiState = errorUiState
            return
        }

        if state.phase == .idle {
            saveSessionToHistoryIfNeeded(session)
            clearActiveSession(resetUiState: true)
            return
        }

        guard !hasNotifiedPushForJoin else { return }
        guard let cid, !cid.isEmpty else { return }
        guard state.phase == .waiting || state.phase == .inCall else { return }

        hasNotifiedPushForJoin = true
        let roomId = session.roomId
        let host = session.serverHost
        let endpoint = pushSubscriptionManager.cachedEndpoint()
        joinSnapshotFeature.prepareSnapshotId(
            host: host,
            roomId: roomId,
            isVideoEnabled: { [weak session] in
                session?.state.localParticipant.videoEnabled ?? false
            },
            isJoinAttemptActive: { [weak self, weak session] in
                guard let self, let session else { return false }
                guard self.activeSession === session else { return false }
                let phase = session.state.phase
                return phase != .idle && phase != .ending && phase != .error
            },
            onReady: { [weak self] snapshotId in
                guard let self else { return }
                Task {
                    do {
                        try await self.apiClient.notifyRoom(
                            host: host,
                            roomId: roomId,
                            cid: cid,
                            snapshotId: snapshotId,
                            pushEndpoint: endpoint
                        )
                    } catch {
                    }
                }
            }
        )
    }

    private func saveSessionToHistoryIfNeeded(_ session: SerenadaSession) {
        guard let startTime = callStartTimeMs else { return }
        guard session.state.localParticipant.cid != nil || activeSessionJoinCid != nil else {
            callStartTimeMs = nil
            return
        }

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let duration = max(0, Int((nowMs - startTime) / 1000))

        recentCallStore.saveCall(
            RecentCall(
                roomId: session.roomId,
                startTime: startTime,
                durationSeconds: duration,
                host: session.serverHost
            )
        )

        callStartTimeMs = nil
        refreshRecentCalls()
    }

    private func clearActiveSession(resetUiState: Bool) {
        activeSessionStateCancellable?.cancel()
        activeSessionStateCancellable = nil
        activeSession = nil
        activeSessionJoinCid = nil
        callStartTimeMs = nil
        hasNotifiedPushForJoin = false

        if resetUiState {
            uiState = CallUiState()
        }
    }

    private func mapSessionPhase(_ phase: SerenadaCallPhase) -> CallPhase {
        switch phase {
        case .idle:
            return .idle
        case .awaitingPermissions, .joining:
            return .joining
        case .waiting:
            return .waiting
        case .inCall:
            return .inCall
        case .ending:
            return .ending
        case .error:
            return .error
        }
    }

    private func mapSessionConnectionStatus(_ status: SerenadaConnectionStatus) -> ConnectionStatus {
        switch status {
        case .connected:
            return .connected
        case .recovering:
            return .recovering
        case .retrying:
            return .retrying
        }
    }

    private func statusMessage(for phase: SerenadaCallPhase, participantCount: Int) -> String? {
        switch phase {
        case .idle:
            return nil
        case .awaitingPermissions, .joining:
            return L10n.callStatusJoiningRoom
        case .waiting:
            return L10n.callStatusWaitingForJoin
        case .inCall:
            return participantCount > 1 ? L10n.callStatusInCall : L10n.callStatusWaitingForJoin
        case .ending:
            return L10n.callStatusCallEnded
        case .error:
            return nil
        }
    }

    private func errorMessage(for error: CallError?) -> String? {
        guard let error else { return nil }

        switch error {
        case .signalingTimeout, .connectionFailed:
            return L10n.callStatusConnectionFailed
        case .roomFull:
            return L10n.errorRoomCapacityUnsupported
        case .serverError(let message), .unknown(let message):
            return message.isEmpty ? L10n.errorUnknown : message
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
            for call in patched where calls.first(where: { $0.roomId == call.roomId })?.host == nil {
                recentCallStore.saveCall(call)
            }
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
            let patched = rooms.map { room in
                room.host == nil
                    ? SavedRoom(roomId: room.roomId, name: room.name, createdAt: room.createdAt, host: host, lastJoinedAt: room.lastJoinedAt)
                    : room
            }
            for room in patched where rooms.first(where: { $0.roomId == room.roomId })?.host == nil {
                savedRoomStore.saveRoom(room)
            }
            savedRooms = patched
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

        if let session = activeSession {
            pushSubscriptionManager.subscribeRoom(roomId: session.roomId, host: session.serverHost)
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

        roomWatcher.watchRooms(roomIds: watchedRoomIds, host: serverHost)
    }

    private func isCurrentServerHost(_ host: String?) -> Bool {
        guard let host else { return true }
        return host.compare(serverHost, options: .caseInsensitive) == .orderedSame
    }

    private func hostOverrideOrNull(_ host: String?) -> String? {
        DeepLinkParser.normalizeHostValue(host).flatMap { isCurrentServerHost($0) ? nil : $0 }
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
}

extension CallManager: RoomWatcherDelegate {
    func roomWatcher(_ watcher: RoomWatcher, didUpdateStatuses statuses: [String: RoomOccupancy]) {
        roomStatuses = statuses
    }
}
