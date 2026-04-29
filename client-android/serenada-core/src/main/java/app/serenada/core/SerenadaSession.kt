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
import app.serenada.core.call.dedupeParticipants
import app.serenada.core.call.resolveHostPeerId
import app.serenada.core.call.ConnectionStatusTracker
import app.serenada.core.call.JoinFlowCoordinator
import app.serenada.core.call.LiveSessionClock
import app.serenada.core.call.PeerNegotiationEngine
import app.serenada.core.call.SessionClock
import app.serenada.core.call.RemoteMediaState
import app.serenada.core.call.resolveCameraModes
import app.serenada.core.call.SignalingMessageRouter
import app.serenada.core.call.StatsPoller
import app.serenada.core.call.CallAudioSessionController
import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.ContentTypeWire
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.LocalFrameSnapshotCapture
import app.serenada.core.call.PeerConnectionSlotProtocol
import app.serenada.core.call.RemoteParticipant
import app.serenada.core.call.SerenadaPeerConnectionState
import app.serenada.core.call.RoomState
import app.serenada.core.call.Participant
import app.serenada.core.call.SessionAudioController
import app.serenada.core.call.SessionMediaEngine
import app.serenada.core.call.SignalingMessage
import app.serenada.core.call.WebRtcEngine
import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.call.CameraCaptureController
import app.serenada.core.call.toContentStatePayload
import app.serenada.core.network.CoreApiClient
import app.serenada.core.network.SessionAPIClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Represents an active call session. Created via [SerenadaCore.join] or [SerenadaCore.createRoom].
 *
 * Observe [state] for app-facing call state changes and [diagnostics] for low-level transport/media details.
 * Control the call via [leave], [end], [toggleAudio], [toggleVideo], etc.
 */
