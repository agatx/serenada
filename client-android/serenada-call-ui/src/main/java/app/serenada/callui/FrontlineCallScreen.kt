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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import app.serenada.core.layout.LayoutMode
import app.serenada.core.layout.LayoutRect
import app.serenada.core.layout.LayoutResult
import app.serenada.core.layout.OccupantType
import app.serenada.core.layout.ParticipantRole
import app.serenada.core.layout.TileLayout
import app.serenada.core.layout.SceneParticipant
import app.serenada.core.layout.UserLayoutPrefs
import app.serenada.core.layout.clampStageTileAspectRatio
import app.serenada.core.layout.computeLayout
import app.serenada.core.call.AudioDevice
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
internal val FrontlineAccent = Color(0xFF15BF54)
private val FrontlineDanger = Color(0xFFF5564B)
private val FrontlineDim = Color(0xFFA1A1AA)
internal val FrontlineSheet = Color(0xFF15161A)
internal val FrontlineSheetRow = Color.White.copy(alpha = 0.09f)
private val FrontlineStageLocalAccentWidth = 2.5.dp
private val FrontlineWearableMaxEdge = 260.dp
private val FrontlineWearableButtonSize = 44.dp
private val FrontlineWearableEndButtonSize = 48.dp
private val FrontlineWearableControlsHorizontalPadding = 8.dp
private val FrontlineWearableControlsCompactHorizontalPadding = 4.dp
private val FrontlineWearableControlsMinimumHorizontalPadding = 2.dp
private val FrontlineWearableControlsSpacing = 6.dp
private val FrontlineWearableControlsCompactSpacing = 4.dp
private val FrontlineWearableControlsBottomPadding = 8.dp
private val FrontlineWearableControlsContentClearance = 8.dp
private val FrontlineWearableContentBottomPadding =
    FrontlineWearableEndButtonSize +
        FrontlineWearableControlsBottomPadding +
        FrontlineWearableControlsContentClearance
private val FrontlineWearableCompactButtonSize = 40.dp
private val FrontlineWearableCompactEndButtonSize = 44.dp
private val FrontlineWearableMinimumButtonSize = 36.dp
private val FrontlineWearableMinimumEndButtonSize = 40.dp
private const val FRONTLINE_ZOOM_CHANGE_THRESHOLD = 0.01f
private const val FRONTLINE_CONTENT_SPOTLIGHT_PREFIX = "content:"
private const val FRONTLINE_MORE_BUTTON_HEIGHT_TO_WIDTH_RATIO = 1.62f
private const val FRONTLINE_RECONNECTING_BADGE_DELAY_MS = 800L

private enum class FrontlineFeed {
    Local,
    Remote,
}

private enum class FrontlineRemoteScreenShareMode {
    Independent,
    Legacy,
}

private data class FrontlineRemoteScreenShareSource(
    val ownerCid: String,
    val mode: FrontlineRemoteScreenShareMode,
    val loading: Boolean = false,
) {
    val id: String = "${mode.name.lowercase(Locale.US)}:$ownerCid"
}

