package app.serenada.callui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlipCameraIos
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.serenada.core.SnapshotSource
import app.serenada.core.layout.CallScene
import app.serenada.core.layout.ContentSource
import app.serenada.core.layout.ContentType
import app.serenada.core.layout.FitMode
import app.serenada.core.layout.Insets
import app.serenada.core.layout.OccupantType
import app.serenada.core.layout.ParticipantRole
import app.serenada.core.layout.SceneParticipant
import app.serenada.core.layout.UserLayoutPrefs
import app.serenada.core.layout.clampStageTileAspectRatio
import app.serenada.core.layout.computeLayout
import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.RemoteParticipant
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink

private val FrontlineBlack = Color.Black
private val FrontlinePanel = Color.Black
private val FrontlineSurface = Color(0xFF1A1A1A)
private val FrontlineBorder = Color(0xFF2A2A2A)
private val FrontlineAccent = Color(0xFF15BF54)
private val FrontlineDanger = Color(0xFFF5564B)
private val FrontlineDim = Color(0xFFA1A1AA)
private val FrontlineSheet = Color(0xFF15161A)
private const val FRONTLINE_VIDEO_CONFIRM_MS = 3_000L
private const val FRONTLINE_ZOOM_CHANGE_THRESHOLD = 0.01f

private enum class FrontlineFeed {
    Local,
    Remote,
}

