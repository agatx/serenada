package app.serenada.android.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.serenada.android.BuildConfig
import app.serenada.android.R
import app.serenada.android.network.ApiClient
import app.serenada.android.network.TurnCredentials
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.Camera2Enumerator
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

private enum class CheckState {
    Pass,
    Warn,
    Fail,
    Running,
    Idle
}

private data class CheckResult(
    val state: CheckState,
    val detail: String
)

private data class ConnectivityReport(
    val roomIdEndpoint: CheckResult,
    val webSocket: CheckResult,
    val diagnosticToken: CheckResult,
    val turnCredentials: CheckResult
)

private data class MediaReport(
    val cameraHardware: CheckResult,
    val frontCamera: CheckResult,
    val backCamera: CheckResult,
    val compositeModePrerequisite: CheckResult,
    val microphoneFeature: CheckResult,
    val echoCancellation: CheckResult,
    val noiseSuppression: CheckResult,
    val autoGainControl: CheckResult,
    val audioSampleRate: String,
    val audioFramesPerBuffer: String
)

private data class IceReport(
    val turnsOnly: Boolean,
    val stun: CheckResult,
    val turn: CheckResult,
    val iceServersSummary: String,
    val logs: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    host: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var permissions by remember {
        mutableStateOf(readPermissionStatus(context, requiredPermissions))
    }
    var mediaReport by remember { mutableStateOf(buildMediaReport(context)) }
    var connectivityReport by remember { mutableStateOf<ConnectivityReport?>(null) }
    var iceReport by remember { mutableStateOf<IceReport?>(null) }
    var iceTurnsOnlyMode by remember { mutableStateOf(false) }
    var connectivityInProgress by remember { mutableStateOf(false) }
    var iceInProgress by remember { mutableStateOf(false) }
    var iceLiveServersSummary by remember { mutableStateOf<String?>(null) }
    val iceLiveLogs = remember { emptyList<String>().toMutableStateList() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissions = readPermissionStatus(context, requiredPermissions)
        mediaReport = buildMediaReport(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_device_check)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val report = buildDiagnosticsReport(
                                generatedAtIso = Instant.now().toString(),
                                host = host,
                                permissions = permissions,
                                mediaReport = mediaReport,
                                connectivityReport = connectivityReport,
                                iceReport = iceReport,
                                iceTurnsOnlyMode = iceTurnsOnlyMode,
                                connectivityInProgress = connectivityInProgress,
                                iceInProgress = iceInProgress,
                                notRunLabel = context.getString(R.string.diagnostics_not_run),
                                runningLabel = context.getString(R.string.diagnostics_running)
                            )
                            copyAndShareDiagnostics(
                                context = context,
                                chooserTitle = context.getString(R.string.diagnostics_share_chooser),
                                report = report
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.diagnostics_share_content_desc)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(title = stringResource(R.string.diagnostics_target_host)) {
                LabeledTextRow(
                    label = stringResource(R.string.settings_server_host),
                    value = host.ifBlank { "-" }
                )
            }

            SectionCard(title = stringResource(R.string.diagnostics_permissions_title)) {
                permissions.forEach { (label, result) ->
                    StatusRow(label = label, result = result)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.diagnostics_request_permissions))
                }
            }

            SectionCard(title = stringResource(R.string.diagnostics_media_title)) {
                StatusRow(stringResource(R.string.diagnostics_camera_hardware), mediaReport.cameraHardware)
                StatusRow(stringResource(R.string.diagnostics_front_camera), mediaReport.frontCamera)
                StatusRow(stringResource(R.string.diagnostics_back_camera), mediaReport.backCamera)
                StatusRow(
                    stringResource(R.string.diagnostics_composite_prerequisite),
                    mediaReport.compositeModePrerequisite
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                StatusRow(stringResource(R.string.diagnostics_microphone_feature), mediaReport.microphoneFeature)
                StatusRow(stringResource(R.string.diagnostics_echo_cancellation), mediaReport.echoCancellation)
                StatusRow(stringResource(R.string.diagnostics_noise_suppression), mediaReport.noiseSuppression)
                StatusRow(stringResource(R.string.diagnostics_auto_gain_control), mediaReport.autoGainControl)
                LabeledTextRow(
                    label = stringResource(R.string.diagnostics_audio_sample_rate),
                    value = mediaReport.audioSampleRate
                )
                LabeledTextRow(
                    label = stringResource(R.string.diagnostics_audio_frames_per_buffer),
                    value = mediaReport.audioFramesPerBuffer
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        mediaReport = buildMediaReport(context)
                        permissions = readPermissionStatus(context, requiredPermissions)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.diagnostics_refresh_media))
                }
            }

            SectionCard(title = stringResource(R.string.diagnostics_connectivity_title)) {
                val pending = CheckResult(CheckState.Idle, stringResource(R.string.diagnostics_not_run))
                val report = connectivityReport
                StatusRow(
                    stringResource(R.string.diagnostics_room_id_endpoint),
                    when {
                        connectivityInProgress -> CheckResult(CheckState.Running, stringResource(R.string.diagnostics_running))
                        report == null -> pending
                        else -> report.roomIdEndpoint
                    }
                )
                StatusRow(
                    stringResource(R.string.diagnostics_websocket_connection),
                    when {
                        connectivityInProgress -> CheckResult(CheckState.Running, stringResource(R.string.diagnostics_running))
                        report == null -> pending
                        else -> report.webSocket
                    }
                )
                StatusRow(
                    stringResource(R.string.diagnostics_diagnostic_token),
                    when {
                        connectivityInProgress -> CheckResult(CheckState.Running, stringResource(R.string.diagnostics_running))
                        report == null -> pending
                        else -> report.diagnosticToken
                    }
                )
                StatusRow(
                    stringResource(R.string.diagnostics_turn_credentials),
                    when {
                        connectivityInProgress -> CheckResult(CheckState.Running, stringResource(R.string.diagnostics_running))
                        report == null -> pending
                        else -> report.turnCredentials
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        connectivityInProgress = true
                        scope.launch {
                            connectivityReport = runConnectivityChecks(host)
                            connectivityInProgress = false
                        }
                    },
                    enabled = !connectivityInProgress && !iceInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (connectivityInProgress) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.diagnostics_run_connectivity))
                }
            }

            SectionCard(title = stringResource(R.string.diagnostics_ice_title)) {
                val pending = CheckResult(CheckState.Idle, stringResource(R.string.diagnostics_not_run))
                val report = iceReport
                StatusRow(
                    stringResource(R.string.diagnostics_stun_status),
                    when {
                        iceInProgress -> CheckResult(CheckState.Running, stringResource(R.string.diagnostics_running))
                        report == null -> pending
                        else -> report.stun
                    }
                )
                StatusRow(
                    stringResource(R.string.diagnostics_turn_status),
                    when {
                        iceInProgress -> CheckResult(CheckState.Running, stringResource(R.string.diagnostics_running))
                        report == null -> pending
                        else -> report.turn
                    }
                )
                LabeledTextRow(
                    label = stringResource(R.string.diagnostics_ice_servers),
                    value = when {
                        iceInProgress -> iceLiveServersSummary
                            ?: report?.iceServersSummary
                            ?: stringResource(R.string.diagnostics_not_run)
                        else -> report?.iceServersSummary ?: stringResource(R.string.diagnostics_not_run)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                DiagnosticsLogBox(
                    lines = when {
                        iceInProgress && iceLiveLogs.isEmpty() -> listOf(stringResource(R.string.diagnostics_running))
                        iceInProgress -> iceLiveLogs
                        report == null -> listOf(stringResource(R.string.diagnostics_ice_log_placeholder))
                        report.logs.isEmpty() -> listOf(stringResource(R.string.diagnostics_not_run))
                        else -> report.logs
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            iceTurnsOnlyMode = false
                            iceInProgress = true
                            iceLiveServersSummary = null
                            iceLiveLogs.clear()
                            scope.launch {
                                iceReport = runIceCheck(
                                    context = context,
                                    host = host,
                                    turnsOnly = false,
                                    onIceServersSummary = { summary ->
                                        scope.launch {
                                            iceLiveServersSummary = summary
                                        }
                                    },
                                    onLogLine = { line ->
                                        scope.launch {
                                            iceLiveLogs.add(line)
                                        }
                                    }
                                )
                                iceLiveLogs.clear()
                                iceLiveLogs.addAll(iceReport?.logs.orEmpty())
                                iceLiveServersSummary = iceReport?.iceServersSummary
                                iceInProgress = false
                            }
                        },
                        enabled = !iceInProgress && !connectivityInProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.diagnostics_run_ice_full))
                    }
                    OutlinedButton(
                        onClick = {
                            iceTurnsOnlyMode = true
                            iceInProgress = true
                            iceLiveServersSummary = null
                            iceLiveLogs.clear()
                            scope.launch {
                                iceReport = runIceCheck(
                                    context = context,
                                    host = host,
                                    turnsOnly = true,
                                    onIceServersSummary = { summary ->
                                        scope.launch {
                                            iceLiveServersSummary = summary
                                        }
                                    },
                                    onLogLine = { line ->
                                        scope.launch {
                                            iceLiveLogs.add(line)
                                        }
                                    }
                                )
                                iceLiveLogs.clear()
                                iceLiveLogs.addAll(iceReport?.logs.orEmpty())
                                iceLiveServersSummary = iceReport?.iceServersSummary
                                iceInProgress = false
                            }
                        },
                        enabled = !iceInProgress && !connectivityInProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.diagnostics_run_ice_turns))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsLogBox(lines: List<String>) {
    val scrollState = rememberScrollState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, result: CheckResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            modifier = Modifier.weight(1f, fill = false),
            shape = RoundedCornerShape(999.dp),
            color = statusColor(result.state).copy(alpha = 0.18f)
        ) {
            Text(
                text = result.detail,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                color = statusColor(result.state),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LabeledTextRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun statusColor(state: CheckState): Color {
    return when (state) {
        CheckState.Pass -> Color(0xFF22C55E)
        CheckState.Warn -> Color(0xFFF59E0B)
        CheckState.Fail -> Color(0xFFEF4444)
        CheckState.Running -> MaterialTheme.colorScheme.secondary
        CheckState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun readPermissionStatus(
    context: Context,
    permissions: List<String>
): List<Pair<String, CheckResult>> {
    return permissions.map { permission ->
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        permissionLabel(context, permission) to if (granted) {
            CheckResult(CheckState.Pass, context.getString(R.string.diagnostics_status_granted))
        } else {
            CheckResult(CheckState.Fail, context.getString(R.string.diagnostics_status_not_granted))
        }
    }
}

private fun permissionLabel(context: Context, permission: String): String {
    return when (permission) {
        Manifest.permission.CAMERA -> context.getString(R.string.diagnostics_permission_camera)
        Manifest.permission.RECORD_AUDIO -> context.getString(R.string.diagnostics_permission_microphone)
        Manifest.permission.POST_NOTIFICATIONS -> context.getString(R.string.diagnostics_permission_notifications)
        else -> permission
    }
}

private fun buildMediaReport(context: Context): MediaReport {
    val packageManager = context.packageManager
    val hasCameraHardware = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    val hasMicrophone = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    val enumerator = Camera2Enumerator(context)
    val names = enumerator.deviceNames.toList()
    val front = names.firstOrNull { enumerator.isFrontFacing(it) }
    val back = names.firstOrNull { enumerator.isBackFacing(it) }
    fun cameraSummary(name: String): String {
        val formats = enumerator.getSupportedFormats(name).orEmpty()
        val best = formats.maxByOrNull { it.width * it.height }
        val maxFps = formats.maxOfOrNull { format -> normalizeFps(format.framerate.max) } ?: 0
        val size = if (best == null) "n/a" else "${best.width}x${best.height}"
        return "$name ($size @${maxFps}fps)"
    }

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "Unknown"
    val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "Unknown"

    return MediaReport(
        cameraHardware = if (hasCameraHardware) {
            CheckResult(CheckState.Pass, "Available")
        } else {
            CheckResult(CheckState.Fail, "Not available")
        },
        frontCamera = if (front != null) {
            CheckResult(CheckState.Pass, cameraSummary(front))
        } else {
            CheckResult(CheckState.Fail, "Missing")
        },
        backCamera = if (back != null) {
            CheckResult(CheckState.Pass, cameraSummary(back))
        } else {
            CheckResult(CheckState.Warn, "Missing")
        },
        compositeModePrerequisite = if (front != null && back != null) {
            CheckResult(CheckState.Pass, "Front + back detected")
        } else {
            CheckResult(CheckState.Warn, "Requires both front and back")
        },
        microphoneFeature = if (hasMicrophone) {
            CheckResult(CheckState.Pass, "Available")
        } else {
            CheckResult(CheckState.Fail, "Not available")
        },
        echoCancellation = effectAvailability(AcousticEchoCanceler.isAvailable()),
        noiseSuppression = effectAvailability(NoiseSuppressor.isAvailable()),
        autoGainControl = effectAvailability(AutomaticGainControl.isAvailable()),
        audioSampleRate = sampleRate,
        audioFramesPerBuffer = framesPerBuffer
    )
}

private fun effectAvailability(isAvailable: Boolean): CheckResult {
    return if (isAvailable) {
        CheckResult(CheckState.Pass, "Available")
    } else {
        CheckResult(CheckState.Warn, "Unavailable")
    }
}

private fun normalizeFps(rawFps: Int): Int {
    return if (rawFps > 1000) rawFps / 1000 else rawFps
}

private suspend fun runConnectivityChecks(host: String): ConnectivityReport = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .callTimeout(6, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
    val apiClient = ApiClient(client)

    val roomIdResult = checkRoomIdEndpoint(client, host)
    val wsResult = checkWebSocket(client, host)
    val diagnosticTokenResult = apiClient.awaitDiagnosticToken(host)
    val tokenResult = diagnosticTokenResult.toNetworkCheck("Token")
    val turnResult = diagnosticTokenResult.fold(
        onSuccess = { token ->
            apiClient.awaitTurnCredentials(host, token).toNetworkCheck("TURN")
        },
        onFailure = { error ->
            CheckResult(CheckState.Fail, "Token failed: ${error.message ?: "error"}")
        }
    )

    ConnectivityReport(
        roomIdEndpoint = roomIdResult,
        webSocket = wsResult,
        diagnosticToken = tokenResult,
        turnCredentials = turnResult
    )
}

private fun checkRoomIdEndpoint(client: OkHttpClient, host: String): CheckResult {
    val url = buildHttpsUrl(host, "/api/room-id")
        ?: return CheckResult(CheckState.Fail, "Invalid host")
    val start = SystemClock.elapsedRealtime()
    return runCatching {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val elapsed = SystemClock.elapsedRealtime() - start
            if (!response.isSuccessful) {
                CheckResult(CheckState.Fail, "HTTP ${response.code}")
            } else {
                CheckResult(CheckState.Pass, "${elapsed}ms")
            }
        }
    }.getOrElse { error ->
        CheckResult(CheckState.Fail, error.message ?: "Connection failed")
    }
}

private suspend fun checkWebSocket(client: OkHttpClient, host: String): CheckResult {
    val url = buildWssUrl(host) ?: return CheckResult(CheckState.Fail, "Invalid host")
    return suspendCancellableCoroutine { continuation ->
        val start = SystemClock.elapsedRealtime()
        var closed = false
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed) return
                closed = true
                webSocket.close(1000, "diagnostics")
                val elapsed = SystemClock.elapsedRealtime() - start
                continuation.resume(CheckResult(CheckState.Pass, "${elapsed}ms"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed) return
                closed = true
                continuation.resume(CheckResult(CheckState.Fail, t.message ?: "WebSocket failed"))
            }
        })
        continuation.invokeOnCancellation {
            ws.cancel()
        }
    }
}

