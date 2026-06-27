package app.serenada.android.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.serenada.android.BuildConfig
import app.serenada.android.R
import app.serenada.android.data.RecentCall
import app.serenada.android.data.RecentCallStore
import app.serenada.android.data.SavedRoom
import app.serenada.android.data.SavedRoomStore
import app.serenada.android.data.SettingsStore
import app.serenada.android.i18n.AppLocaleManager
import app.serenada.android.network.HostApiClient
import app.serenada.android.push.PushSubscriptionManager
import app.serenada.android.service.CallService
import app.serenada.android.service.CallServiceCall
import app.serenada.callui.SerenadaCallUiVariant
import app.serenada.core.CallDiagnostics
import app.serenada.core.CallId
import app.serenada.core.CallRegistryState
import app.serenada.core.CallState
import app.serenada.core.RoomOccupancy
import app.serenada.core.RoomRef
import app.serenada.core.RoomWatcher
import app.serenada.core.RoomWatcherDelegate
import app.serenada.core.SerenadaCallRegistry
import app.serenada.core.SerenadaConfig
import app.serenada.core.AndroidSerenadaLogger
import app.serenada.core.SerenadaCore
import app.serenada.core.SerenadaSession
import app.serenada.core.SerenadaTransport
import app.serenada.core.call.CallPhase
import app.serenada.core.network.CoreApiClient
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

internal const val INDEPENDENT_CONTENT_VIDEO_ENABLED = true

class CallManager(context: Context) : RoomWatcherDelegate {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = HostApiClient(okHttpClient)
    private val coreApiClient = CoreApiClient(okHttpClient)
    private val settingsStore = SettingsStore(appContext)
    private val recentCallStore = RecentCallStore(appContext)
    private val savedRoomStore = SavedRoomStore(appContext)

    private val _uiState = mutableStateOf(CallUiState())
    val uiState: State<CallUiState> = _uiState

    private val _serverHost = mutableStateOf(settingsStore.host)
    val serverHost: State<String> = _serverHost

    private val _selectedLanguage = mutableStateOf(settingsStore.language)
    val selectedLanguage: State<String> = _selectedLanguage

    private val _isDefaultCameraEnabled = mutableStateOf(settingsStore.isDefaultCameraEnabled)
    val isDefaultCameraEnabled: State<Boolean> = _isDefaultCameraEnabled

    private val _isDefaultMicrophoneEnabled = mutableStateOf(settingsStore.isDefaultMicrophoneEnabled)
    val isDefaultMicrophoneEnabled: State<Boolean> = _isDefaultMicrophoneEnabled

    private val _isHdVideoExperimentalEnabled =
        mutableStateOf(settingsStore.isHdVideoExperimentalEnabled)
    val isHdVideoExperimentalEnabled: State<Boolean> = _isHdVideoExperimentalEnabled

    private val _callUiVariant = mutableStateOf(settingsStore.callUiVariant)
    val callUiVariant: State<SerenadaCallUiVariant> = _callUiVariant

    private val _recentCalls = mutableStateOf<List<RecentCall>>(emptyList())
    val recentCalls: State<List<RecentCall>> = _recentCalls

    private val _savedRooms = mutableStateOf<List<SavedRoom>>(emptyList())
    val savedRooms: State<List<SavedRoom>> = _savedRooms

    private val _areSavedRoomsShownFirst = mutableStateOf(settingsStore.areSavedRoomsShownFirst)
    val areSavedRoomsShownFirst: State<Boolean> = _areSavedRoomsShownFirst

    private val _areRoomInviteNotificationsEnabled =
        mutableStateOf(settingsStore.areRoomInviteNotificationsEnabled)
    val areRoomInviteNotificationsEnabled: State<Boolean> = _areRoomInviteNotificationsEnabled

    private val _displayName = mutableStateOf(settingsStore.displayName)
    val displayName: State<String> = _displayName

    private val _roomStatuses = mutableStateOf<Map<String, RoomOccupancy>>(emptyMap())
    val roomStatuses: State<Map<String, RoomOccupancy>> = _roomStatuses

    // --- Registry-backed call state (multi-call session, Phase 5) ---
    //
    // The app migrates from a single `_session` to a [SerenadaCallRegistry]. For
    // minimal UI churn we keep exposing the SAME `sessionState` (the active call's
    // session) and `uiState` (the active call's UI state, re-derived from the
    // registry's active call), and ADD `callListState` for the switcher. The SDK
    // direct `join()` API stays for third-party consumers; only this bundled app
    // migrates. Single-call UX is preserved exactly (one call = a registry with one
    // foreground call).

    // The registry holds ONE SerenadaCore (also kept here for createRoom). The
    // core's config (host, default mic/cam, HD) is snapshotted at construction, so
    // when the registry is fully idle (no live calls) we recreate both to pick up
    // settings changes — matching the pre-migration "fresh core per join" behavior.
    // While calls are live the registry is stable (it owns the arbiter mode).
    private var core: SerenadaCore = createSdkCore(settingsStore.host)
    private var registry: SerenadaCallRegistry = SerenadaCallRegistry(core)