@Composable
internal fun FrontlineCallScreen(
    uiState: CallUiState,
    roomShareUrl: String?,
    eglContext: EglBase.Context,
    config: SerenadaCallFlowConfig,
    theme: SerenadaCallFlowTheme,
    strings: Map<SerenadaString, String>?,
    onToggleAudio: () -> Unit,
    onToggleVideo: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleFlashlight: () -> Unit,
    onLocalPinchZoom: (Float) -> Unit,
    onEndCall: () -> Unit,
    onShareLink: (() -> Unit)?,
    onInviteToRoom: () -> Unit,
    onStartScreenShare: (Intent) -> Unit,
    onStopScreenShare: () -> Unit,
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
    onSnapshotRequested: ((SnapshotSource) -> Unit)?,
) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val context = LocalContext.current
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

    var videoConfirming by rememberSaveable { mutableStateOf(false) }
    var pipSwapped by rememberSaveable { mutableStateOf(false) }
    var isMoreSheetVisible by rememberSaveable { mutableStateOf(false) }
    var showSnapshotFlash by remember { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var debugTapTimestampMs by remember { mutableStateOf(0L) }
    var localAspectRatio by remember { mutableStateOf<Float?>(null) }
    val remoteTileAspectRatios = remember { mutableStateMapOf<String, Float>() }
    var pinnedParticipantId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(videoConfirming) {
        if (videoConfirming) {
            delay(FRONTLINE_VIDEO_CONFIRM_MS)
            videoConfirming = false
        }
    }
    LaunchedEffect(uiState.localVideoEnabled) {
        if (uiState.localVideoEnabled) {
            videoConfirming = false
        }
    }

    val localContentMode =
        uiState.localCameraMode == LocalCameraMode.WORLD ||
            uiState.localCameraMode == LocalCameraMode.COMPOSITE ||
            uiState.isScreenSharing
    val isCallSurfacePhase =
        uiState.phase == CallPhase.InCall || uiState.phase == CallPhase.Waiting
    val remote = uiState.remoteParticipants.firstOrNull()
    val remoteVideoEnabled = remote?.videoEnabled == true
    LaunchedEffect(uiState.localVideoEnabled, remote?.cid, localContentMode) {
        pipSwapped = false
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val localRendererEvents = remember {
        aspectRatioRendererEvents(mainHandler) { ratio -> localAspectRatio = ratio }
    }
    val remoteRendererEvents = remember {
        aspectRatioRendererEvents(mainHandler) {}
    }
    val localZoomTransformState = rememberTransformableState { zoomChange, _, _ ->
        if (zoomChange > 0f && abs(zoomChange - 1f) > FRONTLINE_ZOOM_CHANGE_THRESHOLD) {
            onLocalPinchZoom(zoomChange)
        }
    }

    val localIsLarge =
        uiState.localVideoEnabled && if (localContentMode) {
            !pipSwapped
        } else {
            pipSwapped
        }
    val largeFeed = if (localIsLarge) FrontlineFeed.Local else FrontlineFeed.Remote
    val pipFeed = when {
        !uiState.localVideoEnabled && !remoteVideoEnabled -> null
        largeFeed == FrontlineFeed.Local -> FrontlineFeed.Remote
        else -> FrontlineFeed.Local
    }
    val canSwapPip =
        pipFeed != null &&
            uiState.localVideoEnabled &&
            remote != null
    val roomLink = roomShareUrl?.takeIf { it.isNotBlank() }
    val shareLinkAction: (() -> Unit)? = when {
        onShareLink != null -> onShareLink
        roomLink != null -> {
            {
                shareFrontlineCallLink(
                    context = context,
                    link = roomLink,
                    chooserTitle = resolveString(SerenadaString.CallShareLinkChooser, strings),
                )
            }
        }
        else -> null
    }
    val showMoreButton =
        isCallSurfacePhase &&
            (config.screenSharingEnabled || config.inviteControlsEnabled)
    val snapshotSource =
        if (
            config.snapshotEnabled &&
                onSnapshotRequested != null &&
                isCallSurfacePhase
        ) {
            when {
                uiState.localVideoEnabled -> SnapshotSource.Local
                else -> uiState.remoteParticipants
                    .firstOrNull { it.videoEnabled }
                    ?.let { SnapshotSource.Remote(it.cid) }
            }
        } else {
            null
        }
    val showReconnectingBadge =
        uiState.phase == CallPhase.InCall &&
            uiState.connectionStatus != ConnectionStatus.Connected
    LaunchedEffect(uiState.localCid, uiState.remoteParticipants.map { it.cid }) {
        val activeCids = uiState.remoteParticipants.map { it.cid }.toSet()
        remoteTileAspectRatios.keys
            .filter { it !in activeCids }
            .forEach { remoteTileAspectRatios.remove(it) }
        if (
            pinnedParticipantId != null &&
                pinnedParticipantId != uiState.localCid &&
                pinnedParticipantId !in activeCids
        ) {
            pinnedParticipantId = null
        }
    }
    val debugSections = remember(
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
            realtimeStats = uiState.realtimeCallStats,
        )
    }
    val avatarCache = rememberAvatarCache(config.avatarProvider)

    SerenadaTheme(theme) {
        CompositionLocalProvider(LocalAvatarCache provides avatarCache) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FrontlineBlack)
                    .testTag("call.frontline.screen")
            ) {
                val isLandscape = maxWidth > maxHeight
                val isTabletLandscape = isLandscape && maxWidth >= 1100.dp && maxHeight >= 720.dp
                val panelWidth = when {
                    !isLandscape -> maxWidth
                    maxWidth >= 720.dp -> 320.dp
                    else -> 260.dp
                }
                val pipInPanel = isTabletLandscape && pipFeed != null
                val pipWidth = when {
                    pipInPanel -> 220.dp
                    maxWidth >= 1100.dp -> 172.dp
                    maxWidth >= 720.dp -> 152.dp
                    maxWidth >= 480.dp -> 120.dp
                    else -> 100.dp
                }
                val pipHeight = when {
                    pipInPanel -> 280.dp
                    maxWidth >= 1100.dp -> 220.dp
                    maxWidth >= 720.dp -> 196.dp
                    maxWidth >= 480.dp -> 154.dp
                    else -> 128.dp
                }
                val pip: @Composable (Modifier) -> Unit = { modifier ->
                    if (pipFeed != null) {
                        FrontlinePip(
                            feed = pipFeed,
                            uiState = uiState,
                            remote = remote,
                            eglContext = eglContext,
                            width = pipWidth,
                            height = pipHeight,
                            showSwapHint = canSwapPip,
                            onClick = {
                                if (canSwapPip) {
                                    pipSwapped = !pipSwapped
                                }
                            },
                            attachLocalSink = attachLocalSink,
                            detachLocalSink = detachLocalSink,
                            attachRemoteSink = attachRemoteSink,
                            detachRemoteSink = detachRemoteSink,
                            modifier = modifier,
                        )
                    }
                }

                if (isLandscape) {
                    Row(Modifier.fillMaxSize()) {
                        FrontlineContentArea(
                            uiState = uiState,
                            remote = remote,
                            largeFeed = largeFeed,
                            pipFeed = pipFeed,
                            pipInPanel = pipInPanel,
                            localContentMode = localContentMode,
                            isCallSurfacePhase = isCallSurfacePhase,
                            eglContext = eglContext,
                            localRendererEvents = localRendererEvents,
                            remoteRendererEvents = remoteRendererEvents,
                            localAspectRatio = localAspectRatio ?: 0f,
                            remoteAspectRatios = remoteTileAspectRatios,
                            pinnedParticipantId = pinnedParticipantId,
                            onPinnedParticipantIdChanged = { pinnedParticipantId = it },
                            localZoomTransformState = localZoomTransformState,
                            attachLocalRenderer = attachLocalRenderer,
                            detachLocalRenderer = detachLocalRenderer,
                            attachLocalSink = attachLocalSink,
                            detachLocalSink = detachLocalSink,
                            attachRemoteRenderer = attachRemoteRenderer,
                            detachRemoteRenderer = detachRemoteRenderer,
                            attachRemoteSinkForCid = attachRemoteSinkForCid,
                            detachRemoteSinkForCid = detachRemoteSinkForCid,
                            pip = pip,
                            strings = strings,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        FrontlineControlsPanel(
                            uiState = uiState,
                            videoConfirming = videoConfirming,
                            isLandscape = true,
                            isTabletLandscape = isTabletLandscape,
                            panelWidth = panelWidth,
                            callControlsEnabled = isCallSurfacePhase,
                            videoControlsEnabled = isCallSurfacePhase && config.videoEnabled && uiState.availableCameraModes.isNotEmpty(),
                            showMoreButton = showMoreButton,
                            snapshotSource = snapshotSource,
                            snapshotHandler = onSnapshotRequested,
                            reservePreviewActions = true,
                            pipInPanel = pipInPanel,
                            pip = pip,
                            onVideoTap = {
                                when {
                                    uiState.localVideoEnabled -> {
                                        videoConfirming = false
                                        pipSwapped = false
                                        onToggleVideo()
                                    }
                                    videoConfirming -> {
                                        videoConfirming = false
                                        onToggleVideo()
                                    }
                                    else -> videoConfirming = true
                                }
                            },
                            onToggleAudio = onToggleAudio,
                            onFlipCamera = onFlipCamera,
                            onToggleFlashlight = onToggleFlashlight,
                            onSnapshotFlash = { showSnapshotFlash = true },
                            onMore = { isMoreSheetVisible = true },
                            onEndCall = onEndCall,
                            modifier = Modifier.width(panelWidth).fillMaxHeight(),
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        FrontlineContentArea(
                            uiState = uiState,
                            remote = remote,
                            largeFeed = largeFeed,
                            pipFeed = pipFeed,
                            pipInPanel = false,
                            localContentMode = localContentMode,
                            isCallSurfacePhase = isCallSurfacePhase,
                            eglContext = eglContext,
                            localRendererEvents = localRendererEvents,
                            remoteRendererEvents = remoteRendererEvents,
                            localAspectRatio = localAspectRatio ?: 0f,
                            remoteAspectRatios = remoteTileAspectRatios,
                            pinnedParticipantId = pinnedParticipantId,
                            onPinnedParticipantIdChanged = { pinnedParticipantId = it },
                            localZoomTransformState = localZoomTransformState,
                            attachLocalRenderer = attachLocalRenderer,
                            detachLocalRenderer = detachLocalRenderer,
                            attachLocalSink = attachLocalSink,
                            detachLocalSink = detachLocalSink,
                            attachRemoteRenderer = attachRemoteRenderer,
                            detachRemoteRenderer = detachRemoteRenderer,
                            attachRemoteSinkForCid = attachRemoteSinkForCid,
                            detachRemoteSinkForCid = detachRemoteSinkForCid,
                            pip = pip,
                            strings = strings,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        FrontlineControlsPanel(
                            uiState = uiState,
                            videoConfirming = videoConfirming,
                            isLandscape = false,
                            isTabletLandscape = false,
                            panelWidth = panelWidth,
                            callControlsEnabled = isCallSurfacePhase,
                            videoControlsEnabled = isCallSurfacePhase && config.videoEnabled && uiState.availableCameraModes.isNotEmpty(),
                            showMoreButton = showMoreButton,
                            snapshotSource = snapshotSource,
                            snapshotHandler = onSnapshotRequested,
                            reservePreviewActions = false,
                            pipInPanel = false,
                            pip = pip,
                            onVideoTap = {
                                when {
                                    uiState.localVideoEnabled -> {
                                        videoConfirming = false
                                        pipSwapped = false
                                        onToggleVideo()
                                    }
                                    videoConfirming -> {
                                        videoConfirming = false
                                        onToggleVideo()
                                    }
                                    else -> videoConfirming = true
                                }
                            },
                            onToggleAudio = onToggleAudio,
                            onFlipCamera = onFlipCamera,
                            onToggleFlashlight = onToggleFlashlight,
                            onSnapshotFlash = { showSnapshotFlash = true },
                            onMore = { isMoreSheetVisible = true },
                            onEndCall = onEndCall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showReconnectingBadge,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 16.dp)
                        .zIndex(4f)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = resolveString(SerenadaString.CallReconnecting, strings),
                                color = Color.White,
                                fontSize = 14.sp,
                            )
                            if (uiState.connectionStatus == ConnectionStatus.Retrying) {
                                Text(
                                    text = resolveString(SerenadaString.CallTakingLongerThanUsual, strings),
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                if (showSnapshotFlash) {
                    LaunchedEffect(Unit) {
                        delay(220)
                        showSnapshotFlash = false
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.86f))
                            .zIndex(5f)
                    )
                }

                if (config.debugOverlayEnabled) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .size(72.dp)
                            .zIndex(7f)
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
                        val debugPanelWidth = minOf(maxWidth * 0.92f, 430.dp)
                        val debugPanelMaxHeight = (maxHeight - 140.dp).coerceAtLeast(120.dp)
                        DebugPanel(
                            sections = debugSections,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .padding(start = 16.dp, top = 16.dp)
                                .width(debugPanelWidth)
                                .heightIn(max = debugPanelMaxHeight)
                                .zIndex(6f),
                        )
                    }
                }

                FrontlineMoreSheet(
                    visible = isMoreSheetVisible,
                    screenSharingEnabled = config.screenSharingEnabled,
                    inviteEnabled = config.inviteControlsEnabled,
                    shareEnabled = config.inviteControlsEnabled && shareLinkAction != null,
                    isScreenSharing = uiState.isScreenSharing,
                    onDismiss = { isMoreSheetVisible = false },
                    onToggleScreenShare = {
                        isMoreSheetVisible = false
                        if (uiState.isScreenSharing) {
                            onStopScreenShare()
                        } else {
                            screenShareLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                        }
                    },
                    onInvite = {
                        isMoreSheetVisible = false
                        onInviteToRoom()
                    },
                    onShare = {
                        isMoreSheetVisible = false
                        shareLinkAction?.invoke()
                    },
                    modifier = Modifier.zIndex(8f),
                )
            }
        }
    }
}

