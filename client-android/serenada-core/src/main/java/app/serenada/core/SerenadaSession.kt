package app.serenada.core

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Bundle
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
import app.serenada.core.call.AudioLevelPoller
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
import org.json.JSONArray
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

    // App lifecycle (foreground / Doze release force-ping — see resilience #8).
    // Activity-counting via the framework `ActivityLifecycleCallbacks` keeps the
    // SDK dependency-free; ProcessLifecycleOwner would require lifecycle-process.
    private var startedActivityCount = 0
    private var lastBackgroundedAtMs: Long? = null
    private val appLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {
            handler.post {
                val wasBackgrounded = startedActivityCount == 0
                startedActivityCount += 1
                if (wasBackgrounded) handleAppForegrounded()
            }
        }
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            handler.post {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) handleAppBackgrounded()
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun handleAppBackgrounded() {
        lastBackgroundedAtMs = clock.nowMs()
    }

    /**
     * Force-ping hook for resilience #8: when Android resumes the app after
     * a long enough background (or a Doze release), the WS that the OS
     * silently killed gets detected inside `foregroundForcePingTimeoutMs`
     * instead of waiting for the regular `pingIntervalMs` cycle.
     */
    private fun handleAppForegrounded() {
        val backgroundedAt = lastBackgroundedAtMs
        lastBackgroundedAtMs = null
        if (backgroundedAt == null) return
        val phase = _state.value.phase
        if (phase != CallPhase.InCall && phase != CallPhase.Joining && phase != CallPhase.Waiting) return
        val backgroundedMs = clock.nowMs() - backgroundedAt
        if (backgroundedMs < FOREGROUND_RESUME_MIN_BACKGROUND_MS) return
        signalingProvider.forceReconnectIfStale(WebRtcResilienceConstants.FOREGROUND_FORCE_PING_TIMEOUT_MS)
    }

    private var clientId: String? = null
    private var hostCid: String? = null
    private var currentRoomState: RoomState? = null
    private val remoteMediaStates = mutableMapOf<String, RemoteMediaState>()
    private var callStartTimeMs: Long? = null
    private var pendingJoinRoom: String? = null
    private val recoveryStorage = RecoveryStorage(appContext)
    private var sessionStartTs: Long? = null
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
            updateState(
                CallState(
                    phase = CallPhase.Error,
                    error = CallError.ConnectionFailed,
                    signalingState = SignalingState.Failed(CallError.ConnectionFailed),
                )
            )
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
            persistRecoveryRecord()
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
            resetResources(clearRecovery = shouldClearRecovery(callError))
            updateState(
                CallState(
                    phase = CallPhase.Error,
                    error = callError,
                    signalingState = SignalingState.Failed(callError),
                )
            )
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
    private val audioLevelPoller = AudioLevelPoller(
        handler = handler,
        statsExecutorProvider = { webRtcStatsExecutor },
        // Run while local media is live, including the Waiting phase before
        // a peer joins. The primer peer connection keeps `media-source` stat
        // available throughout, so sensitivity matches InCall.
        isActivePhase = { _state.value.phase == CallPhase.Waiting || _state.value.phase == CallPhase.InCall },
        getPeerSlots = { peerSlots.values.toList() },
        collectLocalLevel = { onComplete -> webRtcEngine.collectLocalAudioLevel(onComplete) },
        onLevelsUpdated = { localLevel, remoteLevels -> applyAudioLevels(localLevel, remoteLevels) },
    )
    private val pendingMessages = java.util.ArrayDeque<SignalingMessage>()
    private val peerSlots = mutableMapOf<String, PeerConnectionSlotProtocol>()
    private val peerNegotiationEngine: PeerNegotiationEngine
    private val signalingProvider: SignalingProvider
    private var reconnectToken: String? = null
    private var reconnectRecoveryPending = false
    // True between transport reconnect and the first authoritative room_state
    // snapshot; gates ICE restart so it runs against a confirmed peer set.
    private var pendingPostReconnectResync = false
    private var iceRestartCallsFromGate = 0
    private val postReconnectResyncTimeoutRunnable = Runnable {
        flushPostReconnectResync(PostReconnectFlushReason.TIMEOUT)
    }

    private enum class PostReconnectFlushReason { SNAPSHOT, TIMEOUT }

    /** Test-only accessor for the post-reconnect snapshot gate state. */
    internal fun isPostReconnectResyncPending(): Boolean = pendingPostReconnectResync

    /** Test-only counter incremented each time the gate fires an ICE restart. */
    internal fun postReconnectResyncFireCount(): Int = iceRestartCallsFromGate

    // Wall-clock ms when the local transport last dropped while a roomState
    // was present (i.e. mid-call). Cleared on reconnect.
    private var localSuspendedSinceMs: Long? = null

    // After a remote peer transitions to suspended, we start a timer; on
    // expiry we flip `presumedLost=true` for that CID. Timers cancel when
    // the peer goes back to active or is removed from the room.
    private val suspendedPresentationRunnables = mutableMapOf<String, Runnable>()
    private val presumedLostRemoteCids = mutableSetOf<String>()

    // #3 — periodic `media_liveness` emission. Active across the in-call
    // window so the server can defer hard-eviction of suspended peers whose
    // media is still flowing locally. Ticks no-op while transport is
    // disconnected (baseline samples preserved so the next post-reconnect
    // tick can detect flow).
    private val lastInboundBytesByCid = mutableMapOf<String, Long>()
    private var mediaLivenessTickRunnable: Runnable? = null
    private var mediaLivenessEmitInFlight = false
    private var mediaLivenessEmitCount = 0
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

    val host: String?
        get() = resolvedConfig.serverHost

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
                localSuspendedSinceMs = null
                updateDiagnostics(
                    _diagnostics.value.copy(
                        isSignalingConnected = true,
                        activeTransport = info.transport,
                    )
                )
                updateConnectionStatusFromSignals()
                refreshSignalingState()
                if (reconnectRecoveryPending && currentRoomState != null) {
                    reconnectRecoveryPending = false
                    armPostReconnectResync()
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
                if (currentRoomState != null && localSuspendedSinceMs == null) {
                    localSuspendedSinceMs = clock.nowMs()
                }
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
                refreshSignalingState()
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
                flushPostReconnectResync(PostReconnectFlushReason.SNAPSHOT)
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

        override fun onNegotiationDirty(event: NegotiationDirtyEvent) {
            runOnMain {
                logger?.log(SerenadaLogLevel.DEBUG, "Session", "RX negotiation_dirty with=${event.withCid}")
                peerNegotiationEngine.scheduleIceRestart(event.withCid, "negotiation-dirty", 0)
            }
        }

        override fun onRelayFailed(event: RelayFailedEvent) {
            runOnMain {
                // Server has the dirty-pair record; once the suspended target
                // reattaches we'll get `negotiation_dirty` and renegotiate then.
                // For now, just surface in logs so suppressed offers/ICE are visible.
                logger?.log(
                    SerenadaLogLevel.DEBUG,
                    "Session",
                    "RX relay_failed reason=${event.reason} of=${event.of ?: "n/a"} targets=${event.targets.joinToString(",")}",
                )
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
        val requestedEnabled = !_state.value.localVideoEnabled
        if (requestedEnabled && !hasCameraPermission() && !_diagnostics.value.isScreenSharing) {
            requestPermissions(listOf(MediaCapability.CAMERA))
            return
        }
        userPreferredVideoEnabled = requestedEnabled
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
        val wasVideoPreferred = userPreferredVideoEnabled
        userPreferredVideoEnabled = true
        if (!webRtcEngine.startScreenShare(intent)) {
            userPreferredVideoEnabled = wasVideoPreferred
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

    /**
     * Attach a [SurfaceViewRenderer][org.webrtc.SurfaceViewRenderer] for remote video.
     *
     * In a 1:1 call, the host app calls this without a CID and we pick a peer
     * for them. Prefer an ACTIVE (non-suspended) participant: picking a
     * suspended one attaches the renderer to a frozen peer connection — the
     * last frame stays on screen as a "ghost" — while a co-existing fresh
     * CID for the same physical device that joined without a reconnect token
     * gets no renderer at all. Falls back to any non-self participant, then
     * to any peer slot, before giving up.
     */
    fun attachRemoteRenderer(
        renderer: org.webrtc.SurfaceViewRenderer,
        rendererEvents: org.webrtc.RendererCommon.RendererEvents? = null,
    ) {
        assertMainThread()
        val participants = currentRoomState?.participants
        val remoteCid = participants
            ?.firstOrNull { it.cid != clientId && it.signalingStatus != ParticipantSignalingStatus.SUSPENDED }
            ?.cid
            ?: participants
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
        // Same active-first preference as attachRemoteRenderer above —
        // attaching a sink to a suspended peer pins it to a frozen track.
        val participants = currentRoomState?.participants
        val remoteCid = participants
            ?.firstOrNull { it.cid != clientId && it.signalingStatus != ParticipantSignalingStatus.SUSPENDED }
            ?.cid
            ?: participants
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
        webRtcEngine.startLocalMedia(startVideoCapture = userPreferredVideoEnabled)

        if (!config.defaultAudioEnabled) webRtcEngine.toggleAudio(false)
        applyLocalVideoPreference()

        startRemoteVideoStatePolling()
        joinFlowCoordinator.ensureSignalingConnection()
    }

    internal fun startWithPermissionCheck() {
        assertMainThread()
        awaitingPermissions = true
        val permissions = requiredPermissionsForJoin()
        updateState(
            _state.value.copy(
                phase = CallPhase.AwaitingPermissions,
                roomId = roomId,
                requiredPermissions = permissions,
            )
        )
        requestPermissions(permissions)
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
            updateState(
                CallState(
                    phase = CallPhase.Error,
                    error = callError,
                    signalingState = SignalingState.Failed(callError),
                )
            )
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
        // Start media-liveness emission only once we have remote peers — there's
        // nothing to report when alone in the room.
        if (phase == CallPhase.InCall) startMediaLivenessTimer()
    }

    private fun refreshRemoteParticipants() {
        val myCid = clientId
        val roomParticipants = currentRoomState?.participants
        reconcileRemoteSuspensionTimers(roomParticipants?.filter { it.cid != myCid } ?: emptyList())
        val orderedRemoteCids = roomParticipants?.map { it.cid }?.filter { it != myCid }
            ?: peerSlots.keys.toList()
        val participantsByCid = roomParticipants?.associateBy { it.cid } ?: emptyMap()
        val previousLevels = _state.value.remoteParticipants.associate { it.cid to it.audioLevel }
        val remoteParticipants = orderedRemoteCids.mapNotNull { cid ->
            val slot = peerSlots[cid] ?: return@mapNotNull null
            val participant = participantsByCid[cid]
            val peerState = remoteMediaStates[cid]
            val signalingStatus = participant?.signalingStatus ?: ParticipantSignalingStatus.ACTIVE
            val audioEnabled = peerState?.audioEnabled ?: participant?.audioEnabled ?: true
            RemoteParticipant(
                cid = cid,
                displayName = participant?.displayName,
                peerId = participant?.peerId,
                audioEnabled = audioEnabled,
                videoEnabled = peerState?.videoEnabled ?: participant?.videoEnabled ?: slot.isRemoteVideoTrackEnabled(),
                connectionState = SerenadaPeerConnectionState.fromRtcState(slot.getConnectionState()),
                signalingStatus = signalingStatus,
                presumedLost = signalingStatus == ParticipantSignalingStatus.SUSPENDED && cid in presumedLostRemoteCids,
                audioLevel = if (audioEnabled) previousLevels[cid] ?: 0f else 0f,
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

    private fun startRemoteVideoStatePolling() {
        statsPoller.start()
        audioLevelPoller.start()
    }
    private fun stopRemoteVideoStatePolling() {
        statsPoller.stop()
        audioLevelPoller.stop()
    }

    private fun applyAudioLevels(localLevel: Float, remoteLevels: Map<String, Float>) {
        val current = _state.value
        val nextLocal = if (current.localAudioEnabled) localLevel else 0f
        var nextRemote: List<RemoteParticipant>? = null
        if (current.remoteParticipants.isNotEmpty()) {
            var remoteChanged = false
            val updated = current.remoteParticipants.map { participant ->
                val raw = remoteLevels[participant.cid] ?: 0f
                val target = if (participant.audioEnabled) raw else 0f
                if (participant.audioLevel == target) {
                    participant
                } else {
                    remoteChanged = true
                    participant.copy(audioLevel = target)
                }
            }
            if (remoteChanged) nextRemote = updated
        }
        if (nextLocal == current.localAudioLevel && nextRemote == null) return
        updateState(
            current.copy(
                localAudioLevel = nextLocal,
                remoteParticipants = nextRemote ?: current.remoteParticipants,
            )
        )
    }

    private fun broadcastLocalMediaState() {
        signalingMessageRouter.broadcastMediaState(
            audioEnabled = _state.value.localAudioEnabled,
            videoEnabled = _state.value.localVideoEnabled,
        )
    }

    // --- Internal: Suspended-peer presentation ---

    /**
     * Walks the latest authoritative remote participant list and starts/cancels
     * per-CID suspended-presentation timers. Cancels cleanly when peers go back
     * to active or are removed; flips `presumedLost=true` on timer expiry.
     *
     * "Already presumed lost" is a sticky state: once the timer has fired, we
     * don't reschedule a new one if the peer remains suspended across
     * subsequent room_state updates. The flag clears the moment the peer
     * transitions back to active or leaves the room.
     */
    private fun reconcileRemoteSuspensionTimers(remoteParticipants: List<Participant>) {
        val seen = remoteParticipants.map { it.cid }.toSet()
        for (participant in remoteParticipants) {
            val isSuspended = participant.signalingStatus == ParticipantSignalingStatus.SUSPENDED
            val hasTimer = participant.cid in suspendedPresentationRunnables
            val isPresumedLost = participant.cid in presumedLostRemoteCids
            if (isSuspended) {
                if (!hasTimer && !isPresumedLost) startRemoteSuspensionTimer(participant.cid)
            } else {
                clearRemoteSuspensionTracking(participant.cid)
            }
        }
        // Drop tracking for CIDs that left the room entirely.
        val tracked = suspendedPresentationRunnables.keys + presumedLostRemoteCids
        for (cid in tracked.toList()) {
            if (cid !in seen) clearRemoteSuspensionTracking(cid)
        }
    }

    private fun startRemoteSuspensionTimer(cid: String) {
        val runnable = Runnable {
            suspendedPresentationRunnables.remove(cid)
            presumedLostRemoteCids.add(cid)
            logger?.log(
                SerenadaLogLevel.INFO,
                "Session",
                "Remote $cid presumed lost after ${WebRtcResilienceConstants.PEER_SUSPENDED_UI_TIMEOUT_MS}ms suspended",
            )
            refreshRemoteParticipants()
        }
        suspendedPresentationRunnables[cid] = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.PEER_SUSPENDED_UI_TIMEOUT_MS)
    }

    /**
     * Clear all per-CID suspension state (timer + presumed-lost flag). Called
     * when a peer transitions back to active, leaves the room, or the session
     * is reset.
     */
    private fun clearRemoteSuspensionTracking(cid: String) {
        suspendedPresentationRunnables.remove(cid)?.let { handler.removeCallbacks(it) }
        presumedLostRemoteCids.remove(cid)
    }

    private fun clearAllRemoteSuspensionTracking() {
        for (runnable in suspendedPresentationRunnables.values) handler.removeCallbacks(runnable)
        suspendedPresentationRunnables.clear()
        presumedLostRemoteCids.clear()
    }

    /** Test-only count of remote CIDs currently flagged as `presumedLost`. */
    internal fun presumedLostRemoteCount(): Int = presumedLostRemoteCids.size

    /** Test-only accessor for the local signaling-state surface. */
    internal fun currentSignalingState(): SignalingState = _state.value.signalingState

    /** Test-only counter incremented on each `media_liveness` broadcast. */
    internal fun mediaLivenessBroadcastCount(): Int = mediaLivenessEmitCount

    // --- Internal: Media-liveness emission (#3) ---

    /**
     * Periodic `media_liveness{cids}` broadcast for #3. Started on a
     * successful join; runs across reconnects (ticks no-op while
     * disconnected but baseline samples persist so the next post-reconnect
     * tick can detect flow). Stopped on session reset/destroy.
     */
    private fun startMediaLivenessTimer() {
        if (mediaLivenessTickRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                emitMediaLiveness()
                handler.postDelayed(this, WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS)
            }
        }
        mediaLivenessTickRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS)
    }

    private fun stopMediaLivenessTimer() {
        mediaLivenessTickRunnable?.let { handler.removeCallbacks(it) }
        mediaLivenessTickRunnable = null
    }

    private fun emitMediaLiveness() {
        if (_state.value.phase == CallPhase.Idle || _state.value.phase == CallPhase.Ending) return
        if (mediaLivenessEmitInFlight) return
        if (!_diagnostics.value.isSignalingConnected) return
        if (currentRoomState == null) return
        val slots = peerSlots.toMap()
        if (slots.isEmpty()) return

        mediaLivenessEmitInFlight = true
        val newSamples = mutableMapOf<String, Long>()
        var remaining = slots.size
        for ((cid, slot) in slots) {
            slot.collectInboundBytes { bytes ->
                handler.post {
                    newSamples[cid] = bytes
                    remaining -= 1
                    if (remaining == 0) finalizeMediaLivenessEmit(newSamples)
                }
            }
        }
    }

    private fun finalizeMediaLivenessEmit(newSamples: Map<String, Long>) {
        mediaLivenessEmitInFlight = false
        val flowing = mutableListOf<String>()
        for ((cid, bytes) in newSamples) {
            val previous = lastInboundBytesByCid[cid]
            if (previous != null && bytes > previous) flowing.add(cid)
            lastInboundBytesByCid[cid] = bytes
        }
        // Drop tracking for peers that left the room.
        val activeCids = peerSlots.keys
        val stale = lastInboundBytesByCid.keys.filterNot { activeCids.contains(it) }
        for (cid in stale) lastInboundBytesByCid.remove(cid)

        if (flowing.isEmpty()) return
        if (!_diagnostics.value.isSignalingConnected) return
        val payload = JSONObject().apply { put("cids", JSONArray(flowing)) }
        signalingProvider.broadcast("media_liveness", payload)
        mediaLivenessEmitCount += 1
    }

    // --- Internal: Local signaling-state computation ---

    private fun computeSignalingState(): SignalingState {
        val error = _state.value.error
        if (error != null) return SignalingState.Failed(error)
        if (_diagnostics.value.isSignalingConnected) return SignalingState.Connected
        val suspendedSince = localSuspendedSinceMs
        if (suspendedSince != null) {
            return SignalingState.Suspended(
                suspendedSinceMs = suspendedSince,
                estimatedHardEvictionAtMs = suspendedSince + WebRtcResilienceConstants.SUSPEND_HARD_EVICTION_TIMEOUT_MS,
            )
        }
        return SignalingState.Reconnecting(attempt = joinFlowCoordinator.reconnectAttempts)
    }

    private fun refreshSignalingState() {
        val next = computeSignalingState()
        if (_state.value.signalingState != next) updateState(_state.value.copy(signalingState = next))
    }

    // --- Internal: Post-reconnect snapshot gate ---

    private fun armPostReconnectResync() {
        pendingPostReconnectResync = true
        handler.removeCallbacks(postReconnectResyncTimeoutRunnable)
        handler.postDelayed(postReconnectResyncTimeoutRunnable, WebRtcResilienceConstants.EPOCH_RESYNC_TIMEOUT_MS)
    }

    private fun flushPostReconnectResync(reason: PostReconnectFlushReason) {
        if (!pendingPostReconnectResync) return
        pendingPostReconnectResync = false
        handler.removeCallbacks(postReconnectResyncTimeoutRunnable)
        if (reason == PostReconnectFlushReason.TIMEOUT) {
            logger?.log(
                SerenadaLogLevel.WARNING,
                "Session",
                "Post-reconnect snapshot timeout after ${WebRtcResilienceConstants.EPOCH_RESYNC_TIMEOUT_MS}ms; firing ICE restart against last-known peer map",
            )
        }
        iceRestartCallsFromGate += 1
        peerNegotiationEngine.scheduleIceRestart("signaling-reconnect", 0)
    }

    private fun cancelPostReconnectResync() {
        pendingPostReconnectResync = false
        handler.removeCallbacks(postReconnectResyncTimeoutRunnable)
    }

    // --- Internal: Cleanup ---

    private fun cleanupCall(reason: EndReason) {
        updateState(_state.value.copy(phase = CallPhase.Ending))
        if (_diagnostics.value.isScreenSharing) webRtcEngine.stopScreenShare()
        resetResources(clearRecovery = true)
        updateState(CallState(phase = CallPhase.Idle))
        delegate?.invoke()?.onSessionEnded(this, reason)
    }

    private fun resetResources(clearRecovery: Boolean = false) {
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
        cancelPostReconnectResync()
        clearAllRemoteSuspensionTracking()
        stopMediaLivenessTimer()
        lastInboundBytesByCid.clear()
        mediaLivenessEmitInFlight = false
        localSuspendedSinceMs = null
        sessionStartTs = null
        if (clearRecovery) recoveryStorage.clear()
        providerScope.coroutineContext.cancelChildren()
        updateDiagnostics(CallDiagnostics())
    }

    private fun shouldClearRecovery(callError: CallError): Boolean {
        return when (callError) {
            CallError.RoomEnded,
            CallError.SessionExpired -> true
            else -> false
        }
    }

    /**
     * Snapshots the in-memory reconnect state into the cross-launch
     * recovery store so a relaunched process can offer a "Rejoin call?"
     * prompt. No-op until the join handshake has produced a CID + token.
     */
    private fun persistRecoveryRecord() {
        val cid = clientId ?: return
        val token = reconnectToken ?: return
        if (sessionStartTs == null) sessionStartTs = clock.nowMs()
        val ttlMs = RecoveryTokenTTLMs
        val record = RecoveryRecord(
            roomId = roomId,
            cid = cid,
            reconnectToken = token,
            lastEpoch = currentRoomState?.epoch,
            sessionStartTs = sessionStartTs ?: clock.nowMs(),
            expiresAtMs = clock.nowMs() + ttlMs,
        )
        recoveryStorage.save(record)
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
        registerAppLifecycleListener()
    }

    private fun unregisterConnectivityListener() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        unregisterAppLifecycleListener()
    }

    private fun registerAppLifecycleListener() {
        val app = appContext as? Application ?: return
        startedActivityCount = if (isAppProcessForeground()) 1 else 0
        lastBackgroundedAtMs = null
        runCatching { app.registerActivityLifecycleCallbacks(appLifecycleCallbacks) }
    }

    private fun unregisterAppLifecycleListener() {
        val app = appContext as? Application ?: return
        runCatching { app.unregisterActivityLifecycleCallbacks(appLifecycleCallbacks) }
        startedActivityCount = 0
        lastBackgroundedAtMs = null
    }

    private fun isAppProcessForeground(): Boolean {
        val processInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processInfo)
        return processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun hasRequiredPermissions(): Boolean {
        return androidPermissionsFor(requiredPermissionsForJoin()).all { permission ->
            appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasCameraPermission(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissionsForJoin(): List<MediaCapability> {
        val permissions = mutableListOf(MediaCapability.MICROPHONE)
        if (videoCaptureSupported && userPreferredVideoEnabled) {
            permissions.add(MediaCapability.CAMERA)
        }
        return permissions
    }

    private fun androidPermissionsFor(capabilities: List<MediaCapability>): List<String> =
        capabilities.map { capability ->
            when (capability) {
                MediaCapability.CAMERA -> android.Manifest.permission.CAMERA
                MediaCapability.MICROPHONE -> android.Manifest.permission.RECORD_AUDIO
            }
        }

    private fun requestPermissions(permissions: List<MediaCapability>) {
        handler.post {
            onPermissionsRequired?.invoke(permissions)
                ?: delegate?.invoke()?.onPermissionsRequired(this, permissions)
        }
    }

    private companion object {
        const val TAG = "SerenadaSession"
        const val CPU_WAKE_LOCK_TAG = "serenada:call-cpu"
        // Matches the server's reconnectTokenTTL (= suspendHardEvictionTimeout).
        // The recovery record stops offering rejoin past this window because
        // the persisted token would no longer be honored anyway.
        const val RecoveryTokenTTLMs = 10L * 60L * 1000L
        // Background duration that triggers a foreground force-ping. Anything
        // shorter is short enough that pings would have noticed the failure on
        // their own; longer is the OS window where Doze / process freeze may
        // have killed the WS.
        const val FOREGROUND_RESUME_MIN_BACKGROUND_MS = 5_000L
    }
}