    private val _session = mutableStateOf<SerenadaSession?>(null)
    /** The active (foreground) call's session, or null. Unchanged UI surface. */
    val sessionState: State<SerenadaSession?> = _session

    private val _callListState = mutableStateOf(CallRegistryState())
    /** Aggregate registry state for the switcher (held call chips + switch action). */
    val callListState: State<CallRegistryState> = _callListState

    val isRemoteVideoFitCover: Boolean
        get() = settingsStore.isRemoteVideoFitCover

    // The session whose state/diagnostics currently drive `sessionState`/`uiState`.
    // Observers key off THIS (the registry active call); updates from a non-active
    // session are dropped (design "Host App Migration" / iOS test parity).
    private var activeSession: SerenadaSession?
        get() = _session.value
        set(value) {
            _session.value = value
        }

    private var activeSessionStateJob: Job? = null
    private var activeSessionStatsJob: Job? = null
    private var observedActiveCallId: CallId? = null
    private var currentRoomId: String? = null
    private var activeCallHostOverride: String? = null
    private var callStartTimeMs: Long? = null
    private var hasNotifiedPushForJoin = false

    private val pushSubscriptionManager = PushSubscriptionManager(
        context = appContext,
        apiClient = apiClient,
        settingsStore = settingsStore,
    )
    private val joinSnapshotFeature = JoinSnapshotFeature(
        apiClient = apiClient,
        handler = handler,
        captureLocalSnapshot = { onResult ->
            activeSession?.captureLocalSnapshot(onResult) ?: onResult(null)
        },
    )
    private val roomWatcher = RoomWatcher(
        okHttpClient = okHttpClient,
        handler = handler,
    )

    init {
        roomWatcher.delegate = this
        refreshRecentCalls()
        refreshSavedRooms()
        observeRegistry()
    }

    private var registrySettingsSnapshot: CoreSettingsSnapshot = CoreSettingsSnapshot.from(settingsStore)
    private var registryStateJob: Job? = null

    /**
     * Observe the registry's aggregate state (multi-call session, Phase 5): keep
     * `callListState` current, drive the foreground service from the call list, and
     * re-derive the single active-call `sessionState`/`uiState` whenever the active
     * (foreground) call changes. This is the one place the registry's view of "who
     * is active" flows into the unchanged single-call UI surface.
     */
    private fun observeRegistry() {
        registryStateJob?.cancel()
        registryStateJob = scope.launch {
            registry.state.collectLatest { state ->
                handler.post { onRegistryState(state) }
            }
        }
    }

    /**
     * Recreate the registry (and its [SerenadaCore]) ONLY when fully idle so a new
     * call picks up the latest settings (host, default mic/cam, HD). While any call
     * is live the registry must stay put (it owns the process-wide arbiter mode);
     * recreating it then would orphan live sessions. No-op if settings are unchanged.
     */
    private fun ensureFreshRegistryIfIdle() {
        if (!isRegistryIdle()) return
        val current = CoreSettingsSnapshot.from(settingsStore)
        if (current == registrySettingsSnapshot) return
        runCatching { registry.close() }
        core = createSdkCore(current.host)
        registry = SerenadaCallRegistry(core)
        registrySettingsSnapshot = current
        observeRegistry()
    }

    /** True when the registry has no non-terminal call (safe to recreate). */
    private fun isRegistryIdle(): Boolean =
        registry.state.value.calls.none { !isTerminalServiceCall(it.membershipPhase) }

    private fun onRegistryState(state: CallRegistryState) {
        _callListState.value = state

        // Drive the single-instance foreground service from the whole call list
        // (design "Foreground Service"): the service stays up while ANY non-ended
        // call exists and stops only when none remains. Held calls are summary
        // text; the active call owns the mute/end actions.
        CallService.update(appContext, state.toServiceCalls())

        // Re-subscribe the active-call observers when the foreground call changes
        // (or clears). Updates from a non-active session are dropped by the
        // `activeSession !== session` guards inside the collectors.
        val newActiveId = state.activeCallId
        if (newActiveId != observedActiveCallId) {
            observedActiveCallId = newActiveId
            bindActiveSession(newActiveId)
        }

        // A registry with no live (non-terminal) call means every call has ended:
        // reset the single-call UI to idle (the active-call screen dismisses).
        if (state.calls.none { !isTerminalServiceCall(it.membershipPhase) }) {
            if (activeSession == null && _uiState.value.phase != CallPhase.Idle &&
                _uiState.value.phase != CallPhase.Error
            ) {
                updateState(CallUiState())
            }
        }
    }

    /**
     * Terminal for foreground-service keep-alive: a call that reached [CallPhase.Idle]
     * or [CallPhase.Error] no longer holds media and does not keep the service up.
     * [CallPhase.Ending] is still live (teardown in flight).
     */
    private fun isTerminalServiceCall(phase: CallPhase): Boolean =
        phase == CallPhase.Idle || phase == CallPhase.Error

