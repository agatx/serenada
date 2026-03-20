package app.serenada.android.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.size
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
import app.serenada.android.R
import app.serenada.core.diagnostics.DiagnosticsCheckResult as CheckResult
import app.serenada.core.diagnostics.DiagnosticsCheckState as CheckState
import app.serenada.core.diagnostics.DiagnosticsIceReport as IceReport
import app.serenada.core.diagnostics.DiagnosticsMediaReport as MediaReport
import app.serenada.core.diagnostics.buildDiagnosticsMediaReport
import app.serenada.core.diagnostics.runDiagnosticsIceCheck
import app.serenada.core.network.CoreApiClient
import app.serenada.core.network.TurnCredentials
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private data class ConnectivityReport(
    val roomIdEndpoint: CheckResult,
    val webSocket: CheckResult,
    val sse: CheckResult,
    val diagnosticToken: CheckResult,
    val turnCredentials: CheckResult
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
                val allPermissionsGranted = permissions.all { (_, result) ->
                    result.state == CheckState.Pass
                }
                permissions.forEach { (label, result) ->
                    StatusRow(label = label, result = result)
                }
                if (!allPermissionsGranted) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.diagnostics_request_permissions))
                    }
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
                    stringResource(R.string.diagnostics_sse_connection),
                    when {
                        connectivityInProgress -> CheckResult(CheckState.Running, stringResource(R.string.diagnostics_running))
                        report == null -> pending
                        else -> report.sse
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
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.diagnostics_run_connectivity))
                        if (connectivityInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
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
    return buildDiagnosticsMediaReport(context)
}

private suspend fun runConnectivityChecks(host: String): ConnectivityReport = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .callTimeout(6, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
    val coreApiClient = CoreApiClient(client)

    val roomIdResult = checkRoomIdEndpoint(client, host)
    val wsResult = checkWebSocket(client, host)
    val sseResult = checkSseEndpoint(client, host)
    val diagnosticTokenResult = coreApiClient.awaitDiagnosticToken(host)
    val tokenResult = diagnosticTokenResult.toNetworkCheck("Token")
    val turnResult = diagnosticTokenResult.fold(
        onSuccess = { token ->
            coreApiClient.awaitTurnCredentials(host, token).toNetworkCheck("TURN")
        },
        onFailure = { error ->
            CheckResult(CheckState.Fail, "Token failed: ${error.message ?: "error"}")
        }
    )

    ConnectivityReport(
        roomIdEndpoint = roomIdResult,
        webSocket = wsResult,
        sse = sseResult,
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

private fun checkSseEndpoint(client: OkHttpClient, host: String): CheckResult {
    val sid = "S-diag-${UUID.randomUUID().toString().replace("-", "").take(16)}"
    val url = buildSseUrl(host, sid) ?: return CheckResult(CheckState.Fail, "Invalid host")
    val start = SystemClock.elapsedRealtime()
    val getRequest = Request.Builder()
        .url(url)
        .header("Accept", "text/event-stream")
        .get()
        .build()

    return runCatching {
        client.newCall(getRequest).execute().use { streamResponse ->
            if (!streamResponse.isSuccessful) {
                return@use CheckResult(CheckState.Fail, "GET HTTP ${streamResponse.code}")
            }
            val contentType = streamResponse.header("Content-Type").orEmpty().lowercase()
            if (!contentType.contains("text/event-stream")) {
                return@use CheckResult(CheckState.Fail, "Unexpected content-type")
            }

            val postBody = """{"v":1,"type":"ping","payload":{"ts":${System.currentTimeMillis()}}}"""
                .toRequestBody(JSON_MEDIA_TYPE)
            val postRequest = Request.Builder()
                .url(url)
                .post(postBody)
                .header("Content-Type", "application/json")
                .build()
            client.newCall(postRequest).execute().use { postResponse ->
                val elapsed = SystemClock.elapsedRealtime() - start
                if (!postResponse.isSuccessful) {
                    CheckResult(CheckState.Fail, "POST HTTP ${postResponse.code}")
                } else {
                    CheckResult(CheckState.Pass, "${elapsed}ms")
                }
            }
        }
    }.getOrElse { error ->
        CheckResult(CheckState.Fail, error.message ?: "SSE failed")
    }
}

private suspend fun runIceCheck(
    context: Context,
    host: String,
    turnsOnly: Boolean,
    onIceServersSummary: (String) -> Unit = {},
    onLogLine: (String) -> Unit = {}
): IceReport {
    return runDiagnosticsIceCheck(
        context = context,
        host = host,
        turnsOnly = turnsOnly,
        onIceServersSummary = onIceServersSummary,
        onLogLine = onLogLine,
    )
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

private fun buildSseUrl(hostInput: String, sid: String): String? {
    if (sid.isBlank()) return null
    val base = buildHttpsUrl(hostInput, "/sse")?.toHttpUrlOrNull() ?: return null
    return base.newBuilder()
        .addQueryParameter("sid", sid)
        .build()
        .toString()
}

private suspend fun CoreApiClient.awaitDiagnosticToken(host: String): Result<String> {
    return suspendCancellableCoroutine { continuation ->
        fetchDiagnosticToken(host) { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }
}

private suspend fun CoreApiClient.awaitTurnCredentials(host: String, token: String): Result<TurnCredentials> {
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
            sse = running,
            diagnosticToken = running,
            turnCredentials = running
        )
        connectivityReport != null -> connectivityReport
        else -> ConnectivityReport(
            roomIdEndpoint = pending,
            webSocket = pending,
            sse = pending,
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
        appendLine("HTTPS /sse (GET+POST): ${resolvedConnectivity.sse.toExportLine()}")
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

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