@Composable
internal fun FrontlineCallScreen(
    uiState: CallUiState,
    roomShareUrl: String?,
    eglContext: EglBase.Context,
    config: SerenadaCallFlowConfig,
    theme: SerenadaCallFlowTheme,
    strings: Map<SerenadaString, String>?,
    availableAudioDevices: List<AudioDevice>,
    currentAudioDevice: AudioDevice?,
    isSystemPictureInPicture: Boolean = false,
    onToggleAudio: () -> Unit,
    onToggleVideo: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleFlashlight: () -> Unit,
    onLocalPinchZoom: (Float) -> Unit,
    onEndCall: () -> Unit,
    onSelectAudioDevice: (AudioDevice) -> Unit,
    onShareLink: (() -> Unit)?,
    onInviteToRoom: () -> Unit,
    onStartScreenShare: (Intent) -> Unit,
    onStopScreenShare: () -> Unit,
    attachLocalRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    attachRemoteSink: (VideoSink) -> Unit,
    detachRemoteSink: (VideoSink) -> Unit,
    attachRemoteContentSinkForCid: (String, VideoSink) -> Unit = { _, _ -> },
    detachRemoteContentSinkForCid: (String, VideoSink) -> Unit = { _, _ -> },
    attachLocalContentSink: (VideoSink) -> Unit = {},
    detachLocalContentSink: (VideoSink) -> Unit = {},
    initialRemoteVideoFitCover: Boolean = true,
    onRemoteVideoFitChanged: ((Boolean) -> Unit)? = null,
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
    var isAudioRouteSheetVisible by rememberSaveable { mutableStateOf(false) }
    var showSnapshotFlash by remember { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var showConnectionStatusBadge by remember { mutableStateOf(false) }
    var debugTapTimestampMs by remember { mutableStateOf(0L) }
    var remoteVideoFitCover by rememberSaveable { mutableStateOf(initialRemoteVideoFitCover) }
    var remoteScreenShareFullscreenSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var localAspectRatio by remember { mutableStateOf<Float?>(null) }
    val remoteTileAspectRatios = remember { mutableStateMapOf<String, Float>() }
    var pinnedSpotlightId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSpotlightId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastVideoStartedParticipantId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousRemoteVideoEnabled by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    // Independent screen-share content resolution (mirrors CallScreen). Track the
    // order in which remote content became active (most-recent LAST) so multiple
    // simultaneous sharers pick the right primary (design "Multiple Sharers":
    // local receive order). Only meaningful with the flag on + a real content
    // track; flag-off the resolver yields LEGACY for every owner and the new path
    // never engages.
    val remoteContentOrder = remember { mutableStateListOf<String>() }
    val activeRemoteContentCids =
        uiState.remoteParticipants.filter { it.content?.active == true }.map { it.cid }
    LaunchedEffect(activeRemoteContentCids) {
        val active = activeRemoteContentCids.toSet()
        remoteContentOrder.retainAll { it in active }
        active.forEach { cid -> if (cid !in remoteContentOrder) remoteContentOrder.add(cid) }
    }
    val contentScene = rememberContentScene(uiState, remoteContentOrder.toList())
    // Non-null ONLY for an INDEPENDENT screen-share primary (flag on + real track).
    // Drives the dedicated content track + simultaneous owner camera; null keeps
    // the Frontline legacy single-video-as-content path byte-identical.
    val frontlineIndependentContent = resolveFrontlineIndependentContent(contentScene)
    val independentContentActive = frontlineIndependentContent != null
    // Whenever ANY participant presents an INDEPENDENT content stream the stage
    // switches to the shared {cid, kind} stream-keyed model (see
    // FrontlineStreamKeyedStage). Spotlight ids then become composite "cid::kind"
    // tile ids instead of the legacy "content:cid" form.
    val streamKeyedStageActive = contentScene.all.any { it.mode == ContentMode.INDEPENDENT }

    // LEGACY content owner (single swapped video). Unchanged from today; gated off
    // when the dedicated independent path is active so the legacy camera-as-content
    // collapse does not also fire. When independent, the content owner is the
    // resolver's primary (its camera stays a normal tile alongside the content).
    val localContentMode =
        !independentContentActive &&
            (
                uiState.localCameraMode == LocalCameraMode.WORLD ||
                    uiState.localCameraMode == LocalCameraMode.COMPOSITE ||
                    uiState.isScreenSharing
                )
    val localSpotlightId = uiState.localCid ?: "local"
    val legacyContentOwnerId = when {
        uiState.isScreenSharing -> localSpotlightId
        uiState.localVideoEnabled &&
            (
                uiState.localCameraMode == LocalCameraMode.WORLD ||
                    uiState.localCameraMode == LocalCameraMode.COMPOSITE
                ) -> localSpotlightId
        uiState.remoteContentCid != null -> uiState.remoteContentCid
        else -> null
    }
    val activeContentOwnerId = when {
        frontlineIndependentContent != null ->
            if (frontlineIndependentContent.isLocal) localSpotlightId else frontlineIndependentContent.ownerCid
        else -> legacyContentOwnerId
    }
    val activeContentSpotlightId = activeContentOwnerId?.frontlineContentSpotlightId()
    val isCallSurfacePhase =
        uiState.phase == CallPhase.InCall || uiState.phase == CallPhase.Waiting
    val remote = uiState.remoteParticipants.firstOrNull()
    val remoteVideoEnabled = remote?.videoEnabled == true
    val toggleRemoteVideoFit = {
        val next = !remoteVideoFitCover
        remoteVideoFitCover = next
        onRemoteVideoFitChanged?.invoke(next)
        Unit
    }
    LaunchedEffect(uiState.localVideoEnabled, remote?.cid, localContentMode) {
        pipSwapped = false
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val localRendererEvents = remember {
        aspectRatioRendererEvents(mainHandler) { ratio -> localAspectRatio = ratio }
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
        isSystemPictureInPicture -> null
        !uiState.localVideoEnabled && !remoteVideoEnabled -> null
        largeFeed == FrontlineFeed.Local -> FrontlineFeed.Remote
        else -> FrontlineFeed.Local
    }
    val canSwapPip =
        pipFeed != null &&
            uiState.localVideoEnabled &&
            remote != null
    val frontlineStageTiles = remember(streamKeyedStageActive, uiState.remoteParticipants, localSpotlightId, contentScene) {
        if (streamKeyedStageActive) {
            val cameras =
                uiState.remoteParticipants.map {
                    StageCameraParticipant(cid = it.cid, isLocal = false)
                } + StageCameraParticipant(cid = localSpotlightId, isLocal = true)
            deriveStageTiles(cameras = cameras, content = contentScene.all)
        } else {
            emptyList()
        }
    }
    val frontlineStageTileIds = remember(frontlineStageTiles) { frontlineStageTiles.map { it.id }.toSet() }
    val frontlineStageSpotlightId = remember(
        frontlineStageTiles,
        frontlineStageTileIds,
        lastVideoStartedParticipantId,
        contentScene.primary,
        pinnedSpotlightId,
        selectedSpotlightId,
    ) {
        if (frontlineStageTiles.isEmpty()) {
            null
        } else {
            val recencyDefaultId =
                lastVideoStartedParticipantId
                    ?.let { stageTileId(StageTileKey(it, StageTileKind.CAMERA)) }
                    ?.takeIf { it in frontlineStageTileIds }
            val defaultSpotlightId = pickStageSpotlightTileId(frontlineStageTiles, null, contentScene.primary)
            pinnedSpotlightId?.takeIf { it in frontlineStageTileIds }
                ?: selectedSpotlightId?.takeIf { it in frontlineStageTileIds }
                ?: defaultSpotlightId?.takeIf { contentScene.primary != null }
                ?: recencyDefaultId
                ?: defaultSpotlightId
        }
    }
    val spotlightedRemoteScreenShareSource = when {
        !isCallSurfacePhase -> null
        streamKeyedStageActive -> {
            val key = frontlineStageSpotlightId?.let { parseStageTileId(it) }
            val ownerContent = key?.let { contentScene.remotes.firstOrNull { content -> content.ownerCid == it.cid } }
            if (
                key != null &&
                    key.kind == StageTileKind.CONTENT &&
                    key.cid != localSpotlightId &&
                    ownerContent?.type == ContentType.SCREEN_SHARE
            ) {
                FrontlineRemoteScreenShareSource(
                    ownerCid = key.cid,
                    mode = FrontlineRemoteScreenShareMode.Independent,
                    loading = ownerContent.loading,
                )
            } else {
                null
            }
        }
        activeContentOwnerId != null &&
            activeContentOwnerId != localSpotlightId &&
            activeContentOwnerId == uiState.remoteContentCid &&
            ContentType.fromWire(uiState.remoteContentType) == ContentType.SCREEN_SHARE -> {
            if (uiState.remoteParticipants.size <= 1) {
                if (largeFeed == FrontlineFeed.Remote) {
                    FrontlineRemoteScreenShareSource(activeContentOwnerId, FrontlineRemoteScreenShareMode.Legacy)
                } else {
                    null
                }
            } else {
                val activeRemoteCids = uiState.remoteParticipants.map { it.cid }.toSet()
                val availableSpotlightIds = activeRemoteCids + localSpotlightId + listOfNotNull(activeContentSpotlightId)
                val defaultPrimary =
                    lastVideoStartedParticipantId?.takeIf { it in availableSpotlightIds }
                        ?: uiState.remoteParticipants.firstOrNull()?.cid
                        ?: localSpotlightId
                val effectiveSpotlight =
                    pinnedSpotlightId?.takeIf { it in availableSpotlightIds }
                        ?: selectedSpotlightId?.takeIf { it in availableSpotlightIds }
                        ?: defaultPrimary
                if (effectiveSpotlight == activeContentSpotlightId) {
                    FrontlineRemoteScreenShareSource(activeContentOwnerId, FrontlineRemoteScreenShareMode.Legacy)
                } else {
                    null
                }
            }
        }
        else -> null
    }
    val remoteScreenShareFullscreenSource =
        spotlightedRemoteScreenShareSource?.takeIf { source ->
            frontlineRemoteScreenShareFullscreenActive(
                requestedSourceId = remoteScreenShareFullscreenSourceId,
                currentSourceId = source.id,
            )
        }
    val remoteScreenShareFullscreenActive = remoteScreenShareFullscreenSource != null
    val enterRemoteScreenShareFullscreen: (FrontlineRemoteScreenShareSource) -> Unit = { source ->
        isMoreSheetVisible = false
        isAudioRouteSheetVisible = false
        showDebug = false
        remoteScreenShareFullscreenSourceId = source.id
    }
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
    val currentAudioRoute = remember(currentAudioDevice, availableAudioDevices) {
        currentCallAudioRoute(currentAudioDevice, availableAudioDevices)
    }
    val audioRouteOptions = remember(currentAudioDevice, availableAudioDevices) {
        callAudioRouteOptions(currentAudioDevice, availableAudioDevices)
    }
    val showAudioRouteControl = currentAudioRoute != null || audioRouteOptions.isNotEmpty()
    val showMoreButton =
        isCallSurfacePhase &&
            !isSystemPictureInPicture &&
            (showAudioRouteControl || config.screenSharingEnabled || config.inviteControlsEnabled)
    val moreOpensAudioRouteDirectly =
        frontlineMoreMenuOpensAudioRouteDirectly(
            showAudioRouteControl = showAudioRouteControl,
            screenSharingEnabled = config.screenSharingEnabled,
            inviteEnabled = config.inviteControlsEnabled,
        )
    val openMoreOrAudioRoute = {
        if (moreOpensAudioRouteDirectly) {
            isAudioRouteSheetVisible = true
        } else {
            isMoreSheetVisible = true
        }
    }
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
    LaunchedEffect(uiState.phase, uiState.connectionStatus) {
        if (uiState.phase != CallPhase.InCall || uiState.connectionStatus == ConnectionStatus.Connected) {
            showConnectionStatusBadge = false
            return@LaunchedEffect
        }
        delay(FRONTLINE_RECONNECTING_BADGE_DELAY_MS)
        if (uiState.phase == CallPhase.InCall && uiState.connectionStatus != ConnectionStatus.Connected) {
            showConnectionStatusBadge = true
        }
    }
    val showReconnectingBadge =
        uiState.phase == CallPhase.InCall &&
            showConnectionStatusBadge
    LaunchedEffect(isSystemPictureInPicture) {
        if (isSystemPictureInPicture) {
            isMoreSheetVisible = false
            isAudioRouteSheetVisible = false
            showDebug = false
        }
    }
    LaunchedEffect(spotlightedRemoteScreenShareSource?.id) {
        val requested = remoteScreenShareFullscreenSourceId ?: return@LaunchedEffect
        if (requested != spotlightedRemoteScreenShareSource?.id) {
            remoteScreenShareFullscreenSourceId = null
        }
    }
    LaunchedEffect(activeContentSpotlightId, streamKeyedStageActive) {
        if (streamKeyedStageActive) {
            // Stream-keyed: do NOT persist the default spotlight as a selection.
            // FrontlineStreamKeyedStage computes the default itself from
            // contentScene.primary (pickStageSpotlightTileId), so selectedSpotlightId
            // stays null until an explicit tap; an unpin then reverts to that default.
            // Writing the default here made it indistinguishable from a user tap and
            // could clobber a user selection when another share became primary (codex P2).
            // Only drop stale legacy "content:cid" ids left from the legacy path.
            if (selectedSpotlightId.isFrontlineContentSpotlightId()) selectedSpotlightId = null
            if (pinnedSpotlightId.isFrontlineContentSpotlightId()) pinnedSpotlightId = null
        } else if (activeContentSpotlightId != null) {
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
        // Stream-keyed pin/select ids are composite "cid::kind" tile ids, validated
        // and cleared inside FrontlineStreamKeyedStage; skip the legacy-id check
        // here (these ids are never in `activeSpotlightIds`, so it would wrongly
        // clear a valid stream-keyed pin/select on every recomposition).
        if (!streamKeyedStageActive) {
            if (pinnedSpotlightId != null && pinnedSpotlightId !in activeSpotlightIds) pinnedSpotlightId = null
            if (selectedSpotlightId != null && selectedSpotlightId !in activeSpotlightIds) selectedSpotlightId = null
        }
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
                val usesWearableLayout = frontlineUsesWearableLayout(maxWidth, maxHeight)
                val isLandscape = maxWidth > maxHeight
                val isTabletLandscape = isLandscape && maxWidth >= 1100.dp && maxHeight >= 720.dp
                val panelWidth = when {
                    !isLandscape -> maxWidth
                    maxWidth >= 720.dp -> 320.dp
                    else -> 260.dp
                }
                val pipInPanel = isTabletLandscape && pipFeed != null
                val pipSize = frontlinePipSize(
                    containerWidth = maxWidth,
                    containerHeight = maxHeight,
                    inPanel = pipInPanel,
                )
                val pip: @Composable (Modifier) -> Unit = { modifier ->
                    if (pipFeed != null) {
                        FrontlinePip(
                            feed = pipFeed,
                            uiState = uiState,
                            remote = remote,
                            eglContext = eglContext,
                            width = pipSize.width,
                            height = pipSize.height,
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
                val showWearableRemoteScreenShareFullscreen =
                    usesWearableLayout &&
                        isCallSurfacePhase &&
                        spotlightedRemoteScreenShareSource != null
                val showWearableFlipCamera =
                    usesWearableLayout &&
                        isCallSurfacePhase &&
                        uiState.localVideoEnabled &&
                        uiState.availableCameraModes.size > 1
                val showWearableSnapshot =
                    usesWearableLayout &&
                        isCallSurfacePhase &&
                        uiState.localVideoEnabled &&
                        snapshotSource != null &&
                        onSnapshotRequested != null
                val showWearableFlashlight =
                    usesWearableLayout &&
                        isCallSurfacePhase &&
                        uiState.localVideoEnabled &&
                        uiState.isFlashAvailable
                val wearableMoreActionsAvailable =
                    usesWearableLayout &&
                        isCallSurfacePhase &&
                        frontlineWearableMoreActionsAvailable(
                            localVideoEnabled = uiState.localVideoEnabled,
                            availableCameraModeCount = uiState.availableCameraModes.size,
                            snapshotAvailable = snapshotSource != null && onSnapshotRequested != null,
                            flashAvailable = uiState.isFlashAvailable,
                            remoteScreenShareFullscreenAvailable = spotlightedRemoteScreenShareSource != null,
                        )
                val showMoreButtonForWearable = showMoreButton || wearableMoreActionsAvailable
                val moreOpensAudioRouteDirectlyForLayout =
                    moreOpensAudioRouteDirectly && !wearableMoreActionsAvailable
                val openMoreOrAudioRouteForLayout = {
                    if (moreOpensAudioRouteDirectlyForLayout) {
                        isAudioRouteSheetVisible = true
                    } else {
                        isMoreSheetVisible = true
                    }
                }

                if (isSystemPictureInPicture) {
                    val remoteCids = uiState.remoteParticipants.map { it.cid }.toSet()
                    val systemPipRemoteCid =
                        listOf(pinnedSpotlightId, selectedSpotlightId, lastVideoStartedParticipantId)
                            .firstNotNullOfOrNull { spotlightId ->
                                when {
                                    spotlightId == null -> null
                                    spotlightId in remoteCids -> spotlightId
                                    spotlightId.isFrontlineContentSpotlightId() -> {
                                        spotlightId
                                            .removePrefix(FRONTLINE_CONTENT_SPOTLIGHT_PREFIX)
                                            .takeIf { it in remoteCids }
                                    }
                                    else -> null
                                }
                            } ?: remote?.cid
                    SystemPictureInPictureContent(
                        uiState = uiState,
                        feed = selectSystemPictureInPictureFeed(
                            localIsLarge = largeFeed == FrontlineFeed.Local,
                            localVideoEnabled = uiState.localVideoEnabled,
                            remoteCid = systemPipRemoteCid,
                        ),
                        eglContext = eglContext,
                        localContentScale = if (uiState.isScreenSharing) ContentScale.Fit else ContentScale.Crop,
                        remoteContentScale = if (remoteVideoFitCover) ContentScale.Crop else ContentScale.Fit,
                        attachLocalSink = attachLocalSink,
                        detachLocalSink = detachLocalSink,
                        attachRemoteSinkForCid = attachRemoteSinkForCid,
                        detachRemoteSinkForCid = detachRemoteSinkForCid,
                    )
                } else if (remoteScreenShareFullscreenSource != null) {
                    FrontlineRemoteScreenShareFullscreenSurface(
                        source = remoteScreenShareFullscreenSource,
                        remote = uiState.remoteParticipants.firstOrNull { it.cid == remoteScreenShareFullscreenSource.ownerCid },
                        eglContext = eglContext,
                        attachRemoteSinkForCid = attachRemoteSinkForCid,
                        detachRemoteSinkForCid = detachRemoteSinkForCid,
                        attachRemoteContentSinkForCid = attachRemoteContentSinkForCid,
                        detachRemoteContentSinkForCid = detachRemoteContentSinkForCid,
                        strings = strings,
                        onExit = { remoteScreenShareFullscreenSourceId = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (usesWearableLayout) {
                    Box(Modifier.fillMaxSize()) {
                        FrontlineContentArea(
                            uiState = uiState,
                            remote = remote,
                            largeFeed = largeFeed,
                            pipFeed = null,
                            pipInPanel = false,
                            localContentMode = localContentMode,
                            isCallSurfacePhase = isCallSurfacePhase,
                            eglContext = eglContext,
                            localRendererEvents = localRendererEvents,
                            localAspectRatio = localAspectRatio ?: 0f,
                            remoteAspectRatios = remoteTileAspectRatios,
                            activeContentSpotlightId = activeContentSpotlightId,
                            pinnedSpotlightId = pinnedSpotlightId,
                            selectedSpotlightId = selectedSpotlightId,
                            lastVideoStartedParticipantId = lastVideoStartedParticipantId,
                            remoteVideoFitCover = remoteVideoFitCover,
                            onToggleRemoteVideoFit = toggleRemoteVideoFit,
                            spotlightedRemoteScreenShareSource = spotlightedRemoteScreenShareSource,
                            onEnterRemoteScreenShareFullscreen = enterRemoteScreenShareFullscreen,
                            onPinnedSpotlightIdChanged = { pinnedSpotlightId = it },
                            onSelectedSpotlightIdChanged = { selectedSpotlightId = it },
                            localZoomTransformState = localZoomTransformState,
                            attachLocalRenderer = attachLocalRenderer,
                            detachLocalRenderer = detachLocalRenderer,
                            attachLocalSink = attachLocalSink,
                            detachLocalSink = detachLocalSink,
                            attachRemoteSink = attachRemoteSink,
                            detachRemoteSink = detachRemoteSink,
                            attachRemoteSinkForCid = attachRemoteSinkForCid,
                            detachRemoteSinkForCid = detachRemoteSinkForCid,
                            contentScene = contentScene,
                            frontlineIndependentContent = frontlineIndependentContent,
                            attachRemoteContentSinkForCid = attachRemoteContentSinkForCid,
                            detachRemoteContentSinkForCid = detachRemoteContentSinkForCid,
                            attachLocalContentSink = attachLocalContentSink,
                            detachLocalContentSink = detachLocalContentSink,
                            pip = {},
                            strings = strings,
                            showInlineOverlays = false,
                            wearableLayout = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                        FrontlineWearableControls(
                            uiState = uiState,
                            callControlsEnabled = isCallSurfacePhase,
                            videoControlsEnabled = isCallSurfacePhase && config.videoEnabled && uiState.availableCameraModes.isNotEmpty(),
                            showMoreButton = showMoreButtonForWearable,
                            onVideoTap = {
                                if (uiState.localVideoEnabled) {
                                    pipSwapped = false
                                }
                                onToggleVideo()
                            },
                            onToggleAudio = onToggleAudio,
                            onMore = openMoreOrAudioRouteForLayout,
                            onEndCall = onEndCall,
                            strings = strings,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                        )
                    }
                } else if (isLandscape) {
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
                            localAspectRatio = localAspectRatio ?: 0f,
                            remoteAspectRatios = remoteTileAspectRatios,
                            activeContentSpotlightId = activeContentSpotlightId,
                            pinnedSpotlightId = pinnedSpotlightId,
                            selectedSpotlightId = selectedSpotlightId,
                            lastVideoStartedParticipantId = lastVideoStartedParticipantId,
                            remoteVideoFitCover = remoteVideoFitCover,
                            onToggleRemoteVideoFit = toggleRemoteVideoFit,
                            spotlightedRemoteScreenShareSource = spotlightedRemoteScreenShareSource,
                            onEnterRemoteScreenShareFullscreen = enterRemoteScreenShareFullscreen,
                            onPinnedSpotlightIdChanged = { pinnedSpotlightId = it },
                            onSelectedSpotlightIdChanged = { selectedSpotlightId = it },
                            localZoomTransformState = localZoomTransformState,
                            attachLocalRenderer = attachLocalRenderer,
                            detachLocalRenderer = detachLocalRenderer,
                            attachLocalSink = attachLocalSink,
                            detachLocalSink = detachLocalSink,
                            attachRemoteSink = attachRemoteSink,
                            detachRemoteSink = detachRemoteSink,
                            attachRemoteSinkForCid = attachRemoteSinkForCid,
                            detachRemoteSinkForCid = detachRemoteSinkForCid,
                            contentScene = contentScene,
                            frontlineIndependentContent = frontlineIndependentContent,
                            attachRemoteContentSinkForCid = attachRemoteContentSinkForCid,
                            detachRemoteContentSinkForCid = detachRemoteContentSinkForCid,
                            attachLocalContentSink = attachLocalContentSink,
                            detachLocalContentSink = detachLocalContentSink,
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
                            onMore = openMoreOrAudioRoute,
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
                            localAspectRatio = localAspectRatio ?: 0f,
                            remoteAspectRatios = remoteTileAspectRatios,
                            activeContentSpotlightId = activeContentSpotlightId,
                            pinnedSpotlightId = pinnedSpotlightId,
                            selectedSpotlightId = selectedSpotlightId,
                            lastVideoStartedParticipantId = lastVideoStartedParticipantId,
                            remoteVideoFitCover = remoteVideoFitCover,
                            onToggleRemoteVideoFit = toggleRemoteVideoFit,
                            spotlightedRemoteScreenShareSource = spotlightedRemoteScreenShareSource,
                            onEnterRemoteScreenShareFullscreen = enterRemoteScreenShareFullscreen,
                            onPinnedSpotlightIdChanged = { pinnedSpotlightId = it },
                            onSelectedSpotlightIdChanged = { selectedSpotlightId = it },
                            localZoomTransformState = localZoomTransformState,
                            attachLocalRenderer = attachLocalRenderer,
                            detachLocalRenderer = detachLocalRenderer,
                            attachLocalSink = attachLocalSink,
                            detachLocalSink = detachLocalSink,
                            attachRemoteSink = attachRemoteSink,
                            detachRemoteSink = detachRemoteSink,
                            attachRemoteSinkForCid = attachRemoteSinkForCid,
                            detachRemoteSinkForCid = detachRemoteSinkForCid,
                            contentScene = contentScene,
                            frontlineIndependentContent = frontlineIndependentContent,
                            attachRemoteContentSinkForCid = attachRemoteContentSinkForCid,
                            detachRemoteContentSinkForCid = detachRemoteContentSinkForCid,
                            attachLocalContentSink = attachLocalContentSink,
                            detachLocalContentSink = detachLocalContentSink,
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
                            onMore = openMoreOrAudioRoute,
                            onEndCall = onEndCall,
                            strings = strings,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showReconnectingBadge && !isSystemPictureInPicture && !remoteScreenShareFullscreenActive,
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

                if (showSnapshotFlash && !isSystemPictureInPicture && !remoteScreenShareFullscreenActive) {
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

                if (config.debugOverlayEnabled && !isSystemPictureInPicture && !remoteScreenShareFullscreenActive) {
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
                    visible = isMoreSheetVisible &&
                        !moreOpensAudioRouteDirectlyForLayout &&
                        !remoteScreenShareFullscreenActive,
                    audioRouteDevice = currentAudioRoute,
                    audioRouteOptions = audioRouteOptions,
                    screenSharingEnabled = config.screenSharingEnabled,
                    inviteEnabled = config.inviteControlsEnabled,
                    shareEnabled = config.inviteControlsEnabled && shareLinkAction != null,
                    isScreenSharing = uiState.isScreenSharing,
                    showRemoteScreenShareFullscreen = showWearableRemoteScreenShareFullscreen,
                    showFlipCamera = showWearableFlipCamera,
                    showFlashlight = showWearableFlashlight,
                    flashlightEnabled = uiState.isFlashEnabled,
                    showSnapshot = showWearableSnapshot,
                    strings = strings,
                    onDismiss = { isMoreSheetVisible = false },
                    onEnterRemoteScreenShareFullscreen = {
                        spotlightedRemoteScreenShareSource?.let { source ->
                            isMoreSheetVisible = false
                            enterRemoteScreenShareFullscreen(source)
                        }
                    },
                    onFlipCamera = {
                        isMoreSheetVisible = false
                        onFlipCamera()
                    },
                    onToggleFlashlight = {
                        isMoreSheetVisible = false
                        onToggleFlashlight()
                    },
                    onSnapshot = {
                        val source = snapshotSource
                        val handler = onSnapshotRequested
                        if (source != null && handler != null) {
                            isMoreSheetVisible = false
                            showSnapshotFlash = true
                            handler(source)
                        }
                    },
                    onAudioRoute = {
                        isMoreSheetVisible = false
                        isAudioRouteSheetVisible = true
                    },
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
                CallAudioRouteSheet(
                    visible = isAudioRouteSheetVisible && !remoteScreenShareFullscreenActive,
                    devices = audioRouteOptions,
                    currentDevice = currentAudioRoute,
                    strings = strings,
                    onDismiss = { isAudioRouteSheetVisible = false },
                    onSelect = { device ->
                        isAudioRouteSheetVisible = false
                        onSelectAudioDevice(device)
                    },
                    modifier = Modifier.zIndex(9f),
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
    localAspectRatio: Float,
    remoteAspectRatios: MutableMap<String, Float>,
    activeContentSpotlightId: String?,
    pinnedSpotlightId: String?,
    selectedSpotlightId: String?,
    lastVideoStartedParticipantId: String?,
    remoteVideoFitCover: Boolean,
    onToggleRemoteVideoFit: () -> Unit,
    spotlightedRemoteScreenShareSource: FrontlineRemoteScreenShareSource?,
    onEnterRemoteScreenShareFullscreen: (FrontlineRemoteScreenShareSource) -> Unit,
    onPinnedSpotlightIdChanged: (String?) -> Unit,
    onSelectedSpotlightIdChanged: (String?) -> Unit,
    localZoomTransformState: androidx.compose.foundation.gestures.TransformableState,
    attachLocalRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteSink: (VideoSink) -> Unit,
    detachRemoteSink: (VideoSink) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    contentScene: ContentScene,
    frontlineIndependentContent: FrontlineIndependentContent?,
    attachRemoteContentSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteContentSinkForCid: (String, VideoSink) -> Unit,
    attachLocalContentSink: (VideoSink) -> Unit,
    detachLocalContentSink: (VideoSink) -> Unit,
    pip: @Composable (Modifier) -> Unit,
    strings: Map<SerenadaString, String>?,
    showInlineOverlays: Boolean = true,
    wearableLayout: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(FrontlineBlack)
            .clipToBounds()
    ) {
        val waitingForRemote = uiState.isFrontlineWaitingForRemote()
        // INDEPENDENT content (flag on + real screen-share track) routes through the
        // content stage like the standard CallScreen's `oneToOneIndependentContent`:
        // a dedicated content tile renders the content track while the sharer's
        // camera stays a normal participant tile (simultaneous camera + content),
        // including the "sharing, waiting for participants" hold while alone. Never
        // reachable flag-off, so the legacy branches below stay byte-identical.
        val useIndependentContentStage = isCallSurfacePhase && frontlineIndependentContent != null
        when {
            !isCallSurfacePhase -> {
                FrontlinePhaseSurface(
                    uiState = uiState,
                    strings = strings,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            useIndependentContentStage || uiState.remoteParticipants.size > 1 -> {
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
                    remoteVideoFitCover = remoteVideoFitCover,
                    onToggleRemoteVideoFit = onToggleRemoteVideoFit,
                    onEnterRemoteScreenShareFullscreen = onEnterRemoteScreenShareFullscreen,
                    onPinnedSpotlightIdChanged = onPinnedSpotlightIdChanged,
                    onSelectedSpotlightIdChanged = onSelectedSpotlightIdChanged,
                    localZoomTransformState = localZoomTransformState,
                    localRendererEvents = localRendererEvents,
                    attachLocalSink = attachLocalSink,
                    detachLocalSink = detachLocalSink,
                    attachRemoteSinkForCid = attachRemoteSinkForCid,
                    detachRemoteSinkForCid = detachRemoteSinkForCid,
                    contentScene = contentScene,
                    frontlineIndependentContent = frontlineIndependentContent,
                    attachRemoteContentSinkForCid = attachRemoteContentSinkForCid,
                    detachRemoteContentSinkForCid = detachRemoteContentSinkForCid,
                    attachLocalContentSink = attachLocalContentSink,
                    detachLocalContentSink = detachLocalContentSink,
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
                    wearableLayout = wearableLayout,
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
                    remoteVideoFitCover = remoteVideoFitCover,
                    remoteVideoIsScreenShare = spotlightedRemoteScreenShareSource != null,
                    localZoomTransformState = localZoomTransformState,
                    attachLocalRenderer = attachLocalRenderer,
                    detachLocalRenderer = detachLocalRenderer,
                    attachRemoteSink = attachRemoteSink,
                    detachRemoteSink = detachRemoteSink,
                    strings = strings,
                    wearableLayout = wearableLayout,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (
            isCallSurfacePhase &&
                !waitingForRemote &&
                !useIndependentContentStage &&
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
            showInlineOverlays &&
                isCallSurfacePhase &&
                !waitingForRemote &&
                !useIndependentContentStage &&
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

        if (
            showInlineOverlays &&
            !useIndependentContentStage &&
            frontlineShowsRemoteFitButton(
                isCallSurfacePhase = isCallSurfacePhase,
                waitingForRemote = waitingForRemote,
                remoteParticipantCount = uiState.remoteParticipants.size,
                largeFeedIsRemote = largeFeed == FrontlineFeed.Remote,
                remoteVideoEnabled = remote?.videoEnabled == true,
            )
        ) {
            FrontlineRemoteFitButton(
                remoteVideoFitCover = if (spotlightedRemoteScreenShareSource == null) remoteVideoFitCover else false,
                strings = strings,
                onClick = {
                    if (spotlightedRemoteScreenShareSource != null) {
                        onEnterRemoteScreenShareFullscreen(spotlightedRemoteScreenShareSource)
                    } else {
                        onToggleRemoteVideoFit()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
            )
        }

        if (showInlineOverlays && isCallSurfacePhase && !waitingForRemote && !useIndependentContentStage && uiState.remoteParticipants.size <= 1 && pipFeed != null && !pipInPanel) {
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
    remoteVideoFitCover: Boolean,
    onToggleRemoteVideoFit: () -> Unit,
    onEnterRemoteScreenShareFullscreen: (FrontlineRemoteScreenShareSource) -> Unit,
    onPinnedSpotlightIdChanged: (String?) -> Unit,
    onSelectedSpotlightIdChanged: (String?) -> Unit,
    localZoomTransformState: androidx.compose.foundation.gestures.TransformableState,
    localRendererEvents: RendererCommon.RendererEvents,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    contentScene: ContentScene,
    frontlineIndependentContent: FrontlineIndependentContent?,
    attachRemoteContentSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteContentSinkForCid: (String, VideoSink) -> Unit,
    attachLocalContentSink: (VideoSink) -> Unit,
    detachLocalContentSink: (VideoSink) -> Unit,
    strings: Map<SerenadaString, String>?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val localId = uiState.localCid ?: "local"
    val isIndependentContent = frontlineIndependentContent != null

    // STREAM-KEYED STAGE: whenever ANY participant presents an INDEPENDENT content
    // stream, switch to the shared {cid, kind} filmstrip+spotlight model (same as
    // the standard CallScreen). EVERY stream is its own tile — a camera tile per
    // camera-on participant and a content tile per sharer (incl the local user's
    // own screen) — so a sharer's camera and screen are EQUAL peer tiles (no
    // camera-PIP-on-content), and multiple simultaneous sharers each get a tile.
    // The Frontline pin/select/recency behavior is preserved, now expressed
    // through the shared model (the spotlight ids are composite "cid::kind" tile
    // ids stored in pinnedSpotlightId / selectedSpotlightId). Never engages
    // flag-off (no independent content ⇒ false), so the legacy multi-party /
    // legacy-content path below stays byte-identical.
    val streamKeyedStageActive = contentScene.all.any { it.mode == ContentMode.INDEPENDENT }
    if (streamKeyedStageActive) {
        FrontlineStreamKeyedStage(
            contentScene = contentScene,
            uiState = uiState,
            localId = localId,
            localContentMode = localContentMode,
            eglContext = eglContext,
            localAspectRatio = localAspectRatio,
            remoteAspectRatios = remoteAspectRatios,
            pinnedSpotlightId = pinnedSpotlightId,
            selectedSpotlightId = selectedSpotlightId,
            lastVideoStartedParticipantId = lastVideoStartedParticipantId,
            remoteVideoFitCover = remoteVideoFitCover,
            onToggleRemoteVideoFit = onToggleRemoteVideoFit,
            onEnterRemoteScreenShareFullscreen = onEnterRemoteScreenShareFullscreen,
            onPinnedSpotlightIdChanged = onPinnedSpotlightIdChanged,
            onSelectedSpotlightIdChanged = onSelectedSpotlightIdChanged,
            localZoomTransformState = localZoomTransformState,
            localRendererEvents = localRendererEvents,
            attachLocalSink = attachLocalSink,
            detachLocalSink = detachLocalSink,
            attachRemoteSinkForCid = attachRemoteSinkForCid,
            detachRemoteSinkForCid = detachRemoteSinkForCid,
            attachRemoteContentSinkForCid = attachRemoteContentSinkForCid,
            detachRemoteContentSinkForCid = detachRemoteContentSinkForCid,
            attachLocalContentSink = attachLocalContentSink,
            detachLocalContentSink = detachLocalContentSink,
            strings = strings,
            modifier = modifier,
        )
        return
    }

    val hasLocalContent = localContentMode
    val activeContentOwnerId = activeContentSpotlightId?.removePrefix(FRONTLINE_CONTENT_SPOTLIGHT_PREFIX)
    val contentSource = when {
        // INDEPENDENT: content source comes from the shared resolver (screen-share
        // only); the owner's CAMERA is kept as a separate participant tile below.
        frontlineIndependentContent != null -> {
            val ownerId = if (frontlineIndependentContent.isLocal) localId else frontlineIndependentContent.ownerCid
            ContentSource(
                type = frontlineIndependentContent.type,
                ownerParticipantId = ownerId,
                aspectRatio = if (frontlineIndependentContent.isLocal) {
                    localAspectRatio.takeIf { it > 0f }
                } else {
                    remoteAspectRatios[ownerId]
                },
            )
        }
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
    val spotlightIsRemote =
        uiState.remoteParticipants.any { it.cid == effectiveSpotlightId } ||
            (spotlightIsContent && contentSource.ownerParticipantId != localId)
    val spotlightIsRemoteScreenShare =
        spotlightIsContent &&
            contentSource.ownerParticipantId != localId &&
            contentSource.type == ContentType.SCREEN_SHARE

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
            spotlightIsRemote,
            contentSource,
            remoteVideoFitCover,
            isIndependentContent,
            spotlightIsRemoteScreenShare,
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
                    // INDEPENDENT: the local camera is a separate track from content,
                    // so it always renders as its own tile (simultaneous camera +
                    // content). LEGACY: keep today's collapse rule (camera swapped to
                    // the single content video).
                    isIndependentContent ||
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
                    userPrefs = UserLayoutPrefs(
                        dominantFit = if (
                            frontlineRemoteScreenShareUsesFit(
                                isRemoteScreenShare = spotlightIsRemoteScreenShare,
                                remoteVideoFitCover = if (spotlightIsRemote) remoteVideoFitCover else true,
                            )
                        ) FitMode.CONTAIN else FitMode.COVER,
                    ),
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
                    val isRemoteScreenShare =
                        isRemoteContent && contentSource?.type == ContentType.SCREEN_SHARE
                    val remote = if (isRemoteContent) {
                        uiState.remoteParticipants.firstOrNull { it.cid == contentOwnerCid }
                    } else if (!isLocal) {
                        uiState.remoteParticipants.firstOrNull { it.cid == tile.id }
                    } else {
                        null
                    }
                    val showRemoteFitButton =
                        tile.zOrder == 0 &&
                            remote != null &&
                            !isLocal &&
                            (!isContentTile || contentOwnerCid != localId)
                    val tileWidth = with(density) { tile.frame.width.toDp() }
                    val tileHeight = with(density) { tile.frame.height.toDp() }
                    val tileX = with(density) { tile.frame.x.toDp() }
                    val tileY = with(density) { tile.frame.y.toDp() }
                    val tileCornerRadius = with(density) { tile.cornerRadius.toDp() }
                    val tileModifier = Modifier
                        .offset(x = tileX, y = tileY)
                        .size(width = tileWidth, height = tileHeight)
                        .clip(RoundedCornerShape(tileCornerRadius))
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
                        onRemoteAspectRatioChanged = remote?.let { participant ->
                            { ratio: Float ->
                                remoteAspectRatios[participant.cid] = ratio
                            }
                        },
                        attachLocalSink = attachLocalSink,
                        detachLocalSink = detachLocalSink,
                        attachRemoteSinkForCid = attachRemoteSinkForCid,
                        detachRemoteSinkForCid = detachRemoteSinkForCid,
                        contentScale = if (
                            frontlineRemoteScreenShareUsesFit(
                                isRemoteScreenShare = isRemoteScreenShare,
                                remoteVideoFitCover = remoteVideoFitCover,
                            )
                        ) ContentScale.Fit else ContentScale.Crop,
                        cornerRadius = tileCornerRadius,
                        pinned = tileSpotlightId == pinnedSpotlightId,
                        showRemoteFitButton = showRemoteFitButton,
                        remoteVideoFitCover = if (isRemoteScreenShare) false else remoteVideoFitCover,
                        onToggleRemoteVideoFit = {
                            if (isRemoteScreenShare) {
                                onEnterRemoteScreenShareFullscreen(
                                    FrontlineRemoteScreenShareSource(
                                        ownerCid = contentOwnerCid,
                                        mode = FrontlineRemoteScreenShareMode.Legacy,
                                    ),
                                )
                            } else {
                                onToggleRemoteVideoFit()
                            }
                        },
                        onSelect = { onSelectedSpotlightIdChanged(tileSpotlightId) },
                        onTogglePinned = {
                            onPinnedSpotlightIdChanged(
                                if (tileSpotlightId == pinnedSpotlightId) null else tileSpotlightId
                            )
                        },
                        strings = strings,
                        modifier = tileModifier,
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
                        onRemoteAspectRatioChanged = null,
                        attachLocalSink = attachLocalSink,
                        detachLocalSink = detachLocalSink,
                        attachRemoteSinkForCid = attachRemoteSinkForCid,
                        detachRemoteSinkForCid = detachRemoteSinkForCid,
                        contentScale = if (pip.fit == FitMode.CONTAIN) ContentScale.Fit else ContentScale.Crop,
                        cornerRadius = pipCornerRadius,
                        pinned = false,
                        showRemoteFitButton = false,
                        remoteVideoFitCover = remoteVideoFitCover,
                        onToggleRemoteVideoFit = onToggleRemoteVideoFit,
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

/**
 * Frontline stream-keyed filmstrip + spotlight stage. Reuses the SAME shared tile
 * model as the standard CallScreen ([deriveStageTiles] / [pickStageSpotlightTileId])
 * over `{cid, kind}` tiles, so a sharer's camera and screen are EQUAL peer tiles
 * (no camera-PIP-on-content) and each simultaneous sharer gets a content tile.
 *
 * Pin / select / recency are preserved: the spotlight is resolved with the
 * Frontline precedence (pin > tap-select > most-recent share, then the
 * recently-started camera, then the first tile). Spotlight ids stored in
 * [pinnedSpotlightId] / [selectedSpotlightId] are composite "cid::kind" tile ids.
 *
 * The spotlight tile is rendered through the conformance-locked [computeLayout]
 * via a composite-id FOCUS scene (opaque tile ids), reusing its single-primary +
 * filmstrip geometry without touching the CONTENT / FOCUS code paths.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrontlineStreamKeyedStage(
    contentScene: ContentScene,
    uiState: CallUiState,
    localId: String,
    localContentMode: Boolean,
    eglContext: EglBase.Context,
    localAspectRatio: Float,
    remoteAspectRatios: MutableMap<String, Float>,
    pinnedSpotlightId: String?,
    selectedSpotlightId: String?,
    lastVideoStartedParticipantId: String?,
    remoteVideoFitCover: Boolean,
    onToggleRemoteVideoFit: () -> Unit,
    onEnterRemoteScreenShareFullscreen: (FrontlineRemoteScreenShareSource) -> Unit,
    onPinnedSpotlightIdChanged: (String?) -> Unit,
    onSelectedSpotlightIdChanged: (String?) -> Unit,
    localZoomTransformState: androidx.compose.foundation.gestures.TransformableState,
    localRendererEvents: RendererCommon.RendererEvents,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    attachRemoteContentSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteContentSinkForCid: (String, VideoSink) -> Unit,
    attachLocalContentSink: (VideoSink) -> Unit,
    detachLocalContentSink: (VideoSink) -> Unit,
    strings: Map<SerenadaString, String>?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val remoteByCid = remember(uiState.remoteParticipants) {
        uiState.remoteParticipants.associateBy { it.cid }
    }
    val contentByOwner = remember(contentScene) { contentScene.all.associateBy { it.ownerCid } }

    // Stream-keyed tiles (pure, unit-tested): remote cameras, local camera, then a
    // content tile per INDEPENDENT sharer (incl the local user's own screen).
    val cameras = remember(uiState.remoteParticipants, localId, uiState.localVideoEnabled) {
        uiState.remoteParticipants.map {
            StageCameraParticipant(cid = it.cid, isLocal = false)
        } + StageCameraParticipant(cid = localId, isLocal = true)
    }
    val stageTiles = remember(cameras, contentScene) {
        deriveStageTiles(cameras = cameras, content = contentScene.all)
    }

    // Frontline spotlight precedence over the {cid, kind} tiles: an explicit pin
    // wins, then a tap-selection, then the shared default (most-recent share, then
    // the recently-started camera, then the first tile).
    val tileIds = stageTiles.map { it.id }.toSet()
    val recencyDefaultId =
        lastVideoStartedParticipantId
            ?.let { stageTileId(StageTileKey(it, StageTileKind.CAMERA)) }
            ?.takeIf { it in tileIds }
    val defaultSpotlightId =
        pickStageSpotlightTileId(stageTiles, null, contentScene.primary)
    val spotlightId =
        pinnedSpotlightId?.takeIf { it in tileIds }
            ?: selectedSpotlightId?.takeIf { it in tileIds }
            ?: defaultSpotlightId?.takeIf { contentScene.primary != null }
            ?: recencyDefaultId
            ?: defaultSpotlightId

    // Drop stale pin/select ids whose tile disappeared so the spotlight reverts.
    LaunchedEffect(stageTiles, pinnedSpotlightId, selectedSpotlightId) {
        if (pinnedSpotlightId != null && pinnedSpotlightId !in tileIds) onPinnedSpotlightIdChanged(null)
        if (selectedSpotlightId != null && selectedSpotlightId !in tileIds) onSelectedSpotlightIdChanged(null)
    }

    if (stageTiles.isEmpty() || spotlightId == null) return
    val spotlightKey = parseStageTileId(spotlightId)
    val spotlightIsRemoteScreenShare =
        spotlightKey != null &&
            spotlightKey.kind == StageTileKind.CONTENT &&
            spotlightKey.cid != localId &&
            contentByOwner[spotlightKey.cid]?.type == ContentType.SCREEN_SHARE
    val spotlightFit = if (
        frontlineRemoteScreenShareUsesFit(
            isRemoteScreenShare = spotlightIsRemoteScreenShare,
            remoteVideoFitCover = remoteVideoFitCover,
        )
    ) FitMode.CONTAIN else FitMode.COVER

    BoxWithConstraints(modifier = modifier) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }

        val computedLayout = remember(
            stageTiles, spotlightId, remoteAspectRatios.toMap(), localAspectRatio,
            viewportWidthPx, viewportHeightPx, spotlightFit,
        ) {
            val participants = stageTiles.map { tile ->
                SceneParticipant(
                    id = tile.id,
                    role = if (tile.id == spotlightId) ParticipantRole.LOCAL else ParticipantRole.REMOTE,
                    videoEnabled = true,
                    videoAspectRatio = if (tile.kind == StageTileKind.CAMERA) {
                        if (tile.isLocal) localAspectRatio.takeIf { it > 0f } else remoteAspectRatios[tile.cid]
                    } else {
                        null
                    },
                )
            }
            val scene = CallScene(
                viewportWidth = viewportWidthPx,
                viewportHeight = viewportHeightPx,
                safeAreaInsets = Insets(),
                participants = participants,
                localParticipantId = spotlightId,
                activeSpeakerId = null,
                pinnedParticipantId = spotlightId,
                contentSource = null,
                userPrefs = UserLayoutPrefs(
                    dominantFit = spotlightFit,
                ),
            )
            if (stageTiles.size == 1) {
                LayoutResult(
                    mode = LayoutMode.FOCUS,
                    tiles = listOf(
                        TileLayout(
                            id = spotlightId,
                            type = OccupantType.PARTICIPANT,
                            frame = LayoutRect(0f, 0f, viewportWidthPx, viewportHeightPx),
                            fit = spotlightFit,
                            cornerRadius = 0f,
                            zOrder = 0,
                        ),
                    ),
                    localPip = null,
                )
            } else {
                computeLayout(scene)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Resolve {cid,kind} keys BEFORE the composable `key {}` block so the block
            // needs no early `return@key`. A labeled return inside the inlined
            // forEach+key emits a `$$$$$NON_LOCAL_RETURN$$$$$` synthetic that R8 9.2.x
            // cannot represent in dex — the APK fails to assemble even though Kotlin
            // compiles and unit tests pass (dexing only runs at APK assembly).
            val orderedTiles = computedLayout.tiles.sortedBy { it.zOrder }
                .mapNotNull { tile -> parseStageTileId(tile.id)?.let { parsed -> tile to parsed } }
            orderedTiles.forEach { (tile, tileKey) ->
                key(tile.id) {
                    val isContentTile = tileKey.kind == StageTileKind.CONTENT
                    val isLocalTile = tileKey.cid == localId
                    val tileRemote = if (isLocalTile) null else remoteByCid[tileKey.cid]
                    val ownerContent = if (isContentTile) contentByOwner[tileKey.cid] else null
                    val isRemoteScreenShare =
                        isContentTile && !isLocalTile && ownerContent?.type == ContentType.SCREEN_SHARE

                    val tileWidth = with(density) { tile.frame.width.toDp() }
                    val tileHeight = with(density) { tile.frame.height.toDp() }
                    val tileX = with(density) { tile.frame.x.toDp() }
                    val tileY = with(density) { tile.frame.y.toDp() }
                    val tileCornerRadius = with(density) { tile.cornerRadius.toDp() }
                    val tileModifier = Modifier
                        .offset(x = tileX, y = tileY)
                        .size(width = tileWidth, height = tileHeight)
                        .clip(RoundedCornerShape(tileCornerRadius))

                    val onSelect = { onSelectedSpotlightIdChanged(tile.id) }
                    val onTogglePinned = {
                        onPinnedSpotlightIdChanged(if (tile.id == pinnedSpotlightId) null else tile.id)
                    }

                    if (isContentTile) {
                        // Content tile: dedicated content (screen share) track, an
                        // EQUAL peer tile (no camera PIP). Content carries no audio.
                        Box(
                            modifier = tileModifier
                                .background(Color(0xFF111111))
                                .clipToBounds()
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onLongClick = onTogglePinned,
                                    onClick = onSelect,
                                )
                        ) {
                            IndependentContentTile(
                                loading = ownerContent?.loading == true,
                                waitingForParticipants = ownerContent?.waitingForParticipants == true,
                                width = tileWidth,
                                height = tileHeight,
                                eglContext = eglContext,
                                onAttach = if (isLocalTile) {
                                    attachLocalContentSink
                                } else {
                                    { sink -> attachRemoteContentSinkForCid(tileKey.cid, sink) }
                                },
                                onDetach = if (isLocalTile) {
                                    detachLocalContentSink
                                } else {
                                    { sink -> detachRemoteContentSinkForCid(tileKey.cid, sink) }
                                },
                                rendererName = if (isLocalTile) {
                                    "frontline-stage-local-content"
                                } else {
                                    "frontline-stage-remote-content-${tileKey.cid}"
                                },
                                strings = strings,
                                contentScale = if (
                                    frontlineRemoteScreenShareUsesFit(
                                        isRemoteScreenShare = isRemoteScreenShare,
                                        remoteVideoFitCover = remoteVideoFitCover,
                                    )
                                ) ContentScale.Fit else ContentScale.Crop,
                            )
                            if (tile.id == pinnedSpotlightId) {
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
                            // Owner label: whose screen this is. Content carries no
                            // audio, so a screen-share glyph instead of the mic chip.
                            val contentOwnerName = if (isLocalTile) {
                                localDisplayName(uiState, strings)
                            } else {
                                remoteDisplayName(tileRemote)
                            }
                            Surface(
                                color = Color.Black.copy(alpha = 0.62f),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ScreenShare,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    if (contentOwnerName.isNotEmpty()) {
                                        Text(
                                            text = contentOwnerName,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            if (tile.zOrder == 0 && isRemoteScreenShare) {
                                FrontlineRemoteFitButton(
                                    remoteVideoFitCover = false,
                                    strings = strings,
                                    onClick = {
                                        onEnterRemoteScreenShareFullscreen(
                                            FrontlineRemoteScreenShareSource(
                                                ownerCid = tileKey.cid,
                                                mode = FrontlineRemoteScreenShareMode.Independent,
                                                loading = ownerContent.loading,
                                            ),
                                        )
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp),
                                )
                            }
                        }
                    } else {
                        // Camera tile (local or remote) — reuse the existing Frontline
                        // tile so name chips / accents / fit toggle stay consistent.
                        FrontlineLayoutTile(
                            tileId = tileKey.cid,
                            isLocal = isLocalTile,
                            isContentTile = false,
                            isLocalContent = false,
                            remote = tileRemote,
                            uiState = uiState,
                            eglContext = eglContext,
                            localContentMode = localContentMode,
                            localZoomTransformState = localZoomTransformState,
                            localRendererEvents = localRendererEvents,
                            onRemoteAspectRatioChanged = tileRemote?.let { participant ->
                                { ratio: Float -> remoteAspectRatios[participant.cid] = ratio }
                            },
                            attachLocalSink = attachLocalSink,
                            detachLocalSink = detachLocalSink,
                            attachRemoteSinkForCid = attachRemoteSinkForCid,
                            detachRemoteSinkForCid = detachRemoteSinkForCid,
                            contentScale = if (tile.fit == FitMode.CONTAIN) ContentScale.Fit else ContentScale.Crop,
                            cornerRadius = tileCornerRadius,
                            pinned = tile.id == pinnedSpotlightId,
                            showRemoteFitButton = tile.zOrder == 0 && tileRemote != null,
                            remoteVideoFitCover = remoteVideoFitCover,
                            onToggleRemoteVideoFit = onToggleRemoteVideoFit,
                            onSelect = onSelect,
                            onTogglePinned = onTogglePinned,
                            strings = strings,
                            modifier = tileModifier,
                        )
                    }
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
    onRemoteAspectRatioChanged: ((Float) -> Unit)?,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    contentScale: ContentScale,
    cornerRadius: Dp,
    pinned: Boolean,
    showRemoteFitButton: Boolean,
    remoteVideoFitCover: Boolean,
    onToggleRemoteVideoFit: () -> Unit,
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
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    FrontlineRemoteVideoSurface(
                        remote = remote,
                        width = maxWidth,
                        height = maxHeight,
                        rendererName = "frontline-remote-stage-${remote.cid}",
                        eglContext = eglContext,
                        contentScale = contentScale,
                        onAspectRatioChanged = onRemoteAspectRatioChanged ?: {},
                        onAttach = { sink -> attachRemoteSinkForCid(remote.cid, sink) },
                        onDetach = { sink -> detachRemoteSinkForCid(remote.cid, sink) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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

        if (showRemoteFitButton) {
            FrontlineRemoteFitButton(
                remoteVideoFitCover = remoteVideoFitCover,
                strings = strings,
                onClick = onToggleRemoteVideoFit,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
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
private fun FrontlineRemoteVideoSurface(
    remote: RemoteParticipant,
    width: Dp,
    height: Dp,
    rendererName: String,
    eglContext: EglBase.Context,
    contentScale: ContentScale,
    onAttach: (VideoSink) -> Unit,
    onDetach: (VideoSink) -> Unit,
    modifier: Modifier = Modifier,
    onAspectRatioChanged: (Float) -> Unit = {},
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currentOnAspectRatioChanged = rememberUpdatedState(onAspectRatioChanged)
    var videoAspectRatio by remember(remote.cid) { mutableStateOf(0f) }

    val rendererEvents = remember(remote.cid, mainHandler) {
        object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() = Unit

            override fun onFrameResolutionChanged(widthPx: Int, heightPx: Int, rotation: Int) {
                val rotatedWidth = if (rotation % 180 == 0) widthPx else heightPx
                val rotatedHeight = if (rotation % 180 == 0) heightPx else widthPx
                if (rotatedWidth == 0 || rotatedHeight == 0) return
                val rawRatio = rotatedWidth.toFloat() / rotatedHeight.toFloat()
                val layoutRatio = clampStageTileAspectRatio(rawRatio)
                mainHandler.post {
                    videoAspectRatio = rawRatio
                    currentOnAspectRatioChanged.value(layoutRatio)
                }
            }
        }
    }
    val geometry = computeFitCoverGeometry(width, height, videoAspectRatio)
    val animatedScale by animateFloatAsState(
        targetValue = if (contentScale == ContentScale.Crop) geometry.coverScale else 1f,
        animationSpec = tween(durationMillis = 260),
        label = "frontline_remote_video_scale",
    )

    Box(
        modifier = modifier
            .background(FrontlineSurface)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        TextureVideoSurface(
            modifier = Modifier
                .size(geometry.fitWidth, geometry.fitHeight)
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                },
            rendererName = rendererName,
            eglContext = eglContext,
            onAttach = onAttach,
            onDetach = onDetach,
            contentScale = ContentScale.Crop,
            rendererEvents = rendererEvents,
        )
    }
}

@Composable
private fun FrontlineRemoteScreenShareFullscreenSurface(
    source: FrontlineRemoteScreenShareSource,
    remote: RemoteParticipant?,
    eglContext: EglBase.Context,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    attachRemoteContentSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteContentSinkForCid: (String, VideoSink) -> Unit,
    strings: Map<SerenadaString, String>?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoomScale by remember(source.id) { mutableStateOf(FRONTLINE_REMOTE_SCREEN_SHARE_MIN_ZOOM_SCALE) }
    var panOffset by remember(source.id) { mutableStateOf(FrontlineScreenSharePanOffset()) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .background(FrontlineBlack)
            .clipToBounds()
    ) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            val nextScale = frontlineRemoteScreenShareZoomScale(
                currentScale = zoomScale,
                change = zoomChange,
            )
            panOffset = frontlineRemoteScreenSharePanOffset(
                currentOffset = panOffset,
                panChangeX = frontlineRemoteScreenShareViewportPanChange(panChange.x, nextScale),
                panChangeY = frontlineRemoteScreenShareViewportPanChange(panChange.y, nextScale),
                scale = nextScale,
                viewportWidth = viewportWidthPx,
                viewportHeight = viewportHeightPx,
            )
            zoomScale = nextScale
        }
        val transformModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = zoomScale
                scaleY = zoomScale
                translationX = panOffset.x
                translationY = panOffset.y
            }
            .transformable(transformState)

        when (source.mode) {
            FrontlineRemoteScreenShareMode.Independent -> {
                Box(modifier = transformModifier) {
                    IndependentContentTile(
                        loading = source.loading,
                        waitingForParticipants = false,
                        width = viewportWidth,
                        height = viewportHeight,
                        eglContext = eglContext,
                        onAttach = { sink -> attachRemoteContentSinkForCid(source.ownerCid, sink) },
                        onDetach = { sink -> detachRemoteContentSinkForCid(source.ownerCid, sink) },
                        rendererName = "frontline-fullscreen-remote-content-${source.ownerCid}",
                        strings = strings,
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            FrontlineRemoteScreenShareMode.Legacy -> {
                if (remote?.videoEnabled == true) {
                    FrontlineRemoteVideoSurface(
                        remote = remote,
                        width = maxWidth,
                        height = maxHeight,
                        rendererName = "frontline-fullscreen-remote-${source.ownerCid}",
                        eglContext = eglContext,
                        contentScale = ContentScale.Fit,
                        onAttach = { sink -> attachRemoteSinkForCid(source.ownerCid, sink) },
                        onDetach = { sink -> detachRemoteSinkForCid(source.ownerCid, sink) },
                        modifier = transformModifier,
                    )
                }
            }
        }

        FrontlineRemoteFitButton(
            remoteVideoFitCover = true,
            strings = strings,
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
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
    remoteVideoFitCover: Boolean,
    remoteVideoIsScreenShare: Boolean,
    localZoomTransformState: androidx.compose.foundation.gestures.TransformableState,
    attachLocalRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    attachRemoteSink: (VideoSink) -> Unit,
    detachRemoteSink: (VideoSink) -> Unit,
    strings: Map<SerenadaString, String>?,
    wearableLayout: Boolean = false,
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
            BoxWithConstraints(modifier = modifier.clipToBounds()) {
                FrontlineRemoteVideoSurface(
                    remote = remote,
                    width = maxWidth,
                    height = maxHeight,
                    rendererName = "frontline-remote-main",
                    eglContext = eglContext,
                    contentScale = if (
                        frontlineRemoteScreenShareUsesFit(
                            isRemoteScreenShare = remoteVideoIsScreenShare,
                            remoteVideoFitCover = remoteVideoFitCover,
                        )
                    ) ContentScale.Fit else ContentScale.Crop,
                    onAttach = attachRemoteSink,
                    onDetach = detachRemoteSink,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        else -> {
            val waitingForRemote = uiState.isFrontlineWaitingForRemote()
            if (waitingForRemote) {
                FrontlineWaitingLarge(
                    strings = strings,
                    wearableLayout = wearableLayout,
                    modifier = modifier,
                )
            } else {
                FrontlineAudioLarge(
                    remote = remote,
                    elapsedLabel = rememberFrontlineCallTimer(uiState.callStartedAtMs),
                    strings = strings,
                    wearableLayout = wearableLayout,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun FrontlineWaitingLarge(
    strings: Map<SerenadaString, String>?,
    wearableLayout: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding = if (wearableLayout) 14.dp else 24.dp
    val fontSize = if (wearableLayout) 22.sp else 34.sp
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = resolveString(SerenadaString.FrontlineWaiting, strings),
            color = Color.White,
            fontSize = fontSize,
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
    wearableLayout: Boolean = false,
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
            wearableLayout = wearableLayout,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FrontlineAudioLarge(
    remote: RemoteParticipant?,
    elapsedLabel: String,
    strings: Map<SerenadaString, String>?,
    wearableLayout: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val name = remoteDisplayName(remote)
    val horizontalPadding = if (wearableLayout) 12.dp else 24.dp
    val bottomPadding = if (wearableLayout) FrontlineWearableContentBottomPadding else 0.dp
    val avatarSize = if (wearableLayout) 88.dp else 140.dp
    val avatarFontSize = if (wearableLayout) 34.sp else 58.sp
    val avatarSpacerHeight = if (wearableLayout) 6.dp else 18.dp
    val audioIndicatorSize = if (wearableLayout) 16.dp else 22.dp
    val nameFontSize = if (wearableLayout) 18.sp else 34.sp
    val timerSpacerHeight = if (wearableLayout) 2.dp else 10.dp
    val timerFontSize = if (wearableLayout) 16.sp else 28.sp
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = horizontalPadding, end = horizontalPadding, bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FrontlineAvatar(
            peerId = remote?.peerId,
            displayName = name,
            size = avatarSize,
            fontSize = avatarFontSize,
            borderWidth = 0.dp,
        )
        Spacer(Modifier.height(avatarSpacerHeight))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (wearableLayout) 6.dp else 10.dp),
        ) {
            FrontlineAudioIndicator(
                muted = remote?.audioEnabled == false,
                audioLevel = remote?.audioLevel ?: 0f,
                size = audioIndicatorSize,
            )
            if (name.isNotBlank()) {
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = nameFontSize,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(timerSpacerHeight))
        Text(
            text = elapsedLabel,
            color = FrontlineDim,
            fontSize = timerFontSize,
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
private fun FrontlineWearableControls(
    uiState: CallUiState,
    callControlsEnabled: Boolean,
    videoControlsEnabled: Boolean,
    showMoreButton: Boolean,
    onVideoTap: () -> Unit,
    onToggleAudio: () -> Unit,
    onMore: () -> Unit,
    onEndCall: () -> Unit,
    strings: Map<SerenadaString, String>?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val regularButtonCount =
            (if (callControlsEnabled && videoControlsEnabled) 1 else 0) +
                (if (callControlsEnabled) 1 else 0) +
                (if (callControlsEnabled && showMoreButton) 1 else 0)
        val metrics = frontlineWearableControlsMetrics(maxWidth, regularButtonCount)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = metrics.horizontalPadding,
                    end = metrics.horizontalPadding,
                    bottom = metrics.bottomPadding,
                )
                .height(metrics.endButtonSize),
            horizontalArrangement = if (metrics.useEvenSpacing) {
                Arrangement.SpaceEvenly
            } else {
                Arrangement.spacedBy(metrics.spacing, Alignment.CenterHorizontally)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (callControlsEnabled && videoControlsEnabled) {
                FrontlineWearableButton(
                    icon = if (uiState.localVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    contentDescription = if (uiState.localVideoEnabled) {
                        resolveString(SerenadaString.FrontlineVideoOn, strings)
                    } else {
                        resolveString(SerenadaString.FrontlineVideo, strings)
                    },
                    active = uiState.localVideoEnabled,
                    size = metrics.buttonSize,
                    onClick = onVideoTap,
                    modifier = Modifier.testTag("call.frontline.wearable.video"),
                )
            }
            if (callControlsEnabled) {
                FrontlineWearableButton(
                    icon = if (uiState.localAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = resolveString(SerenadaString.FrontlineMute, strings),
                    danger = !uiState.localAudioEnabled,
                    size = metrics.buttonSize,
                    onClick = onToggleAudio,
                    modifier = Modifier.testTag("call.frontline.wearable.audio"),
                )
                if (showMoreButton) {
                    FrontlineWearableButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = resolveString(SerenadaString.FrontlineMore, strings),
                        size = metrics.buttonSize,
                        onClick = onMore,
                        modifier = Modifier.testTag("call.frontline.wearable.more"),
                    )
                }
            }
            FrontlineWearableButton(
                icon = Icons.Default.CallEnd,
                contentDescription = resolveString(SerenadaString.FrontlineEnd, strings),
                danger = true,
                size = metrics.endButtonSize,
                onClick = onEndCall,
                modifier = Modifier.testTag("call.frontline.endCall"),
            )
        }
    }
}

@Composable
private fun FrontlineWearableButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    danger: Boolean = false,
    size: Dp = FrontlineWearableButtonSize,
) {
    val background = when {
        danger -> FrontlineDanger
        active -> FrontlineAccent
        else -> Color.Black.copy(alpha = 0.74f)
    }
    val foreground = if (active) Color.Black else Color.White
    Surface(
        modifier = modifier
            .size(size)
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .clickable(onClick = onClick),
        color = background,
        shape = CircleShape,
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = foreground,
                modifier = Modifier.size(22.dp),
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
    audioRouteDevice: AudioDevice?,
    audioRouteOptions: List<AudioDevice>,
    screenSharingEnabled: Boolean,
    inviteEnabled: Boolean,
    shareEnabled: Boolean,
    isScreenSharing: Boolean,
    showRemoteScreenShareFullscreen: Boolean,
    showFlipCamera: Boolean,
    showFlashlight: Boolean,
    flashlightEnabled: Boolean,
    showSnapshot: Boolean,
    strings: Map<SerenadaString, String>?,
    onDismiss: () -> Unit,
    onEnterRemoteScreenShareFullscreen: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleFlashlight: () -> Unit,
    onSnapshot: () -> Unit,
    onAudioRoute: () -> Unit,
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
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(FrontlineSheet)
                        .verticalScroll(scrollState)
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
                    if (audioRouteDevice != null || audioRouteOptions.isNotEmpty()) {
                        FrontlineSheetItem(
                            icon = callAudioRouteIcon(audioRouteDevice?.kind),
                            title = audioRouteDevice?.callAudioRouteLabel(strings)
                                ?: resolveString(SerenadaString.CallAudioRoute, strings),
                            onClick = onAudioRoute,
                        )
                    }
                    if (showRemoteScreenShareFullscreen) {
                        FrontlineSheetItem(
                            icon = Icons.Default.Fullscreen,
                            title = resolveString(SerenadaString.FrontlineOpenScreenShare, strings),
                            onClick = onEnterRemoteScreenShareFullscreen,
                        )
                    }
                    if (showFlipCamera) {
                        FrontlineSheetItem(
                            icon = Icons.Default.FlipCameraIos,
                            title = resolveString(SerenadaString.FrontlineFlipCamera, strings),
                            onClick = onFlipCamera,
                        )
                    }
                    if (showFlashlight) {
                        FrontlineSheetItem(
                            icon = if (flashlightEnabled) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                            title = resolveString(SerenadaString.CallToggleFlashlight, strings),
                            onClick = onToggleFlashlight,
                        )
                    }
                    if (showSnapshot) {
                        FrontlineSheetItem(
                            icon = Icons.Default.PhotoCamera,
                            title = resolveString(SerenadaString.CallTakeSnapshot, strings),
                            onClick = onSnapshot,
                        )
                    }
                    if (screenSharingEnabled) {
                        FrontlineSheetItem(
                            icon = if (isScreenSharing) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                            title = if (isScreenSharing) {
                                resolveString(SerenadaString.FrontlineStopScreenShare, strings)
                            } else {
                                resolveString(SerenadaString.FrontlineShareScreen, strings)
                            },
                            danger = isScreenSharing,
                            onClick = onToggleScreenShare,
                        )
                    }
                    if (inviteEnabled) {
                        FrontlineSheetItem(
                            icon = Icons.Default.NotificationsActive,
                            title = resolveString(SerenadaString.CallInviteToRoom, strings),
                            onClick = onInvite,
                        )
                    }
                    if (shareEnabled) {
                        FrontlineSheetItem(
                            icon = Icons.Default.Share,
                            title = resolveString(SerenadaString.CallShareInvitation, strings),
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
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    val background = if (danger) FrontlineDanger else FrontlineSheetRow
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(background)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(38.dp)
                    .padding(8.dp),
            )
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FrontlineRemoteFitButton(
    remoteVideoFitCover: Boolean,
    strings: Map<SerenadaString, String>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.4f), CircleShape),
    ) {
        Icon(
            imageVector = if (remoteVideoFitCover) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            contentDescription = resolveString(SerenadaString.CallToggleVideoFit, strings),
            tint = Color.White,
        )
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

private fun frontlineMoreMenuOpensAudioRouteDirectly(
    showAudioRouteControl: Boolean,
    screenSharingEnabled: Boolean,
    inviteEnabled: Boolean,
): Boolean = showAudioRouteControl && !screenSharingEnabled && !inviteEnabled

internal data class FrontlineWearableControlMetrics(
    val buttonSize: Dp,
    val endButtonSize: Dp,
    val horizontalPadding: Dp,
    val bottomPadding: Dp,
    val spacing: Dp,
    val useEvenSpacing: Boolean,
)

internal fun frontlineWearableControlsMetrics(
    width: Dp,
    regularButtonCount: Int,
): FrontlineWearableControlMetrics {
    val regularCount = regularButtonCount.coerceAtLeast(0)
    val defaultMetrics = FrontlineWearableControlMetrics(
        buttonSize = FrontlineWearableButtonSize,
        endButtonSize = FrontlineWearableEndButtonSize,
        horizontalPadding = FrontlineWearableControlsHorizontalPadding,
        bottomPadding = FrontlineWearableControlsBottomPadding,
        spacing = FrontlineWearableControlsSpacing,
        useEvenSpacing = false,
    )
    if (frontlineWearableControlsRequiredWidth(defaultMetrics, regularCount) <= width) {
        return defaultMetrics
    }

    val compactEvenMetrics = defaultMetrics.copy(
        horizontalPadding = FrontlineWearableControlsCompactHorizontalPadding,
        spacing = FrontlineWearableControlsCompactSpacing,
        useEvenSpacing = true,
    )
    if (frontlineWearableControlsRequiredWidth(compactEvenMetrics, regularCount) <= width) {
        return compactEvenMetrics
    }

    val compactButtonMetrics = compactEvenMetrics.copy(
        buttonSize = FrontlineWearableCompactButtonSize,
        endButtonSize = FrontlineWearableCompactEndButtonSize,
    )
    if (frontlineWearableControlsRequiredWidth(compactButtonMetrics, regularCount) <= width) {
        return compactButtonMetrics
    }

    return compactButtonMetrics.copy(
        buttonSize = FrontlineWearableMinimumButtonSize,
        endButtonSize = FrontlineWearableMinimumEndButtonSize,
        horizontalPadding = FrontlineWearableControlsMinimumHorizontalPadding,
    )
}

internal fun frontlineWearableControlsRequiredWidth(
    metrics: FrontlineWearableControlMetrics,
    regularButtonCount: Int,
): Dp {
    val regularCount = regularButtonCount.coerceAtLeast(0)
    val fixedControlsWidth =
        metrics.buttonSize * regularCount.toFloat() +
            metrics.endButtonSize +
            metrics.horizontalPadding * 2f
    val gapsWidth = if (metrics.useEvenSpacing) {
        0.dp
    } else {
        metrics.spacing * regularCount.toFloat()
    }
    return fixedControlsWidth + gapsWidth
}

internal fun frontlineWearableMoreActionsAvailable(
    localVideoEnabled: Boolean,
    availableCameraModeCount: Int,
    snapshotAvailable: Boolean,
    flashAvailable: Boolean,
    remoteScreenShareFullscreenAvailable: Boolean,
): Boolean =
    remoteScreenShareFullscreenAvailable ||
        (
            localVideoEnabled &&
                (availableCameraModeCount > 1 || snapshotAvailable || flashAvailable)
            )

private fun frontlineShowsRemoteFitButton(
    isCallSurfacePhase: Boolean,
    waitingForRemote: Boolean,
    remoteParticipantCount: Int,
    largeFeedIsRemote: Boolean,
    remoteVideoEnabled: Boolean,
): Boolean =
    isCallSurfacePhase &&
        !waitingForRemote &&
        remoteParticipantCount <= 1 &&
        largeFeedIsRemote &&
        remoteVideoEnabled

internal fun frontlineUsesWearableLayout(width: Dp, height: Dp): Boolean {
    return width <= FrontlineWearableMaxEdge && height <= FrontlineWearableMaxEdge
}

private data class FrontlinePipSize(val width: Dp, val height: Dp)

private fun frontlinePipSize(
    containerWidth: Dp,
    containerHeight: Dp,
    inPanel: Boolean,
): FrontlinePipSize {
    if (inPanel) {
        return FrontlinePipSize(width = 220.dp, height = 280.dp)
    }
    val referenceWidth = if (containerWidth > containerHeight) containerHeight else containerWidth
    return when {
        referenceWidth >= 1100.dp -> FrontlinePipSize(width = 172.dp, height = 220.dp)
        referenceWidth >= 720.dp -> FrontlinePipSize(width = 152.dp, height = 196.dp)
        referenceWidth >= 480.dp -> FrontlinePipSize(width = 120.dp, height = 154.dp)
        else -> FrontlinePipSize(width = 100.dp, height = 128.dp)
    }
}

private fun String.frontlineContentSpotlightId(): String = "$FRONTLINE_CONTENT_SPOTLIGHT_PREFIX$this"

private fun String?.isFrontlineContentSpotlightId(): Boolean =
    this?.startsWith(FRONTLINE_CONTENT_SPOTLIGHT_PREFIX) == true

private fun CallUiState.isFrontlineWaitingForRemote(): Boolean {
    return (phase == CallPhase.Waiting || phase == CallPhase.InCall) &&
        remoteParticipants.isEmpty()
}