private suspend fun runIceCheck(
    context: Context,
    host: String,
    turnsOnly: Boolean,
    onIceServersSummary: (String) -> Unit = {},
    onLogLine: (String) -> Unit = {}
): IceReport = withContext(Dispatchers.IO) {
    val okHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    val apiClient = ApiClient(okHttpClient)
    val logs = Collections.synchronizedList(mutableListOf<String>())

    fun log(msg: String) {
        val line = "[${System.currentTimeMillis() / 1000}] $msg"
        logs.add(line)
        onLogLine(line)
    }
    log("Starting ICE test (turnsOnly=$turnsOnly)...")
    log("Requesting diagnostic token...")

    val token = apiClient.awaitDiagnosticToken(host).getOrElse { error ->
        val message = error.message ?: "Diagnostic token failed"
        onLogLine("Token error: $message")
        return@withContext IceReport(
            turnsOnly = turnsOnly,
            stun = CheckResult(CheckState.Fail, message),
            turn = CheckResult(CheckState.Fail, message),
            iceServersSummary = "n/a",
            logs = listOf("Token error: $message")
        )
    }
    log("Diagnostic token received.")

    val creds = apiClient.awaitTurnCredentials(host, token).getOrElse { error ->
        val message = error.message ?: "TURN credentials failed"
        onLogLine("TURN credentials error: $message")
        return@withContext IceReport(
            turnsOnly = turnsOnly,
            stun = CheckResult(CheckState.Fail, message),
            turn = CheckResult(CheckState.Fail, message),
            iceServersSummary = "n/a",
            logs = listOf("TURN credentials error: $message")
        )
    }
    log(
        "TURN credentials: ttl=${creds.ttl}s, usernameTs=${creds.username.substringBefore(':', "n/a")}, uris=${creds.uris.size}"
    )
    creds.uris.forEachIndexed { index, uri ->
        log("ICE URI[$index]: ${describeIceServerUri(uri)}")
    }

    val filteredUris = if (turnsOnly) {
        creds.uris.filter { it.startsWith("turns:", ignoreCase = true) }
    } else {
        creds.uris
    }
    if (filteredUris.isEmpty()) {
        onIceServersSummary("n/a")
        return@withContext IceReport(
            turnsOnly = turnsOnly,
            stun = if (turnsOnly) {
                CheckResult(CheckState.Warn, "Skipped (TURNS only)")
            } else {
                CheckResult(CheckState.Fail, "No ICE servers")
            },
            turn = CheckResult(CheckState.Fail, "No compatible ICE servers"),
            iceServersSummary = "n/a",
            logs = listOf("No compatible ICE servers for this mode.")
        )
    }

    val servers = filteredUris.map { uri ->
        val builder = PeerConnection.IceServer.builder(uri)
        if (!uri.startsWith("stun:", ignoreCase = true)) {
            builder.setUsername(creds.username)
            builder.setPassword(creds.password)
        }
        builder.createIceServer()
    }

    if (turnsOnly) {
        log("Filtered for TURNS only: ${filteredUris.size}/${creds.uris.size} servers")
    }
    val iceServersSummary = filteredUris.joinToString()
    onIceServersSummary(iceServersSummary)
    log("ICE servers: $iceServersSummary")
    val gather = runIceGathering(context, servers, turnsOnly, ::log)
    IceReport(
        turnsOnly = turnsOnly,
        stun = gather.first,
        turn = gather.second,
        iceServersSummary = iceServersSummary,
        logs = logs.toList()
    )
}

