package app.serenada.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
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
    Diagnostics,
    Call,
    Error
}

@Composable
fun SerenadaAppRoot(
    callManager: CallManager,
    deepLinkUri: Uri?,
    onDeepLinkConsumed: () -> Unit
) {
    val uiState by callManager.uiState
    val serverHost by callManager.serverHost
    val selectedLanguage by callManager.selectedLanguage
    val recentCalls by callManager.recentCalls
    val savedRooms by callManager.savedRooms
    val areSavedRoomsShownFirst by callManager.areSavedRoomsShownFirst
    val roomStatuses by callManager.roomStatuses
    val context = LocalContext.current
    val showActiveCallScreen =
        uiState.phase == CallPhase.Waiting ||
                uiState.phase == CallPhase.InCall ||
                uiState.connectionState == "CONNECTED"

    var hostInput by rememberSaveable { mutableStateOf(serverHost) }
    var roomInput by rememberSaveable { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsHostError by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsSaveInProgress by rememberSaveable { mutableStateOf(false) }
    var showJoinWithCode by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

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
            showDiagnostics = false
            roomInput = ""
        }
    }

    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }

    val callPermissions = remember {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
    val notificationPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationPermissionRequested = true
    }

    fun requestNotificationPermissionIfNeeded() {
        val permission = notificationPermission ?: return
        if (notificationPermissionRequested) return
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            notificationPermissionRequested = true
            return
        }
        notificationPermissionRequested = true
        notificationLauncher.launch(permission)
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

    fun runWithCallPermissions(action: () -> Unit) {
        val allGranted = callPermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            requestNotificationPermissionIfNeeded()
            action()
        } else {
            pendingAction = {
                requestNotificationPermissionIfNeeded()
                action()
            }
            launcher.launch(callPermissions)
        }
    }

    LaunchedEffect(deepLinkUri) {
        val uri = deepLinkUri ?: return@LaunchedEffect
        runWithCallPermissions {
            callManager.handleDeepLink(uri)
        }
        onDeepLinkConsumed()
    }

    SerenadaTheme {
        val statusMessage = uiState.statusMessageResId?.let { stringResource(it) }.orEmpty()
        val errorMessage = uiState.errorMessageResId?.let { stringResource(it) } ?: uiState.errorMessageText
        val hasError = !errorMessage.isNullOrBlank()
        val currentScreen = when {
            showDiagnostics -> RootScreen.Diagnostics
            showSettings -> RootScreen.Settings
            showJoinWithCode -> RootScreen.JoinWithCode
            showActiveCallScreen -> RootScreen.Call
            uiState.phase == CallPhase.Error -> RootScreen.Error
            else -> RootScreen.Join
        }

        fun closeSettings() {
            hostInput = serverHost
            settingsHostError = null
            settingsSaveInProgress = false
            showDiagnostics = false
            showSettings = false
        }

        fun closeJoinWithCode() {
            if (hasError) callManager.dismissError()
            showJoinWithCode = false
            roomInput = ""
        }

        val handlesBackNavigation = currentScreen == RootScreen.Diagnostics ||
            currentScreen == RootScreen.Settings ||
            currentScreen == RootScreen.JoinWithCode ||
            currentScreen == RootScreen.Error

        BackHandler(enabled = handlesBackNavigation) {
            when (currentScreen) {
                RootScreen.Diagnostics -> showDiagnostics = false
                RootScreen.Settings -> closeSettings()
                RootScreen.JoinWithCode -> closeJoinWithCode()
                RootScreen.Error -> callManager.dismissError()
                RootScreen.Join,
                RootScreen.Call -> Unit
            }
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
                        hostError = settingsHostError,
                        isSaving = settingsSaveInProgress,

                        isDefaultCameraEnabled = callManager.isDefaultCameraEnabled.value,
                        isDefaultMicrophoneEnabled = callManager.isDefaultMicrophoneEnabled.value,
                        isHdVideoExperimentalEnabled = callManager.isHdVideoExperimentalEnabled.value,
                        areSavedRoomsShownFirst = areSavedRoomsShownFirst,
                        onDefaultCameraChange = { callManager.updateDefaultCamera(it) },
                        onDefaultMicrophoneChange = { callManager.updateDefaultMicrophone(it) },
                        onHdVideoExperimentalChange = { callManager.updateHdVideoExperimental(it) },
                        onSavedRoomsShownFirstChange = { callManager.updateSavedRoomsShownFirst(it) },
                        onOpenDiagnostics = {
                            showDiagnostics = true
                        },

                        onHostChange = {
                            hostInput = it
                            settingsHostError = null
                        },
                        onLanguageSelect = { callManager.updateLanguage(it) },
                        onSave = {
                            if (settingsSaveInProgress) return@SettingsScreen
                            settingsHostError = null
                            settingsSaveInProgress = true
                            callManager.validateServerHost(hostInput) { result ->
                                settingsSaveInProgress = false
                                result
                                    .onSuccess { validatedHost ->
                                        callManager.updateServerHost(validatedHost)
                                        settingsHostError = null
                                        showSettings = false
                                    }
                                    .onFailure {
                                        settingsHostError =
                                            context.getString(R.string.settings_error_invalid_server_host)
                                    }
                            }
                        },
                        onCancel = {
                            closeSettings()
                        }
                    )
                }
                RootScreen.Diagnostics -> {
                    DiagnosticsScreen(
                        host = hostInput,
                        onBack = { showDiagnostics = false }
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
                            runWithCallPermissions {
                                callManager.joinFromInput(roomInput)
                            }
                        },
                        onBack = {
                            closeJoinWithCode()
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
                        onToggleFlashlight = { callManager.toggleFlashlight() },
                        onEndCall = { callManager.leaveCall() },
                        onStartScreenShare = { intent -> callManager.startScreenShare(intent) },
                        onStopScreenShare = { callManager.stopScreenShare() },
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
                        recentCalls = recentCalls,
                        savedRooms = savedRooms,
                        areSavedRoomsShownFirst = areSavedRoomsShownFirst,
                        roomStatuses = roomStatuses,
                        onOpenJoinWithCode = { showJoinWithCode = true },
                        onOpenSettings = {
                            hostInput = serverHost
                            settingsHostError = null
                            settingsSaveInProgress = false
                            showDiagnostics = false
                            showSettings = true
                        },
                        onStartCall = {
                            callManager.updateServerHost(hostInput)
                            runWithCallPermissions { callManager.startNewCall() }
                        },
                        onJoinRecentCall = { roomId ->
                            callManager.updateServerHost(hostInput)
                            runWithCallPermissions { callManager.joinRoom(roomId) }
                        },
                        onJoinSavedRoom = { room ->
                            runWithCallPermissions { callManager.joinSavedRoom(room) }
                        },
                        onRemoveRecentCall = { roomId ->
                            callManager.removeRecentCall(roomId)
                        },
                        onSaveRoom = { roomId, name ->
                            callManager.saveRoom(roomId, name)
                        },
                        onCreateSavedRoomInviteLink = { roomName, onResult ->
                            callManager.createSavedRoomInviteLink(
                                roomName = roomName,
                                hostInput = hostInput,
                                onResult = onResult
                            )
                        },
                        onRemoveSavedRoom = { roomId ->
                            callManager.removeSavedRoom(roomId)
                        }
                    )
                }
            }
        }
    }
}
