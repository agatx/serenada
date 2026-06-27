import Combine
import Foundation
import SerenadaBroadcastExtensionSupport
import SerenadaCallUI
import SerenadaCore

@MainActor
final class CallManager: ObservableObject {
    static let independentContentVideoEnabled = true

    @Published private(set) var uiState = CallUiState()
    @Published private(set) var serverHost: String
    @Published private(set) var selectedLanguage: String
    @Published private(set) var isDefaultCameraEnabled: Bool
    @Published private(set) var isDefaultMicrophoneEnabled: Bool
    @Published private(set) var isHdVideoExperimentalEnabled: Bool
    @Published private(set) var callUiVariant: SerenadaCallUiVariant
    @Published private(set) var areSavedRoomsShownFirst: Bool
    @Published private(set) var areRoomInviteNotificationsEnabled: Bool
    @Published private(set) var displayName: String
    @Published private(set) var appVersion: String
    @Published private(set) var recentCalls: [RecentCall] = []
    @Published private(set) var savedRooms: [SavedRoom] = []
    @Published private(set) var roomStatuses: [String: RoomOccupancy] = [:]
    /// The active (foreground) call's session, mirrored from
    /// `callRegistry.activeCall?.session`. Kept as a published mirror (rather than
    /// a stored single session) so the existing `RootView` and call-screen code
    /// keep working unchanged; the registry is the source of truth.
    @Published private(set) var activeSession: SerenadaSession?
    /// Held (background) calls, for the minimal switcher UI. Excludes the active
    /// call and any ended-but-not-yet-dismissed records. Empty for the common
    /// single-call case.
    @Published private(set) var heldCalls: [ManagedCallState] = []
    /// True while a serialized registry operation (join/switch/hold/leave/end) is
    /// running; the switcher disables its actions while this is set.
    @Published private(set) var isCallOperationInProgress = false
    @Published var snapshotBanner: SnapshotBanner?
    /// Transient banner for a RECOVERABLE per-call error (e.g. a failed switch that
    /// left the live call intact). Distinct from `uiState.phase == .error`, which is
    /// the whole-app terminal error screen reserved for "no call survived".
    @Published var callErrorBanner: CallErrorBanner?

    struct SnapshotBanner: Identifiable, Equatable {
        let id = UUID()
        let success: Bool
        let message: String
    }

    struct CallErrorBanner: Identifiable, Equatable {
        let id = UUID()
        let message: String
    }

    private var snapshotBannerTask: Task<Void, Never>?
    private var callErrorBannerTask: Task<Void, Never>?

