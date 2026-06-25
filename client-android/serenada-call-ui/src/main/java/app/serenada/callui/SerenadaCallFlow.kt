package app.serenada.callui

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import app.serenada.core.CallDiagnostics
import app.serenada.core.CallState
import app.serenada.core.SerenadaConfig
import app.serenada.core.SerenadaCore
import app.serenada.core.SerenadaSession
import app.serenada.core.SerenadaTransport
import app.serenada.core.SnapshotError
import app.serenada.core.SnapshotResult
import app.serenada.core.SnapshotSource
import app.serenada.core.call.AudioDevice
import app.serenada.core.call.CallPhase
import app.serenada.core.call.LocalCameraMode
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import kotlin.math.roundToInt

private val FRONTLINE_CAMERA_MODES =
    listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE, LocalCameraMode.COMPOSITE)

/**
 * Pre-built call flow that manages the full call lifecycle.
 *
 * Provide either a [url] (URL-first) or a [session] (session-first) to start a call.
 * When a [url] is provided, the composable creates and owns the [SerenadaSession] internally.
 * When a [session] is provided, the caller retains ownership and is responsible for closing it.
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
 * @param onEndCall Called when the user taps the end-call button. When `null`, the session leaves directly.
 *   When provided, the host owns any cleanup and dismissal for that button path.
 * @param onStartScreenShare Called with the [MediaProjection][android.media.projection.MediaProjection]
 *   consent intent when the user starts screen sharing. Use this to start a foreground service with
 *   [FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION][android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]
 *   before the projection begins. When `null`, the session handles screen sharing directly.
 * @param onStopScreenShare Called when the user stops screen sharing. Use this to downgrade the
 *   foreground service type. When `null`, the session handles it directly.
 * @param onDismiss Called when the call ends and the UI should be dismissed, excluding a custom
 *   [onEndCall] button path.
 */