private fun runIceGathering(
    context: Context,
    servers: List<PeerConnection.IceServer>,
    turnsOnly: Boolean,
    log: (String) -> Unit
): Pair<CheckResult, CheckResult> {
    val appContext = context.applicationContext
    enableVerboseWebRtcLoggingForDiagnostics()
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
    )
    val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    val stunFound = AtomicBoolean(false)
    val turnFound = AtomicBoolean(false)
    val candidateSeq = AtomicInteger(0)
    val hostCount = AtomicInteger(0)
    val srflxCount = AtomicInteger(0)
    val relayCount = AtomicInteger(0)
    val prflxCount = AtomicInteger(0)
    val otherCount = AtomicInteger(0)
    val candidateErrorCount = AtomicInteger(0)
    val gatherDone = CountDownLatch(1)
    val failed = AtomicBoolean(false)
    var failureReason = "Unknown ICE error"

    val config = PeerConnection.RTCConfiguration(servers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }
    log(
        "RTC config: policy=${config.iceTransportsType}, semantics=${config.sdpSemantics}, servers=${servers.size}"
    )

    val peerConnection = factory.createPeerConnection(config, object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val type = extractCandidateType(candidate.sdp)
            when (type) {
                "host" -> hostCount.incrementAndGet()
                "srflx" -> {
                    srflxCount.incrementAndGet()
                    stunFound.set(true)
                }
                "relay" -> {
                    relayCount.incrementAndGet()
                    turnFound.set(true)
                }
                "prflx" -> prflxCount.incrementAndGet()
                else -> otherCount.incrementAndGet()
            }
            val seq = candidateSeq.incrementAndGet()
            log(formatIceCandidateLog(candidate, seq))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            log("pc state: $newState")
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) {
            log("signaling: $newState")
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            log("ice state: $newState")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
        }

        override fun onIceCandidateError(event: IceCandidateErrorEvent) {
            val count = candidateErrorCount.incrementAndGet()
            val category = classifyIceError(event.errorCode)
            log(
                "ICE candidate error#$count: code=${event.errorCode}($category), text=${event.errorText}, url=${event.url}, address=${event.address}, port=${event.port}"
            )
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            val local = formatCandidateBrief(event.local)
            val remote = formatCandidateBrief(event.remote)
            log(
                "Selected pair: local={$local} remote={$remote} reason=${event.reason} lastDataMs=${event.lastDataReceivedMs} estDisconnectedMs=${event.estimatedDisconnectedTimeMs}"
            )
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            log("ICE gathering state: $newState")
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                gatherDone.countDown()
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
        }

        override fun onAddStream(stream: org.webrtc.MediaStream) {
        }

        override fun onRemoveStream(stream: org.webrtc.MediaStream) {
        }

        override fun onDataChannel(dc: DataChannel) {
        }

        override fun onRenegotiationNeeded() {
        }

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
        }
    })

    if (peerConnection == null) {
        factory.dispose()
        return Pair(
            CheckResult(CheckState.Fail, "PeerConnection creation failed"),
            CheckResult(CheckState.Fail, "PeerConnection creation failed")
        )
    }

    peerConnection.createDataChannel("diagnostics", DataChannel.Init())
    peerConnection.createOffer(object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {
            if (desc == null) {
                failed.set(true)
                failureReason = "Empty offer"
                gatherDone.countDown()
                return
            }
            peerConnection.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                }

                override fun onSetSuccess() {
                    log("Local description set.")
                }

                override fun onCreateFailure(error: String?) {
                }

                override fun onSetFailure(error: String?) {
                    failed.set(true)
                    failureReason = error ?: "setLocalDescription failed"
                    gatherDone.countDown()
                }
            }, desc)
        }

        override fun onSetSuccess() {
        }

        override fun onCreateFailure(error: String?) {
            failed.set(true)
            failureReason = error ?: "createOffer failed"
            gatherDone.countDown()
        }

        override fun onSetFailure(error: String?) {
        }
    }, MediaConstraints())

    val completed = gatherDone.await(15, TimeUnit.SECONDS)
    if (!completed) {
        log("ICE gathering timed out after 15s.")
    } else {
        log("ICE gathering reached COMPLETE.")
    }
    if (failed.get()) {
        log("ICE setup failed: $failureReason")
    }
    log(
        "ICE candidate summary: total=${candidateSeq.get()}, host=${hostCount.get()}, srflx=${srflxCount.get()}, relay=${relayCount.get()}, prflx=${prflxCount.get()}, other=${otherCount.get()}, errors=${candidateErrorCount.get()}"
    )
    if (turnsOnly && !turnFound.get()) {
        if (candidateErrorCount.get() > 0) {
            log("TURNS-only result: relay missing and candidate errors were reported (see error lines above).")
        } else {
            log("TURNS-only result: relay missing with no candidate errors reported by libwebrtc.")
        }
    }

    runCatching { peerConnection.close() }
    runCatching { peerConnection.dispose() }
    factory.dispose()

    val stunResult = when {
        turnsOnly -> CheckResult(CheckState.Warn, "Skipped (TURNS only)")
        failed.get() -> CheckResult(CheckState.Fail, failureReason)
        stunFound.get() -> CheckResult(CheckState.Pass, "Detected")
        else -> CheckResult(CheckState.Fail, "No server-reflexive candidate")
    }
    val turnResult = when {
        failed.get() -> CheckResult(CheckState.Fail, failureReason)
        turnFound.get() -> CheckResult(CheckState.Pass, "Detected")
        else -> CheckResult(CheckState.Fail, "No relay candidate")
    }
    return Pair(stunResult, turnResult)
}