    /**
     * Point the unchanged `sessionState`/`uiState` surface at the registry's active
     * (foreground) call. Cancels the previous call's collectors and starts fresh
     * ones for [activeCallId]; a null id means no call is foreground (e.g. the
     * active call ended while held calls remain — no auto-promote, Core Invariant
     * 5), and the UI collapses to a holding/idle state.
     */
    private fun bindActiveSession(activeCallId: CallId?) {
        clearActiveSessionObservers()
        val session = activeCallId?.let { registry.session(it) }
        activeSession = session
        if (session == null) {
            // No foreground call: keep held calls connected (callListState shows
            // them) but the active-call screen has nothing to render.
            return
        }
        currentRoomId = session.roomId
        if (callStartTimeMs == null) callStartTimeMs = System.currentTimeMillis()
        hasNotifiedPushForJoin = false
        watchRecentRoomsIfNeeded()

        activeSessionStateJob =
            scope.launch {
                session.state.collectLatest { state ->
                    if (activeSession !== session) return@collectLatest
                    handler.post {
                        handleSdkSessionState(session, state)
                    }
                }
            }
        activeSessionStatsJob =
            scope.launch {
                session.diagnostics.collectLatest { diagnostics ->
                    if (activeSession !== session) return@collectLatest
                    handler.post {
                        applySdkStateToUi(session.state.value, diagnostics)
                    }
                }
            }
        applySdkStateToUi(session.state.value, session.diagnostics.value)
    }

    /** Project the registry call list into the framework-free service snapshot. */
    private fun CallRegistryState.toServiceCalls(): List<CallServiceCall> =
        calls.map { call ->
            val session = registry.session(call.callId)
            CallServiceCall(
                callId = call.callId,
                label = call.roomId.let { savedRoomNameForNotification(it) ?: it },
                isForeground = call.callId == activeCallId,
                isEnded = isTerminalServiceCall(call.membershipPhase),
                isScreenSharing = call.callId == activeCallId &&
                    session?.diagnostics?.value?.isScreenSharing == true,
            )
        }

    /** Snapshot of the settings that feed a [SerenadaCore]'s config at creation. */
    private data class CoreSettingsSnapshot(
        val host: String,
        val defaultMicEnabled: Boolean,
        val defaultCameraEnabled: Boolean,
        val hdVideoEnabled: Boolean,
    ) {
        companion object {
            fun from(settings: SettingsStore) = CoreSettingsSnapshot(
                host = settings.host,
                defaultMicEnabled = settings.isDefaultMicrophoneEnabled,
                defaultCameraEnabled = settings.isDefaultCameraEnabled,
                hdVideoEnabled = settings.isHdVideoExperimentalEnabled,
            )
        }
    }

    private fun createSdkCore(host: String): SerenadaCore {
        val transports =
            if (BuildConfig.FORCE_SSE_SIGNALING) {
                listOf(SerenadaTransport.SSE)
            } else {
                listOf(SerenadaTransport.WS, SerenadaTransport.SSE)
            }
        val core = SerenadaCore(
            config = SerenadaConfig(
                serverHost = host,
                defaultAudioEnabled = settingsStore.isDefaultMicrophoneEnabled,
                defaultVideoEnabled = settingsStore.isDefaultCameraEnabled,
                isHdVideoExperimentalEnabled = settingsStore.isHdVideoExperimentalEnabled,
                transports = transports,
                proximityMonitoringEnabled = true,
                // Bundled Android app opt-in: the SDK default remains false for
                // external integrators, but this app intentionally ships the
                // independent screen-share media path.
                enableIndependentContentVideo = INDEPENDENT_CONTENT_VIDEO_ENABLED,
            ),
            context = appContext,
        )
        core.logger = AndroidSerenadaLogger()
        return core
    }

    private fun handleSdkSessionState(session: SerenadaSession, state: CallState) {
        currentRoomId = state.roomId ?: session.roomId
        applySdkStateToUi(state, session.diagnostics.value)

        val roomId = currentRoomId
        val localCid = state.localCid
        val sessionHost = session.host
        if (!hasNotifiedPushForJoin && roomId != null && localCid != null && sessionHost != null) {
            hasNotifiedPushForJoin = true
            pushSubscriptionManager.subscribeRoom(roomId, sessionHost)
            joinSnapshotFeature.prepareSnapshotId(
                host = sessionHost,
                roomId = roomId,
                isVideoEnabled = { activeSession?.state?.value?.localVideoEnabled == true },
                isJoinAttemptActive = {
                    activeSession === session &&
                        currentRoomId == roomId &&
                        activeSession?.state?.value?.phase != CallPhase.Idle
                },
            ) { snapshotId ->
                val endpoint = pushSubscriptionManager.cachedEndpoint()
                apiClient.notifyRoom(sessionHost, roomId, localCid, snapshotId, endpoint) { result ->
                    result.onFailure { error ->
                        Log.w("CallManager", "Post-join push notify failed", error)
                    }
                }
            }
        }

        // History/teardown and the foreground service are now driven by the
        // registry (onRegistryState). When the ACTIVE call reaches a terminal phase
        // on its own (remote end / fatal error), record history once; the registry
        // runs its own serialized terminal cleanup (lease release, mode drop). NO
        // auto-promote of a held call (Core Invariant 5).
        if (state.phase == CallPhase.Idle) {
            saveCurrentCallToHistoryIfNeeded()
        }
    }

