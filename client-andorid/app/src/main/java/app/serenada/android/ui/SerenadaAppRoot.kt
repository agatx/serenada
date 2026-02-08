package app.serenada.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.serenada.android.R
import app.serenada.android.call.CallManager
import app.serenada.android.call.CallPhase

private enum class RootScreen {
    Join,
    JoinWithCode,
    Settings,
    Call,
    Error
}

@Composable
fun SerenadaAppRoot(callManager: CallManager) {
    val uiState by callManager.uiState
    val serverHost by callManager.serverHost
    val selectedLanguage by callManager.selectedLanguage
    val context = LocalContext.current
    val showActiveCallScreen =
        uiState.phase == CallPhase.Waiting ||
            uiState.phase == CallPhase.InCall ||
            uiState.connectionState == "CONNECTED"

    var hostInput by rememberSaveable { mutableStateOf(serverHost) }
    var roomInput by rememberSaveable { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showJoinWithCode by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(serverHost) {
        hostInput = serverHost
    }

    LaunchedEffect(uiState.phase) {
        if (uiState.phase == CallPhase.Waiting || uiState.phase == CallPhase.InCall) {
            showJoinWithCode = false
            roomInput = ""
        }
    }

    LaunchedEffect(showActiveCallScreen) {
        if (showActiveCallScreen) {
            showJoinWithCode = false
            showSettings = false
            roomInput = ""
        }
    }

    val permissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            pendingAction?.invoke()
        }
        pendingAction = null
    }

    fun runWithPermissions(action: () -> Unit) {
        val allGranted = permissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            action()
        } else {
            pendingAction = action
            launcher.launch(permissions)
        }
    }

    SerenadaTheme {
        val statusMessage = uiState.statusMessageResId?.let { stringResource(it) }.orEmpty()
        val errorMessage = uiState.errorMessageResId?.let { stringResource(it) } ?: uiState.errorMessageText
        val hasError = !errorMessage.isNullOrBlank()
        val currentScreen = when {
            showSettings -> RootScreen.Settings
            showJoinWithCode -> RootScreen.JoinWithCode
            showActiveCallScreen -> RootScreen.Call
            uiState.phase == CallPhase.Error -> RootScreen.Error
            else -> RootScreen.Join
        }

        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                val durationMs = 260
                val isEnteringCall = targetState == RootScreen.Call
                val isLeavingCall = initialState == RootScreen.Call
                when {
                    isEnteringCall -> {
                        (slideInVertically(
                            animationSpec = tween(durationMillis = durationMs),
                            initialOffsetY = { it / 4 }
                        ) + fadeIn(animationSpec = tween(durationMillis = durationMs)))
                            .togetherWith(
                                slideOutVertically(
                                    animationSpec = tween(durationMillis = durationMs),
                                    targetOffsetY = { -it / 8 }
                                ) + fadeOut(animationSpec = tween(durationMillis = durationMs))
                            )
                    }
                    isLeavingCall -> {
                        (slideInVertically(
                            animationSpec = tween(durationMillis = durationMs),
                            initialOffsetY = { -it / 8 }
                        ) + fadeIn(animationSpec = tween(durationMillis = durationMs)))
                            .togetherWith(
                                slideOutVertically(
                                    animationSpec = tween(durationMillis = durationMs),
                                    targetOffsetY = { it / 4 }
                                ) + fadeOut(animationSpec = tween(durationMillis = durationMs))
                            )
                    }
                    else -> {
                        val slideDistance: (Int) -> Int = { fullWidth -> (fullWidth * 0.18f).toInt() }
                        val movingForward = targetState.ordinal > initialState.ordinal
                        val slideInOffset: (Int) -> Int = { width ->
                            if (movingForward) slideDistance(width) else -slideDistance(width)
                        }
                        val slideOutOffset: (Int) -> Int = { width ->
                            if (movingForward) -slideDistance(width) else slideDistance(width)
                        }
                        (slideInHorizontally(
                            animationSpec = tween(durationMillis = durationMs),
                            initialOffsetX = slideInOffset
                        ) + fadeIn(animationSpec = tween(durationMillis = durationMs)))
                            .togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(durationMillis = durationMs),
                                    targetOffsetX = slideOutOffset
                                ) + fadeOut(animationSpec = tween(durationMillis = durationMs))
                            )
                    }
                }
            },
            label = "root_screen_transition"
        ) { screen ->
            when (screen) {
                RootScreen.Settings -> {
                    SettingsScreen(
                        host = hostInput,
                        selectedLanguage = selectedLanguage,
                        onHostChange = { hostInput = it },
                        onLanguageSelect = { callManager.updateLanguage(it) },
                        onSave = {
                            callManager.updateServerHost(hostInput)
                            showSettings = false
                        },
                        onCancel = {
                            hostInput = serverHost
                            showSettings = false
                        }
                    )
                }
                RootScreen.JoinWithCode -> {
                    JoinWithCodeScreen(
                        roomInput = roomInput,
                        isBusy = uiState.phase == CallPhase.Joining || uiState.phase == CallPhase.CreatingRoom,
                        statusMessage = statusMessage,
                        errorMessage = errorMessage,
                        onRoomInputChange = {
                            roomInput = it
                            if (hasError) callManager.dismissError()
                        },
                        onJoinCall = {
                            callManager.updateServerHost(hostInput)
                            runWithPermissions {
                                callManager.joinFromInput(roomInput)
                            }
                        },
                        onBack = {
                            if (hasError) callManager.dismissError()
                            showJoinWithCode = false
                            roomInput = ""
                        }
                    )
                }
                RootScreen.Call -> {
                    CallScreen(
                        roomId = uiState.roomId.orEmpty(),
                        uiState = uiState,
                        serverHost = serverHost,
                        eglContext = callManager.eglContext(),
                        onToggleAudio = { callManager.toggleAudio() },
                        onToggleVideo = { callManager.toggleVideo() },
                        onFlipCamera = { callManager.flipCamera() },
                        onEndCall = { callManager.endCall() },
                        attachLocalRenderer = { renderer, events ->
                            callManager.attachLocalRenderer(renderer, events)
                        },
                        detachLocalRenderer = { callManager.detachLocalRenderer(it) },
                        attachLocalSink = { callManager.attachLocalSink(it) },
                        detachLocalSink = { callManager.detachLocalSink(it) },
                        attachRemoteRenderer = { renderer, events ->
                            callManager.attachRemoteRenderer(renderer, events)
                        },
                        detachRemoteRenderer = { callManager.detachRemoteRenderer(it) },
                        attachRemoteSink = { callManager.attachRemoteSink(it) },
                        detachRemoteSink = { callManager.detachRemoteSink(it) }
                    )
                }
                RootScreen.Error -> {
                    ErrorScreen(
                        message = errorMessage ?: stringResource(R.string.error_something_went_wrong),
                        onDismiss = { callManager.dismissError() }
                    )
                }
                RootScreen.Join -> {
                    JoinScreen(
                        isBusy = uiState.phase == CallPhase.CreatingRoom || uiState.phase == CallPhase.Joining,
                        statusMessage = statusMessage,
                        onOpenJoinWithCode = { showJoinWithCode = true },
                        onOpenSettings = { showSettings = true },
                        onStartCall = {
                            callManager.updateServerHost(hostInput)
                            runWithPermissions { callManager.startNewCall() }
                        }
                    )
                }
            }
        }
    }
}