    /// Surface a recoverable per-call error as a transient banner over the call UI.
    /// Used when a switch/activation fails but a live call (active or held) remains,
    /// so we must NOT tear the whole app down to the error screen (FIX P5-2).
    func presentCallErrorBanner(_ message: String) {
        callErrorBanner = CallErrorBanner(message: message)
        callErrorBannerTask?.cancel()
        callErrorBannerTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if !Task.isCancelled {
                self?.callErrorBanner = nil
            }
        }
    }

    func presentSnapshotToast(saved: Bool, reason: String?) {
        let message: String
        if saved {
            message = reason ?? L10n.snapshotSavedToPhotos
        } else if let reason {
            message = "\(L10n.snapshotFailed): \(reason)"
        } else {
            message = L10n.snapshotFailed
        }
        snapshotBanner = SnapshotBanner(success: saved, message: message)
        snapshotBannerTask?.cancel()
        snapshotBannerTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if !Task.isCancelled {
                self?.snapshotBanner = nil
            }
        }
    }

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

    /// Process-wide multi-call registry. Created lazily and bound to a
    /// ``SerenadaCore`` for a specific server host (`registryHost`). Recreated when
    /// the host changes AND no call is live, so a single-call UX (one registry with
    /// one foreground call) is preserved and the v1 "one mode per process" arbiter
    /// invariant is never violated by stale registries.
    private var callRegistry: SerenadaCallRegistry?
    /// The ``SerenadaCore`` the current registry is bound to (used for
    /// `createRoom` / `roomURL`); kept alongside the registry since both are
    /// host-specific.
    private var registryCore: SerenadaCore?
    private var registryHost: String?
    private var registryCancellables = Set<AnyCancellable>()
    /// CallId the host most recently asked the registry to foreground, so observer
    /// re-derivation of the active session keys off the registry's active call.
    private var activeCallId: CallId?

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
        self.callUiVariant = settingsStore.callUiVariant
        self.areSavedRoomsShownFirst = settingsStore.areSavedRoomsShownFirst
        self.areRoomInviteNotificationsEnabled = settingsStore.areRoomInviteNotificationsEnabled
        self.displayName = settingsStore.displayName
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
        snapshotBannerTask?.cancel()
        callErrorBannerTask?.cancel()
        registryCancellables.forEach { $0.cancel() }
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

    func updateCallUiVariant(_ variant: SerenadaCallUiVariant) {
        settingsStore.callUiVariant = variant
        callUiVariant = variant
    }

    func updateSavedRoomsShownFirst(_ enabled: Bool) {
        settingsStore.areSavedRoomsShownFirst = enabled
        areSavedRoomsShownFirst = enabled
    }

    func updateRoomInviteNotifications(_ enabled: Bool) {
        settingsStore.areRoomInviteNotificationsEnabled = enabled
        areRoomInviteNotificationsEnabled = enabled
    }

    func updateDisplayName(_ name: String) {
        settingsStore.displayName = name
        displayName = name
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

        let (registry, core) = registry(forHost: serverHost)
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let created = try await core.createRoom()
                await self.startOrSwitch(registry: registry, room: RoomRef(url: created.url, displayName: self.resolvedDisplayName))
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
        // The registry's core is bound to `targetHost`, so a bare-roomId RoomRef
        // resolves against the correct host even when a deep-link one-off host
        // differs from the persisted server host. The registry canonicalizes the
        // room token for dedup.
        let (registry, _) = registry(forHost: targetHost)
        let room = RoomRef(roomId: trimmed, displayName: resolvedDisplayName)

        // Show a joining placeholder for a fresh single call so the user is not left
        // on the join screen while the held session connects (it is promoted to
        // foreground by `joinAndSwitch`). When a call is already active, leave the
        // active screen untouched — switching happens behind the held chips.
        if activeCallId == nil {
            uiState = CallUiState(phase: .joining, roomId: trimmed, errorMessage: nil)
            uiState.statusMessage = L10n.callStatusJoiningRoom
        }

        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.startOrSwitch(registry: registry, room: room)
        }
    }

    /// Route a new outgoing/joined call through the registry (`joinAndSwitch`).
    /// Handles the `needsPermission` outcome by prompting and retrying the switch
    /// (registry-managed sessions do not auto-prompt during foreground activation —
    /// permission is preflighted before the old call is released, design
    /// "Permissions" / Core Invariant 4).
    private func startOrSwitch(registry: SerenadaCallRegistry, room: RoomRef) async {
        switch await registry.joinAndSwitch(room) {
        case .active:
            break
        case let .needsPermission(callId):
            await requestPermissionsAndSwitch(registry: registry, callId: callId)
        case let .failed(callId, error):
            await handleStartOrSwitchFailure(registry: registry, failedCallId: callId, error: error)
        }
    }

    /// Handle a `joinAndSwitch`/`switchTo` failure WITHOUT blowing away a surviving
    /// live call (FIX P5-2). The registry rolls the previous active call back to
    /// foreground on a switch/activation failure, and leaves the prior active
    /// untouched on a room-join failure, so after a failure a live call may still
    /// exist. In that case keep the live-call UI and surface the failed target as a
    /// transient, recoverable banner. Only fall through to the whole-app `.error`
    /// screen when there is genuinely no active call AND no live held calls.
    private func handleStartOrSwitchFailure(
        registry: SerenadaCallRegistry,
        failedCallId: CallId?,
        error: CallActivationError
    ) async {
        // Drop the failed target's record (never the surviving active call). On a
        // room-join failure the registry already marked it ended; on an activation
        // failure the rolled-back target is still live-but-hidden (filtered out of
        // the switcher because it carries an `activationError`). `retireCall` handles
        // both cases.
        if let failedCallId, registry.activeCallId != failedCallId {
            await Self.retireCall(registry, id: failedCallId)
        }

        surfaceFailureOrError(registry: registry, error: error)
    }

    /// After a switch/activation failure, surface the error as a recoverable banner
    /// if any live call survived (rolled-back active, or held calls remain), else
    /// fall through to the whole-app `.error` screen (FIX P5-2).
    private func surfaceFailureOrError(registry: SerenadaCallRegistry, error: CallActivationError) {
        let liveHeldCount = Self.heldCalls(from: registry.calls, activeCallId: registry.activeCallId).count
        if Self.callSurvivesFailure(activeCallId: registry.activeCallId, liveHeldCount: liveHeldCount) {
            presentCallErrorBanner(callActivationErrorMessage(error))
        } else {
            uiState = CallUiState(
                phase: .error,
                errorMessage: callActivationErrorMessage(error)
            )
        }
    }

    /// True when a live call (the rolled-back active call, or any live held call)
    /// survives a switch/activation failure, so the host must keep the call UI and
    /// surface the failure as a recoverable per-call error instead of tearing the
    /// whole app down to the error screen (FIX P5-2). Pure + static so the decision
    /// is unit-testable without a registry.
    static func callSurvivesFailure(activeCallId: CallId?, liveHeldCount: Int) -> Bool {
        activeCallId != nil || liveHeldCount > 0
    }

    /// True when the active call's terminal `.error` must be surfaced as a transient
    /// per-call banner (with routing falling through to the held surface) instead of
    /// a whole-app error screen: that is, whenever live held calls remain after the
    /// active call ends in error. The active call is gone by definition in this path
    /// (it just hit terminal error), so only the live held set decides — Invariant 5
    /// (no auto-promote) means those held calls must stay reachable, not be masked by
    /// a whole-app error screen (FIX P5-7). Pure + static so the decision is
    /// unit-testable without a registry.
    static func terminalErrorShowsBanner(liveHeldCount: Int) -> Bool {
        liveHeldCount > 0
    }

    /// The held call exists; prompt for the missing grants, then retry the switch.
    /// If the user declines, the held call is left/dismissed and the UI returns to
    /// idle (matching the old cancel-on-deny behavior).
    private func requestPermissionsAndSwitch(registry: SerenadaCallRegistry, callId: CallId) async {
        let permissions: [MediaCapability]
        if case let .needsPermission(caps)? = registry.calls.first(where: { $0.id == callId })?.activationError {
            permissions = caps
        } else {
            permissions = [.microphone]
        }

        let granted = await SerenadaPermissions.request(permissions)
        guard granted else {
            await Self.retireCall(registry, id: callId)
            return
        }

        switch await registry.switchToCall(id: callId) {
        case .active:
            break
        case .needsPermission:
            // Still denied at the OS level after the prompt: give up on this call.
            await Self.retireCall(registry, id: callId)
        case let .failed(error):
            await Self.retireCall(registry, id: callId)
            // The switch failed but a live call may still survive (rolled-back
            // active, or other held calls). Keep its UI; only fall to the error
            // screen when nothing survived (FIX P5-2).
            surfaceFailureOrError(registry: registry, error: error)
        }
    }

    func dismissActiveCall() {
        guard let registry = callRegistry, let callId = activeCallId else {
            // No registry-backed active call; nothing to tear down.
            clearActiveSession(resetUiState: true)
            return
        }

        // Capture history before the registry tears the session down.
        if let session = registry.call(id: callId)?.session {
            saveSessionToHistoryIfNeeded(session)
        }

        Task { @MainActor [weak self] in
            await Self.retireCall(registry, id: callId)
            self?.clearActiveSession(resetUiState: true)
        }
    }

    /// Switch the foreground to a held call (switcher action).
    func switchToHeldCall(id: CallId) {
        guard let registry = callRegistry else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            switch await registry.switchToCall(id: id) {
            case .active:
                break
            case .needsPermission:
                await self.requestPermissionsAndSwitch(registry: registry, callId: id)
            case let .failed(error):
                // The switch failed but the previously-active call is rolled back to
                // foreground (or other held calls remain). Keep the call UI and
                // surface the failure as a recoverable banner (FIX P5-2) rather than
                // writing an invisible `uiState.errorMessage`.
                self.presentCallErrorBanner(self.callActivationErrorMessage(error))
            }
        }
    }

    /// Leave a held call without disturbing the active call (switcher action).
    func leaveHeldCall(id: CallId) {
        guard let registry = callRegistry else { return }
        Task { @MainActor in
            await Self.retireCall(registry, id: id)
        }
    }

    /// Leave a call and dismiss its record. `leaveCall` retires a still-live call
    /// and is a safe no-op on an already-ended one, so this pair retires a target
    /// regardless of its current phase.
    private static func retireCall(_ registry: SerenadaCallRegistry, id: CallId) async {
        await registry.leaveCall(id: id)
        await registry.dismissEndedCall(id: id)
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

    private var resolvedDisplayName: String? {
        let name = settingsStore.displayName
        return name.isEmpty ? nil : name
    }

    private func makeSerenadaCore(host: String) -> SerenadaCore {
        let frontline = settingsStore.callUiVariant == .frontline
        let cameraModes: [LocalCameraMode]? = frontline ? [.world, .selfie, .composite] : nil
        let core = SerenadaCore(
            config: SerenadaConfig(
                serverHost: host,
                defaultAudioEnabled: settingsStore.isDefaultMicrophoneEnabled,
                defaultVideoEnabled: frontline ? false : settingsStore.isDefaultCameraEnabled,
                // Bundled host apps opt into independent screen-share content;
                // the SDK library default remains disabled for integrators that
                // need legacy single-video receiver behavior.
                enableIndependentContentVideo: Self.independentContentVideoEnabled,
                // Full-device screen sharing via the embedded broadcast upload
                // extension. The app group + extension bundle ID must match the
                // SerenadaBroadcast target's entitlement and PRODUCT_BUNDLE_IDENTIFIER.
                screenShareMode: .broadcast(
                    BroadcastIPCConfig(
                        appGroupIdentifier: AppConstants.appGroupIdentifier,
                        extensionBundleId: AppConstants.broadcastExtensionBundleIdentifier
                    )
                ),
                cameraModes: cameraModes,
                proximityMonitoringEnabled: true
            )
        )
        core.logger = PrintSerenadaLogger()
        return core
    }

    /// Get (or lazily create) the registry bound to `host`. A new registry +
    /// ``SerenadaCore`` is built only when none exists yet OR when the host changed
    /// AND no call is live (so we never strand a live registry, and never let two
    /// registries fight over the process arbiter — Core Invariant 6). When a call
    /// is already live, the existing registry is reused regardless of host (the
    /// per-call RoomRef carries its own host), preserving the single-process,
    /// single-mode contract.
    private func registry(forHost host: String) -> (SerenadaCallRegistry, SerenadaCore) {
        if let registry = callRegistry, let core = registryCore {
            let hasLiveCall = registry.calls.contains { !Self.isEndedPhase($0.membershipPhase) && $0.activationError == nil }
                || activeCallId != nil
            if registryHost == host || hasLiveCall {
                return (registry, core)
            }
        }

        // Tear down observers for the old (idle) registry before replacing it.
        registryCancellables.forEach { $0.cancel() }
        registryCancellables.removeAll()

        let core = makeSerenadaCore(host: host)
        let registry = SerenadaCallRegistry(core: core)
        callRegistry = registry
        registryCore = core
        registryHost = host

        // Subscribe to the registry's published axes (calls / activeCallId /
        // op-in-progress). `combineLatest` delivers POST-mutation values, so the app
        // re-derives the active UI from the registry's active call — never from a
        // single stored session.
        registry.$calls
            .combineLatest(registry.$activeCallId, registry.$registryOperationInProgress)
            .sink { [weak self, weak registry] _, _, _ in
                guard let self, let registry else { return }
                self.onRegistryStateChange(registry)
            }
            .store(in: &registryCancellables)

        return (registry, core)
    }

    /// React to any registry state change: re-derive the active session (keying off
    /// the registry's active call, NOT a single stored session), refresh the held
    /// list + busy flag, and handle active-call termination. This is the single
    /// place app `uiState` is re-derived from the active call.
    private func onRegistryStateChange(_ registry: SerenadaCallRegistry) {
        isCallOperationInProgress = registry.registryOperationInProgress

        // Held calls = live, non-active, non-ended calls (switcher source).
        heldCalls = Self.heldCalls(from: registry.calls, activeCallId: registry.activeCallId)

        // Re-bind only when the active call identity actually changes. The registry
        // can publish a transient `activeCallId == nil` mid-switch (it clears the
        // old id before activating the new one); binding off the registry's CURRENT
        // active session — not a single stored session — means we simply follow it.
        // Terminal UI (error/idle) is driven by the session observer +
        // `dismissActiveCall`, NOT by a transient nil here, so a switch never flashes
        // the join screen.
        let newActiveId = registry.activeCallId
        guard newActiveId != activeCallId else { return }

        // Skip a transient nil while an op is still running (mid-switch). The final
        // publish at op completion delivers the settled active id.
        if newActiveId == nil, registry.registryOperationInProgress { return }

        activeCallId = newActiveId
        bindActiveSession(registry.activeCall?.session)
    }

    /// Swap the per-session Combine observers to the new active session (or detach
    /// when nil). Mirrors the old single-session wiring; the registry decides which
    /// session is active, so the app ignores updates from non-active sessions.
    private func bindActiveSession(_ session: SerenadaSession?) {
        activeSessionStateCancellable?.cancel()
        activeSessionStateCancellable = nil
        activeSession = session
        activeSessionJoinCid = nil

        guard let session else { return }

        callStartTimeMs = Int64(Date().timeIntervalSince1970 * 1000)
        hasNotifiedPushForJoin = false

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
        // Ignore state updates from any session that is no longer the active call
        // (design: "app-level CallManager ignores state updates from non-active
        // sessions"). The registry owns which call is foreground.
        guard activeSession === session else { return }

        let cid = state.localParticipant.cid?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let cid, !cid.isEmpty, activeSessionJoinCid != cid {
            activeSessionJoinCid = cid
            if let sessionHost = session.serverHost {
                pushSubscriptionManager.subscribeRoom(roomId: session.roomId, host: sessionHost)
            }
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
        next.localDisplayName = state.localParticipant.displayName
        next.localAudioLevel = state.localParticipant.audioLevel
        next.remoteParticipants = state.remoteParticipants.map {
            RemoteParticipant(
                cid: $0.cid,
                displayName: $0.displayName,
                peerId: $0.peerId,
                audioEnabled: $0.audioEnabled,
                videoEnabled: $0.videoEnabled,
                connectionState: $0.connectionState,
                audioLevel: $0.audioLevel
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
        next.availableCameraModes = state.localParticipant.availableCameraModes
        next.cameraZoomFactor = diagnostics.cameraZoomFactor
        next.isFlashAvailable = diagnostics.isFlashAvailable
        next.isFlashEnabled = diagnostics.isFlashEnabled
        next.remoteContentCid = diagnostics.remoteContentParticipantId
        next.remoteContentType = diagnostics.remoteContentType
        next.callStartedAtMs = state.callStartedAtMs
        uiState = next

        // Session-driven terminal states (remote-ended, fatal error). The REGISTRY
        // owns teardown: its phase observer enqueues `terminalBody`, which marks the
        // call ended and clears `activeCallId` (→ `bindActiveSession(nil)`). Here we
        // only drive the app UI + history, and detach this observer so a late
        // duplicate emission can't re-fire. We still dismiss the ended call record
        // so the switcher does not show a dead chip.
        if state.phase == .error {
            let endedCallId = activeCallId
            let errorDetail = errorMessage(for: state.error)
            detachActiveSessionObserver()

            // The active call ended in error, but the registry does NOT auto-promote
            // a held call (Invariant 5). If live held calls remain, a whole-app error
            // screen would mask them and strand the user. Route to the held surface
            // (the `.heldOnly` screen) and surface this failure as a transient per-call
            // banner instead — only write the whole-app `.error` when nothing survives
            // (FIX P5-7). `heldCalls` is already re-derived from the registry by
            // `onRegistryStateChange`, so reading it here reflects the live held set.
            if Self.terminalErrorShowsBanner(liveHeldCount: heldCalls.count) {
                // Reset the active-call UI to idle so routing falls through to the
                // held surface; the banner carries the failure detail.
                uiState = CallUiState()
                presentCallErrorBanner(errorDetail ?? L10n.errorUnknown)
            } else {
                uiState = CallUiState(
                    phase: .error,
                    roomId: state.roomId,
                    errorMessage: errorDetail
                )
            }
            if let registry = callRegistry, let endedCallId {
                Task { @MainActor in await registry.dismissEndedCall(id: endedCallId) }
            }
            return
        }

        if state.phase == .idle {
            saveSessionToHistoryIfNeeded(session)
            let endedCallId = activeCallId
            detachActiveSessionObserver()
            uiState = CallUiState()
            if let registry = callRegistry, let endedCallId {
                Task { @MainActor in await registry.dismissEndedCall(id: endedCallId) }
            }
            return
        }

        guard !hasNotifiedPushForJoin else { return }
        guard let cid, !cid.isEmpty else { return }
        guard state.phase == .waiting || state.phase == .inCall else { return }
        guard let host = session.serverHost else { return }

        hasNotifiedPushForJoin = true
        let roomId = session.roomId
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

    /// Detach the per-session observers and forget the active session, WITHOUT
    /// touching the registry (the registry owns call lifecycle). Used by the
    /// session terminal handlers, which then ask the registry to dismiss the record.
    private func detachActiveSessionObserver() {
        activeSessionStateCancellable?.cancel()
        activeSessionStateCancellable = nil
        activeSession = nil
        activeSessionJoinCid = nil
        callStartTimeMs = nil
        hasNotifiedPushForJoin = false
    }

    private func clearActiveSession(resetUiState: Bool) {
        detachActiveSessionObserver()
        activeCallId = nil

        if resetUiState {
            uiState = CallUiState()
        }
    }

    /// True for membership phases that mean the call is no longer live. Static +
    /// pure so the switcher's held-call selection is unit-testable.
    static func isEndedPhase(_ phase: SerenadaCallPhase) -> Bool {
        phase == .idle || phase == .error
    }

    /// The switcher's chip source: live (non-ended, non-failed) calls that are not
    /// the active foreground call. Pure + static so the derivation that drives
    /// `HeldCallsSwitcher` is unit-testable without spinning up a registry.
    static func heldCalls(from calls: [ManagedCallState], activeCallId: CallId?) -> [ManagedCallState] {
        calls.filter { call in
            call.id != activeCallId
                && !isEndedPhase(call.membershipPhase)
                && call.activationError == nil
        }
    }

    private func callActivationErrorMessage(_ error: CallActivationError) -> String {
        Self.callActivationErrorMessage(error)
    }

    /// Map a per-call registry activation error to a user-facing message. Static +
    /// pure (only reads localized strings) so the mapping is unit-testable.
    static func callActivationErrorMessage(_ error: CallActivationError) -> String {
        switch error {
        case .needsPermission:
            return L10n.callStatusConnectionFailed
        case let .activationFailed(message):
            return message.isEmpty ? L10n.errorUnknown : message
        case .releaseTimedOut:
            return L10n.callStatusConnectionFailed
        case let .joinFailed(message):
            return message.isEmpty ? L10n.callStatusConnectionFailed : message
        case .leaseUnavailable:
            return L10n.callStatusConnectionFailed
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
        case .roomEnded:
            return L10n.callStatusRoomEnded
        case .sessionExpired:
            return L10n.callStatusSessionExpired
        case .permissionDenied:
            return L10n.callStatusConnectionFailed
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

        if let session = activeSession, let sessionHost = session.serverHost {
            pushSubscriptionManager.subscribeRoom(roomId: session.roomId, host: sessionHost)
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

        do {
            try roomWatcher.watchRooms(roomIds: watchedRoomIds, host: serverHost)
        } catch {
            NSLog("CallManager failed to watch rooms for host %@: %@", serverHost, error.localizedDescription)
        }
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
