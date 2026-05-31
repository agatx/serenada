package app.serenada.callui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import app.serenada.core.call.CallPhase
import app.serenada.core.call.RemoteParticipant
import kotlin.math.roundToInt
import org.webrtc.EglBase
import org.webrtc.VideoSink

internal sealed interface SystemPictureInPictureFeed {
    object Local : SystemPictureInPictureFeed

    data class Remote(val cid: String?) : SystemPictureInPictureFeed
}

/**
 * Chooses which feed system PiP should surface. Shared by [CallScreen] and
 * [FrontlineCallScreen] so the priority order stays in one place: prefer the
 * local camera when it is the large feed, otherwise a present remote, then any
 * enabled local camera, falling back to remote.
 */
internal fun selectSystemPictureInPictureFeed(
    localIsLarge: Boolean,
    localVideoEnabled: Boolean,
    remoteCid: String?,
): SystemPictureInPictureFeed =
    when {
        localIsLarge && localVideoEnabled -> SystemPictureInPictureFeed.Local
        remoteCid != null -> SystemPictureInPictureFeed.Remote(remoteCid)
        localVideoEnabled -> SystemPictureInPictureFeed.Local
        else -> SystemPictureInPictureFeed.Remote(null)
    }

@Composable
internal fun rememberSystemPictureInPictureMode(
    enabled: Boolean,
    uiState: CallUiState,
    sourceRect: Rect?,
    onPictureInPictureDismissRequested: () -> Unit,
): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val componentActivity = activity as? ComponentActivity
    val supported =
        enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true
    val shouldEnter = supported && uiState.phase.isSystemPipPhase()
    val latestShouldEnter by rememberUpdatedState(shouldEnter)
    val latestOnPictureInPictureDismissRequested by rememberUpdatedState(onPictureInPictureDismissRequested)
    val latestParams = remember { mutableStateOf<PictureInPictureParams?>(null) }
    var isInPictureInPicture by remember(activity) {
        mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInPictureInPictureMode == true)
    }
    DisposableEffect(componentActivity) {
        if (componentActivity == null) return@DisposableEffect onDispose {}
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            isInPictureInPicture = info.isInPictureInPictureMode
        }
        componentActivity.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            componentActivity.removeOnPictureInPictureModeChangedListener(listener)
        }
    }

    LaunchedEffect(activity, supported, shouldEnter, sourceRect) {
        if (!supported) {
            latestParams.value = null
            return@LaunchedEffect
        }
        val hostActivity = activity ?: return@LaunchedEffect
        val params =
            buildSystemPictureInPictureParams(
                sourceRect = sourceRect,
                autoEnter = shouldEnter,
            )
        latestParams.value = params
        runCatching { hostActivity.setPictureInPictureParams(params) }
    }

    DisposableEffect(componentActivity, supported) {
        if (
            !supported ||
            componentActivity == null ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            return@DisposableEffect onDispose {}
        }
        val listener = Runnable {
            val params = latestParams.value ?: return@Runnable
            if (latestShouldEnter) {
                runCatching { componentActivity.enterPictureInPictureMode(params) }
            }
        }
        componentActivity.addOnUserLeaveHintListener(listener)
        onDispose {
            componentActivity.removeOnUserLeaveHintListener(listener)
        }
    }

    LaunchedEffect(activity, supported, isInPictureInPicture, shouldEnter) {
        if (supported && isInPictureInPicture && !shouldEnter) {
            latestOnPictureInPictureDismissRequested()
        }
    }

    return isInPictureInPicture
}

private fun buildSystemPictureInPictureParams(
    sourceRect: Rect?,
    autoEnter: Boolean,
): PictureInPictureParams {
    val builder =
        PictureInPictureParams.Builder()
            .setAspectRatio(sourceRect.toPipAspectRatio())
    if (sourceRect != null && !sourceRect.isEmpty) {
        builder.setSourceRectHint(sourceRect)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter)
        builder.setSeamlessResizeEnabled(false)
    }
    return builder.build()
}

@Composable
internal fun SystemPictureInPictureContent(
    uiState: CallUiState,
    feed: SystemPictureInPictureFeed,
    eglContext: EglBase.Context,
    localContentScale: ContentScale,
    remoteContentScale: ContentScale,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
) {
    val remote =
        when (feed) {
            SystemPictureInPictureFeed.Local -> null
            is SystemPictureInPictureFeed.Remote -> {
                feed.cid?.let { cid -> uiState.remoteParticipants.firstOrNull { it.cid == cid } }
                    ?: uiState.remoteParticipants.firstOrNull()
            }
        }
    val showLocalVideo = feed == SystemPictureInPictureFeed.Local && uiState.localVideoEnabled
    val showRemoteVideo = feed is SystemPictureInPictureFeed.Remote && remote?.videoEnabled == true

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            showLocalVideo -> {
                TextureVideoSurface(
                    modifier = Modifier.fillMaxSize(),
                    rendererName = "system-pip-local",
                    eglContext = eglContext,
                    onAttach = attachLocalSink,
                    onDetach = detachLocalSink,
                    mirror = uiState.isFrontCamera && !uiState.isScreenSharing,
                    contentScale = localContentScale,
                )
            }
            showRemoteVideo -> {
                TextureVideoSurface(
                    modifier = Modifier.fillMaxSize(),
                    rendererName = "system-pip-remote-${remote.cid}",
                    eglContext = eglContext,
                    onAttach = { sink ->
                        remote.cid.let { cid -> attachRemoteSinkForCid(cid, sink) }
                    },
                    onDetach = { sink ->
                        remote.cid.let { cid -> detachRemoteSinkForCid(cid, sink) }
                    },
                    mirror = false,
                    contentScale = remoteContentScale,
                )
            }
            else -> {
                SystemPictureInPictureAvatar(
                    participant = remote,
                    displayName = if (feed == SystemPictureInPictureFeed.Local) {
                        uiState.localDisplayName
                    } else {
                        remote?.displayName
                    },
                    peerId = if (feed == SystemPictureInPictureFeed.Local) {
                        uiState.localCid
                    } else {
                        remote?.peerId
                    },
                )
            }
        }
    }
}

@Composable
private fun SystemPictureInPictureAvatar(
    participant: RemoteParticipant?,
    displayName: String?,
    peerId: String?,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color(0xFF111111)),
        contentAlignment = Alignment.Center,
    ) {
        val minDimension = minOf(maxWidth, maxHeight)
        val avatarSize = (minDimension * 0.46f).coerceIn(42.dp, 108.dp)
        val avatarFontSize = (avatarSize.value * 0.38f).sp
        val showName = maxHeight >= 150.dp && !displayName.isNullOrBlank()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            RemoteAvatar(
                peerId = peerId ?: participant?.peerId,
                displayName = displayName ?: participant?.displayName,
                size = avatarSize,
                fontSize = avatarFontSize,
            )
            if (showName) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = displayName.orEmpty(),
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun Rect?.toPipAspectRatio(): Rational {
    if (this != null && width() > 0 && height() > 0) {
        return boundedRational(width().toFloat() / height().toFloat())
    }
    return Rational(9, 16)
}

private fun boundedRational(ratio: Float): Rational {
    val bounded = ratio.coerceIn(1f / 2.39f, 2.39f)
    return Rational((bounded * 1_000).roundToInt().coerceAtLeast(1), 1_000)
}

private fun CallPhase.isSystemPipPhase(): Boolean =
    this == CallPhase.Waiting || this == CallPhase.InCall

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