    private fun applySdkStateToUi(state: CallState, diagnostics: CallDiagnostics) {
        val previous = _uiState.value
        val statusMessageResId =
            when (state.phase) {
                CallPhase.CreatingRoom -> R.string.call_status_creating_room
                CallPhase.AwaitingPermissions,
                CallPhase.Joining -> R.string.call_status_joining_room
                CallPhase.Waiting -> R.string.call_status_waiting_for_join
                CallPhase.InCall -> R.string.call_status_in_call
                CallPhase.Ending -> previous.statusMessageResId
                CallPhase.Error,
                CallPhase.Idle -> null
            }

        updateState(
            previous.copy(
                phase = state.phase,
                roomId = state.roomId ?: currentRoomId,
                localCid = state.localCid,
                statusMessageResId = statusMessageResId,
                errorMessageResId = if (state.phase == CallPhase.Error && state.error == null) {
                    R.string.error_unknown
                } else {
                    null
                },
                errorMessageText = if (state.phase == CallPhase.Error) state.error?.displayMessage else null,
                isHost = state.isHost,
                participantCount = state.participantCount,
                localAudioEnabled = state.localAudioEnabled,
                localVideoEnabled = state.localVideoEnabled,
                remoteParticipants = state.remoteParticipants,
                connectionStatus = state.connectionStatus,
                isSignalingConnected = diagnostics.isSignalingConnected,
                iceConnectionState = diagnostics.iceConnectionState.name,
                connectionState = diagnostics.peerConnectionState.name,
                signalingState = diagnostics.rtcSignalingState.name,
                activeTransport = diagnostics.activeTransport,
                realtimeCallStats = diagnostics.realtimeStats,
                isFrontCamera = diagnostics.isFrontCamera,
                isScreenSharing = diagnostics.isScreenSharing,
                localCameraMode = state.localCameraMode,
                isFlashAvailable = diagnostics.isFlashAvailable,
                isFlashEnabled = diagnostics.isFlashEnabled,
                remoteContentCid = diagnostics.remoteContentCid,
                remoteContentType = diagnostics.remoteContentType,
            ),
        )
    }

    private fun clearActiveSessionObservers() {
        activeSessionStateJob?.cancel()
        activeSessionStateJob = null
        activeSessionStatsJob?.cancel()
        activeSessionStatsJob = null
    }

    fun updateServerHost(host: String) {
        val trimmed = host.trim().ifBlank { SettingsStore.DEFAULT_HOST }
        val changed = trimmed != _serverHost.value
        settingsStore.host = trimmed
        _serverHost.value = trimmed
        if (changed) {
            roomWatcher.stop()
            syncSavedRoomPushSubscriptions(_savedRooms.value)
            refreshWatchedRooms()
        }
    }

    fun validateServerHost(host: String, onResult: (Result<String>) -> Unit) {
        val normalized = host.trim().ifBlank { SettingsStore.DEFAULT_HOST }
        coreApiClient.validateServerHost(normalized) { result ->
            handler.post {
                onResult(result.map { normalized })
            }
        }
    }

    fun updateLanguage(language: String) {
        val normalized = SettingsStore.normalizeLanguage(language)
        if (normalized == _selectedLanguage.value) return
        settingsStore.language = normalized
        _selectedLanguage.value = normalized
        AppLocaleManager.applyLanguage(normalized)
    }

    fun updateDefaultCamera(enabled: Boolean) {
        settingsStore.isDefaultCameraEnabled = enabled
        _isDefaultCameraEnabled.value = enabled
    }

    fun updateDefaultMicrophone(enabled: Boolean) {
        settingsStore.isDefaultMicrophoneEnabled = enabled
        _isDefaultMicrophoneEnabled.value = enabled
    }

    fun updateHdVideoExperimental(enabled: Boolean) {
        settingsStore.isHdVideoExperimentalEnabled = enabled
        _isHdVideoExperimentalEnabled.value = enabled
    }

    fun updateCallUiVariant(variant: SerenadaCallUiVariant) {
        settingsStore.callUiVariant = variant
        _callUiVariant.value = variant
    }

    fun updateSavedRoomsShownFirst(enabled: Boolean) {
        settingsStore.areSavedRoomsShownFirst = enabled
        _areSavedRoomsShownFirst.value = enabled
    }

    fun updateRoomInviteNotifications(enabled: Boolean) {
        settingsStore.areRoomInviteNotificationsEnabled = enabled
        _areRoomInviteNotificationsEnabled.value = enabled
    }

    fun updateDisplayName(name: String) {
        settingsStore.displayName = name
        _displayName.value = name
    }

    fun updateRemoteVideoFitCover(isCover: Boolean) {
        settingsStore.isRemoteVideoFitCover = isCover
    }

