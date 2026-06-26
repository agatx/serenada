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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.serenada.core.call.AudioDevice
import app.serenada.core.call.AudioIntent
import app.serenada.core.call.AudioCoordinatorEvent
import app.serenada.core.call.SerenadaAudioCoordinator
import app.serenada.core.call.AudioDeviceKind
import app.serenada.core.call.dedupeParticipants
import app.serenada.core.call.resolveHostPeerId
import app.serenada.core.call.CallQualityTracker
import app.serenada.core.call.ReconnectReason
import app.serenada.core.call.ConnectionStatusTracker
import app.serenada.core.call.FrameSnapshotCapture
import app.serenada.core.call.InboundLivenessSample
import app.serenada.core.call.InboundRoleBytes
import app.serenada.core.call.JoinFlowCoordinator
import app.serenada.core.call.LiveSessionClock
import app.serenada.core.call.PeerNegotiationEngine
import app.serenada.core.call.SessionClock
import app.serenada.core.call.RemoteMediaState
import app.serenada.core.call.resolveCameraModes
import app.serenada.core.call.SignalingMessageRouter
import app.serenada.core.call.AudioLevelPoller
import app.serenada.core.call.StatsPoller
import app.serenada.core.call.DefaultAudioCoordinator
import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.ContentTypeWire
import app.serenada.core.call.MediaActivationState
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.LocalFrameSnapshotCapture
import app.serenada.core.call.ParticipantCapabilities
import app.serenada.core.call.ParticipantContent
import app.serenada.core.call.ParticipantMediaPolicy
import app.serenada.core.call.PeerConnectionSlotProtocol
import app.serenada.core.call.RemoteParticipant
import app.serenada.core.call.RoleLiveness
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Control the call via [leave], [end], [toggleAudio], [toggleVideo], etc. Call [close] once
 * the host is done with the session object.
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
    /**
     * Initial foreground-media role (multi-call session, Phase 2). The public
     * [SerenadaCore.join] always passes [CallMediaRole.FOREGROUND]; the (Phase 3)
     * registry passes [CallMediaRole.HELD] to create a held call that owns no
     * capture and holds no lease. A HELD initial role does NOT activate the audio
     * coordinator/controller and does NOT start capture — it only creates stable
     * senders (contract §5) and connects signaling. Defaults to FOREGROUND so
     * existing construction is unchanged.
     */
    private val initialMediaRole: CallMediaRole = CallMediaRole.FOREGROUND,
    /**
     * When true, acquire the process-wide foreground lease (mode
     * [ForegroundArbiterMode.DIRECT]) before activating media — the route for a
     * public single-call [SerenadaCore.join]. A second concurrent direct join
     * while one is live throws [ForegroundLeaseUnavailable]. Held-initial joins
     * never acquire the lease; the (Phase 3) registry manages the lease itself and
     * leaves this false.
     */
    private val acquireForegroundLease: Boolean = false,
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
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val _state = MutableStateFlow(CallState())
    /** Primary observable call state. Collect this flow for UI updates. */
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(CallDiagnostics())
    /** Real-time connection diagnostics (stats, transport state, ICE state). */
    val diagnostics: StateFlow<CallDiagnostics> = _diagnostics.asStateFlow()

    /**
     * Aggregate call-quality summary. Reflects the live
     * tracker while in-call and the finalized snapshot after the call ends;
     * stays readable after teardown. Null before sampling begins (first
     * InCall).
     */
    val qualitySummary: CallQualitySummary?
        get() = _qualitySummary ?: qualityTracker.summarize()

    private val _availableAudioDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    /** Audio routes currently published by the active coordinator. */
    val availableAudioDevices: StateFlow<List<AudioDevice>> = _availableAudioDevices.asStateFlow()

    private val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)
    /** Current selected or active output route, or null when no route is available yet. */
    val currentAudioDevice: StateFlow<AudioDevice?> = _currentAudioDevice.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    /** Whether the microphone is effectively muted by user action, external audio, or missing input. */
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isMicMutedByExternalAudio = MutableStateFlow(false)
    /** Whether the microphone is muted specifically because external audio, such as push-to-talk, is active. */
    val isMicMutedByExternalAudio: StateFlow<Boolean> = _isMicMutedByExternalAudio.asStateFlow()

    // Latched true once the call's media has connected at least once. A "network-online"
    // ICE restart is a recovery mechanism for an established call; firing it during initial
    // setup is never useful and is actively harmful: registerNetworkCallback replays
    // onAvailable for every already-connected network right after registration, and that
    // replay would otherwise schedule an ICE restart mid-first-offer, forcing a redundant
    // renegotiation the moment the first answer lands (observed as a "pending-retry").
    private var hasEverConnectedPeer = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (_state.value.phase == CallPhase.InCall) {
                    if (isConnectionDegraded()) markConnectionDegraded()
                    if (hasEverConnectedPeer) {
                        peerNegotiationEngine.scheduleIceRestart("network-online", 0)
                    }
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
    // Latest accepted content (screen share) presentation state per remote cid,
    // sourced from `content_state` peer messages. A stale, out-of-order update
    // (revision <= tracked) is discarded; see [onContentStateReceived].
    private val remoteContentStates = mutableMapOf<String, ParticipantContent>()
    // Latest tracked revision per remote cid. Cleared when the peer leaves so a
    // rejoin that restarts numbering is accepted by identity.
    private val remoteContentRevisions = mutableMapOf<String, Long>()
    // Outgoing per-session content revision for the local participant. Bumped on
    // every content_state we broadcast so receivers can order quick toggles.
    private var localContentRevision: Long = 0L
    // Static capabilities advertised by each remote participant at join, sourced
    // from joined/room_state. Absent entries default to no capability.
    private val remoteCapabilities = mutableMapOf<String, ParticipantCapabilities>()
    // Per-session media policy advertised by each remote participant at join.
    private val remoteMediaPolicies = mutableMapOf<String, ParticipantMediaPolicy>()
    private var callStartTimeMs: Long? = null
    private var pendingJoinRoom: String? = null
    private val recoveryStorage = RecoveryStorage(appContext)
    private var sessionStartTs: Long? = null
    private var userMuted = false
    private var externalAudioMuted = false
    private var playbackDuckingActive = false
    private var routeInputAvailable = true
    private var sessionActivated = false
    private val audioCoordinatorMutex = Mutex()
    private val audioCoordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioCoordinatorCollectorJobs = mutableListOf<Job>()
    private var audioCoordinatorDeactivationJob: Job? = null
    private var closed = false
    // Aggregate call-quality tracker, driven by explicit
    // inputs. `_qualitySummary` is snapshotted at finalize and survives
    // teardown so hosts can read it after the session stops.
    private val qualityTracker = CallQualityTracker { event ->
        // Guard the host callback (Kotlin callbacks can throw unchecked):
        // a throwing `onConnectionEvent` must not unwind and skip the
        // terminal `onSessionStateChanged` / `onSessionEnded` callbacks that
        // run after the emit on terminal paths. Mirrors web's
        // `dispatchConnectionEvent` try/catch (SerenadaSession.ts).
        try {
            delegate?.invoke()?.onConnectionEvent(this, event)
        } catch (t: Throwable) {
            logger?.log(
                SerenadaLogLevel.ERROR,
                "Session",
                "onConnectionEvent listener failed for ${event::class.simpleName}: ${t.message}",
            )
        }
    }
    private var _qualitySummary: CallQualitySummary? = null
    private var lastTrackedConnectionStatus: ConnectionStatus = ConnectionStatus.Connected
    private val connectionStatusTracker = ConnectionStatusTracker(
        handler = handler,
        getPhase = { _state.value.phase },
        getDiagnostics = { _diagnostics.value },
        getCurrentStatus = { _state.value.connectionStatus },
        setConnectionStatus = { status ->
            if (_state.value.connectionStatus != status) updateState(_state.value.copy(connectionStatus = status))
            feedQualityConnectionStatus(status)
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
                    independentContentVideo = config.enableIndependentContentVideo,
                    videoMediaEnabled = config.videoMediaEnabled,
                ),
            )
        },
        onJoinTimeout = {
            if (qualityTracker.hasStartedSampling()) {
                qualityTracker.reportReconnectFailed(ConnectionEvent.ReconnectFailedReason.TIMEOUT)
            }
            finalizeQuality()
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
        onJoined = { cid, _, roomState, _, _, newReconnectToken, newReconnectTokenTTL ->
            clientId = cid
            updateState(_state.value.copy(localCid = clientId))
            newReconnectToken?.let { reconnectToken = it }
            newReconnectTokenTTL?.let { reconnectTokenTTLMs = it }
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
        onError = { callError, serverCode ->
            joinFlowCoordinator.clearJoinTimeout()
            maybeReportReconnectFailed(serverCode)
            finalizeQuality()
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
        onContentStateReceived = { fromCid, active, contentType, revision ->
            handleRemoteContentState(fromCid, active, contentType, revision)
        },
        onMediaStateReceived = { fromCid, audioEnabled, videoEnabled, held ->
            val existing = remoteMediaStates[fromCid]
            remoteMediaStates[fromCid] = RemoteMediaState(
                audioEnabled = audioEnabled ?: existing?.audioEnabled,
                videoEnabled = videoEnabled ?: existing?.videoEnabled,
                held = held ?: existing?.held,
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
            // Feed the quality tracker. Sampling only begins
            // once the tracker has seen the first InCall transition, so
            // pre-call Waiting/Joining samples are ignored.
            qualityTracker.onStatsSample(merged, this.clock.monotonicMs())
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
    private var reconnectTokenTTLMs: Long? = null
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
    // media is still flowing locally. The broadcast no-ops while transport is
    // disconnected, preserving media-liveness baselines so the next
    // post-reconnect tick can detect flow.
    private val lastInboundBytesByCid = mutableMapOf<String, Long>()
    // Per-remote-cid, per-role tally of cumulative inbound VIDEO bytesReceived,
    // split by the BOUND transceiver role (camera vs content) so a stalled
    // CONTENT stream is distinguishable from a healthy camera on the same peer.
    // Sampled on the same media-liveness tick; a role is "receiving" when its
    // sample advances over the previous one. The derived booleans are cached in
    // [roleLivenessByCid] for synchronous reads while assembling participant
    // state. Separate from [lastInboundBytesByCid] (the all-RTP audio-inclusive
    // sum for the server `media_liveness` eviction-deferral signal, unchanged).
    private val lastInboundRoleBytesByCid = mutableMapOf<String, InboundRoleBytes>()
    private val roleLivenessByCid = mutableMapOf<String, RoleLiveness>()
    private var mediaLivenessTickRunnable: Runnable? = null
    private var mediaLivenessEmitInFlight = false
    private var mediaLivenessEmitCount = 0
    private var outboundMediaWatchdogRunnable: Runnable? = null
    private var iceFetchGeneration = 0
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private val videoMediaEnabled: Boolean = config.videoMediaEnabled
    private val availableCameraModes: List<LocalCameraMode> = resolveAvailableCameraModes()
    private val videoCaptureSupported: Boolean = videoMediaEnabled && availableCameraModes.isNotEmpty()
    private var userPreferredVideoEnabled = videoCaptureSupported && config.defaultVideoEnabled
    private var isVideoPausedByProximity = false

    // --- Multi-call session primitives (Phase 1) ---
    // mediaRole + mediaActivationState are two of the three orthogonal axes
    // (membership phase is the existing CallPhase). A single-call session is
    // FOREGROUND/ACTIVE for its whole life; hold/resume toggle these. desired*
    // are the single source of user intent and survive hold; actual* reflect
    // what peers observe now (a held call always publishes false/false).
    // Seed from the requested initial role (Phase 2). A FOREGROUND single-call
    // session is FOREGROUND/ACTIVE for its whole life; a HELD-initial session
    // (registry, Phase 3) starts HELD/INACTIVE and owns no capture or lease.
    private var mediaRole: CallMediaRole = initialMediaRole
    private var mediaActivationState: MediaActivationState =
        if (initialMediaRole == CallMediaRole.HELD) MediaActivationState.INACTIVE else MediaActivationState.ACTIVE
    private var desiredAudioEnabled: Boolean = config.defaultAudioEnabled
    private var desiredVideoMode: LocalCameraMode? =
        if (videoCaptureSupported && config.defaultVideoEnabled) {
            availableCameraModes.firstOrNull()
        } else {
            null
        }
    private var actualAudioPublished: Boolean = false
    private var actualVideoPublished: Boolean = false

    // Monotonic generation bumped for every hold/resume role transition (FIX N2).
    // A resume captures the generation at its start; if a concurrent hold runs
    // while resume awaits the (async) audio coordinator, the hold bumps this
    // counter so the resume re-check detects it was superseded and rolls back to
    // held instead of committing foreground (stale-activation race fence).
    // Phase 2 feeds this from the arbiter operation generation via the token-gated
    // activateForeground; the un-gated Phase-1 hold/resume primitives keep
    // self-incrementing it for the local suspend/resume race.
    private var mediaOpGeneration: Long = 0

    /**
     * The arbiter-minted lease owner token while this call holds (or is
     * activating) the foreground media lease, else null. It is the SECOND fence
     * (alongside [mediaOpGeneration]) for a late activation callback: an
     * [activateForeground] result is honored only if BOTH the current generation
     * AND this token still match (contract §3 fencing). For a single-call
     * [SerenadaCore.join] (direct mode) the session acquires + releases its own
     * lease; for a held-initial join it stays null until the (Phase 3) registry
     * activates it via [activateForeground].
     */
    private var foregroundOwnerToken: ForegroundOwnerToken? = null

    /**
     * The lease token this session acquired for ITS OWN account via a direct
     * single-call [SerenadaCore.join] (mode DIRECT), or null. DISTINCT from
     * [foregroundOwnerToken] (the registry-issued fence token): this is the only
     * lease the session self-releases, and it does so in [resetResources] /
     * [close]. A registry-activated session never sets this, so teardown never
     * frees a registry-owned lease (parity with iOS `directLeaseToken`). Without
     * the split, teardown of a registry-activated session would call
     * [ForegroundMediaArbiter.releaseLease] on a lease the registry owns.
     */
    private var directLeaseToken: ForegroundOwnerToken? = null

    // True only during the media-apply phase of a resume (FIX M3). While set,
    // broadcastLocalMediaState() is suppressed so the intermediate updates from
    // updateEffectiveMicState()/applyLocalVideoPreference() do not emit redundant
    // participant_media_state messages. Resume commits FOREGROUND first, applies
    // media silently, then emits exactly one final held=false.
    private var suppressMediaStateBroadcast: Boolean = false

    /**
     * True while this session owns no foreground media (Core Invariant 2: a held
     * call owns NO capture). User media toggles consult this to update `desired*`
     * intent ONLY — no capture, no camera restart, no broadcast — while held.
     */
    private val isHeld: Boolean
        get() = mediaRole == CallMediaRole.HELD

    /** Test-only accessor: current media role. */
    internal fun mediaRoleForTest(): CallMediaRole = mediaRole

    /** Test-only accessor: current media activation state. */
    internal fun mediaActivationStateForTest(): MediaActivationState = mediaActivationState

    /** Test-only accessor: desired audio intent (survives hold). */
    internal fun desiredAudioEnabledForTest(): Boolean = desiredAudioEnabled

    /** Test-only accessor: desired video mode (null = off). */
    internal fun desiredVideoModeForTest(): LocalCameraMode? = desiredVideoMode

    /** Test-only accessor: actual published audio (false while held). */
    internal fun actualAudioPublishedForTest(): Boolean = actualAudioPublished

    /** Test-only accessor: actual published video (false while held). */
    internal fun actualVideoPublishedForTest(): Boolean = actualVideoPublished

    /** Test-only accessor: current foreground lease owner token (null = none). */
    internal fun foregroundOwnerTokenForTest(): ForegroundOwnerToken? = foregroundOwnerToken

    /**
     * Test-only: overwrite the lease owner token mid-flight to model a
     * superseding op (e.g. a switch rollback that re-activates the old call under
     * a fresh token while a stuck activation callback from the failed new call is
     * still in flight). Lets a test exercise the token fence independently of the
     * generation fence (contract §3 double-fence).
     */
    internal fun setForegroundOwnerTokenForTest(token: ForegroundOwnerToken?) {
        foregroundOwnerToken = token
    }

    /**
     * Test-only: invoke the video sink the audio-environment callbacks (proximity
     * / route change) reach. Lets a test assert the held SINK guard refuses to
     * restart camera capture via the indirect path (FIX A-2), independent of how
     * the real coordinator fires the callback.
     */
    internal fun applyLocalVideoPreferenceForTest() = applyLocalVideoPreference()

    /**
     * Test-only: invoke the mic sink the audio-environment callbacks reach. Lets a
     * test assert a held call cannot re-enable mic capture via an audio-environment
     * callback (FIX A-2 audit).
     */
    internal fun updateEffectiveMicStateForTest() = updateEffectiveMicState()
    private val isMediaEngineInjected = mediaEngine != null
    // Owned at the session level so that engine recreation (or release on call end) does not
    // invalidate the EglBase.Context handed to Compose AndroidView factories. Releasing the
    // EglBase before the call UI unmounts caused crashes in WebRTC's EglBase14Impl with
    // "Invalid sharedContext" when a new PiP renderer was created with a stale handle.
    private val eglBase: EglBase? = if (isMediaEngineInjected) null else EglBase.create()
    private var webRtcEngine: SessionMediaEngine = mediaEngine ?: buildWebRtcEngine()
    private var awaitingPermissions = false
    private var hasInitialIceServers = false
    private var localMediaReadyForNegotiation = false

    private fun resolveAvailableCameraModes(): List<LocalCameraMode> {
        if (!config.videoMediaEnabled) return emptyList()
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
            deferInitialAnswer = { config.deferInitialAnswer },
            getParticipantCount = { _state.value.participantCount },
            getCurrentRoomState = { currentRoomState },
            isSignalingConnected = { _diagnostics.value.isSignalingConnected },
            hasIceServers = { webRtcEngine.hasIceServers() },
            isLocalMediaReady = { localMediaReadyForNegotiation },
            getSlot = { cid: String -> peerSlots[cid] },
            getAllSlots = { peerSlots.toMap() },
            setSlot = { cid: String, slot: PeerConnectionSlotProtocol ->
                peerSlots[cid] = slot
                if (playbackDuckingActive) {
                    slot.duckPlayback(true)
                }
            },
            removeSlotEntry = { cid: String -> peerSlots.remove(cid) },
            createSlotViaEngine = {
                remoteCid: String,
                onLocalIce: (String, org.webrtc.IceCandidate) -> Unit,
                onRemoteVideo: (String, org.webrtc.VideoTrack?) -> Unit,
                onConnState: (String, org.webrtc.PeerConnection.PeerConnectionState) -> Unit,
                onIceConnState: (String, org.webrtc.PeerConnection.IceConnectionState) -> Unit,
                onSigState: (String, org.webrtc.PeerConnection.SignalingState) -> Unit,
                onRenegotiation: (String) -> Unit,
                supportsIndependentContentVideo: Boolean,
                isOfferOwner: () -> Boolean ->
                webRtcEngine.createSlot(
                    remoteCid = remoteCid,
                    onLocalIceCandidate = onLocalIce,
                    onRemoteVideoTrack = onRemoteVideo,
                    onConnectionStateChange = onConnState,
                    onIceConnectionStateChange = onIceConnState,
                    onSignalingStateChange = onSigState,
                    onRenegotiationNeeded = onRenegotiation,
                    supportsIndependentContentVideo = supportsIndependentContentVideo,
                    isOfferOwner = isOfferOwner,
                )
            },
            engineRemoveSlot = { slot: PeerConnectionSlotProtocol -> webRtcEngine.removeSlot(slot) },
            peerIndependentContentCapability = { cid -> resolvePeerIndependentContentCapability(cid) },
            sendMessage = { type: String, payload: org.json.JSONObject?, to: String? -> sendMessage(type, payload, to) },
            onRemoteParticipantsChanged = { refreshRemoteParticipants() },
            onAggregatePeerStateChanged = { ice: IceConnectionState, conn: PeerConnectionState, sig: RtcSignalingState ->
                if (conn == PeerConnectionState.CONNECTED) hasEverConnectedPeer = true
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

    private val defaultAudioCoordinator = DefaultAudioCoordinator(
        context = appContext,
        handler = handler,
        proximityMonitoringEnabled = config.proximityMonitoringEnabled,
        onProximityChanged = { near ->
            logger?.log(SerenadaLogLevel.DEBUG, "Session", "Proximity sensor changed: ${if (near) "NEAR" else "FAR"}")
        },
        onAudioEnvironmentChanged = { applyLocalVideoPreference() },
        logger = logger,
    )
    private val audioCoordinator: SerenadaAudioCoordinator = config.audioCoordinator ?: defaultAudioCoordinator
    private val callAudioSessionController: SessionAudioController = audioController ?: (config.audioCoordinator?.let { CustomAudioCoordinatorAdapter(it, config.proximityMonitoringEnabled, sensorManager, proximitySensor, handler, { applyLocalVideoPreference() }) } ?: defaultAudioCoordinator)

    init {
        startAudioCoordinatorCollectors()
    }

    private fun startAudioCoordinatorCollectors() {
        if (audioCoordinatorCollectorJobs.isNotEmpty()) return
        audioCoordinatorCollectorJobs += audioCoordinatorScope.launch {
            audioCoordinator.availableDevices.collect { devices ->
                _availableAudioDevices.value = devices
            }
        }
        audioCoordinatorCollectorJobs += audioCoordinatorScope.launch {
            audioCoordinator.effectiveInputDevice.collect { device ->
                if (!sessionActivated) return@collect
                routeInputAvailable = (device != null)
                updateEffectiveMicState()
            }
        }
        audioCoordinatorCollectorJobs += audioCoordinatorScope.launch {
            audioCoordinator.effectiveOutputDevice.collect { device ->
                _currentAudioDevice.value = device
                if (sessionActivated) applyLocalVideoPreference()
            }
        }
        audioCoordinatorCollectorJobs += audioCoordinatorScope.launch {
            audioCoordinator.events.collect { event ->
                handleCoordinatorEvent(event)
            }
        }
    }

    private fun stopAudioCoordinatorCollectors() {
        audioCoordinatorCollectorJobs.forEach { it.cancel() }
        audioCoordinatorCollectorJobs.clear()
    }

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
                forgetRemoteContentTracking(event.peerId)
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
                if (message.type == "content_state" || message.type == "participant_media_state" ||
                    message.type == "offer" || message.type == "answer" || message.type == "ice" ||
                    message.type == "media_restart_request"
                ) {
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
                if (event.code == "TURN_REFRESH_FAILED") {
                    // Non-fatal: media keeps flowing on the existing credentials until expiry.
                    logger?.log(SerenadaLogLevel.WARNING, "Session", "TURN refresh failed: ${event.message}")
                    return@runOnMain
                }
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
                peerNegotiationEngine.scheduleDirtyPairRestart(event.withCid)
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

        override fun onReconnectTokenRefreshed(event: ReconnectTokenRefreshedEvent) {
            runOnMain {
                reconnectToken = event.reconnectToken
                event.reconnectTokenTTLMs?.let { reconnectTokenTTLMs = it }
                persistRecoveryRecord()
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

    /** Permanently release this session after the host is done with it. */
    fun close() {
        assertMainThread()
        if (closed) return
        closed = true

        val deactivationJob =
            if (_state.value.phase == CallPhase.Idle) {
                audioCoordinatorDeactivationJob
            } else {
                signalingProvider.leaveRoom()
                cleanupCall(EndReason.LocalLeft)
            }

        providerScope.cancel()
        cancelAudioCoordinatorScopeAfter(deactivationJob)
    }

    /** Toggle local audio on or off. */
    fun toggleAudio() {
        assertMainThread()
        // While held, `userMuted` is frozen (no capture); toggle relative to the
        // desired intent so a held toggle flips the intent applied on resume.
        val currentlyEnabled = if (isHeld) desiredAudioEnabled else !userMuted
        setMicMuted(currentlyEnabled)
    }

    /** Toggle local video on or off. */
    fun toggleVideo() {
        assertMainThread()
        if (!videoCaptureSupported) return
        // While held, `localVideoEnabled` is false (no capture); toggle relative to
        // the desired intent so a held toggle flips the intent applied on resume.
        val requestedEnabled = if (isHeld) desiredVideoMode == null else !_state.value.localVideoEnabled
        // While held this session owns no capture (Core Invariant 2): handle held
        // FIRST — record the desired intent ONLY, with NO permission prompt, NO
        // camera restart, NO broadcast. Resume applies the latest intent (and
        // prompts for camera permission then if still needed). Requesting
        // permission here would surface a prompt for a call that owns no media and
        // could return before recording the user's intent (FIX A-1).
        if (isHeld) {
            userPreferredVideoEnabled = requestedEnabled
            desiredVideoMode = if (requestedEnabled) {
                webRtcEngine.activeCameraMode() ?: _state.value.localCameraMode
            } else {
                null
            }
            return
        }
        if (requestedEnabled && !hasCameraPermission() && !_diagnostics.value.isScreenSharing) {
            requestPermissions(listOf(MediaCapability.CAMERA))
            return
        }
        userPreferredVideoEnabled = requestedEnabled
        // Track user intent for hold/resume (Phase 1): off when disabled, else
        // the active camera mode.
        desiredVideoMode = if (requestedEnabled) {
            webRtcEngine.activeCameraMode() ?: _state.value.localCameraMode
        } else {
            null
        }
        applyLocalVideoPreference()
        broadcastLocalMediaState()
    }

    /** Cycle to the next camera mode in the configured preference order. */
    fun flipCamera() {
        assertMainThread()
        if (availableCameraModes.size <= 1) return
        // While held this session owns no camera (Core Invariant 2): advance the
        // desired camera mode ONLY (no capture, no broadcast) so resume restores
        // the framing. A held flip with video off is a no-op.
        if (isHeld) {
            val current = desiredVideoMode ?: return
            val idx = availableCameraModes.indexOf(current)
            if (idx < 0) return
            desiredVideoMode = availableCameraModes[(idx + 1) % availableCameraModes.size]
            return
        }
        val sharing = _diagnostics.value.isScreenSharing
        if (config.enableIndependentContentVideo) {
            // Independent mode: the camera is a separate track, so flipping it
            // during a screen share is valid and leaves the content track
            // untouched (pitfall #6). Do NOT broadcast a camera-framing
            // content_state while sharing — the screen share owns content_state.
            webRtcEngine.flipCamera()
            return
        }
        // Legacy mode: a single video track carries the share, so flip is blocked
        // while sharing (it would clobber the display track).
        if (!sharing) {
            val currentMode = _state.value.localCameraMode
            if (currentMode.isContentMode) broadcastLocalContentState(false)
            webRtcEngine.flipCamera()
        }
    }

    /** Set a specific camera mode. Ignored when [mode] is not in the configured list. */
    fun setCameraMode(mode: LocalCameraMode) {
        assertMainThread()
        if (mode !in availableCameraModes) return
        // While held this session owns no camera (Core Invariant 2): record the
        // desired camera mode ONLY (no capture, no broadcast) so resume restores
        // it. A held setCameraMode with video off is a no-op (intent stays off).
        if (isHeld) {
            if (desiredVideoMode != null) desiredVideoMode = mode
            return
        }
        // The session's state copy of the camera mode is posted asynchronously,
        // so flipping in a loop must read the engine-side mode, which flipCamera
        // updates synchronously.
        repeat(availableCameraModes.size) {
            val current = webRtcEngine.activeCameraMode() ?: _state.value.localCameraMode
            if (current == mode) return
            flipCamera()
        }
    }

    /** Start screen sharing using the given media projection intent. */
    fun startScreenShare(intent: Intent) {
        assertMainThread()
        // Held media owns no screen share (Core Invariant 2): refuse while held so
        // a held session never starts MediaProjection capture or broadcasts a
        // content/participant_media_state. Screen share is foreground-only and is
        // NOT auto-restored on resume — the user must restart it.
        if (isHeld) {
            logger?.log(SerenadaLogLevel.INFO, "Session", "Ignoring startScreenShare while held")
            return
        }
        if (!videoMediaEnabled) return
        if (_diagnostics.value.isScreenSharing) return
        if (config.enableIndependentContentVideo) {
            startScreenShareIndependent(intent)
        } else {
            startScreenShareLegacy(intent)
        }
    }

    /** Legacy single-video screen share path (flag off): byte-identical to today. */
    private fun startScreenShareLegacy(intent: Intent) {
        val wasVideoPreferred = userPreferredVideoEnabled
        userPreferredVideoEnabled = true
        if (!webRtcEngine.startScreenShare(intent)) {
            userPreferredVideoEnabled = wasVideoPreferred
            logger?.log(SerenadaLogLevel.WARNING, "Session", "Failed to start screen sharing")
            return
        }
        updateDiagnostics(_diagnostics.value.copy(isScreenSharing = true))
        broadcastLocalContentState(true, ContentTypeWire.SCREEN_SHARE)
        applyLocalVideoPreference()
    }

    /**
     * Independent content share path (flag on): the screen rides a SEPARATE
     * content track, so the camera preference is NOT touched (pitfall #6) and
     * `cameraMode` is never set to screenShare. Signal `content_state` only after
     * capture/attach succeeds so peers do not render a transient failed share.
     */
    private fun startScreenShareIndependent(intent: Intent) {
        if (!webRtcEngine.startScreenShare(intent)) {
            logger?.log(SerenadaLogLevel.WARNING, "Session", "Failed to start screen sharing")
            return
        }
        updateDiagnostics(_diagnostics.value.copy(isScreenSharing = true))
        broadcastLocalContentState(true, ContentTypeWire.SCREEN_SHARE)
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
        // Legacy stop restores the camera onto the single sender, so re-apply the
        // camera preference. Independent stop never touched the camera, so the
        // re-apply is unnecessary (the content track was separate).
        if (!config.enableIndependentContentVideo) {
            broadcastLocalContentState(false)
            applyLocalVideoPreference()
        } else {
            val restoredType = cameraContentTypeAfterIndependentStop()
            if (restoredType != null) {
                broadcastLocalContentState(true, restoredType)
            } else {
                broadcastLocalContentState(false)
            }
        }
    }

    /**
     * Independent-stop only: content type that should remain active when the
     * camera is still in a world/composite content framing. SELFIE has no
     * content framing, so stopping the share emits inactive instead.
     */
    private fun cameraContentTypeAfterIndependentStop(): String? {
        val cameraMode = webRtcEngine.activeCameraMode() ?: _state.value.localCameraMode
        return when (cameraMode) {
            LocalCameraMode.WORLD -> ContentTypeWire.WORLD_CAMERA
            LocalCameraMode.COMPOSITE -> ContentTypeWire.COMPOSITE_CAMERA
            else -> null
        }
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

    /**
     * Capture the current video frame from the chosen stream as JPEG bytes
     * at the source track's full intrinsic resolution.
     *
     * Throws [SnapshotError.StreamNotActive] when the chosen stream's video
     * is off or the participant is not connected, [SnapshotError.CaptureTimeout]
     * if no frame arrives within the resilience window, or
     * [SnapshotError.CaptureFailed] for encode errors.
     */
    suspend fun captureSnapshot(source: SnapshotSource = SnapshotSource.Local): SnapshotResult {
        assertMainThread()
        val attachSink: (org.webrtc.VideoSink) -> Unit
        val detachSink: (org.webrtc.VideoSink) -> Unit

        when (source) {
            SnapshotSource.Local -> {
                val phase = _state.value.phase
                if (!_state.value.localVideoEnabled ||
                    (phase != CallPhase.InCall && phase != CallPhase.Waiting)
                ) {
                    throw SnapshotError.StreamNotActive
                }
                attachSink = { sink -> webRtcEngine.attachLocalSink(sink) }
                detachSink = { sink -> webRtcEngine.detachLocalSink(sink) }
            }
            is SnapshotSource.Remote -> {
                val slot = peerSlots[source.cid] ?: throw SnapshotError.StreamNotActive
                if (!slot.isRemoteVideoTrackEnabled()) {
                    throw SnapshotError.StreamNotActive
                }
                attachSink = { sink -> slot.attachRemoteSink(sink) }
                detachSink = { sink -> slot.detachRemoteSink(sink) }
            }
        }

        return FrameSnapshotCapture(
            handler = handler,
            source = source,
            attachSink = attachSink,
            detachSink = detachSink,
        ).capture()
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

    // --- Content (screen share) renderer APIs ---
    // Camera renderers stay on attachRemoteRenderer / attachLocalSink above.
    // These render the independent CONTENT (screen share) stream separately.

    /** Attach a sink to a specific peer's remote CONTENT (screen share) track. */
    fun attachRemoteContentRenderer(renderer: org.webrtc.VideoSink, participantCid: String) {
        assertMainThread()
        peerSlots[participantCid]?.attachRemoteContentSink(renderer)
    }

    /** Detach a previously attached remote content renderer for a peer. */
    fun detachRemoteContentRenderer(renderer: org.webrtc.VideoSink, participantCid: String) {
        assertMainThread()
        peerSlots[participantCid]?.detachRemoteContentSink(renderer)
    }

    /** Attach a sink to the LOCAL content (screen share) track for local preview. */
    fun attachLocalContentRenderer(renderer: org.webrtc.VideoSink) {
        assertMainThread()
        webRtcEngine.attachLocalContentSink(renderer)
    }

    /** Detach a previously attached local content renderer. */
    fun detachLocalContentRenderer(renderer: org.webrtc.VideoSink) {
        assertMainThread()
        webRtcEngine.detachLocalContentSink(renderer)
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
        // A held-initial join (registry, Phase 2) owns no capture and must NOT
        // request camera/mic permissions — permission prompts belong to the call
        // being foregrounded. Skip the permission gate so it goes straight to the
        // held join path (createSendersForHold, no capture).
        val hasPermissions = initialMediaRole == CallMediaRole.HELD || hasRequiredPermissions()
        joinFlowCoordinator.start(hasPermissions)
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
        startAudioCoordinatorCollectors()
        localMediaReadyForNegotiation = false
        hasEverConnectedPeer = false
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
                callStartedAtMs = callStartTimeMs,
                localAudioEnabled = config.defaultAudioEnabled,
                localVideoEnabled = videoCaptureSupported && config.defaultVideoEnabled,
                // `localVideoEnabled` remains the camera-specific compatibility
                // signal; independent screen share is exposed via localContent.
                localCameraEnabled = videoCaptureSupported && config.defaultVideoEnabled,
                localContent = null,
                localDisplayName = displayName,
                remoteParticipants = emptyList(),
                localCameraMode = initialCameraMode,
                availableCameraModes = availableCameraModes,
                connectionStatus = ConnectionStatus.Connected,
            )
        )
        updateDiagnostics(CallDiagnostics())

        if (initialMediaRole == CallMediaRole.HELD) {
            startHeldJoinInternal(joinAttemptId)
            return
        }

        // Foreground single-call join. Route through the process-wide arbiter
        // (mode DIRECT) BEFORE activating any media so a second concurrent direct
        // join fails fast with ForegroundLeaseUnavailable and only one call ever
        // owns capture/audio (contract §2; Core Invariant 1). Held-initial joins
        // and the registry leave acquireForegroundLease false (the registry owns
        // the lease itself in Phase 3). This is the session's OWN lease, stored in
        // directLeaseToken (NOT foregroundOwnerToken, which is the registry-issued
        // fence token); it is the only lease the session self-releases, in
        // resetResources.
        if (acquireForegroundLease && directLeaseToken == null) {
            try {
                directLeaseToken = ForegroundMediaArbiter.acquireForeground(
                    ownerId = roomId,
                    mode = ForegroundArbiterMode.DIRECT,
                    modeOwnerRef = this,
                )
            } catch (e: ForegroundLeaseUnavailable) {
                logger?.log(SerenadaLogLevel.ERROR, "Media", "Foreground lease unavailable: ${e.message}")
                handleError(CallError.Unknown(e.message ?: "Foreground media is owned by another call"))
                return
            }
        }

        acquirePerformanceLocks()
        providerScope.launch {
            try {
                withTimeout(WebRtcResilienceConstants.AUDIO_COORDINATOR_TIMEOUT_MS) {
                    audioCoordinatorMutex.withLock {
                        audioCoordinator.activateCallSession(config.audioIntent)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger?.log(SerenadaLogLevel.ERROR, "Audio", "Audio session activation timed out")
                handleError(CallError.Unknown("Audio session activation timed out"))
                return@launch
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger?.log(SerenadaLogLevel.ERROR, "Audio", "Failed to activate audio session: ${e.message}")
                handleError(CallError.Unknown(e.message ?: "Audio session activation failed"))
                return@launch
            }
            if (!isActive) return@launch
            joinFlowCoordinator.scheduleJoinTimeout(roomId, joinAttemptId)
            try {
                callAudioSessionController.activate()
                webRtcEngine.startLocalMedia(startVideoCapture = userPreferredVideoEnabled)
                localMediaReadyForNegotiation = true
                userMuted = !config.defaultAudioEnabled
                sessionActivated = true
                // Single-call start: this session owns foreground media and is
                // fully active (Phase 1). actual* track updateEffectiveMicState /
                // applyLocalVideoPreference below.
                mediaRole = CallMediaRole.FOREGROUND
                mediaActivationState = MediaActivationState.ACTIVE
                updateEffectiveMicState()
                applyLocalVideoPreference()
                actualAudioPublished = _state.value.localAudioEnabled
                actualVideoPublished = _state.value.localVideoEnabled
                startRemoteVideoStatePolling()
                peerNegotiationEngine.onLocalMediaReady()
                joinFlowCoordinator.scheduleJoinKickstart(joinAttemptId)
                joinFlowCoordinator.ensureSignalingConnection()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger?.log(SerenadaLogLevel.ERROR, "Media", "Failed to start local media: ${e.message}")
                handleError(CallError.Unknown(e.message ?: "Local media startup failed"))
            }
        }
    }

    /**
     * Held-initial join (multi-call session, Phase 2; registry-internal). Unlike
     * the foreground path this:
     * - activates NO audio coordinator and NO audio controller (held calls own no
     *   audio routing/focus; Core Invariant 2),
     * - starts NO mic/camera capture (the OS never reports a held session
     *   capturing),
     * - creates stable audio + video transceivers/senders with a null track
     *   ([SessionMediaEngine.createSendersForHold]) so a later resume attaches
     *   fresh tracks with no SDP renegotiation (contract §5 / Core Invariant 3),
     * - deafens remote playout, and broadcasts `held=true` after senders exist,
     * - still connects signaling and preserves peer-connection identity.
     * The session sits HELD/INACTIVE until the registry foregrounds it via
     * [activateForeground].
     */
    private fun startHeldJoinInternal(joinAttemptId: Long) {
        joinFlowCoordinator.scheduleJoinTimeout(roomId, joinAttemptId)
        try {
            // Create stable senders without capture. A held call publishes
            // nothing (actual* stay false); desired* survive for resume.
            webRtcEngine.createSendersForHold()
            // Remote playout is silenced while held (defense in depth: a peer that
            // joins/renegotiates during hold inherits the deafen at slot creation).
            webRtcEngine.setRemotePlaybackEnabled(false)
            localMediaReadyForNegotiation = true
            userMuted = !config.defaultAudioEnabled
            // Held: no capture, no audio session — but signaling/negotiation run.
            sessionActivated = false
            mediaRole = CallMediaRole.HELD
            mediaActivationState = MediaActivationState.INACTIVE
            actualAudioPublished = false
            actualVideoPublished = false
            // Reflect held in published local state so peers and UI converge.
            updateState(
                _state.value.copy(
                    localAudioEnabled = false,
                    localVideoEnabled = false,
                    localCameraEnabled = false,
                )
            )
            peerNegotiationEngine.onLocalMediaReady()
            joinFlowCoordinator.scheduleJoinKickstart(joinAttemptId)
            joinFlowCoordinator.ensureSignalingConnection()
            // Broadcast held AFTER senders exist (local-state-then-broadcast). The
            // builder forces audio=false/video=false so an old peer degrades to
            // muted/camera-off, never a wrong "live" state.
            runCatching { broadcastLocalMediaState(held = true) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger?.log(SerenadaLogLevel.ERROR, "Media", "Failed to start held media: ${e.message}")
            handleError(CallError.Unknown(e.message ?: "Held media startup failed"))
        }
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

    // --- Internal: Multi-call hold / resume (Phase 1) ---
    //
    // These are the session-internal mechanics behind the eventual token-gated
    // releaseForeground / activateForeground (Phase 2). They take no token this
    // phase. Both run on the main thread. They preserve signaling, reconnect
    // identity, and peer-connection identity; only foreground media ownership is
    // suspended/restored. desired* are preserved.

    /**
     * Drive this session to a fully-held media state: stop screen share, release
     * local capture (mic + camera), deafen remote audio, deactivate the audio
     * controller + coordinator, release foreground-media wake locks, and
     * broadcast `held=true` AFTER capture has stopped.
     *
     * Idempotent: a second call is a no-op. Must not throw after partial release.
     */
    internal suspend fun applyHeldRoleInternal() {
        assertMainThread()
        // Bump the op generation FIRST so an in-flight resume that awaits the
        // async audio coordinator detects supersession on its post-await fence
        // and rolls back instead of committing foreground (FIX N2).
        mediaOpGeneration += 1
        // Idempotent no-op only when fully held AND no resume is mid-flight. While
        // a resume is ACTIVATING the role is still HELD but foreground resources
        // are (being) re-acquired, so we must still drive them back down.
        if (mediaRole == CallMediaRole.HELD && mediaActivationState != MediaActivationState.ACTIVATING) return
        mediaRole = CallMediaRole.HELD
        mediaActivationState = MediaActivationState.INACTIVE
        suspendForegroundMediaResources()
        // Broadcast held AFTER capture has stopped (local-stop-then-broadcast).
        runCatching { broadcastLocalMediaState(held = true) }
    }

    /**
     * Tear down all foreground media resources to the fully-held state: stop
     * screen share, release local capture, deafen remote audio, detach renderers,
     * deactivate the audio controller + coordinator, release wake locks, pause
     * pollers, and reflect held in published local state. Does NOT broadcast — the
     * caller controls broadcast ordering (hold broadcasts held=true; a resume
     * rollback stays silent). Each step is best-effort (must not throw after a
     * partial release).
     */
    private suspend fun suspendForegroundMediaResources() {
        // 1. Stop screen share first (foreground-only; not restored on resume).
        //    Broadcast content_state:false after the engine stop succeeds so peers
        //    don't retain stale active content while we go held — the normal
        //    stopScreenShare() path does the same (FIX A-3).
        runCatching {
            if (_diagnostics.value.isScreenSharing) {
                webRtcEngine.stopScreenShare()
                updateDiagnostics(_diagnostics.value.copy(isScreenSharing = false))
                broadcastLocalContentState(false)
            }
        }
        // 2. Release local capture (mic + camera) — capture actually stops.
        runCatching { webRtcEngine.suspendLocalMediaForHold() }
        // 3. Deafen remote audio playout.
        runCatching { webRtcEngine.setRemotePlaybackEnabled(false) }
        // 4. Detach/pause visible renderers.
        runCatching { webRtcEngine.detachRenderersForHold() }
        // 5. Deactivate the audio controller, then the coordinator (mirror the
        //    teardown ordering used in resetResources). The coordinator op is the
        //    awaited async boundary; a cancellation here MUST propagate (FIX N3).
        runCatching { callAudioSessionController.deactivate() }
        runIgnoringNonCancellation { deactivateAudioCoordinatorForHold() }
        // 6. Release foreground-media wake locks.
        runCatching { releasePerformanceLocks() }
        // 7. Pause foreground-only pollers.
        runCatching { stopRemoteVideoStatePolling() }

        sessionActivated = false
        actualAudioPublished = false
        actualVideoPublished = false
        // Reflect held in published local state so peers and UI converge.
        updateState(_state.value.copy(localAudioEnabled = false, localVideoEnabled = false, localCameraEnabled = false))
    }

    /**
     * Drive this session back to foreground: activate the audio coordinator +
     * controller, resume local capture per desired intent, re-enable remote
     * playout, restart foreground pollers, and broadcast `held=false` AFTER
     * media is flowing.
     *
     * Idempotent: a second call while already foreground is a no-op.
     */
    internal suspend fun applyForegroundRoleInternal(
        // Phase 2 token-gated fence (contract §3). When non-null, the post-await
        // result is honored only if BOTH the current generation AND this token
        // still match. Null on the Phase-1 un-gated path, where only the
        // self-incrementing generation fences a local hold/resume race.
        expectedToken: ForegroundOwnerToken? = null,
        expectedGeneration: Long? = null,
    ) {
        assertMainThread()
        if (mediaRole == CallMediaRole.FOREGROUND) return
        // Capture the op generation for this resume. A concurrent hold that lands
        // while we await the async audio coordinator bumps the generation; the
        // post-await fence below detects it and rolls back to held instead of
        // committing foreground (FIX N2). On the token-gated path the caller has
        // already seeded mediaOpGeneration with the arbiter generation; on the
        // un-gated path we self-increment.
        if (expectedGeneration == null) {
            mediaOpGeneration += 1
        }
        val gen = expectedGeneration ?: mediaOpGeneration
        mediaActivationState = MediaActivationState.ACTIVATING

        // 1. Activate the coordinator, then the controller (mirror start order).
        //    The coordinator activation is the awaited async boundary; a cancellation
        //    here MUST propagate (FIX N3: do not swallow CancellationException).
        //    Unlike the (best-effort, no-throw) hold/deactivate op, a resume's
        //    coordinator activation FAILURE/timeout must NOT be swallowed (FIX A):
        //    if it failed, this session never took process audio, so committing
        //    foreground/active would leave an orphaned (silent) call holding the
        //    lease. Capture the outcome and fail the activation when it didn't
        //    succeed, so the registry's withTimeout/catch rolls back to the
        //    previous call.
        val coordinatorActivated = activateAudioCoordinatorForResume()
        // FENCE (double-fence; contract §3): if a hold superseded this resume while
        // we awaited the coordinator, roll back to fully-held and bail — do NOT
        // commit foreground or broadcast held=false. The hold that superseded us
        // already broadcast held=true. A late callback is honored only if BOTH the
        // generation AND (when token-gated) the lease owner token still match;
        // generation alone is insufficient because a rollback re-activates the old
        // call under a fresh generation while a stuck callback could match it.
        val supersededByGeneration = gen != mediaOpGeneration
        // The arbiter is the source of truth for who currently owns the lease, not
        // the session's local field: after a rollback the local field can still
        // equal a stale token, so fence against the arbiter's live owner (parity
        // with web `foregroundArbiter.isCurrentOwner(token)`).
        val supersededByToken = expectedToken != null && !ForegroundMediaArbiter.isCurrentOwner(expectedToken)
        if (supersededByGeneration || supersededByToken) {
            mediaRole = CallMediaRole.HELD
            mediaActivationState = MediaActivationState.INACTIVE
            suspendForegroundMediaResources()
            return
        }
        // FIX A: the coordinator activation actually failed/timed out (and we are
        // still the current op + owner). Do NOT commit foreground. Tear back down
        // to fully-held, mark the activation FAILED, and surface the failure so the
        // token-gated activateForeground throws and the registry rolls back to the
        // previous call (no orphaned lease holder, single-lease invariant kept).
        if (!coordinatorActivated) {
            suspendForegroundMediaResources()
            mediaRole = CallMediaRole.HELD
            mediaActivationState = MediaActivationState.FAILED
            return
        }
        runCatching { callAudioSessionController.activate() }
        // 2. Resume local capture per desired intent (no renegotiation).
        runCatching { webRtcEngine.resumeLocalMediaFromHold(desiredAudioEnabled, desiredVideoMode) }
        // 3. Re-enable remote playout.
        runCatching { webRtcEngine.setRemotePlaybackEnabled(true) }
        // 4. Restart foreground-only pollers.
        runCatching { startRemoteVideoStatePolling() }

        // 5. Commit the FOREGROUND role BEFORE applying media (FIX M3). Restoring
        //    intent calls updateEffectiveMicState()/applyLocalVideoPreference(),
        //    which broadcast on change; were the role still HELD they would emit
        //    redundant held=true messages before the final held=false. Commit the
        //    role first AND suppress the intermediate broadcasts so exactly one
        //    held=false is sent once media is flowing.
        mediaRole = CallMediaRole.FOREGROUND
        mediaActivationState = MediaActivationState.ACTIVE
        sessionActivated = true
        userMuted = !desiredAudioEnabled
        userPreferredVideoEnabled = desiredVideoMode != null
        suppressMediaStateBroadcast = true
        try {
            updateEffectiveMicState()
            applyLocalVideoPreference()
        } finally {
            suppressMediaStateBroadcast = false
        }

        actualAudioPublished = _state.value.localAudioEnabled
        actualVideoPublished = _state.value.localVideoEnabled

        // 6. Broadcast exactly one held=false AFTER media is flowing
        //    (attach-then-broadcast).
        runCatching { broadcastLocalMediaState(held = false) }
    }

    /**
     * Like [runCatching] but NEVER swallows [CancellationException] (FIX N3):
     * structured-concurrency cancellation must propagate so an aborted hold/resume
     * does not silently continue to (de)activate media. All other failures are
     * absorbed (hold/resume steps are best-effort).
     */
    private suspend fun runIgnoringNonCancellation(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Hold/resume step failed: ${e.message}")
        }
    }

    /**
     * Deactivate the audio coordinator for hold (awaited; mirrors [resetResources]).
     * Best-effort/no-throw: hold MUST NOT throw after a partial release, so a
     * timeout/failure here is logged at WARNING and swallowed (a CancellationException
     * still propagates so an aborted hold stops cleanly).
     */
    private suspend fun deactivateAudioCoordinatorForHold() {
        try {
            withTimeout(WebRtcResilienceConstants.AUDIO_COORDINATOR_TIMEOUT_MS) {
                audioCoordinatorMutex.withLock { audioCoordinator.deactivateCallSession() }
            }
        } catch (e: TimeoutCancellationException) {
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Hold: audio coordinator deactivation timed out")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Hold: audio coordinator deactivation failed: ${e.message}")
        }
    }

    /**
     * Activate the audio coordinator for resume (mirrors [startJoinInternal]).
     * Returns true iff the coordinator activation actually completed; false on
     * timeout/failure (FIX A). A CancellationException still propagates (FIX N3: an
     * aborted resume must stop cleanly). Unlike the hold/deactivate path, the
     * outcome is NOT swallowed: the caller fails the foreground activation when this
     * returns false so no session commits foreground without owning process audio.
     */
    private suspend fun activateAudioCoordinatorForResume(): Boolean {
        return try {
            withTimeout(WebRtcResilienceConstants.AUDIO_COORDINATOR_TIMEOUT_MS) {
                audioCoordinatorMutex.withLock { audioCoordinator.activateCallSession(config.audioIntent) }
            }
            true
        } catch (e: TimeoutCancellationException) {
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Resume: audio coordinator activation timed out")
            false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Resume: audio coordinator activation failed: ${e.message}")
            false
        }
    }

    // --- Internal: Token-gated foreground contract (Phase 2; contract §3) ---
    //
    // Registry-owned, token-gated wrappers around the Phase-1 hold/resume
    // mechanics. The (Phase 3) registry calls these with an arbiter-minted owner
    // token + operation generation; public single-call integrations never see
    // them (they keep using join()/leave() + the audio/video toggles).

    /** Result of [preflightForeground]: a pure permission/precondition check. */
    enum class ForegroundPreflight {
        /** Desired media can be foregrounded with no prompt. */
        OK,

        /** A required mic/camera grant for the desired media is missing. */
        NEEDS_PERMISSION,

        /** A non-permission precondition failed (e.g. session torn down). */
        FAILED,
    }

    /**
     * PURE preflight check (contract §3): can this call activate foreground media
     * with its DESIRED media WITHOUT prompting or capturing? Opens NO permission
     * prompt and starts NO capture — the host owns the prompt.
     *
     * - [ForegroundPreflight.OK] when the desired media needs no permission: if
     *   `desiredAudioEnabled == false` AND `desiredVideoMode == off`, a fully
     *   muted, camera-off call can foreground with no prompt.
     * - [ForegroundPreflight.NEEDS_PERMISSION] when a required grant (RECORD_AUDIO
     *   for desired audio, CAMERA for a desired camera mode) is not already
     *   granted.
     * - [ForegroundPreflight.FAILED] for non-permission preconditions.
     */
    fun preflightForeground(): ForegroundPreflight {
        assertMainThread()
        if (isInactiveForForeground()) return ForegroundPreflight.FAILED
        // A fully muted, camera-off call requires no device permission.
        val needsMic = desiredAudioEnabled
        val needsCamera = desiredVideoMode != null &&
            desiredVideoMode != LocalCameraMode.SCREEN_SHARE &&
            videoCaptureSupported
        if (!needsMic && !needsCamera) return ForegroundPreflight.OK
        val required = buildList {
            if (needsMic) add(android.Manifest.permission.RECORD_AUDIO)
            if (needsCamera) add(android.Manifest.permission.CAMERA)
        }
        val allGranted = required.all { permission ->
            appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        return if (allGranted) ForegroundPreflight.OK else ForegroundPreflight.NEEDS_PERMISSION
    }

    private fun isInactiveForForeground(): Boolean = closed || _state.value.phase == CallPhase.Idle

    /**
     * Registry-owned (contract §7) setter for the activation state on the paths
     * that change it WITHOUT a media transition: a refused switch marks the target
     * NEEDS_PERMISSION; an old-release timeout marks the old call FAILED (its media
     * may be partial but it keeps the lease). Not a public single-call API.
     */
    internal fun setMediaActivationState(state: MediaActivationState) {
        assertMainThread()
        mediaActivationState = state
    }

    /**
     * Token-gated foreground activation (contract §3; wraps the Phase-1
     * [applyForegroundRoleInternal]). The registry passes the arbiter-minted owner
     * token it just acquired plus the arbiter operation generation. The awaited
     * coordinator activation result is honored ONLY if BOTH the current
     * [mediaOpGeneration] AND the current lease owner token still match (the
     * double fence; contract §3): generation alone is insufficient because a
     * rollback re-activates the old call under a fresh generation, and a stuck
     * callback from the failed new activation could otherwise still match.
     *
     * Throws on failure so the registry can run its rollback path.
     */
    suspend fun activateForeground(ownerToken: ForegroundOwnerToken, generation: Long) {
        assertMainThread()
        // Test-only injection (see [failNextForegroundActivationForTest]): model an
        // activation that fails so the registry rollback path can be exercised
        // deterministically without a real media/coordinator failure.
        if (failNextForegroundActivationForTest) {
            failNextForegroundActivationForTest = false
            foregroundOwnerToken = ownerToken
            mediaActivationState = MediaActivationState.FAILED
            throw IllegalStateException("test-injected activation failure")
        }
        // Seed the lease token + generation BEFORE awaiting so the post-await
        // fences inside applyForegroundRoleInternal see them. The session keeps
        // mediaOpGeneration wired to the passed generation (the arbiter's
        // monotonic counter) and uses foregroundOwnerToken as the 2nd fence.
        foregroundOwnerToken = ownerToken
        mediaOpGeneration = generation
        applyForegroundRoleInternal(expectedToken = ownerToken, expectedGeneration = generation)
        if (mediaRole != CallMediaRole.FOREGROUND) {
            throw IllegalStateException("activateForeground did not reach FOREGROUND (superseded or failed)")
        }
    }

    /**
     * Test-only: when true, the NEXT [activateForeground] throws instead of
     * activating (and clears itself). Lets a registry test drive the
     * switch-failure rollback path deterministically (the new call's activation
     * fails; the registry must restore the old call to foreground). NOT a public
     * API.
     */
    internal var failNextForegroundActivationForTest: Boolean = false

    /**
     * Test-only: when true, [releaseForeground] suspends forever so the registry's
     * `withTimeout(FOREGROUND_RELEASE_TIMEOUT_MS)` drain fires (Core Invariant 1:
     * the old call keeps its lease, the next lease is never acquired). NOT a public
     * API.
     */
    internal var hangNextForegroundReleaseForTest: Boolean = false

    /**
     * Token-gated release (contract §3; wraps the Phase-1
     * [applyHeldRoleInternal]). Idempotent and MUST NOT throw after a partial
     * release. Uses the token ONLY to fence (it proves the caller is draining the
     * current owner) and does NOT call [ForegroundMediaArbiter.releaseLease] — the
     * registry releases the lease after this confirms fully-held.
     */
    suspend fun releaseForeground(ownerToken: ForegroundOwnerToken) {
        assertMainThread()
        // Test-only injection (see [hangNextForegroundReleaseForTest]): suspend
        // forever so the registry's bounded drain times out (Invariant 1 path).
        if (hangNextForegroundReleaseForTest) {
            kotlinx.coroutines.awaitCancellation()
        }
        // A release for a token that is not ours is a no-op (already drained / the
        // lease moved on); never throw.
        if (foregroundOwnerToken != null && foregroundOwnerToken !== ownerToken) return
        applyHeldRoleInternal()
        // The session no longer owns the lease conceptually; the registry frees it.
        foregroundOwnerToken = null
    }

    /**
     * Undo a partial/failed foreground activation, driving the session back to
     * fully-held (contract §3). Idempotent and must not throw. Like
     * [releaseForeground] it does NOT touch the arbiter lease (registry-owned).
     */
    suspend fun abortForegroundActivation(ownerToken: ForegroundOwnerToken) {
        assertMainThread()
        if (foregroundOwnerToken != null && foregroundOwnerToken !== ownerToken) return
        applyHeldRoleInternal()
        foregroundOwnerToken = null
    }

    /**
     * Release the DIRECT single-call arbiter lease + mode claim this session held
     * (single-call teardown). Idempotent. The session owns a lease ONLY on the
     * direct [SerenadaCore.join] path (stored in [directLeaseToken]); a
     * registry-activated session's [foregroundOwnerToken] is a registry-issued
     * fence token, never released here (the registry owns and releases its own
     * lease). Releasing only [directLeaseToken] prevents teardown from
     * self-releasing a registry-owned lease (parity with iOS).
     *
     * Release only when this session is STILL the arbiter's current owner: guards
     * the (test-only) case where the singleton was reset out from under a live
     * session, so a stale release never touches another owner's lease.
     */
    private fun releaseForegroundLeaseAndMode() {
        val token = directLeaseToken
        if (token != null) {
            if (ForegroundMediaArbiter.isCurrentOwner(token)) {
                runCatching { ForegroundMediaArbiter.releaseLease(token) }
            }
            directLeaseToken = null
        }
        // Drop the DIRECT mode claim keyed by this session (no-op if never claimed
        // or already released). This clears process mode when the last direct
        // session ends so the registry can later claim REGISTRY mode.
        runCatching { ForegroundMediaArbiter.releaseMode(this) }
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
                    // Keep desired video mode in sync with the active camera mode
                    // while video is on, so a hold/resume cycle restores framing
                    // (Phase 1). Screen-share mode is not a camera intent.
                    if (userPreferredVideoEnabled && mode != LocalCameraMode.SCREEN_SHARE) {
                        desiredVideoMode = mode
                    }
                    if (config.enableIndependentContentVideo) {
                        // Independent mode: the camera mode never represents screen
                        // share (isScreenSharing is owned by start/stopScreenShare,
                        // not derived from the camera mode), and a real screen share
                        // owns `content_state` on the separate content track. The
                        // world/composite framing remains a CAMERA-path presentation
                        // hint, but it must NEVER collide with an active screen
                        // share's content_state — so suppress the camera-framing
                        // hint while screen sharing.
                        if (_diagnostics.value.isScreenSharing) return@post
                        val isContent = mode.isContentMode
                        val wasContent = previousMode.isContentMode
                        if (isContent) {
                            val type = if (mode == LocalCameraMode.WORLD) ContentTypeWire.WORLD_CAMERA else ContentTypeWire.COMPOSITE_CAMERA
                            broadcastLocalContentState(true, type)
                        } else if (wasContent) {
                            broadcastLocalContentState(false)
                        }
                        return@post
                    }
                    updateDiagnostics(_diagnostics.value.copy(isScreenSharing = mode == LocalCameraMode.SCREEN_SHARE))
                    val isContent = mode.isContentMode
                    val wasContent = previousMode.isContentMode
                    if (isContent) {
                        val type = if (mode == LocalCameraMode.WORLD) ContentTypeWire.WORLD_CAMERA else ContentTypeWire.COMPOSITE_CAMERA
                        broadcastLocalContentState(true, type)
                    } else if (wasContent) {
                        broadcastLocalContentState(false)
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
                    // External stop (OS revokes MediaProjection / system control).
                    // The engine already ran the idempotent stop path; mirror the
                    // session-side state once (pitfall #9: one content_state).
                    if (_diagnostics.value.isScreenSharing) {
                        updateDiagnostics(_diagnostics.value.copy(isScreenSharing = false))
                        if (config.enableIndependentContentVideo) {
                            val restoredType = cameraContentTypeAfterIndependentStop()
                            if (restoredType != null) {
                                broadcastLocalContentState(true, restoredType)
                            } else {
                                broadcastLocalContentState(false)
                            }
                        } else {
                            broadcastLocalContentState(false)
                        }
                    }
                    // Independent stop never preempted the camera, so don't
                    // re-apply the camera preference (pitfall #6).
                    if (!config.enableIndependentContentVideo) {
                        applyLocalVideoPreference()
                    }
                }
            },
            onFeatureDegradation = { degradation ->
                handler.post {
                    setFeatureDegradation(degradation)
                }
            },
            isHdVideoExperimentalEnabled = config.isHdVideoExperimentalEnabled,
            videoMediaEnabled = videoMediaEnabled,
            enableIndependentContentVideo = config.enableIndependentContentVideo,
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
            // Transport exhaustion: report via the synthetic code so the
            // shared reason table classifies it as networkConnectivity.
            maybeReportReconnectFailed("ICE_SERVER_FETCH_FAILED")
            finalizeQuality()
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
        seedLocalContentRevisionFromSnapshot(roomState)
        seedRemoteContentFromRoomState(roomState)
        val count = roomState.participants.size
        val isHostNow = clientId != null && clientId == roomState.hostCid
        val phase = if (count <= 1) CallPhase.Waiting else CallPhase.InCall
        if (phase != CallPhase.Joining) joinFlowCoordinator.clearJoinTimeout()
        val localJoinedAtMs = roomState.participants
            .firstOrNull { it.cid == clientId }
            ?.joinedAt
            ?.takeIf { isPlausibleJoinedAtMs(it, System.currentTimeMillis()) }

        updateState(
            _state.value.copy(
                phase = phase,
                isHost = isHostNow,
                participantCount = count,
                callStartedAtMs = localJoinedAtMs ?: _state.value.callStartedAtMs,
            )
        )

        peerNegotiationEngine.syncPeers(roomState)
        refreshRemoteParticipants()
        updateConnectionStatusFromSignals()
        // Start media-liveness emission only once we have remote peers — there's
        // nothing to report when alone in the room.
        if (phase == CallPhase.InCall) {
            startMediaLivenessTimer()
            startOutboundMediaWatchdog()
        }
    }

    private fun seedLocalContentRevisionFromSnapshot(roomState: RoomState) {
        val localRevision = roomState.participants
            .firstOrNull { it.cid == clientId }
            ?.contentState
            ?.revision
        if (localRevision != null && localRevision > localContentRevision) {
            localContentRevision = localRevision
        }
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
            val videoEnabled = peerState?.videoEnabled ?: participant?.videoEnabled ?: slot.isRemoteVideoTrackEnabled()
            // Per-role inbound liveness for stall diagnostics. Both default to
            // false until the first sample. Flag off / legacy peers: the single
            // inbound video routes to the camera role, so contentReceiving stays
            // false (additive, byte-identical for camera-only consumers).
            val roleLiveness = roleLivenessFor(cid)
            RemoteParticipant(
                cid = cid,
                displayName = participant?.displayName,
                peerId = participant?.peerId,
                audioEnabled = audioEnabled,
                videoEnabled = videoEnabled,
                // `videoEnabled` is the legacy/public camera signal. Keep
                // `cameraEnabled` mirrored here for backward-compatible callers.
                cameraEnabled = videoEnabled,
                content = remoteContentStates[cid],
                cameraReceiving = roleLiveness.camera,
                contentReceiving = roleLiveness.content,
                supportsIndependentContentVideo = remoteSupportsIndependentContentVideo(cid),
                connectionState = SerenadaPeerConnectionState.fromRtcState(slot.getConnectionState()),
                signalingStatus = signalingStatus,
                presumedLost = signalingStatus == ParticipantSignalingStatus.SUSPENDED && cid in presumedLostRemoteCids,
                audioLevel = if (audioEnabled) previousLevels[cid] ?: 0f else 0f,
                held = peerState?.held ?: false,
            )
        }
        val currentState = _state.value
        val currentDiagnostics = _diagnostics.value
        val activeCids = remoteParticipants.map { it.cid }.toSet()
        // Re-derive the diagnostics content pointer from the per-cid map if it
        // currently points at a peer no longer in the room. Re-pointing (not just
        // nulling) keeps a still-active sharer's identity when a DIFFERENT peer
        // departs. With one sharer (legacy / flag-off) this clears to null exactly
        // as before.
        val pointerStale = currentDiagnostics.remoteContentCid != null &&
            currentDiagnostics.remoteContentCid !in activeCids
        if (currentState.remoteParticipants == remoteParticipants) {
            if (pointerStale) refreshContentDiagnosticsPointer(preferredCid = null)
            return
        }
        updateState(currentState.copy(remoteParticipants = remoteParticipants))
        if (pointerStale) refreshContentDiagnosticsPointer(preferredCid = null)
    }

    // --- Internal: State ---

    private fun updateState(newState: CallState) {
        val previousPhase = _state.value.phase
        _state.value = newState
        // Drive the quality tracker on phase transitions.
        // Sampling/dropout tracking only begins once the tracker sees the
        // first InCall transition.
        if (newState.phase != previousPhase) {
            qualityTracker.onPhaseTransition(newState.phase, clock.monotonicMs())
        }
        delegate?.invoke()?.onSessionStateChanged(this, newState)
    }

    /**
     * Feed a connection-status change to the quality tracker. The dropout
     * **trigger** is derived at the transition: a degradation driven by lost
     * signaling is `NETWORK_LOST`; an ICE/peer-level degradation while
     * signaling is up is `UNKNOWN`.
     */
    private fun feedQualityConnectionStatus(next: ConnectionStatus) {
        if (next == lastTrackedConnectionStatus) return
        val trigger = if (!_diagnostics.value.isSignalingConnected) {
            DropoutTrigger.NETWORK_LOST
        } else {
            DropoutTrigger.UNKNOWN
        }
        qualityTracker.onConnectionStatusTransition(next, trigger, clock.monotonicMs())
        lastTrackedConnectionStatus = next
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

    private fun broadcastLocalMediaState(held: Boolean = mediaRole == CallMediaRole.HELD) {
        // During a resume's media-apply phase, suppress intermediate broadcasts so
        // exactly one held=false is emitted once media is flowing (FIX M3).
        if (suppressMediaStateBroadcast) return
        // A held call owns no capture (Core Invariant 2): peers must always see
        // audio=false/video=false while held, regardless of the (possibly stale)
        // local state snapshot. Resume broadcasts held=false with live values.
        signalingMessageRouter.broadcastMediaState(
            audioEnabled = !held && _state.value.localAudioEnabled,
            videoEnabled = !held && _state.value.localVideoEnabled,
            held = held,
        )
    }

    /**
     * Store per-participant capabilities/media policy and replay any persisted
     * content state carried in `joined`/`room_state` (e.g. for a peer that was
     * already sharing when we reconnected). Reuses the same supersede-by-revision
     * rule so a live `content_state` is never overwritten by an older snapshot.
     */
    private fun seedRemoteContentFromRoomState(roomState: RoomState) {
        val myCid = clientId
        for (participant in roomState.participants) {
            if (participant.cid == myCid) continue
            // Each authoritative snapshot is the source of truth: clear stored
            // caps/mediaPolicy for a still-present CID whose snapshot omits them
            // so accessors fall back to contract defaults instead of stale values.
            val caps = participant.capabilities
            if (caps != null) remoteCapabilities[participant.cid] = caps else remoteCapabilities.remove(participant.cid)
            val policy = participant.mediaPolicy
            if (policy != null) remoteMediaPolicies[participant.cid] = policy else remoteMediaPolicies.remove(participant.cid)
            val content = participant.contentState ?: continue
            handleRemoteContentState(
                fromCid = participant.cid,
                active = content.active,
                contentType = content.contentType,
                revision = content.revision,
            )
        }
    }

    /**
     * Apply an inbound `content_state` from [fromCid].
     *
     * Revision handling (revision is scoped to the sender's `(cid, sid)` on the
     * wire; the SDK does not currently receive the sender's `sid` on relayed
     * peer messages, so tracking is keyed by `cid` and reset when the peer
     * leaves — a rejoin therefore supersedes by identity):
     * - a missing revision is always accepted (older senders / forward compat);
     * - a revision <= the tracked one is discarded as stale/out-of-order;
     * - otherwise it is accepted and becomes the new tracked revision.
     */
    private fun handleRemoteContentState(
        fromCid: String,
        active: Boolean,
        contentType: String?,
        revision: Long?,
    ) {
        if (revision != null) {
            val tracked = remoteContentRevisions[fromCid]
            if (tracked != null && revision <= tracked) return
            remoteContentRevisions[fromCid] = revision
        }
        if (active) {
            remoteContentStates[fromCid] = ParticipantContent(
                active = true,
                type = contentType ?: ContentTypeWire.SCREEN_SHARE,
                revision = revision ?: 0L,
            )
        } else {
            remoteContentStates.remove(fromCid)
        }
        // Derive the single diagnostics content pointer from the per-cid map
        // rather than blindly tracking the last sender. With multiple sharers, a
        // peer's `active:false` must only clear the pointer if it was pointing at
        // THAT peer; otherwise the pointer keeps reflecting a still-active sharer.
        // Prefer the peer that just went active so a fresh start re-points
        // immediately. Mirrors iOS's per-cid correctness; flag-off (legacy
        // single-content) is unchanged because there is only ever one entry.
        refreshContentDiagnosticsPointer(preferredCid = if (active) fromCid else null)
        refreshRemoteParticipants()
    }

    /**
     * Recompute [CallDiagnostics.remoteContentCid] / [CallDiagnostics.remoteContentType]
     * from the per-cid [remoteContentStates] map. The pointer is single by design
     * (a diagnostics convenience); when several peers share, it points at one
     * active sharer ([preferredCid] when it is itself active, otherwise the
     * current pointer if still active, otherwise any active sharer), or null when
     * no peer is sharing. A peer's stop therefore only clears the pointer when no
     * other peer is actively sharing.
     */
    private fun refreshContentDiagnosticsPointer(preferredCid: String?) {
        val current = _diagnostics.value
        val target = when {
            preferredCid != null && remoteContentStates[preferredCid]?.active == true -> preferredCid
            current.remoteContentCid != null && remoteContentStates[current.remoteContentCid]?.active == true ->
                current.remoteContentCid
            else -> remoteContentStates.entries.firstOrNull { it.value.active }?.key
        }
        val targetType = target?.let { remoteContentStates[it]?.type }
        if (current.remoteContentCid == target && current.remoteContentType == targetType) return
        updateDiagnostics(
            current.copy(
                remoteContentCid = target,
                remoteContentType = targetType,
            )
        )
    }

    /**
     * Whether [cid] advertised independent content video at join. Defaults to
     * false when absent. Consumed by the media engine in a later phase; exposed
     * now so the stored capability is observable and testable.
     */
    internal fun remoteSupportsIndependentContentVideo(cid: String): Boolean =
        remoteCapabilities[cid]?.independentContentVideo ?: false

    /**
     * Whether [cid] permits any video media (signaled `mediaPolicy`). Defaults
     * to true when absent, per the audio-only compatibility boundary.
     */
    internal fun remoteVideoMediaEnabled(cid: String): Boolean =
        remoteMediaPolicies[cid]?.videoMediaEnabled ?: true

    /**
     * Resolve the per-peer independent-content routing inputs for a slot. A peer
     * is routed via the independent camera+content path only when ALL hold: the
     * local build flag is on, BOTH ends' `videoMediaEnabled` are true, and the
     * peer advertised `independentContentVideo`. When the flag is off this is
     * always `supported=false`, so every peer uses the legacy single-video path
     * (byte-identical to today). Mirrors web's `isPeerIndependentCapable`.
     */
    private fun resolvePeerIndependentContentCapability(
        cid: String,
    ): PeerNegotiationEngine.PeerIndependentContentCapability {
        val supported = config.enableIndependentContentVideo &&
            videoMediaEnabled &&
            remoteVideoMediaEnabled(cid) &&
            remoteSupportsIndependentContentVideo(cid)
        return PeerNegotiationEngine.PeerIndependentContentCapability(
            supported = supported,
        )
    }

    /**
     * Drop all tracked content/capability/policy state for a departed peer so a
     * later rejoin (which may restart its revision numbering) is accepted by
     * identity rather than discarded as stale.
     */
    private fun forgetRemoteContentTracking(cid: String) {
        remoteContentStates.remove(cid)
        remoteContentRevisions.remove(cid)
        remoteCapabilities.remove(cid)
        remoteMediaPolicies.remove(cid)
        // Drop per-role liveness baselines so a rejoin starts fresh (conservative
        // first sample) rather than diffing against a departed peer's totals.
        lastInboundRoleBytesByCid.remove(cid)
        roleLivenessByCid.remove(cid)
    }

    /**
     * Broadcast a `content_state` carrying a strictly-greater per-session
     * revision and mirror the new presentation state into [CallState.localContent].
     * Every send (start, stop, rollback) bumps the revision so receivers can
     * order quick toggles and discard stale, out-of-order updates.
     */
    private fun broadcastLocalContentState(active: Boolean, contentType: String? = null) {
        localContentRevision += 1
        val revision = localContentRevision
        signalingMessageRouter.broadcastContentState(active, contentType, revision)
        val nextContent = if (active) {
            ParticipantContent(
                active = true,
                type = contentType ?: ContentTypeWire.SCREEN_SHARE,
                revision = revision,
            )
        } else {
            null
        }
        if (_state.value.localContent != nextContent) {
            updateState(_state.value.copy(localContent = nextContent))
        }
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
                // Total inbound flow and per-role stall diagnostics refresh from
                // one stats sample per peer. Broadcast remains gated by signaling
                // connection, while role diagnostics stay current through blips.
                sampleInboundLiveness()
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

    private fun startOutboundMediaWatchdog() {
        if (outboundMediaWatchdogRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                peerNegotiationEngine.recoverStalledOutboundMedia()
                handler.postDelayed(this, WebRtcResilienceConstants.OUTBOUND_MEDIA_WATCHDOG_INTERVAL_MS)
            }
        }
        outboundMediaWatchdogRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.OUTBOUND_MEDIA_WATCHDOG_INTERVAL_MS)
    }

    private fun stopOutboundMediaWatchdog() {
        outboundMediaWatchdogRunnable?.let { handler.removeCallbacks(it) }
        outboundMediaWatchdogRunnable = null
    }

    private fun sampleInboundLiveness() {
        if (_state.value.phase == CallPhase.Idle || _state.value.phase == CallPhase.Ending) return
        if (mediaLivenessEmitInFlight) return
        val slots = peerSlots.toMap()
        if (slots.isEmpty()) {
            if (lastInboundRoleBytesByCid.isNotEmpty() || roleLivenessByCid.isNotEmpty()) {
                lastInboundRoleBytesByCid.clear()
                roleLivenessByCid.clear()
                refreshRemoteParticipants()
            }
            return
        }
        mediaLivenessEmitInFlight = true
        val newSamples = mutableMapOf<String, InboundLivenessSample>()
        var remaining = slots.size
        for ((cid, slot) in slots) {
            slot.collectInboundLiveness { sample ->
                handler.post {
                    newSamples[cid] = sample
                    remaining -= 1
                    if (remaining == 0) finalizeInboundLivenessSample(newSamples)
                }
            }
        }
    }

    private fun finalizeInboundLivenessSample(newSamples: Map<String, InboundLivenessSample>) {
        mediaLivenessEmitInFlight = false
        val flowing = mutableListOf<String>()
        val canBroadcast = _diagnostics.value.isSignalingConnected && currentRoomState != null
        for ((cid, sample) in newSamples) {
            if (canBroadcast) {
                val previousBytes = lastInboundBytesByCid[cid]
                if (previousBytes != null && sample.inboundBytes > previousBytes) flowing.add(cid)
                lastInboundBytesByCid[cid] = sample.inboundBytes
            }
            val roleBytes = sample.roleBytes
            val previous = lastInboundRoleBytesByCid[cid]
            roleLivenessByCid[cid] = RoleLiveness(
                camera = previous != null && roleBytes.cameraBytes > previous.cameraBytes,
                content = previous != null && roleBytes.contentBytes > previous.contentBytes,
            )
            lastInboundRoleBytesByCid[cid] = roleBytes
        }
        val activeCids = peerSlots.keys
        if (canBroadcast) {
            val stale = lastInboundBytesByCid.keys.filterNot { activeCids.contains(it) }
            for (cid in stale) lastInboundBytesByCid.remove(cid)
        }
        val staleBytes = lastInboundRoleBytesByCid.keys.filterNot { activeCids.contains(it) }
        for (cid in staleBytes) lastInboundRoleBytesByCid.remove(cid)
        val staleLiveness = roleLivenessByCid.keys.filterNot { activeCids.contains(it) }
        for (cid in staleLiveness) roleLivenessByCid.remove(cid)
        refreshRemoteParticipants()

        if (!canBroadcast) return
        // Emit even when `flowing` is empty so the server knows this client is
        // still a fresh reporter that sees no media from suspended peers.
        val payload = JSONObject().apply { put("cids", JSONArray(flowing)) }
        signalingProvider.broadcast("media_liveness", payload)
        mediaLivenessEmitCount += 1
    }

    /**
     * Latest cached per-role inbound liveness for a peer, or both false when no
     * sample has been taken yet. Read synchronously while assembling participant
     * state (mirrors web's `getRoleLiveness`).
     */
    private fun roleLivenessFor(cid: String): RoleLiveness =
        roleLivenessByCid[cid] ?: RoleLiveness()

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
                "Post-reconnect snapshot timeout after ${WebRtcResilienceConstants.EPOCH_RESYNC_TIMEOUT_MS}ms; recovering peers against last-known peer map",
            )
        }
        iceRestartCallsFromGate += 1
        peerNegotiationEngine.handleSignalingReconnect()
        // Re-broadcast current media state (incl. held) so peers that missed the
        // original message converge after a reconnect (multi-call Phase 1).
        if (sessionActivated || mediaRole == CallMediaRole.HELD) broadcastLocalMediaState()
    }

    private fun cancelPostReconnectResync() {
        pendingPostReconnectResync = false
        handler.removeCallbacks(postReconnectResyncTimeoutRunnable)
    }

    // --- Internal: Cleanup ---

    /**
     * Finalize the quality summary and snapshot it so it survives teardown.
     * Must run BEFORE [resetResources]/`statsPoller.stop()`. Idempotent —
     * the first call wins.
     */
    private fun finalizeQuality() {
        if (_qualitySummary != null) return
        qualityTracker.finalize(clock.monotonicMs())
        _qualitySummary = qualityTracker.summarize()
    }

    /**
     * Emit `reconnectFailed` for a call that reached InCall
     * when the local termination was driven by a concrete recovery-abandonment
     * path — classified from the original signaling **code** via the shared
     * [ReconnectReason] table (join hard-timeout / invalid-or-expired token /
     * connection-failed / transport-exhaustion only). Arbitrary server errors
     * (BAD_REQUEST, etc.) map to null and emit nothing. Never for user hangup
     * or remote-ended. No-op once the tracker is finalized.
     */
    private fun maybeReportReconnectFailed(serverCode: String?) {
        if (!qualityTracker.hasStartedSampling()) return
        val reason = ReconnectReason.reasonForCode(serverCode) ?: return
        qualityTracker.reportReconnectFailed(reason)
    }

    private fun cleanupCall(reason: EndReason): Job {
        finalizeQuality()
        updateState(_state.value.copy(phase = CallPhase.Ending))
        if (_diagnostics.value.isScreenSharing) webRtcEngine.stopScreenShare()
        val deactivationJob = resetResources(clearRecovery = true)
        updateState(CallState(phase = CallPhase.Idle))
        delegate?.invoke()?.onSessionEnded(this, reason)
        return deactivationJob
    }

    private fun resetResources(clearRecovery: Boolean = false): Job {
        joinFlowCoordinator.reset()
        peerNegotiationEngine.resetAll()
        iceFetchGeneration += 1
        callAudioSessionController.deactivate()
        val deactivationJob = audioCoordinatorScope.launch {
            try {
                withTimeout(WebRtcResilienceConstants.AUDIO_COORDINATOR_TIMEOUT_MS) {
                    audioCoordinatorMutex.withLock {
                        audioCoordinator.deactivateCallSession()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Audio session deactivation timed out")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to deactivate audio session: ${e.message}")
            }
        }
        audioCoordinatorDeactivationJob = deactivationJob
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
        remoteContentStates.clear(); remoteContentRevisions.clear()
        remoteCapabilities.clear(); remoteMediaPolicies.clear()
        localContentRevision = 0L
        connectionStatusTracker.cancelTimer()
        userPreferredVideoEnabled = videoCaptureSupported && config.defaultVideoEnabled; isVideoPausedByProximity = false
        reconnectToken = null; reconnectTokenTTLMs = null; reconnectRecoveryPending = false; hasInitialIceServers = false
        cancelPostReconnectResync()
        clearAllRemoteSuspensionTracking()
        stopMediaLivenessTimer()
        stopOutboundMediaWatchdog()
        lastInboundBytesByCid.clear()
        lastInboundRoleBytesByCid.clear()
        roleLivenessByCid.clear()
        mediaLivenessEmitInFlight = false
        localSuspendedSinceMs = null
        sessionStartTs = null
        sessionActivated = false
        localMediaReadyForNegotiation = false
        // Release the process-wide foreground lease (and DIRECT mode claim) this
        // session held, so a subsequent direct join can acquire it (single-call
        // join/leave round-trips through the arbiter). Idempotent: a null token or
        // an already-released token is a safe no-op. resetResources runs on the
        // main thread, so the arbiter's main-thread assert holds.
        releaseForegroundLeaseAndMode()
        // Reset multi-call role/activation/actual to defaults for a fresh start
        // (desired* survive into the next start as user intent defaults).
        mediaRole = CallMediaRole.FOREGROUND
        mediaActivationState = MediaActivationState.ACTIVE
        actualAudioPublished = false
        actualVideoPublished = false
        playbackDuckingActive = false
        externalAudioMuted = false
        routeInputAvailable = true
        if (clearRecovery) recoveryStorage.clear()
        providerScope.coroutineContext.cancelChildren()
        updateDiagnostics(CallDiagnostics())
        return deactivationJob
    }

    private fun cancelAudioCoordinatorScopeAfter(deactivationJob: Job?) {
        if (deactivationJob?.isActive == true) {
            deactivationJob.invokeOnCompletion {
                runOnMain { cancelAudioCoordinatorScope() }
            }
        } else {
            cancelAudioCoordinatorScope()
        }
    }

    private fun cancelAudioCoordinatorScope() {
        stopAudioCoordinatorCollectors()
        audioCoordinatorScope.cancel()
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
        val ttlMs = reconnectTokenTTLMs ?: WebRtcResilienceConstants.RECONNECT_TOKEN_TTL_FALLBACK_MS
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
        // Core Invariant 2: a held call owns NO capture via ANY path. This sink is
        // reachable from the audio-environment callbacks (proximity / route
        // changes) which fire independently of the user-toggle guards, so guard
        // HERE (the media-applying sink), not only at the toggle entry points. Keep
        // the desired intent (set by the toggles), force local/actual video false,
        // and return WITHOUT calling the engine or broadcasting. Resume
        // (applyForegroundRoleInternal) reacquires per the desired mode (FIX A-2).
        if (isHeld) {
            actualVideoPublished = false
            if (_state.value.localVideoEnabled || _state.value.localCameraEnabled) {
                updateState(_state.value.copy(localVideoEnabled = false, localCameraEnabled = false))
            }
            return
        }
        val shouldPause = callAudioSessionController.shouldPauseVideoForProximity(_diagnostics.value.isScreenSharing)
        isVideoPausedByProximity = shouldPause
        val requestedEnabled = userPreferredVideoEnabled && !shouldPause
        val effectiveEnabled = webRtcEngine.toggleVideo(requestedEnabled)
        // Foreground actual reflects the effective capture state (FIX A-4: keep
        // actual* in sync on foreground toggles, not only at start/resume/hold).
        actualVideoPublished = effectiveEnabled
        if (_state.value.localVideoEnabled != effectiveEnabled) {
            // `localVideoEnabled` remains the camera-specific public signal in
            // independent mode; `localContent` carries screen-share state.
            updateState(_state.value.copy(localVideoEnabled = effectiveEnabled, localCameraEnabled = effectiveEnabled))
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

    /**
     * Request routing to a coordinator-published audio device.
     *
     * The call is asynchronous; failures are logged and the current route is left unchanged.
     */
    fun selectAudioDevice(device: AudioDevice) {
        assertMainThread()
        providerScope.launch {
            try {
                audioCoordinatorMutex.withLock {
                    if (!sessionActivated) return@withLock
                    audioCoordinator.applyRouting(device)
                }
            } catch (e: Exception) {
                logger?.log(SerenadaLogLevel.ERROR, "Audio", "Failed to apply routing to device ${device.displayName}: ${e.message}")
            }
        }
    }

    /**
     * Set the user-requested microphone mute state.
     *
     * The effective mute state may still be true when external audio is active or no input route is available.
     */
    fun setMicMuted(muted: Boolean) {
        assertMainThread()
        // Track user intent so a hold/resume cycle restores it (Phase 1).
        desiredAudioEnabled = !muted
        // While held this session owns no capture (Core Invariant 2): record the
        // desired intent ONLY — no capture, no coordinator call, no broadcast.
        // Resume (applyForegroundRoleInternal) applies the latest desired intent.
        if (isHeld) return
        userMuted = muted
        updateEffectiveMicState()
        providerScope.launch {
            runCatching {
                audioCoordinatorMutex.withLock {
                    if (!sessionActivated) return@withLock
                    audioCoordinator.setMicMuted(muted)
                }
            }.onFailure { e ->
                logger?.log(SerenadaLogLevel.ERROR, "Audio", "Failed to set mic muted state on coordinator to $muted: ${e.message}")
            }
        }
    }

    private fun updateEffectiveMicState() {
        // Core Invariant 2: a held call owns NO mic capture via ANY path. This sink
        // is reachable from the audio-environment callbacks (route loss / external
        // audio), so guard HERE — a held call's `sessionActivated` is already false
        // (capture stays released, no broadcast), and this explicit branch also
        // forces `actualAudioPublished` false and keeps the public mute flags in
        // sync without ever re-enabling capture (FIX A-2 audit). Resume re-derives
        // the effective state once foreground.
        if (isHeld) {
            actualAudioPublished = false
            _isMicMuted.value = userMuted || externalAudioMuted || !routeInputAvailable
            _isMicMutedByExternalAudio.value = externalAudioMuted
            return
        }
        val effectiveEnabled = !userMuted && !externalAudioMuted && routeInputAvailable
        // The engine ENSURES a mic track exists when enabling (e.g. unmuting after
        // a muted hold was resumed muted, which left the mic released) and reports
        // the EFFECTIVE publish state — a live track backing the broadcast. Publish
        // only that, never a live audio state backed by a null track (FIX P5).
        val enginePublishedAudio = if (sessionActivated) {
            webRtcEngine.toggleAudio(effectiveEnabled)
        } else {
            false
        }
        // Foreground actual reflects the effective publish state (FIX A-4: keep
        // actual* in sync on foreground toggles, not only at start/resume/hold).
        actualAudioPublished = enginePublishedAudio
        updateState(_state.value.copy(localAudioEnabled = enginePublishedAudio))
        _isMicMuted.value = userMuted || externalAudioMuted || !routeInputAvailable
        _isMicMutedByExternalAudio.value = externalAudioMuted
        if (sessionActivated) {
            broadcastLocalMediaState()
        }
    }

    private fun handleCoordinatorEvent(event: AudioCoordinatorEvent) {
        if (!sessionActivated && event !is AudioCoordinatorEvent.AvailableDevicesChanged) return
        when (event) {
            is AudioCoordinatorEvent.AvailableDevicesChanged -> {
                _availableAudioDevices.value = event.devices
            }
            is AudioCoordinatorEvent.EffectiveRouteChanged -> {
                routeInputAvailable = (event.input != null)
                _currentAudioDevice.value = event.output
                updateEffectiveMicState()
                applyLocalVideoPreference()
            }
            is AudioCoordinatorEvent.ExternalAudioStarted -> {
                if (config.audioIntent.muteDuringExternalAudio) {
                    externalAudioMuted = true
                    updateEffectiveMicState()
                }
                if (config.audioIntent.duckDuringExternalAudio) {
                    playbackDuckingActive = true
                    peerSlots.values.forEach { it.duckPlayback(true) }
                }
            }
            is AudioCoordinatorEvent.ExternalAudioEnded -> {
                externalAudioMuted = false
                updateEffectiveMicState()
                if (playbackDuckingActive) {
                    playbackDuckingActive = false
                    peerSlots.values.forEach { it.duckPlayback(false) }
                }
            }
            is AudioCoordinatorEvent.PlaybackDuckingStarted -> {
                if (config.audioIntent.duckDuringExternalAudio) {
                    playbackDuckingActive = true
                    peerSlots.values.forEach { it.duckPlayback(true) }
                }
            }
            is AudioCoordinatorEvent.PlaybackDuckingEnded -> {
                if (playbackDuckingActive) {
                    playbackDuckingActive = false
                    peerSlots.values.forEach { it.duckPlayback(false) }
                }
            }
        }
    }

    private fun handleError(error: CallError) {
        // Local failures (audio/media startup) are not recovery-abandonment
        // paths — no signaling code, so no reconnectFailed event.
        maybeReportReconnectFailed(null)
        finalizeQuality()
        resetResources()
        updateState(
            CallState(
                phase = CallPhase.Error,
                error = error,
                signalingState = SignalingState.Failed(error),
            )
        )
        delegate?.invoke()?.onSessionEnded(this, EndReason.Error(error))
    }

    private fun isPlausibleJoinedAtMs(joinedAtMs: Long, nowMs: Long): Boolean {
        return joinedAtMs >= PLAUSIBLE_EPOCH_MS &&
            joinedAtMs <= nowMs + JOINED_AT_FUTURE_SKEW_MS
    }

    private companion object {
        const val TAG = "SerenadaSession"
        const val CPU_WAKE_LOCK_TAG = "serenada:call-cpu"
        const val PLAUSIBLE_EPOCH_MS = 946_684_800_000L // 2000-01-01T00:00:00Z
        const val JOINED_AT_FUTURE_SKEW_MS = 5L * 60L * 1000L
        // Background duration that triggers a foreground force-ping. Anything
        // shorter is short enough that pings would have noticed the failure on
        // their own; longer is the OS window where Doze / process freeze may
        // have killed the WS.
        const val FOREGROUND_RESUME_MIN_BACKGROUND_MS = 5_000L
    }
}

private class CustomAudioCoordinatorAdapter(
    private val coordinator: SerenadaAudioCoordinator,
    private val proximityMonitoringEnabled: Boolean,
    private val sensorManager: SensorManager?,
    private val proximitySensor: Sensor?,
    private val handler: Handler,
    private val onAudioEnvironmentChanged: () -> Unit
) : SessionAudioController {
    private var proximityMonitoringActive = false
    private var isProximityNear = false

    private val proximitySensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val maxRange = proximitySensor?.maximumRange ?: return
            val distance = event.values.firstOrNull() ?: return
            val near = distance < maxRange
            if (near == isProximityNear) return
            isProximityNear = near
            onAudioEnvironmentChanged()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun activate() {
        if (proximityMonitoringEnabled) {
            startProximityMonitoring()
        }
    }

    override fun deactivate() {
        stopProximityMonitoring()
    }

    override fun shouldPauseVideoForProximity(isScreenSharing: Boolean): Boolean {
        return proximityMonitoringActive && isProximityNear && !isScreenSharing && !isBluetoothHeadsetConnected()
    }

    private fun isBluetoothHeadsetConnected(): Boolean {
        val currentDevice = coordinator.effectiveOutputDevice.value
        return currentDevice?.kind is AudioDeviceKind.Bluetooth
    }

    private fun startProximityMonitoring() {
        if (proximityMonitoringActive) return
        val manager = sensorManager ?: return
        val sensor = proximitySensor ?: return
        val registered = runCatching {
            manager.registerListener(
                proximitySensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                handler
            )
        }.getOrElse { false }
        if (registered) {
            proximityMonitoringActive = true
            isProximityNear = false
        }
    }

    private fun stopProximityMonitoring() {
        if (!proximityMonitoringActive) {
            isProximityNear = false
            return
        }
        runCatching {
            sensorManager?.unregisterListener(proximitySensorListener)
        }
        proximityMonitoringActive = false
        isProximityNear = false
    }
}