private val diagnosticsWebRtcLoggingEnabled = AtomicBoolean(false)

private fun enableVerboseWebRtcLoggingForDiagnostics() {
    if (!BuildConfig.DEBUG) return
    if (!diagnosticsWebRtcLoggingEnabled.compareAndSet(false, true)) return
    runCatching {
        Logging.enableLogThreads()
        Logging.enableLogTimeStamps()
        Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE)
        Log.i("Diagnostics", "Verbose native WebRTC logging enabled")
    }.onFailure { error ->
        Log.w("Diagnostics", "Failed to enable WebRTC verbose logging", error)
    }
}

private fun extractCandidateType(candidateSdp: String): String? {
    val marker = " typ "
    val index = candidateSdp.indexOf(marker)
    if (index < 0) return null
    val start = index + marker.length
    if (start >= candidateSdp.length) return null
    val end = candidateSdp.indexOf(' ', start).takeIf { it >= 0 } ?: candidateSdp.length
    return candidateSdp.substring(start, end).trim().ifBlank { null }
}

private fun formatIceCandidateLog(candidate: IceCandidate): String {
    return formatIceCandidateLog(candidate, null)
}

private fun formatCandidateBrief(candidate: IceCandidate): String {
    val raw = candidate.sdp
    val parts = raw.split(' ')
    val ip = parts.getOrNull(4) ?: "unknown"
    val port = parts.getOrNull(5) ?: "unknown"
    val type = extractCandidateType(raw) ?: "unknown"
    val transport = parts.getOrNull(2)?.lowercase() ?: "unknown"
    return "${candidate.sdpMid}:${candidate.sdpMLineIndex} type=$type proto=$transport addr=$ip:$port"
}