class SerenadaSession internal constructor(
    /** The room ID for this call session. */
    val roomId: String,
    /** Full URL for this call session (e.g. "https://serenada.app/call/ABC123"). */
    val roomUrl: String?,
    private val config: SerenadaConfig,
    private val context: Context,
    private val delegate: (() -> SerenadaCoreDelegate?)?,
    okHttpClient: OkHttpClient,
    initialSignalingProvider: SignalingProvider? = null,
    signaling: app.serenada.core.call.SessionSignaling? = null,
    apiClient: SessionAPIClient? = null,
    audioController: SessionAudioController? = null,
    mediaEngine: SessionMediaEngine? = null,
    clock: SessionClock? = null,
    private val logger: SerenadaLogger? = null,
    private val displayName: String? = null,
    private val peerId: String? = null,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var webRtcStatsExecutor: ExecutorService? = newWebRtcStatsExecutor()
    private val apiClient: SessionAPIClient = apiClient ?: CoreApiClient(okHttpClient)
    private val clock: SessionClock = clock ?: LiveSessionClock()
    private val resolvedConfig = resolveSerenadaConfig(config)
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _state = MutableStateFlow(CallState())
    /** Primary observable call state. Collect this flow for UI updates. */
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(CallDiagnostics())
    /** Real-time connection diagnostics (stats, transport state, ICE state). */
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
    private val remoteMediaStates = mutableMapOf<String, RemoteMediaState>()
    private var callStartTimeMs: Long? = null
    private var pendingJoinRoom: String? = null
    private val connectionStatusTracker = ConnectionStatusTracker(
        handler = handler,
        getPhase = { _state.value.phase },
        getDiagnostics = { _diagnostics.value },
        getCurrentStatus = { _state.value.connectionStatus },
        setConnectionStatus = { status ->
            if (_state.value.connectionStatus != status) updateState(_state.value.copy(connectionStatus = status))
        },
    )
    private val joinFlowCoordinator = JoinFlowCoordinator(
        handler = handler,
        roomId = roomId,
        getPhase = { _state.value.phase },
        isSignalingConnected = { _diagnostics.value.isSignalingConnected },
        onStartJoinInternal = { startJoinInternal() },
        onPermissionCheckRequired = { startWithPermissionCheck() },
        connectProvider = { signalingProvider.connect() },
        joinRoom = { targetRoomId, reconnectPeerId ->
            signalingProvider.joinRoom(
                targetRoomId,
                JoinOptions(
                    reconnectPeerId = reconnectPeerId,
                    maxParticipants = 4,
                    displayName = displayName,
                    appPeerId = peerId,
                ),
            )
        },
        onJoinTimeout = {
            resetResources()
            updateState(CallState(phase = CallPhase.Error, error = CallError.ConnectionFailed))
            delegate?.invoke()?.onSessionEnded(this, EndReason.Error(CallError.ConnectionFailed))
        },
        onJoinRecovery = {
            if (_state.value.phase == CallPhase.Joining) {
                updateState(_state.value.copy(phase = CallPhase.Waiting, participantCount = 1))
                updateConnectionStatusFromSignals()
            }
        },
        setPendingJoinRoom = { roomId -> pendingJoinRoom = roomId },
        getReconnectPeerId = { clientId },
    )
    private val signalingMessageRouter = SignalingMessageRouter(
        getClientId = { clientId },
        getHostCid = { hostCid },
        onJoined = { cid, _, roomState, _, _, newReconnectToken ->
            clientId = cid
            updateState(_state.value.copy(localCid = clientId))
            newReconnectToken?.let { reconnectToken = it }
            if (roomState != null) {
                currentRoomState = roomState
                hostCid = roomState.hostCid
                updateParticipants(roomState)
            }
            broadcastLocalMediaState()
            loadInitialIceServers()
        },
        onRoomStateUpdated = { roomState ->
            currentRoomState = roomState
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        },
        onError = { callError ->
            joinFlowCoordinator.clearJoinTimeout()
            resetResources()
            updateState(CallState(phase = CallPhase.Error, error = callError))
            delegate?.invoke()?.onSessionEnded(this, EndReason.Error(callError))
        },
        onRoomEnded = { cleanupCall(EndReason.RemoteEnded) },
        onContentStateReceived = { fromCid, active, contentType ->
            updateDiagnostics(
                _diagnostics.value.copy(
                    remoteContentCid = if (active) fromCid else null,
                    remoteContentType = contentType,
                )
            )
        },
        onMediaStateReceived = { fromCid, audioEnabled, videoEnabled ->
            val existing = remoteMediaStates[fromCid]
            remoteMediaStates[fromCid] = RemoteMediaState(
                audioEnabled = audioEnabled ?: existing?.audioEnabled,
                videoEnabled = videoEnabled ?: existing?.videoEnabled,
            )
            refreshRemoteParticipants()
        },
        onTurnRefreshed = { _ -> },
        onSignalingPayload = { msg -> handleSignalingPayload(msg) },
        onPong = { },
        sendMessage = { type, payload, to -> sendMessage(type, payload, to) },
        clearJoinTimers = { joinFlowCoordinator.clearAllJoinTimers() },
        setJoinAcknowledged = { joinFlowCoordinator.markJoinAcknowledged() },
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
    private val signalingProvider: SignalingProvider
    private var reconnectToken: String? = null
    private var reconnectRecoveryPending = false
    private var iceFetchGeneration = 0
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private val availableCameraModes: List<LocalCameraMode> = resolveAvailableCameraModes()
    private val videoCaptureSupported: Boolean = availableCameraModes.isNotEmpty()
    private var userPreferredVideoEnabled = videoCaptureSupported && config.defaultVideoEnabled
    private var isVideoPausedByProximity = false
    private val isMediaEngineInjected = mediaEngine != null
    // Owned at the session level so that engine recreation (or release on call end) does not
    // invalidate the EglBase.Context handed to Compose AndroidView factories. Releasing the
    // EglBase before the call UI unmounts caused crashes in WebRTC's EglBase14Impl with
    // "Invalid sharedContext" when a new PiP renderer was created with a stale handle.
    private val eglBase: EglBase? = if (isMediaEngineInjected) null else EglBase.create()
    private var webRtcEngine: SessionMediaEngine = mediaEngine ?: buildWebRtcEngine()
    private var awaitingPermissions = false
    private var hasInitialIceServers = false

    private fun resolveAvailableCameraModes(): List<LocalCameraMode> {
        val configuredModes = resolveCameraModes(config.cameraModes)
        if (LocalCameraMode.COMPOSITE !in configuredModes) return configuredModes
        val compositeAvailable = CameraCaptureController.isCompositeCameraModeAvailable(appContext, logger)
        return resolveCameraModes(config.cameraModes, compositeAvailable = compositeAvailable)
    }

    init {
        peerNegotiationEngine = PeerNegotiationEngine(
            handler = handler,
            clock = this.clock,
            getClientId = { clientId },
            getHostCid = { hostCid },
            getParticipantCount = { _state.value.participantCount },
            getCurrentRoomState = { currentRoomState },
            isSignalingConnected = { _diagnostics.value.isSignalingConnected },
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
        signalingProvider = initialSignalingProvider ?: resolvedConfig.signalingProvider ?: SerenadaServerProvider(
            serverHost = resolvedConfig.serverHost ?: throw IllegalStateException("requires serverHost"),
            handler = handler,
            okHttpClient = okHttpClient,
            apiClient = this.apiClient,
            signaling = signaling,
            transports = config.transports,
            logger = logger,
        )
        signalingProvider.listener = buildProviderListener()

        // Skip periodic TURN refresh while every peer is on a direct ICE path —
        // the credentials go unused and the call survives arbitrary-length
        // signaling outages. A failover to relay triggers the next refresh.
        // Gate returns `true` to allow the refresh, so we negate the direct-
        // path check: direct → `false` (skip), relay/unknown → `true` (refresh).
        (signalingProvider as? SerenadaServerProvider)?.setTurnRefreshGate {
            !arePeerPathsAllDirect()
        }
    }

    /**
     * True only when at least one peer exists and every slot's last observed
     * candidate pair is direct. A null cached value (no stats yet) is treated
     * as "not confirmed direct" so the gate errs on the side of refreshing.
     */
    private fun arePeerPathsAllDirect(): Boolean {
        val slots = peerSlots.values
        if (slots.isEmpty()) return false
        for (slot in slots) {
            val direct = slot.isPathDirect() ?: return false
            if (!direct) return false
        }
        return true
    }

    /** Callback invoked when camera/microphone permissions are needed before joining. */
    var onPermissionsRequired: ((List<MediaCapability>) -> Unit)? = null

    val host: String
        get() = resolvedConfig.serverHost ?: throw IllegalStateException("requires serverHost")

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SerenadaSession APIs must be called on the main thread"
        }
    }

    private val callAudioSessionController: SessionAudioController = audioController ?: CallAudioSessionController(
        context = appContext,
        handler = handler,
        proximityMonitoringEnabled = config.proximityMonitoringEnabled,
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

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) {
            block()
        } else {
            handler.post(block)
        }
    }

    private fun buildProviderListener(): SignalingProvider.Listener = object : SignalingProvider.Listener {
        override fun onConnected(info: ConnectionInfo) {
            runOnMain {
                joinFlowCoordinator.resetReconnectAttempts()
                updateDiagnostics(
                    _diagnostics.value.copy(
                        isSignalingConnected = true,
                        activeTransport = info.transport,
                    )
                )
                updateConnectionStatusFromSignals()
                if (reconnectRecoveryPending && currentRoomState != null) {
                    reconnectRecoveryPending = false
                    peerNegotiationEngine.scheduleIceRestart("signaling-reconnect", 0)
                }
                pendingJoinRoom?.let { join ->
                    pendingJoinRoom = null
                    joinFlowCoordinator.sendJoin(join)
                }
            }
        }

        override fun onDisconnected(reason: String?) {
            runOnMain {
                val shouldReconnect = _state.value.phase != CallPhase.Idle
                updateDiagnostics(
                    _diagnostics.value.copy(
                        isSignalingConnected = false,
                        activeTransport = null,
                    )
                )
                updateConnectionStatusFromSignals()
                if (shouldReconnect) {
                    if (signalingProvider.capabilities.handlesReconnection) {
                        reconnectRecoveryPending = currentRoomState != null
                    } else {
                        joinFlowCoordinator.scheduleReconnect()
                    }
                }
            }
        }

        override fun onJoined(event: JoinedEvent) {
            runOnMain {
                logger?.log(SerenadaLogLevel.DEBUG, "Session", "RX joined")
                signalingMessageRouter.processJoinedEvent(event)
            }
        }

        override fun onRoomStateUpdated(event: RoomStateEvent) {
            runOnMain {
                logger?.log(SerenadaLogLevel.DEBUG, "Session", "RX room_state")
                signalingMessageRouter.processRoomStateEvent(event)
            }
        }

        override fun onPeerJoined(event: PeerEvent) {
            runOnMain {
                currentRoomState = upsertParticipant(currentRoomState, event, clientId)
                currentRoomState?.let { roomState ->
                    hostCid = roomState.hostCid
                    updateParticipants(roomState)
                }
                broadcastLocalMediaState()
            }
        }

        override fun onPeerLeft(event: PeerEvent) {
            runOnMain {
                remoteMediaStates.remove(event.peerId)
                currentRoomState = removeParticipant(currentRoomState, event.peerId, clientId)
                currentRoomState?.let { roomState ->
                    hostCid = roomState.hostCid
                    updateParticipants(roomState)
                }
            }
        }

        override fun onMessage(message: PeerMessage) {
            runOnMain {
                logger?.log(SerenadaLogLevel.DEBUG, "Session", "RX ${message.type}")
                if (message.type == "content_state" || message.type == "participant_media_state" || message.type == "offer" || message.type == "answer" || message.type == "ice") {
                    signalingMessageRouter.processPeerMessage(message)
                }
            }
        }

        override fun onRoomEnded(event: RoomEndedEvent) {
            runOnMain {
                logger?.log(SerenadaLogLevel.DEBUG, "Session", "RX room_ended (${event.reason})")
                cleanupCall(EndReason.RemoteEnded)
            }
        }

        override fun onError(event: ErrorEvent) {
            runOnMain {
                logger?.log(SerenadaLogLevel.DEBUG, "Session", "RX error ${event.code}")
                signalingMessageRouter.processErrorEvent(event)
            }
        }

        override fun onIceServersChanged(iceServers: List<PeerConnection.IceServer>) {
            runOnMain {
                applyIceServers(iceServers)
            }
        }
    }

    // --- Public API ---

    /** Leave the call gracefully. The other participant stays in the room. */
    fun leave() {
        assertMainThread()
        if (_state.value.phase == CallPhase.Idle) return
        signalingProvider.leaveRoom()
        cleanupCall(EndReason.LocalLeft)
    }

    /** End the call for all participants. */
    fun end() {
        assertMainThread()
        signalingProvider.endRoom()
        leave()
    }

    /** Toggle local audio on or off. */
    fun toggleAudio() {
        assertMainThread()
        val enabled = !_state.value.localAudioEnabled
        webRtcEngine.toggleAudio(enabled)
        updateState(_state.value.copy(localAudioEnabled = enabled))
        broadcastLocalMediaState()
    }

    /** Toggle local video on or off. */
    fun toggleVideo() {
        assertMainThread()
        if (!videoCaptureSupported) return
        userPreferredVideoEnabled = !_state.value.localVideoEnabled
        applyLocalVideoPreference()
        broadcastLocalMediaState()
    }

    /** Cycle to the next camera mode in the configured preference order. */
    fun flipCamera() {
        assertMainThread()
        if (availableCameraModes.size <= 1) return
        if (!_diagnostics.value.isScreenSharing) {
            val currentMode = _state.value.localCameraMode
            if (currentMode.isContentMode) signalingMessageRouter.broadcastContentState(false)
            webRtcEngine.flipCamera()
        }
    }

    /** Set a specific camera mode. Ignored when [mode] is not in the configured list. */
    fun setCameraMode(mode: LocalCameraMode) {
        assertMainThread()
        if (mode == _state.value.localCameraMode) return
        if (mode !in availableCameraModes) return
        repeat(availableCameraModes.size) {
            if (_state.value.localCameraMode == mode) return
            flipCamera()
        }
    }

    /** Start screen sharing using the given media projection intent. */
    fun startScreenShare(intent: Intent) {
        assertMainThread()
        if (_diagnostics.value.isScreenSharing) return
        if (!webRtcEngine.startScreenShare(intent)) {
            logger?.log(SerenadaLogLevel.WARNING, "Session", "Failed to start screen sharing")
            return
        }
        updateDiagnostics(_diagnostics.value.copy(isScreenSharing = true))
        signalingMessageRouter.broadcastContentState(true, ContentTypeWire.SCREEN_SHARE)
        applyLocalVideoPreference()
    }

    /** Stop screen sharing and return to camera. */
    fun stopScreenShare() {
        assertMainThread()
        if (!_diagnostics.value.isScreenSharing) return
        if (!webRtcEngine.stopScreenShare()) {
            logger?.log(SerenadaLogLevel.WARNING, "Session", "Failed to stop screen sharing")
            return
        }
        updateDiagnostics(_diagnostics.value.copy(isScreenSharing = false))
        signalingMessageRouter.broadcastContentState(false)
        applyLocalVideoPreference()
    }

    /** Capture a JPEG snapshot of the local video frame. */
    fun captureLocalSnapshot(onResult: (ByteArray?) -> Unit) {
        assertMainThread()
        LocalFrameSnapshotCapture(
            handler = handler,
            attachLocalSink = { sink -> webRtcEngine.attachLocalSink(sink) },
            detachLocalSink = { sink -> webRtcEngine.detachLocalSink(sink) },
        ).capture(onResult)
    }

    /** Resume joining after camera/microphone permissions have been granted. */
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

    /** Cancel an in-progress join attempt. */
    fun cancelJoin() {
        assertMainThread()
        if (awaitingPermissions) {
            awaitingPermissions = false
            cleanupCall(EndReason.LocalLeft)
        }
    }

    /** Attach a [SurfaceViewRenderer][org.webrtc.SurfaceViewRenderer] for local video preview. */
    fun attachLocalRenderer(
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null,
    ) {
        assertMainThread()
        webRtcEngine.attachLocalRenderer(renderer, rendererEvents)
    }

    /** Detach a previously attached local video renderer. */
    fun detachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        assertMainThread()
        webRtcEngine.detachLocalRenderer(renderer)
    }

    /** Attach a [SurfaceViewRenderer][org.webrtc.SurfaceViewRenderer] for remote video. */
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

    /** Detach a previously attached remote video renderer. */
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

    /** Get the EGL context for custom rendering or renderer initialization. */
    fun eglContext(): EglBase.Context {
        assertMainThread()
        return eglBase?.eglBaseContext ?: webRtcEngine.getEglContext()
    }

    /** Adjust the camera zoom level by the given scale factor. */
    fun adjustLocalCameraZoom(scaleFactor: Float) {
        assertMainThread()
        webRtcEngine.adjustWorldCameraZoom(scaleFactor)
    }

    /** Toggle the device flashlight on or off. */
    fun toggleFlashlight() {
        assertMainThread()
        webRtcEngine.toggleFlashlight()
    }

    // --- Internal: Start ---

    internal fun start() {
        assertMainThread()
        joinFlowCoordinator.start(hasRequiredPermissions())
    }

    private fun startJoinInternal() {
        val joinAttemptId = joinFlowCoordinator.prepareJoinAttempt()
        callStartTimeMs = System.currentTimeMillis()
        pendingMessages.clear()
        peerSlots.clear()
        currentRoomState = null
        hasInitialIceServers = false
        reconnectRecoveryPending = false
        iceFetchGeneration += 1
        if (webRtcStatsExecutor == null) {
            webRtcStatsExecutor = newWebRtcStatsExecutor()
        }

        recreateWebRtcEngineForNewCall()
        registerConnectivityListener()

        val initialCameraMode = availableCameraModes.firstOrNull() ?: LocalCameraMode.SELFIE
        updateState(
            _state.value.copy(
                phase = CallPhase.Joining,
                roomId = roomId,
                error = null,
                localAudioEnabled = config.defaultAudioEnabled,
                localVideoEnabled = videoCaptureSupported && config.defaultVideoEnabled,
                localDisplayName = displayName,
                remoteParticipants = emptyList(),
                localCameraMode = initialCameraMode,
                availableCameraModes = availableCameraModes,
                connectionStatus = ConnectionStatus.Connected,
            )
        )
        updateDiagnostics(CallDiagnostics())
        joinFlowCoordinator.scheduleJoinTimeout(roomId, joinAttemptId)
        joinFlowCoordinator.scheduleJoinKickstart(joinAttemptId)

        acquirePerformanceLocks()
        callAudioSessionController.activate()
        webRtcEngine.startLocalMedia()

        if (!config.defaultAudioEnabled) webRtcEngine.toggleAudio(false)
        applyLocalVideoPreference()

        startRemoteVideoStatePolling()
        joinFlowCoordinator.ensureSignalingConnection()
    }

    internal fun startWithPermissionCheck() {
        assertMainThread()
        awaitingPermissions = true
        val permissions = if (videoCaptureSupported) {
            listOf(MediaCapability.CAMERA, MediaCapability.MICROPHONE)
        } else {
            listOf(MediaCapability.MICROPHONE)
        }
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
        val sharedEglBase = requireNotNull(eglBase) {
            "buildWebRtcEngine should not be called when a media engine is injected"
        }
        return WebRtcEngine(
            context = appContext,
            eglBase = sharedEglBase,
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
                        signalingMessageRouter.broadcastContentState(true, type)
                    } else if (wasContent) {
                        signalingMessageRouter.broadcastContentState(false)
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
                        signalingMessageRouter.broadcastContentState(false)
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
            availableCameraModes = availableCameraModes,
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

    private fun sendMessage(type: String, payload: JSONObject?, to: String? = null) {
        logger?.log(SerenadaLogLevel.DEBUG, "Session", "TX $type")
        if (to != null) {
            signalingProvider.sendToPeer(to, type, payload)
        } else {
            signalingProvider.broadcast(type, payload)
        }
    }

    private fun handleSignalingPayload(msg: SignalingMessage) {
        if (!webRtcEngine.hasIceServers()) {
            pendingMessages.add(msg)
            return
        }
        peerNegotiationEngine.processSignalingPayload(msg)
    }

    // Adapter functions (joinedMessageFromEvent, roomStateMessageFromEvent,
    // signalingMessageFromPeerMessage, errorMessageFromEvent, participantsJson)
    // removed — the router now accepts provider events directly.

    // dedupeParticipants and resolveHostPeerId extracted to ParticipantUtils.kt
    // Adapter functions removed — the router now accepts provider events directly.

    private fun upsertParticipant(
        roomState: RoomState?,
        event: PeerEvent,
        localPeerId: String?,
    ): RoomState? {
        val participants = dedupeParticipants(
            (roomState?.participants ?: emptyList()) + Participant(
                cid = event.peerId,
                joinedAt = event.joinedAt,
                displayName = event.displayName,
                peerId = event.appPeerId,
            ),
            localPeerId,
        )
        val host = roomState?.hostCid ?: localPeerId ?: participants.firstOrNull()?.cid ?: return null
        return RoomState(
            hostCid = if (host in participants.map { it.cid }.toSet()) host else participants.first().cid,
            participants = participants,
            maxParticipants = roomState?.maxParticipants,
        )
    }

    private fun removeParticipant(
        roomState: RoomState?,
        peerId: String,
        localPeerId: String?,
    ): RoomState? {
        roomState ?: return null
        val participants = dedupeParticipants(
            roomState.participants.filter { it.cid != peerId },
            localPeerId,
        )
        if (participants.isEmpty()) {
            return null
        }
        val nextHost = when {
            roomState.hostCid != peerId && participants.any { it.cid == roomState.hostCid } -> roomState.hostCid
            !localPeerId.isNullOrBlank() && participants.any { it.cid == localPeerId } -> localPeerId
            else -> participants.first().cid
        }
        return RoomState(
            hostCid = nextHost,
            participants = participants,
            maxParticipants = roomState.maxParticipants,
        )
    }

    private fun defaultIceServers(): List<PeerConnection.IceServer> {
        return listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    }

    private fun applyIceServers(iceServers: List<PeerConnection.IceServer>) {
        val resolvedIceServers = if (iceServers.isEmpty()) defaultIceServers() else iceServers
        webRtcEngine.setIceServers(resolvedIceServers)
        hasInitialIceServers = true
        while (pendingMessages.isNotEmpty()) {
            peerNegotiationEngine.processSignalingPayload(pendingMessages.removeFirst())
        }
        peerNegotiationEngine.onIceServersReady()
    }

    private fun loadInitialIceServers() {
        val fetchGeneration = ++iceFetchGeneration
        providerScope.launch {
            var lastError: Throwable? = null
            for (delayMs in WebRtcResilienceConstants.ICE_FETCH_RETRY_DELAYS_MS) {
                if (delayMs > 0) {
                    delay(delayMs)
                }
                if (fetchGeneration != iceFetchGeneration) {
                    return@launch
                }
                try {
                    val iceServers = signalingProvider.getIceServers()
                    if (fetchGeneration != iceFetchGeneration) {
                        return@launch
                    }
                    applyIceServers(iceServers)
                    return@launch
                } catch (error: Throwable) {
                    lastError = error
                }
            }

            if (fetchGeneration != iceFetchGeneration) {
                return@launch
            }

            val callError = CallError.ServerError(lastError?.message ?: "Failed to fetch ICE servers")
            resetResources()
            updateState(CallState(phase = CallPhase.Error, error = callError))
            delegate?.invoke()?.onSessionEnded(this@SerenadaSession, EndReason.Error(callError))
        }
    }

    // --- Internal: Participants ---

    private fun updateParticipants(roomState: RoomState) {
        val count = roomState.participants.size
        val isHostNow = clientId != null && clientId == roomState.hostCid
        val phase = if (count <= 1) CallPhase.Waiting else CallPhase.InCall
        if (phase != CallPhase.Joining) joinFlowCoordinator.clearJoinTimeout()

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
        val roomParticipants = currentRoomState?.participants
        val orderedRemoteCids = roomParticipants?.map { it.cid }?.filter { it != myCid }
            ?: peerSlots.keys.toList()
        val participantsByCid = roomParticipants?.associateBy { it.cid } ?: emptyMap()
        val remoteParticipants = orderedRemoteCids.mapNotNull { cid ->
            val slot = peerSlots[cid] ?: return@mapNotNull null
            val participant = participantsByCid[cid]
            val peerState = remoteMediaStates[cid]
            RemoteParticipant(
                cid = cid,
                displayName = participant?.displayName,
                peerId = participant?.peerId,
                audioEnabled = peerState?.audioEnabled ?: participant?.audioEnabled ?: true,
                videoEnabled = peerState?.videoEnabled ?: participant?.videoEnabled ?: slot.isRemoteVideoTrackEnabled(),
                connectionState = SerenadaPeerConnectionState.fromRtcState(slot.getConnectionState()),
                signalingStatus = participant?.signalingStatus ?: ParticipantSignalingStatus.ACTIVE,
            )
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

    // --- Internal: State ---

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

    private fun isConnectionDegraded(): Boolean = connectionStatusTracker.isConnectionDegraded()
    private fun markConnectionDegraded() { connectionStatusTracker.update() }
    private fun updateConnectionStatusFromSignals() { connectionStatusTracker.update() }

    // --- Internal: Stats Polling ---

    private fun startRemoteVideoStatePolling() { statsPoller.start() }
    private fun stopRemoteVideoStatePolling() { statsPoller.stop() }

    private fun broadcastLocalMediaState() {
        signalingMessageRouter.broadcastMediaState(
            audioEnabled = _state.value.localAudioEnabled,
            videoEnabled = _state.value.localVideoEnabled,
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
        joinFlowCoordinator.reset()
        peerNegotiationEngine.resetAll()
        iceFetchGeneration += 1
        callAudioSessionController.deactivate()
        releasePerformanceLocks()
        stopRemoteVideoStatePolling()
        signalingProvider.disconnect()
        peerSlots.values.forEach { it.closePeerConnection() }
        peerSlots.clear()
        webRtcEngine.release()
        webRtcStatsExecutor?.shutdown()
        webRtcStatsExecutor = null
        unregisterConnectivityListener()
        clientId = null; hostCid = null; currentRoomState = null; callStartTimeMs = null
        pendingJoinRoom = null; pendingMessages.clear(); remoteMediaStates.clear()
        connectionStatusTracker.cancelTimer()
        userPreferredVideoEnabled = config.defaultVideoEnabled; isVideoPausedByProximity = false
        reconnectToken = null; reconnectRecoveryPending = false; hasInitialIceServers = false
        providerScope.coroutineContext.cancelChildren()
        updateDiagnostics(CallDiagnostics())
    }

    private fun applyLocalVideoPreference() {
        val shouldPause = callAudioSessionController.shouldPauseVideoForProximity(_diagnostics.value.isScreenSharing)
        isVideoPausedByProximity = shouldPause
        val requestedEnabled = userPreferredVideoEnabled && !shouldPause
        val effectiveEnabled = webRtcEngine.toggleVideo(requestedEnabled)
        if (_state.value.localVideoEnabled != effectiveEnabled) {
            updateState(_state.value.copy(localVideoEnabled = effectiveEnabled))
            broadcastLocalMediaState()
        }
    }

    private fun acquirePerformanceLocks() {
        val lock = cpuWakeLock ?: powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }.also { cpuWakeLock = it }
        if (!lock.isHeld) runCatching { lock.acquire(60 * 60 * 1000L) }
    }

    private fun releasePerformanceLocks() {
        cpuWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    private fun registerConnectivityListener() {
        runCatching { connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback) }
    }

    private fun unregisterConnectivityListener() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = if (videoCaptureSupported) {
            REQUIRED_ANDROID_PERMISSIONS
        } else {
            AUDIO_ONLY_REQUIRED_PERMISSIONS
        }
        return permissions.all { permission ->
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
        val AUDIO_ONLY_REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.RECORD_AUDIO,
        )
    }
}
