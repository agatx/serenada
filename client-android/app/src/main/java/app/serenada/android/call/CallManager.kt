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
import java.util.concurrent.atomic.AtomicBoolean

class CallManager(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = ApiClient(okHttpClient)
    private val settingsStore = SettingsStore(appContext)
    private val recentCallStore = RecentCallStore(appContext)
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

    private val _roomStatuses = mutableStateOf<Map<String, Int>>(emptyMap())
    val roomStatuses: State<Map<String, Int>> = _roomStatuses

    private var currentRoomId: String? = null
    private var clientId: String? = null
    private var hostCid: String? = null
    private var callStartTimeMs: Long? = null
    private var watchedRoomIds: List<String> = emptyList()
    private var pendingJoinRoom: String? = null
    private var pendingJoinSnapshotId: String? = null
    private var joinAttemptSerial = 0L
    private var reconnectAttempts = 0
    private var sentOffer = false
    private var isMakingOffer = false
    private var pendingIceRestart = false
    private var lastIceRestartAt = 0L
    private var iceRestartRunnable: Runnable? = null
    private var offerTimeoutRunnable: Runnable? = null
    private var remoteVideoStatePollRunnable: Runnable? = null
    private var webrtcStatsRequestInFlight = false
    private var lastWebRtcStatsPollAtMs = 0L
    private val pendingMessages = ArrayDeque<SignalingMessage>()
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
            pendingJoinRoom?.let { join ->
                pendingJoinRoom = null
                sendJoin(join, pendingJoinSnapshotId)
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
            updateState(_uiState.value.copy(isSignalingConnected = false, activeTransport = null))
            if (shouldReconnectSignaling()) {
                scheduleReconnect()
            }
        }
    })

    init {
        registerConnectivityListener()
        refreshRecentCalls()
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
        if (changed && currentRoomId == null && watchedRoomIds.isNotEmpty()) {
            signalingClient.close()
            watchRecentRoomsIfNeeded()
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

    fun handleDeepLink(uri: Uri) {
        val roomId = extractRoomId(uri) ?: return
        val state = _uiState.value
        val isSameActiveRoom = (state.roomId == roomId || currentRoomId == roomId) &&
                state.phase != CallPhase.Idle &&
                state.phase != CallPhase.Error &&
                state.phase != CallPhase.Ending
        if (isSameActiveRoom) {
            Log.d("CallManager", "Ignoring duplicate deep link for active room $roomId")
            return
        }
        val linkHost = uri.host
        if (!linkHost.isNullOrBlank()) {
            updateServerHost(linkHost)
        }
        joinRoom(roomId)
    }

    private fun extractRoomId(uri: Uri): String? {
        return uri.pathSegments.lastOrNull()
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
            val roomId = extractRoomId(uri)
            if (roomId != null) {
                updateServerHost(uri.host ?: serverHost.value)
                joinRoom(roomId)
                return
            }
        }
        joinRoom(trimmed)
    }

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

    fun joinRoom(roomId: String) {
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
        currentRoomId = roomId
        val joinAttemptId = ++joinAttemptSerial
        callStartTimeMs = System.currentTimeMillis()
        sentOffer = false
        pendingMessages.clear()
        pendingJoinSnapshotId = null

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
                isFlashAvailable = false,
                isFlashEnabled = false
            )
        )

        acquirePerformanceLocks()
        activateAudioSession()
        webRtcEngine.startLocalMedia()

        // Apply defaults immediately after starting media
        if (!defaultAudio) webRtcEngine.toggleAudio(false)
        applyLocalVideoPreference()

        startRemoteVideoStatePolling()
        prepareJoinSnapshotAndConnect(roomId, joinAttemptId)
        CallService.start(appContext, roomId)
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

    fun startScreenShare(intent: Intent) {
        if (_uiState.value.isScreenSharing) return
        val roomId = currentRoomId
        if (roomId == null) {
            Log.w("CallManager", "Failed to start screen sharing: roomId is missing")
            return
        }
        CallService.start(appContext, roomId, includeMediaProjection = true)
        startScreenShareWhenForegroundReady(intent, roomId, attemptsRemaining = 15)
    }

    fun stopScreenShare() {
        if (!_uiState.value.isScreenSharing) return
        if (!webRtcEngine.stopScreenShare()) {
            Log.w("CallManager", "Failed to stop screen sharing")
            return
        }
        currentRoomId?.let { roomId ->
            CallService.start(appContext, roomId)
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
                CallService.start(appContext, roomId)
                Log.w("CallManager", "Failed to start screen sharing")
                return
            }
            updateState(_uiState.value.copy(isScreenSharing = true))
            applyLocalVideoPreference()
            return
        }
        if (attemptsRemaining <= 0) {
            CallService.start(appContext, roomId)
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
        val roomToJoin = currentRoomId
        if (signalingClient.isConnected()) {
            if (!roomToJoin.isNullOrBlank()) {
                pendingJoinRoom = null
                sendJoin(roomToJoin, pendingJoinSnapshotId)
            }
            sendWatchRoomsIfNeeded()
            return
        }
        pendingJoinRoom = roomToJoin
        signalingClient.connect(serverHost.value)
    }

    private fun sendJoin(roomId: String, snapshotId: String? = null) {
        val buildPayload = { endpoint: String? ->
            JSONObject().apply {
                put("device", "android")
                put("capabilities", JSONObject().apply { put("trickleIce", true) })
                val reconnectCid = clientId ?: settingsStore.reconnectCid
                reconnectCid?.let { put("reconnectCid", it) }
                endpoint?.trim()?.takeIf { it.isNotBlank() }?.let { put("pushEndpoint", it) }
                val joinSnapshotId = snapshotId?.ifBlank { null }
                if (joinSnapshotId != null) {
                    put("snapshotId", joinSnapshotId)
                    Log.d("CallManager", "Including snapshotId in join payload")
                }
            }
        }
        pendingJoinSnapshotId = null
        val sent = AtomicBoolean(false)
        fun sendWithEndpoint(endpoint: String?) {
            if (!sent.compareAndSet(false, true)) return
            if (currentRoomId != roomId) return
            if (!signalingClient.isConnected()) return
            val msg = SignalingMessage(
                type = "join",
                rid = roomId,
                sid = null,
                cid = null,
                to = null,
                payload = buildPayload(endpoint)
            )
            signalingClient.send(msg)
        }

        val fallbackSend = Runnable { sendWithEndpoint(null) }
        val cachedEndpoint = pushSubscriptionManager.cachedEndpoint()
        if (!cachedEndpoint.isNullOrBlank()) {
            sendWithEndpoint(cachedEndpoint)
            return
        }

        handler.postDelayed(fallbackSend, JOIN_PUSH_ENDPOINT_WAIT_MS)
        pushSubscriptionManager.refreshPushEndpoint { endpoint ->
            handler.post {
                handler.removeCallbacks(fallbackSend)
                sendWithEndpoint(endpoint)
            }
        }
    }

    private fun prepareJoinSnapshotAndConnect(roomId: String, joinAttemptId: Long) {
        joinSnapshotFeature.prepareSnapshotId(
            host = serverHost.value,
            roomId = roomId,
            isVideoEnabled = { _uiState.value.localVideoEnabled },
            isJoinAttemptActive = { isJoinAttemptActive(roomId, joinAttemptId) }
        ) { snapshotId ->
            if (!isJoinAttemptActive(roomId, joinAttemptId)) {
                return@prepareSnapshotId
            }
            pendingJoinSnapshotId = snapshotId
            ensureSignalingConnection()
        }
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
            "offer", "answer", "ice" -> handleSignalingPayload(msg)
            "error" -> handleError(msg)
        }
    }

    private fun handleJoined(msg: SignalingMessage) {
        clientId = msg.cid
        clientId?.let { settingsStore.reconnectCid = it }
        val joinedRoomId = msg.rid ?: currentRoomId
        if (!joinedRoomId.isNullOrBlank()) {
            pushSubscriptionManager.subscribeRoom(joinedRoomId, serverHost.value)
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
    }

    private fun handleRoomState(msg: SignalingMessage) {
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
                val sdp = msg.payload?.optString("sdp").orEmpty().ifBlank { return }
                webRtcEngine.setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
                    webRtcEngine.createAnswer(onSdp = { answerSdp ->
                        val payload = JSONObject().apply { put("sdp", answerSdp) }
                        sendMessage("answer", payload)
                    })
                }
            }
            "answer" -> {
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
            maybeSendOffer()
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
            }
        }
        offerTimeoutRunnable = runnable
        handler.postDelayed(runnable, 8000)
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
        if (now - lastIceRestartAt < 10_000) return
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
        apiClient.fetchTurnCredentials(serverHost.value, token) { result ->
            handler.post {
                result
                    .onSuccess { creds ->
                        applyTurnCredentials(creds)
                    }
                    .onFailure {
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
        val hostCid = payload.optString("hostCid", "")
        if (hostCid.isBlank()) return null
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
        return RoomState(hostCid, participants)
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
        webRtcEngine.collectWebRtcStatsSummary { summary ->
            handler.post {
                webrtcStatsRequestInFlight = false
                lastWebRtcStatsPollAtMs = System.currentTimeMillis()
                if (_uiState.value.webrtcStatsSummary != summary) {
                    updateState(_uiState.value.copy(webrtcStatsSummary = summary))
                }
                Log.d("CallManager", "[WebRTCStats] $summary")
            }
        }
    }

    private fun isHost(): Boolean = clientId != null && clientId == hostCid

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
        pendingJoinRoom = null
        pendingJoinSnapshotId = null
        pendingMessages.clear()
        reconnectAttempts = 0
        sentOffer = false
        isMakingOffer = false
        pendingIceRestart = false
        clearOfferTimeout()
        clearIceRestartTimer()
        userPreferredVideoEnabled = true
        isVideoPausedByProximity = false
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
        acquireWifiLock()
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
        val backoff = (500L * (1 shl (reconnectAttempts - 1))).coerceAtMost(5000L)
        handler.postDelayed({
            if (signalingClient.isConnected()) {
                return@postDelayed
            }
            if (roomId != null && currentRoomId == roomId) {
                pendingJoinRoom = roomId
                signalingClient.connect(serverHost.value)
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
        watchedRoomIds = calls.map { it.roomId }
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
            signalingClient.connect(serverHost.value)
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

    private companion object {
        const val WEBRTC_STATS_POLL_INTERVAL_MS = 2000L
        const val JOIN_PUSH_ENDPOINT_WAIT_MS = 250L
        const val CPU_WAKE_LOCK_TAG = "serenada:call-cpu"
        const val WIFI_PERF_LOCK_TAG = "serenada:call-wifi"
    }
}
