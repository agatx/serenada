package app.serenada.callui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.serenada.core.CallDiagnostics
import app.serenada.core.CallState
import app.serenada.core.SerenadaConfig
import app.serenada.core.SerenadaCore
import app.serenada.core.SerenadaSession
import app.serenada.core.SerenadaTransport
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink

@Composable
fun SerenadaCallFlow(
    url: String? = null,
    session: SerenadaSession? = null,
    config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
    theme: SerenadaCallFlowTheme = SerenadaCallFlowTheme(),
    roomName: String? = null,
    initialRemoteVideoFitCover: Boolean = true,
    strings: Map<SerenadaString, String>? = null,
    onShareLink: (() -> Unit)? = null,
    onInviteToRoom: (() -> Unit)? = null,
    onRemoteVideoFitChanged: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val ownedSession =
        remember(url, session, context.applicationContext) {
            session ?: url
                ?.takeIf { it.isNotBlank() }
                ?.let { callUrl ->
                    SerenadaCore(
                        config = SerenadaConfig(
                            serverHost = resolveServerHost(callUrl),
                            transports = defaultTransports(),
                        ),
                        context = context.applicationContext,
                    ).join(callUrl)
                }
        }

    val activeSession = ownedSession ?: return
    val state by activeSession.state.collectAsState()
    val diagnostics by activeSession.diagnostics.collectAsState()
    var pendingPermissions by remember(activeSession) { mutableStateOf<List<app.serenada.core.MediaCapability>?>(null) }
    var hasStarted by remember(activeSession) { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            pendingPermissions = null
            if (granted) {
                activeSession.resumeJoin()
            } else {
                activeSession.cancelJoin()
            }
        }

    DisposableEffect(activeSession, activity) {
        val previousHandler = activeSession.onPermissionsRequired
        if (previousHandler == null && activity != null) {
            activeSession.onPermissionsRequired = { permissions ->
                pendingPermissions = permissions
            }
        }
        onDispose {
            if (activeSession.onPermissionsRequired != null && previousHandler == null) {
                activeSession.onPermissionsRequired = null
            }
        }
    }

    LaunchedEffect(state.phase, state.requiredPermissions, activity, activeSession) {
        if (state.phase == app.serenada.core.call.CallPhase.AwaitingPermissions &&
            state.requiredPermissions.isNotEmpty() &&
            pendingPermissions == null &&
            activeSession.onPermissionsRequired == null
        ) {
            pendingPermissions = state.requiredPermissions
        }
        if (state.phase != app.serenada.core.call.CallPhase.Idle) {
            hasStarted = true
        } else if (hasStarted) {
            onDismiss()
        }
    }

    LaunchedEffect(pendingPermissions, activity) {
        val requested = pendingPermissions ?: return@LaunchedEffect
        val hostActivity = activity ?: run {
            activeSession.cancelJoin()
            pendingPermissions = null
            return@LaunchedEffect
        }
        val androidPermissions = SerenadaPermissions.permissionsFor(requested)
        if (androidPermissions.isEmpty() || SerenadaPermissions.areGranted(hostActivity)) {
            pendingPermissions = null
            activeSession.resumeJoin()
        } else {
            permissionLauncher.launch(androidPermissions)
        }
    }

    val uiState = rememberCallUiState(state, diagnostics)
    val roomId = state.roomId ?: activeSession.roomId
    val serverHost = activeSession.host
    val internalConfig =
        if (config.inviteControlsEnabled && onInviteToRoom == null) {
            config.copy(inviteControlsEnabled = false)
        } else {
            config
        }

    SerenadaCallFlow(
        uiState = uiState,
        roomId = roomId,
        serverHost = serverHost,
        eglContext = activeSession.eglContext(),
        roomName = roomName,
        initialRemoteVideoFitCover = initialRemoteVideoFitCover,
        config = internalConfig,
        theme = theme,
        strings = strings,
        onToggleAudio = { activeSession.toggleAudio() },
        onToggleVideo = { activeSession.toggleVideo() },
        onFlipCamera = { activeSession.flipCamera() },
        onToggleFlashlight = { activeSession.toggleFlashlight() },
        onLocalPinchZoom = { scaleFactor -> activeSession.adjustLocalCameraZoom(scaleFactor) },
        onEndCall = { activeSession.leave() },
        onShareLink = onShareLink,
        onInviteToRoom = { onInviteToRoom?.invoke() },
        onRemoteVideoFitChanged = onRemoteVideoFitChanged,
        onStartScreenShare = { intent -> activeSession.startScreenShare(intent) },
        onStopScreenShare = { activeSession.stopScreenShare() },
        attachLocalRenderer = { renderer, events -> activeSession.attachLocalRenderer(renderer, events) },
        detachLocalRenderer = { renderer -> activeSession.detachLocalRenderer(renderer) },
        attachLocalSink = { sink -> activeSession.attachLocalSink(sink) },
        detachLocalSink = { sink -> activeSession.detachLocalSink(sink) },
        attachRemoteRenderer = { renderer, events -> activeSession.attachRemoteRenderer(renderer, events) },
        detachRemoteRenderer = { renderer -> activeSession.detachRemoteRenderer(renderer) },
        attachRemoteSinkForCid = { cid, sink -> activeSession.attachRemoteSinkForCid(cid, sink) },
        detachRemoteSinkForCid = { cid, sink -> activeSession.detachRemoteSinkForCid(cid, sink) },
        attachRemoteSink = { sink -> activeSession.attachRemoteSink(sink) },
        detachRemoteSink = { sink -> activeSession.detachRemoteSink(sink) },
        onDismiss = onDismiss,
    )
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun SerenadaCallFlow(
    uiState: CallUiState,
    roomId: String,
    serverHost: String,
    eglContext: EglBase.Context,
    roomName: String? = null,
    rendererProvider: CallRendererProvider? = null,
    initialRemoteVideoFitCover: Boolean = true,
    config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
    theme: SerenadaCallFlowTheme = SerenadaCallFlowTheme(),
    strings: Map<SerenadaString, String>? = null,
    onToggleAudio: () -> Unit,
    onToggleVideo: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleFlashlight: () -> Unit = {},
    onLocalPinchZoom: (Float) -> Unit = {},
    onEndCall: () -> Unit,
    onShareLink: (() -> Unit)? = null,
    onInviteToRoom: () -> Unit = {},
    onRemoteVideoFitChanged: ((Boolean) -> Unit)? = null,
    onStartScreenShare: (Intent) -> Unit = {},
    onStopScreenShare: () -> Unit = {},
    attachLocalRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachRemoteRenderer: (SurfaceViewRenderer) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    attachRemoteSink: (VideoSink) -> Unit,
    detachRemoteSink: (VideoSink) -> Unit,
    onDismiss: () -> Unit = {},
) {
    CallScreen(
        roomId = roomId,
        uiState = uiState,
        serverHost = serverHost,
        eglContext = eglContext,
        initialRemoteVideoFitCover = initialRemoteVideoFitCover,
        config = config,
        theme = theme,
        strings = strings,
        onToggleAudio = onToggleAudio,
        onToggleVideo = onToggleVideo,
        onFlipCamera = onFlipCamera,
        onToggleFlashlight = onToggleFlashlight,
        onLocalPinchZoom = onLocalPinchZoom,
        onEndCall = onEndCall,
        onShareLink = onShareLink,
        onInviteToRoom = onInviteToRoom,
        onRemoteVideoFitChanged = onRemoteVideoFitChanged,
        onStartScreenShare = onStartScreenShare,
        onStopScreenShare = onStopScreenShare,
        attachLocalRenderer = attachLocalRenderer,
        detachLocalRenderer = detachLocalRenderer,
        attachLocalSink = attachLocalSink,
        detachLocalSink = detachLocalSink,
        attachRemoteRenderer = attachRemoteRenderer,
        detachRemoteRenderer = detachRemoteRenderer,
        attachRemoteSinkForCid = attachRemoteSinkForCid,
        detachRemoteSinkForCid = detachRemoteSinkForCid,
        attachRemoteSink = attachRemoteSink,
        detachRemoteSink = detachRemoteSink,
    )
}

@Composable
private fun rememberCallUiState(
    state: CallState,
    diagnostics: CallDiagnostics,
): CallUiState {
    return remember(state, diagnostics) {
        CallUiState(
            phase = state.phase,
            roomId = state.roomId,
            localCid = state.localCid,
            errorMessageText = state.errorMessage,
            isHost = state.isHost,
            participantCount = state.participantCount,
            localAudioEnabled = state.localAudioEnabled,
            localVideoEnabled = state.localVideoEnabled,
            remoteParticipants = state.remoteParticipants,
            connectionStatus = state.connectionStatus,
            isSignalingConnected = diagnostics.isSignalingConnected,
            iceConnectionState = diagnostics.iceConnectionState.name,
            connectionState = diagnostics.peerConnectionState.name,
            signalingState = diagnostics.rtcSignalingState.name,
            activeTransport = diagnostics.activeTransport,
            realtimeCallStats = diagnostics.realtimeStats,
            isFrontCamera = diagnostics.isFrontCamera,
            isScreenSharing = diagnostics.isScreenSharing,
            localCameraMode = state.localCameraMode,
            isFlashAvailable = diagnostics.isFlashAvailable,
            isFlashEnabled = diagnostics.isFlashEnabled,
            remoteContentCid = diagnostics.remoteContentCid,
            remoteContentType = diagnostics.remoteContentType,
        )
    }
}

private fun resolveServerHost(url: String): String {
    val parsed = runCatching { Uri.parse(url) }.getOrNull()
    return parsed?.authority?.takeIf { it.isNotBlank() } ?: "serenada.app"
}

private fun defaultTransports(): List<SerenadaTransport> {
    return listOf(SerenadaTransport.WS, SerenadaTransport.SSE)
}
