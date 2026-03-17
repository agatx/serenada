package app.serenada.android.call

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.serenada.android.R
import app.serenada.android.data.RecentCall
import app.serenada.android.data.RecentCallStore
import app.serenada.android.data.SavedRoom
import app.serenada.android.data.SavedRoomStore
import app.serenada.android.data.SettingsStore
import app.serenada.android.i18n.AppLocaleManager
import app.serenada.android.network.ApiClient
import app.serenada.android.network.TurnCredentials
import app.serenada.android.push.PushSubscriptionManager
import app.serenada.android.service.CallService
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.util.Locale
import java.util.concurrent.Executors

class CallManager(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val webRtcStatsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "webrtc-stats")
    }
    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = ApiClient(okHttpClient)
    private val settingsStore = SettingsStore(appContext)
    private val recentCallStore = RecentCallStore(appContext)
    private val savedRoomStore = SavedRoomStore(appContext)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (_uiState.value.phase == CallPhase.InCall) {
                    val state = _uiState.value
                    if (isConnectionDegraded(state)) {
                        markConnectionDegraded()
                    }
                    // Keep opportunistic ICE restart on network transitions so the call can
                    // migrate to a better path (for example mobile -> Wi-Fi) without forcing UI
                    // into degraded mode when media is still flowing.
                    scheduleIceRestart("network-online", 0)
                }
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                if (_uiState.value.phase == CallPhase.InCall) {
                    val state = _uiState.value
                    val hasAnyActiveNetwork = connectivityManager.activeNetwork != null
                    if (!hasAnyActiveNetwork || isConnectionDegraded(state)) {
                        markConnectionDegraded()
                    }
                }
            }
        }
    }

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

    private val _roomStatuses = mutableStateOf<Map<String, RoomStatus>>(emptyMap())
    val roomStatuses: State<Map<String, RoomStatus>> = _roomStatuses

    private var currentRoomId: String? = null
    private var activeCallHostOverride: String? = null
    private var clientId: String? = null
    private var hostCid: String? = null
    private var currentRoomState: RoomState? = null
    private var callStartTimeMs: Long? = null
    private var watchedRoomIds: List<String> = emptyList()
    private var pendingJoinRoom: String? = null
    private var hasNotifiedPushForJoin = false
    // All mutable state below is accessed exclusively from the handler thread — no synchronization needed.
    private var joinAttemptSerial = 0L
    private var reconnectAttempts = 0
    private var connectionStatusRetryingRunnable: Runnable? = null
    private var joinTimeoutRunnable: Runnable? = null
    private var joinKickstartRunnable: Runnable? = null
    private var joinRecoveryRunnable: Runnable? = null
    private var turnRefreshRunnable: Runnable? = null
    private var remoteVideoStatePollRunnable: Runnable? = null
    private var webrtcStatsRequestInFlight = false
    private var lastWebRtcStatsPollAtMs = 0L
    private val pendingMessages = java.util.ArrayDeque<SignalingMessage>()
    private val peerSlots = mutableMapOf<String, PeerConnectionSlot>()
    private var reconnectToken: String? = null
    private var turnTokenTTLMs: Long? = null
    private var hasJoinSignalStarted = false
    private var hasJoinAcknowledged = false
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiPerformanceLock: WifiManager.WifiLock? = null
    private var userPreferredVideoEnabled = true
    private var isVideoPausedByProximity = false
    private val callAudioSessionController = CallAudioSessionController(
        context = appContext,
        handler = handler,
        onProximityChanged = { near ->
            Log.d("CallManager", "Proximity sensor changed: ${if (near) "NEAR" else "FAR"}")
        },
        onAudioEnvironmentChanged = {
            applyLocalVideoPreference()
        }
    )

    private var webRtcEngine = buildWebRtcEngine()
    private val pushSubscriptionManager = PushSubscriptionManager(
        context = appContext,
        apiClient = apiClient,
        settingsStore = settingsStore
    )
    private val joinSnapshotFeature = JoinSnapshotFeature(
        apiClient = apiClient,
        handler = handler,
        attachLocalSink = { sink -> webRtcEngine.attachLocalSink(sink) },
        detachLocalSink = { sink -> webRtcEngine.detachLocalSink(sink) }
    )

    private val signalingClient = SignalingClient(okHttpClient, handler, object : SignalingClient.Listener {
        override fun onOpen(activeTransport: String) {
            reconnectAttempts = 0
            updateState(
                _uiState.value.copy(
                    isSignalingConnected = true,
                    activeTransport = activeTransport
                )
            )
            updateConnectionStatusFromSignals()
            pendingJoinRoom?.let { join ->
                pendingJoinRoom = null
                sendJoin(join)
            }
            sendWatchRoomsIfNeeded()
        }

        override fun onMessage(message: SignalingMessage) {
            handleSignalingMessage(message)
        }

        override fun onClosed(reason: String) {
            val shouldReconnect = shouldReconnectSignaling()
            updateState(_uiState.value.copy(
                isSignalingConnected = false,
                activeTransport = null
            ))
            updateConnectionStatusFromSignals()
            if (shouldReconnect) {
                scheduleReconnect()
            }
        }
    })

    init {
        registerConnectivityListener()
        refreshRecentCalls()
        refreshSavedRooms()
    }

    private fun buildWebRtcEngine(): WebRtcEngine {
        return WebRtcEngine(
            context = appContext,
            onCameraFacingChanged = { isFront ->
                handler.post {
                    updateState(_uiState.value.copy(isFrontCamera = isFront))
                }
            },
            onCameraModeChanged = { mode ->
                handler.post {
                    val previousMode = _uiState.value.localCameraMode
                    updateState(_uiState.value.copy(localCameraMode = mode))
                    // Broadcast content state when entering/leaving world/composite mode
                    val isContent = mode.isContentMode
                    val wasContent = previousMode.isContentMode
                    if (isContent) {
                        val type = if (mode == LocalCameraMode.WORLD) ContentTypeWire.WORLD_CAMERA else ContentTypeWire.COMPOSITE_CAMERA
                        broadcastContentState(true, type)
                    } else if (wasContent) {
                        broadcastContentState(false)
                    }
                }
            },
            onFlashlightStateChanged = { available, enabled ->
                handler.post {
                    updateState(
                        _uiState.value.copy(
                            isFlashAvailable = available,
                            isFlashEnabled = enabled
                        )
                    )
                }
            },
            onScreenShareStopped = {
                handler.post {
                    if (_uiState.value.isScreenSharing) {
                        updateState(_uiState.value.copy(isScreenSharing = false))
                        broadcastContentState(false)
                    }
                    applyLocalVideoPreference()
                }
            },
            isHdVideoExperimentalEnabled = settingsStore.isHdVideoExperimentalEnabled
        )
    }

    private fun recreateWebRtcEngineForNewCall() {
        runCatching { webRtcEngine.release() }
        webRtcEngine = buildWebRtcEngine()
    }

    private fun registerConnectivityListener() {
        try {
            connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        } catch (e: Exception) {
            Log.e("CallManager", "Failed to register network callback", e)
        }
    }

    private fun shouldReconnectSignaling(): Boolean {
        return currentRoomId != null || watchedRoomIds.isNotEmpty()
    }

    private fun clearConnectionStatusRetryingTimer() {
        connectionStatusRetryingRunnable?.let { handler.removeCallbacks(it) }
        connectionStatusRetryingRunnable = null
    }

    private fun vibrateOnRetrying() {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) {
            Log.d("CallManager", "Retrying haptic skipped: no vibrator hardware")
            return
        }
        val hapticFeedbackEnabled =
            runCatching {
                Settings.System.getInt(
                    appContext.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    -1
                )
            }.getOrDefault(-1)
        Log.d(
            "CallManager",
            "Retrying haptic attempt (hapticFeedbackEnabled=$hapticFeedbackEnabled)"
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use an explicit, stronger pulse to remain noticeable on devices with subtle defaults.
                vibrator.vibrate(VibrationEffect.createOneShot(160, 255))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(160)
            }
        }.onSuccess {
            Log.d("CallManager", "Retrying haptic triggered")
        }.onFailure { error ->
            Log.w("CallManager", "Failed to trigger retrying haptic", error)
        }
    }

    private fun setConnectionStatus(status: ConnectionStatus) {
        val current = _uiState.value.connectionStatus
        if (current == status) return
        Log.d("CallManager", "Connection status: $current -> $status")
        updateState(_uiState.value.copy(connectionStatus = status))
        if (current != ConnectionStatus.Retrying && status == ConnectionStatus.Retrying) {
            vibrateOnRetrying()
        }
    }

    private fun resetConnectionStatusMachine() {
        clearConnectionStatusRetryingTimer()
        setConnectionStatus(ConnectionStatus.Connected)
    }

    private fun scheduleConnectionStatusRetryingTimer() {
        if (connectionStatusRetryingRunnable != null) return

        val runnable = Runnable {
            connectionStatusRetryingRunnable = null
            if (_uiState.value.phase != CallPhase.InCall) {
                resetConnectionStatusMachine()
                return@Runnable
            }
            if (_uiState.value.connectionStatus == ConnectionStatus.Recovering) {
                setConnectionStatus(ConnectionStatus.Retrying)
            }
        }
        connectionStatusRetryingRunnable = runnable
        handler.postDelayed(runnable, 10_000)
    }

    private fun markConnectionDegraded() {
        if (_uiState.value.phase != CallPhase.InCall) {
            resetConnectionStatusMachine()
            return
        }
        when (_uiState.value.connectionStatus) {
            ConnectionStatus.Connected -> {
                setConnectionStatus(ConnectionStatus.Recovering)
                scheduleConnectionStatusRetryingTimer()
            }

            ConnectionStatus.Recovering -> scheduleConnectionStatusRetryingTimer()
            ConnectionStatus.Retrying -> Unit
        }
    }

    private fun updateConnectionStatusFromSignals() {
        val state = _uiState.value
        if (state.phase != CallPhase.InCall) {
            resetConnectionStatusMachine()
            return
        }

        if (isConnectionDegraded(state)) {
            markConnectionDegraded()
            return
        }

        resetConnectionStatusMachine()
    }

    private fun isConnectionDegraded(state: CallUiState): Boolean {
        return !state.isSignalingConnected ||
            state.iceConnectionState == "DISCONNECTED" ||
            state.iceConnectionState == "FAILED" ||
            state.connectionState == "DISCONNECTED" ||
            state.connectionState == "FAILED"
    }

    fun updateServerHost(host: String) {
        val trimmed = host.trim().ifBlank { SettingsStore.DEFAULT_HOST }
        val changed = trimmed != _serverHost.value
        settingsStore.host = trimmed
        _serverHost.value = trimmed
        if (changed && currentRoomId == null) {
            signalingClient.close()
            syncSavedRoomPushSubscriptions(_savedRooms.value)
            refreshWatchedRooms()
        }
    }

    fun validateServerHost(host: String, onResult: (Result<String>) -> Unit) {
        val normalized = host.trim().ifBlank { SettingsStore.DEFAULT_HOST }
        apiClient.validateServerHost(normalized) { result ->
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
        webRtcEngine.setHdVideoExperimentalEnabled(enabled)
    }

    fun updateSavedRoomsShownFirst(enabled: Boolean) {
        settingsStore.areSavedRoomsShownFirst = enabled
        _areSavedRoomsShownFirst.value = enabled
    }

    fun updateRoomInviteNotifications(enabled: Boolean) {
        settingsStore.areRoomInviteNotificationsEnabled = enabled
        _areRoomInviteNotificationsEnabled.value = enabled
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
                host = resolvedHost
            )
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
                        IllegalArgumentException(appContext.getString(R.string.error_invalid_saved_room_name))
                    )
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
                        IllegalArgumentException(appContext.getString(R.string.settings_error_invalid_server_host))
                    )
                )
            }
            return
        }
        apiClient.createRoomId(normalizedHost) { result ->
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
                    errorMessageText = null
                )
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
        val action = when {
            savedRoomName != null -> DeepLinkAction.SaveRoom
            else -> DeepLinkAction.Join
        }

        return DeepLinkTarget(
            action = action,
            roomId = roomId,
            host = normalizeHostValue(uri.getQueryParameter("host")) ?: normalizeHostValue(uri.authority),
            savedRoomName = savedRoomName
        )
    }

    private fun extractRoomId(uri: Uri): String? {
        return uri.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun buildSavedRoomInviteLink(host: String, roomId: String, roomName: String): String {
        val normalizedHost = normalizeHostValue(host) ?: host
        val appLinkHost = if (normalizedHost == SettingsStore.HOST_RU) {
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
        val withScheme = if (raw.startsWith("http://", ignoreCase = true) ||
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
        if (_uiState.value.phase != CallPhase.Idle) return
        updateState(
            _uiState.value.copy(
                phase = CallPhase.CreatingRoom,
                statusMessageResId = R.string.call_status_creating_room
            )
        )
        apiClient.createRoomId(serverHost.value) { result ->
            handler.post {
                result
                    .onSuccess { roomId ->
                        joinRoom(roomId)
                    }
                    .onFailure { err ->
                        val fallback = appContext.getString(R.string.error_failed_create_room)
                        val message = err.message?.ifBlank { null } ?: fallback
                        updateState(
                            _uiState.value.copy(
                                phase = CallPhase.Error,
                                errorMessageResId = if (message == fallback) R.string.error_failed_create_room else null,
                                errorMessageText = if (message == fallback) null else message
                            )
                        )
                    }
            }
        }
    }

    fun joinRoom(roomId: String, oneOffHost: String? = null) {
        if (roomId.isBlank()) {
            updateState(
                _uiState.value.copy(
                    phase = CallPhase.Error,
                    errorMessageResId = R.string.error_invalid_room_id,
                    errorMessageText = null
                )
            )
            return
        }
        if (savedRoomStore.markRoomJoined(roomId)) {
            refreshSavedRooms()
        }
        activeCallHostOverride = normalizeHostValue(oneOffHost)
        if (activeCallHostOverride != null && signalingClient.isConnected()) {
            signalingClient.close()
        }
        currentRoomId = roomId
        val joinAttemptId = ++joinAttemptSerial
        callStartTimeMs = System.currentTimeMillis()
        pendingMessages.clear()
        peerSlots.clear()
        currentRoomState = null
        hasJoinSignalStarted = false
        hasJoinAcknowledged = false
        hasNotifiedPushForJoin = false

        recreateWebRtcEngineForNewCall()

        val defaultAudio = settingsStore.isDefaultMicrophoneEnabled
        val defaultVideo = settingsStore.isDefaultCameraEnabled
        userPreferredVideoEnabled = defaultVideo

        updateState(
            _uiState.value.copy(
                phase = CallPhase.Joining,
                roomId = roomId,
                statusMessageResId = R.string.call_status_joining_room,
                errorMessageResId = null,
                errorMessageText = null,
                localAudioEnabled = defaultAudio,
                localVideoEnabled = defaultVideo,
                remoteParticipants = emptyList(),
                localCameraMode = LocalCameraMode.SELFIE,
                connectionStatus = ConnectionStatus.Connected,
                webrtcStatsSummary = "",
                realtimeCallStats = null,
                isFlashAvailable = false,
                isFlashEnabled = false
            )
        )
        scheduleJoinTimeout(roomId, joinAttemptId)
        scheduleJoinKickstart(roomId, joinAttemptId)

        acquirePerformanceLocks()
        activateAudioSession()
        webRtcEngine.startLocalMedia()

        // Apply defaults immediately after starting media
        if (!defaultAudio) webRtcEngine.toggleAudio(false)
        applyLocalVideoPreference()

        startRemoteVideoStatePolling()
        ensureSignalingConnection()
        CallService.start(appContext, roomId, roomName = savedRoomNameForNotification(roomId))
    }

    fun leaveCall() {
        if (_uiState.value.phase == CallPhase.Idle) return
        sendMessage("leave", null)
        cleanupCall(R.string.call_status_left_room)
    }

    fun dismissError() {
        if (_uiState.value.phase == CallPhase.Error) {
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
        val enabled = !_uiState.value.localAudioEnabled
        webRtcEngine.toggleAudio(enabled)
        updateState(_uiState.value.copy(localAudioEnabled = enabled))
    }

    fun toggleVideo() {
        // Toggle from the effective state so UI semantics remain intuitive even when proximity
        // temporarily pauses local video.
        userPreferredVideoEnabled = !_uiState.value.localVideoEnabled
        applyLocalVideoPreference()
    }

    fun toggleFlashlight() {
        webRtcEngine.toggleFlashlight()
    }

    fun flipCamera() {
        // Can only flip if not screen sharing
        if (!_uiState.value.isScreenSharing) {
            // If currently in content mode (world/composite), broadcast deactivation
            // before the flip. The onCameraModeChanged callback will broadcast
            // activation if the new mode is also a content mode.
            val currentMode = _uiState.value.localCameraMode
            if (currentMode.isContentMode) {
                broadcastContentState(false)
            }
            webRtcEngine.flipCamera()
        }
    }

    fun adjustLocalCameraZoom(scaleFactor: Float) {
        webRtcEngine.adjustWorldCameraZoom(scaleFactor)
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
            includeMediaProjection = true
        )
        startScreenShareWhenForegroundReady(intent, roomId, attemptsRemaining = 15)
    }

    fun stopScreenShare() {
        if (!_uiState.value.isScreenSharing) return
        if (!webRtcEngine.stopScreenShare()) {
            Log.w("CallManager", "Failed to stop screen sharing")
            return
        }
        currentRoomId?.let { roomId ->
            CallService.start(appContext, roomId, roomName = savedRoomNameForNotification(roomId))
        }
        updateState(_uiState.value.copy(isScreenSharing = false))
        broadcastContentState(false)
        applyLocalVideoPreference()
    }

    private fun startScreenShareWhenForegroundReady(
        intent: Intent,
        roomId: String,
        attemptsRemaining: Int
    ) {
        if (CallService.isMediaProjectionForegroundActive()) {
            if (!webRtcEngine.startScreenShare(intent)) {
                CallService.start(appContext, roomId, roomName = savedRoomNameForNotification(roomId))
                Log.w("CallManager", "Failed to start screen sharing")
                return
            }
            updateState(_uiState.value.copy(isScreenSharing = true))
            broadcastContentState(true, ContentTypeWire.SCREEN_SHARE)
            applyLocalVideoPreference()
            return
        }
        if (attemptsRemaining <= 0) {
            CallService.start(appContext, roomId, roomName = savedRoomNameForNotification(roomId))
            Log.w("CallManager", "Failed to start screen sharing: media projection foreground type not ready")
            return
        }
        handler.postDelayed(
            { startScreenShareWhenForegroundReady(intent, roomId, attemptsRemaining - 1) },
            50
        )
    }

    fun attachLocalRenderer(
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null
    ) {
        webRtcEngine.attachLocalRenderer(renderer, rendererEvents)
    }

    fun detachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRtcEngine.detachLocalRenderer(renderer)
    }

    fun attachRemoteRenderer(
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null
    ) {
        val remoteCid = currentRoomState
            ?.participants
            ?.firstOrNull { it.cid != clientId }
            ?.cid
            ?: peerSlots.keys.firstOrNull()
            ?: return
        attachRemoteRendererForCid(remoteCid, renderer, rendererEvents)
    }

    fun detachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        peerSlots.values.forEach { slot ->
            slot.detachRemoteRenderer(renderer)
        }
    }

    fun attachLocalSink(sink: org.webrtc.VideoSink) {
        webRtcEngine.attachLocalSink(sink)
    }

    fun detachLocalSink(sink: org.webrtc.VideoSink) {
        webRtcEngine.detachLocalSink(sink)
    }

    fun attachRemoteSink(sink: org.webrtc.VideoSink) {
        val remoteCid = currentRoomState
            ?.participants
            ?.firstOrNull { it.cid != clientId }
            ?.cid
            ?: peerSlots.keys.firstOrNull()
            ?: return
        peerSlots[remoteCid]?.attachRemoteSink(sink)
    }

    fun detachRemoteSink(sink: org.webrtc.VideoSink) {
        peerSlots.values.forEach { slot ->
            slot.detachRemoteSink(sink)
        }
    }

    fun attachRemoteRendererForCid(
        cid: String,
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null,
    ) {
        webRtcEngine.initRenderer(renderer, rendererEvents)
        peerSlots[cid]?.attachRemoteRenderer(renderer)
    }

    fun detachRemoteRendererForCid(
        cid: String,
        renderer: org.webrtc.SurfaceViewRenderer,
    ) {
        peerSlots[cid]?.detachRemoteRenderer(renderer)
    }

    fun attachRemoteSinkForCid(cid: String, sink: org.webrtc.VideoSink) {
        peerSlots[cid]?.attachRemoteSink(sink)
    }

    fun detachRemoteSinkForCid(cid: String, sink: org.webrtc.VideoSink) {
        peerSlots[cid]?.detachRemoteSink(sink)
    }

    fun eglContext(): org.webrtc.EglBase.Context = webRtcEngine.getEglContext()

    private fun ensureSignalingConnection() {
        hasJoinSignalStarted = true
        val roomToJoin = currentRoomId
        if (signalingClient.isConnected()) {
            if (!roomToJoin.isNullOrBlank()) {
                pendingJoinRoom = null
                sendJoin(roomToJoin)
            }
            sendWatchRoomsIfNeeded()
            return
        }
        pendingJoinRoom = roomToJoin
        signalingClient.connect(currentSignalingHost())
    }

    private fun sendJoin(roomId: String) {
        val buildPayload = {
            JSONObject().apply {
                put("device", "android")
                put(
                    "capabilities",
                    JSONObject().apply {
                        put("trickleIce", true)
                        put("maxParticipants", 4)
                    }
                )
                put("createMaxParticipants", 4)
                val reconnectCid = clientId ?: settingsStore.reconnectCid
                reconnectCid?.let { put("reconnectCid", it) }
                reconnectToken?.let { put("reconnectToken", it) }
            }
        }
        if (currentRoomId != roomId) return
        if (!signalingClient.isConnected()) return
        val msg = SignalingMessage(
            type = "join",
            rid = roomId,
            sid = null,
            cid = null,
            to = null,
            payload = buildPayload()
        )
        signalingClient.send(msg)
        scheduleJoinRecovery(roomId)
    }

    private fun isJoinAttemptActive(roomId: String, joinAttemptId: Long): Boolean {
        return joinAttemptSerial == joinAttemptId &&
            currentRoomId == roomId &&
            _uiState.value.phase == CallPhase.Joining
    }

    private fun sendMessage(type: String, payload: JSONObject?, to: String? = null) {
        Log.d("CallManager", "TX $type")
        val msg = SignalingMessage(
            type = type,
            rid = currentRoomId,
            sid = null,
            cid = clientId,
            to = to,
            payload = payload
        )
        signalingClient.send(msg)
    }

    private fun sendWatchRoomsIfNeeded() {
        if (watchedRoomIds.isEmpty()) return
        if (!signalingClient.isConnected()) return
        val payload = JSONObject().apply {
            put("rids", JSONArray(watchedRoomIds))
        }
        val msg = SignalingMessage(
            type = "watch_rooms",
            rid = null,
            sid = null,
            cid = null,
            to = null,
            payload = payload
        )
        signalingClient.send(msg)
    }

    private fun handleSignalingMessage(msg: SignalingMessage) {
        Log.d("CallManager", "RX ${msg.type}")
        when (msg.type) {
            "joined" -> handleJoined(msg)
            "room_state" -> handleRoomState(msg)
            "room_ended" -> handleRoomEnded(msg)
            "room_statuses" -> handleRoomStatuses(msg)
            "room_status_update" -> handleRoomStatusUpdate(msg)
            "pong" -> signalingClient.recordPong()
            "turn-refreshed" -> handleTurnRefreshed(msg)
            "offer", "answer", "ice" -> handleSignalingPayload(msg)
            "content_state" -> handleContentState(msg)
            "error" -> handleError(msg)
        }
    }

    private fun handleJoined(msg: SignalingMessage) {
        clearJoinTimeout()
        clearJoinKickstart()
        clearJoinRecovery()
        hasJoinAcknowledged = true

        clientId = msg.cid
        clientId?.let { settingsStore.reconnectCid = it }
        updateState(_uiState.value.copy(localCid = clientId))

        msg.payload?.optString("reconnectToken").orEmpty().ifBlank { null }?.let {
            reconnectToken = it
        }
        msg.payload?.optLong("turnTokenTTLMs", 0)?.takeIf { it > 0 }?.let { ttl ->
            turnTokenTTLMs = ttl
            scheduleTurnRefresh(ttl)
        }

        val joinedRoomId = msg.rid ?: currentRoomId
        if (!joinedRoomId.isNullOrBlank()) {
            pushSubscriptionManager.subscribeRoom(joinedRoomId, currentSignalingHost())
        }
        val roomState = parseRoomState(msg.payload)
        if (roomState != null) {
            currentRoomState = roomState
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        }
        val token = msg.payload?.optString("turnToken").orEmpty().ifBlank { null }
        if (!token.isNullOrBlank()) {
            fetchTurnCredentials(token)
        } else {
            applyDefaultIceServers()
        }

        // Fire async post-join push notification (fresh joins only)
        if (!hasNotifiedPushForJoin) {
            hasNotifiedPushForJoin = true
            val notifyRoomId = joinedRoomId ?: return
            val notifyCid = clientId ?: return
            val notifyHost = currentSignalingHost()
            val notifyJoinAttempt = joinAttemptSerial
            joinSnapshotFeature.prepareSnapshotId(
                host = notifyHost,
                roomId = notifyRoomId,
                isVideoEnabled = { _uiState.value.localVideoEnabled },
                isJoinAttemptActive = { joinAttemptSerial == notifyJoinAttempt && currentRoomId == notifyRoomId }
            ) { snapshotId ->
                val endpoint = pushSubscriptionManager.cachedEndpoint()
                apiClient.notifyRoom(notifyHost, notifyRoomId, notifyCid, snapshotId, endpoint) { result ->
                    result.onFailure { e ->
                        Log.w("CallManager", "Post-join push notify failed", e)
                    }
                }
            }
        }
    }

    private fun handleRoomState(msg: SignalingMessage) {
        clearJoinTimeout()
        clearJoinKickstart()
        clearJoinRecovery()
        hasJoinAcknowledged = true
        val roomState = parseRoomState(msg.payload) ?: return
        currentRoomState = roomState
        hostCid = roomState.hostCid
        updateParticipants(roomState)
    }

    private fun handleRoomEnded(@Suppress("UNUSED_PARAMETER") msg: SignalingMessage) {
        cleanupCall(R.string.call_status_room_ended)
    }

    private fun handleRoomStatuses(msg: SignalingMessage) {
        val payload = msg.payload ?: return
        val watched = watchedRoomIds.toSet()
        if (watched.isEmpty()) {
            _roomStatuses.value = emptyMap()
            return
        }

        _roomStatuses.value = RoomStatuses
            .mergeStatusesPayload(previous = _roomStatuses.value, payload = payload)
            .filterKeys { watched.contains(it) }
    }

    private fun handleRoomStatusUpdate(msg: SignalingMessage) {
        val payload = msg.payload ?: return
        val rid = payload.optString("rid").orEmpty()
        if (!watchedRoomIds.contains(rid)) return
        _roomStatuses.value = RoomStatuses.mergeStatusUpdatePayload(previous = _roomStatuses.value, payload = payload)
    }

    private fun handleContentState(msg: SignalingMessage) {
        val fromCid = msg.payload?.optString("from") ?: return
        val active = msg.payload?.optBoolean("active") == true
        val contentType = if (active) msg.payload?.optString("contentType") else null
        updateState(
            _uiState.value.copy(
                remoteContentCid = if (active) fromCid else null,
                remoteContentType = contentType,
            )
        )
    }

    private fun broadcastContentState(active: Boolean, contentType: String? = null) {
        val payload = JSONObject().apply {
            put("active", active)
            if (active && contentType != null) put("contentType", contentType)
        }
        sendMessage("content_state", payload)
    }

    private fun handleError(msg: SignalingMessage) {
        val code = msg.payload?.optString("code").orEmpty().ifBlank { null }
        val rawMessage = msg.payload?.optString("message").orEmpty().ifBlank { null }
        val resolvedMessage =
            when (code) {
                "ROOM_CAPACITY_UNSUPPORTED" ->
                    rawMessage ?: appContext.getString(R.string.error_room_capacity_unsupported)
                else -> rawMessage
            }
        clearJoinTimeout()
        resetResources()
        updateState(
            CallUiState(
                phase = CallPhase.Error,
                errorMessageResId = if (resolvedMessage == null) R.string.error_unknown else null,
                errorMessageText = resolvedMessage
            )
        )
    }

    private fun handleSignalingPayload(msg: SignalingMessage) {
        if (!webRtcEngine.hasIceServers()) {
            pendingMessages.add(msg)
            return
        }
        processSignalingPayload(msg)
    }

    private fun getOrCreateSlot(remoteCid: String): PeerConnectionSlot {
        return peerSlots.getOrPut(remoteCid) {
            webRtcEngine.createSlot(
                remoteCid = remoteCid,
                onLocalIceCandidate = { cid: String, candidate: IceCandidate ->
                    val payload = JSONObject().apply {
                        val candidateJson = JSONObject()
                        candidateJson.put("candidate", candidate.sdp)
                        candidateJson.put("sdpMid", candidate.sdpMid)
                        candidateJson.put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidateJson)
                    }
                    sendMessage("ice", payload, to = cid)
                },
                onRemoteVideoTrack = { _, _ ->
                    handler.post { refreshRemoteParticipants() }
                },
                onConnectionStateChange = { cid, state ->
                    handler.post {
                        when (state) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                clearIceRestartTimer(cid)
                                peerSlots[cid]?.pendingIceRestart = false
                            }

                            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                scheduleIceRestart(cid, "conn-disconnected", 2000)
                            }

                            PeerConnection.PeerConnectionState.FAILED -> {
                                scheduleIceRestart(cid, "conn-failed", 0)
                            }

                            else -> Unit
                        }
                        refreshRemoteParticipants()
                        updateAggregatePeerState()
                        updateConnectionStatusFromSignals()
                    }
                },
                onIceConnectionStateChange = { cid, state ->
                    handler.post {
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                clearIceRestartTimer(cid)
                                peerSlots[cid]?.pendingIceRestart = false
                            }

                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                scheduleIceRestart(cid, "ice-disconnected", 2000)
                            }

                            PeerConnection.IceConnectionState.FAILED -> {
                                scheduleIceRestart(cid, "ice-failed", 0)
                            }

                            else -> Unit
                        }
                        refreshRemoteParticipants()
                        updateAggregatePeerState()
                        updateConnectionStatusFromSignals()
                    }
                },
                onSignalingStateChange = { cid, state ->
                    handler.post {
                        if (state == PeerConnection.SignalingState.STABLE) {
                            clearOfferTimeout(cid)
                            if (peerSlots[cid]?.pendingIceRestart == true) {
                                peerSlots[cid]?.pendingIceRestart = false
                                triggerIceRestart(cid, "pending-retry")
                            }
                        }
                        updateAggregatePeerState()
                        updateConnectionStatusFromSignals()
                    }
                },
                onRenegotiationNeeded = { cid ->
                    handler.post {
                        peerSlots[cid]?.let { slot ->
                            maybeSendOffer(slot, force = true, iceRestart = false)
                        }
                    }
                }
            )
        }
    }

    private fun removePeerSlot(remoteCid: String) {
        clearOfferTimeout(remoteCid)
        clearIceRestartTimer(remoteCid)
        clearNonHostOfferFallback(remoteCid)
        val slot = peerSlots.remove(remoteCid) ?: return
        webRtcEngine.removeSlot(slot)
        slot.closePeerConnection()
    }

    private fun updateAggregatePeerState() {
        var bestIcePriority = Int.MAX_VALUE
        var bestIceState = "NEW"
        var bestConnPriority = Int.MAX_VALUE
        var bestConnState = "NEW"
        var bestSigPriority = Int.MAX_VALUE
        var bestSigState = "STABLE"
        for (slot in peerSlots.values) {
            val icePri = ICE_CONNECTION_PRIORITY[slot.getIceConnectionState()] ?: Int.MAX_VALUE
            if (icePri < bestIcePriority) { bestIcePriority = icePri; bestIceState = slot.getIceConnectionState().name }
            val connPri = CONNECTION_PRIORITY[slot.getConnectionState()] ?: Int.MAX_VALUE
            if (connPri < bestConnPriority) { bestConnPriority = connPri; bestConnState = slot.getConnectionState().name }
            val sigPri = SIGNALING_PRIORITY[slot.getSignalingState()] ?: Int.MAX_VALUE
            if (sigPri < bestSigPriority) { bestSigPriority = sigPri; bestSigState = slot.getSignalingState().name }
        }
        val nextIceState = bestIceState
        val nextConnectionState = bestConnState
        val nextSignalingState = bestSigState
        val state = _uiState.value
        if (
            state.iceConnectionState == nextIceState &&
            state.connectionState == nextConnectionState &&
            state.signalingState == nextSignalingState
        ) {
            return
        }
        updateState(
            state.copy(
                iceConnectionState = nextIceState,
                connectionState = nextConnectionState,
                signalingState = nextSignalingState
            )
        )
    }

    private fun shouldIOffer(remoteCid: String, roomState: RoomState? = currentRoomState): Boolean {
        val state = roomState ?: return false
        val myCid = clientId ?: return false
        val myJoinedAt = state.participants.find { it.cid == myCid }?.joinedAt ?: 0L
        val theirJoinedAt = state.participants.find { it.cid == remoteCid }?.joinedAt ?: 0L
        return myJoinedAt < theirJoinedAt || (myJoinedAt == theirJoinedAt && myCid < remoteCid)
    }

    private fun processSignalingPayload(msg: SignalingMessage) {
        val fromCid = msg.payload?.optString("from").orEmpty().ifBlank { return }
        val slot = getOrCreateSlot(fromCid)
        if (!slot.isReady() && !slot.ensurePeerConnection()) {
            pendingMessages.add(msg)
            return
        }
        when (msg.type) {
            "offer" -> {
                clearNonHostOfferFallback(fromCid)
                val sdp = msg.payload?.optString("sdp").orEmpty().ifBlank { return }
                slot.setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
                    slot.createAnswer(onSdp = { answerSdp ->
                        val payload = JSONObject().apply { put("sdp", answerSdp) }
                        sendMessage("answer", payload, to = fromCid)
                    })
                }
            }
            "answer" -> {
                clearNonHostOfferFallback(fromCid)
                val sdp = msg.payload?.optString("sdp").orEmpty().ifBlank { return }
                slot.setRemoteDescription(SessionDescription.Type.ANSWER, sdp) {
                    clearOfferTimeout(fromCid)
                    slot.pendingIceRestart = false
                    updateAggregatePeerState()
                    updateConnectionStatusFromSignals()
                }
            }
            "ice" -> {
                val candidateJson = msg.payload?.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(
                    candidateJson.optString("sdpMid").ifBlank { null },
                    candidateJson.optInt("sdpMLineIndex", 0),
                    candidateJson.optString("candidate", "")
                )
                slot.addIceCandidate(candidate)
            }
        }
    }

    private fun updateParticipants(roomState: RoomState) {
        val count = roomState.participants.size
        val isHostNow = clientId != null && clientId == roomState.hostCid
        val remotePeers = roomState.participants.filter { it.cid != clientId }
        val remoteCids = remotePeers.map { it.cid }.toSet()
        val phase = when {
            count <= 1 -> CallPhase.Waiting
            else -> CallPhase.InCall
        }
        if (phase != CallPhase.Joining) {
            clearJoinTimeout()
        }

        val departing = peerSlots.keys.filter { it !in remoteCids }
        departing.forEach { remoteCid -> removePeerSlot(remoteCid) }
        if (remotePeers.isEmpty()) {
            clearOfferTimeout()
            clearIceRestartTimer()
            clearNonHostOfferFallback()
        }

        remotePeers.forEach { participant ->
            val slot = getOrCreateSlot(participant.cid)
            slot.ensurePeerConnection()
            if (shouldIOffer(participant.cid, roomState)) {
                clearNonHostOfferFallback(participant.cid)
                maybeSendOffer(slot)
            } else {
                maybeScheduleNonHostOfferFallback(participant.cid, "participants")
            }
        }

        updateState(
            _uiState.value.copy(
                phase = phase,
                isHost = isHostNow,
                participantCount = count,
                statusMessageResId =
                    if (count <= 1) {
                        R.string.call_status_waiting_for_join
                    } else {
                        R.string.call_status_in_call
                    }
            )
        )
        refreshRemoteParticipants()
        updateAggregatePeerState()
        updateConnectionStatusFromSignals()
    }

    private fun maybeSendOffer(force: Boolean = false, iceRestart: Boolean = false) {
        peerSlots.values.forEach { slot ->
            if (shouldIOffer(slot.remoteCid, currentRoomState)) {
                maybeSendOffer(slot, force, iceRestart)
            }
        }
    }

    private fun maybeSendOffer(
        slot: PeerConnectionSlot,
        force: Boolean = false,
        iceRestart: Boolean = false,
    ) {
        if (slot.isMakingOffer) {
            if (iceRestart) {
                slot.pendingIceRestart = true
            }
            return
        }
        if (!force && slot.sentOffer) return
        if (!canOffer(slot)) return
        if (slot.getSignalingState() != PeerConnection.SignalingState.STABLE) {
            if (iceRestart) {
                slot.pendingIceRestart = true
            }
            return
        }
        slot.isMakingOffer = true
        val started = slot.createOffer(
            iceRestart = iceRestart,
            onSdp = { sdp ->
                val payload = JSONObject().apply { put("sdp", sdp) }
                sendMessage("offer", payload, to = slot.remoteCid)
                scheduleOfferTimeout(slot.remoteCid)
            },
            onComplete = { success ->
                handler.post {
                    slot.isMakingOffer = false
                    if (!success && iceRestart) {
                        scheduleIceRestart(slot.remoteCid, "offer-failed", 500)
                    }
                }
            }
        )
        if (!started) {
            slot.isMakingOffer = false
            if (iceRestart) {
                slot.pendingIceRestart = true
            }
            return
        }
        if (!force) {
            slot.sentOffer = true
        }
    }

    private fun canOffer(slot: PeerConnectionSlot): Boolean {
        if (currentRoomId == null) return false
        if (!signalingClient.isConnected()) return false
        if (!slot.isReady()) return false
        if (!shouldIOffer(slot.remoteCid, currentRoomState)) return false
        val participantCids = currentRoomState?.participants?.map { it.cid }?.toSet() ?: emptySet()
        return slot.remoteCid in participantCids
    }

    private fun scheduleOfferTimeout(remoteCid: String) {
        val slot = peerSlots[remoteCid] ?: return
        clearOfferTimeout(remoteCid)
        val runnable = Runnable {
            slot.offerTimeoutTask = null
            val signalingState = slot.getSignalingState()
            if (signalingState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                Log.w("CallManager", "Offer timeout for $remoteCid; rolling back and retrying")
                slot.pendingIceRestart = true
                slot.rollbackLocalDescription {
                    handler.post {
                        if (shouldIOffer(remoteCid, currentRoomState)) {
                            scheduleIceRestart(remoteCid, "offer-timeout", 0)
                        } else {
                            maybeScheduleNonHostOfferFallback(remoteCid, "offer-timeout")
                        }
                    }
                }
            } else {
                if (shouldIOffer(remoteCid, currentRoomState)) {
                    scheduleIceRestart(remoteCid, "offer-timeout-stale", 0)
                } else {
                    maybeScheduleNonHostOfferFallback(remoteCid, "offer-timeout-stale")
                }
            }
        }
        slot.offerTimeoutTask = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.OFFER_TIMEOUT_MS)
    }

    private fun scheduleJoinTimeout(roomId: String, joinAttemptId: Long) {
        clearJoinTimeout()
        val runnable = Runnable {
            joinTimeoutRunnable = null
            val state = _uiState.value
            val isStillJoining = state.phase == CallPhase.Joining &&
                currentRoomId == roomId &&
                joinAttemptSerial == joinAttemptId
            if (!isStillJoining) return@Runnable
            Log.w("CallManager", "Join timeout for room $roomId; failing attempt")
            failJoinWithError(R.string.call_status_connection_failed)
        }
        joinTimeoutRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS)
    }

    private fun clearJoinTimeout() {
        joinTimeoutRunnable?.let { handler.removeCallbacks(it) }
        joinTimeoutRunnable = null
    }

    private fun failJoinWithError(messageResId: Int) {
        clearJoinTimeout()
        resetResources()
        updateState(
            CallUiState(
                phase = CallPhase.Error,
                errorMessageResId = messageResId,
                errorMessageText = null
            )
        )
    }

    private fun clearOfferTimeout(remoteCid: String? = null) {
        if (remoteCid != null) {
            peerSlots[remoteCid]?.offerTimeoutTask?.let { handler.removeCallbacks(it) }
            peerSlots[remoteCid]?.offerTimeoutTask = null
            return
        }
        peerSlots.values.forEach { slot ->
            slot.offerTimeoutTask?.let { handler.removeCallbacks(it) }
            slot.offerTimeoutTask = null
        }
    }

    private fun scheduleIceRestart(reason: String, delayMs: Long) {
        peerSlots.values.forEach { slot ->
            if (shouldIOffer(slot.remoteCid, currentRoomState)) {
                scheduleIceRestart(slot.remoteCid, reason, delayMs)
            }
        }
    }

    private fun scheduleIceRestart(remoteCid: String, reason: String, delayMs: Long) {
        val slot = peerSlots[remoteCid] ?: return
        if (!canOffer(slot)) {
            slot.pendingIceRestart = true
            return
        }
        if (slot.iceRestartTask != null) return
        val now = System.currentTimeMillis()
        if (now - slot.lastIceRestartAt < WebRtcResilienceConstants.ICE_RESTART_COOLDOWN_MS) return
        val runnable = Runnable {
            slot.iceRestartTask = null
            triggerIceRestart(remoteCid, reason)
        }
        slot.iceRestartTask = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearIceRestartTimer(remoteCid: String? = null) {
        if (remoteCid != null) {
            peerSlots[remoteCid]?.iceRestartTask?.let { handler.removeCallbacks(it) }
            peerSlots[remoteCid]?.iceRestartTask = null
            return
        }
        peerSlots.values.forEach { slot ->
            slot.iceRestartTask?.let { handler.removeCallbacks(it) }
            slot.iceRestartTask = null
        }
    }

    private fun triggerIceRestart(reason: String) {
        peerSlots.values.forEach { slot ->
            if (shouldIOffer(slot.remoteCid, currentRoomState)) {
                triggerIceRestart(slot.remoteCid, reason)
            }
        }
    }

    private fun triggerIceRestart(remoteCid: String, reason: String) {
        val slot = peerSlots[remoteCid] ?: return
        if (!canOffer(slot)) {
            slot.pendingIceRestart = true
            return
        }
        if (slot.isMakingOffer) {
            slot.pendingIceRestart = true
            return
        }
        Log.w("CallManager", "ICE restart triggered for $remoteCid ($reason)")
        slot.lastIceRestartAt = System.currentTimeMillis()
        slot.pendingIceRestart = false
        maybeSendOffer(slot, force = true, iceRestart = true)
    }

    private fun fetchTurnCredentials(token: String) {
        var resolved = false
        val timeoutRunnable = Runnable {
            if (resolved) return@Runnable
            resolved = true
            Log.w("CallManager", "TURN fetch timed out, applying default STUN")
            applyDefaultIceServers()
        }
        handler.postDelayed(timeoutRunnable, WebRtcResilienceConstants.TURN_FETCH_TIMEOUT_MS)
        apiClient.fetchTurnCredentials(currentSignalingHost(), token) { result ->
            handler.post {
                handler.removeCallbacks(timeoutRunnable)
                if (resolved) return@post
                resolved = true
                result
                    .onSuccess { creds -> applyTurnCredentials(creds) }
                    .onFailure {
                        Log.w("CallManager", "TURN fetch failed, applying default STUN")
                        applyDefaultIceServers()
                    }
            }
        }
    }

    private fun applyTurnCredentials(creds: TurnCredentials) {
        val servers = creds.uris.map {
            PeerConnection.IceServer.builder(it)
                .setUsername(creds.username)
                .setPassword(creds.password)
                .createIceServer()
        }
        webRtcEngine.setIceServers(servers)
        onIceServersReady()
    }

    private fun applyDefaultIceServers() {
        val servers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        webRtcEngine.setIceServers(servers)
        onIceServersReady()
    }

    private fun onIceServersReady() {
        flushPendingMessages()
        maybeSendOffer()
        peerSlots.values.forEach { slot ->
            if (!shouldIOffer(slot.remoteCid, currentRoomState)) {
                maybeScheduleNonHostOfferFallback(slot.remoteCid, "ice-ready")
            }
        }
    }

    private fun flushPendingMessages() {
        while (pendingMessages.isNotEmpty() && webRtcEngine.hasIceServers()) {
            processSignalingPayload(pendingMessages.removeFirst())
        }
    }

    private fun parseRoomState(payload: JSONObject?): RoomState? {
        if (payload == null) return null
        val parsedHostCid = payload.optString("hostCid", "").ifBlank { null }
        val maxParticipants = payload.optInt("maxParticipants", 0).takeIf { it > 0 }
        val participantsJson = payload.optJSONArray("participants")
        val participants = mutableListOf<Participant>()
        if (participantsJson != null) {
            for (i in 0 until participantsJson.length()) {
                val participantObj = participantsJson.optJSONObject(i)
                val cid = participantObj?.optString("cid", "") ?: ""
                if (cid.isNotBlank()) {
                    val rawJoinedAt = participantObj?.optLong("joinedAt") ?: 0L
                    val joinedAt = rawJoinedAt.takeIf { it > 0L }
                    participants.add(Participant(cid, joinedAt))
                }
            }
        }
        // Multi-level hostCid fallback (matches iOS behavior)
        var resolvedHostCid = parsedHostCid ?: hostCid ?: clientId
        if (resolvedHostCid != null && participants.isNotEmpty()) {
            val participantCids = participants.map { it.cid }.toSet()
            if (resolvedHostCid !in participantCids) {
                resolvedHostCid = participants.firstOrNull()?.cid
            }
        }
        if (resolvedHostCid.isNullOrBlank()) return null
        return RoomState(
            hostCid = resolvedHostCid,
            participants = participants,
            maxParticipants = maxParticipants,
        )
    }

    private fun updateState(state: CallUiState) {
        _uiState.value = state
    }

    private fun refreshRemoteParticipants() {
        val myCid = clientId
        val orderedRemoteCids =
            currentRoomState
                ?.participants
                ?.map { it.cid }
                ?.filter { it != myCid }
                ?: peerSlots.keys.toList()
        val remoteParticipants =
            orderedRemoteCids.mapNotNull { cid ->
                val slot = peerSlots[cid] ?: return@mapNotNull null
                RemoteParticipant(
                    cid = cid,
                    videoEnabled = slot.isRemoteVideoTrackEnabled(),
                    connectionState = slot.getConnectionState().name,
                )
            }
        val state = _uiState.value
        val activeCids = remoteParticipants.map { it.cid }.toSet()
        val clearContent = state.remoteContentCid != null && state.remoteContentCid !in activeCids
        if (state.remoteParticipants == remoteParticipants) {
            // Still check if remote content CID left
            if (clearContent) {
                updateState(state.copy(remoteContentCid = null, remoteContentType = null))
            }
            return
        }
        updateState(
            state.copy(
                remoteParticipants = remoteParticipants,
                remoteContentCid = if (clearContent) null else state.remoteContentCid,
                remoteContentType = if (clearContent) null else state.remoteContentType,
            )
        )
    }

    private fun startRemoteVideoStatePolling() {
        if (remoteVideoStatePollRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                refreshRemoteParticipants()
                pollWebRtcStats()
                handler.postDelayed(this, 500)
            }
        }
        remoteVideoStatePollRunnable = runnable
        handler.post(runnable)
    }

    private fun stopRemoteVideoStatePolling() {
        remoteVideoStatePollRunnable?.let { handler.removeCallbacks(it) }
        remoteVideoStatePollRunnable = null
        webrtcStatsRequestInFlight = false
        lastWebRtcStatsPollAtMs = 0L
    }

    private fun pollWebRtcStats() {
        val phase = _uiState.value.phase
        if (phase != CallPhase.InCall && phase != CallPhase.Waiting && phase != CallPhase.Joining) return
        val now = System.currentTimeMillis()
        if (webrtcStatsRequestInFlight) return
        if (now - lastWebRtcStatsPollAtMs < WEBRTC_STATS_POLL_INTERVAL_MS) return

        val slots = peerSlots.values.toList()
        if (slots.isEmpty()) {
            val state = _uiState.value
            if (state.webrtcStatsSummary.isNotEmpty() || state.realtimeCallStats != null) {
                updateState(
                    state.copy(
                        webrtcStatsSummary = "",
                        realtimeCallStats = null
                    )
                )
            }
            return
        }

        webrtcStatsRequestInFlight = true
        webRtcStatsExecutor.execute {
            val summaries = mutableListOf<String>()
            val stats = mutableListOf<RealtimeCallStats>()
            var remaining = slots.size
            slots.forEach { slot ->
                slot.collectWebRtcStats { summary, realtimeStats ->
                    synchronized(summaries) {
                        summaries.add(summary)
                        realtimeStats?.let(stats::add)
                        remaining -= 1
                        if (remaining == 0) {
                            val mergedSummary = summaries.joinToString(" | ")
                            val mergedStats = mergeRealtimeStats(stats)
                            handler.post {
                                webrtcStatsRequestInFlight = false
                                lastWebRtcStatsPollAtMs = System.currentTimeMillis()
                                val state = _uiState.value
                                if (
                                    state.webrtcStatsSummary != mergedSummary ||
                                    state.realtimeCallStats != mergedStats
                                ) {
                                    updateState(
                                        state.copy(
                                            webrtcStatsSummary = mergedSummary,
                                            realtimeCallStats = mergedStats
                                        )
                                    )
                                }
                                Log.d("CallManager", "[WebRTCStats] $mergedSummary")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mergeRealtimeStats(stats: List<RealtimeCallStats>): RealtimeCallStats? {
        if (stats.isEmpty()) return null
        fun sumNullable(selector: (RealtimeCallStats) -> Double?): Double? {
            val values = stats.mapNotNull(selector)
            return if (values.isEmpty()) null else values.sum()
        }
        fun maxNullable(selector: (RealtimeCallStats) -> Double?): Double? {
            val values = stats.mapNotNull(selector)
            return values.maxOrNull()
        }
        fun latestNullable(selector: (RealtimeCallStats) -> String?): String? =
            stats.asReversed().firstNotNullOfOrNull(selector)

        return RealtimeCallStats(
            transportPath = stats.mapNotNull { it.transportPath }.distinct().joinToString().ifBlank { null },
            rttMs = maxNullable { it.rttMs },
            availableOutgoingKbps = sumNullable { it.availableOutgoingKbps },
            audioRxPacketLossPct = maxNullable { it.audioRxPacketLossPct },
            audioTxPacketLossPct = maxNullable { it.audioTxPacketLossPct },
            audioJitterMs = maxNullable { it.audioJitterMs },
            audioPlayoutDelayMs = maxNullable { it.audioPlayoutDelayMs },
            audioConcealedPct = maxNullable { it.audioConcealedPct },
            audioRxKbps = sumNullable { it.audioRxKbps },
            audioTxKbps = sumNullable { it.audioTxKbps },
            videoRxPacketLossPct = maxNullable { it.videoRxPacketLossPct },
            videoTxPacketLossPct = maxNullable { it.videoTxPacketLossPct },
            videoRxKbps = sumNullable { it.videoRxKbps },
            videoTxKbps = sumNullable { it.videoTxKbps },
            videoFps = maxNullable { it.videoFps },
            videoResolution = latestNullable { it.videoResolution },
            videoFreezeCount60s = stats.mapNotNull { it.videoFreezeCount60s }.sum().takeIf { it > 0 },
            videoFreezeDuration60s = sumNullable { it.videoFreezeDuration60s },
            videoRetransmitPct = maxNullable { it.videoRetransmitPct },
            videoNackPerMin = sumNullable { it.videoNackPerMin },
            videoPliPerMin = sumNullable { it.videoPliPerMin },
            videoFirPerMin = sumNullable { it.videoFirPerMin },
            updatedAtMs = stats.maxOf { it.updatedAtMs },
        )
    }

    private fun isHost(): Boolean = clientId != null && clientId == hostCid

    private fun handleTurnRefreshed(msg: SignalingMessage) {
        if (currentRoomId == null) return
        msg.payload?.optLong("turnTokenTTLMs", 0)?.takeIf { it > 0 }?.let { ttl ->
            turnTokenTTLMs = ttl
            scheduleTurnRefresh(ttl)
        }
        val token = msg.payload?.optString("turnToken").orEmpty().ifBlank { null }
        if (!token.isNullOrBlank()) {
            fetchTurnCredentials(token)
        }
    }

    private fun scheduleTurnRefresh(ttlMs: Long) {
        clearTurnRefresh()
        if (ttlMs <= 0) return
        val delayMs = (ttlMs * WebRtcResilienceConstants.TURN_REFRESH_TRIGGER_RATIO).toLong()
        val roomId = currentRoomId
        val runnable = Runnable {
            turnRefreshRunnable = null
            if (currentRoomId != roomId || currentRoomId == null) return@Runnable
            if (!signalingClient.isConnected()) return@Runnable
            Log.d("CallManager", "Sending turn-refresh")
            sendMessage("turn-refresh", null)
        }
        turnRefreshRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearTurnRefresh() {
        turnRefreshRunnable?.let { handler.removeCallbacks(it) }
        turnRefreshRunnable = null
    }

    private fun scheduleJoinKickstart(roomId: String, joinAttemptId: Long) {
        clearJoinKickstart()
        val runnable = Runnable {
            joinKickstartRunnable = null
            if (_uiState.value.phase != CallPhase.Joining) return@Runnable
            if (currentRoomId != roomId) return@Runnable
            if (joinAttemptSerial != joinAttemptId) return@Runnable
            if (hasJoinSignalStarted) return@Runnable
            Log.d("CallManager", "Join kickstart fired for $roomId")
            ensureSignalingConnection()
        }
        joinKickstartRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_CONNECT_KICKSTART_MS)
    }

    private fun clearJoinKickstart() {
        joinKickstartRunnable?.let { handler.removeCallbacks(it) }
        joinKickstartRunnable = null
    }

    private fun scheduleJoinRecovery(roomId: String) {
        clearJoinRecovery()
        val runnable = Runnable {
            joinRecoveryRunnable = null
            if (currentRoomId != roomId) return@Runnable
            if (!signalingClient.isConnected()) return@Runnable
            if (!hasJoinAcknowledged) {
                Log.d("CallManager", "Join recovery: no ack, resending")
                if (_uiState.value.phase == CallPhase.Joining) {
                    pendingJoinRoom = roomId
                    ensureSignalingConnection()
                }
                return@Runnable
            }
            // If still in joining after recovery delay, promote state
            if (_uiState.value.phase == CallPhase.Joining) {
                updateState(_uiState.value.copy(
                    phase = CallPhase.Waiting,
                    participantCount = 1,
                    statusMessageResId = R.string.call_status_waiting_for_join
                ))
                updateConnectionStatusFromSignals()
            }
        }
        joinRecoveryRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_RECOVERY_MS)
    }

    private fun clearJoinRecovery() {
        joinRecoveryRunnable?.let { handler.removeCallbacks(it) }
        joinRecoveryRunnable = null
    }

    private fun maybeScheduleNonHostOfferFallback(reason: String) {
        peerSlots.values.forEach { slot ->
            if (!shouldIOffer(slot.remoteCid, currentRoomState)) {
                maybeScheduleNonHostOfferFallback(slot.remoteCid, reason)
            }
        }
    }

    private fun maybeScheduleNonHostOfferFallback(remoteCid: String, reason: String) {
        val slot = peerSlots[remoteCid] ?: return
        if (currentRoomId == null) return
        if (shouldIOffer(remoteCid, currentRoomState)) {
            clearNonHostOfferFallback(remoteCid)
            return
        }
        if (!signalingClient.isConnected()) return
        if (slot.nonHostFallbackTask != null) return
        if (slot.nonHostFallbackAttempts >= WebRtcResilienceConstants.NON_HOST_FALLBACK_MAX_ATTEMPTS) return

        val roomId = currentRoomId
        Log.d("CallManager", "Non-host fallback scheduled for $remoteCid ($reason)")
        val runnable = Runnable {
            slot.nonHostFallbackTask = null
            if (currentRoomId != roomId) return@Runnable
            slot.nonHostFallbackAttempts++
            Log.w(
                "CallManager",
                "Non-host fallback offer for $remoteCid (attempt ${slot.nonHostFallbackAttempts})"
            )
            maybeSendNonHostFallbackOffer(remoteCid)
        }
        slot.nonHostFallbackTask = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.NON_HOST_FALLBACK_DELAY_MS)
    }

    private fun clearNonHostOfferFallback(remoteCid: String? = null) {
        if (remoteCid != null) {
            peerSlots[remoteCid]?.nonHostFallbackTask?.let { handler.removeCallbacks(it) }
            peerSlots[remoteCid]?.nonHostFallbackTask = null
            return
        }
        peerSlots.values.forEach { slot ->
            slot.nonHostFallbackTask?.let { handler.removeCallbacks(it) }
            slot.nonHostFallbackTask = null
        }
    }

    private fun maybeSendNonHostFallbackOffer(remoteCid: String) {
        val slot = peerSlots[remoteCid] ?: return
        if (shouldIOffer(remoteCid, currentRoomState)) return
        if (!signalingClient.isConnected()) return
        if (!slot.isReady() && !slot.ensurePeerConnection()) return
        if (slot.getSignalingState() != PeerConnection.SignalingState.STABLE) {
            maybeScheduleNonHostOfferFallback(remoteCid, "signaling-not-stable")
            return
        }
        if (slot.hasRemoteDescription()) return
        if (slot.isMakingOffer) {
            maybeScheduleNonHostOfferFallback(remoteCid, "already-making-offer")
            return
        }

        Log.d("CallManager", "Non-host fallback offer triggered for $remoteCid")
        slot.isMakingOffer = true
        val started = slot.createOffer(
            onSdp = { sdp ->
                val payload = JSONObject().apply { put("sdp", sdp) }
                sendMessage("offer", payload, to = remoteCid)
                scheduleOfferTimeout(remoteCid)
            },
            onComplete = { success ->
                handler.post {
                    slot.isMakingOffer = false
                    if (!success) {
                        maybeScheduleNonHostOfferFallback(remoteCid, "offer-failed")
                    }
                }
            }
        )
        if (!started) {
            slot.isMakingOffer = false
            maybeScheduleNonHostOfferFallback(remoteCid, "offer-not-started")
        }
    }

    private fun cleanupCall(messageResId: Int) {
        updateState(
            _uiState.value.copy(
                phase = CallPhase.Ending,
                statusMessageResId = messageResId
            )
        )
        saveCurrentCallToHistoryIfNeeded()
        if (uiState.value.isScreenSharing) {
            webRtcEngine.stopScreenShare()
        }

        settingsStore.reconnectCid = null
        resetResources()
        updateState(CallUiState(phase = CallPhase.Idle))
        watchRecentRoomsIfNeeded()
    }

    private fun resetResources() {
        clearJoinTimeout()
        clearJoinKickstart()
        clearJoinRecovery()
        clearNonHostOfferFallback()
        clearTurnRefresh()
        deactivateAudioSession()
        releasePerformanceLocks()
        stopRemoteVideoStatePolling()
        signalingClient.close()
        clearOfferTimeout()
        clearIceRestartTimer()
        peerSlots.clear()
        webRtcEngine.release()
        CallService.stop(appContext)
        currentRoomId = null
        hostCid = null
        clientId = null
        currentRoomState = null
        callStartTimeMs = null
        activeCallHostOverride = null
        pendingJoinRoom = null
        pendingMessages.clear()
        reconnectAttempts = 0
        clearConnectionStatusRetryingTimer()
        userPreferredVideoEnabled = true
        isVideoPausedByProximity = false
        reconnectToken = null
        turnTokenTTLMs = null
        hasJoinSignalStarted = false
        hasJoinAcknowledged = false
        hasNotifiedPushForJoin = false
    }

    private fun applyLocalVideoPreference() {
        val shouldPauseForProximity =
            callAudioSessionController.shouldPauseVideoForProximity(
                isScreenSharing = _uiState.value.isScreenSharing
            )
        if (shouldPauseForProximity != isVideoPausedByProximity) {
            isVideoPausedByProximity = shouldPauseForProximity
            if (shouldPauseForProximity) {
                Log.d("CallManager", "Pausing local video due to proximity NEAR")
            } else {
                Log.d(
                    "CallManager",
                    "Resuming local video after proximity FAR (userPreferredVideoEnabled=$userPreferredVideoEnabled)"
                )
            }
        }
        val enabled = userPreferredVideoEnabled && !shouldPauseForProximity
        webRtcEngine.toggleVideo(enabled)
        if (_uiState.value.localVideoEnabled != enabled) {
            updateState(_uiState.value.copy(localVideoEnabled = enabled))
        }
    }

    private fun activateAudioSession() {
        callAudioSessionController.activate()
    }

    private fun deactivateAudioSession() {
        callAudioSessionController.deactivate()
    }

    private fun acquirePerformanceLocks() {
        acquireCpuWakeLock()
        // Wi-Fi low-latency lock disabled: on some devices (notably Samsung with Qualcomm WiFi),
        // WIFI_MODE_FULL_LOW_LATENCY triggers prolonged off-channel scans (~1.5s) instead of
        // suppressing them, causing massive UDP packet jitter and ~1s+ audio playout delay.
    }

    private fun acquireCpuWakeLock() {
        val lock =
            cpuWakeLock
                ?: powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                }.also { cpuWakeLock = it }
        if (lock.isHeld) return
        runCatching { lock.acquire() }
            .onSuccess { Log.d("CallManager", "CPU wake lock acquired") }
            .onFailure { error -> Log.w("CallManager", "Failed to acquire CPU wake lock", error) }
    }

    private fun acquireWifiLock() {
        val manager = wifiManager ?: return
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val lock =
            wifiPerformanceLock
                ?: manager.createWifiLock(mode, WIFI_PERF_LOCK_TAG).apply {
                    setReferenceCounted(false)
                }.also { wifiPerformanceLock = it }
        if (lock.isHeld) return
        runCatching { lock.acquire() }
            .onSuccess {
                Log.d(
                    "CallManager",
                    "Wi-Fi performance lock acquired (mode=${if (mode == WifiManager.WIFI_MODE_FULL_LOW_LATENCY) "LOW_LATENCY" else "HIGH_PERF"})"
                )
            }
            .onFailure { error -> Log.w("CallManager", "Failed to acquire Wi-Fi performance lock", error) }
    }

    private fun releasePerformanceLocks() {
        wifiPerformanceLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
                    .onSuccess { Log.d("CallManager", "Wi-Fi performance lock released") }
                    .onFailure { error -> Log.w("CallManager", "Failed to release Wi-Fi performance lock", error) }
            }
        }
        cpuWakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
                    .onSuccess { Log.d("CallManager", "CPU wake lock released") }
                    .onFailure { error -> Log.w("CallManager", "Failed to release CPU wake lock", error) }
            }
        }
    }

    private fun scheduleReconnect() {
        val roomId = currentRoomId
        if (roomId == null && watchedRoomIds.isEmpty()) return
        reconnectAttempts += 1
        val backoff = (WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS * (1 shl (reconnectAttempts - 1))).coerceAtMost(WebRtcResilienceConstants.RECONNECT_BACKOFF_CAP_MS)
        handler.postDelayed({
            if (signalingClient.isConnected()) {
                return@postDelayed
            }
            if (roomId != null && currentRoomId == roomId) {
                pendingJoinRoom = roomId
                signalingClient.connect(currentSignalingHost())
                return@postDelayed
            }
            if (roomId == null && currentRoomId == null && watchedRoomIds.isNotEmpty()) {
                signalingClient.connect(serverHost.value)
            }
        }, backoff)
    }

    private fun refreshRecentCalls() {
        val calls = recentCallStore.getRecentCalls()
        if (calls.any { it.host == null }) {
            val host = serverHost.value
            val patched = calls.map { if (it.host == null) it.copy(host = host) else it }
            patched.forEach { recentCallStore.saveCall(it) }
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
            rooms.filter { it.host == null }.forEach {
                savedRoomStore.saveRoom(it.copy(host = host))
            }
            _savedRooms.value = savedRoomStore.getSavedRooms()
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
        val h = host ?: return true
        return h.equals(serverHost.value, ignoreCase = true)
    }

    private fun hostOverrideOrNull(host: String?): String? {
        return normalizeHostValue(host)?.takeUnless { isCurrentServerHost(it) }
    }

    private fun currentSignalingHost(): String {
        return if (currentRoomId != null) {
            activeCallHostOverride ?: serverHost.value
        } else {
            serverHost.value
        }
    }

    private fun refreshWatchedRooms() {
        val mergedRoomIds = LinkedHashSet<String>()
        _savedRooms.value
            .filter { isCurrentServerHost(it.host) }
            .forEach { mergedRoomIds.add(it.roomId) }
        _recentCalls.value
            .filter { isCurrentServerHost(it.host) }
            .forEach { mergedRoomIds.add(it.roomId) }
        watchedRoomIds = mergedRoomIds.toList()
        val watched = watchedRoomIds.toSet()
        _roomStatuses.value = _roomStatuses.value.filterKeys { watched.contains(it) }
        watchRecentRoomsIfNeeded()
    }

    private fun watchRecentRoomsIfNeeded() {
        if (watchedRoomIds.isEmpty()) {
            if (currentRoomId == null && signalingClient.isConnected()) {
                signalingClient.close()
            }
            return
        }
        if (signalingClient.isConnected()) {
            sendWatchRoomsIfNeeded()
        } else {
            signalingClient.connect(currentSignalingHost())
        }
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
                host = currentSignalingHost()
            )
        )
        callStartTimeMs = null
        refreshRecentCalls()
    }

    private enum class DeepLinkAction {
        Join,
        SaveRoom
    }

    private data class DeepLinkTarget(
        val action: DeepLinkAction,
        val roomId: String,
        val host: String?,
        val savedRoomName: String?
    )

    private data class DeepLinkHostPolicy(
        val persistedHost: String? = null,
        val oneOffHost: String? = null
    )

    private companion object {
        const val WEBRTC_STATS_POLL_INTERVAL_MS = 2000L
        const val CPU_WAKE_LOCK_TAG = "serenada:call-cpu"
        const val WIFI_PERF_LOCK_TAG = "serenada:call-wifi"
        const val MAX_SAVED_ROOM_NAME_LENGTH = 120
        val ROOM_ID_REGEX = Regex("^[A-Za-z0-9_-]{27}$")
        val ICE_CONNECTION_PRIORITY =
            mapOf(
                PeerConnection.IceConnectionState.FAILED to 0,
                PeerConnection.IceConnectionState.DISCONNECTED to 1,
                PeerConnection.IceConnectionState.CHECKING to 2,
                PeerConnection.IceConnectionState.NEW to 3,
                PeerConnection.IceConnectionState.CONNECTED to 4,
                PeerConnection.IceConnectionState.COMPLETED to 5,
                PeerConnection.IceConnectionState.CLOSED to 6,
            )
        val CONNECTION_PRIORITY =
            mapOf(
                PeerConnection.PeerConnectionState.FAILED to 0,
                PeerConnection.PeerConnectionState.DISCONNECTED to 1,
                PeerConnection.PeerConnectionState.CONNECTING to 2,
                PeerConnection.PeerConnectionState.NEW to 3,
                PeerConnection.PeerConnectionState.CONNECTED to 4,
                PeerConnection.PeerConnectionState.CLOSED to 5,
            )
        val SIGNALING_PRIORITY =
            mapOf(
                PeerConnection.SignalingState.CLOSED to 0,
                PeerConnection.SignalingState.HAVE_LOCAL_OFFER to 1,
                PeerConnection.SignalingState.HAVE_REMOTE_OFFER to 2,
                PeerConnection.SignalingState.HAVE_LOCAL_PRANSWER to 3,
                PeerConnection.SignalingState.HAVE_REMOTE_PRANSWER to 4,
                PeerConnection.SignalingState.STABLE to 5,
            )
    }
}