    fun inviteToCurrentRoom(onResult: (Result<Unit>) -> Unit) {
        val roomId = currentRoomId?.trim().orEmpty()
        if (roomId.isBlank()) {
            handler.post {
                onResult(Result.failure(IllegalStateException("No active room")))
            }
            return
        }
        val host = currentSignalingHost()
        val endpoint = pushSubscriptionManager.cachedEndpoint()
        apiClient.sendPushInvite(host, roomId, endpoint) { result ->
            handler.post {
                onResult(result)
            }
        }
    }

    fun saveRoom(roomId: String, name: String, host: String? = null) {
        val cleanRoomId = roomId.trim()
        val cleanName = normalizeSavedRoomName(name) ?: return
        if (!isValidRoomId(cleanRoomId)) return
        val existingRoom = _savedRooms.value.firstOrNull { it.roomId == cleanRoomId }
        val recentHost = _recentCalls.value.firstOrNull { it.roomId == cleanRoomId }?.host
        val resolvedHost = normalizeHostValue(host)
            ?: existingRoom?.host
            ?: recentHost
            ?: serverHost.value
        savedRoomStore.saveRoom(
            SavedRoom(
                roomId = cleanRoomId,
                name = cleanName,
                createdAt = System.currentTimeMillis(),
                host = resolvedHost,
            ),
        )
        refreshSavedRooms()
    }

    fun joinSavedRoom(room: SavedRoom) {
        joinRoom(room.roomId, hostOverrideOrNull(room.host))
    }

    fun joinRecentCall(call: RecentCall) {
        joinRoom(call.roomId, hostOverrideOrNull(call.host))
    }

    fun removeSavedRoom(roomId: String) {
        savedRoomStore.removeRoom(roomId)
        refreshSavedRooms()
    }

    fun createSavedRoomInviteLink(roomName: String, hostInput: String, onResult: (Result<String>) -> Unit) {
        val normalizedName = normalizeSavedRoomName(roomName)
        if (normalizedName == null) {
            handler.post {
                onResult(
                    Result.failure(
                        IllegalArgumentException(appContext.getString(R.string.error_invalid_saved_room_name)),
                    ),
                )
            }
            return
        }

        val targetHost = hostInput.trim().ifBlank { serverHost.value }
        val normalizedHost = normalizeHostValue(targetHost)
        if (normalizedHost == null) {
            handler.post {
                onResult(
                    Result.failure(
                        IllegalArgumentException(appContext.getString(R.string.settings_error_invalid_server_host)),
                    ),
                )
            }
            return
        }
        coreApiClient.createRoomId(normalizedHost) { result ->
            handler.post {
                result
                    .onSuccess { roomId ->
                        saveRoom(roomId, normalizedName, normalizedHost)
                        val link = buildSavedRoomInviteLink(normalizedHost, roomId, normalizedName)
                        onResult(Result.success(link))
                    }
                    .onFailure { onResult(Result.failure(it)) }
            }
        }
    }

    fun handleDeepLink(uri: Uri) {
        val deepLinkTarget = parseDeepLinkTarget(uri) ?: return
        val hostPolicy = resolveDeepLinkHostPolicy(deepLinkTarget.host)
        if (deepLinkTarget.action == DeepLinkAction.SaveRoom) {
            hostPolicy.persistedHost?.let { updateServerHost(it) }
            val roomName = deepLinkTarget.savedRoomName ?: deepLinkTarget.roomId
            saveRoom(deepLinkTarget.roomId, roomName, deepLinkTarget.host)
            return
        }

        val state = _uiState.value
        val roomId = deepLinkTarget.roomId
        val isSameActiveRoom = (state.roomId == roomId || currentRoomId == roomId) &&
            state.phase != CallPhase.Idle &&
            state.phase != CallPhase.Error &&
            state.phase != CallPhase.Ending
        if (isSameActiveRoom) {
            Log.d("CallManager", "Ignoring duplicate deep link for active room $roomId")
            return
        }
        hostPolicy.persistedHost?.let { updateServerHost(it) }
        joinRoom(roomId, hostPolicy.oneOffHost)
    }

