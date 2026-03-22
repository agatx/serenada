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
import app.serenada.core.call.ConnectionStatusTracker
import app.serenada.core.call.JoinTimer
import app.serenada.core.call.LiveSessionClock
import app.serenada.core.call.PeerNegotiationEngine
import app.serenada.core.call.SessionClock
import app.serenada.core.call.StatsPoller
import app.serenada.core.call.TurnManager
import app.serenada.core.call.CallAudioSessionController
import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.ContentTypeWire
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.LocalFrameSnapshotCapture
import app.serenada.core.call.Participant
import app.serenada.core.call.PeerConnectionSlotProtocol
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
    clock: SessionClock? = null,
    private val logger: SerenadaLogger? = null,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var webRtcStatsExecutor: ExecutorService? = newWebRtcStatsExecutor()
    private val apiClient: SessionAPIClient = apiClient ?: CoreApiClient(okHttpClient)
    private val clock: SessionClock = clock ?: LiveSessionClock()
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
                    peerNegotiationEngine.scheduleIceRestart("network-online", 0)
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
    private val connectionStatusTracker = ConnectionStatusTracker(
        handler = handler,
        getPhase = { _state.value.phase },
        getDiagnostics = { _diagnostics.value },
        getCurrentStatus = { _state.value.connectionStatus },
        setConnectionStatus = { status ->
            if (_state.value.connectionStatus != status) updateState(_state.value.copy(connectionStatus = status))
        },
    )
    private val joinTimer = JoinTimer(
        handler = handler,
        getPhase = { _state.value.phase },
        getJoinAttemptSerial = { joinAttemptSerial },
        hasJoinSignalStarted = { hasJoinSignalStarted },
        hasJoinAcknowledged = { hasJoinAcknowledged },
        isSignalingConnected = { signalingClient.isConnected() },
        onJoinTimeout = {
            resetResources()
            updateState(CallState(phase = CallPhase.Error, errorMessage = "Connection failed"))
            delegate?.invoke()?.onSessionEnded(this, EndReason.ERROR)
        },
        ensureSignalingConnection = { ensureSignalingConnection() },
        onRecovery = {
            if (_state.value.phase == CallPhase.Joining) {
                updateState(_state.value.copy(phase = CallPhase.Waiting, participantCount = 1))
                updateConnectionStatusFromSignals()
            }
        },
        setPendingJoinRoom = { roomId -> pendingJoinRoom = roomId },
    )
    private val turnManager = TurnManager(
        handler = handler,
        serverHost = serverHost,
        apiClient = this.apiClient,
        isSignalingConnected = { signalingClient.isConnected() },
        setIceServers = { servers -> webRtcEngine.setIceServers(servers) },
        onIceServersReady = {
            while (pendingMessages.isNotEmpty() && webRtcEngine.hasIceServers()) {
                peerNegotiationEngine.processSignalingPayload(pendingMessages.removeFirst())
            }
            peerNegotiationEngine.onIceServersReady()
        },
        sendTurnRefresh = { sendMessage("turn-refresh", null) },
    )
    private val statsPoller = StatsPoller(
        handler = handler,
        clock = this.clock,
        statsExecutorProvider = { webRtcStatsExecutor },
        isActivePhase = {
            val phase = _state.value.phase
            phase == CallPhase.InCall || phase == CallPhase.Waiting || phase == CallPhase.Joining
        },
        getPeerSlots = { peerSlots.values.toList() },
        onStatsUpdated = { merged ->
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
        },
        onRefreshRemoteParticipants = { refreshRemoteParticipants() },
    )
    private val pendingMessages = java.util.ArrayDeque<SignalingMessage>()
    private val peerSlots = mutableMapOf<String, PeerConnectionSlotProtocol>()
    private val peerNegotiationEngine: PeerNegotiationEngine
    private var reconnectToken: String? = null
    private var hasJoinSignalStarted = false
    private var hasJoinAcknowledged = false
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var userPreferredVideoEnabled = config.defaultVideoEnabled
    private var isVideoPausedByProximity = false
    private val isMediaEngineInjected = mediaEngine != null
    private var webRtcEngine: SessionMediaEngine = mediaEngine ?: buildWebRtcEngine()
    private var awaitingPermissions = false

    init {
        peerNegotiationEngine = PeerNegotiationEngine(
            handler = handler,
            clock = this.clock,
            getClientId = { clientId },
            getHostCid = { hostCid },
            getParticipantCount = { _state.value.participantCount },
            getCurrentRoomState = { currentRoomState },
            isSignalingConnected = { signalingClient.isConnected() },
            hasIceServers = { webRtcEngine.hasIceServers() },
            getSlot = { cid: String -> peerSlots[cid] },
            getAllSlots = { peerSlots.toMap() },
            setSlot = { cid: String, slot: PeerConnectionSlotProtocol -> peerSlots[cid] = slot },
            removeSlotEntry = { cid: String -> peerSlots.remove(cid) },
            createSlotViaEngine = {
                remoteCid: String,
                onLocalIce: (String, org.webrtc.IceCandidate) -> Unit,
                onRemoteVideo: (String, org.webrtc.VideoTrack?) -> Unit,
                onConnState: (String, org.webrtc.PeerConnection.PeerConnectionState) -> Unit,
                onIceConnState: (String, org.webrtc.PeerConnection.IceConnectionState) -> Unit,
                onSigState: (String, org.webrtc.PeerConnection.SignalingState) -> Unit,
                onRenegotiation: (String) -> Unit ->
                webRtcEngine.createSlot(
                    remoteCid = remoteCid,
                    onLocalIceCandidate = onLocalIce,
                    onRemoteVideoTrack = onRemoteVideo,
                    onConnectionStateChange = onConnState,
                    onIceConnectionStateChange = onIceConnState,
                    onSignalingStateChange = onSigState,
                    onRenegotiationNeeded = onRenegotiation,
                )
            },
            engineRemoveSlot = { slot: PeerConnectionSlotProtocol -> webRtcEngine.removeSlot(slot) },
            sendMessage = { type: String, payload: org.json.JSONObject?, to: String? -> sendMessage(type, payload, to) },
            onRemoteParticipantsChanged = { refreshRemoteParticipants() },
            onAggregatePeerStateChanged = { ice: IceConnectionState, conn: PeerConnectionState, sig: RtcSignalingState ->
                val current = _diagnostics.value
                val next = current.copy(
                    iceConnectionState = ice,
                    peerConnectionState = conn,
                    rtcSignalingState = sig,
                )
                if (next != current) updateDiagnostics(next)
            },
            onConnectionStatusUpdate = { updateConnectionStatusFromSignals() },
            logger = logger,
        )
    }

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
            logger?.log(SerenadaLogLevel.DEBUG, "Session", "Proximity sensor changed: ${if (near) "NEAR" else "FAR"}")
        },
        onAudioEnvironmentChanged = { applyLocalVideoPreference() },
        logger = logger,
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
        okHttpClient, handler, signalingListener, forceSse = forceSse, logger = logger,
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
            logger?.log(SerenadaLogLevel.WARNING, "Session", "Failed to start screen sharing")
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
            logger?.log(SerenadaLogLevel.WARNING, "Session", "Failed to stop screen sharing")
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
            isHdVideoExperimentalEnabled = config.isHdVideoExperimentalEnabled,
            logger = logger,
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
        logger?.log(SerenadaLogLevel.DEBUG, "Session", "TX $type")
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
        logger?.log(SerenadaLogLevel.DEBUG, "Session", "RX ${msg.type}")
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
            turnManager.handleJoinedTTL(ttl)
        }
        val roomState = parseRoomState(msg.payload)
        if (roomState != null) {
            currentRoomState = roomState
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        }
        val token = msg.payload?.optString("turnToken").orEmpty().ifBlank { null }
        if (!token.isNullOrBlank()) {
            turnManager.fetchTurnCredentials(token)
        } else {
            turnManager.applyDefaultIceServers()
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
        peerNegotiationEngine.processSignalingPayload(msg)
    }

    // --- Internal: Participants ---

    private fun updateParticipants(roomState: RoomState) {
        val count = roomState.participants.size
        val isHostNow = clientId != null && clientId == roomState.hostCid
        val phase = if (count <= 1) CallPhase.Waiting else CallPhase.InCall
        if (phase != CallPhase.Joining) clearJoinTimeout()

        updateState(
            _state.value.copy(
                phase = phase,
                isHost = isHostNow,
                participantCount = count,
            )
        )

        peerNegotiationEngine.syncPeers(roomState)
        refreshRemoteParticipants()
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

    // --- Internal: Timers ---

    private fun scheduleJoinTimeout(roomId: String, joinAttemptId: Long) {
        joinTimer.scheduleTimeout(roomId, joinAttemptId)
    }

    private fun clearJoinTimeout() {
        joinTimer.clearTimeout()
    }

    private fun scheduleJoinKickstart(joinAttemptId: Long) {
        joinTimer.scheduleKickstart(joinAttemptId)
    }

    private fun clearJoinKickstart() {
        joinTimer.clearKickstart()
    }

    private fun scheduleJoinRecovery(roomId: String) {
        joinTimer.scheduleRecovery(roomId)
    }

    private fun clearJoinRecovery() {
        joinTimer.clearRecovery()
    }

    // --- Internal: TURN ---

    private fun handleTurnRefreshed(msg: SignalingMessage) {
        turnManager.handleTurnRefreshed(msg)
    }

    private fun clearTurnRefresh() {
        turnManager.cancelRefresh()
    }

    // --- Internal: State ---


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

    private fun isConnectionDegraded(): Boolean {
        return connectionStatusTracker.isConnectionDegraded()
    }

    private fun markConnectionDegraded() {
        connectionStatusTracker.update()
    }

    private fun updateConnectionStatusFromSignals() {
        connectionStatusTracker.update()
    }

    private fun clearConnectionStatusRetryingTimer() {
        connectionStatusTracker.cancelTimer()
    }

    // --- Internal: Stats Polling ---

    private fun startRemoteVideoStatePolling() {
        statsPoller.start()
    }

    private fun stopRemoteVideoStatePolling() {
        statsPoller.stop()
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
        peerNegotiationEngine.resetAll()
        clearTurnRefresh()
        clearReconnect()
        callAudioSessionController.deactivate()
        releasePerformanceLocks()
        stopRemoteVideoStatePolling()
        signalingClient.close()
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
        reconnectToken = null; turnManager.reset(); hasJoinSignalStarted = false; hasJoinAcknowledged = false
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
        const val CPU_WAKE_LOCK_TAG = "serenada:call-cpu"
        val REQUIRED_ANDROID_PERMISSIONS = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
        )
    }
}
