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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
private val FrontlineStageLocalAccentWidth = 2.5.dp
private const val FRONTLINE_ZOOM_CHANGE_THRESHOLD = 0.01f
private const val FRONTLINE_CONTENT_SPOTLIGHT_PREFIX = "content:"
private const val FRONTLINE_MORE_BUTTON_HEIGHT_TO_WIDTH_RATIO = 1.62f

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

    var pipSwapped by rememberSaveable { mutableStateOf(false) }
    var isMoreSheetVisible by rememberSaveable { mutableStateOf(false) }
    var showSnapshotFlash by remember { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var debugTapTimestampMs by remember { mutableStateOf(0L) }
    var localAspectRatio by remember { mutableStateOf<Float?>(null) }
    val remoteTileAspectRatios = remember { mutableStateMapOf<String, Float>() }
    var pinnedSpotlightId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSpotlightId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastVideoStartedParticipantId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousRemoteVideoEnabled by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    val localContentMode =
        uiState.localCameraMode == LocalCameraMode.WORLD ||
            uiState.localCameraMode == LocalCameraMode.COMPOSITE ||
            uiState.isScreenSharing
    val localSpotlightId = uiState.localCid ?: "local"
    val activeContentOwnerId = when {
        uiState.isScreenSharing -> localSpotlightId
        uiState.localVideoEnabled &&
            (
                uiState.localCameraMode == LocalCameraMode.WORLD ||
                    uiState.localCameraMode == LocalCameraMode.COMPOSITE
                ) -> localSpotlightId
        uiState.remoteContentCid != null -> uiState.remoteContentCid
        else -> null
    }
    val activeContentSpotlightId = activeContentOwnerId?.frontlineContentSpotlightId()
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
                shareLink(
                    context = context,
                    text = roomLink,
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
    LaunchedEffect(activeContentSpotlightId) {
        if (activeContentSpotlightId != null) {
            selectedSpotlightId = activeContentSpotlightId
        } else {
            if (selectedSpotlightId.isFrontlineContentSpotlightId()) selectedSpotlightId = null
            if (pinnedSpotlightId.isFrontlineContentSpotlightId()) pinnedSpotlightId = null
        }
    }
    LaunchedEffect(uiState.remoteParticipants.map { it.cid to it.videoEnabled }) {
        val nextRemoteVideoEnabled = uiState.remoteParticipants.associate { it.cid to it.videoEnabled }
        if (previousRemoteVideoEnabled.isNotEmpty()) {
            uiState.remoteParticipants
                .lastOrNull { participant ->
                    participant.videoEnabled && previousRemoteVideoEnabled[participant.cid] != true
                }
                ?.let { participant -> lastVideoStartedParticipantId = participant.cid }
        }
        previousRemoteVideoEnabled = nextRemoteVideoEnabled
    }
    LaunchedEffect(
        uiState.localCid,
        uiState.localVideoEnabled,
        uiState.remoteParticipants.map { it.cid to it.videoEnabled },
        activeContentSpotlightId,
    ) {
        val activeCids = uiState.remoteParticipants.map { it.cid }.toSet()
        val activeSpotlightIds = activeCids + localSpotlightId + listOfNotNull(activeContentSpotlightId)
        remoteTileAspectRatios.keys
            .filter { it !in activeCids }
            .forEach { remoteTileAspectRatios.remove(it) }
        if (pinnedSpotlightId != null && pinnedSpotlightId !in activeSpotlightIds) pinnedSpotlightId = null
        if (selectedSpotlightId != null && selectedSpotlightId !in activeSpotlightIds) selectedSpotlightId = null
        if (
            lastVideoStartedParticipantId != null &&
                uiState.remoteParticipants.none { it.cid == lastVideoStartedParticipantId && it.videoEnabled }
        ) {
            lastVideoStartedParticipantId = null
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
                            strings = strings,
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
                            activeContentSpotlightId = activeContentSpotlightId,
                            pinnedSpotlightId = pinnedSpotlightId,
                            selectedSpotlightId = selectedSpotlightId,
                            lastVideoStartedParticipantId = lastVideoStartedParticipantId,
                            onPinnedSpotlightIdChanged = { pinnedSpotlightId = it },
                            onSelectedSpotlightIdChanged = { selectedSpotlightId = it },
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
                                if (uiState.localVideoEnabled) {
                                    pipSwapped = false
                                }
                                onToggleVideo()
                            },
                            onToggleAudio = onToggleAudio,
                            onFlipCamera = onFlipCamera,
                            onToggleFlashlight = onToggleFlashlight,
                            onSnapshotFlash = { showSnapshotFlash = true },
                            onMore = { isMoreSheetVisible = true },
                            onEndCall = onEndCall,
                            strings = strings,
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
                            activeContentSpotlightId = activeContentSpotlightId,
                            pinnedSpotlightId = pinnedSpotlightId,
                            selectedSpotlightId = selectedSpotlightId,
                            lastVideoStartedParticipantId = lastVideoStartedParticipantId,
                            onPinnedSpotlightIdChanged = { pinnedSpotlightId = it },
                            onSelectedSpotlightIdChanged = { selectedSpotlightId = it },
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
                                if (uiState.localVideoEnabled) {
                                    pipSwapped = false
                                }
                                onToggleVideo()
                            },
                            onToggleAudio = onToggleAudio,
                            onFlipCamera = onFlipCamera,
                            onToggleFlashlight = onToggleFlashlight,
                            onSnapshotFlash = { showSnapshotFlash = true },
                            onMore = { isMoreSheetVisible = true },
                            onEndCall = onEndCall,
                            strings = strings,
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
                    strings = strings,
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
    activeContentSpotlightId: String?,
    pinnedSpotlightId: String?,
    selectedSpotlightId: String?,
    lastVideoStartedParticipantId: String?,
    onPinnedSpotlightIdChanged: (String?) -> Unit,
    onSelectedSpotlightIdChanged: (String?) -> Unit,
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
                    strings = strings,
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
                    strings = strings,
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
                    activeContentSpotlightId = activeContentSpotlightId,
                    pinnedSpotlightId = pinnedSpotlightId,
                    selectedSpotlightId = selectedSpotlightId,
                    lastVideoStartedParticipantId = lastVideoStartedParticipantId,
                    onPinnedSpotlightIdChanged = onPinnedSpotlightIdChanged,
                    onSelectedSpotlightIdChanged = onSelectedSpotlightIdChanged,
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
                label = if (chipIsLocal) localDisplayName(uiState, strings) else remoteDisplayName(remote),
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
    strings: Map<SerenadaString, String>?,
    modifier: Modifier = Modifier,
) {
    val title = when (uiState.phase) {
        CallPhase.CreatingRoom,
        CallPhase.AwaitingPermissions,
        CallPhase.Joining,
        CallPhase.Ending,
        CallPhase.Idle -> resolveString(SerenadaString.CallWaitingShort, strings)
        CallPhase.Error -> uiState.errorMessageText?.takeIf { it.isNotBlank() }
            ?: resolveString(SerenadaString.CallWaitingShort, strings)
        CallPhase.Waiting -> resolveString(SerenadaString.FrontlineWaiting, strings)
        CallPhase.InCall -> resolveString(SerenadaString.CallWaitingShort, strings)
    }
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FrontlineLocalAvatar(
            size = 124.dp,
            fontSize = 48.sp,
            displayName = uiState.localDisplayName,
            strings = strings,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FrontlineMultiPartyStage(
    uiState: CallUiState,
    localContentMode: Boolean,
    eglContext: EglBase.Context,
    localAspectRatio: Float,
    remoteAspectRatios: MutableMap<String, Float>,
    activeContentSpotlightId: String?,
    pinnedSpotlightId: String?,
    selectedSpotlightId: String?,
    lastVideoStartedParticipantId: String?,
    onPinnedSpotlightIdChanged: (String?) -> Unit,
    onSelectedSpotlightIdChanged: (String?) -> Unit,
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
    val activeContentOwnerId = activeContentSpotlightId?.removePrefix(FRONTLINE_CONTENT_SPOTLIGHT_PREFIX)
    val contentSource = when {
        hasLocalContent && activeContentOwnerId == localId -> {
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
        uiState.remoteContentCid != null && activeContentOwnerId == uiState.remoteContentCid -> ContentSource(
            type = ContentType.fromWire(uiState.remoteContentType),
            ownerParticipantId = uiState.remoteContentCid,
            aspectRatio = remoteAspectRatios[uiState.remoteContentCid],
        )
        else -> null
    }
    val participantIds = remember(localId, uiState.remoteParticipants) {
        uiState.remoteParticipants.map { it.cid }.toSet() + localId
    }
    val availableSpotlightIds = participantIds + listOfNotNull(activeContentSpotlightId)
    val defaultPrimaryParticipantId =
        lastVideoStartedParticipantId?.takeIf { id -> id in participantIds }
            ?: uiState.remoteParticipants.firstOrNull()?.cid
            ?: localId
    val effectiveSpotlightId =
        pinnedSpotlightId?.takeIf { it in availableSpotlightIds }
            ?: selectedSpotlightId?.takeIf { it in availableSpotlightIds }
            ?: defaultPrimaryParticipantId
    val spotlightIsContent =
        contentSource != null &&
            activeContentSpotlightId != null &&
            effectiveSpotlightId == activeContentSpotlightId

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
            activeContentSpotlightId,
            effectiveSpotlightId,
            spotlightIsContent,
            contentSource,
        ) {
            val baseParticipants =
                uiState.remoteParticipants.map { participant ->
                    SceneParticipant(
                        id = participant.cid,
                        role = ParticipantRole.REMOTE,
                        videoEnabled = participant.videoEnabled,
                        videoAspectRatio = remoteAspectRatios[participant.cid],
                    )
                } + if (
                    frontlineIncludesNormalLocalStageTile(
                        localSpotlightId = localId,
                        activeContentOwnerId = contentSource?.ownerParticipantId,
                        contentTileIsSpotlight = spotlightIsContent,
                    )
                ) {
                    listOf(
                        SceneParticipant(
                            id = localId,
                            role = ParticipantRole.LOCAL,
                            videoEnabled = uiState.localVideoEnabled,
                            videoAspectRatio = localAspectRatio.takeIf { it > 0f },
                        )
                    )
                } else {
                    emptyList()
                }
            val participants =
                if (contentSource != null && activeContentSpotlightId != null && !spotlightIsContent) {
                    baseParticipants + SceneParticipant(
                        id = activeContentSpotlightId,
                        role = ParticipantRole.REMOTE,
                        videoEnabled = true,
                        videoAspectRatio = contentSource.aspectRatio,
                    )
                } else {
                    baseParticipants
                }

            computeLayout(
                CallScene(
                    viewportWidth = viewportWidthPx,
                    viewportHeight = viewportHeightPx,
                    safeAreaInsets = Insets(),
                    participants = participants,
                    localParticipantId = localId,
                    activeSpeakerId = null,
                    pinnedParticipantId = if (spotlightIsContent) null else effectiveSpotlightId,
                    contentSource = if (spotlightIsContent) contentSource else null,
                    userPrefs = UserLayoutPrefs(dominantFit = FitMode.COVER),
                )
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            layout.tiles.sortedBy { it.zOrder }.forEach { tile ->
                key(tile.id, tile.type) {
                    val isSyntheticContentTile = activeContentSpotlightId != null && tile.id == activeContentSpotlightId
                    val isContentTile = tile.type == OccupantType.CONTENT_SOURCE || isSyntheticContentTile
                    val contentOwnerCid = if (isContentTile) contentSource?.ownerParticipantId else null
                    val tileSpotlightId = if (isContentTile && activeContentSpotlightId != null) {
                        activeContentSpotlightId
                    } else {
                        tile.id
                    }
                    val isLocal = tile.id == localId && !isContentTile
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
                        cornerRadius = tileCornerRadius,
                        pinned = tileSpotlightId == pinnedSpotlightId,
                        onSelect = { onSelectedSpotlightIdChanged(tileSpotlightId) },
                        onTogglePinned = {
                            onPinnedSpotlightIdChanged(
                                if (tileSpotlightId == pinnedSpotlightId) null else tileSpotlightId
                            )
                        },
                        strings = strings,
                        modifier = Modifier
                            .offset(x = tileX, y = tileY)
                            .size(width = tileWidth, height = tileHeight)
                            .clip(RoundedCornerShape(tileCornerRadius)),
                    )
                }
            }

            layout.localPip?.let { pip ->
                val pipWidth = with(density) { pip.frame.width.toDp() }
                val pipHeight = with(density) { pip.frame.height.toDp() }
                val pipX = with(density) { pip.frame.x.toDp() }
                val pipY = with(density) { pip.frame.y.toDp() }
                val pipCornerRadius = with(density) { pip.cornerRadius.toDp() }
                key(pip.participantId, "pip") {
                    FrontlineLayoutTile(
                        tileId = pip.participantId,
                        isLocal = true,
                        isContentTile = false,
                        isLocalContent = false,
                        remote = null,
                        uiState = uiState,
                        eglContext = eglContext,
                        localContentMode = localContentMode,
                        localZoomTransformState = localZoomTransformState,
                        localRendererEvents = localRendererEvents,
                        remoteRendererEvents = null,
                        attachLocalSink = attachLocalSink,
                        detachLocalSink = detachLocalSink,
                        attachRemoteSinkForCid = attachRemoteSinkForCid,
                        detachRemoteSinkForCid = detachRemoteSinkForCid,
                        contentScale = if (pip.fit == FitMode.CONTAIN) ContentScale.Fit else ContentScale.Crop,
                        cornerRadius = pipCornerRadius,
                        pinned = false,
                        onSelect = { onSelectedSpotlightIdChanged(pip.participantId) },
                        onTogglePinned = {
                            onPinnedSpotlightIdChanged(
                                if (pip.participantId == pinnedSpotlightId) null else pip.participantId
                            )
                        },
                        strings = strings,
                        modifier = Modifier
                            .offset(x = pipX, y = pipY)
                            .size(width = pipWidth, height = pipHeight)
                            .clip(RoundedCornerShape(pipCornerRadius)),
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
    cornerRadius: Dp,
    pinned: Boolean,
    onSelect: () -> Unit,
    onTogglePinned: () -> Unit,
    strings: Map<SerenadaString, String>?,
    modifier: Modifier = Modifier,
) {
    val displayName =
        if (isLocal) localDisplayName(uiState, strings)
        else remoteDisplayName(remote)
    val muted = if (isLocal) !uiState.localAudioEnabled else remote?.audioEnabled == false
    val audioLevel = if (isLocal) uiState.localAudioLevel else remote?.audioLevel ?: 0f
    val videoEnabled = when {
        isLocal || isLocalContent -> uiState.localVideoEnabled || uiState.isScreenSharing
        else -> remote?.videoEnabled == true
    }
    val showLocalVideoAccent = (isLocal || isLocalContent) && uiState.localVideoEnabled
    val tileShape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .background(FrontlineSurface)
            .clipToBounds()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onTogglePinned,
                onClick = onSelect,
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

        if (showLocalVideoAccent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(FrontlineStageLocalAccentWidth, FrontlineAccent, tileShape),
            )
        }
    }
}

@Composable
private fun FrontlineCameraOffTile(
    isLocal: Boolean,
    participant: RemoteParticipant?,
    displayName: String,
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
                FrontlineWaitingLarge(
                    strings = strings,
                    modifier = modifier,
                )
            } else {
                FrontlineAudioLarge(
                    remote = remote,
                    elapsedLabel = rememberFrontlineCallTimer(uiState.callStartedAtMs),
                    strings = strings,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun FrontlineWaitingLarge(
    strings: Map<SerenadaString, String>?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = resolveString(SerenadaString.FrontlineWaiting, strings),
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
    strings: Map<SerenadaString, String>?,
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
        FrontlineWaitingLarge(
            strings = strings,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FrontlineAudioLarge(
    remote: RemoteParticipant?,
    elapsedLabel: String,
    strings: Map<SerenadaString, String>?,
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
            if (name.isNotBlank()) {
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
    strings: Map<SerenadaString, String>?,
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
                        FrontlineLocalAvatar(
                            size = 74.dp,
                            fontSize = 34.sp,
                            strings = strings,
                        )
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
            label = if (showsLocal) localDisplayName(uiState, strings) else remoteDisplayName(remote),
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
    strings: Map<SerenadaString, String>?,
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
                strings = strings,
            )

            FrontlineControlGrid(
                uiState = uiState,
                isLandscape = isLandscape,
                isTablet = isTabletLandscape || (!isLandscape && panelWidth >= 320.dp),
                videoControlsEnabled = videoControlsEnabled,
                showMoreButton = showMoreButton,
                onVideoTap = onVideoTap,
                onToggleAudio = onToggleAudio,
                onMore = onMore,
                strings = strings,
            )
        } else {
            Spacer(Modifier.height(if (isLandscape) 24.dp else 12.dp))
        }
        Spacer(Modifier.height(if (isLandscape) 20.dp else 12.dp))
        FrontlineEndButton(
            height = 56.dp,
            onClick = onEndCall,
            strings = strings,
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
    strings: Map<SerenadaString, String>?,
) {
    val visible = uiState.localVideoEnabled
    if (!visible && !reserveWhenHidden) return
    val flashEnabled = uiState.isFlashAvailable
    val showFlash = visible
    val showSnapshot = visible && snapshotSource != null && snapshotHandler != null
    val showFlip = visible && uiState.availableCameraModes.size > 1
    val rowHeight = if (compact) 84.dp else 92.dp
    val bottomBalancePadding = if (compact) 12.dp else 14.dp
    Row(
        modifier = Modifier
            .height(rowHeight)
            .fillMaxWidth()
            .padding(bottom = bottomBalancePadding)
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
            contentDescription = resolveString(SerenadaString.CallToggleFlashlight, strings),
            onClick = onToggleFlashlight,
        )
        Spacer(Modifier.width(22.dp))
        FrontlineRoundActionButton(
            visible = showSnapshot,
            size = 72.dp,
            icon = Icons.Default.PhotoCamera,
            primary = true,
            contentDescription = resolveString(SerenadaString.CallTakeSnapshot, strings),
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
            contentDescription = resolveString(SerenadaString.FrontlineFlipCamera, strings),
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
    isLandscape: Boolean,
    isTablet: Boolean,
    videoControlsEnabled: Boolean,
    showMoreButton: Boolean,
    onVideoTap: () -> Unit,
    onToggleAudio: () -> Unit,
    onMore: () -> Unit,
    strings: Map<SerenadaString, String>?,
) {
    val buttonHeight = when {
        isTablet -> 86.dp
        isLandscape -> 68.dp
        else -> 74.dp
    }
    val moreButtonWidth = (buttonHeight.value / FRONTLINE_MORE_BUTTON_HEIGHT_TO_WIDTH_RATIO).dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (videoControlsEnabled) {
            FrontlineGridButton(
                label = if (uiState.localVideoEnabled) {
                    resolveString(SerenadaString.FrontlineVideoOn, strings)
                } else {
                    resolveString(SerenadaString.FrontlineVideo, strings)
                },
                icon = if (uiState.localVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                active = uiState.localVideoEnabled,
                onClick = onVideoTap,
                modifier = Modifier.weight(1f).height(buttonHeight),
            )
        }
        FrontlineGridButton(
            label = resolveString(SerenadaString.FrontlineMute, strings),
            icon = if (uiState.localAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
            danger = !uiState.localAudioEnabled,
            onClick = onToggleAudio,
            modifier = Modifier.weight(1f).height(buttonHeight),
        )
        if (showMoreButton) {
            FrontlineGridButton(
                label = resolveString(SerenadaString.FrontlineMore, strings),
                icon = Icons.Default.MoreVert,
                onClick = onMore,
                showLabel = false,
                modifier = Modifier.width(moreButtonWidth).height(buttonHeight),
            )
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
    showLabel: Boolean = true,
) {
    val background = when {
        active -> FrontlineAccent
        danger -> FrontlineDanger
        else -> FrontlineSurface
    }
    val foreground = if (active) Color.Black else Color.White
    Surface(
        modifier = modifier
            .border(
                width = 1.5.dp,
                color = FrontlineBorder,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
        color = background,
        shape = RoundedCornerShape(14.dp),
    ) {
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
            if (showLabel) {
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
        }
    }
}

@Composable
private fun FrontlineEndButton(
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    strings: Map<SerenadaString, String>?,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
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
                text = resolveString(SerenadaString.FrontlineEnd, strings),
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
    strings: Map<SerenadaString, String>?,
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
                    Spacer(Modifier.height(18.dp))
                    if (screenSharingEnabled) {
                        FrontlineSheetItem(
                            icon = if (isScreenSharing) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                            title = if (isScreenSharing) {
                                resolveString(SerenadaString.FrontlineStopScreenShare, strings)
                            } else {
                                resolveString(SerenadaString.FrontlineShareScreen, strings)
                            },
                            subtitle = if (isScreenSharing) {
                                resolveString(SerenadaString.FrontlineReturnToCamera, strings)
                            } else {
                                resolveString(SerenadaString.FrontlineShowYourPhone, strings)
                            },
                            onClick = onToggleScreenShare,
                        )
                    }
                    if (inviteEnabled) {
                        FrontlineSheetItem(
                            icon = Icons.Default.NotificationsActive,
                            title = resolveString(SerenadaString.CallInviteToRoom, strings),
                            subtitle = resolveString(SerenadaString.FrontlineInviteSubtitle, strings),
                            onClick = onInvite,
                        )
                    }
                    if (shareEnabled) {
                        FrontlineSheetItem(
                            icon = Icons.Default.Share,
                            title = resolveString(SerenadaString.CallShareInvitation, strings),
                            subtitle = resolveString(SerenadaString.FrontlineShareLinkSubtitle, strings),
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
                            Text(
                                text = resolveString(SerenadaString.FrontlineClose, strings),
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
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
        if (label.isNotBlank()) {
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
    strings: Map<SerenadaString, String>? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF2A3540)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsFor(displayName).ifBlank { resolveString(SerenadaString.FrontlineYou, strings) },
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

private fun localDisplayName(uiState: CallUiState, strings: Map<SerenadaString, String>?): String {
    return uiState.localDisplayName?.takeIf { it.isNotBlank() }
        ?: resolveString(SerenadaString.FrontlineYou, strings)
}

private fun remoteDisplayName(remote: RemoteParticipant?): String {
    return remote?.displayName?.takeIf { it.isNotBlank() }
        ?: ""
}

private fun frontlineIncludesNormalLocalStageTile(
    localSpotlightId: String,
    activeContentOwnerId: String?,
    contentTileIsSpotlight: Boolean,
): Boolean = activeContentOwnerId != localSpotlightId || contentTileIsSpotlight

private fun String.frontlineContentSpotlightId(): String = "$FRONTLINE_CONTENT_SPOTLIGHT_PREFIX$this"

private fun String?.isFrontlineContentSpotlightId(): Boolean =
    this?.startsWith(FRONTLINE_CONTENT_SPOTLIGHT_PREFIX) == true

private fun CallUiState.isFrontlineWaitingForRemote(): Boolean {
    return (phase == CallPhase.Waiting || phase == CallPhase.InCall) &&
        remoteParticipants.isEmpty()
}
