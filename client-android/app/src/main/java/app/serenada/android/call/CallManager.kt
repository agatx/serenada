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
import app.serenada.core.CallDiagnostics
import app.serenada.core.CallState
import app.serenada.core.RoomOccupancy
import app.serenada.core.RoomWatcher
import app.serenada.core.RoomWatcherDelegate
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

    private val _recentCalls = mutableStateOf<List<RecentCall>>(emptyList())
    val recentCalls: State<List<RecentCall>> = _recentCalls

    private val _savedRooms = mutableStateOf<List<SavedRoom>>(emptyList())
    val savedRooms: State<List<SavedRoom>> = _savedRooms

    private val _areSavedRoomsShownFirst = mutableStateOf(settingsStore.areSavedRoomsShownFirst)
    val areSavedRoomsShownFirst: State<Boolean> = _areSavedRoomsShownFirst

    private val _areRoomInviteNotificationsEnabled =
        mutableStateOf(settingsStore.areRoomInviteNotificationsEnabled)
    val areRoomInviteNotificationsEnabled: State<Boolean> = _areRoomInviteNotificationsEnabled

    private val _roomStatuses = mutableStateOf<Map<String, RoomOccupancy>>(emptyMap())
    val roomStatuses: State<Map<String, RoomOccupancy>> = _roomStatuses

    private val _session = mutableStateOf<SerenadaSession?>(null)
    val sessionState: State<SerenadaSession?> = _session

    val isRemoteVideoFitCover: Boolean
        get() = settingsStore.isRemoteVideoFitCover

    private var activeSession: SerenadaSession?
        get() = _session.value
        set(value) {
            _session.value = value
        }

    private var activeSessionStateJob: Job? = null
    private var activeSessionStatsJob: Job? = null
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
            ),
            context = appContext,
        )
        core.logger = AndroidSerenadaLogger()
        return core
    }

    private fun beginSdkSession(session: SerenadaSession, hostOverride: String? = null) {
        clearActiveSessionObservers()
        activeSession = session
        activeCallHostOverride = normalizeHostValue(hostOverride)
        currentRoomId = session.roomId
        callStartTimeMs = System.currentTimeMillis()
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
        CallService.start(
            appContext,
            session.roomId,
            roomName = savedRoomNameForNotification(session.roomId),
        )
    }

    private fun handleSdkSessionState(session: SerenadaSession, state: CallState) {
        currentRoomId = state.roomId ?: session.roomId
        applySdkStateToUi(state, session.diagnostics.value)

        val roomId = currentRoomId
        val localCid = state.localCid
        if (!hasNotifiedPushForJoin && roomId != null && localCid != null) {
            hasNotifiedPushForJoin = true
            pushSubscriptionManager.subscribeRoom(roomId, session.host)
            joinSnapshotFeature.prepareSnapshotId(
                host = session.host,
                roomId = roomId,
                isVideoEnabled = { activeSession?.state?.value?.localVideoEnabled == true },
                isJoinAttemptActive = {
                    activeSession === session &&
                        currentRoomId == roomId &&
                        activeSession?.state?.value?.phase != CallPhase.Idle
                },
            ) { snapshotId ->
                val endpoint = pushSubscriptionManager.cachedEndpoint()
                apiClient.notifyRoom(session.host, roomId, localCid, snapshotId, endpoint) { result ->
                    result.onFailure { error ->
                        Log.w("CallManager", "Post-join push notify failed", error)
                    }
                }
            }
        }

        when (state.phase) {
            CallPhase.Idle -> finishSdkSession(session, saveHistory = true)
            CallPhase.Error -> CallService.stop(appContext)
            else -> {
                roomId?.let { rid ->
                    CallService.start(appContext, rid, roomName = savedRoomNameForNotification(rid))
                }
            }
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

    private fun finishSdkSession(session: SerenadaSession, saveHistory: Boolean) {
        if (activeSession !== session) return
        if (saveHistory) {
            saveCurrentCallToHistoryIfNeeded()
        }
        clearActiveSessionObservers()
        activeSession = null
        CallService.stop(appContext)
        currentRoomId = null
        activeCallHostOverride = null
        callStartTimeMs = null
        hasNotifiedPushForJoin = false
        updateState(CallUiState())
        refreshRecentCalls()
        refreshSavedRooms()
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

    fun updateSavedRoomsShownFirst(enabled: Boolean) {
        settingsStore.areSavedRoomsShownFirst = enabled
        _areSavedRoomsShownFirst.value = enabled
    }

    fun updateRoomInviteNotifications(enabled: Boolean) {
        settingsStore.areRoomInviteNotificationsEnabled = enabled
        _areRoomInviteNotificationsEnabled.value = enabled
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
        if (_uiState.value.phase != CallPhase.Idle || activeSession != null) return
        updateState(
            _uiState.value.copy(
                phase = CallPhase.CreatingRoom,
                statusMessageResId = R.string.call_status_creating_room,
            ),
        )
        scope.launch {
            try {
                val created = createSdkCore(serverHost.value).createRoom()
                beginSdkSession(created.session)
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
        val resolvedHost = normalizeHostValue(oneOffHost) ?: serverHost.value
        val session = createSdkCore(resolvedHost).join(roomId, resolvedHost)
        beginSdkSession(session, hostOverride = oneOffHost)
    }

    fun leaveCall() {
        activeSession?.leave() ?: run {
            if (_uiState.value.phase != CallPhase.Idle) {
                updateState(CallUiState())
            }
        }
    }

    fun dismissError() {
        if (_uiState.value.phase == CallPhase.Error) {
            activeSession?.let { finishSdkSession(it, saveHistory = false) }
            updateState(CallUiState())
            refreshRecentCalls()
            refreshSavedRooms()
        }
    }

    fun removeRecentCall(roomId: String) {
        recentCallStore.removeCall(roomId)
        refreshRecentCalls()
    }

    fun endCall() {
        leaveCall()
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
        if (_uiState.value.isScreenSharing) return
        val roomId = currentRoomId
        if (roomId == null) {
            Log.w("CallManager", "Failed to start screen sharing: roomId is missing")
            return
        }
        CallService.start(
            appContext,
            roomId,
            roomName = savedRoomNameForNotification(roomId),
            includeMediaProjection = true,
        )
        startScreenShareWhenForegroundReady(intent, roomId, attemptsRemaining = 15)
    }

    fun stopScreenShare() {
        if (!_uiState.value.isScreenSharing) return
        activeSession?.stopScreenShare()
        currentRoomId?.let { roomId ->
            CallService.start(appContext, roomId, roomName = savedRoomNameForNotification(roomId))
        }
    }

    private fun startScreenShareWhenForegroundReady(intent: Intent, roomId: String, attemptsRemaining: Int) {
        if (CallService.isMediaProjectionForegroundActive()) {
            activeSession?.startScreenShare(intent)
            return
        }
        if (attemptsRemaining <= 0) {
            CallService.start(appContext, roomId, roomName = savedRoomNameForNotification(roomId))
            Log.w("CallManager", "Failed to start screen sharing: media projection foreground type not ready")
            return
        }
        handler.postDelayed(
            { startScreenShareWhenForegroundReady(intent, roomId, attemptsRemaining - 1) },
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