@Composable
fun SerenadaCallFlow(
    url: String? = null,
    session: SerenadaSession? = null,
    config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
    theme: SerenadaCallFlowTheme = SerenadaCallFlowTheme(),
    roomName: String? = null,
    initialRemoteVideoFitCover: Boolean = true,
    // Independent screen share (Phase 4b). The url-first owned session uses default
    // config flags (off); a host that builds its own session with
    // enableIndependentContentVideo=true must echo the flag here, or the prebuilt UI
    // resolves every share as LEGACY and never renders the remote content tile (the
    // SDK still negotiates + receives the content track; only the UI was gated off).
    independentContentEnabled: Boolean = false,
    videoMediaEnabled: Boolean = true,
    strings: Map<SerenadaString, String>? = null,
    onShareLink: (() -> Unit)? = null,
    onInviteToRoom: (() -> Unit)? = null,
    onRemoteVideoFitChanged: ((Boolean) -> Unit)? = null,
    onEndCall: (() -> Unit)? = null,
    onStartScreenShare: ((Intent) -> Unit)? = null,
    onStopScreenShare: (() -> Unit)? = null,
    onSnapshotCaptured: ((SnapshotResult) -> Unit)? = null,
    onSnapshotError: ((SnapshotError) -> Unit)? = null,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    val isFrontlineVariant = config.uiVariant == SerenadaCallUiVariant.Frontline
    val ownedSession =
        remember(url, session, context.applicationContext) {
            session ?: url
                ?.takeIf { it.isNotBlank() }
                ?.let { callUrl ->
                    SerenadaCore(
                        config = SerenadaConfig(
                            serverHost = resolveServerHost(callUrl),
                            defaultVideoEnabled = !isFrontlineVariant,
                            transports = defaultTransports(),
                            cameraModes = when {
                                !config.videoEnabled -> emptyList()
                                isFrontlineVariant -> FRONTLINE_CAMERA_MODES
                                else -> null
                            },
                        ),
                        context = context.applicationContext,
                    ).join(callUrl)
                }
        }

    DisposableEffect(ownedSession, session) {
        onDispose {
            if (session == null) ownedSession?.close()
        }
    }

    val activeSession = ownedSession ?: return
    val state by activeSession.state.collectAsState()
    val diagnostics by activeSession.diagnostics.collectAsState()
    val availableAudioDevices by activeSession.availableAudioDevices.collectAsState()
    val currentAudioDevice by activeSession.currentAudioDevice.collectAsState()
    var pendingPermissions by remember(activeSession) { mutableStateOf<List<app.serenada.core.MediaCapability>?>(null) }
    var pendingPermissionPurpose by remember(activeSession) { mutableStateOf<PermissionRequestPurpose?>(null) }
    var hasStarted by remember(activeSession) { mutableStateOf(false) }
    var skipNextIdleDismiss by remember(activeSession) { mutableStateOf(false) }

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
            if (skipNextIdleDismiss) {
                skipNextIdleDismiss = false
            } else {
                onDismiss()
            }
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

    // Independent-content / video-media flags come from the caller (defaulting
    // off / on for the url-first owned-session path, byte-identical to legacy). A
    // host that builds its own session with enableIndependentContentVideo=true
    // passes independentContentEnabled=true so the UI renders the dedicated
    // content tile instead of the legacy single-video-as-content path.
    val uiState = rememberCallUiState(
        state = state,
        diagnostics = diagnostics,
        independentContentEnabled = independentContentEnabled,
        videoMediaEnabled = videoMediaEnabled,
    )
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
        availableAudioDevices = availableAudioDevices,
        currentAudioDevice = currentAudioDevice,
        onToggleAudio = { activeSession.toggleAudio() },
        onToggleVideo = { activeSession.toggleVideo() },
        onFlipCamera = { activeSession.flipCamera() },
        onToggleFlashlight = { activeSession.toggleFlashlight() },
        onLocalPinchZoom = { scaleFactor -> activeSession.adjustLocalCameraZoom(scaleFactor) },
        onEndCall = onEndCall?.let { customEndCall ->
            {
                skipNextIdleDismiss = true
                customEndCall()
            }
        } ?: { activeSession.leave() },
        onSelectAudioDevice = { device -> activeSession.selectAudioDevice(device) },
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
        attachRemoteContentSinkForCid = { cid, sink -> activeSession.attachRemoteContentRenderer(sink, cid) },
        detachRemoteContentSinkForCid = { cid, sink -> activeSession.detachRemoteContentRenderer(sink, cid) },
        attachLocalContentSink = { sink -> activeSession.attachLocalContentRenderer(sink) },
        detachLocalContentSink = { sink -> activeSession.detachLocalContentRenderer(sink) },
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
 * @param availableAudioDevices Coordinator-published output routes available to the call UI.
 * @param currentAudioDevice Currently selected or active output route, if known.
 * @param onToggleAudio Called when the user toggles the microphone.
 * @param onToggleVideo Called when the user toggles the camera.
 * @param onFlipCamera Called when the user cycles the camera mode.
 * @param onToggleFlashlight Called when the user toggles the flashlight.
 * @param onLocalPinchZoom Called with the scale factor when the user pinch-zooms the local video.
 * @param onEndCall Called when the user taps the end-call button.
 * @param onSelectAudioDevice Called when the user picks an audio route.
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
    availableAudioDevices: List<AudioDevice> = emptyList(),
    currentAudioDevice: AudioDevice? = null,
    onToggleAudio: () -> Unit,
    onToggleVideo: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleFlashlight: () -> Unit = {},
    onLocalPinchZoom: (Float) -> Unit = {},
    onEndCall: () -> Unit,
    onSelectAudioDevice: (AudioDevice) -> Unit = {},
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
    attachRemoteContentSinkForCid: (String, VideoSink) -> Unit = { _, _ -> },
    detachRemoteContentSinkForCid: (String, VideoSink) -> Unit = { _, _ -> },
    attachLocalContentSink: (VideoSink) -> Unit = {},
    detachLocalContentSink: (VideoSink) -> Unit = {},
    onSnapshotRequested: ((SnapshotSource) -> Unit)? = null,
    onDismiss: () -> Unit = {},
) {
    var systemPipSourceRect by remember { mutableStateOf<Rect?>(null) }
    val isSystemPictureInPicture =
        rememberSystemPictureInPictureMode(
            enabled = config.systemPictureInPictureEnabled,
            uiState = uiState,
            sourceRect = systemPipSourceRect,
            onPictureInPictureDismissRequested = onDismiss,
        )
    val systemPipModifier =
        androidx.compose.ui.Modifier
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                systemPipSourceRect =
                    Rect(
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    )
            }

    androidx.compose.foundation.layout.Box(modifier = systemPipModifier) {
        if (config.uiVariant == SerenadaCallUiVariant.Frontline) {
            FrontlineCallScreen(
                uiState = uiState,
                roomShareUrl = roomShareUrl,
                eglContext = eglContext,
                config = config,
                theme = theme,
                strings = strings,
                availableAudioDevices = availableAudioDevices,
                currentAudioDevice = currentAudioDevice,
                isSystemPictureInPicture = isSystemPictureInPicture,
                onToggleAudio = onToggleAudio,
                onToggleVideo = onToggleVideo,
                onFlipCamera = onFlipCamera,
                onToggleFlashlight = onToggleFlashlight,
                onLocalPinchZoom = onLocalPinchZoom,
                onEndCall = onEndCall,
                onSelectAudioDevice = onSelectAudioDevice,
                onShareLink = onShareLink,
                onInviteToRoom = onInviteToRoom,
                onStartScreenShare = onStartScreenShare,
                onStopScreenShare = onStopScreenShare,
                attachLocalRenderer = attachLocalRenderer,
                detachLocalRenderer = detachLocalRenderer,
                attachLocalSink = attachLocalSink,
                detachLocalSink = detachLocalSink,
                attachRemoteSinkForCid = attachRemoteSinkForCid,
                detachRemoteSinkForCid = detachRemoteSinkForCid,
                attachRemoteSink = attachRemoteSink,
                detachRemoteSink = detachRemoteSink,
                attachRemoteContentSinkForCid = attachRemoteContentSinkForCid,
                detachRemoteContentSinkForCid = detachRemoteContentSinkForCid,
                attachLocalContentSink = attachLocalContentSink,
                detachLocalContentSink = detachLocalContentSink,
                initialRemoteVideoFitCover = initialRemoteVideoFitCover,
                onRemoteVideoFitChanged = onRemoteVideoFitChanged,
                onSnapshotRequested = onSnapshotRequested,
            )
        } else {
            CallScreen(
                uiState = uiState,
                roomShareUrl = roomShareUrl,
                eglContext = eglContext,
                initialRemoteVideoFitCover = initialRemoteVideoFitCover,
                config = config,
                theme = theme,
                strings = strings,
                isSystemPictureInPicture = isSystemPictureInPicture,
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
                attachRemoteContentSinkForCid = attachRemoteContentSinkForCid,
                detachRemoteContentSinkForCid = detachRemoteContentSinkForCid,
                attachLocalContentSink = attachLocalContentSink,
                detachLocalContentSink = detachLocalContentSink,
                onSnapshotRequested = onSnapshotRequested,
            )
        }
    }
}

@Composable
private fun rememberCallUiState(
    state: CallState,
    diagnostics: CallDiagnostics,
    independentContentEnabled: Boolean,
    videoMediaEnabled: Boolean,
): CallUiState {
    return remember(state, diagnostics, independentContentEnabled, videoMediaEnabled) {
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
            callStartedAtMs = state.callStartedAtMs,
            localAudioEnabled = state.localAudioEnabled,
            localVideoEnabled = state.localVideoEnabled,
            localCameraEnabled = state.localCameraEnabled,
            localContent = state.localContent,
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
            independentContentEnabled = independentContentEnabled,
            videoMediaEnabled = videoMediaEnabled,
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
