package app.serenada.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import app.serenada.core.call.LocalFrameSnapshotCapture
import app.serenada.core.call.Participant
import app.serenada.core.call.PeerConnectionSlot
import app.serenada.core.call.RemoteParticipant
import app.serenada.core.call.RealtimeCallStats
import app.serenada.core.call.RoomState
import app.serenada.core.call.SessionAudioController
import app.serenada.core.call.SessionMediaEngine
import app.serenada.core.call.SessionSignaling
import app.serenada.core.call.SignalingClient
import app.serenada.core.call.SignalingMessage
import app.serenada.core.call.WebRtcEngine
import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.network.CoreApiClient
import app.serenada.core.network.SessionAPIClient
import app.serenada.core.network.TurnCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Represents an active call session. Created via [SerenadaCore.join] or [SerenadaCore.createRoom].
 *
 * Observe [state] for app-facing call state changes and [diagnostics] for low-level transport/media details.
 * Control the call via [leave], [end], [toggleAudio], [toggleVideo], etc.
 */
class SerenadaSession internal constructor(
    val roomId: String,
    val roomUrl: String?,
    private val serverHost: String,
    private val config: SerenadaConfig,
    private val context: Context,
    private val delegate: (() -> SerenadaCoreDelegate?)?,
    okHttpClient: OkHttpClient,
    signaling: SessionSignaling? = null,
    apiClient: SessionAPIClient? = null,
    audioController: SessionAudioController? = null,
    mediaEngine: SessionMediaEngine? = null,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var webRtcStatsExecutor: ExecutorService? = newWebRtcStatsExecutor()
    private val apiClient: SessionAPIClient = apiClient ?: CoreApiClient(okHttpClient)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(CallDiagnostics())
    val diagnostics: StateFlow<CallDiagnostics> = _diagnostics.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (_state.value.phase == CallPhase.InCall) {
                    if (isConnectionDegraded()) markConnectionDegraded()
                    scheduleIceRestart("network-online", 0)
                }
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                if (_state.value.phase == CallPhase.InCall) {
                    val hasAnyActiveNetwork = connectivityManager.activeNetwork != null
                    if (!hasAnyActiveNetwork || isConnectionDegraded()) {
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
    private val isMediaEngineInjected = mediaEngine != null
    private var webRtcEngine: SessionMediaEngine = mediaEngine ?: buildWebRtcEngine()
    private var awaitingPermissions = false

    var onPermissionsRequired: ((List<MediaCapability>) -> Unit)? = null

    val host: String
        get() = serverHost

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SerenadaSession APIs must be called on the main thread"
        }
    }

    private val callAudioSessionController: SessionAudioController = audioController ?: CallAudioSessionController(
        context = appContext,
        handler = handler,
        onProximityChanged = { near ->
            Log.d(TAG, "Proximity sensor changed: ${if (near) "NEAR" else "FAR"}")
        },
        onAudioEnvironmentChanged = { applyLocalVideoPreference() }
    )

    private val forceSse = config.transports == listOf(SerenadaTransport.SSE)

    private fun newWebRtcStatsExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "webrtc-stats")
        }

    private val signalingListener = object : SessionSignaling.Listener {
        override fun onOpen(activeTransport: String) {
            reconnectAttempts = 0
            updateDiagnostics(
                _diagnostics.value.copy(
                    isSignalingConnected = true,
                    activeTransport = activeTransport,
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
            updateDiagnostics(
                _diagnostics.value.copy(
                    isSignalingConnected = false,
                    activeTransport = null,
                )
            )
            updateConnectionStatusFromSignals()
            if (shouldReconnect) scheduleReconnect()
        }
    }

    private val signalingClient: SessionSignaling = (signaling ?: SignalingClient(
        okHttpClient, handler, signalingListener, forceSse = forceSse,
    )).also { it.listener = signalingListener }

    // --- Public API ---

    fun leave() {
        assertMainThread()
        if (_state.value.phase == CallPhase.Idle) return
        sendMessage("leave", null)
        cleanupCall(EndReason.LOCAL_LEFT)
    }

    fun end() {
        assertMainThread()
        sendMessage("end_room", null)
        leave()
    }

    fun toggleAudio() {
        assertMainThread()
        val enabled = !_state.value.localAudioEnabled
        webRtcEngine.toggleAudio(enabled)
        updateState(_state.value.copy(localAudioEnabled = enabled))
    }

    fun toggleVideo() {
        assertMainThread()
        userPreferredVideoEnabled = !_state.value.localVideoEnabled
        applyLocalVideoPreference()
    }

    fun flipCamera() {
        assertMainThread()
        if (!_diagnostics.value.isScreenSharing) {
            val currentMode = _state.value.localCameraMode
            if (currentMode.isContentMode) broadcastContentState(false)
            webRtcEngine.flipCamera()
        }
    }

    fun setCameraMode(@Suppress("UNUSED_PARAMETER") mode: LocalCameraMode) {
        assertMainThread()
        // Camera mode is driven by flipCamera() internally
        flipCamera()
    }

    fun startScreenShare(intent: Intent) {
        assertMainThread()
        if (_diagnostics.value.isScreenSharing) return
        if (!webRtcEngine.startScreenShare(intent)) {
            Log.w(TAG, "Failed to start screen sharing")
            return
        }
        updateDiagnostics(_diagnostics.value.copy(isScreenSharing = true))
        broadcastContentState(true, ContentTypeWire.SCREEN_SHARE)
        applyLocalVideoPreference()
    }

    fun stopScreenShare() {
        assertMainThread()
        if (!_diagnostics.value.isScreenSharing) return
        if (!webRtcEngine.stopScreenShare()) {
            Log.w(TAG, "Failed to stop screen sharing")
            return
        }
        updateDiagnostics(_diagnostics.value.copy(isScreenSharing = false))
        broadcastContentState(false)
        applyLocalVideoPreference()
    }

    fun captureLocalSnapshot(onResult: (ByteArray?) -> Unit) {
        assertMainThread()
        LocalFrameSnapshotCapture(
            handler = handler,
            attachLocalSink = { sink -> webRtcEngine.attachLocalSink(sink) },
            detachLocalSink = { sink -> webRtcEngine.detachLocalSink(sink) },
        ).capture(onResult)
    }

    fun resumeJoin() {
        assertMainThread()
        if (!awaitingPermissions) return
        if (!hasRequiredPermissions()) {
            startWithPermissionCheck()
            return
        }
        awaitingPermissions = false
        updateState(
            _state.value.copy(
                phase = CallPhase.Joining,
                requiredPermissions = emptyList()
            )
        )
        startJoinInternal()
    }

    fun cancelJoin() {
        assertMainThread()
        if (awaitingPermissions) {
            awaitingPermissions = false
            cleanupCall(EndReason.LOCAL_LEFT)
        }
    }

    fun attachLocalRenderer(
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null,
    ) {
        assertMainThread()
        webRtcEngine.attachLocalRenderer(renderer, rendererEvents)
    }

    fun detachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        assertMainThread()
        webRtcEngine.detachLocalRenderer(renderer)
    }

    fun attachRemoteRenderer(
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null,
    ) {
        assertMainThread()
        val remoteCid = currentRoomState
            ?.participants
            ?.firstOrNull { it.cid != clientId }
            ?.cid
            ?: peerSlots.keys.firstOrNull()
            ?: return
        attachRemoteRendererForCid(remoteCid, renderer, rendererEvents)
    }

    fun detachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        assertMainThread()
        peerSlots.values.forEach { it.detachRemoteRenderer(renderer) }
    }

    fun attachRemoteRendererForCid(
        cid: String,
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null,
    ) {
        assertMainThread()
        webRtcEngine.initRenderer(renderer, rendererEvents)
        peerSlots[cid]?.attachRemoteRenderer(renderer)
    }

    fun detachRemoteRendererForCid(cid: String, renderer: org.webrtc.SurfaceViewRenderer) {
        assertMainThread()
        peerSlots[cid]?.detachRemoteRenderer(renderer)
    }

    fun attachLocalSink(sink: org.webrtc.VideoSink) {
        assertMainThread()
        webRtcEngine.attachLocalSink(sink)
    }

    fun detachLocalSink(sink: org.webrtc.VideoSink) {
        assertMainThread()
        webRtcEngine.detachLocalSink(sink)
    }

    fun attachRemoteSink(sink: org.webrtc.VideoSink) {
        assertMainThread()
        val remoteCid = currentRoomState
            ?.participants
            ?.firstOrNull { it.cid != clientId }
            ?.cid
            ?: peerSlots.keys.firstOrNull()
            ?: return
        peerSlots[remoteCid]?.attachRemoteSink(sink)
    }

    fun detachRemoteSink(sink: org.webrtc.VideoSink) {
        assertMainThread()
        peerSlots.values.forEach { it.detachRemoteSink(sink) }
    }

    fun attachRemoteSinkForCid(cid: String, sink: org.webrtc.VideoSink) {
        assertMainThread()
        peerSlots[cid]?.attachRemoteSink(sink)
    }

    fun detachRemoteSinkForCid(cid: String, sink: org.webrtc.VideoSink) {
        assertMainThread()
        peerSlots[cid]?.detachRemoteSink(sink)
    }

    fun eglContext(): org.webrtc.EglBase.Context {
        assertMainThread()
        return webRtcEngine.getEglContext()
    }

    fun adjustLocalCameraZoom(scaleFactor: Float) {
        assertMainThread()
        webRtcEngine.adjustWorldCameraZoom(scaleFactor)
    }

    fun toggleFlashlight() {
        assertMainThread()
        webRtcEngine.toggleFlashlight()
    }

    // --- Internal: Start ---

    internal fun start() {
        assertMainThread()
        if (!hasRequiredPermissions()) {
            startWithPermissionCheck()
            return
        }
        startJoinInternal()
    }

    private fun startJoinInternal() {
        val joinAttemptId = ++joinAttemptSerial
        callStartTimeMs = System.currentTimeMillis()
        pendingMessages.clear()
        peerSlots.clear()
        currentRoomState = null
        hasJoinSignalStarted = false
        hasJoinAcknowledged = false
        if (webRtcStatsExecutor == null) {
            webRtcStatsExecutor = newWebRtcStatsExecutor()
        }

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
            )
        )
        updateDiagnostics(CallDiagnostics())
        scheduleJoinTimeout(roomId, joinAttemptId)
        scheduleJoinKickstart(joinAttemptId)

        acquirePerformanceLocks()
        callAudioSessionController.activate()
        webRtcEngine.startLocalMedia()

        if (!config.defaultAudioEnabled) webRtcEngine.toggleAudio(false)
        applyLocalVideoPreference()

        startRemoteVideoStatePolling()
        ensureSignalingConnection()
    }

    internal fun startWithPermissionCheck() {
        assertMainThread()
        awaitingPermissions = true
        val permissions = listOf(MediaCapability.CAMERA, MediaCapability.MICROPHONE)
        updateState(
            _state.value.copy(
                phase = CallPhase.AwaitingPermissions,
                roomId = roomId,
                requiredPermissions = permissions,
            )
        )
        handler.post {
            onPermissionsRequired?.invoke(permissions)
                ?: delegate?.invoke()?.onPermissionsRequired(this, permissions)
        }
    }

    // --- Internal: WebRTC Engine ---

    private fun buildWebRtcEngine(): WebRtcEngine {
        return WebRtcEngine(
            context = appContext,
            onCameraFacingChanged = { isFront ->
                handler.post {
                    updateDiagnostics(_diagnostics.value.copy(isFrontCamera = isFront))
                }
            },
            onCameraModeChanged = { mode ->
                handler.post {
                    val previousMode = _state.value.localCameraMode
                    updateState(_state.value.copy(localCameraMode = mode))
                    updateDiagnostics(_diagnostics.value.copy(isScreenSharing = mode == LocalCameraMode.SCREEN_SHARE))
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
                    updateDiagnostics(
                        _diagnostics.value.copy(
                            isFlashAvailable = available,
                            isFlashEnabled = enabled,
                        )
                    )
                }
            },
            onScreenShareStopped = {
                handler.post {
                    if (_diagnostics.value.isScreenSharing) {
                        updateDiagnostics(_diagnostics.value.copy(isScreenSharing = false))
                        broadcastContentState(false)
                    }
                    applyLocalVideoPreference()
                }
            },
            onFeatureDegradation = { degradation ->
                handler.post {
                    setFeatureDegradation(degradation)
                }
            },
            isHdVideoExperimentalEnabled = config.isHdVideoExperimentalEnabled
        )
    }

    private fun recreateWebRtcEngineForNewCall() {
        runCatching { webRtcEngine.release() }
        if (!isMediaEngineInjected) {
            webRtcEngine = buildWebRtcEngine()
        }
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
                        put("maxParticipants", 4)
                    }
                )
                put("createMaxParticipants", 4)
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
        val payload = msg.payload ?: return
        val fromCid = payload.optString("from")
        if (fromCid.isBlank()) return
        val active = payload.optBoolean("active")
        val contentType = if (active) payload.optString("contentType") else null
        updateDiagnostics(
            _diagnostics.value.copy(
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
                                peerSlots[cid]?.clearPendingIceRestart()
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
                                peerSlots[cid]?.clearPendingIceRestart()
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
                                peerSlots[cid]?.clearPendingIceRestart()
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
                    slot.clearPendingIceRestart()
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
        val currentDiagnostics = _diagnostics.value
        val activeCids = remoteParticipants.map { it.cid }.toSet()
        val clearContent = currentDiagnostics.remoteContentCid != null && currentDiagnostics.remoteContentCid !in activeCids
        if (currentState.remoteParticipants == remoteParticipants) {
            if (clearContent) {
                updateDiagnostics(currentDiagnostics.copy(remoteContentCid = null, remoteContentType = null))
            }
            return
        }
        updateState(currentState.copy(remoteParticipants = remoteParticipants))
        if (clearContent) {
            updateDiagnostics(currentDiagnostics.copy(remoteContentCid = null, remoteContentType = null))
        }
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
        if (slot.isMakingOffer) { if (iceRestart) slot.markPendingIceRestart(); return }
        if (!force && slot.sentOffer) return
        if (!canOffer(slot)) return
        if (slot.getSignalingState() != PeerConnection.SignalingState.STABLE) { if (iceRestart) slot.markPendingIceRestart(); return }
        slot.beginOffer()
        val started = slot.createOffer(
            iceRestart = iceRestart,
            onSdp = { sdp ->
                val payload = JSONObject().apply { put("sdp", sdp) }
                sendMessage("offer", payload, to = slot.remoteCid)
                scheduleOfferTimeout(slot.remoteCid)
            },
            onComplete = { success ->
                handler.post {
                    slot.completeOffer()
                    if (!success && iceRestart) scheduleIceRestart(slot.remoteCid, "offer-failed", 500)
                }
            }
        )
        if (!started) { slot.completeOffer(); if (iceRestart) slot.markPendingIceRestart(); return }
        if (!force) slot.markOfferSent()
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
            slot.cancelOfferTimeout()
            if (slot.getSignalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                slot.markPendingIceRestart()
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
        slot.setOfferTimeoutTask(runnable)
        handler.postDelayed(runnable, WebRtcResilienceConstants.OFFER_TIMEOUT_MS)
    }

    private fun clearOfferTimeout(remoteCid: String? = null) {
        if (remoteCid != null) {
            peerSlots[remoteCid]?.let { slot -> slot.offerTimeoutTask?.let { handler.removeCallbacks(it) }; slot.cancelOfferTimeout() }
        } else {
            peerSlots.values.forEach { slot -> slot.offerTimeoutTask?.let { r -> handler.removeCallbacks(r) }; slot.cancelOfferTimeout() }
        }
    }

    private fun scheduleIceRestart(reason: String, delayMs: Long) {
        peerSlots.values.forEach { if (shouldIOffer(it.remoteCid)) scheduleIceRestart(it.remoteCid, reason, delayMs) }
    }

    private fun scheduleIceRestart(remoteCid: String, reason: String, delayMs: Long) {
        val slot = peerSlots[remoteCid] ?: return
        if (!canOffer(slot)) { slot.markPendingIceRestart(); return }
        if (slot.iceRestartTask != null) return
        if (System.currentTimeMillis() - slot.lastIceRestartAt < WebRtcResilienceConstants.ICE_RESTART_COOLDOWN_MS) return
        val runnable = Runnable { slot.cancelIceRestartTask(); triggerIceRestart(remoteCid, reason) }
        slot.setIceRestartTask(runnable)
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearIceRestartTimer(remoteCid: String? = null) {
        if (remoteCid != null) {
            peerSlots[remoteCid]?.let { slot -> slot.iceRestartTask?.let { handler.removeCallbacks(it) }; slot.cancelIceRestartTask() }
        } else {
            peerSlots.values.forEach { slot -> slot.iceRestartTask?.let { r -> handler.removeCallbacks(r) }; slot.cancelIceRestartTask() }
        }
    }

    private fun triggerIceRestart(remoteCid: String, reason: String) {
        val slot = peerSlots[remoteCid] ?: return
        if (!canOffer(slot)) { slot.markPendingIceRestart(); return }
        if (slot.isMakingOffer) { slot.markPendingIceRestart(); return }
        Log.w(TAG, "ICE restart triggered for $remoteCid ($reason)")
        slot.recordIceRestart()
        maybeSendOffer(slot, force = true, iceRestart = true)
    }

    private fun maybeScheduleNonHostOfferFallback(remoteCid: String, reason: String) {
        val slot = peerSlots[remoteCid] ?: return
        if (shouldIOffer(remoteCid)) { clearNonHostOfferFallback(remoteCid); return }
        if (!signalingClient.isConnected()) return
        if (slot.nonHostFallbackTask != null) return
        if (slot.nonHostFallbackAttempts >= WebRtcResilienceConstants.NON_HOST_FALLBACK_MAX_ATTEMPTS) return
        val runnable = Runnable {
            slot.clearNonHostFallbackTask()
            slot.incrementNonHostFallbackAttempts()
            Log.w(TAG, "Non-host offer fallback for $remoteCid ($reason)")
            maybeSendNonHostFallbackOffer(remoteCid)
        }
        slot.setNonHostFallbackTask(runnable)
        handler.postDelayed(runnable, WebRtcResilienceConstants.NON_HOST_FALLBACK_DELAY_MS)
    }

    private fun clearNonHostOfferFallback(remoteCid: String? = null) {
        if (remoteCid != null) {
            peerSlots[remoteCid]?.let { slot -> slot.nonHostFallbackTask?.let { handler.removeCallbacks(it) }; slot.cancelNonHostFallbackTask() }
        } else {
            peerSlots.values.forEach { slot -> slot.nonHostFallbackTask?.let { r -> handler.removeCallbacks(r) }; slot.cancelNonHostFallbackTask() }
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
        slot.beginOffer()
        val started = slot.createOffer(
            onSdp = { sdp ->
                sendMessage("offer", JSONObject().apply { put("sdp", sdp) }, to = remoteCid)
                scheduleOfferTimeout(remoteCid)
            },
            onComplete = { success ->
                handler.post { slot.completeOffer(); if (!success) maybeScheduleNonHostOfferFallback(remoteCid, "offer-failed") }
            }
        )
        if (!started) { slot.completeOffer(); maybeScheduleNonHostOfferFallback(remoteCid, "offer-not-started") }
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

    private fun scheduleJoinKickstart(joinAttemptId: Long) {
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
        val current = _diagnostics.value
        val next = current.copy(
            iceConnectionState = IceConnectionState.from(bestIce),
            peerConnectionState = PeerConnectionState.from(bestConn),
            rtcSignalingState = RtcSignalingState.from(bestSig),
        )
        if (next != current) {
            updateDiagnostics(next)
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

    private fun updateDiagnostics(newDiagnostics: CallDiagnostics) {
        _diagnostics.value = newDiagnostics
    }

    private fun setFeatureDegradation(degradation: FeatureDegradationState) {
        val current = _diagnostics.value
        val nextDegradations = current.featureDegradations
            .filterNot { it.kind == degradation.kind } + degradation
        updateDiagnostics(current.copy(featureDegradations = nextDegradations))
    }

    // --- Internal: Connection Status ---

    private fun isConnectionDegraded(diagnostics: CallDiagnostics = _diagnostics.value): Boolean {
        return !diagnostics.isSignalingConnected ||
            diagnostics.iceConnectionState == IceConnectionState.DISCONNECTED ||
            diagnostics.iceConnectionState == IceConnectionState.FAILED ||
            diagnostics.peerConnectionState == PeerConnectionState.DISCONNECTED ||
            diagnostics.peerConnectionState == PeerConnectionState.FAILED
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
        if (isConnectionDegraded(_diagnostics.value)) { markConnectionDegraded(); return }
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
        val executor = webRtcStatsExecutor?.takeIf { !it.isShutdown }
        if (executor == null) { webrtcStatsRequestInFlight = false; return }
        try { executor.execute {
            val stats = mutableListOf<RealtimeCallStats>()
            var remaining = slots.size
            slots.forEach { slot ->
                slot.collectWebRtcStats { _, realtimeStats ->
                    synchronized(stats) {
                        realtimeStats?.let(stats::add)
                        remaining -= 1
                        if (remaining == 0) {
                            val merged = mergeRealtimeStats(stats)
                            handler.post {
                                webrtcStatsRequestInFlight = false
                                lastWebRtcStatsPollAtMs = System.currentTimeMillis()
                                if (merged != null) {
                                    val nextCallStats = CallStats(
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
                                        realtimeStats = merged,
                                        updatedAtMs = merged.updatedAtMs,
                                    )
                                    updateDiagnostics(
                                        _diagnostics.value.copy(
                                            callStats = nextCallStats,
                                            realtimeStats = merged,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        } catch (_: java.util.concurrent.RejectedExecutionException) { webrtcStatsRequestInFlight = false }
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
        if (_diagnostics.value.isScreenSharing) webRtcEngine.stopScreenShare()
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
        webRtcStatsExecutor?.shutdown()
        webRtcStatsExecutor = null
        unregisterConnectivityListener()
        clientId = null; hostCid = null; currentRoomState = null; callStartTimeMs = null
        pendingJoinRoom = null; pendingMessages.clear(); reconnectAttempts = 0
        clearConnectionStatusRetryingTimer()
        userPreferredVideoEnabled = config.defaultVideoEnabled; isVideoPausedByProximity = false
        reconnectToken = null; turnTokenTTLMs = null; hasJoinSignalStarted = false; hasJoinAcknowledged = false
        updateDiagnostics(CallDiagnostics())
    }

    private fun applyLocalVideoPreference() {
        val shouldPause = callAudioSessionController.shouldPauseVideoForProximity(_diagnostics.value.isScreenSharing)
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

    private fun hasRequiredPermissions(): Boolean {
        return REQUIRED_ANDROID_PERMISSIONS.all { permission ->
            appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private companion object {
        const val TAG = "SerenadaSession"
        const val WEBRTC_STATS_POLL_INTERVAL_MS = 2000L
        const val CPU_WAKE_LOCK_TAG = "serenada:call-cpu"
        val REQUIRED_ANDROID_PERMISSIONS = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
        )
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
