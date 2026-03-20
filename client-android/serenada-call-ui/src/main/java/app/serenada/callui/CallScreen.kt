package app.serenada.callui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.media.projection.MediaProjectionManager
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import app.serenada.core.layout.CallScene
import app.serenada.core.layout.ContentSource
import app.serenada.core.layout.ContentType
import app.serenada.core.layout.FitMode
import app.serenada.core.layout.Insets
import app.serenada.core.layout.OccupantType
import app.serenada.core.layout.ParticipantRole
import app.serenada.core.layout.SceneParticipant
import app.serenada.core.layout.StageTileSpec
import app.serenada.core.layout.StageRowLayout
import app.serenada.core.layout.StageTileLayout
import app.serenada.core.layout.UserLayoutPrefs
import app.serenada.core.layout.clampStageTileAspectRatio
import app.serenada.core.layout.computeLayout
import app.serenada.core.layout.computeStageLayout
import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.ContentTypeWire
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.RemoteParticipant
import app.serenada.core.call.RealtimeCallStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

private const val PINCH_ZOOM_CHANGE_THRESHOLD = 0.01f

@Composable
internal fun CallScreen(
    roomId: String,
    uiState: CallUiState,
    serverHost: String,
    eglContext: EglBase.Context,
    initialRemoteVideoFitCover: Boolean = true,
    config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
    theme: SerenadaCallFlowTheme = SerenadaCallFlowTheme(),
    strings: Map<SerenadaString, String>? = null,
    onToggleAudio: () -> Unit,
    onToggleVideo: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleFlashlight: () -> Unit,
    onLocalPinchZoom: (Float) -> Unit,
    onEndCall: () -> Unit,
    onInviteToRoom: () -> Unit,
    // Added callbacks for Screen Share
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
    onRemoteVideoFitChanged: ((Boolean) -> Unit)? = null,
    onShareLink: (() -> Unit)? = null,
) {
    // Keep the screen on for the duration of the call
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var areControlsVisible by remember { mutableStateOf(true) }
    var isControlsAutoHideEnabled by remember { mutableStateOf(true) }
    var wereControlsLastHiddenByAutoHide by remember { mutableStateOf(false) }
    var isLocalLarge by rememberSaveable { mutableStateOf(false) }
    var remoteVideoFitCover by rememberSaveable { mutableStateOf(initialRemoteVideoFitCover) }
    var lastFrontCameraState by remember { mutableStateOf(uiState.isFrontCamera) }
    var localAspectRatio by remember { mutableStateOf<Float?>(null) }
    var remoteAspectRatio by remember { mutableStateOf<Float?>(null) }
    val remoteTileAspectRatios = remember { mutableStateMapOf<String, Float>() }
    var pinnedParticipantId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var debugTapTimestampMs by remember { mutableStateOf(0L) }
    var showRecoveringBadge by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val localRenderer = remember { SurfaceViewRenderer(context) }
    val remoteRenderer = remember { SurfaceViewRenderer(context) }
    val localPipRenderer = remember { PipTextureRendererView(context, "local-pip") }
    val localFocusRenderer = remember { PipTextureRendererView(context, "local-focus") }
    val remotePipRenderer = remember { PipTextureRendererView(context, "remote-pip") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val localZoomTransformState = rememberTransformableState { zoomChange, _, _ ->
        if (zoomChange > 0f && abs(zoomChange - 1f) > PINCH_ZOOM_CHANGE_THRESHOLD) {
            onLocalPinchZoom(zoomChange)
        }
    }
    val isWorldOrCompositeMode =
        uiState.localCameraMode == LocalCameraMode.WORLD ||
                uiState.localCameraMode == LocalCameraMode.COMPOSITE
    val isMultiParty = uiState.remoteParticipants.size > 1
    val isLocalPinchZoomEnabled =
        uiState.phase == CallPhase.InCall &&
                isLocalLarge &&
                uiState.localVideoEnabled &&
                !uiState.isScreenSharing &&
                isWorldOrCompositeMode

    // Screen Share Launcher
    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onStartScreenShare(result.data!!)
        }
    }

    val remoteRendererEvents = remember {
        aspectRatioRendererEvents(mainHandler) { ratio -> remoteAspectRatio = ratio }
    }
    val localRendererEvents = remember {
        aspectRatioRendererEvents(mainHandler) { ratio -> localAspectRatio = ratio }
    }

    DisposableEffect(Unit) {
        localPipRenderer.init(eglContext)
        localFocusRenderer.init(eglContext, localRendererEvents)
        remotePipRenderer.init(eglContext)
        onDispose {
            localRenderer.release()
            remoteRenderer.release()
            localPipRenderer.release()
            localFocusRenderer.release()
            remotePipRenderer.release()
        }
    }

    LaunchedEffect(uiState.phase, uiState.connectionStatus) {
        if (uiState.phase != CallPhase.InCall || uiState.connectionStatus != ConnectionStatus.Recovering) {
            showRecoveringBadge = false
            return@LaunchedEffect
        }
        delay(800)
        if (uiState.phase == CallPhase.InCall && uiState.connectionStatus == ConnectionStatus.Recovering) {
            showRecoveringBadge = true
        }
    }

    LaunchedEffect(uiState.remoteParticipants.map { it.cid }) {
        val activeCids = uiState.remoteParticipants.map { it.cid }.toSet()
        remoteTileAspectRatios.keys
            .filter { it !in activeCids }
            .forEach { remoteTileAspectRatios.remove(it) }
        // Auto-unpin if pinned participant left (but not if local is pinned)
        if (pinnedParticipantId != null && pinnedParticipantId != uiState.localCid && pinnedParticipantId !in activeCids) {
            pinnedParticipantId = null
        }
    }

    val showReconnectingBadge =
        uiState.phase == CallPhase.InCall &&
            (uiState.connectionStatus == ConnectionStatus.Retrying || showRecoveringBadge)

    val debugSections =
        remember(
            uiState.isSignalingConnected,
            uiState.activeTransport,
            uiState.iceConnectionState,
            uiState.connectionState,
            uiState.signalingState,
            uiState.roomId,
            uiState.participantCount,
            uiState.connectionStatus,
            uiState.realtimeCallStats
        ) {
            buildDebugPanelSections(
                isConnected = uiState.isSignalingConnected,
                activeTransport = uiState.activeTransport,
                iceConnectionState = uiState.iceConnectionState,
                connectionState = uiState.connectionState,
                signalingState = uiState.signalingState,
                roomParticipantCount = if (uiState.roomId != null) uiState.participantCount else null,
                showReconnecting = uiState.connectionStatus != ConnectionStatus.Connected,
                realtimeStats = uiState.realtimeCallStats
            )
        }

    val toggleControlsVisibility: () -> Unit = {
        if (areControlsVisible) {
            areControlsVisible = false
            wereControlsLastHiddenByAutoHide = false
        } else {
            areControlsVisible = true
            if (wereControlsLastHiddenByAutoHide) {
                isControlsAutoHideEnabled = false
                wereControlsLastHiddenByAutoHide = false
            }
        }
    }

    // Auto-hide controls
    LaunchedEffect(areControlsVisible, uiState.phase, isControlsAutoHideEnabled) {
        if (areControlsVisible && uiState.phase == CallPhase.InCall && isControlsAutoHideEnabled) {
            delay(8000)
            wereControlsLastHiddenByAutoHide = true
            areControlsVisible = false
        }
    }

    // Auto-swap based on camera facing
    LaunchedEffect(uiState.isFrontCamera) {
        if (uiState.isFrontCamera != lastFrontCameraState) {
            // Front -> Back: Swapping to main view for better preview of what we capture
            // Back -> Front: Swapping to PIP to see remote person clearly
            isLocalLarge = !uiState.isFrontCamera
            lastFrontCameraState = uiState.isFrontCamera
        }
    }

    SerenadaTheme(theme) {
        BoxWithConstraints(
            modifier =
                Modifier.fillMaxSize().background(theme.backgroundColor)
                    .testTag("call.screen")
                    .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { toggleControlsVisibility() }
        ) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .align(Alignment.TopStart)
                .testTag("call.participantCount")
                .semantics {
                    contentDescription = uiState.participantCount.toString()
                }
        )

        val controlsAnimationDuration = 320
        val showPip =
            !isMultiParty &&
                    (uiState.phase == CallPhase.InCall ||
                        uiState.phase == CallPhase.Waiting ||
                        uiState.connectionState == "CONNECTED")
        val animatedPipBottomPadding by
        animateDpAsState(
            targetValue = if (areControlsVisible) 160.dp else 48.dp,
            animationSpec = tween(durationMillis = controlsAnimationDuration),
            label = "pip_bottom_padding"
        )
        val pipBackgroundColor = Color(0xFF222222)
        // For a square inset inside rounded corners, bleed-free geometry needs:
        // padding >= radius * (1 - 1/sqrt(2)) ~= 0.293 * radius.
        // Texture-based PIP supports real clipping, so we can use a stronger radius.
        val pipCornerRadius = 12.dp
        val pipContentPadding = 2.5.dp
        val pipInnerCornerRadius =
            if (pipCornerRadius > pipContentPadding) pipCornerRadius - pipContentPadding else 0.dp
        val mainModifier =
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { toggleControlsVisibility() }
        val debugPanelWidth = minOf(maxWidth * 0.92f, 430.dp)
        val debugPanelMaxHeight = (maxHeight - 140.dp).coerceAtLeast(120.dp)

        val pipBaseModifier =
            if (showPip) {
                Modifier.padding(
                    bottom = animatedPipBottomPadding,
                    end = 16.dp
                )
                    .align(Alignment.BottomEnd)
                    .size(100.dp, 150.dp)
                    .zIndex(1f)
            } else {
                Modifier.size(0.dp)
            }

        val pipBackgroundModifier =
            pipBaseModifier.clip(RoundedCornerShape(pipCornerRadius)).background(pipBackgroundColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { isLocalLarge = !isLocalLarge }
        val pipVideoModifier =
            pipBaseModifier.padding(pipContentPadding).clip(RoundedCornerShape(pipInnerCornerRadius))

        val localModifier = if (isLocalLarge) mainModifier else pipVideoModifier
        val remoteModifier = if (isLocalLarge) pipVideoModifier else mainModifier
        if (showPip) {
            Box(modifier = pipBackgroundModifier)
        }

        if (isMultiParty) {
            MultiPartyStage(
                modifier = Modifier.fillMaxSize(),
                remoteParticipants = uiState.remoteParticipants,
                remoteAspectRatios = remoteTileAspectRatios,
                localCid = uiState.localCid,
                localVideoEnabled = uiState.localVideoEnabled,
                localMirror = uiState.isFrontCamera && !uiState.isScreenSharing,
                localCameraMode = uiState.localCameraMode,
                isScreenSharing = uiState.isScreenSharing,
                localAspectRatio = localAspectRatio ?: 0f,
                localPipRenderer = localPipRenderer,
                localFocusRenderer = localFocusRenderer,
                attachLocalSink = attachLocalSink,
                detachLocalSink = detachLocalSink,
                eglContext = eglContext,
                attachRemoteSinkForCid = attachRemoteSinkForCid,
                detachRemoteSinkForCid = detachRemoteSinkForCid,
                bottomPadding = animatedPipBottomPadding,
                remoteContentCid = uiState.remoteContentCid,
                remoteContentType = uiState.remoteContentType,
                remoteVideoFitCover = remoteVideoFitCover,
                onToggleRemoteVideoFit = {
                    val next = !remoteVideoFitCover
                    remoteVideoFitCover = next
                    onRemoteVideoFitChanged?.invoke(next)
                },
                pinnedParticipantId = pinnedParticipantId,
                onPinnedParticipantIdChanged = { pinnedParticipantId = it },
                onTap = toggleControlsVisibility,
                onLocalPinchZoom = onLocalPinchZoom,
                strings = strings,
            )
        } else if (isLocalLarge) {
            val ratio = localAspectRatio ?: 0f
            val containerRatio = if (maxHeight == 0.dp) 1f else maxWidth / maxHeight
            val safeContainerRatio = if (containerRatio > 0f) containerRatio else 1f
            val fitWidth: androidx.compose.ui.unit.Dp
            val fitHeight: androidx.compose.ui.unit.Dp
            if (ratio > 0f) {
                if (safeContainerRatio > ratio) {
                    fitHeight = maxHeight
                    fitWidth = maxHeight * ratio
                } else {
                    fitWidth = maxWidth
                    fitHeight = maxWidth / ratio
                }
            } else {
                fitWidth = maxWidth
                fitHeight = maxHeight
            }
            if (uiState.localVideoEnabled) {
                val localLargeModifier =
                    localModifier
                        .clipToBounds()
                        .then(
                            if (isLocalPinchZoomEnabled) {
                                Modifier.transformable(state = localZoomTransformState)
                            } else {
                                Modifier
                            }
                        )
                Box(modifier = localLargeModifier) {
                    VideoSurface(
                        modifier =
                            Modifier.size(fitWidth, fitHeight)
                                .align(Alignment.Center),
                        renderer = localRenderer,
                        onAttach = { renderer -> attachLocalRenderer(renderer, localRendererEvents) },
                        onDetach = detachLocalRenderer,
                        mirror = uiState.isFrontCamera && !uiState.isScreenSharing,
                        contentScale = ContentScale.Fit,
                        isMediaOverlay = false
                    )
                }
            }
            if (uiState.remoteVideoEnabled) {
                TextureVideoSurface(
                    modifier = remoteModifier,
                    renderer = remotePipRenderer,
                    onAttach = attachRemoteSink,
                    onDetach = detachRemoteSink,
                    mirror = false,
                    contentScale = ContentScale.Crop
                )
            }
        } else {
            val ratio = remoteAspectRatio ?: 0f
            val containerRatio = if (maxHeight == 0.dp) 1f else maxWidth / maxHeight
            val safeContainerRatio = if (containerRatio > 0f) containerRatio else 1f
            val fitWidth: androidx.compose.ui.unit.Dp
            val fitHeight: androidx.compose.ui.unit.Dp
            val coverScale: Float
            if (ratio > 0f) {
                if (safeContainerRatio > ratio) {
                    fitHeight = maxHeight
                    fitWidth = maxHeight * ratio
                    coverScale = safeContainerRatio / ratio
                } else {
                    fitWidth = maxWidth
                    fitHeight = maxWidth / ratio
                    coverScale = ratio / safeContainerRatio
                }
            } else {
                fitWidth = maxWidth
                fitHeight = maxHeight
                coverScale = 1f
            }
            val animatedRemoteScale by
                animateFloatAsState(
                    targetValue = if (remoteVideoFitCover) coverScale else 1f,
                    animationSpec = tween(durationMillis = 260),
                    label = "remote_video_scale"
                )
            if (uiState.remoteVideoEnabled) {
                Box(modifier = remoteModifier.clipToBounds()) {
                    VideoSurface(
                        modifier =
                            Modifier.size(fitWidth, fitHeight)
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    scaleX = animatedRemoteScale
                                    scaleY = animatedRemoteScale
                                },
                        renderer = remoteRenderer,
                        onAttach = { renderer ->
                            attachRemoteRenderer(renderer, remoteRendererEvents)
                        },
                        onDetach = detachRemoteRenderer,
                        contentScale = ContentScale.Crop,
                        isMediaOverlay = false
                    )
                }
            }
            if (uiState.localVideoEnabled) {
                TextureVideoSurface(
                    modifier = localModifier,
                    renderer = localPipRenderer,
                    onAttach = attachLocalSink,
                    onDetach = detachLocalSink,
                    mirror = uiState.isFrontCamera && !uiState.isScreenSharing,
                    contentScale = if (uiState.isScreenSharing) ContentScale.Fit else ContentScale.Crop
                )
            }
        }

        if (!isMultiParty && !uiState.localVideoEnabled) {
            Box(modifier = localModifier) {
                VideoPlaceholder(
                    text =
                        if (isLocalLarge) resolveString(SerenadaString.CallLocalCameraOff, strings)
                        else resolveString(SerenadaString.CallCameraOff, strings),
                    fontSize = if (isLocalLarge) 16.sp else 10.sp
                )
            }
        }

        val showRemotePlaceholder =
            !isMultiParty &&
                    !uiState.remoteVideoEnabled &&
                    (uiState.phase == CallPhase.InCall ||
                            (uiState.phase == CallPhase.Waiting && isLocalLarge))
        if (showRemotePlaceholder) {
            val text =
                if (uiState.phase == CallPhase.Waiting) resolveString(SerenadaString.CallWaitingShort, strings)
                else resolveString(SerenadaString.CallVideoOff, strings)
            Box(modifier = remoteModifier) {
                VideoPlaceholder(text = text, fontSize = if (isLocalLarge) 10.sp else 16.sp)
            }
        }

        // Debug tap target — only when debug overlay is enabled via config
        if (config.debugOverlayEnabled) {
            Box(
                modifier =
                    Modifier.align(Alignment.TopStart)
                        .statusBarsPadding()
                        .size(72.dp)
                        .zIndex(6f)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    val now = System.currentTimeMillis()
                                    if (now - debugTapTimestampMs < 450L) {
                                        debugTapTimestampMs = 0L
                                        showDebug = !showDebug
                                    } else {
                                        debugTapTimestampMs = now
                                    }
                                }
                            )
                        }
            )

            if (showDebug) {
                DebugPanel(
                    sections = debugSections,
                    modifier =
                        Modifier.align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 16.dp, top = 16.dp)
                            .width(debugPanelWidth)
                            .heightIn(max = debugPanelMaxHeight)
                            .zIndex(5f)
                )
            }
        }

        // Waiting State Overlay
        if (uiState.phase == CallPhase.Waiting && !isLocalLarge) {
            WaitingOverlay(
                roomId = roomId,
                serverHost = serverHost,
                onInviteToRoom = onInviteToRoom,
                strings = strings,
                config = config,
                onShareLink = onShareLink,
            )
        }

        // Reconnecting Indicator
        AnimatedVisibility(
            visible = showReconnectingBadge,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
        ) {
            Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = resolveString(SerenadaString.CallReconnecting, strings),
                        color = Color.White,
                        fontSize = 14.sp
                    )

                    if (uiState.connectionStatus == ConnectionStatus.Retrying) {
                        Text(
                            text = resolveString(SerenadaString.CallTakingLongerThanUsual, strings),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        val showFlashButton =
            uiState.phase == CallPhase.InCall &&
                    isWorldOrCompositeMode &&
                    uiState.isFlashAvailable
        val showRemoteFitButton = uiState.remoteVideoEnabled && !isLocalLarge && !isMultiParty
        if (showFlashButton || showRemoteFitButton) {
            Column(
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 16.dp, end = 16.dp)
                        .zIndex(2f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                if (showFlashButton) {
                    IconButton(
                        onClick = onToggleFlashlight,
                        modifier =
                            Modifier.size(44.dp)
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    ) {
                        Icon(
                            imageVector =
                                if (uiState.isFlashEnabled) Icons.Default.FlashlightOn
                                else Icons.Default.FlashlightOff,
                            contentDescription = resolveString(SerenadaString.CallToggleFlashlight, strings),
                            tint = Color.White
                        )
                    }
                }

                if (showRemoteFitButton) {
                    IconButton(
                        onClick = {
                            val next = !remoteVideoFitCover
                            remoteVideoFitCover = next
                            onRemoteVideoFitChanged?.invoke(next)
                        },
                        modifier =
                            Modifier.size(44.dp)
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    ) {
                        Icon(
                            imageVector =
                                if (remoteVideoFitCover) Icons.Default.FullscreenExit
                                else Icons.Default.Fullscreen,
                            contentDescription = resolveString(SerenadaString.CallToggleVideoFit, strings),
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Controls Bar
        AnimatedVisibility(
            visible = areControlsVisible,
            enter =
                fadeIn(animationSpec = tween(durationMillis = controlsAnimationDuration)) +
                        slideInVertically(
                            animationSpec = tween(durationMillis = controlsAnimationDuration),
                            initialOffsetY = { fullHeight -> fullHeight / 3 }
                        ),
            exit =
                fadeOut(animationSpec = tween(durationMillis = controlsAnimationDuration)) +
                        slideOutVertically(
                            animationSpec = tween(durationMillis = controlsAnimationDuration),
                            targetOffsetY = { fullHeight -> fullHeight / 3 }
                        ),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .animateContentSize(
                            animationSpec =
                                tween(durationMillis = controlsAnimationDuration)
                        )
                        .background(
                            brush =
                                androidx.compose.ui.graphics.Brush
                                    .verticalGradient(
                                        colors =
                                            listOf(
                                                Color.Transparent,
                                                Color.Black
                                                    .copy(
                                                        alpha =
                                                            0.7f
                                                    )
                                            )
                                    )
                        )
                        .padding(bottom = 48.dp, top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Order: Mute, Camera, Flip, Screen Share, End call

                    // Mute Button
                    ControlButton(
                        onClick = onToggleAudio,
                        icon =
                            if (uiState.localAudioEnabled) Icons.Default.Mic
                            else Icons.Default.MicOff,
                        backgroundColor =
                            if (uiState.localAudioEnabled) Color.White.copy(alpha = 0.2f)
                            else Color.Red
                    )

                    // Video Toggle Button
                    ControlButton(
                        onClick = onToggleVideo,
                        icon =
                            if (uiState.localVideoEnabled) Icons.Default.Videocam
                            else Icons.Default.VideocamOff,
                        backgroundColor =
                            if (uiState.localVideoEnabled) Color.White.copy(alpha = 0.2f)
                                else Color.Red
                    )

                    // Flip Camera
                    ControlButton(
                        onClick = onFlipCamera,
                        icon = Icons.Default.FlipCameraIos,
                        backgroundColor =
                            if (uiState.isScreenSharing) Color.Gray.copy(alpha = 0.1f)
                            else Color.White.copy(alpha = 0.2f),
                        // Disabled visual appearance could be added here
                    )

                    // Screen Share Button — only when enabled via config
                    if (config.screenSharingEnabled) {
                        ControlButton(
                            onClick = {
                                if (uiState.isScreenSharing) {
                                    onStopScreenShare()
                                } else {
                                    screenShareLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                }
                            },
                            icon = if (uiState.isScreenSharing) {
                                Icons.AutoMirrored.Filled.StopScreenShare
                            } else {
                                Icons.AutoMirrored.Filled.ScreenShare
                            },
                            backgroundColor = if (uiState.isScreenSharing) Color.Red else Color.White.copy(alpha = 0.2f)
                        )
                    }

                    // End Call Button
                    ControlButton(
                        onClick = onEndCall,
                        icon = Icons.Default.CallEnd,
                        backgroundColor = Color.Red,
                        modifier = Modifier.testTag("call.endCall")
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun DebugPanel(
    sections: List<DebugPanelSection>,
    modifier: Modifier = Modifier
) {
    val panelShape = RoundedCornerShape(10.dp)
    Surface(
        modifier =
            modifier.border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = panelShape
            ),
        color = Color.Black.copy(alpha = 0.7f),
        shape = panelShape
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
        ) {
            val useTwoColumns = maxWidth >= 390.dp
            if (useTwoColumns) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (index in sections.indices step 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DebugPanelSectionCard(
                                section = sections[index],
                                modifier = Modifier.weight(1f)
                            )
                            if (index + 1 < sections.size) {
                                DebugPanelSectionCard(
                                    section = sections[index + 1],
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sections.forEach { section ->
                        DebugPanelSectionCard(
                            section = section,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private enum class DebugStatus {
    GOOD,
    WARN,
    BAD,
    NA
}

private data class DebugPanelMetric(
    val label: String,
    val value: String,
    val status: DebugStatus
)

private data class DebugPanelSection(
    val title: String,
    val metrics: List<DebugPanelMetric>
)

@Composable
private fun DebugPanelSectionCard(
    section: DebugPanelSection,
    modifier: Modifier = Modifier
) {
    val sectionShape = RoundedCornerShape(8.dp)
    Surface(
        modifier =
            modifier.border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.11f),
                shape = sectionShape
            ),
        color = Color.White.copy(alpha = 0.04f),
        shape = sectionShape
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(
                text = section.title,
                color = Color(0xD9E6EDF3),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            section.metrics.forEach { metric ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(debugDotColor(metric.status))
                        )
                        if (metric.label.isNotBlank()) {
                            Text(
                                text = metric.label,
                                color = Color(0xF2E6EDF3),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                    Text(
                        text = metric.value,
                        color = Color(0xE6E6EDF3),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun debugDotColor(status: DebugStatus): Color {
    return when (status) {
        DebugStatus.GOOD -> Color(0xFF2ECC71)
        DebugStatus.WARN -> Color(0xFFF1C40F)
        DebugStatus.BAD -> Color(0xFFE74C3C)
        DebugStatus.NA -> Color(0xFF95A5A6)
    }
}

private fun buildDebugPanelSections(
    isConnected: Boolean,
    activeTransport: String?,
    iceConnectionState: String,
    connectionState: String,
    signalingState: String,
    roomParticipantCount: Int?,
    showReconnecting: Boolean,
    realtimeStats: RealtimeCallStats?
): List<DebugPanelSection> {
    val normalizedIceConnectionState = normalizeState(iceConnectionState)
    val normalizedConnectionState = normalizeState(connectionState)
    val normalizedSignalingState = normalizeState(signalingState)

    val signalingStatus = if (isConnected) DebugStatus.GOOD else DebugStatus.BAD
    val iceStatus =
        when (normalizedIceConnectionState) {
            "connected", "completed" -> DebugStatus.GOOD
            "checking", "disconnected" -> DebugStatus.WARN
            else -> DebugStatus.BAD
        }
    val pcStatus =
        when (normalizedConnectionState) {
            "connected" -> DebugStatus.GOOD
            "connecting", "disconnected" -> DebugStatus.WARN
            else -> DebugStatus.BAD
        }
    val reconnectingStatus = if (showReconnecting) DebugStatus.BAD else DebugStatus.GOOD

    val currentTransportPath = realtimeStats?.transportPath
    val transportPathStatus =
        when {
            currentTransportPath == null -> DebugStatus.NA
            currentTransportPath.startsWith("TURN relay") -> DebugStatus.WARN
            else -> DebugStatus.GOOD
        }
    val rttStatus = lowerIsBetter(realtimeStats?.rttMs, 120.0, 250.0)
    val availableOutgoingStatus = higherIsBetter(realtimeStats?.availableOutgoingKbps, 1500.0, 600.0)

    val audioLossStatus =
        worstStatus(
            lowerIsBetter(realtimeStats?.audioRxPacketLossPct, 1.0, 3.0),
            lowerIsBetter(realtimeStats?.audioTxPacketLossPct, 1.0, 3.0)
        )
    val audioBitrateStatus =
        worstStatus(
            higherIsBetter(realtimeStats?.audioRxKbps, 20.0, 12.0),
            higherIsBetter(realtimeStats?.audioTxKbps, 20.0, 12.0)
        )

    val videoLossStatus =
        worstStatus(
            lowerIsBetter(realtimeStats?.videoRxPacketLossPct, 1.0, 3.0),
            lowerIsBetter(realtimeStats?.videoTxPacketLossPct, 1.0, 3.0)
        )
    val videoBitrateStatus =
        worstStatus(
            higherIsBetter(realtimeStats?.videoRxKbps, 900.0, 350.0),
            higherIsBetter(realtimeStats?.videoTxKbps, 900.0, 350.0)
        )

    return listOf(
        DebugPanelSection(
            title = "Connection",
            metrics =
                listOf(
                    DebugPanelMetric(
                        label = "Signaling",
                        value = if (isConnected) "connected" else "disconnected",
                        status = signalingStatus
                    ),
                    DebugPanelMetric(
                        label = "Transport",
                        value = activeTransport ?: "n/a",
                        status = signalingStatus
                    ),
                    DebugPanelMetric(
                        label = "ICE / PC",
                        value = "$normalizedIceConnectionState / $normalizedConnectionState",
                        status = worstStatus(iceStatus, pcStatus)
                    ),
                    DebugPanelMetric(
                        label = "SDP",
                        value = normalizedSignalingState,
                        status = if (normalizedSignalingState == "stable") DebugStatus.GOOD else DebugStatus.WARN
                    ),
                    DebugPanelMetric(
                        label = "Room",
                        value = if (roomParticipantCount != null) "$roomParticipantCount participants" else "none",
                        status = if (roomParticipantCount != null) DebugStatus.GOOD else DebugStatus.WARN
                    ),
                    DebugPanelMetric(
                        label = "Reconnecting",
                        value = if (showReconnecting) "yes" else "no",
                        status = reconnectingStatus
                    )
                )
        ),
        DebugPanelSection(
            title = "Latency",
            metrics =
                listOf(
                    DebugPanelMetric("RTT", formatMs(realtimeStats?.rttMs), rttStatus),
                    DebugPanelMetric("", realtimeStats?.transportPath ?: "n/a", transportPathStatus),
                    DebugPanelMetric(
                        "Outgoing headroom",
                        formatKbps(realtimeStats?.availableOutgoingKbps),
                        availableOutgoingStatus
                    ),
                    DebugPanelMetric(
                        "Updated",
                        formatTimeLabel(realtimeStats?.updatedAtMs),
                        DebugStatus.NA
                    )
                )
        ),
        DebugPanelSection(
            title = "Audio Quality",
            metrics =
                listOf(
                    DebugPanelMetric(
                        "Packet loss \u21F5",
                        "${formatPercent(realtimeStats?.audioRxPacketLossPct)} / ${formatPercent(realtimeStats?.audioTxPacketLossPct)}",
                        audioLossStatus
                    ),
                    DebugPanelMetric(
                        "Jitter",
                        formatMs(realtimeStats?.audioJitterMs),
                        lowerIsBetter(realtimeStats?.audioJitterMs, 20.0, 40.0)
                    ),
                    DebugPanelMetric(
                        "Playout delay",
                        formatMs(realtimeStats?.audioPlayoutDelayMs),
                        lowerIsBetter(realtimeStats?.audioPlayoutDelayMs, 80.0, 180.0)
                    ),
                    DebugPanelMetric(
                        "Concealed audio",
                        formatPercent(realtimeStats?.audioConcealedPct),
                        lowerIsBetter(realtimeStats?.audioConcealedPct, 2.0, 8.0)
                    ),
                    DebugPanelMetric(
                        "Bitrate \u21F5",
                        "${formatKbps(realtimeStats?.audioRxKbps)} / ${formatKbps(realtimeStats?.audioTxKbps)}",
                        audioBitrateStatus
                    )
                )
        ),
        DebugPanelSection(
            title = "Video Quality",
            metrics =
                listOf(
                    DebugPanelMetric(
                        "Packet loss \u21F5",
                        "${formatPercent(realtimeStats?.videoRxPacketLossPct)} / ${formatPercent(realtimeStats?.videoTxPacketLossPct)}",
                        videoLossStatus
                    ),
                    DebugPanelMetric(
                        "Bitrate \u21F5",
                        "${formatKbps(realtimeStats?.videoRxKbps)} / ${formatKbps(realtimeStats?.videoTxKbps)}",
                        videoBitrateStatus
                    ),
                    DebugPanelMetric(
                        "Render FPS",
                        formatFps(realtimeStats?.videoFps),
                        higherIsBetter(realtimeStats?.videoFps, 24.0, 15.0)
                    ),
                    DebugPanelMetric(
                        "Resolution",
                        realtimeStats?.videoResolution ?: "n/a",
                        if (realtimeStats?.videoResolution != null) DebugStatus.GOOD else DebugStatus.NA
                    ),
                    DebugPanelMetric(
                        "Freezes (last 60s)",
                        formatFreezeWindow(realtimeStats?.videoFreezeCount60s, realtimeStats?.videoFreezeDuration60s),
                        worstStatus(
                            lowerIsBetter(realtimeStats?.videoFreezeCount60s?.toDouble(), 0.0, 2.0),
                            lowerIsBetter(realtimeStats?.videoFreezeDuration60s, 0.2, 1.0)
                        )
                    ),
                    DebugPanelMetric(
                        "Retransmit",
                        formatPercent(realtimeStats?.videoRetransmitPct),
                        lowerIsBetter(realtimeStats?.videoRetransmitPct, 1.0, 3.0)
                    )
                )
        )
    )
}

private fun normalizeState(value: String): String {
    val normalized = value.trim().lowercase(Locale.US)
    return if (normalized.isBlank()) "n/a" else normalized
}

private fun formatMs(value: Double?): String {
    return if (value == null) "n/a" else "${value.roundToInt()} ms"
}

private fun formatPercent(value: Double?): String {
    return if (value == null) "n/a" else "%.1f%%".format(Locale.US, value)
}

private fun formatKbps(value: Double?): String {
    return if (value == null) "n/a" else "${value.roundToInt()} kbps"
}

private fun formatFps(value: Double?): String {
    return if (value == null) "n/a" else "%.1f fps".format(Locale.US, value)
}

private fun formatFreezeWindow(count: Long?, durationSeconds: Double?): String {
    if (count == null || durationSeconds == null) return "n/a"
    return "$count / %.1fs".format(Locale.US, durationSeconds)
}

private fun formatTimeLabel(timestampMs: Long?): String {
    if (timestampMs == null) return "n/a"
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestampMs))
}

private fun lowerIsBetter(value: Double?, goodMax: Double, warnMax: Double): DebugStatus {
    if (value == null) return DebugStatus.NA
    return when {
        value <= goodMax -> DebugStatus.GOOD
        value <= warnMax -> DebugStatus.WARN
        else -> DebugStatus.BAD
    }
}

private fun higherIsBetter(value: Double?, goodMin: Double, warnMin: Double): DebugStatus {
    if (value == null) return DebugStatus.NA
    return when {
        value >= goodMin -> DebugStatus.GOOD
        value >= warnMin -> DebugStatus.WARN
        else -> DebugStatus.BAD
    }
}

private fun worstStatus(vararg statuses: DebugStatus): DebugStatus {
    val concreteStatuses = statuses.filter { it != DebugStatus.NA }
    if (concreteStatuses.isEmpty()) return DebugStatus.NA
    if (concreteStatuses.contains(DebugStatus.BAD)) return DebugStatus.BAD
    if (concreteStatuses.contains(DebugStatus.WARN)) return DebugStatus.WARN
    return DebugStatus.GOOD
}

@Composable
private fun ControlButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    buttonSize: androidx.compose.ui.unit.Dp = 56.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp
) {
    Surface(
        modifier = modifier.size(buttonSize).clip(CircleShape).clickable { onClick() },
        color = backgroundColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun WaitingOverlay(
    roomId: String,
    serverHost: String,
    onInviteToRoom: () -> Unit,
    strings: Map<SerenadaString, String>?,
    config: SerenadaCallFlowConfig,
    onShareLink: (() -> Unit)?,
) {
    val link = "https://$serverHost/call/$roomId"
    val qrBitmap = remember(link) { generateQrCode(link) }
    val context = LocalContext.current
    val chooserTitle = resolveString(SerenadaString.CallShareLinkChooser, strings)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = resolveString(SerenadaString.CallWaitingOverlay, strings),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Surface(
            modifier = Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)),
            color = Color.White
        ) {
            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = resolveString(SerenadaString.CallQrCode, strings),
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (onShareLink != null) {
                    onShareLink()
                } else {
                    shareLink(context, link, chooserTitle)
                }
            },
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.2f)
                ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(resolveString(SerenadaString.CallShareInvitation, strings))
        }

        if (config.inviteControlsEnabled) {
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onInviteToRoom,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.2f)
                    ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(resolveString(SerenadaString.CallInviteToRoom, strings))
            }
        }
    }
}

private fun aspectRatioRendererEvents(
    handler: android.os.Handler,
    onAspectRatioChanged: (Float) -> Unit,
): RendererCommon.RendererEvents = object : RendererCommon.RendererEvents {
    private var current: Float? = null

    override fun onFirstFrameRendered() = Unit

    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
        val rotatedWidth = if (rotation % 180 == 0) width else height
        val rotatedHeight = if (rotation % 180 == 0) height else width
        if (rotatedWidth == 0 || rotatedHeight == 0) return
        val rawRatio = rotatedWidth.toFloat() / rotatedHeight.toFloat()
        val ratio = ((rawRatio / 0.05f).roundToInt() * 0.05f).coerceAtLeast(0.1f)
        handler.post {
            val prev = current
            val orientationChanged = prev != null && ((prev > 1f) != (ratio > 1f))
            val deltaThreshold = if (orientationChanged) 0.01f else 0.20f
            if (prev == null || abs(prev - ratio) > deltaThreshold) {
                current = ratio
                onAspectRatioChanged(ratio)
            }
        }
    }
}

@Composable
private fun MultiPartyStage(
    modifier: Modifier,
    remoteParticipants: List<RemoteParticipant>,
    remoteAspectRatios: MutableMap<String, Float>,
    localCid: String?,
    localVideoEnabled: Boolean,
    localMirror: Boolean,
    localCameraMode: LocalCameraMode,
    isScreenSharing: Boolean,
    localAspectRatio: Float,
    localPipRenderer: PipTextureRendererView,
    localFocusRenderer: PipTextureRendererView,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    eglContext: EglBase.Context,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    remoteContentCid: String?,
    remoteContentType: String?,
    remoteVideoFitCover: Boolean,
    onToggleRemoteVideoFit: () -> Unit,
    pinnedParticipantId: String?,
    onPinnedParticipantIdChanged: (String?) -> Unit,
    onTap: () -> Unit,
    onLocalPinchZoom: (Float) -> Unit,
    strings: Map<SerenadaString, String>?,
) {
    val density = LocalDensity.current
    val gap = 12.dp
    val outerPadding = 16.dp
    val pipCornerRadius = 12.dp
    val tileCornerRadius = 16.dp

    val localContentZoomState = rememberTransformableState { zoomChange, _, _ ->
        if (zoomChange > 0f && abs(zoomChange - 1f) > 0.01f) {
            onLocalPinchZoom(zoomChange)
        }
    }

    // Content source: local world/composite/screen share or remote content
    val hasLocalContent = isScreenSharing || localCameraMode.isContentMode
    val hasContentSource = hasLocalContent || remoteContentCid != null
    val useComputedLayout = localCid != null && (pinnedParticipantId != null || hasContentSource)

    Box(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
                val fullWidthPx = with(density) { maxWidth.toPx() }
                val fullHeightPx = with(density) { maxHeight.toPx() }
                val topChromePx = with(density) { 20.dp.toPx() }
                val bottomChromePx = with(density) { (bottomPadding + 12.dp).toPx() }

                if (useComputedLayout && localCid != null) {
                // Focus/content mode: use computeLayout for primary + filmstrip rendering
                val contentSource = if (hasLocalContent) {
                    val type = when {
                        isScreenSharing -> ContentType.SCREEN_SHARE
                        localCameraMode == LocalCameraMode.WORLD -> ContentType.WORLD_CAMERA
                        else -> ContentType.COMPOSITE_CAMERA
                    }
                    ContentSource(
                        type = type,
                        ownerParticipantId = localCid,
                        aspectRatio = null,
                    )
                } else if (remoteContentCid != null) {
                    val type = ContentType.fromWire(remoteContentType)
                    ContentSource(
                        type = type,
                        ownerParticipantId = remoteContentCid,
                        aspectRatio = null,
                    )
                } else null

                val computedLayout = remember(
                    pinnedParticipantId, contentSource, remoteParticipants, remoteAspectRatios.toMap(),
                    localCid, localVideoEnabled, fullWidthPx, fullHeightPx, remoteVideoFitCover,
                    bottomChromePx
                ) {
                    val participants = remoteParticipants.map { p ->
                        SceneParticipant(
                            id = p.cid,
                            role = ParticipantRole.REMOTE,
                            videoEnabled = p.videoEnabled,
                            videoAspectRatio = remoteAspectRatios[p.cid],
                        )
                    } + SceneParticipant(
                        id = localCid,
                        role = ParticipantRole.LOCAL,
                        videoEnabled = localVideoEnabled,
                        videoAspectRatio = null,
                    )

                    computeLayout(
                        CallScene(
                            viewportWidth = fullWidthPx,
                            viewportHeight = fullHeightPx,
                            safeAreaInsets = Insets(
                                top = topChromePx,
                                bottom = bottomChromePx,
                            ),
                            participants = participants,
                            localParticipantId = localCid,
                            activeSpeakerId = null,
                            pinnedParticipantId = if (contentSource != null) null else pinnedParticipantId,
                            contentSource = contentSource,
                            userPrefs = UserLayoutPrefs(
                                dominantFit = if (remoteVideoFitCover) FitMode.COVER else FitMode.CONTAIN,
                            ),
                        )
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    computedLayout.tiles.forEach { tile ->
                        key(tile.id) {
                        val isContentTile = tile.type == OccupantType.CONTENT_SOURCE
                        val isLocal = tile.id == localCid
                        val contentOwnerCid = contentSource?.ownerParticipantId
                        val isLocalContent = isContentTile && contentOwnerCid == localCid
                        val isRemoteContent = isContentTile && contentOwnerCid != localCid
                        val isLocalPlaceholder = isLocal && contentOwnerCid == localCid && !isContentTile
                        val tileWidthDp = with(density) { tile.frame.width.toDp() }
                        val tileHeightDp = with(density) { tile.frame.height.toDp() }
                        val tileXDp = with(density) { tile.frame.x.toDp() }
                        val tileYDp = with(density) { tile.frame.y.toDp() }
                        val tileCornerRadiusDp = with(density) { tile.cornerRadius.toDp() }

                        val isLocalContentZoomable = isLocalContent && localCameraMode.isContentMode
                        @OptIn(ExperimentalFoundationApi::class)
                        Box(
                            modifier = Modifier
                                .offset(x = tileXDp, y = tileYDp)
                                .size(width = tileWidthDp, height = tileHeightDp)
                                .clip(RoundedCornerShape(tileCornerRadiusDp))
                                .background(Color(0xFF111111))
                                .then(
                                    if (isLocalContentZoomable) Modifier.transformable(state = localContentZoomState)
                                    else Modifier
                                )
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onLongClick = {
                                        if (!isContentTile) {
                                            onPinnedParticipantIdChanged(
                                                if (tile.id == pinnedParticipantId) null else tile.id
                                            )
                                        }
                                    },
                                    onClick = onTap
                                )
                        ) {
                            if (isLocalContent || (isLocal && !isLocalPlaceholder)) {
                                // Local content tile or local filmstrip tile: render local video
                                if (localVideoEnabled || isLocalContent) {
                                    val localIsCover = !isLocalContent && tile.fit != FitMode.CONTAIN
                                    val localGeo = computeFitCoverGeometry(tileWidthDp, tileHeightDp, localAspectRatio)
                                    val localAnimatedScale by animateFloatAsState(
                                        targetValue = if (localIsCover) localGeo.coverScale else 1f,
                                        animationSpec = tween(durationMillis = 260),
                                        label = "local_tile_video_scale"
                                    )
                                    TextureVideoSurface(
                                        modifier = Modifier
                                            .size(localGeo.fitWidth, localGeo.fitHeight)
                                            .align(Alignment.Center)
                                            .graphicsLayer {
                                                scaleX = localAnimatedScale
                                                scaleY = localAnimatedScale
                                            },
                                        renderer = localFocusRenderer,
                                        onAttach = attachLocalSink,
                                        onDetach = detachLocalSink,
                                        mirror = if (isLocalContent) false else localMirror,
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    VideoPlaceholder(
                                        text = resolveString(SerenadaString.CallCameraOff, strings),
                                        fontSize = 10.sp
                                    )
                                }
                            } else if (isRemoteContent) {
                                // Remote content tile: render the content owner's video
                                val ownerParticipant = remoteParticipants.firstOrNull { it.cid == contentOwnerCid }
                                if (ownerParticipant != null) {
                                    RemoteParticipantStageTile(
                                        participant = ownerParticipant,
                                        width = tileWidthDp,
                                        height = tileHeightDp,
                                        cornerRadius = tileCornerRadiusDp,
                                        contentScale = if (tile.fit == FitMode.CONTAIN) ContentScale.Fit else ContentScale.Crop,
                                        eglContext = eglContext,
                                        onAspectRatioChanged = { ratio ->
                                            remoteAspectRatios[ownerParticipant.cid] = ratio
                                        },
                                        attachRemoteSink = { sink ->
                                            attachRemoteSinkForCid(ownerParticipant.cid, sink)
                                        },
                                        detachRemoteSink = { sink ->
                                            detachRemoteSinkForCid(ownerParticipant.cid, sink)
                                        },
                                        strings = strings,
                                    )
                                }
                            } else if (isLocalPlaceholder) {
                                // Local participant in content mode: camera replaced by screen share
                                VideoPlaceholder(
                                    text = resolveString(SerenadaString.CallCameraOff, strings),
                                    fontSize = 10.sp
                                )
                            } else {
                                val participant = remoteParticipants.firstOrNull { it.cid == tile.id }
                                if (participant != null) {
                                    RemoteParticipantStageTile(
                                        participant = participant,
                                        width = tileWidthDp,
                                        height = tileHeightDp,
                                        cornerRadius = tileCornerRadiusDp,
                                        contentScale = if (tile.fit == FitMode.CONTAIN) ContentScale.Fit else ContentScale.Crop,
                                        eglContext = eglContext,
                                        onAspectRatioChanged = { ratio ->
                                            remoteAspectRatios[tile.id] = ratio
                                        },
                                        attachRemoteSink = { sink ->
                                            attachRemoteSinkForCid(tile.id, sink)
                                        },
                                        detachRemoteSink = { sink ->
                                            detachRemoteSinkForCid(tile.id, sink)
                                        },
                                        strings = strings,
                                    )
                                }
                            }
                            // Pin indicator on pinned tile
                            if (tile.id == pinnedParticipantId) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                        .background(
                                            Color.Black.copy(alpha = 0.56f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                            // Fit toggle on primary tile (bottom-end to avoid flashlight conflict)
                            if (tile.zOrder == 0) {
                                IconButton(
                                    onClick = onToggleRemoteVideoFit,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .size(44.dp)
                                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (remoteVideoFitCover) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                        contentDescription = resolveString(SerenadaString.CallToggleVideoFit, strings),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    } // key(tile.id)
                    }
                }
            } else {
                // Grid mode: existing row-based rendering (applies its own padding)
                val gridWidthPx = fullWidthPx - with(density) { outerPadding.toPx() } * 2
                val gridHeightPx = fullHeightPx - topChromePx - bottomChromePx
                val layout = remember(remoteParticipants, remoteAspectRatios.toMap(), gridWidthPx, gridHeightPx) {
                    computeStageLayout(
                        tiles =
                            remoteParticipants.map { participant ->
                                StageTileSpec(
                                    cid = participant.cid,
                                    aspectRatio = clampStageTileAspectRatio(remoteAspectRatios[participant.cid]),
                                )
                            },
                        availableWidthPx = gridWidthPx,
                        availableHeightPx = gridHeightPx,
                        gapPx = with(density) { gap.toPx() },
                    )
                }

                Column(
                    modifier = Modifier.fillMaxSize()
                        .padding(
                            start = outerPadding,
                            end = outerPadding,
                            top = 20.dp,
                            bottom = bottomPadding + 12.dp
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    layout.forEachIndexed { rowIndex, row ->
                        if (rowIndex > 0) {
                            Spacer(modifier = Modifier.height(gap))
                        }
                        Row(horizontalArrangement = Arrangement.Center) {
                            row.items.forEachIndexed { itemIndex, tile ->
                                if (itemIndex > 0) {
                                    Spacer(modifier = Modifier.width(gap))
                                }
                                val participant = remoteParticipants.first { it.cid == tile.cid }
                                @OptIn(ExperimentalFoundationApi::class)
                                Box(
                                    modifier = Modifier.combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onLongClick = {
                                            onPinnedParticipantIdChanged(
                                                if (tile.cid == pinnedParticipantId) null else tile.cid
                                            )
                                        },
                                        onClick = onTap
                                    )
                                ) {
                                    RemoteParticipantStageTile(
                                        participant = participant,
                                        width = with(density) { tile.widthPx.toDp() },
                                        height = with(density) { tile.heightPx.toDp() },
                                        cornerRadius = tileCornerRadius,
                                        eglContext = eglContext,
                                        onAspectRatioChanged = { ratio ->
                                            remoteAspectRatios[tile.cid] = ratio
                                        },
                                        attachRemoteSink = { sink ->
                                            attachRemoteSinkForCid(tile.cid, sink)
                                        },
                                        detachRemoteSink = { sink ->
                                            detachRemoteSinkForCid(tile.cid, sink)
                                        },
                                        strings = strings,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Hide local PIP when in focus/content mode (local is in the filmstrip)
        if (!useComputedLayout) {
            Box(
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = bottomPadding)
                        .size(100.dp, 150.dp)
                        .clip(RoundedCornerShape(pipCornerRadius))
                        .background(Color(0xFF222222))
            ) {
                if (localVideoEnabled) {
                    TextureVideoSurface(
                        modifier = Modifier.fillMaxSize().padding(2.5.dp).clip(RoundedCornerShape(10.dp)),
                        renderer = localPipRenderer,
                        onAttach = attachLocalSink,
                        onDetach = detachLocalSink,
                        mirror = localMirror,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    VideoPlaceholder(
                        text = resolveString(SerenadaString.CallCameraOff, strings),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

private data class FitCoverGeometry(
    val fitWidth: androidx.compose.ui.unit.Dp,
    val fitHeight: androidx.compose.ui.unit.Dp,
    val coverScale: Float,
)

@Composable
private fun computeFitCoverGeometry(
    tileWidth: androidx.compose.ui.unit.Dp,
    tileHeight: androidx.compose.ui.unit.Dp,
    videoAspectRatio: Float,
): FitCoverGeometry {
    val density = LocalDensity.current
    val tileWidthPx = with(density) { tileWidth.toPx() }
    val tileHeightPx = with(density) { tileHeight.toPx() }
    val tileAspect = if (tileHeightPx > 0f) tileWidthPx / tileHeightPx else 1f
    if (videoAspectRatio <= 0f) {
        return FitCoverGeometry(tileWidth, tileHeight, 1f)
    }
    val fitWidth: androidx.compose.ui.unit.Dp
    val fitHeight: androidx.compose.ui.unit.Dp
    if (tileAspect > videoAspectRatio) {
        fitHeight = tileHeight
        fitWidth = with(density) { (tileHeightPx * videoAspectRatio).toDp() }
    } else {
        fitWidth = tileWidth
        fitHeight = with(density) { (tileWidthPx / videoAspectRatio).toDp() }
    }
    val coverScale = if (tileAspect > videoAspectRatio) tileAspect / videoAspectRatio
        else videoAspectRatio / tileAspect
    return FitCoverGeometry(fitWidth, fitHeight, coverScale)
}

@Composable
private fun RemoteParticipantStageTile(
    participant: RemoteParticipant,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp,
    contentScale: ContentScale = ContentScale.Fit,
    eglContext: EglBase.Context,
    onAspectRatioChanged: (Float) -> Unit,
    attachRemoteSink: (VideoSink) -> Unit,
    detachRemoteSink: (VideoSink) -> Unit,
    strings: Map<SerenadaString, String>? = null,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var videoAspectRatio by remember { mutableStateOf(0f) }
    val isCover = contentScale == ContentScale.Crop

    val rendererEvents =
        remember(participant.cid) {
            object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() = Unit

                override fun onFrameResolutionChanged(widthPx: Int, heightPx: Int, rotation: Int) {
                    val rotatedWidth = if (rotation % 180 == 0) widthPx else heightPx
                    val rotatedHeight = if (rotation % 180 == 0) heightPx else widthPx
                    if (rotatedWidth == 0 || rotatedHeight == 0) return
                    val rawRatio = rotatedWidth.toFloat() / rotatedHeight.toFloat()
                    val layoutRatio = clampStageTileAspectRatio(rawRatio)
                    mainHandler.post {
                        // Keep full frame aspect for rendering so tall screen-share streams
                        // still fit correctly, but clamp the ratio used by stage layout.
                        videoAspectRatio = rawRatio
                        onAspectRatioChanged(layoutRatio)
                    }
                }
            }
        }

    // Use TextureView-based renderer so graphicsLayer clip/scale works correctly.
    // SurfaceView renders on a separate hardware surface that ignores Compose clips,
    // causing video to bleed outside rounded tile corners when scaled.
    val renderer = remember(participant.cid) {
        PipTextureRendererView(context, "remote-${participant.cid}").also {
            it.init(eglContext, rendererEvents)
        }
    }

    DisposableEffect(renderer) {
        onDispose {
            renderer.release()
        }
    }

    // Animate fit-to-cover scale (same approach as 1:1 mode)
    val geo = computeFitCoverGeometry(width, height, videoAspectRatio)
    val animatedScale by animateFloatAsState(
        targetValue = if (isCover) geo.coverScale else 1f,
        animationSpec = tween(durationMillis = 260),
        label = "tile_video_scale"
    )

    Box(
        modifier =
            Modifier.size(width = width, height = height)
                .clip(RoundedCornerShape(cornerRadius))
                .background(Color(0xFF111111))
                .clipToBounds()
    ) {
        TextureVideoSurface(
            modifier = Modifier
                .size(geo.fitWidth, geo.fitHeight)
                .align(Alignment.Center)
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                },
            renderer = renderer,
            onAttach = { attachRemoteSink(it) },
            onDetach = { detachRemoteSink(it) },
            contentScale = ContentScale.Crop
        )
        if (!participant.videoEnabled) {
            Box(modifier = Modifier.fillMaxSize()) {
                VideoPlaceholder(
                    text = resolveString(SerenadaString.CallVideoOff, strings),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun TextureVideoSurface(
    modifier: Modifier,
    renderer: PipTextureRendererView,
    onAttach: (VideoSink) -> Unit,
    onDetach: (VideoSink) -> Unit,
    mirror: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop
) {
    DisposableEffect(renderer) {
        onAttach(renderer)
        onDispose { onDetach(renderer) }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            // Detach from any existing parent to prevent "child already has a parent"
            // crash when Compose reuses the same View across composition slots
            (renderer.parent as? ViewGroup)?.removeView(renderer)
            renderer
        },
        update = {
            it.setMirror(mirror)
            it.setScalingType(
                if (contentScale == ContentScale.Crop)
                    RendererCommon.ScalingType.SCALE_ASPECT_FILL
                else RendererCommon.ScalingType.SCALE_ASPECT_FIT
            )
        }
    )
}

@Composable
private fun VideoSurface(
    modifier: Modifier,
    renderer: SurfaceViewRenderer,
    onAttach: (SurfaceViewRenderer) -> Unit,
    onDetach: (SurfaceViewRenderer) -> Unit,
    mirror: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
    cornerRadius: androidx.compose.ui.unit.Dp? = null,
    isMediaOverlay: Boolean = false
) {
    val density = LocalDensity.current
    val cornerRadiusPx = remember(cornerRadius, density) {
        cornerRadius?.let { with(density) { it.toPx() } }
    }

    DisposableEffect(renderer) {
        onAttach(renderer)
        onDispose { onDetach(renderer) }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            RendererContainer(it, renderer).apply {
                updateCornerRadius(cornerRadiusPx)
            }
        },
        update = { container ->
            container.updateCornerRadius(cornerRadiusPx)
            val scalingType = if (contentScale == ContentScale.Crop)
                RendererCommon.ScalingType.SCALE_ASPECT_FILL
            else RendererCommon.ScalingType.SCALE_ASPECT_FIT
            renderer.apply {
                setZOrderOnTop(false)
                setZOrderMediaOverlay(isMediaOverlay)
                setMirror(mirror)
                setScalingType(scalingType)
            }
        }
    )
}

private class PipTextureRendererView(
    context: Context,
    name: String
) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val eglRenderer = EglRenderer(name)
    private val drawer = GlRectDrawer()
    private val transformMatrix = Matrix()
    private var initialized = false
    private var firstFrameRendered = false
    private var frameWidth = 0
    private var frameHeight = 0
    private var mirror = false
    private var scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL
    private var rendererEvents: RendererCommon.RendererEvents? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun init(
        eglContext: EglBase.Context,
        rendererEvents: RendererCommon.RendererEvents? = null
    ) {
        if (initialized) {
            this.rendererEvents = rendererEvents
            return
        }
        this.rendererEvents = rendererEvents
        eglRenderer.init(eglContext, EglBase.CONFIG_PLAIN, drawer)
        eglRenderer.setMirror(mirror)
        initialized = true
        if (isAvailable) {
            surfaceTexture?.let { eglRenderer.createEglSurface(it) }
        }
    }

    fun release() {
        if (!initialized) return
        initialized = false
        firstFrameRendered = false
        frameWidth = 0
        frameHeight = 0
        eglRenderer.releaseEglSurface {}
        eglRenderer.release()
    }

    fun setMirror(mirror: Boolean) {
        this.mirror = mirror
        if (initialized) {
            eglRenderer.setMirror(mirror)
        }
    }

    fun setScalingType(scalingType: RendererCommon.ScalingType) {
        this.scalingType = scalingType
        updateTransform()
    }

    override fun onFrame(frame: VideoFrame) {
        val rotatedWidth = if (frame.rotation % 180 == 0) frame.buffer.width else frame.buffer.height
        val rotatedHeight = if (frame.rotation % 180 == 0) frame.buffer.height else frame.buffer.width
        if (!firstFrameRendered) {
            firstFrameRendered = true
            rendererEvents?.onFirstFrameRendered()
        }
        if (frameWidth != rotatedWidth || frameHeight != rotatedHeight) {
            frameWidth = rotatedWidth
            frameHeight = rotatedHeight
            rendererEvents?.onFrameResolutionChanged(frame.buffer.width, frame.buffer.height, frame.rotation)
            post { updateTransform() }
        }
        eglRenderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (!initialized) return
        eglRenderer.createEglSurface(surface)
        updateTransform()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (initialized) {
            eglRenderer.releaseEglSurface {}
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    private fun updateTransform() {
        if (width == 0 || height == 0 || frameWidth == 0 || frameHeight == 0) return
        val viewAspect = width.toFloat() / height.toFloat()
        val frameAspect = frameWidth.toFloat() / frameHeight.toFloat()
        var sx = 1f
        var sy = 1f
        if (scalingType == RendererCommon.ScalingType.SCALE_ASPECT_FILL) {
            if (frameAspect > viewAspect) {
                sx = frameAspect / viewAspect
            } else {
                sy = viewAspect / frameAspect
            }
        } else {
            if (frameAspect > viewAspect) {
                sy = viewAspect / frameAspect
            } else {
                sx = frameAspect / viewAspect
            }
        }
        transformMatrix.reset()
        transformMatrix.setScale(sx, sy, width / 2f, height / 2f)
        setTransform(transformMatrix)
    }
}

private class RendererContainer(
    context: Context,
    renderer: SurfaceViewRenderer
) : FrameLayout(context) {
    private var cornerRadiusPx: Float = 0f
    private val roundedOutlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }

    init {
        if (renderer.parent is ViewGroup) {
            (renderer.parent as ViewGroup).removeView(renderer)
        }
        addView(
            renderer,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        isClickable = false
        isFocusable = false
    }

    fun updateCornerRadius(cornerRadiusPx: Float?) {
        val radius = cornerRadiusPx ?: 0f
        if (radius <= 0f) {
            clipToOutline = false
            return
        }
        this.cornerRadiusPx = radius
        outlineProvider = roundedOutlineProvider
        clipToOutline = true
        invalidateOutline()
    }
}

@Composable
private fun VideoPlaceholder(text: String, fontSize: androidx.compose.ui.unit.TextUnit = 16.sp) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF111111)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.VideocamOff,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(if (fontSize < 12.sp) 32.dp else 48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = fontSize,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun generateQrCode(text: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x,
                    y,
                    if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

private fun shareLink(context: Context, text: String, chooserTitle: String) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}