    fun joinFromInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            updateState(
                _uiState.value.copy(
                    phase = CallPhase.Error,
                    errorMessageResId = R.string.error_enter_room_or_id,
                    errorMessageText = null,
                ),
            )
            return
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        if (uri != null && uri.scheme != null && uri.host != null) {
            val deepLinkTarget = parseDeepLinkTarget(uri)
            if (deepLinkTarget != null) {
                val hostPolicy = resolveDeepLinkHostPolicy(deepLinkTarget.host)
                hostPolicy.persistedHost?.let { updateServerHost(it) }
                if (deepLinkTarget.action == DeepLinkAction.SaveRoom) {
                    val roomName = deepLinkTarget.savedRoomName ?: deepLinkTarget.roomId
                    saveRoom(deepLinkTarget.roomId, roomName, deepLinkTarget.host)
                } else {
                    joinRoom(deepLinkTarget.roomId, hostPolicy.oneOffHost)
                }
                return
            }
        }
        joinRoom(trimmed)
    }

    private fun parseDeepLinkTarget(uri: Uri): DeepLinkTarget? {
        val roomId = extractRoomId(uri) ?: return null
        if (!isValidRoomId(roomId)) return null
        val savedRoomName = normalizeSavedRoomName(uri.getQueryParameter("name"))
        val action =
            when {
                savedRoomName != null -> DeepLinkAction.SaveRoom
                else -> DeepLinkAction.Join
            }

        return DeepLinkTarget(
            action = action,
            roomId = roomId,
            host = normalizeHostValue(uri.getQueryParameter("host")) ?: normalizeHostValue(uri.authority),
            savedRoomName = savedRoomName,
        )
    }

    private fun extractRoomId(uri: Uri): String? {
        return uri.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun buildSavedRoomInviteLink(host: String, roomId: String, roomName: String): String {
        val normalizedHost = normalizeHostValue(host) ?: host
        val appLinkHost =
            if (normalizedHost == SettingsStore.HOST_RU) {
                SettingsStore.HOST_RU
            } else {
                SettingsStore.DEFAULT_HOST
            }
        return Uri.Builder()
            .scheme("https")
            .authority(appLinkHost)
            .appendPath("call")
            .appendPath(roomId)
            .appendQueryParameter("host", normalizedHost)
            .appendQueryParameter("name", roomName)
            .build()
            .toString()
    }

    private fun normalizeHostValue(hostInput: String?): String? {
        val raw = hostInput?.trim().orEmpty()
        if (raw.isBlank()) return null
        val withScheme =
            if (raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)
            ) {
                raw
            } else {
                "https://$raw"
            }
        val parsed = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        if (!parsed.userInfo.isNullOrBlank()) return null
        if (!parsed.query.isNullOrBlank()) return null
        if (!parsed.fragment.isNullOrBlank()) return null
        val path = parsed.path.orEmpty()
        if (path.isNotBlank() && path != "/") return null

        val host = parsed.host?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (host.isBlank()) return null
        val port = parsed.port
        if (port == -1) return host
        if (port <= 0 || port > 65535) return null
        return "$host:$port"
    }

    private val resolvedDisplayName: String?
        get() = settingsStore.displayName.ifBlank { null }

    private fun resolveDeepLinkHostPolicy(host: String?): DeepLinkHostPolicy {
        val normalized = normalizeHostValue(host) ?: return DeepLinkHostPolicy()
        return if (isTrustedDeepLinkHost(normalized)) {
            DeepLinkHostPolicy(persistedHost = normalized)
        } else {
            DeepLinkHostPolicy(oneOffHost = normalized)
        }
    }

    private fun isTrustedDeepLinkHost(host: String): Boolean {
        val canonical = host.lowercase(Locale.ROOT)
        return canonical == SettingsStore.DEFAULT_HOST || canonical == SettingsStore.HOST_RU
    }

    private fun normalizeSavedRoomName(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        return trimmed.take(MAX_SAVED_ROOM_NAME_LENGTH)
    }

    private fun isValidRoomId(roomId: String): Boolean = ROOM_ID_REGEX.matches(roomId)

    fun startNewCall() {
        // Single-call guard preserved: when idle the registry has no live call.
        if (_uiState.value.phase != CallPhase.Idle || activeSession != null) return
        ensureFreshRegistryIfIdle()
        activeCallHostOverride = null
        updateState(
            _uiState.value.copy(
                phase = CallPhase.CreatingRoom,
                statusMessageResId = R.string.call_status_creating_room,
            ),
        )
        scope.launch {
            try {
                val created = core.createRoom()
                // joinAndSwitch: held join then foreground. With no prior active call
                // this is single-call; with one already active it holds the old call
                // first (Core Invariant 4 preflight) — the multi-call new-call flow.
                val result = registry.joinAndSwitch(RoomRef.Id(created.roomId))
                handleJoinAndSwitchResult(result)
            } catch (error: Throwable) {
                val fallback = appContext.getString(R.string.error_failed_create_room)
                val message = error.message?.ifBlank { null } ?: fallback
                updateState(
                    _uiState.value.copy(
                        phase = CallPhase.Error,
                        errorMessageResId = if (message == fallback) R.string.error_failed_create_room else null,
                        errorMessageText = if (message == fallback) null else message,
                    ),
                )
            }
        }
    }

    fun joinRoom(roomId: String, oneOffHost: String? = null) {
        if (roomId.isBlank()) {
            updateState(
                _uiState.value.copy(
                    phase = CallPhase.Error,
                    errorMessageResId = R.string.error_invalid_room_id,
                    errorMessageText = null,
                ),
            )
            return
        }
        if (savedRoomStore.markRoomJoined(roomId)) {
            refreshSavedRooms()
        }
        ensureFreshRegistryIfIdle()
        val resolvedHost = normalizeHostValue(oneOffHost) ?: serverHost.value
        activeCallHostOverride = normalizeHostValue(oneOffHost)
        // Show the joining state immediately (the held room join runs async).
        updateState(
            _uiState.value.copy(
                phase = CallPhase.Joining,
                statusMessageResId = R.string.call_status_joining_room,
            ),
        )
        scope.launch {
            val result = registry.joinAndSwitch(RoomRef.Id(roomId, serverHost = resolvedHost))
            handleJoinAndSwitchResult(result)
        }
    }

    private fun handleJoinAndSwitchResult(result: app.serenada.core.JoinAndSwitchResult) {
        when (result) {
            is app.serenada.core.JoinAndSwitchResult.Active -> Unit // onRegistryState binds the active call.
            is app.serenada.core.JoinAndSwitchResult.NeedsPermission -> {
                // Held call exists; the host already gates the call on runtime
                // permissions (runWithCallPermissions), so this is unexpected here.
                // Surface it rather than silently stranding the call held.
                Log.w("CallManager", "joinAndSwitch needs permission for ${result.callId}")
            }
            is app.serenada.core.JoinAndSwitchResult.Failed -> {
                if (activeSession == null) {
                    val message = result.error.message
                    updateState(
                        _uiState.value.copy(
                            phase = CallPhase.Error,
                            errorMessageResId = null,
                            errorMessageText = message,
                        ),
                    )
                }
                result.callId?.let { id -> scope.launch { registry.dismissCall(id) } }
            }
        }
    }

    /**
     * Human-friendly label for a managed call (saved-room name when known, else the
     * room id), used by the switcher UI. Pure read; safe to call from Compose.
     */
    fun callLabel(roomId: String): String =
        savedRoomNameForNotification(roomId) ?: roomId

    /** Switch the foreground lease to [callId] (switcher UI + notification action). */
    fun switchToCall(callId: CallId) {
        scope.launch { registry.switchToCall(callId) }
    }

    /** Hold [callId] (drop it out of foreground without leaving). No auto-promote. */
    fun holdCall(callId: CallId) {
        scope.launch { registry.holdCall(callId) }
    }

    fun leaveCall() {
        val target = activeCallIdOrNull()
        if (target == null) {
            if (_uiState.value.phase != CallPhase.Idle) {
                updateState(CallUiState())
            }
            return
        }
        scope.launch { registry.leaveCall(target) }
    }

    /** Leave [callId] (switcher per-call leave). */
    fun leaveCall(callId: CallId) {
        scope.launch { registry.leaveCall(callId) }
    }

    /**
     * Leave EVERY managed call (design "Foreground Service": onTaskRemoved leaves
     * all registry calls, not just the active one).
     */
    fun leaveAllCalls() {
        val ids = registry.state.value.calls.map { it.callId }
        scope.launch { ids.forEach { registry.leaveCall(it) } }
    }

    fun dismissError() {
        if (_uiState.value.phase == CallPhase.Error) {
            activeCallIdOrNull()?.let { id -> scope.launch { registry.endCall(id) } }
            registry.state.value.calls
                .filter { it.membershipPhase == CallPhase.Error }
                .forEach { call -> scope.launch { registry.dismissCall(call.callId) } }
            updateState(CallUiState())
            refreshRecentCalls()
            refreshSavedRooms()
        }
    }

    private fun activeCallIdOrNull(): CallId? = registry.state.value.activeCallId

    fun removeRecentCall(roomId: String) {
        recentCallStore.removeCall(roomId)
        refreshRecentCalls()
    }

    fun endCall() {
        val target = activeCallIdOrNull()
        if (target == null) {
            if (_uiState.value.phase != CallPhase.Idle) {
                updateState(CallUiState())
            }
            return
        }
        scope.launch { registry.endCall(target) }
    }

    fun toggleAudio() {
        activeSession?.toggleAudio()
    }

    fun toggleVideo() {
        activeSession?.toggleVideo()
    }

    fun toggleFlashlight() {
        activeSession?.toggleFlashlight()
    }

    fun flipCamera() {
        activeSession?.flipCamera()
    }

    fun adjustLocalCameraZoom(scaleFactor: Float) {
        activeSession?.adjustLocalCameraZoom(scaleFactor)
    }

    fun startScreenShare(intent: Intent) {
        // Screen share is foreground-only (design "Screen Share During Switch"): it
        // belongs to the active call. Arm the mediaProjection FGS type, then start
        // capture once the service has entered the foreground with that type.
        if (_uiState.value.isScreenSharing) return
        val session = activeSession
        if (session == null) {
            Log.w("CallManager", "Failed to start screen sharing: no active call")
            return
        }
        CallService.startScreenShareForeground(appContext)
        startScreenShareWhenForegroundReady(intent, session, attemptsRemaining = 15)
    }

    fun stopScreenShare() {
        if (!_uiState.value.isScreenSharing) return
        activeSession?.stopScreenShare()
        // Drop the mediaProjection FGS type WITHOUT tearing down the service
        // (design "Foreground Service"): the next foreground update sheds the type.
        CallService.clearPendingMediaProjection(appContext)
    }

    private fun startScreenShareWhenForegroundReady(
        intent: Intent,
        session: SerenadaSession,
        attemptsRemaining: Int,
    ) {
        // Guard against the active call changing/ending while we poll.
        if (activeSession !== session) {
            CallService.clearPendingMediaProjection(appContext)
            return
        }
        if (CallService.isMediaProjectionForegroundActive()) {
            session.startScreenShare(intent)
            // Refresh so the registry-derived snapshot now reports isScreenSharing,
            // keeping the mediaProjection FGS type after the pending flag clears.
            CallService.update(appContext, registry.state.value.toServiceCalls())
            return
        }
        if (attemptsRemaining <= 0) {
            CallService.clearPendingMediaProjection(appContext)
            Log.w("CallManager", "Failed to start screen sharing: media projection foreground type not ready")
            return
        }
        handler.postDelayed(
            { startScreenShareWhenForegroundReady(intent, session, attemptsRemaining - 1) },
            50,
        )
    }

    private fun updateState(state: CallUiState) {
        _uiState.value = state
    }

    private fun refreshRecentCalls() {
        val calls = recentCallStore.getRecentCalls()
        if (calls.any { it.host == null }) {
            val host = serverHost.value
            val patched = calls.map { if (it.host == null) it.copy(host = host) else it }
            calls.forEachIndexed { i, call -> if (call.host == null) recentCallStore.saveCall(patched[i]) }
            _recentCalls.value = patched
        } else {
            _recentCalls.value = calls
        }
        refreshWatchedRooms()
    }

    private fun refreshSavedRooms() {
        val rooms = savedRoomStore.getSavedRooms()
        if (rooms.any { it.host == null }) {
            val host = serverHost.value
            val patched = rooms.map { if (it.host == null) it.copy(host = host) else it }
            rooms.forEachIndexed { i, room -> if (room.host == null) savedRoomStore.saveRoom(patched[i]) }
            _savedRooms.value = patched
        } else {
            _savedRooms.value = rooms
        }
        syncSavedRoomPushSubscriptions(_savedRooms.value)
        refreshWatchedRooms()
    }

    private fun syncSavedRoomPushSubscriptions(rooms: List<SavedRoom>) {
        val host = serverHost.value
        rooms
            .filter { isCurrentServerHost(it.host) }
            .forEach { room ->
                pushSubscriptionManager.subscribeRoom(room.roomId, host)
            }
    }

    private fun savedRoomNameForNotification(roomId: String): String? {
        return _savedRooms.value.firstOrNull { it.roomId == roomId }?.name
    }

    private fun isCurrentServerHost(host: String?): Boolean {
        val value = host ?: return true
        return value.equals(serverHost.value, ignoreCase = true)
    }

    private fun hostOverrideOrNull(host: String?): String? {
        return normalizeHostValue(host)?.takeUnless { isCurrentServerHost(it) }
    }

    private fun currentSignalingHost(): String {
        return activeSession?.host ?: activeCallHostOverride ?: serverHost.value
    }

    private fun refreshWatchedRooms() {
        val mergedRoomIds = LinkedHashSet<String>()
        _savedRooms.value
            .filter { isCurrentServerHost(it.host) }
            .forEach { mergedRoomIds.add(it.roomId) }
        _recentCalls.value
            .filter { isCurrentServerHost(it.host) }
            .forEach { mergedRoomIds.add(it.roomId) }
        val watchedRoomIds = mergedRoomIds.toList()
        val watched = watchedRoomIds.toSet()
        _roomStatuses.value = _roomStatuses.value.filterKeys { watched.contains(it) }
        roomWatcher.watchRooms(roomIds = watchedRoomIds, host = serverHost.value)
    }

    private fun watchRecentRoomsIfNeeded() {
        refreshWatchedRooms()
    }

    private fun saveCurrentCallToHistoryIfNeeded() {
        val roomId = currentRoomId ?: return
        val startTime = callStartTimeMs ?: return
        val durationSeconds = ((System.currentTimeMillis() - startTime) / 1000L)
            .coerceAtLeast(0L)
            .toInt()
        recentCallStore.saveCall(
            RecentCall(
                roomId = roomId,
                startTime = startTime,
                durationSeconds = durationSeconds,
                host = currentSignalingHost(),
            ),
        )
        callStartTimeMs = null
        refreshRecentCalls()
    }

    private enum class DeepLinkAction {
        Join,
        SaveRoom,
    }

    private data class DeepLinkTarget(
        val action: DeepLinkAction,
        val roomId: String,
        val host: String?,
        val savedRoomName: String?,
    )

    private data class DeepLinkHostPolicy(
        val persistedHost: String? = null,
        val oneOffHost: String? = null,
    )

    private companion object {
        const val MAX_SAVED_ROOM_NAME_LENGTH = 120
        val ROOM_ID_REGEX = Regex("^[A-Za-z0-9_-]{27}$")
    }

    override fun roomWatcher(
        watcher: RoomWatcher,
        didUpdateStatuses: Map<String, RoomOccupancy>,
    ) {
        _roomStatuses.value = didUpdateStatuses
    }
}
