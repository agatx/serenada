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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (_uiState.value.phase == CallPhase.InCall) {
                    scheduleIceRestart("network-online", 0)
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

    private val _roomStatuses = mutableStateOf<Map<String, Int>>(emptyMap())
    val roomStatuses: State<Map<String, Int>> = _roomStatuses

    private var currentRoomId: String? = null
    private var activeCallHostOverride: String? = null
    private var clientId: String? = null
    private var hostCid: String? = null
    private var callStartTimeMs: Long? = null
    private var watchedRoomIds: List<String> = emptyList()
    private var pendingJoinRoom: String? = null
    private var hasNotifiedPushForJoin = false
    // All mutable state below is accessed exclusively from the handler thread — no synchronization needed.
    private var joinAttemptSerial = 0L
    private var reconnectAttempts = 0
    private var sentOffer = false
    private var isMakingOffer = false
    private var pendingIceRestart = false
    private var lastIceRestartAt = 0L
    private var iceRestartRunnable: Runnable? = null
    private var offerTimeoutRunnable: Runnable? = null
    private var joinTimeoutRunnable: Runnable? = null
    private var joinKickstartRunnable: Runnable? = null
    private var joinRecoveryRunnable: Runnable? = null
    private var nonHostOfferFallbackRunnable: Runnable? = null
    private var nonHostOfferFallbackAttempts = 0
    private var turnRefreshRunnable: Runnable? = null
    private var remoteVideoStatePollRunnable: Runnable? = null
    private var webrtcStatsRequestInFlight = false
    private var lastWebRtcStatsPollAtMs = 0L
    private val pendingMessages = java.util.ArrayDeque<SignalingMessage>()
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
                    isReconnecting = false,
                    activeTransport = activeTransport
                )
            )
            pendingJoinRoom?.let { join ->
                pendingJoinRoom = null
                sendJoin(join)
            }
            sendWatchRoomsIfNeeded()
            if (pendingIceRestart) {
                handler.post { triggerIceRestart("signaling-reconnect") }
            }
        }

        override fun onMessage(message: SignalingMessage) {
            handleSignalingMessage(message)
        }

        override fun onClosed(reason: String) {
            val shouldReconnect = shouldReconnectSignaling()
            updateState(_uiState.value.copy(
                isSignalingConnected = false,
                isReconnecting = shouldReconnect,
                activeTransport = null
            ))
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
            onLocalIceCandidate = { candidate ->
                val payload = JSONObject().apply {
                    val candidateJson = JSONObject()
                    candidateJson.put("candidate", candidate.sdp)
                    candidateJson.put("sdpMid", candidate.sdpMid)
                    candidateJson.put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidateJson)
                }
                sendMessage("ice", payload)
            },
            onConnectionState = { state ->
                handler.post {
                    val messageResId = when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> R.string.call_status_connected
                        PeerConnection.PeerConnectionState.CONNECTING -> R.string.call_status_connecting
                        PeerConnection.PeerConnectionState.DISCONNECTED -> R.string.call_status_disconnected
                        PeerConnection.PeerConnectionState.FAILED -> R.string.call_status_connection_failed
                        PeerConnection.PeerConnectionState.CLOSED -> R.string.call_status_call_ended
                        else -> null
                    }
                    updateState(
                        _uiState.value.copy(
                            statusMessageResId = messageResId,
                            connectionState = state.name
                        )
                    )
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            clearIceRestartTimer()
                            pendingIceRestart = false
                        }

                        PeerConnection.PeerConnectionState.DISCONNECTED -> scheduleIceRestart(
                            "conn-disconnected",
                            2000
                        )

                        PeerConnection.PeerConnectionState.FAILED -> scheduleIceRestart("conn-failed", 0)
                        else -> {}
                    }
                }
            },
            onIceConnectionState = { state ->
                handler.post {
                    updateState(_uiState.value.copy(iceConnectionState = state.name))
                    when (state) {
                        PeerConnection.IceConnectionState.DISCONNECTED -> scheduleIceRestart(
                            "ice-disconnected",
                            2000
                        )

                        PeerConnection.IceConnectionState.FAILED -> scheduleIceRestart("ice-failed", 0)
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            clearIceRestartTimer()
                            pendingIceRestart = false
                        }

                        else -> {}
                    }
                }
            },
            onSignalingState = { state ->
                handler.post {
                    if (state == PeerConnection.SignalingState.STABLE) {
                        clearOfferTimeout()
                        if (pendingIceRestart) {
                            pendingIceRestart = false
                            triggerIceRestart("pending-retry")
                        }
                    }
                    updateState(_uiState.value.copy(signalingState = state.name))
                }
            },
            onRenegotiationNeededCallback = {
                handler.post { maybeSendOffer(force = true, iceRestart = false) }
            },
            onRemoteVideoTrack = { _ ->
                handler.post {
                    refreshRemoteVideoEnabled()
                }
            },
            onCameraFacingChanged = { isFront ->
                handler.post {
                    updateState(_uiState.value.copy(isFrontCamera = isFront))
                }
            },
            onCameraModeChanged = { mode ->
                handler.post {
                    updateState(_uiState.value.copy(localCameraMode = mode))
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
        val normalizedHost = normalizeHostValue(host)
        val hostOverride = normalizedHost?.takeUnless { isTrustedDeepLinkHost(it) }
        if (!isValidRoomId(cleanRoomId)) return
        savedRoomStore.saveRoom(
            SavedRoom(
                roomId = cleanRoomId,
                name = cleanName,
                createdAt = System.currentTimeMillis(),
                host = hostOverride
            )
        )
        refreshSavedRooms()
    }

    fun joinSavedRoom(room: SavedRoom) {
        val roomHostOverride = normalizeHostValue(room.host)?.takeUnless { isTrustedDeepLinkHost(it) }
        joinRoom(room.roomId, roomHostOverride)
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
        val roomHostOverride = normalizedHost.takeUnless { isTrustedDeepLinkHost(it) }
        apiClient.createRoomId(normalizedHost) { result ->
            handler.post {
                result
                    .onSuccess { roomId ->
                        saveRoom(roomId, normalizedName, roomHostOverride)
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
            saveRoom(deepLinkTarget.roomId, roomName, hostPolicy.oneOffHost)
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
                    saveRoom(deepLinkTarget.roomId, roomName, hostPolicy.oneOffHost)
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
        currentRoomId = roomId
        val joinAttemptId = ++joinAttemptSerial
        callStartTimeMs = System.currentTimeMillis()
        sentOffer = false
        pendingMessages.clear()
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
                localCameraMode = LocalCameraMode.SELFIE,
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
        if (_uiState.value.phase == CallPhase.Idle) return
        if (isHost()) {
            sendMessage("end_room", null)
        } else {
            sendMessage("leave", null)
        }
        cleanupCall(R.string.call_status_call_ended)
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
        webRtcEngine.attachRemoteRenderer(renderer, rendererEvents)
    }

    fun detachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRtcEngine.detachRemoteRenderer(renderer)
    }

    fun attachLocalSink(sink: org.webrtc.VideoSink) {
        webRtcEngine.attachLocalSink(sink)
    }

    fun detachLocalSink(sink: org.webrtc.VideoSink) {
        webRtcEngine.detachLocalSink(sink)
    }

    fun attachRemoteSink(sink: org.webrtc.VideoSink) {
        webRtcEngine.attachRemoteSink(sink)
    }

    fun detachRemoteSink(sink: org.webrtc.VideoSink) {
        webRtcEngine.detachRemoteSink(sink)
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
                put("capabilities", JSONObject().apply { put("trickleIce", true) })
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

        val statuses = mutableMapOf<String, Int>()
        val keys = payload.keys()
        while (keys.hasNext()) {
            val rid = keys.next()
            if (!watched.contains(rid)) continue
            statuses[rid] = payload.optInt(rid, 0).coerceAtLeast(0)
        }
        _roomStatuses.value = statuses
    }

    private fun handleRoomStatusUpdate(msg: SignalingMessage) {
        val payload = msg.payload ?: return
        val rid = payload.optString("rid").orEmpty()
        if (!watchedRoomIds.contains(rid)) return
        val count = payload.optInt("count", 0).coerceAtLeast(0)
        _roomStatuses.value = _roomStatuses.value.toMutableMap().apply {
            this[rid] = count
        }
    }

    private fun handleError(msg: SignalingMessage) {
        val rawMessage = msg.payload?.optString("message").orEmpty().ifBlank { null }
        clearJoinTimeout()
        resetResources()
        updateState(
            CallUiState(
                phase = CallPhase.Error,
                errorMessageResId = if (rawMessage == null) R.string.error_unknown else null,
                errorMessageText = rawMessage
            )
        )
    }

    private fun handleSignalingPayload(msg: SignalingMessage) {
        if (!webRtcEngine.isReady()) {
            webRtcEngine.ensurePeerConnection()
            if (!webRtcEngine.isReady()) {
                pendingMessages.add(msg)
                return
            }
        }
        processSignalingPayload(msg)
    }

    private fun processSignalingPayload(msg: SignalingMessage) {
        when (msg.type) {
            "offer" -> {
                clearNonHostOfferFallback()
                val sdp = msg.payload?.optString("sdp").orEmpty().ifBlank { return }
                webRtcEngine.setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
                    webRtcEngine.createAnswer(onSdp = { answerSdp ->
                        val payload = JSONObject().apply { put("sdp", answerSdp) }
                        sendMessage("answer", payload)
                    })
                }
            }
            "answer" -> {
                clearNonHostOfferFallback()
                val sdp = msg.payload?.optString("sdp").orEmpty().ifBlank { return }
                webRtcEngine.setRemoteDescription(SessionDescription.Type.ANSWER, sdp) {
                    clearOfferTimeout()
                    pendingIceRestart = false
                }
            }
            "ice" -> {
                val candidateJson = msg.payload?.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(
                    candidateJson.optString("sdpMid").ifBlank { null },
                    candidateJson.optInt("sdpMLineIndex", 0),
                    candidateJson.optString("candidate", "")
                )
                webRtcEngine.addIceCandidate(candidate)
            }
        }
    }

    private fun updateParticipants(roomState: RoomState) {
        val count = roomState.participants.size
        val isHostNow = clientId != null && clientId == roomState.hostCid
        val phase = when {
            count <= 1 -> CallPhase.Waiting
            else -> CallPhase.InCall
        }
        if (phase != CallPhase.Joining) {
            clearJoinTimeout()
        }
        if (count <= 1) {
            sentOffer = false
            clearOfferTimeout()
            clearIceRestartTimer()
            pendingIceRestart = false
            isMakingOffer = false
            if (webRtcEngine.isReady()) {
                webRtcEngine.closePeerConnection()
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
        if (count > 1) {
            webRtcEngine.ensurePeerConnection()
        }
        if (count > 1 && isHostNow) {
            clearNonHostOfferFallback()
            maybeSendOffer()
        } else if (count > 1) {
            maybeScheduleNonHostOfferFallback("participants")
        }
    }

    private fun maybeSendOffer(force: Boolean = false, iceRestart: Boolean = false) {
        if (isMakingOffer) {
            if (iceRestart) {
                pendingIceRestart = true
            }
            return
        }
        if (!force && sentOffer) return
        if (!canOffer()) return
        val signalingState = webRtcEngine.getSignalingState()
        if (signalingState != null && signalingState != PeerConnection.SignalingState.STABLE) {
            if (iceRestart) {
                pendingIceRestart = true
            }
            return
        }
        isMakingOffer = true
        val started = webRtcEngine.createOffer(
            iceRestart = iceRestart,
            onSdp = { sdp ->
                val payload = JSONObject().apply { put("sdp", sdp) }
                sendMessage("offer", payload)
                scheduleOfferTimeout()
            },
            onComplete = { success ->
                handler.post {
                    isMakingOffer = false
                    if (!success && iceRestart) {
                        scheduleIceRestart("offer-failed", 500)
                    }
                }
            }
        )
        if (!started) {
            isMakingOffer = false
            if (iceRestart) {
                pendingIceRestart = true
            }
            return
        }
        if (!force) {
            sentOffer = true
        }
    }

    private fun canOffer(): Boolean {
        val state = _uiState.value
        if (!state.isHost || state.participantCount <= 1) return false
        if (!webRtcEngine.isReady()) return false
        if (!signalingClient.isConnected()) return false
        return true
    }

    private fun scheduleOfferTimeout() {
        clearOfferTimeout()
        val runnable = Runnable {
            offerTimeoutRunnable = null
            val signalingState = webRtcEngine.getSignalingState()
            if (signalingState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                Log.w("CallManager", "Offer timeout; rolling back and retrying")
                pendingIceRestart = true
                webRtcEngine.rollbackLocalDescription {
                    handler.post { scheduleIceRestart("offer-timeout", 0) }
                }
            } else {
                // Always schedule ICE restart on offer timeout regardless of signaling state
                scheduleIceRestart("offer-timeout-stale", 0)
            }
        }
        offerTimeoutRunnable = runnable
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

    private fun clearOfferTimeout() {
        offerTimeoutRunnable?.let { handler.removeCallbacks(it) }
        offerTimeoutRunnable = null
    }

    private fun scheduleIceRestart(reason: String, delayMs: Long) {
        if (!canOffer()) {
            pendingIceRestart = true
            return
        }
        if (iceRestartRunnable != null) return
        val now = System.currentTimeMillis()
        if (now - lastIceRestartAt < WebRtcResilienceConstants.ICE_RESTART_COOLDOWN_MS) return
        val runnable = Runnable {
            iceRestartRunnable = null
            triggerIceRestart(reason)
        }
        iceRestartRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearIceRestartTimer() {
        iceRestartRunnable?.let { handler.removeCallbacks(it) }
        iceRestartRunnable = null
    }

    private fun triggerIceRestart(reason: String) {
        if (!canOffer()) {
            pendingIceRestart = true
            return
        }
        if (isMakingOffer) {
            pendingIceRestart = true
            return
        }
        Log.w("CallManager", "ICE restart triggered ($reason)")
        lastIceRestartAt = System.currentTimeMillis()
        pendingIceRestart = false
        maybeSendOffer(force = true, iceRestart = true)
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
        flushPendingMessages()
        maybeSendOffer()
    }

    private fun applyDefaultIceServers() {
        val servers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        webRtcEngine.setIceServers(servers)
        flushPendingMessages()
        maybeSendOffer()
    }

    private fun flushPendingMessages() {
        while (pendingMessages.isNotEmpty()) {
            processSignalingPayload(pendingMessages.removeFirst())
        }
    }

    private fun parseRoomState(payload: JSONObject?): RoomState? {
        if (payload == null) return null
        val parsedHostCid = payload.optString("hostCid", "").ifBlank { null }
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
        return RoomState(resolvedHostCid, participants)
    }

    private fun updateState(state: CallUiState) {
        _uiState.value = state
    }

    private fun refreshRemoteVideoEnabled() {
        val remoteVideoEnabled = webRtcEngine.isRemoteVideoTrackEnabled()
        if (_uiState.value.remoteVideoEnabled != remoteVideoEnabled) {
            Log.d(
                "CallManager",
                "[RemoteVideo] uiEnabled->$remoteVideoEnabled ${webRtcEngine.remoteVideoDiagnostics()}"
            )
            updateState(_uiState.value.copy(remoteVideoEnabled = remoteVideoEnabled))
        }
    }

    private fun startRemoteVideoStatePolling() {
        if (remoteVideoStatePollRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                refreshRemoteVideoEnabled()
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

        webrtcStatsRequestInFlight = true
        webRtcStatsExecutor.execute {
            webRtcEngine.collectWebRtcStats { summary, realtimeStats ->
                handler.post {
                    webrtcStatsRequestInFlight = false
                    lastWebRtcStatsPollAtMs = System.currentTimeMillis()
                    val state = _uiState.value
                    if (state.webrtcStatsSummary != summary || state.realtimeCallStats != realtimeStats) {
                        updateState(
                            state.copy(
                                webrtcStatsSummary = summary,
                                realtimeCallStats = realtimeStats
                            )
                        )
                    }
                    Log.d("CallManager", "[WebRTCStats] $summary")
                }
            }
        }
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
        if (currentRoomId == null) return
        val state = _uiState.value
        if (state.participantCount <= 1) { clearNonHostOfferFallback(); return }
        if (state.isHost) { clearNonHostOfferFallback(); return }
        if (!signalingClient.isConnected()) return
        if (nonHostOfferFallbackRunnable != null) return
        if (nonHostOfferFallbackAttempts >= WebRtcResilienceConstants.NON_HOST_FALLBACK_MAX_ATTEMPTS) return

        val roomId = currentRoomId
        Log.d("CallManager", "Non-host fallback scheduled ($reason)")
        val runnable = Runnable {
            nonHostOfferFallbackRunnable = null
            if (currentRoomId != roomId) return@Runnable
            nonHostOfferFallbackAttempts++
            Log.w("CallManager", "Non-host fallback offer (attempt $nonHostOfferFallbackAttempts)")
            maybeSendNonHostFallbackOffer()
        }
        nonHostOfferFallbackRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.NON_HOST_FALLBACK_DELAY_MS)
    }

    private fun clearNonHostOfferFallback() {
        nonHostOfferFallbackRunnable?.let { handler.removeCallbacks(it) }
        nonHostOfferFallbackRunnable = null
    }

    private fun maybeSendNonHostFallbackOffer() {
        val state = _uiState.value
        if (state.participantCount <= 1) return
        if (state.isHost) return
        if (!signalingClient.isConnected()) return
        if (!webRtcEngine.isReady()) return
        val signalingState = webRtcEngine.getSignalingState()
        if (signalingState != null && signalingState != PeerConnection.SignalingState.STABLE) {
            maybeScheduleNonHostOfferFallback("signaling-not-stable")
            return
        }
        if (webRtcEngine.hasRemoteDescription()) return
        if (isMakingOffer) {
            maybeScheduleNonHostOfferFallback("already-making-offer")
            return
        }

        Log.d("CallManager", "Non-host fallback offer triggered")
        isMakingOffer = true
        val started = webRtcEngine.createOffer(
            onSdp = { sdp ->
                val payload = JSONObject().apply { put("sdp", sdp) }
                sendMessage("offer", payload)
                scheduleOfferTimeout()
            },
            onComplete = { success ->
                handler.post {
                    isMakingOffer = false
                    if (!success) {
                        maybeScheduleNonHostOfferFallback("offer-failed")
                    }
                }
            }
        )
        if (!started) {
            isMakingOffer = false
            maybeScheduleNonHostOfferFallback("offer-not-started")
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
        nonHostOfferFallbackAttempts = 0
        clearTurnRefresh()
        deactivateAudioSession()
        releasePerformanceLocks()
        stopRemoteVideoStatePolling()
        signalingClient.close()
        webRtcEngine.release()
        CallService.stop(appContext)
        currentRoomId = null
        hostCid = null
        clientId = null
        callStartTimeMs = null
        activeCallHostOverride = null
        pendingJoinRoom = null
        pendingMessages.clear()
        reconnectAttempts = 0
        sentOffer = false
        isMakingOffer = false
        pendingIceRestart = false
        clearOfferTimeout()
        clearIceRestartTimer()
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
        _recentCalls.value = calls
        refreshWatchedRooms()
    }

    private fun refreshSavedRooms() {
        val rooms = savedRoomStore.getSavedRooms()
        _savedRooms.value = rooms
        syncSavedRoomPushSubscriptions(rooms)
        refreshWatchedRooms()
    }

    private fun syncSavedRoomPushSubscriptions(rooms: List<SavedRoom>) {
        val host = serverHost.value
        rooms
            .filter { shouldWatchSavedRoom(it) }
            .forEach { room ->
                pushSubscriptionManager.subscribeRoom(room.roomId, host)
            }
    }

    private fun savedRoomNameForNotification(roomId: String): String? {
        return _savedRooms.value.firstOrNull { it.roomId == roomId }?.name
    }

    private fun shouldWatchSavedRoom(room: SavedRoom): Boolean {
        val roomHost = room.host ?: return true
        return roomHost.equals(serverHost.value, ignoreCase = true)
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
            .filter { shouldWatchSavedRoom(it) }
            .forEach { mergedRoomIds.add(it.roomId) }
        _recentCalls.value.forEach { mergedRoomIds.add(it.roomId) }
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
                durationSeconds = durationSeconds
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
    }
}
