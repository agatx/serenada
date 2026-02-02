package app.serenada.android.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.serenada.android.data.SettingsStore
import app.serenada.android.network.ApiClient
import app.serenada.android.network.TurnCredentials
import app.serenada.android.service.CallService
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class CallManager(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = ApiClient(okHttpClient)
    private val settingsStore = SettingsStore(appContext)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallbackRegistered = false
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

    private var currentRoomId: String? = null
    private var clientId: String? = null
    private var hostCid: String? = null
    private var pendingJoinRoom: String? = null
    private var reconnectAttempts = 0
    private var sentOffer = false
    private var isMakingOffer = false
    private var pendingIceRestart = false
    private var lastIceRestartAt = 0L
    private var iceRestartRunnable: Runnable? = null
    private var offerTimeoutRunnable: Runnable? = null
    private val pendingMessages = ArrayDeque<SignalingMessage>()

    private val webRtcEngine = WebRtcEngine(
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
                val message = when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> "Connected"
                    PeerConnection.PeerConnectionState.CONNECTING -> "Connecting"
                    PeerConnection.PeerConnectionState.DISCONNECTED -> "Disconnected"
                    PeerConnection.PeerConnectionState.FAILED -> "Connection failed"
                    PeerConnection.PeerConnectionState.CLOSED -> "Call ended"
                    else -> ""
                }
                updateState(_uiState.value.copy(statusMessage = message))
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        clearIceRestartTimer()
                        pendingIceRestart = false
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> scheduleIceRestart("conn-disconnected", 2000)
                    PeerConnection.PeerConnectionState.FAILED -> scheduleIceRestart("conn-failed", 0)
                    else -> {}
                }
            }
        },
        onIceConnectionState = { state ->
            handler.post {
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED -> scheduleIceRestart("ice-disconnected", 2000)
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
            }
        },
        onRenegotiationNeededCallback = {
            handler.post { maybeSendOffer(force = true, iceRestart = false) }
        },
        onRemoteVideoTrack = { track ->
            handler.post {
                updateState(_uiState.value.copy(remoteVideoEnabled = track != null))
            }
        }
    )

    private val signalingClient = SignalingClient(okHttpClient, handler, object : SignalingClient.Listener {
        override fun onOpen() {
            reconnectAttempts = 0
            pendingJoinRoom?.let { join ->
                pendingJoinRoom = null
                sendJoin(join)
            }
            if (pendingIceRestart) {
                handler.post { triggerIceRestart("signaling-reconnect") }
            }
        }

        override fun onMessage(message: SignalingMessage) {
            handleSignalingMessage(message)
        }

        override fun onClosed(reason: String) {
            val activeRoom = currentRoomId
            if (activeRoom != null && _uiState.value.phase != CallPhase.Ending) {
                scheduleReconnect()
            }
        }
    })

    init {
        registerConnectivityListener()
    }

    fun updateServerHost(host: String) {
        val trimmed = host.trim().ifBlank { SettingsStore.DEFAULT_HOST }
        settingsStore.host = trimmed
        _serverHost.value = trimmed
    }

    fun handleDeepLink(uri: Uri) {
        val roomId = extractRoomId(uri) ?: return
        val linkHost = uri.host
        if (!linkHost.isNullOrBlank()) {
            updateServerHost(linkHost)
        }
        joinRoom(roomId)
    }

    fun joinFromInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            updateState(_uiState.value.copy(phase = CallPhase.Error, errorMessage = "Enter a room link or ID"))
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
        updateState(_uiState.value.copy(phase = CallPhase.CreatingRoom, statusMessage = "Creating room..."))
        apiClient.createRoomId(serverHost.value) { result ->
            handler.post {
                result
                    .onSuccess { roomId ->
                        joinRoom(roomId)
                    }
                    .onFailure { err ->
                        updateState(
                            _uiState.value.copy(
                                phase = CallPhase.Error,
                                errorMessage = err.message ?: "Failed to create room"
                            )
                        )
                    }
            }
        }
    }

    fun joinRoom(roomId: String) {
        if (roomId.isBlank()) {
            updateState(_uiState.value.copy(phase = CallPhase.Error, errorMessage = "Invalid room ID"))
            return
        }
        currentRoomId = roomId
        sentOffer = false
        pendingMessages.clear()
        updateState(
            _uiState.value.copy(
                phase = CallPhase.Joining,
                roomId = roomId,
                statusMessage = "Joining room...",
                errorMessage = null
            )
        )
        webRtcEngine.startLocalMedia()
        ensureSignalingConnection()
        CallService.start(appContext, roomId)
    }

    fun leaveCall() {
        if (_uiState.value.phase == CallPhase.Idle) return
        sendMessage("leave", null)
        cleanupCall("Left room")
    }

    fun dismissError() {
        if (_uiState.value.phase == CallPhase.Error) {
            updateState(CallUiState())
        }
    }

    fun endCall() {
        if (_uiState.value.phase == CallPhase.Idle) return
        if (isHost()) {
            sendMessage("end_room", null)
        } else {
            sendMessage("leave", null)
        }
        cleanupCall("Call ended")
    }

    fun toggleAudio() {
        val enabled = !_uiState.value.localAudioEnabled
        webRtcEngine.toggleAudio(enabled)
        updateState(_uiState.value.copy(localAudioEnabled = enabled))
    }

    fun toggleVideo() {
        val enabled = !_uiState.value.localVideoEnabled
        webRtcEngine.toggleVideo(enabled)
        updateState(_uiState.value.copy(localVideoEnabled = enabled))
    }

    fun flipCamera() {
        webRtcEngine.flipCamera()
    }

    fun attachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRtcEngine.attachLocalRenderer(renderer)
    }

    fun detachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRtcEngine.detachLocalRenderer(renderer)
    }

    fun attachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRtcEngine.attachRemoteRenderer(renderer)
    }

    fun detachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRtcEngine.detachRemoteRenderer(renderer)
    }

    fun eglContext(): org.webrtc.EglBase.Context = webRtcEngine.getEglContext()

    private fun ensureSignalingConnection() {
        if (signalingClient.isConnected()) {
            pendingJoinRoom?.let { join ->
                pendingJoinRoom = null
                sendJoin(join)
            }
            return
        }
        pendingJoinRoom = currentRoomId
        signalingClient.connect(serverHost.value)
    }

    private fun sendJoin(roomId: String) {
        val payload = JSONObject().apply {
            put("device", "android")
            put("capabilities", JSONObject().apply { put("trickleIce", true) })
            val reconnectCid = clientId ?: settingsStore.reconnectCid
            reconnectCid?.let { put("reconnectCid", it) }
        }
        val msg = SignalingMessage(
            type = "join",
            rid = roomId,
            sid = null,
            cid = null,
            to = null,
            payload = payload
        )
        signalingClient.send(msg)
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

    private fun handleSignalingMessage(msg: SignalingMessage) {
        Log.d("CallManager", "RX ${msg.type}")
        when (msg.type) {
            "joined" -> handleJoined(msg)
            "room_state" -> handleRoomState(msg)
            "room_ended" -> handleRoomEnded(msg)
            "offer", "answer", "ice" -> handleSignalingPayload(msg)
            "error" -> handleError(msg)
        }
    }

    private fun handleJoined(msg: SignalingMessage) {
        clientId = msg.cid
        clientId?.let { settingsStore.reconnectCid = it }
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
        cleanupCall("Room ended")
    }

    private fun handleError(msg: SignalingMessage) {
        val message = msg.payload?.optString("message", "Unknown error") ?: "Unknown error"
        resetResources()
        updateState(CallUiState(phase = CallPhase.Error, errorMessage = message))
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
                statusMessage = if (count <= 1) "Waiting for someone to join" else "In call"
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
                    participants.add(Participant(cid, participantObj.optLong("joinedAt")))
                }
            }
        }
        return RoomState(hostCid, participants)
    }

    private fun updateState(state: CallUiState) {
        _uiState.value = state
    }

    private fun isHost(): Boolean = clientId != null && clientId == hostCid

    private fun cleanupCall(message: String) {
        updateState(
            _uiState.value.copy(
                phase = CallPhase.Ending,
                statusMessage = message
            )
        )
        settingsStore.reconnectCid = null
        resetResources()
        updateState(CallUiState(phase = CallPhase.Idle, statusMessage = ""))
    }

    private fun resetResources() {
        signalingClient.close()
        webRtcEngine.release()
        CallService.stop(appContext)
        currentRoomId = null
        hostCid = null
        clientId = null
        pendingJoinRoom = null
        pendingMessages.clear()
        reconnectAttempts = 0
        sentOffer = false
        isMakingOffer = false
        pendingIceRestart = false
        clearOfferTimeout()
        clearIceRestartTimer()
    }

    private fun scheduleReconnect() {
        val roomId = currentRoomId ?: return
        reconnectAttempts += 1
        val backoff = (500L * (1 shl (reconnectAttempts - 1))).coerceAtMost(5000L)
        handler.postDelayed({
            if (currentRoomId == roomId && !signalingClient.isConnected()) {
                pendingJoinRoom = roomId
                signalingClient.connect(serverHost.value)
            }
        }, backoff)
    }

    private fun registerConnectivityListener() {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder().build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            }
        } catch (e: Exception) {
            networkCallbackRegistered = false
            Log.w("CallManager", "Failed to register network callback", e)
        }
    }

    private fun extractRoomId(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.isNullOrEmpty()) return null
        val idx = segments.indexOf("call")
        if (idx == -1 || segments.size <= idx + 1) return null
        return segments[idx + 1]
    }
}