private fun formatIceCandidateLog(candidate: IceCandidate, sequence: Int?): String {
    val raw = candidate.sdp
    val parts = raw.split(' ')
    val ip = parts.getOrNull(4) ?: "unknown"
    val port = parts.getOrNull(5) ?: "unknown"
    val transport = parts.getOrNull(2)?.lowercase() ?: "unknown"
    val type = extractCandidateType(raw) ?: "unknown"
    val base = if (sequence != null) {
        "Candidate#$sequence: $type ($transport) -> $ip:$port"
    } else {
        "Candidate: $type ($transport) -> $ip:$port"
    }
    return if (type == "relay") {
        "$base [$transport] | raw=$raw"
    } else {
        "$base | raw=$raw"
    }
}

private fun classifyIceError(errorCode: Int): String {
    return when (errorCode) {
        401 -> "UNAUTHORIZED"
        403 -> "FORBIDDEN"
        437 -> "ALLOCATION_MISMATCH"
        438 -> "STALE_NONCE"
        486 -> "ALLOCATION_QUOTA_REACHED"
        500 -> "SERVER_ERROR"
        701 -> "SERVER_UNREACHABLE"
        else -> "UNKNOWN"
    }
}

private fun describeIceServerUri(uri: String): String {
    val schemeEnd = uri.indexOf(':')
    if (schemeEnd <= 0) return "raw=$uri"
    val scheme = uri.substring(0, schemeEnd).lowercase()
    val rest = uri.substring(schemeEnd + 1)
    val endpoint = rest.substringBefore('?')
    val query = rest.substringAfter('?', "")
    val transport = query.split('&')
        .firstOrNull { it.startsWith("transport=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.lowercase() ?: "default"
    return "scheme=$scheme, endpoint=$endpoint, transport=$transport, raw=$uri"
}

private fun buildHttpsUrl(hostInput: String, path: String): String? {
    val raw = hostInput.trim()
    val withScheme =
        if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    val base = withScheme.toHttpUrlOrNull() ?: return null
    return base.newBuilder()
        .scheme("https")
        .encodedPath(path)
        .build()
        .toString()
}

private fun buildWssUrl(hostInput: String): String? {
    val host = hostInput.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    if (host.isBlank()) return null
    return "wss://$host/ws"
}

private suspend fun ApiClient.awaitDiagnosticToken(host: String): Result<String> {
    return suspendCancellableCoroutine { continuation ->
        fetchDiagnosticToken(host) { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }
}

private suspend fun ApiClient.awaitTurnCredentials(host: String, token: String): Result<TurnCredentials> {
    return suspendCancellableCoroutine { continuation ->
        fetchTurnCredentials(host, token) { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }
}

private fun <T> Result<T>.toNetworkCheck(label: String): CheckResult {
    return fold(
        onSuccess = { CheckResult(CheckState.Pass, "OK") },
        onFailure = { error ->
            val message = error.message ?: "$label failed"
            Log.w("Diagnostics", "$label check failed", error)
            CheckResult(CheckState.Fail, message)
        }
    )
}

private fun buildDiagnosticsReport(
    generatedAtIso: String,
    host: String,
    permissions: List<Pair<String, CheckResult>>,
    mediaReport: MediaReport,
    connectivityReport: ConnectivityReport?,
    iceReport: IceReport?,
    iceTurnsOnlyMode: Boolean,
    connectivityInProgress: Boolean,
    iceInProgress: Boolean,
    notRunLabel: String,
    runningLabel: String
): String {
    val pending = CheckResult(CheckState.Idle, notRunLabel)
    val running = CheckResult(CheckState.Running, runningLabel)
    val resolvedConnectivity = when {
        connectivityInProgress -> ConnectivityReport(
            roomIdEndpoint = running,
            webSocket = running,
            diagnosticToken = running,
            turnCredentials = running
        )
        connectivityReport != null -> connectivityReport
        else -> ConnectivityReport(
            roomIdEndpoint = pending,
            webSocket = pending,
            diagnosticToken = pending,
            turnCredentials = pending
        )
    }
    val resolvedIce = when {
        iceInProgress -> IceReport(
            turnsOnly = iceTurnsOnlyMode,
            stun = running,
            turn = running,
            iceServersSummary = notRunLabel,
            logs = emptyList()
        )
        iceReport != null -> iceReport
        else -> IceReport(
            turnsOnly = iceTurnsOnlyMode,
            stun = pending,
            turn = pending,
            iceServersSummary = notRunLabel,
            logs = emptyList()
        )
    }

    return buildString {
        appendLine("SERENADA DIAGNOSTICS DATA")
        appendLine("==========================")
        appendLine("Generated: $generatedAtIso")
        appendLine("Host: ${host.ifBlank { "-" }}")
        appendLine("Android: sdk=${Build.VERSION.SDK_INT}, device=${Build.DEVICE}, model=${Build.MODEL}")
        appendLine()

        appendLine("## Required App Permissions")
        permissions.forEach { (label, result) ->
            appendLine("$label: ${result.toExportLine()}")
        }
        appendLine()

        appendLine("## Audio And Video Capabilities")
        appendLine("Camera hardware: ${mediaReport.cameraHardware.toExportLine()}")
        appendLine("Front camera: ${mediaReport.frontCamera.toExportLine()}")
        appendLine("Back camera: ${mediaReport.backCamera.toExportLine()}")
        appendLine("Composite mode prerequisite: ${mediaReport.compositeModePrerequisite.toExportLine()}")
        appendLine("Microphone feature: ${mediaReport.microphoneFeature.toExportLine()}")
        appendLine("Echo cancellation: ${mediaReport.echoCancellation.toExportLine()}")
        appendLine("Noise suppression: ${mediaReport.noiseSuppression.toExportLine()}")
        appendLine("Auto gain control: ${mediaReport.autoGainControl.toExportLine()}")
        appendLine("Audio sample rate: ${mediaReport.audioSampleRate}")
        appendLine("Audio frames per buffer: ${mediaReport.audioFramesPerBuffer}")
        appendLine()

        appendLine("## Network Connectivity")
        appendLine("GET /api/room-id: ${resolvedConnectivity.roomIdEndpoint.toExportLine()}")
        appendLine("WSS /ws: ${resolvedConnectivity.webSocket.toExportLine()}")
        appendLine("POST /api/diagnostic-token: ${resolvedConnectivity.diagnosticToken.toExportLine()}")
        appendLine("GET /api/turn-credentials: ${resolvedConnectivity.turnCredentials.toExportLine()}")
        appendLine()

        appendLine("## ICE Connectivity (STUN/TURN)")
        appendLine("Test mode: ${if (resolvedIce.turnsOnly) "TURNS only" else "Full"}")
        appendLine("STUN status: ${resolvedIce.stun.toExportLine()}")
        appendLine("TURN status: ${resolvedIce.turn.toExportLine()}")
        appendLine("ICE servers: ${resolvedIce.iceServersSummary}")
        appendLine("ICE log:")
        if (resolvedIce.logs.isEmpty()) {
            appendLine(notRunLabel)
        } else {
            resolvedIce.logs.forEach { appendLine(it) }
        }
    }
}

private fun CheckResult.toExportLine(): String {
    val stateLabel = when (state) {
        CheckState.Pass -> "PASS"
        CheckState.Warn -> "WARN"
        CheckState.Fail -> "FAIL"
        CheckState.Running -> "RUNNING"
        CheckState.Idle -> "IDLE"
    }
    return "$stateLabel - $detail"
}

private fun copyAndShareDiagnostics(
    context: Context,
    chooserTitle: String,
    report: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Serenada diagnostics", report))

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Serenada diagnostics")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    val chooser = Intent.createChooser(intent, chooserTitle)
    if (context !is Activity) {
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(chooser)
    }.onFailure { error ->
        Log.w("Diagnostics", "Failed to open share sheet", error)
    }
}
