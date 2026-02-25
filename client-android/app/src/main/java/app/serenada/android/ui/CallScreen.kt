package app.serenada.android.ui

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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import app.serenada.android.R
import app.serenada.android.call.CallPhase
import app.serenada.android.call.CallUiState
import app.serenada.android.call.LocalCameraMode
import app.serenada.android.call.RealtimeCallStats
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
fun CallScreen(
    roomId: String,
    uiState: CallUiState,
    serverHost: String,
    eglContext: EglBase.Context,
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
    attachRemoteSink: (VideoSink) -> Unit,
    detachRemoteSink: (VideoSink) -> Unit
) {
    var areControlsVisible by remember { mutableStateOf(true) }
    var isLocalLarge by rememberSaveable { mutableStateOf(false) }
    var remoteVideoFitCover by rememberSaveable { mutableStateOf(true) }
    var lastFrontCameraState by remember { mutableStateOf(uiState.isFrontCamera) }
    var localAspectRatio by remember { mutableStateOf<Float?>(null) }
    var remoteAspectRatio by remember { mutableStateOf<Float?>(null) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var debugTapTimestampMs by remember { mutableStateOf(0L) }
    var showReconnecting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val localRenderer = remember { SurfaceViewRenderer(context) }
    val remoteRenderer = remember { SurfaceViewRenderer(context) }
    val localPipRenderer = remember { PipTextureRendererView(context, "local-pip") }
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
        object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() = Unit

            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                val rotatedWidth = if (rotation % 180 == 0) width else height
                val rotatedHeight = if (rotation % 180 == 0) height else width
                if (rotatedWidth == 0 || rotatedHeight == 0) return
                val rawRatio = rotatedWidth.toFloat() / rotatedHeight.toFloat()
                // Quantize minor encoder size swings so we don't keep relaying out the SurfaceView.
                val ratio = ((rawRatio / 0.05f).roundToInt() * 0.05f).coerceAtLeast(0.1f)
                mainHandler.post {
                    val current = remoteAspectRatio
                    val orientationChanged =
                        current != null && ((current > 1f) != (ratio > 1f))
                    val deltaThreshold = if (orientationChanged) 0.01f else 0.20f
                    if (current == null || abs(current - ratio) > deltaThreshold) {
                        remoteAspectRatio = ratio
                    }
                }
            }
        }
    }
    val localRendererEvents = remember {
        object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() = Unit

            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                val rotatedWidth = if (rotation % 180 == 0) width else height
                val rotatedHeight = if (rotation % 180 == 0) height else width
                if (rotatedWidth == 0 || rotatedHeight == 0) return
                val rawRatio = rotatedWidth.toFloat() / rotatedHeight.toFloat()
                val ratio = ((rawRatio / 0.05f).roundToInt() * 0.05f).coerceAtLeast(0.1f)
                mainHandler.post {
                    val current = localAspectRatio
                    val orientationChanged =
                        current != null && ((current > 1f) != (ratio > 1f))
                    val deltaThreshold = if (orientationChanged) 0.01f else 0.20f
                    if (current == null || abs(current - ratio) > deltaThreshold) {
                        localAspectRatio = ratio
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        localPipRenderer.init(eglContext)
        remotePipRenderer.init(eglContext)
        onDispose {
            localRenderer.release()
            remoteRenderer.release()
            localPipRenderer.release()
            remotePipRenderer.release()
        }
    }

    val isReconnecting =
        remember(
            uiState.iceConnectionState,
            uiState.connectionState,
            uiState.isSignalingConnected
        ) {
            val iceState = uiState.iceConnectionState
            val connState = uiState.connectionState
            !uiState.isSignalingConnected ||
                    iceState == "DISCONNECTED" ||
                    iceState == "FAILED" ||
                    connState == "DISCONNECTED" ||
                    connState == "FAILED"
        }

    LaunchedEffect(uiState.phase, isReconnecting) {
        if (uiState.phase != CallPhase.InCall || !isReconnecting) {
            showReconnecting = false
            return@LaunchedEffect
        }
        delay(800)
        if (uiState.phase == CallPhase.InCall && isReconnecting) {
            showReconnecting = true
        }
    }

    val debugSections =
        remember(
            uiState.isSignalingConnected,
            uiState.activeTransport,
            uiState.iceConnectionState,
            uiState.connectionState,
            uiState.signalingState,
            uiState.roomId,
            uiState.participantCount,
            showReconnecting,
            uiState.realtimeCallStats
        ) {
            buildDebugPanelSections(
                isConnected = uiState.isSignalingConnected,
                activeTransport = uiState.activeTransport,
                iceConnectionState = uiState.iceConnectionState,
                connectionState = uiState.connectionState,
                signalingState = uiState.signalingState,
                roomParticipantCount = if (uiState.roomId != null) uiState.participantCount else null,
                showReconnecting = showReconnecting,
                realtimeStats = uiState.realtimeCallStats
            )
        }

    // Auto-hide controls
    LaunchedEffect(areControlsVisible, uiState.phase) {
        if (areControlsVisible && uiState.phase == CallPhase.InCall) {
            delay(8000)
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

    BoxWithConstraints(
        modifier =
            Modifier.fillMaxSize().background(Color.Black).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { areControlsVisible = !areControlsVisible }
    ) {
        val controlsAnimationDuration = 320
        val showPip =
            uiState.phase == CallPhase.InCall ||
                    uiState.phase == CallPhase.Waiting ||
                    uiState.connectionState == "CONNECTED"
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
            ) { areControlsVisible = !areControlsVisible }
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

        if (isLocalLarge) {
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
                        mirror = uiState.isFrontCamera && !uiState.isScreenSharing, // No mirror if screen share
                        contentScale = if(uiState.isScreenSharing) ContentScale.Fit else ContentScale.Fit,
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
                    mirror = uiState.isFrontCamera && !uiState.isScreenSharing, // No mirror if screen share
                    contentScale = if(uiState.isScreenSharing) ContentScale.Fit else ContentScale.Crop
                )
            }
        }

        if (!uiState.localVideoEnabled) {
            Box(modifier = localModifier) {
                VideoPlaceholder(
                    text =
                        if (isLocalLarge) stringResource(R.string.call_local_camera_off)
                        else stringResource(R.string.call_camera_off),
                    fontSize = if (isLocalLarge) 16.sp else 10.sp
                )
            }
        }

        val showRemotePlaceholder =
            !uiState.remoteVideoEnabled &&
                    (uiState.phase == CallPhase.InCall ||
                            (uiState.phase == CallPhase.Waiting && isLocalLarge))
        if (showRemotePlaceholder) {
            val text =
                if (uiState.phase == CallPhase.Waiting) stringResource(R.string.call_waiting_short)
                else stringResource(R.string.call_video_off)
            Box(modifier = remoteModifier) {
                VideoPlaceholder(text = text, fontSize = if (isLocalLarge) 10.sp else 16.sp)
            }
        }

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

        // Waiting State Overlay
        if (uiState.phase == CallPhase.Waiting && !isLocalLarge) {
            WaitingOverlay(
                roomId = roomId,
                serverHost = serverHost,
                onInviteToRoom = onInviteToRoom
            )
        }

        // Reconnecting Indicator
        AnimatedVisibility(
            visible = showReconnecting && uiState.phase == CallPhase.InCall,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
        ) {
            Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(20.dp)) {
                Text(
                    text = stringResource(R.string.call_reconnecting),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 14.sp
                )
            }
        }

        val showFlashButton =
            uiState.phase == CallPhase.InCall &&
                    isWorldOrCompositeMode &&
                    uiState.isFlashAvailable
        val showRemoteFitButton = uiState.remoteVideoEnabled && !isLocalLarge
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
                                if (uiState.isFlashEnabled) Icons.Default.FlashOn
                                else Icons.Default.FlashOff,
                            contentDescription = stringResource(R.string.call_toggle_flashlight),
                            tint = Color.White
                        )
                    }
                }

                if (showRemoteFitButton) {
                    IconButton(
                        onClick = { remoteVideoFitCover = !remoteVideoFitCover },
                        modifier =
                            Modifier.size(44.dp)
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    ) {
                        Icon(
                            imageVector =
                                if (remoteVideoFitCover) Icons.Default.FullscreenExit
                                else Icons.Default.Fullscreen,
                            contentDescription = stringResource(R.string.call_toggle_video_fit),
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

                    // Screen Share Button
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

                    // End Call Button
                    ControlButton(
                        onClick = onEndCall,
                        icon = Icons.Default.CallEnd,
                        backgroundColor = Color.Red
                    )
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

    val transportPathStatus =
        when {
            realtimeStats?.transportPath == null -> DebugStatus.NA
            realtimeStats.transportPath.startsWith("TURN relay") -> DebugStatus.WARN
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
                        "Packet loss ⇵",
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
                        "Bitrate ⇵",
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
                        "Packet loss ⇵",
                        "${formatPercent(realtimeStats?.videoRxPacketLossPct)} / ${formatPercent(realtimeStats?.videoTxPacketLossPct)}",
                        videoLossStatus
                    ),
                    DebugPanelMetric(
                        "Bitrate ⇵",
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
    buttonSize: androidx.compose.ui.unit.Dp = 56.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp
) {
    Surface(
        modifier = Modifier.size(buttonSize).clip(CircleShape).clickable { onClick() },
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
private fun WaitingOverlay(roomId: String, serverHost: String, onInviteToRoom: () -> Unit) {
    val link = "https://$serverHost/call/$roomId"
    val qrBitmap = remember(link) { generateQrCode(link) }
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.call_share_link_chooser)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.call_waiting_overlay),
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
                    contentDescription = stringResource(R.string.call_qr_code),
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { shareLink(context, link, chooserTitle) },
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.2f)
                ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.call_share_invitation))
        }

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
            Text(stringResource(R.string.call_invite_to_room))
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
        factory = { renderer },
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
            renderer.apply {
                setZOrderOnTop(false)
                setZOrderMediaOverlay(isMediaOverlay)
                setMirror(mirror)
                setScalingType(
                    if (contentScale == ContentScale.Crop)
                        RendererCommon.ScalingType.SCALE_ASPECT_FILL
                    else RendererCommon.ScalingType.SCALE_ASPECT_FIT
                )
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
