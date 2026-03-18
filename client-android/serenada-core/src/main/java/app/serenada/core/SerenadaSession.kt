package app.serenada.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import app.serenada.core.call.CallAudioSessionController
import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.ContentTypeWire
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.Participant
import app.serenada.core.call.PeerConnectionSlot
import app.serenada.core.call.RemoteParticipant
import app.serenada.core.call.RealtimeCallStats
import app.serenada.core.call.RoomState
import app.serenada.core.call.SignalingClient
import app.serenada.core.call.SignalingMessage
import app.serenada.core.call.WebRtcEngine
import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.network.CoreApiClient
import app.serenada.core.network.TurnCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.util.concurrent.Executors

/**
 * Represents an active call session. Created via [SerenadaCore.join] or [SerenadaCore.createRoom].
 *
 * Observe [state] for call state changes and [callStats] for real-time statistics.
 * Control the call via [leave], [end], [toggleAudio], [toggleVideo], etc.
 */
class SerenadaSession internal constructor(
    val roomId: String,
    val roomUrl: String?,
    private val serverHost: String,
    private val config: SerenadaConfig,
    private val context: Context,
    private val delegate: (() -> SerenadaCoreDelegate?)?,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val webRtcStatsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "webrtc-stats")
    }
    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = CoreApiClient(okHttpClient)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _callStats = MutableStateFlow(CallStats())
    val callStats: StateFlow<CallStats> = _callStats.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (_state.value.phase == CallPhase.InCall) {
                    if (isConnectionDegraded(_state.value)) markConnectionDegraded()
                    scheduleIceRestart("network-online", 0)
                }
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                if (_state.value.phase == CallPhase.InCall) {
                    val hasAnyActiveNetwork = connectivityManager.activeNetwork != null
                    if (!hasAnyActiveNetwork || isConnectionDegraded(_state.value)) {
                        markConnectionDegraded()
                    }
                }
            }
        }
    }

    private var clientId: String? = null
    private var hostCid: String? = null
    private var currentRoomState: RoomState? = null
    private var callStartTimeMs: Long? = null
    private var pendingJoinRoom: String? = null
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
    private var userPreferredVideoEnabled = config.defaultVideoEnabled
    private var isVideoPausedByProximity = false
    private var webRtcEngine = buildWebRtcEngine()
    private var awaitingPermissions = false

    private val callAudioSessionController = CallAudioSessionController(
        context = appContext,
        handler = handler,
        onProximityChanged = { near ->
            Log.d(TAG, "Proximity sensor changed: ${if (near) "NEAR" else "FAR"}")
        },
        onAudioEnvironmentChanged = { applyLocalVideoPreference() }
    )

    private val forceSse = config.transports == listOf(SerenadaTransport.SSE)

    private val signalingClient = SignalingClient(
        okHttpClient, handler,
        object : SignalingClient.Listener {
            override fun onOpen(activeTransport: String) {
                reconnectAttempts = 0
                updateState(
                    _state.value.copy(
                        isSignalingConnected = true,
                        activeTransport = activeTransport
                    )
                )
                updateConnectionStatusFromSignals()
                pendingJoinRoom?.let { join ->
                    pendingJoinRoom = null
                    sendJoin(join)
                }
            }

            override fun onMessage(message: SignalingMessage) {
                handleSignalingMessage(message)
            }

            override fun onClosed(reason: String) {
                val shouldReconnect = _state.value.phase != CallPhase.Idle
                updateState(
                    _state.value.copy(
                        isSignalingConnected = false,
                        activeTransport = null
                    )
                )
                updateConnectionStatusFromSignals()
                if (shouldReconnect) scheduleReconnect()
            }
        },
        forceSse = forceSse,
    )

    // --- Public API ---

    fun leave() {
        if (_state.value.phase == CallPhase.Idle) return
        sendMessage("leave", null)
        cleanupCall(EndReason.LOCAL_LEFT)
    }

    fun end() {
        sendMessage("end_room", null)
        leave()
    }

    fun toggleAudio() {
        val enabled = !_state.value.localAudioEnabled
        webRtcEngine.toggleAudio(enabled)
        updateState(_state.value.copy(localAudioEnabled = enabled))
    }

    fun toggleVideo() {
        userPreferredVideoEnabled = !_state.value.localVideoEnabled
        applyLocalVideoPreference()
    }

    fun flipCamera() {
        if (!_state.value.isScreenSharing) {
            val currentMode = _state.value.localCameraMode
            if (currentMode.isContentMode) broadcastContentState(false)
            webRtcEngine.flipCamera()
        }
    }

    fun setCameraMode(@Suppress("UNUSED_PARAMETER") mode: LocalCameraMode) {
        // Camera mode is driven by flipCamera() internally
        flipCamera()
    }

    fun startScreenShare(intent: Intent) {
        if (_state.value.isScreenSharing) return
        if (!webRtcEngine.startScreenShare(intent)) {
            Log.w(TAG, "Failed to start screen sharing")
            return
        }
        updateState(_state.value.copy(isScreenSharing = true))
        broadcastContentState(true, ContentTypeWire.SCREEN_SHARE)
        applyLocalVideoPreference()
    }

    fun stopScreenShare() {
        if (!_state.value.isScreenSharing) return
        if (!webRtcEngine.stopScreenShare()) {
            Log.w(TAG, "Failed to stop screen sharing")
            return
        }
        updateState(_state.value.copy(isScreenSharing = false))
        broadcastContentState(false)
        applyLocalVideoPreference()
    }

    fun resumeJoin() {
        if (!awaitingPermissions) return
        awaitingPermissions = false
        updateState(_state.value.copy(requiredPermissions = emptyList()))
        startJoinInternal()
    }

    fun cancelJoin() {
        if (awaitingPermissions) {
            awaitingPermissions = false
            cleanupCall(EndReason.LOCAL_LEFT)
        }
    }

    fun attachLocalRenderer(
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null,
    ) {
        webRtcEngine.attachLocalRenderer(renderer, rendererEvents)
    }

    fun detachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRtcEngine.detachLocalRenderer(renderer)
    }

    fun attachRemoteRenderer(
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null,
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
        peerSlots.values.forEach { it.detachRemoteRenderer(renderer) }
    }

    fun attachRemoteRendererForCid(
        cid: String,
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null,
    ) {
        webRtcEngine.initRenderer(renderer, rendererEvents)
        peerSlots[cid]?.attachRemoteRenderer(renderer)
    }

    fun detachRemoteRendererForCid(cid: String, renderer: org.webrtc.SurfaceViewRenderer) {
        peerSlots[cid]?.detachRemoteRenderer(renderer)
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
        peerSlots.values.forEach { it.detachRemoteSink(sink) }
    }

    fun attachRemoteSinkForCid(cid: String, sink: org.webrtc.VideoSink) {
        peerSlots[cid]?.attachRemoteSink(sink)
    }

    fun detachRemoteSinkForCid(cid: String, sink: org.webrtc.VideoSink) {
        peerSlots[cid]?.detachRemoteSink(sink)
    }

    fun eglContext(): org.webrtc.EglBase.Context = webRtcEngine.getEglContext()

    fun adjustLocalCameraZoom(scaleFactor: Float) {
        webRtcEngine.adjustWorldCameraZoom(scaleFactor)
    }

    fun toggleFlashlight() {
        webRtcEngine.toggleFlashlight()
    }

    // --- Internal: Start ---

    internal fun start() {
        val joinAttemptId = ++joinAttemptSerial
        callStartTimeMs = System.currentTimeMillis()
        pendingMessages.clear()
        peerSlots.clear()
        currentRoomState = null
        hasJoinSignalStarted = false
        hasJoinAcknowledged = false

        recreateWebRtcEngineForNewCall()
        registerConnectivityListener()

        updateState(
            _state.value.copy(
                phase = CallPhase.Joining,
                roomId = roomId,
                errorMessage = null,
                localAudioEnabled = config.defaultAudioEnabled,
                localVideoEnabled = config.defaultVideoEnabled,
                remoteParticipants = emptyList(),
                localCameraMode = LocalCameraMode.SELFIE,
                connectionStatus = ConnectionStatus.Connected,
                isFlashAvailable = false,
                isFlashEnabled = false,
            )
        )
        scheduleJoinTimeout(roomId, joinAttemptId)
        scheduleJoinKickstart(roomId, joinAttemptId)

        acquirePerformanceLocks()
        callAudioSessionController.activate()
        webRtcEngine.startLocalMedia()

        if (!config.defaultAudioEnabled) webRtcEngine.toggleAudio(false)
        applyLocalVideoPreference()

        startRemoteVideoStatePolling()
        ensureSignalingConnection()
    }

    internal fun startWithPermissionCheck() {
        awaitingPermissions = true
        updateState(
            _state.value.copy(
                phase = CallPhase.Joining,
                roomId = roomId,
                requiredPermissions = listOf(MediaCapability.CAMERA, MediaCapability.MICROPHONE),
            )
        )
        delegate?.invoke()?.onPermissionsRequired(this, listOf(MediaCapability.CAMERA, MediaCapability.MICROPHONE))
    }

    private fun startJoinInternal() {
        start()
    }

    // --- Internal: WebRTC Engine ---

    private fun buildWebRtcEngine(): WebRtcEngine {
        return WebRtcEngine(
            context = appContext,
            onCameraFacingChanged = { isFront ->
                handler.post {
                    updateState(_state.value.copy(isFrontCamera = isFront))
                }
            },
            onCameraModeChanged = { mode ->
                handler.post {
                    val previousMode = _state.value.localCameraMode
                    updateState(_state.value.copy(localCameraMode = mode))
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
                        _state.value.copy(
                            isFlashAvailable = available,
                            isFlashEnabled = enabled
                        )
                    )
                }
            },
            onScreenShareStopped = {
                handler.post {
                    if (_state.value.isScreenSharing) {
                        updateState(_state.value.copy(isScreenSharing = false))
                        broadcastContentState(false)
                    }
                    applyLocalVideoPreference()
                }
            },
            isHdVideoExperimentalEnabled = false
        )
    }

    private fun recreateWebRtcEngineForNewCall() {
        runCatching { webRtcEngine.release() }
        webRtcEngine = buildWebRtcEngine()
    }

    // --- Internal: Signaling ---

    private fun ensureSignalingConnection() {
        hasJoinSignalStarted = true
        if (signalingClient.isConnected()) {
            pendingJoinRoom = null
            sendJoin(roomId)
            return
        }
        pendingJoinRoom = roomId
        signalingClient.connect(serverHost)
    }

    private fun sendJoin(roomId: String) {
        val buildPayload = {
            JSONObject().apply {
                put("device", "android")
                put(
                    "capabilities",
                    JSONObject().apply {
                        put("trickleIce", true)
                        put("maxParticipants", 2)
                    }
                )
                put("createMaxParticipants", 2)
                reconnectToken?.let { put("reconnectToken", it) }
            }
        }
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

    private fun sendMessage(type: String, payload: JSONObject?, to: String? = null) {
        Log.d(TAG, "TX $type")
        val msg = SignalingMessage(
            type = type,
            rid = roomId,
            sid = null,
            cid = clientId,
            to = to,
            payload = payload
        )
        signalingClient.send(msg)
    }

    private fun handleSignalingMessage(msg: SignalingMessage) {
        Log.d(TAG, "RX ${msg.type}")
        when (msg.type) {
            "joined" -> handleJoined(msg)
            "room_state" -> handleRoomState(msg)
            "room_ended" -> handleRoomEnded()
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
        updateState(_state.value.copy(localCid = clientId))
        msg.payload?.optString("reconnectToken").orEmpty().ifBlank { null }?.let {
            reconnectToken = it
        }
        msg.payload?.optLong("turnTokenTTLMs", 0)?.takeIf { it > 0 }?.let { ttl ->
            turnTokenTTLMs = ttl
            scheduleTurnRefresh(ttl)
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

    private fun handleRoomEnded() {
        cleanupCall(EndReason.REMOTE_ENDED)
    }

    private fun handleContentState(msg: SignalingMessage) {
        val fromCid = msg.payload?.optString("from") ?: return
        val active = msg.payload?.optBoolean("active") == true
        val contentType = if (active) msg.payload?.optString("contentType") else null
        updateState(
            _state.value.copy(
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
        val rawMessage = msg.payload?.optString("message").orEmpty().ifBlank { null }
        clearJoinTimeout()
        resetResources()
        updateState(
            CallState(
                phase = CallPhase.Error,
                errorMessage = rawMessage ?: "Unknown error"
            )
        )
        delegate?.invoke()?.onSessionEnded(this, EndReason.ERROR)
    }

    private fun handleSignalingPayload(msg: SignalingMessage) {
        if (!webRtcEngine.hasIceServers()) {
            pendingMessages.add(msg)
            return
        }
        processSignalingPayload(msg)
    }

    // --- Internal: Peer Connections ---

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
                onConnectionStateChange = { cid, connState ->
                    handler.post {
                        when (connState) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                clearIceRestartTimer(cid)
                                peerSlots[cid]?.pendingIceRestart = false
                            }
                            PeerConnection.PeerConnectionState.DISCONNECTED ->
                                scheduleIceRestart(cid, "conn-disconnected", 2000)
                            PeerConnection.PeerConnectionState.FAILED ->
                                scheduleIceRestart(cid, "conn-failed", 0)
                            else -> Unit
                        }
                        refreshRemoteParticipants()
                        updateAggregatePeerState()
                        updateConnectionStatusFromSignals()
                    }
                },
                onIceConnectionStateChange = { cid, iceState ->
                    handler.post {
                        when (iceState) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                clearIceRestartTimer(cid)
                                peerSlots[cid]?.pendingIceRestart = false
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED ->
                                scheduleIceRestart(cid, "ice-disconnected", 2000)
                            PeerConnection.IceConnectionState.FAILED ->
                                scheduleIceRestart(cid, "ice-failed", 0)
                            else -> Unit
                        }
                        refreshRemoteParticipants()
                        updateAggregatePeerState()
                        updateConnectionStatusFromSignals()
                    }
                },
                onSignalingStateChange = { cid, sigState ->
                    handler.post {
                        if (sigState == PeerConnection.SignalingState.STABLE) {
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
                        peerSlots[cid]?.let { maybeSendOffer(it, force = true) }
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

    // --- Internal: Participants ---

    private fun updateParticipants(roomState: RoomState) {
        val count = roomState.participants.size
        val isHostNow = clientId != null && clientId == roomState.hostCid
        val remotePeers = roomState.participants.filter { it.cid != clientId }
        val remoteCids = remotePeers.map { it.cid }.toSet()
        val phase = if (count <= 1) CallPhase.Waiting else CallPhase.InCall
        if (phase != CallPhase.Joining) clearJoinTimeout()

        peerSlots.keys.filter { it !in remoteCids }.forEach { removePeerSlot(it) }
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
            _state.value.copy(
                phase = phase,
                isHost = isHostNow,
                participantCount = count,
            )
        )
        refreshRemoteParticipants()
        updateAggregatePeerState()
        updateConnectionStatusFromSignals()
    }

    private fun refreshRemoteParticipants() {
        val myCid = clientId
        val orderedRemoteCids = currentRoomState?.participants?.map { it.cid }?.filter { it != myCid }
            ?: peerSlots.keys.toList()
        val remoteParticipants = orderedRemoteCids.mapNotNull { cid ->
            val slot = peerSlots[cid] ?: return@mapNotNull null
            RemoteParticipant(cid = cid, videoEnabled = slot.isRemoteVideoTrackEnabled(), connectionState = slot.getConnectionState().name)
        }
        val currentState = _state.value
        val activeCids = remoteParticipants.map { it.cid }.toSet()
        val clearContent = currentState.remoteContentCid != null && currentState.remoteContentCid !in activeCids
        if (currentState.remoteParticipants == remoteParticipants) {
            if (clearContent) updateState(currentState.copy(remoteContentCid = null, remoteContentType = null))
            return
        }
        updateState(currentState.copy(
            remoteParticipants = remoteParticipants,
            remoteContentCid = if (clearContent) null else currentState.remoteContentCid,
            remoteContentType = if (clearContent) null else currentState.remoteContentType,
        ))
    }

    // --- Internal: Offer / ICE ---

    private fun shouldIOffer(remoteCid: String, roomState: RoomState? = currentRoomState): Boolean {
        val state = roomState ?: return false
        val myCid = clientId ?: return false
        val myJoinedAt = state.participants.find { it.cid == myCid }?.joinedAt ?: 0L
        val theirJoinedAt = state.participants.find { it.cid == remoteCid }?.joinedAt ?: 0L
        return myJoinedAt < theirJoinedAt || (myJoinedAt == theirJoinedAt && myCid < remoteCid)
    }

    private fun maybeSendOffer(force: Boolean = false, iceRestart: Boolean = false) {
        peerSlots.values.forEach { slot ->
            if (shouldIOffer(slot.remoteCid, currentRoomState)) maybeSendOffer(slot, force, iceRestart)
        }
    }

    private fun maybeSendOffer(slot: PeerConnectionSlot, force: Boolean = false, iceRestart: Boolean = false) {
        if (slot.isMakingOffer) { if (iceRestart) slot.pendingIceRestart = true; return }
        if (!force && slot.sentOffer) return
        if (!canOffer(slot)) return
        if (slot.getSignalingState() != PeerConnection.SignalingState.STABLE) { if (iceRestart) slot.pendingIceRestart = true; return }
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
                    if (!success && iceRestart) scheduleIceRestart(slot.remoteCid, "offer-failed", 500)
                }
            }
        )
        if (!started) { slot.isMakingOffer = false; if (iceRestart) slot.pendingIceRestart = true; return }
        if (!force) slot.sentOffer = true
    }

    private fun canOffer(slot: PeerConnectionSlot): Boolean {
        if (!signalingClient.isConnected()) return false
        if (!slot.isReady()) return false
        if (!shouldIOffer(slot.remoteCid, currentRoomState)) return false
        val participantCids = currentRoomState?.participants?.map { it.cid }?.toSet() ?: emptySet()
        return slot.remoteCid in participantCids
    }

    // --- Internal: Timers ---

    private fun scheduleOfferTimeout(remoteCid: String) {
        val slot = peerSlots[remoteCid] ?: return
        clearOfferTimeout(remoteCid)
        val runnable = Runnable {
            slot.offerTimeoutTask = null
            if (slot.getSignalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                slot.pendingIceRestart = true
                slot.rollbackLocalDescription {
                    handler.post {
                        if (shouldIOffer(remoteCid)) scheduleIceRestart(remoteCid, "offer-timeout", 0)
                        else maybeScheduleNonHostOfferFallback(remoteCid, "offer-timeout")
                    }
                }
            } else {
                if (shouldIOffer(remoteCid)) scheduleIceRestart(remoteCid, "offer-timeout-stale", 0)
                else maybeScheduleNonHostOfferFallback(remoteCid, "offer-timeout-stale")
            }
        }
        slot.offerTimeoutTask = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.OFFER_TIMEOUT_MS)
    }

    private fun clearOfferTimeout(remoteCid: String? = null) {
        if (remoteCid != null) {
            peerSlots[remoteCid]?.offerTimeoutTask?.let { handler.removeCallbacks(it) }
            peerSlots[remoteCid]?.offerTimeoutTask = null
        } else {
            peerSlots.values.forEach { it.offerTimeoutTask?.let { r -> handler.removeCallbacks(r) }; it.offerTimeoutTask = null }
        }
    }

    private fun scheduleIceRestart(reason: String, delayMs: Long) {
        peerSlots.values.forEach { if (shouldIOffer(it.remoteCid)) scheduleIceRestart(it.remoteCid, reason, delayMs) }
    }

    private fun scheduleIceRestart(remoteCid: String, reason: String, delayMs: Long) {
        val slot = peerSlots[remoteCid] ?: return
        if (!canOffer(slot)) { slot.pendingIceRestart = true; return }
        if (slot.iceRestartTask != null) return
        if (System.currentTimeMillis() - slot.lastIceRestartAt < WebRtcResilienceConstants.ICE_RESTART_COOLDOWN_MS) return
        val runnable = Runnable { slot.iceRestartTask = null; triggerIceRestart(remoteCid, reason) }
        slot.iceRestartTask = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearIceRestartTimer(remoteCid: String? = null) {
        if (remoteCid != null) {
            peerSlots[remoteCid]?.iceRestartTask?.let { handler.removeCallbacks(it) }; peerSlots[remoteCid]?.iceRestartTask = null
        } else {
            peerSlots.values.forEach { it.iceRestartTask?.let { r -> handler.removeCallbacks(r) }; it.iceRestartTask = null }
        }
    }

    private fun triggerIceRestart(remoteCid: String, reason: String) {
        val slot = peerSlots[remoteCid] ?: return
        if (!canOffer(slot)) { slot.pendingIceRestart = true; return }
        if (slot.isMakingOffer) { slot.pendingIceRestart = true; return }
        Log.w(TAG, "ICE restart triggered for $remoteCid ($reason)")
        slot.lastIceRestartAt = System.currentTimeMillis()
        slot.pendingIceRestart = false
        maybeSendOffer(slot, force = true, iceRestart = true)
    }

    private fun maybeScheduleNonHostOfferFallback(remoteCid: String, reason: String) {
        val slot = peerSlots[remoteCid] ?: return
        if (shouldIOffer(remoteCid)) { clearNonHostOfferFallback(remoteCid); return }
        if (!signalingClient.isConnected()) return
        if (slot.nonHostFallbackTask != null) return
        if (slot.nonHostFallbackAttempts >= WebRtcResilienceConstants.NON_HOST_FALLBACK_MAX_ATTEMPTS) return
        val runnable = Runnable {
            slot.nonHostFallbackTask = null
            slot.nonHostFallbackAttempts++
            maybeSendNonHostFallbackOffer(remoteCid)
        }
        slot.nonHostFallbackTask = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.NON_HOST_FALLBACK_DELAY_MS)
    }

    private fun clearNonHostOfferFallback(remoteCid: String? = null) {
        if (remoteCid != null) {
            peerSlots[remoteCid]?.nonHostFallbackTask?.let { handler.removeCallbacks(it) }; peerSlots[remoteCid]?.nonHostFallbackTask = null
        } else {
            peerSlots.values.forEach { it.nonHostFallbackTask?.let { r -> handler.removeCallbacks(r) }; it.nonHostFallbackTask = null }
        }
    }

    private fun maybeSendNonHostFallbackOffer(remoteCid: String) {
        val slot = peerSlots[remoteCid] ?: return
        if (shouldIOffer(remoteCid)) return
        if (!signalingClient.isConnected()) return
        if (!slot.isReady() && !slot.ensurePeerConnection()) return
        if (slot.getSignalingState() != PeerConnection.SignalingState.STABLE) return
        if (slot.hasRemoteDescription()) return
        if (slot.isMakingOffer) return
        slot.isMakingOffer = true
        val started = slot.createOffer(
            onSdp = { sdp ->
                sendMessage("offer", JSONObject().apply { put("sdp", sdp) }, to = remoteCid)
                scheduleOfferTimeout(remoteCid)
            },
            onComplete = { success ->
                handler.post { slot.isMakingOffer = false; if (!success) maybeScheduleNonHostOfferFallback(remoteCid, "offer-failed") }
            }
        )
        if (!started) { slot.isMakingOffer = false; maybeScheduleNonHostOfferFallback(remoteCid, "offer-not-started") }
    }

    private fun scheduleJoinTimeout(roomId: String, joinAttemptId: Long) {
        clearJoinTimeout()
        val runnable = Runnable {
            joinTimeoutRunnable = null
            if (_state.value.phase == CallPhase.Joining && joinAttemptSerial == joinAttemptId) {
                Log.w(TAG, "Join timeout for room $roomId")
                resetResources()
                updateState(CallState(phase = CallPhase.Error, errorMessage = "Connection failed"))
                delegate?.invoke()?.onSessionEnded(this, EndReason.ERROR)
            }
        }
        joinTimeoutRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS)
    }

    private fun clearJoinTimeout() { joinTimeoutRunnable?.let { handler.removeCallbacks(it) }; joinTimeoutRunnable = null }

    private fun scheduleJoinKickstart(roomId: String, joinAttemptId: Long) {
        clearJoinKickstart()
        val runnable = Runnable {
            joinKickstartRunnable = null
            if (_state.value.phase != CallPhase.Joining) return@Runnable
            if (joinAttemptSerial != joinAttemptId) return@Runnable
            if (hasJoinSignalStarted) return@Runnable
            ensureSignalingConnection()
        }
        joinKickstartRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_CONNECT_KICKSTART_MS)
    }

    private fun clearJoinKickstart() { joinKickstartRunnable?.let { handler.removeCallbacks(it) }; joinKickstartRunnable = null }

    private fun scheduleJoinRecovery(roomId: String) {
        clearJoinRecovery()
        val runnable = Runnable {
            joinRecoveryRunnable = null
            if (!signalingClient.isConnected()) return@Runnable
            if (!hasJoinAcknowledged) {
                if (_state.value.phase == CallPhase.Joining) {
                    pendingJoinRoom = roomId
                    ensureSignalingConnection()
                }
                return@Runnable
            }
            if (_state.value.phase == CallPhase.Joining) {
                updateState(_state.value.copy(phase = CallPhase.Waiting, participantCount = 1))
                updateConnectionStatusFromSignals()
            }
        }
        joinRecoveryRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_RECOVERY_MS)
    }

    private fun clearJoinRecovery() { joinRecoveryRunnable?.let { handler.removeCallbacks(it) }; joinRecoveryRunnable = null }

    // --- Internal: TURN ---

    private fun fetchTurnCredentials(token: String) {
        var resolved = false
        val timeoutRunnable = Runnable {
            if (resolved) return@Runnable; resolved = true
            applyDefaultIceServers()
        }
        handler.postDelayed(timeoutRunnable, WebRtcResilienceConstants.TURN_FETCH_TIMEOUT_MS)
        apiClient.fetchTurnCredentials(serverHost, token) { result ->
            handler.post {
                handler.removeCallbacks(timeoutRunnable)
                if (resolved) return@post; resolved = true
                result.onSuccess { applyTurnCredentials(it) }.onFailure { applyDefaultIceServers() }
            }
        }
    }

    private fun applyTurnCredentials(creds: TurnCredentials) {
        val servers = creds.uris.map {
            PeerConnection.IceServer.builder(it).setUsername(creds.username).setPassword(creds.password).createIceServer()
        }
        webRtcEngine.setIceServers(servers)
        onIceServersReady()
    }

    private fun applyDefaultIceServers() {
        webRtcEngine.setIceServers(listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()))
        onIceServersReady()
    }

    private fun onIceServersReady() {
        while (pendingMessages.isNotEmpty() && webRtcEngine.hasIceServers()) {
            processSignalingPayload(pendingMessages.removeFirst())
        }
        maybeSendOffer()
        peerSlots.values.forEach { if (!shouldIOffer(it.remoteCid)) maybeScheduleNonHostOfferFallback(it.remoteCid, "ice-ready") }
    }

    private fun scheduleTurnRefresh(ttlMs: Long) {
        clearTurnRefresh()
        if (ttlMs <= 0) return
        val delayMs = (ttlMs * WebRtcResilienceConstants.TURN_REFRESH_TRIGGER_RATIO).toLong()
        val runnable = Runnable {
            turnRefreshRunnable = null
            if (!signalingClient.isConnected()) return@Runnable
            sendMessage("turn-refresh", null)
        }
        turnRefreshRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearTurnRefresh() { turnRefreshRunnable?.let { handler.removeCallbacks(it) }; turnRefreshRunnable = null }

    private fun handleTurnRefreshed(msg: SignalingMessage) {
        msg.payload?.optLong("turnTokenTTLMs", 0)?.takeIf { it > 0 }?.let { scheduleTurnRefresh(it) }
        msg.payload?.optString("turnToken").orEmpty().ifBlank { null }?.let { fetchTurnCredentials(it) }
    }

    // --- Internal: State ---

    private fun updateAggregatePeerState() {
        var bestIcePri = Int.MAX_VALUE; var bestIce = "NEW"
        var bestConnPri = Int.MAX_VALUE; var bestConn = "NEW"
        var bestSigPri = Int.MAX_VALUE; var bestSig = "STABLE"
        for (slot in peerSlots.values) {
            val ip = ICE_PRIORITY[slot.getIceConnectionState()] ?: Int.MAX_VALUE
            if (ip < bestIcePri) { bestIcePri = ip; bestIce = slot.getIceConnectionState().name }
            val cp = CONN_PRIORITY[slot.getConnectionState()] ?: Int.MAX_VALUE
            if (cp < bestConnPri) { bestConnPri = cp; bestConn = slot.getConnectionState().name }
            val sp = SIG_PRIORITY[slot.getSignalingState()] ?: Int.MAX_VALUE
            if (sp < bestSigPri) { bestSigPri = sp; bestSig = slot.getSignalingState().name }
        }
        val s = _state.value
        if (s.iceConnectionState == bestIce && s.connectionState == bestConn && s.signalingState == bestSig) return
        updateState(s.copy(iceConnectionState = bestIce, connectionState = bestConn, signalingState = bestSig))
    }

    private fun parseRoomState(payload: JSONObject?): RoomState? {
        if (payload == null) return null
        val parsedHostCid = payload.optString("hostCid", "").ifBlank { null }
        val maxParticipants = payload.optInt("maxParticipants", 0).takeIf { it > 0 }
        val participantsJson = payload.optJSONArray("participants")
        val participants = mutableListOf<Participant>()
        if (participantsJson != null) {
            for (i in 0 until participantsJson.length()) {
                val p = participantsJson.optJSONObject(i)
                val cid = p?.optString("cid", "") ?: ""
                if (cid.isNotBlank()) participants.add(Participant(cid, p?.optLong("joinedAt")?.takeIf { it > 0 }))
            }
        }
        var resolved = parsedHostCid ?: hostCid ?: clientId
        if (resolved != null && participants.isNotEmpty()) {
            if (resolved !in participants.map { it.cid }.toSet()) resolved = participants.firstOrNull()?.cid
        }
        if (resolved.isNullOrBlank()) return null
        return RoomState(hostCid = resolved, participants = participants, maxParticipants = maxParticipants)
    }

    private fun updateState(newState: CallState) {
        _state.value = newState
        delegate?.invoke()?.onSessionStateChanged(this, newState)
    }

    // --- Internal: Connection Status ---

    private fun isConnectionDegraded(state: CallState): Boolean {
        return !state.isSignalingConnected || state.iceConnectionState == "DISCONNECTED" || state.iceConnectionState == "FAILED" ||
            state.connectionState == "DISCONNECTED" || state.connectionState == "FAILED"
    }

    private fun setConnectionStatus(status: ConnectionStatus) {
        if (_state.value.connectionStatus == status) return
        updateState(_state.value.copy(connectionStatus = status))
    }

    private fun resetConnectionStatusMachine() {
        clearConnectionStatusRetryingTimer()
        setConnectionStatus(ConnectionStatus.Connected)
    }

    private fun markConnectionDegraded() {
        if (_state.value.phase != CallPhase.InCall) { resetConnectionStatusMachine(); return }
        when (_state.value.connectionStatus) {
            ConnectionStatus.Connected -> { setConnectionStatus(ConnectionStatus.Recovering); scheduleConnectionStatusRetryingTimer() }
            ConnectionStatus.Recovering -> scheduleConnectionStatusRetryingTimer()
            ConnectionStatus.Retrying -> Unit
        }
    }

    private fun updateConnectionStatusFromSignals() {
        if (_state.value.phase != CallPhase.InCall) { resetConnectionStatusMachine(); return }
        if (isConnectionDegraded(_state.value)) { markConnectionDegraded(); return }
        resetConnectionStatusMachine()
    }

    private fun scheduleConnectionStatusRetryingTimer() {
        if (connectionStatusRetryingRunnable != null) return
        val runnable = Runnable {
            connectionStatusRetryingRunnable = null
            if (_state.value.phase != CallPhase.InCall) { resetConnectionStatusMachine(); return@Runnable }
            if (_state.value.connectionStatus == ConnectionStatus.Recovering) setConnectionStatus(ConnectionStatus.Retrying)
        }
        connectionStatusRetryingRunnable = runnable
        handler.postDelayed(runnable, 10_000)
    }

    private fun clearConnectionStatusRetryingTimer() {
        connectionStatusRetryingRunnable?.let { handler.removeCallbacks(it) }; connectionStatusRetryingRunnable = null
    }

    // --- Internal: Stats Polling ---

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
        remoteVideoStatePollRunnable?.let { handler.removeCallbacks(it) }; remoteVideoStatePollRunnable = null
        webrtcStatsRequestInFlight = false; lastWebRtcStatsPollAtMs = 0L
    }

    private fun pollWebRtcStats() {
        val phase = _state.value.phase
        if (phase != CallPhase.InCall && phase != CallPhase.Waiting && phase != CallPhase.Joining) return
        val now = System.currentTimeMillis()
        if (webrtcStatsRequestInFlight) return
        if (now - lastWebRtcStatsPollAtMs < WEBRTC_STATS_POLL_INTERVAL_MS) return
        val slots = peerSlots.values.toList()
        if (slots.isEmpty()) return
        webrtcStatsRequestInFlight = true
        webRtcStatsExecutor.execute {
            val summaries = mutableListOf<String>()
            val stats = mutableListOf<RealtimeCallStats>()
            var remaining = slots.size
            slots.forEach { slot ->
                slot.collectWebRtcStats { _, realtimeStats ->
                    synchronized(summaries) {
                        realtimeStats?.let(stats::add)
                        remaining -= 1
                        if (remaining == 0) {
                            val merged = mergeRealtimeStats(stats)
                            handler.post {
                                webrtcStatsRequestInFlight = false
                                lastWebRtcStatsPollAtMs = System.currentTimeMillis()
                                if (merged != null) {
                                    _callStats.value = CallStats(
                                        bitrate = merged.availableOutgoingKbps,
                                        packetLoss = merged.videoRxPacketLossPct,
                                        jitter = merged.audioJitterMs,
                                        roundTripTime = merged.rttMs,
                                        audioRxKbps = merged.audioRxKbps,
                                        audioTxKbps = merged.audioTxKbps,
                                        videoRxKbps = merged.videoRxKbps,
                                        videoTxKbps = merged.videoTxKbps,
                                        videoFps = merged.videoFps,
                                        videoResolution = merged.videoResolution,
                                        iceCandidatePair = merged.transportPath,
                                        updatedAtMs = merged.updatedAtMs,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mergeRealtimeStats(stats: List<RealtimeCallStats>): RealtimeCallStats? {
        if (stats.isEmpty()) return null
        fun sumN(sel: (RealtimeCallStats) -> Double?) = stats.mapNotNull(sel).sum().takeIf { stats.any { s -> sel(s) != null } }
        fun maxN(sel: (RealtimeCallStats) -> Double?) = stats.mapNotNull(sel).maxOrNull()
        return RealtimeCallStats(
            transportPath = stats.mapNotNull { it.transportPath }.distinct().joinToString().ifBlank { null },
            rttMs = maxN { it.rttMs }, availableOutgoingKbps = sumN { it.availableOutgoingKbps },
            audioRxPacketLossPct = maxN { it.audioRxPacketLossPct }, audioTxPacketLossPct = maxN { it.audioTxPacketLossPct },
            audioJitterMs = maxN { it.audioJitterMs }, audioPlayoutDelayMs = maxN { it.audioPlayoutDelayMs },
            audioConcealedPct = maxN { it.audioConcealedPct },
            audioRxKbps = sumN { it.audioRxKbps }, audioTxKbps = sumN { it.audioTxKbps },
            videoRxPacketLossPct = maxN { it.videoRxPacketLossPct }, videoTxPacketLossPct = maxN { it.videoTxPacketLossPct },
            videoRxKbps = sumN { it.videoRxKbps }, videoTxKbps = sumN { it.videoTxKbps },
            videoFps = maxN { it.videoFps }, videoResolution = stats.asReversed().firstNotNullOfOrNull { it.videoResolution },
            videoFreezeCount60s = stats.mapNotNull { it.videoFreezeCount60s }.sum().takeIf { it > 0 },
            videoFreezeDuration60s = sumN { it.videoFreezeDuration60s },
            videoRetransmitPct = maxN { it.videoRetransmitPct }, videoNackPerMin = sumN { it.videoNackPerMin },
            videoPliPerMin = sumN { it.videoPliPerMin }, videoFirPerMin = sumN { it.videoFirPerMin },
            updatedAtMs = stats.maxOf { it.updatedAtMs },
        )
    }

    // --- Internal: Cleanup ---

    private fun cleanupCall(reason: EndReason) {
        updateState(_state.value.copy(phase = CallPhase.Ending))
        if (_state.value.isScreenSharing) webRtcEngine.stopScreenShare()
        resetResources()
        updateState(CallState(phase = CallPhase.Idle))
        delegate?.invoke()?.onSessionEnded(this, reason)
    }

    private fun resetResources() {
        clearJoinTimeout()
        clearJoinKickstart()
        clearJoinRecovery()
        clearNonHostOfferFallback()
        clearTurnRefresh()
        clearReconnect()
        callAudioSessionController.deactivate()
        releasePerformanceLocks()
        stopRemoteVideoStatePolling()
        signalingClient.close()
        clearOfferTimeout()
        clearIceRestartTimer()
        peerSlots.values.forEach { it.closePeerConnection() }
        peerSlots.clear()
        webRtcEngine.release()
        webRtcStatsExecutor.shutdown()
        unregisterConnectivityListener()
        clientId = null; hostCid = null; currentRoomState = null; callStartTimeMs = null
        pendingJoinRoom = null; pendingMessages.clear(); reconnectAttempts = 0
        clearConnectionStatusRetryingTimer()
        userPreferredVideoEnabled = config.defaultVideoEnabled; isVideoPausedByProximity = false
        reconnectToken = null; turnTokenTTLMs = null; hasJoinSignalStarted = false; hasJoinAcknowledged = false
    }

    private fun applyLocalVideoPreference() {
        val shouldPause = callAudioSessionController.shouldPauseVideoForProximity(_state.value.isScreenSharing)
        isVideoPausedByProximity = shouldPause
        val enabled = userPreferredVideoEnabled && !shouldPause
        webRtcEngine.toggleVideo(enabled)
        if (_state.value.localVideoEnabled != enabled) updateState(_state.value.copy(localVideoEnabled = enabled))
    }

    private fun acquirePerformanceLocks() {
        val lock = cpuWakeLock ?: powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }.also { cpuWakeLock = it }
        if (!lock.isHeld) runCatching { lock.acquire(60 * 60 * 1000L) }
    }

    private fun releasePerformanceLocks() {
        cpuWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    private var reconnectRunnable: Runnable? = null

    private fun scheduleReconnect() {
        reconnectAttempts += 1
        val backoff = (WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS * (1L shl minOf(reconnectAttempts - 1, 13)))
            .coerceAtMost(WebRtcResilienceConstants.RECONNECT_BACKOFF_CAP_MS)
        val runnable = Runnable {
            reconnectRunnable = null
            if (signalingClient.isConnected()) return@Runnable
            if (_state.value.phase != CallPhase.Idle) {
                pendingJoinRoom = roomId
                signalingClient.connect(serverHost)
            }
        }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, backoff)
    }

    private fun clearReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun registerConnectivityListener() {
        runCatching { connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback) }
    }

    private fun unregisterConnectivityListener() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    private companion object {
        const val TAG = "SerenadaSession"
        const val WEBRTC_STATS_POLL_INTERVAL_MS = 2000L
        const val CPU_WAKE_LOCK_TAG = "serenada:call-cpu"
        val ICE_PRIORITY = mapOf(
            PeerConnection.IceConnectionState.FAILED to 0, PeerConnection.IceConnectionState.DISCONNECTED to 1,
            PeerConnection.IceConnectionState.CHECKING to 2, PeerConnection.IceConnectionState.NEW to 3,
            PeerConnection.IceConnectionState.CONNECTED to 4, PeerConnection.IceConnectionState.COMPLETED to 5,
            PeerConnection.IceConnectionState.CLOSED to 6,
        )
        val CONN_PRIORITY = mapOf(
            PeerConnection.PeerConnectionState.FAILED to 0, PeerConnection.PeerConnectionState.DISCONNECTED to 1,
            PeerConnection.PeerConnectionState.CONNECTING to 2, PeerConnection.PeerConnectionState.NEW to 3,
            PeerConnection.PeerConnectionState.CONNECTED to 4, PeerConnection.PeerConnectionState.CLOSED to 5,
        )
        val SIG_PRIORITY = mapOf(
            PeerConnection.SignalingState.CLOSED to 0, PeerConnection.SignalingState.HAVE_LOCAL_OFFER to 1,
            PeerConnection.SignalingState.HAVE_REMOTE_OFFER to 2, PeerConnection.SignalingState.HAVE_LOCAL_PRANSWER to 3,
            PeerConnection.SignalingState.HAVE_REMOTE_PRANSWER to 4, PeerConnection.SignalingState.STABLE to 5,
        )
    }
}
