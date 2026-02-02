package app.serenada.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.serenada.android.call.CallManager
import app.serenada.android.call.CallPhase

@Composable
fun SerenadaAppRoot(callManager: CallManager) {
    val uiState by callManager.uiState
    val serverHost by callManager.serverHost
    val context = LocalContext.current

    var hostInput by remember { mutableStateOf(serverHost) }
    var roomInput by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showJoinWithCode by remember { mutableStateOf(false) }

    LaunchedEffect(serverHost) {
        hostInput = serverHost
    }

    LaunchedEffect(uiState.phase) {
        if (uiState.phase == CallPhase.Waiting || uiState.phase == CallPhase.InCall) {
            showJoinWithCode = false
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
        if (showSettings) {
            SettingsScreen(
                host = hostInput,
                onHostChange = { hostInput = it },
                onSave = {
                    callManager.updateServerHost(hostInput)
                    showSettings = false
                },
                onCancel = {
                    hostInput = serverHost
                    showSettings = false
                }
            )
            return@SerenadaTheme
        }

        if (showJoinWithCode) {
            JoinWithCodeScreen(
                roomInput = roomInput,
                isBusy = uiState.phase == CallPhase.Joining || uiState.phase == CallPhase.CreatingRoom,
                statusMessage = uiState.statusMessage,
                errorMessage = uiState.errorMessage,
                onRoomInputChange = {
                    roomInput = it
                    if (uiState.errorMessage != null) callManager.dismissError()
                },
                onJoinCall = {
                    callManager.updateServerHost(hostInput)
                    runWithPermissions {
                        callManager.joinFromInput(roomInput)
                    }
                },
                onBack = {
                    if (uiState.errorMessage != null) callManager.dismissError()
                    showJoinWithCode = false
                    roomInput = ""
                }
            )
            return@SerenadaTheme
        }

        when (uiState.phase) {
            CallPhase.Idle, CallPhase.CreatingRoom, CallPhase.Joining, CallPhase.Ending -> {
                JoinScreen(
                    isBusy = uiState.phase == CallPhase.CreatingRoom || uiState.phase == CallPhase.Joining,
                    statusMessage = uiState.statusMessage,
                    onOpenJoinWithCode = { showJoinWithCode = true },
                    onOpenSettings = { showSettings = true },
                    onStartCall = {
                        callManager.updateServerHost(hostInput)
                        runWithPermissions { callManager.startNewCall() }
                    }
                )
            }
            CallPhase.Waiting, CallPhase.InCall -> {
                CallScreen(
                    roomId = uiState.roomId.orEmpty(),
                    uiState = uiState,
                    serverHost = serverHost,
                    onToggleAudio = { callManager.toggleAudio() },
                    onToggleVideo = { callManager.toggleVideo() },
                    onFlipCamera = { callManager.flipCamera() },
                    onEndCall = { callManager.endCall() },
                    attachLocalRenderer = { callManager.attachLocalRenderer(it) },
                    detachLocalRenderer = { callManager.detachLocalRenderer(it) },
                    attachRemoteRenderer = { callManager.attachRemoteRenderer(it) },
                    detachRemoteRenderer = { callManager.detachRemoteRenderer(it) }
                )
            }
            CallPhase.Error -> {
                ErrorScreen(
                    message = uiState.errorMessage ?: "Something went wrong",
                    onDismiss = { callManager.dismissError() }
                )
            }
        }
    }
}
