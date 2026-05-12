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
import androidx.compose.runtime.rememberCoroutineScope
import app.serenada.core.CallDiagnostics
import app.serenada.core.CallState
import app.serenada.core.SerenadaConfig
import app.serenada.core.SerenadaCore
import app.serenada.core.SerenadaSession
import app.serenada.core.SerenadaTransport
import app.serenada.core.SnapshotError
import app.serenada.core.SnapshotResult
import app.serenada.core.SnapshotSource
import app.serenada.core.call.CallPhase
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink

/**
 * Pre-built call flow that manages the full call lifecycle.
 *
 * Provide either a [url] (URL-first) or a [session] (session-first) to start a call.
 * When a [url] is provided, the composable creates and owns the [SerenadaSession] internally.
 * When a [session] is provided, the caller retains ownership and is responsible for its lifecycle.
 *
 * @param url Call URL to join. Mutually exclusive with [session].
 * @param session An externally created [SerenadaSession]. Mutually exclusive with [url].
 * @param config Feature flags controlling which UI controls are shown.
 * @param theme Visual customisation (colors, shapes, typography).
 * @param roomName Optional display name shown in the call UI.
 * @param initialRemoteVideoFitCover Whether remote video defaults to cover (crop-to-fill) mode.
 * @param strings Localized string overrides keyed by [SerenadaString].
 * @param onShareLink Called when the user taps the share-link control. Pass `null` to hide it.
 * @param onInviteToRoom Called when the user taps the invite control. Pass `null` to hide it.
 * @param onRemoteVideoFitChanged Called when the user toggles remote video fit/fill mode.
 * @param onStartScreenShare Called with the [MediaProjection][android.media.projection.MediaProjection]
 *   consent intent when the user starts screen sharing. Use this to start a foreground service with
 *   [FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION][android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]
 *   before the projection begins. When `null`, the session handles screen sharing directly.
 * @param onStopScreenShare Called when the user stops screen sharing. Use this to downgrade the
 *   foreground service type. When `null`, the session handles it directly.
 * @param onDismiss Called when the call ends and the UI should be dismissed.
 */
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
    onStartScreenShare: ((Intent) -> Unit)? = null,
    onStopScreenShare: (() -> Unit)? = null,
    onSnapshotCaptured: ((SnapshotResult) -> Unit)? = null,
    onSnapshotError: ((SnapshotError) -> Unit)? = null,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()
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
    var pendingPermissionPurpose by remember(activeSession) { mutableStateOf<PermissionRequestPurpose?>(null) }
    var hasStarted by remember(activeSession) { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            val purpose = pendingPermissionPurpose
            pendingPermissions = null
            pendingPermissionPurpose = null
            if (granted) {
                purpose.applyGrant(activeSession)
            } else if (purpose == PermissionRequestPurpose.Join) {
                activeSession.cancelJoin()
            }
        }

    DisposableEffect(activeSession, activity) {
        val previousHandler = activeSession.onPermissionsRequired
        if (previousHandler == null && activity != null) {
            activeSession.onPermissionsRequired = { permissions ->
                pendingPermissionPurpose = if (activeSession.state.value.phase == CallPhase.AwaitingPermissions) {
                    PermissionRequestPurpose.Join
                } else {
                    PermissionRequestPurpose.EnableVideo
                }
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
        if (state.phase == CallPhase.AwaitingPermissions &&
            state.requiredPermissions.isNotEmpty() &&
            pendingPermissions == null &&
            activeSession.onPermissionsRequired == null
        ) {
            pendingPermissionPurpose = PermissionRequestPurpose.Join
            pendingPermissions = state.requiredPermissions
        }
        if (state.phase != CallPhase.Idle) {
            hasStarted = true
        } else if (hasStarted) {
            onDismiss()
        }
    }

    LaunchedEffect(pendingPermissions, activity) {
        val requested = pendingPermissions ?: return@LaunchedEffect
        val purpose = pendingPermissionPurpose
        val hostActivity = activity ?: run {
            if (purpose == PermissionRequestPurpose.Join) {
                activeSession.cancelJoin()
            }
            pendingPermissions = null
            pendingPermissionPurpose = null
            return@LaunchedEffect
        }
        val androidPermissions = SerenadaPermissions.permissionsFor(requested)
        if (androidPermissions.isEmpty() || SerenadaPermissions.areGranted(hostActivity, androidPermissions)) {
            pendingPermissions = null
            pendingPermissionPurpose = null
            purpose.applyGrant(activeSession)
        } else {
            permissionLauncher.launch(androidPermissions)
        }
    }

    val uiState = rememberCallUiState(state, diagnostics)
    val roomId = state.roomId ?: activeSession.roomId
    val roomShareUrl = activeSession.roomUrl
    val internalConfig =
        if (config.inviteControlsEnabled && onInviteToRoom == null) {
            config.copy(inviteControlsEnabled = false)
        } else {
            config
        }

    SerenadaCallFlow(
        uiState = uiState,
        roomId = roomId,
        roomShareUrl = roomShareUrl,
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
        onStartScreenShare = onStartScreenShare ?: { intent -> activeSession.startScreenShare(intent) },
        onStopScreenShare = onStopScreenShare ?: { activeSession.stopScreenShare() },
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
        onSnapshotRequested = if (config.snapshotEnabled && (onSnapshotCaptured != null || onSnapshotError != null)) {
            { source ->
                coroutineScope.launch {
                    try {
                        val result = activeSession.captureSnapshot(source)
                        onSnapshotCaptured?.invoke(result)
                    } catch (error: SnapshotError) {
                        onSnapshotError?.invoke(error)
                    } catch (error: Throwable) {
                        onSnapshotError?.invoke(SnapshotError.CaptureFailed(error.message ?: error.toString()))
                    }
                }
            }
        } else {
            null
        },
        onDismiss = onDismiss,
    )
}

private enum class PermissionRequestPurpose {
    Join,
    EnableVideo
}

private fun PermissionRequestPurpose?.applyGrant(session: SerenadaSession) {
    when (this) {
        PermissionRequestPurpose.Join -> session.resumeJoin()
        PermissionRequestPurpose.EnableVideo -> session.toggleVideo()
        null -> Unit
    }
}

/**
 * Low-level call flow composable that takes explicit state and callbacks.
 *
 * Use the higher-level [SerenadaCallFlow] overload (with [url] or [session]) for most
 * integrations. This overload is for hosts that manage their own [SerenadaSession] and need
 * full control over every callback and renderer attachment.
 *
 * @param uiState Current call UI state, typically derived from [CallState].
 * @param roomId The room identifier for the active call.
 * @param roomShareUrl Shareable room URL (e.g. `"https://serenada.app/call/<roomId>"`) shown in
 *   the waiting overlay's QR code and share button. Pass `null` (typical when running with a
 *   custom [app.serenada.core.SignalingProvider] and no Serenada-hosted server) to hide both
 *   controls. When using the high-level overload, [SerenadaSession.roomUrl] is forwarded here.
 * @param eglContext Shared EGL context for WebRTC video rendering.
 * @param roomName Optional display name shown in the call UI.
 * @param rendererProvider Custom renderer provider for advanced rendering setups.
 * @param initialRemoteVideoFitCover Whether remote video defaults to cover (crop-to-fill) mode.
 * @param config Feature flags controlling which UI controls are shown.
 * @param theme Visual customisation (colors, shapes, typography).
 * @param strings Localized string overrides keyed by [SerenadaString].
 * @param onToggleAudio Called when the user toggles the microphone.
 * @param onToggleVideo Called when the user toggles the camera.
 * @param onFlipCamera Called when the user cycles the camera mode.
 * @param onToggleFlashlight Called when the user toggles the flashlight.
 * @param onLocalPinchZoom Called with the scale factor when the user pinch-zooms the local video.
 * @param onEndCall Called when the user taps the end-call button.
 * @param onShareLink Called when the user taps the share-link control. Pass `null` to hide it.
 * @param onInviteToRoom Called when the user taps the invite control.
 * @param onRemoteVideoFitChanged Called when the user toggles remote video fit/fill mode.
 * @param onStartScreenShare Called with the MediaProjection consent intent when screen sharing starts.
 * @param onStopScreenShare Called when screen sharing stops.
 * @param attachLocalRenderer Attaches a [SurfaceViewRenderer] for the local video track.
 * @param detachLocalRenderer Detaches the local video renderer.
 * @param attachLocalSink Attaches a [VideoSink] for the local video track.
 * @param detachLocalSink Detaches the local video sink.
 * @param attachRemoteRenderer Attaches a [SurfaceViewRenderer] for the remote video track.
 * @param detachRemoteRenderer Detaches the remote video renderer.
 * @param attachRemoteSinkForCid Attaches a [VideoSink] for a specific remote participant by CID.
 * @param detachRemoteSinkForCid Detaches the video sink for a specific remote participant.
 * @param attachRemoteSink Attaches a [VideoSink] for the remote video track.
 * @param detachRemoteSink Detaches the remote video sink.
 * @param onDismiss Called when the call ends and the UI should be dismissed.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun SerenadaCallFlow(
    uiState: CallUiState,
    roomId: String,
    roomShareUrl: String?,
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
    onSnapshotRequested: ((SnapshotSource) -> Unit)? = null,
    onDismiss: () -> Unit = {},
) {
    CallScreen(
        uiState = uiState,
        roomShareUrl = roomShareUrl,
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
        onSnapshotRequested = onSnapshotRequested,
    )
}

@Composable
private fun rememberCallUiState(
    state: CallState,
    diagnostics: CallDiagnostics,
): CallUiState {
    return remember(state, diagnostics) {
        // Hide presumed-lost remotes from the call grid — the SDK keeps their
        // peer connections open in case they reattach, but the active grid
        // should not display them. Host apps wanting different presentation
        // (e.g., a "connection lost" tile) can read presumedLost off the SDK's
        // CallState directly instead of using SerenadaCallFlow.
        val visibleRemotes = state.remoteParticipants.filterNot { it.presumedLost }
        CallUiState(
            phase = state.phase,
            roomId = state.roomId,
            localCid = state.localCid,
            errorMessageText = state.error?.displayMessage,
            isHost = state.isHost,
            participantCount = 1 + visibleRemotes.size,
            localAudioEnabled = state.localAudioEnabled,
            localVideoEnabled = state.localVideoEnabled,
            localDisplayName = state.localDisplayName,
            localAudioLevel = state.localAudioLevel,
            remoteParticipants = visibleRemotes,
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
            availableCameraModes = state.availableCameraModes,
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