@Composable
private fun FrontlineContentArea(
    uiState: CallUiState,
    remote: RemoteParticipant?,
    largeFeed: FrontlineFeed,
    pipFeed: FrontlineFeed?,
    pipInPanel: Boolean,
    localContentMode: Boolean,
    isCallSurfacePhase: Boolean,
    eglContext: EglBase.Context,
    localRendererEvents: RendererCommon.RendererEvents,
    remoteRendererEvents: RendererCommon.RendererEvents,
    localAspectRatio: Float,
    remoteAspectRatios: MutableMap<String, Float>,
    pinnedParticipantId: String?,
    onPinnedParticipantIdChanged: (String?) -> Unit,
    localZoomTransformState: androidx.compose.foundation.gestures.TransformableState,
    attachLocalRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachRemoteRenderer: (SurfaceViewRenderer) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    pip: @Composable (Modifier) -> Unit,
    strings: Map<SerenadaString, String>?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(FrontlineBlack)
            .clipToBounds()
    ) {
        val waitingForRemote = uiState.isFrontlineWaitingForRemote()
        when {
            !isCallSurfacePhase -> {
                FrontlinePhaseSurface(
                    uiState = uiState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            waitingForRemote -> {
                FrontlineWaitingSurface(
                    uiState = uiState,
                    localContentMode = localContentMode,
                    localRendererEvents = localRendererEvents,
                    localZoomTransformState = localZoomTransformState,
                    attachLocalRenderer = attachLocalRenderer,
                    detachLocalRenderer = detachLocalRenderer,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            uiState.remoteParticipants.size > 1 -> {
                FrontlineMultiPartyStage(
                    uiState = uiState,
                    localContentMode = localContentMode,
                    eglContext = eglContext,
                    localAspectRatio = localAspectRatio,
                    remoteAspectRatios = remoteAspectRatios,
                    pinnedParticipantId = pinnedParticipantId,
                    onPinnedParticipantIdChanged = onPinnedParticipantIdChanged,
                    localZoomTransformState = localZoomTransformState,
                    localRendererEvents = localRendererEvents,
                    attachLocalSink = attachLocalSink,
                    detachLocalSink = detachLocalSink,
                    attachRemoteSinkForCid = attachRemoteSinkForCid,
                    detachRemoteSinkForCid = detachRemoteSinkForCid,
                    strings = strings,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                FrontlineLargeSurface(
                    feed = largeFeed,
                    uiState = uiState,
                    remote = remote,
                    localContentMode = localContentMode,
                    eglContext = eglContext,
                    localRendererEvents = localRendererEvents,
                    remoteRendererEvents = remoteRendererEvents,
                    localZoomTransformState = localZoomTransformState,
                    attachLocalRenderer = attachLocalRenderer,
                    detachLocalRenderer = detachLocalRenderer,
                    attachRemoteRenderer = attachRemoteRenderer,
                    detachRemoteRenderer = detachRemoteRenderer,
                    strings = strings,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (
            isCallSurfacePhase &&
                !waitingForRemote &&
                uiState.remoteParticipants.size <= 1 &&
                uiState.localVideoEnabled &&
                largeFeed == FrontlineFeed.Local
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(3.dp, FrontlineAccent)
            )
        }

        val chipIsLocal = largeFeed == FrontlineFeed.Local
        val showLargeFeedChip =
            isCallSurfacePhase &&
                !waitingForRemote &&
                uiState.remoteParticipants.size <= 1 &&
                (
                    (chipIsLocal && uiState.localVideoEnabled) ||
                        (!chipIsLocal && remote?.videoEnabled == true)
                    )
        if (showLargeFeedChip) {
            FrontlineNameChip(
                label = if (chipIsLocal) "You" else remoteDisplayName(remote),
                muted = if (chipIsLocal) !uiState.localAudioEnabled else remote?.audioEnabled == false,
                audioLevel = if (chipIsLocal) uiState.localAudioLevel else remote?.audioLevel ?: 0f,
                broadcasting = false,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp),
            )
        }

        if (isCallSurfacePhase && !waitingForRemote && uiState.remoteParticipants.size <= 1 && pipFeed != null && !pipInPanel) {
            pip(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 12.dp, end = 14.dp)
                    .zIndex(2f)
            )
        }
    }
}

@Composable
private fun FrontlinePhaseSurface(
    uiState: CallUiState,
    modifier: Modifier = Modifier,
) {
    val title = when (uiState.phase) {
        CallPhase.CreatingRoom -> "Creating call"
        CallPhase.AwaitingPermissions -> "Waiting for permissions"
        CallPhase.Joining -> "Joining call"
        CallPhase.Ending -> "Ending call"
        CallPhase.Error -> uiState.errorMessageText?.takeIf { it.isNotBlank() } ?: "Call failed"
        CallPhase.Idle -> "Call ended"
        CallPhase.Waiting -> "Waiting"
        CallPhase.InCall -> "Connected"
    }
    val subtitle = when (uiState.phase) {
        CallPhase.AwaitingPermissions -> "Allow microphone and camera access to continue"
        CallPhase.Error -> "End the call and try again"
        CallPhase.Idle -> "The session is no longer active"
        else -> null
    }
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FrontlineLocalAvatar(size = 124.dp, fontSize = 48.sp, displayName = uiState.localDisplayName)
        Spacer(Modifier.height(22.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = subtitle,
                color = FrontlineDim,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FrontlineMultiPartyStage(
    uiState: CallUiState,
    localContentMode: Boolean,
    eglContext: EglBase.Context,
    localAspectRatio: Float,
    remoteAspectRatios: MutableMap<String, Float>,
    pinnedParticipantId: String?,
    onPinnedParticipantIdChanged: (String?) -> Unit,
    localZoomTransformState: androidx.compose.foundation.gestures.TransformableState,
    localRendererEvents: RendererCommon.RendererEvents,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    strings: Map<SerenadaString, String>?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val localId = uiState.localCid ?: "local"
    val hasLocalContent = localContentMode
    val contentSource = when {
        hasLocalContent -> {
            val type = when {
                uiState.isScreenSharing -> ContentType.SCREEN_SHARE
                uiState.localCameraMode == LocalCameraMode.WORLD -> ContentType.WORLD_CAMERA
                else -> ContentType.COMPOSITE_CAMERA
            }
            ContentSource(
                type = type,
                ownerParticipantId = localId,
                aspectRatio = localAspectRatio.takeIf { it > 0f },
            )
        }
        uiState.remoteContentCid != null -> ContentSource(
            type = ContentType.fromWire(uiState.remoteContentType),
            ownerParticipantId = uiState.remoteContentCid,
            aspectRatio = remoteAspectRatios[uiState.remoteContentCid],
        )
        else -> null
    }
    val defaultPrimaryParticipantId = uiState.remoteParticipants.firstOrNull()?.cid ?: localId
    val effectivePinnedParticipantId =
        if (contentSource == null) pinnedParticipantId ?: defaultPrimaryParticipantId else null

    BoxWithConstraints(modifier = modifier) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val layout = remember(
            viewportWidthPx,
            viewportHeightPx,
            localId,
            uiState.localVideoEnabled,
            localAspectRatio,
            uiState.remoteParticipants,
            remoteAspectRatios.toMap(),
            effectivePinnedParticipantId,
            contentSource,
        ) {
            val participants =
                uiState.remoteParticipants.map { participant ->
                    SceneParticipant(
                        id = participant.cid,
                        role = ParticipantRole.REMOTE,
                        videoEnabled = participant.videoEnabled,
                        videoAspectRatio = remoteAspectRatios[participant.cid],
                    )
                } + SceneParticipant(
                    id = localId,
                    role = ParticipantRole.LOCAL,
                    videoEnabled = uiState.localVideoEnabled,
                    videoAspectRatio = localAspectRatio.takeIf { it > 0f },
                )

            computeLayout(
                CallScene(
                    viewportWidth = viewportWidthPx,
                    viewportHeight = viewportHeightPx,
                    safeAreaInsets = Insets(),
                    participants = participants,
                    localParticipantId = localId,
                    activeSpeakerId = null,
                    pinnedParticipantId = effectivePinnedParticipantId,
                    contentSource = contentSource,
                    userPrefs = UserLayoutPrefs(dominantFit = FitMode.COVER),
                )
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            layout.tiles.sortedBy { it.zOrder }.forEach { tile ->
                key(tile.id, tile.type) {
                    val isContentTile = tile.type == OccupantType.CONTENT_SOURCE
                    val contentOwnerCid = contentSource?.ownerParticipantId
                    val isLocal = tile.id == localId
                    val isLocalContent = isContentTile && contentOwnerCid == localId
                    val isRemoteContent = isContentTile && contentOwnerCid != null && contentOwnerCid != localId
                    val remote = if (isRemoteContent) {
                        uiState.remoteParticipants.firstOrNull { it.cid == contentOwnerCid }
                    } else if (!isLocal) {
                        uiState.remoteParticipants.firstOrNull { it.cid == tile.id }
                    } else {
                        null
                    }
                    val tileWidth = with(density) { tile.frame.width.toDp() }
                    val tileHeight = with(density) { tile.frame.height.toDp() }
                    val tileX = with(density) { tile.frame.x.toDp() }
                    val tileY = with(density) { tile.frame.y.toDp() }
                    val tileCornerRadius = with(density) { tile.cornerRadius.toDp() }
                    FrontlineLayoutTile(
                        tileId = tile.id,
                        isLocal = isLocal,
                        isContentTile = isContentTile,
                        isLocalContent = isLocalContent,
                        remote = remote,
                        uiState = uiState,
                        eglContext = eglContext,
                        localContentMode = localContentMode,
                        localZoomTransformState = localZoomTransformState,
                        localRendererEvents = localRendererEvents,
                        remoteRendererEvents = remote?.let { participant ->
                            remember(participant.cid, mainHandler) {
                                aspectRatioRendererEvents(mainHandler) { ratio ->
                                    remoteAspectRatios[participant.cid] = clampStageTileAspectRatio(ratio)
                                }
                            }
                        },
                        attachLocalSink = attachLocalSink,
                        detachLocalSink = detachLocalSink,
                        attachRemoteSinkForCid = attachRemoteSinkForCid,
                        detachRemoteSinkForCid = detachRemoteSinkForCid,
                        contentScale = if (tile.fit == FitMode.CONTAIN) ContentScale.Fit else ContentScale.Crop,
                        pinned = tile.id == pinnedParticipantId && !isContentTile,
                        onTogglePinned = {
                            if (!isContentTile) {
                                onPinnedParticipantIdChanged(
                                    if (tile.id == pinnedParticipantId) null else tile.id
                                )
                            }
                        },
                        strings = strings,
                        modifier = Modifier
                            .offset(x = tileX, y = tileY)
                            .size(width = tileWidth, height = tileHeight)
                            .clip(RoundedCornerShape(tileCornerRadius)),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrontlineLayoutTile(
    tileId: String,
    isLocal: Boolean,
    isContentTile: Boolean,
    isLocalContent: Boolean,
    remote: RemoteParticipant?,
    uiState: CallUiState,
    eglContext: EglBase.Context,
    localContentMode: Boolean,
    localZoomTransformState: androidx.compose.foundation.gestures.TransformableState,
    localRendererEvents: RendererCommon.RendererEvents,
    remoteRendererEvents: RendererCommon.RendererEvents?,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    contentScale: ContentScale,
    pinned: Boolean,
    onTogglePinned: () -> Unit,
    strings: Map<SerenadaString, String>?,
    modifier: Modifier = Modifier,
) {
    val displayName =
        if (isLocal) uiState.localDisplayName?.takeIf { it.isNotBlank() } ?: "You"
        else remoteDisplayName(remote)
    val muted = if (isLocal) !uiState.localAudioEnabled else remote?.audioEnabled == false
    val audioLevel = if (isLocal) uiState.localAudioLevel else remote?.audioLevel ?: 0f
    val videoEnabled = when {
        isLocal || isLocalContent -> uiState.localVideoEnabled || uiState.isScreenSharing
        else -> remote?.videoEnabled == true
    }

    Box(
        modifier = modifier
            .background(FrontlineSurface)
            .clipToBounds()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onTogglePinned,
                onClick = {},
            )
    ) {
        when {
            isLocal || isLocalContent -> {
                if (videoEnabled) {
                    TextureVideoSurface(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (isLocalContent && localContentMode) Modifier.transformable(localZoomTransformState)
                                else Modifier
                            ),
                        rendererName = "frontline-$tileId",
                        eglContext = eglContext,
                        onAttach = attachLocalSink,
                        onDetach = detachLocalSink,
                        mirror = !isLocalContent && uiState.isFrontCamera && !uiState.isScreenSharing,
                        contentScale = if (uiState.isScreenSharing && isLocalContent) ContentScale.Fit else contentScale,
                        rendererEvents = localRendererEvents,
                    )
                } else {
                    FrontlineCameraOffTile(
                        isLocal = true,
                        participant = null,
                        displayName = displayName,
                        text = resolveString(SerenadaString.CallCameraOff, strings),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            remote != null && remote.videoEnabled -> {
                TextureVideoSurface(
                    modifier = Modifier.fillMaxSize(),
                    rendererName = "frontline-remote-stage-${remote.cid}",
                    eglContext = eglContext,
                    onAttach = { sink -> attachRemoteSinkForCid(remote.cid, sink) },
                    onDetach = { sink -> detachRemoteSinkForCid(remote.cid, sink) },
                    contentScale = contentScale,
                    rendererEvents = remoteRendererEvents,
                )
            }
            else -> {
                FrontlineCameraOffTile(
                    isLocal = false,
                    participant = remote,
                    displayName = displayName,
                    text = resolveString(SerenadaString.CallVideoOff, strings),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (pinned) {
            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }

        if (!isContentTile) {
            FrontlineNameChip(
                label = displayName,
                muted = muted,
                audioLevel = audioLevel,
                broadcasting = false,
                compact = true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun FrontlineCameraOffTile(
    isLocal: Boolean,
    participant: RemoteParticipant?,
    displayName: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isLocal) {
            FrontlineLocalAvatar(size = 86.dp, fontSize = 34.sp, displayName = displayName)
        } else {
            FrontlineAvatar(
                peerId = participant?.peerId,
                displayName = displayName,
                size = 86.dp,
                fontSize = 32.sp,
                borderWidth = 0.dp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            color = FrontlineDim,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FrontlineLargeSurface(
    feed: FrontlineFeed,
    uiState: CallUiState,
    remote: RemoteParticipant?,
    localContentMode: Boolean,
    eglContext: EglBase.Context,
    localRendererEvents: RendererCommon.RendererEvents,
    remoteRendererEvents: RendererCommon.RendererEvents,
    localZoomTransformState: androidx.compose.foundation.gestures.TransformableState,
    attachLocalRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    attachRemoteRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachRemoteRenderer: (SurfaceViewRenderer) -> Unit,
    strings: Map<SerenadaString, String>?,
    modifier: Modifier = Modifier,
) {
    when {
        feed == FrontlineFeed.Local && uiState.localVideoEnabled -> {
            Box(
                modifier = modifier
                    .clipToBounds()
                    .then(
                        if (localContentMode) Modifier.transformable(localZoomTransformState)
                        else Modifier
                    )
            ) {
                VideoSurface(
                    modifier = Modifier.fillMaxSize(),
                    viewKey = "frontline-local-main",
                    onAttach = { renderer -> attachLocalRenderer(renderer, localRendererEvents) },
                    onDetach = detachLocalRenderer,
                    mirror = uiState.isFrontCamera && !uiState.isScreenSharing,
                    contentScale = if (uiState.isScreenSharing) ContentScale.Fit else ContentScale.Crop,
                    isMediaOverlay = false,
                )
            }
        }
        feed == FrontlineFeed.Remote && remote?.videoEnabled == true -> {
            VideoSurface(
                modifier = modifier.clipToBounds(),
                viewKey = "frontline-remote-main",
                onAttach = { renderer -> attachRemoteRenderer(renderer, remoteRendererEvents) },
                onDetach = detachRemoteRenderer,
                mirror = false,
                contentScale = ContentScale.Crop,
                isMediaOverlay = false,
            )
        }
        else -> {
            val waitingForRemote = uiState.isFrontlineWaitingForRemote()
            if (waitingForRemote) {
                FrontlineWaitingLarge(modifier = modifier)
            } else {
                FrontlineAudioLarge(
                    remote = remote,
                    elapsedLabel = rememberFrontlineCallTimer(uiState.callStartedAtMs),
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun FrontlineWaitingLarge(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Waiting",
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FrontlineWaitingSurface(
    uiState: CallUiState,
    localContentMode: Boolean,
    localRendererEvents: RendererCommon.RendererEvents,
    localZoomTransformState: androidx.compose.foundation.gestures.TransformableState,
    attachLocalRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(FrontlineBlack)
            .clipToBounds()
    ) {
        if (uiState.localVideoEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (localContentMode) Modifier.transformable(localZoomTransformState)
                        else Modifier
                    )
            ) {
                VideoSurface(
                    modifier = Modifier.fillMaxSize(),
                    viewKey = "frontline-local-waiting",
                    onAttach = { renderer -> attachLocalRenderer(renderer, localRendererEvents) },
                    onDetach = detachLocalRenderer,
                    mirror = uiState.isFrontCamera && !uiState.isScreenSharing,
                    contentScale = if (uiState.isScreenSharing) ContentScale.Fit else ContentScale.Crop,
                    isMediaOverlay = false,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.18f))
                )
            }
        }
        FrontlineWaitingLarge(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun FrontlineAudioLarge(
    remote: RemoteParticipant?,
    elapsedLabel: String,
    modifier: Modifier = Modifier,
) {
    val name = remoteDisplayName(remote)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FrontlineAvatar(
            peerId = remote?.peerId,
            displayName = name,
            size = 140.dp,
            fontSize = 58.sp,
            borderWidth = 0.dp,
        )
        Spacer(Modifier.height(18.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FrontlineAudioIndicator(
                muted = remote?.audioEnabled == false,
                audioLevel = remote?.audioLevel ?: 0f,
                size = 22.dp,
            )
            Text(
                text = name,
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = elapsedLabel,
            color = FrontlineDim,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FrontlinePip(
    feed: FrontlineFeed,
    uiState: CallUiState,
    remote: RemoteParticipant?,
    eglContext: EglBase.Context,
    width: Dp,
    height: Dp,
    showSwapHint: Boolean,
    onClick: () -> Unit,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteSink: (VideoSink) -> Unit,
    detachRemoteSink: (VideoSink) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showsLocal = feed == FrontlineFeed.Local
    val borderColor = if (showsLocal && uiState.localVideoEnabled) FrontlineAccent else Color.White.copy(alpha = 0.4f)
    Box(
        modifier = modifier
            .size(width, height)
            .shadow(8.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .border(if (showsLocal && uiState.localVideoEnabled) 2.5.dp else 1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .background(Color(0xFF222222))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
    ) {
        when {
            showsLocal && uiState.localVideoEnabled -> {
                TextureVideoSurface(
                    modifier = Modifier.fillMaxSize().padding(2.5.dp).clip(RoundedCornerShape(12.dp)),
                    rendererName = "frontline-local-pip",
                    eglContext = eglContext,
                    onAttach = attachLocalSink,
                    onDetach = detachLocalSink,
                    mirror = uiState.isFrontCamera && !uiState.isScreenSharing,
                    contentScale = if (uiState.isScreenSharing) ContentScale.Fit else ContentScale.Crop,
                )
            }
            !showsLocal && remote?.videoEnabled == true -> {
                TextureVideoSurface(
                    modifier = Modifier.fillMaxSize().padding(2.5.dp).clip(RoundedCornerShape(12.dp)),
                    rendererName = "frontline-remote-pip",
                    eglContext = eglContext,
                    onAttach = attachRemoteSink,
                    onDetach = detachRemoteSink,
                    contentScale = ContentScale.Crop,
                )
            }
            else -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (showsLocal) {
                        FrontlineLocalAvatar(size = 74.dp, fontSize = 34.sp)
                    } else {
                        FrontlineAvatar(
                            peerId = remote?.peerId,
                            displayName = remoteDisplayName(remote),
                            size = 74.dp,
                            fontSize = 30.sp,
                            borderWidth = 0.dp,
                        )
                    }
                }
            }
        }
        if (showSwapHint) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(22.dp),
                color = Color.Black.copy(alpha = 0.62f),
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Default.FlipCameraIos,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(5.dp),
                )
            }
        }
        FrontlineNameChip(
            label = if (showsLocal) "You" else remoteDisplayName(remote),
            muted = if (showsLocal) !uiState.localAudioEnabled else remote?.audioEnabled == false,
            audioLevel = if (showsLocal) uiState.localAudioLevel else remote?.audioLevel ?: 0f,
            broadcasting = false,
            compact = true,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
        )
    }
}

@Composable
private fun FrontlineControlsPanel(
    uiState: CallUiState,
    videoConfirming: Boolean,
    isLandscape: Boolean,
    isTabletLandscape: Boolean,
    panelWidth: Dp,
    callControlsEnabled: Boolean,
    videoControlsEnabled: Boolean,
    showMoreButton: Boolean,
    snapshotSource: SnapshotSource?,
    snapshotHandler: ((SnapshotSource) -> Unit)?,
    reservePreviewActions: Boolean,
    pipInPanel: Boolean,
    pip: @Composable (Modifier) -> Unit,
    onVideoTap: () -> Unit,
    onToggleAudio: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleFlashlight: () -> Unit,
    onSnapshotFlash: () -> Unit,
    onMore: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelPadding = if (isLandscape) {
        PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)
    } else {
        PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp)
    }
    Column(
        modifier = modifier
            .background(FrontlinePanel)
            .navigationBarsPadding()
            .padding(panelPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pipInPanel) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                pip(Modifier)
            }
        } else if (isLandscape) {
            Spacer(Modifier.weight(1f))
        }

        if (callControlsEnabled) {
            FrontlinePreviewActions(
                uiState = uiState,
                snapshotSource = snapshotSource,
                snapshotHandler = snapshotHandler,
                reserveWhenHidden = reservePreviewActions,
                compact = isLandscape,
                onToggleFlashlight = onToggleFlashlight,
                onSnapshotFlash = onSnapshotFlash,
                onFlipCamera = onFlipCamera,
            )

            FrontlineControlGrid(
                uiState = uiState,
                videoConfirming = videoConfirming,
                isLandscape = isLandscape,
                isTablet = isTabletLandscape || (!isLandscape && panelWidth >= 320.dp),
                videoControlsEnabled = videoControlsEnabled,
                showMoreButton = showMoreButton,
                onVideoTap = onVideoTap,
                onToggleAudio = onToggleAudio,
                onMore = onMore,
            )
        } else {
            Spacer(Modifier.height(if (isLandscape) 24.dp else 12.dp))
        }
        Spacer(Modifier.height(if (isLandscape) 20.dp else 12.dp))
        FrontlineEndButton(
            height = 56.dp,
            onClick = onEndCall,
        )

        if (isLandscape && !pipInPanel) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun FrontlinePreviewActions(
    uiState: CallUiState,
    snapshotSource: SnapshotSource?,
    snapshotHandler: ((SnapshotSource) -> Unit)?,
    reserveWhenHidden: Boolean,
    compact: Boolean,
    onToggleFlashlight: () -> Unit,
    onSnapshotFlash: () -> Unit,
    onFlipCamera: () -> Unit,
) {
    val visible = uiState.localVideoEnabled
    if (!visible && !reserveWhenHidden) return
    val flashEnabled = uiState.isFlashAvailable
    val showFlash = visible
    val showSnapshot = visible && snapshotSource != null && snapshotHandler != null
    val showFlip = visible && uiState.availableCameraModes.size > 1
    val rowHeight = if (compact) 84.dp else 92.dp
    Row(
        modifier = Modifier
            .height(rowHeight)
            .fillMaxWidth()
            .alpha(if (visible) 1f else 0f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrontlineRoundActionButton(
            visible = showFlash,
            size = 56.dp,
            icon = if (uiState.isFlashEnabled) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
            active = uiState.isFlashEnabled && flashEnabled,
            enabled = flashEnabled,
            contentDescription = resolveString(SerenadaString.CallToggleFlashlight, null),
            onClick = onToggleFlashlight,
        )
        Spacer(Modifier.width(22.dp))
        FrontlineRoundActionButton(
            visible = showSnapshot,
            size = 72.dp,
            icon = Icons.Default.PhotoCamera,
            primary = true,
            contentDescription = resolveString(SerenadaString.CallTakeSnapshot, null),
            onClick = {
                val source = snapshotSource
                val handler = snapshotHandler
                if (source != null && handler != null) {
                    onSnapshotFlash()
                    handler(source)
                }
            },
            modifier = Modifier.testTag("call.frontline.takeSnapshot"),
        )
        Spacer(Modifier.width(22.dp))
        FrontlineRoundActionButton(
            visible = showFlip,
            size = 56.dp,
            icon = Icons.Default.FlipCameraIos,
            contentDescription = "Flip camera",
            onClick = onFlipCamera,
        )
    }
}

@Composable
private fun FrontlineRoundActionButton(
    visible: Boolean,
    size: Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    if (!visible) {
        Spacer(modifier.size(size))
        return
    }
    val background = when {
        primary -> Color.White.copy(alpha = 0.95f)
        active -> Color.White
        !enabled -> Color.Black.copy(alpha = 0.28f)
        else -> Color.Black.copy(alpha = 0.58f)
    }
    val tint = when {
        primary || active -> Color.Black
        enabled -> Color.White
        else -> Color.White.copy(alpha = 0.42f)
    }
    Surface(
        modifier = modifier
            .size(size)
            .border(
                width = if (primary) 4.dp else 1.dp,
                color = Color.White.copy(alpha = if (primary) 0.45f else if (enabled) 0.28f else 0.12f),
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        color = background,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(if (primary) 30.dp else 24.dp),
            )
        }
    }
}

@Composable
private fun FrontlineControlGrid(
    uiState: CallUiState,
    videoConfirming: Boolean,
    isLandscape: Boolean,
    isTablet: Boolean,
    videoControlsEnabled: Boolean,
    showMoreButton: Boolean,
    onVideoTap: () -> Unit,
    onToggleAudio: () -> Unit,
    onMore: () -> Unit,
) {
    val buttonHeight = when {
        isTablet -> 86.dp
        isLandscape -> 68.dp
        else -> 74.dp
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (videoControlsEnabled) {
                FrontlineGridButton(
                    label = if (uiState.localVideoEnabled) "VIDEO ON" else "VIDEO",
                    icon = if (uiState.localVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    active = uiState.localVideoEnabled,
                    confirming = videoConfirming,
                    onClick = onVideoTap,
                    modifier = Modifier.weight(1f).height(buttonHeight),
                )
            }
            FrontlineGridButton(
                label = "MUTE",
                icon = if (uiState.localAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                danger = !uiState.localAudioEnabled,
                onClick = onToggleAudio,
                modifier = Modifier.weight(1f).height(buttonHeight),
            )
        }
        if (showMoreButton) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FrontlineGridButton(
                    label = "MORE",
                    icon = Icons.Default.MoreVert,
                    onClick = onMore,
                    modifier = Modifier.weight(1f).height(buttonHeight),
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FrontlineGridButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    danger: Boolean = false,
    confirming: Boolean = false,
) {
    val background = when {
        active -> FrontlineAccent
        danger -> FrontlineDanger
        else -> FrontlineSurface
    }
    val foreground = if (active) Color.Black else Color.White
    Surface(
        modifier = modifier
            .frontlineGridOutline(confirming)
            .clickable(onClick = onClick),
        color = background,
        shape = RoundedCornerShape(14.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = foreground,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    color = foreground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (confirming) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .clickable(onClick = onClick),
                    color = Color.Black,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = "TAP AGAIN",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

private fun Modifier.frontlineGridOutline(confirming: Boolean): Modifier {
    val shape = RoundedCornerShape(14.dp)
    if (!confirming) {
        return border(
            width = 1.5.dp,
            color = FrontlineBorder,
            shape = shape,
        )
    }
    return drawWithContent {
        drawContent()
        val strokeWidth = 2.dp.toPx()
        val inset = strokeWidth / 2f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                    phase = 0f,
                ),
            ),
        )
    }
}

@Composable
private fun FrontlineEndButton(
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .requiredSize(width = 140.dp, height = height)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(FrontlineDanger)
            .clickable(onClick = onClick)
            .testTag("call.frontline.endCall"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "END",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun FrontlineMoreSheet(
    visible: Boolean,
    screenSharingEnabled: Boolean,
    inviteEnabled: Boolean,
    shareEnabled: Boolean,
    isScreenSharing: Boolean,
    onDismiss: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onInvite: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onDismiss)
            )
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(FrontlineSheet)
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.24f))
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "MORE",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (screenSharingEnabled) {
                        FrontlineSheetItem(
                            icon = if (isScreenSharing) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                            title = if (isScreenSharing) "Stop screen share" else "Share screen",
                            subtitle = if (isScreenSharing) "Return to camera" else "Show your phone",
                            onClick = onToggleScreenShare,
                        )
                    }
                    if (inviteEnabled) {
                        FrontlineSheetItem(
                            icon = Icons.Default.NotificationsActive,
                            title = "Invite to call",
                            subtitle = "Bring in another teammate",
                            onClick = onInvite,
                        )
                    }
                    if (shareEnabled) {
                        FrontlineSheetItem(
                            icon = Icons.Default.Share,
                            title = resolveString(SerenadaString.CallShareInvitation, null),
                            subtitle = "Send the call link",
                            onClick = onShare,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable(onClick = onDismiss),
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Close", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrontlineSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            color = Color.White.copy(alpha = 0.08f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = FrontlineDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FrontlineNameChip(
    label: String,
    muted: Boolean,
    audioLevel: Float,
    broadcasting: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.62f), shape)
            .border(
                width = 1.dp,
                color = if (broadcasting) FrontlineAccent.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.16f),
                shape = shape,
            )
            .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = if (compact) 4.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
    ) {
        FrontlineAudioIndicator(
            muted = muted,
            audioLevel = audioLevel,
            size = 14.dp,
        )
        Text(
            text = label,
            color = if (broadcasting) FrontlineAccent else Color.White,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FrontlineAudioIndicator(
    muted: Boolean,
    audioLevel: Float,
    size: Dp,
) {
    if (muted) {
        Icon(
            imageVector = Icons.Default.MicOff,
            contentDescription = null,
            tint = FrontlineDanger,
            modifier = Modifier.size(size),
        )
    } else {
        AudioActivityIndicator(level = audioLevel, size = size)
    }
}

@Composable
private fun FrontlineAvatar(
    peerId: String?,
    displayName: String?,
    size: Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    borderWidth: Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(borderWidth, Color.White, CircleShape)
                } else {
                    Modifier
                }
            )
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        RemoteAvatar(
            peerId = peerId,
            displayName = displayName,
            size = size,
            fontSize = fontSize,
        )
    }
}

@Composable
private fun FrontlineLocalAvatar(
    size: Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    displayName: String? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF2A3540)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsFor(displayName).ifBlank { "You" },
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun rememberFrontlineCallTimer(startedAtMs: Long?): String {
    val fallbackStartedAt = remember { System.currentTimeMillis() }
    val startedAt = startedAtMs ?: fallbackStartedAt
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedSeconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun remoteDisplayName(remote: RemoteParticipant?): String {
    return remote?.displayName?.takeIf { it.isNotBlank() } ?: "Participant"
}

private fun CallUiState.isFrontlineWaitingForRemote(): Boolean {
    return (phase == CallPhase.Waiting || phase == CallPhase.InCall) &&
        remoteParticipants.isEmpty()
}

private fun shareFrontlineCallLink(
    context: Context,
    link: String,
    chooserTitle: String,
) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}
