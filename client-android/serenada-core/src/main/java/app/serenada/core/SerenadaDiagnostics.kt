package app.serenada.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import app.serenada.core.call.SignalingClient
import app.serenada.core.call.SignalingMessage
import app.serenada.core.diagnostics.runDiagnosticsIceCheck
import app.serenada.core.network.CoreApiClient
import app.serenada.core.network.buildHttpsUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.Camera2Enumerator
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Pre-flight diagnostics utility. Checks device capabilities and server connectivity
 * without prompting for permissions.
 */
class SerenadaDiagnostics(
    private val config: SerenadaConfig,
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = CoreApiClient(okHttpClient)
    private val handler = Handler(Looper.getMainLooper())

    init {
        // Eagerly warm up the PeerConnectionFactory on a background thread so
        // its network thread is ready by the time the user runs an ICE probe.
        Thread { app.serenada.core.diagnostics.warmUpPeerConnectionFactory(appContext) }.start()
    }

    suspend fun runAll(): DiagnosticsReport = suspendCancellableCoroutine { continuation ->
        runAll { report ->
            if (continuation.isActive) {
                continuation.resume(report)
            }
        }
    }

    fun runAll(completion: (DiagnosticsReport) -> Unit) {
        var cameraResult: DiagnosticCheckResult? = null
        var micResult: DiagnosticCheckResult? = null
        var speakerResult: DiagnosticCheckResult? = null
        var networkResult: DiagnosticCheckResult? = null
        var signalingResult: SignalingCheckResult? = null
        var turnResult: TurnCheckResult? = null
        var devices: List<DeviceInfo> = emptyList()

        val remaining = AtomicInteger(7)

        fun tryComplete() {
            if (remaining.decrementAndGet() <= 0) {
                handler.post {
                    completion(
                        DiagnosticsReport(
                            camera = cameraResult ?: DiagnosticCheckResult.SKIPPED,
                            microphone = micResult ?: DiagnosticCheckResult.SKIPPED,
                            speaker = speakerResult ?: DiagnosticCheckResult.SKIPPED,
                            network = networkResult ?: DiagnosticCheckResult.SKIPPED,
                            signaling = signalingResult ?: SignalingCheckResult.Skipped("not checked"),
                            turn = turnResult ?: TurnCheckResult.Skipped("not checked"),
                            devices = devices,
                        )
                    )
                }
            }
        }

        checkCamera { cameraResult = it; tryComplete() }
        checkMicrophone { micResult = it; tryComplete() }
        checkSpeaker { speakerResult = it; tryComplete() }
        checkNetwork { networkResult = it; tryComplete() }
        checkSignaling { signalingResult = it; tryComplete() }
        checkTurn { turnResult = it; tryComplete() }
        enumerateDevices { devices = it; tryComplete() }
    }

    suspend fun runConnectivityChecks(host: String = config.serverHost): ConnectivityReport = withContext(Dispatchers.IO) {
        val normalizedHost = host.trim().ifBlank { config.serverHost }
        // Fetch the diagnostic token once and reuse it for the TURN credentials check.
        var tokenForTurn: String? = null
        val roomApi = runTimedCheck { awaitCreateRoomId(normalizedHost) }
        val webSocket = runTimedCheck { testWebSocket(normalizedHost) }
        val sse = runTimedCheck { testSse(normalizedHost) }
        val diagnosticToken = runTimedCheck { tokenForTurn = awaitDiagnosticToken(normalizedHost) }
        val turnCredentials = runTimedCheck {
            val token = tokenForTurn ?: awaitDiagnosticToken(normalizedHost)
            awaitTurnCredentials(normalizedHost, token)
        }
        ConnectivityReport(
            roomApi = roomApi,
            webSocket = webSocket,
            sse = sse,
            diagnosticToken = diagnosticToken,
            turnCredentials = turnCredentials,
        )
    }

    suspend fun runIceProbe(
        turnsOnly: Boolean,
        host: String = config.serverHost,
        onCandidateLog: ((String) -> Unit)? = null,
    ): IceProbeReport {
        val report = runDiagnosticsIceCheck(
            context = appContext,
            host = host.trim().ifBlank { config.serverHost },
            turnsOnly = turnsOnly,
            onLogLine = { line -> onCandidateLog?.invoke(line) },
        )
        return IceProbeReport(
            stunPassed = report.stun.state == app.serenada.core.diagnostics.DiagnosticsCheckState.Pass,
            turnPassed = report.turn.state == app.serenada.core.diagnostics.DiagnosticsCheckState.Pass,
            logs = report.logs,
            iceServersSummary = report.iceServersSummary,
        )
    }

    fun checkCamera(completion: (DiagnosticCheckResult) -> Unit) {
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            completion(DiagnosticCheckResult.NOT_AUTHORIZED)
            return
        }
        try {
            val enumerator = Camera2Enumerator(appContext)
            val names = enumerator.deviceNames
            if (names.isNotEmpty()) {
                completion(DiagnosticCheckResult.AVAILABLE)
            } else {
                completion(DiagnosticCheckResult.UNAVAILABLE)
            }
        } catch (_: Exception) {
            completion(DiagnosticCheckResult.NOT_AUTHORIZED)
        }
    }

    fun checkMicrophone(completion: (DiagnosticCheckResult) -> Unit) {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            completion(DiagnosticCheckResult.NOT_AUTHORIZED)
            return
        }
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            completion(DiagnosticCheckResult.AVAILABLE)
        } else {
            completion(DiagnosticCheckResult.UNAVAILABLE)
        }
    }

    fun checkSpeaker(completion: (DiagnosticCheckResult) -> Unit) {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            completion(DiagnosticCheckResult.AVAILABLE)
        } else {
            completion(DiagnosticCheckResult.UNAVAILABLE)
        }
    }

    fun checkNetwork(completion: (DiagnosticCheckResult) -> Unit) {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        if (cm?.activeNetwork != null) {
            completion(DiagnosticCheckResult.AVAILABLE)
        } else {
            completion(DiagnosticCheckResult.UNAVAILABLE)
        }
    }

    fun checkSignaling(completion: (SignalingCheckResult) -> Unit) {
        val forceSse = config.transports == listOf(SerenadaTransport.SSE)
        var diagClient: SignalingClient? = null
        var completed = false
        val timeoutRunnable = Runnable {
            if (completed) return@Runnable; completed = true
            diagClient?.close()
            completion(SignalingCheckResult.Failed("timeout"))
        }
        diagClient = SignalingClient(
            okHttpClient, handler,
            object : SignalingClient.Listener {
                override fun onOpen(activeTransport: String) {
                    if (completed) return; completed = true
                    handler.removeCallbacks(timeoutRunnable)
                    diagClient?.close()
                    completion(SignalingCheckResult.Connected(activeTransport))
                }

                override fun onMessage(message: SignalingMessage) {}

                override fun onClosed(reason: String) {
                    if (completed) return; completed = true
                    handler.removeCallbacks(timeoutRunnable)
                    completion(SignalingCheckResult.Failed(reason))
                }
            },
            forceSse = forceSse,
        )

        handler.postDelayed(timeoutRunnable, 5000)

        diagClient.connect(config.serverHost)
    }

    fun checkTurn(completion: (TurnCheckResult) -> Unit) {
        apiClient.fetchDiagnosticToken(config.serverHost) { tokenResult ->
            tokenResult
                .onSuccess { token ->
                    val start = System.currentTimeMillis()
                    apiClient.fetchTurnCredentials(config.serverHost, token) { turnResult ->
                        turnResult
                            .onSuccess {
                                val latencyMs = System.currentTimeMillis() - start
                                completion(TurnCheckResult.Reachable(latencyMs))
                            }
                            .onFailure { completion(TurnCheckResult.Unreachable(it.message ?: "unknown")) }
                    }
                }
                .onFailure { completion(TurnCheckResult.Unreachable(it.message ?: "unknown")) }
        }
    }

    suspend fun validateServerHost(host: String = config.serverHost) {
        suspendCancellableCoroutine<Unit> { continuation ->
            apiClient.validateServerHost(host) { result ->
                if (continuation.isActive) {
                    result
                        .onSuccess { continuation.resume(Unit) }
                        .onFailure { continuation.resumeWithException(it) }
                }
            }
        }
    }

    private fun enumerateDevices(completion: (List<DeviceInfo>) -> Unit) {
        val devices = mutableListOf<DeviceInfo>()
        try {
            val enumerator = Camera2Enumerator(appContext)
            enumerator.deviceNames.forEach { name ->
                val kind = if (enumerator.isFrontFacing(name)) "front-camera" else "back-camera"
                devices.add(DeviceInfo(id = name, name = name, kind = kind))
            }
        } catch (_: Exception) {}
        completion(devices)
    }

    private suspend fun runTimedCheck(block: suspend () -> Unit): CheckOutcome {
        val start = SystemClock.elapsedRealtime()
        return try {
            block()
            CheckOutcome.Passed((SystemClock.elapsedRealtime() - start).toInt())
        } catch (error: Throwable) {
            CheckOutcome.Failed(error.message ?: "error")
        }
    }

    private suspend fun awaitCreateRoomId(host: String): String = suspendCancellableCoroutine { continuation ->
        apiClient.createRoomId(host) { result ->
            if (continuation.isActive) {
                result
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            }
        }
    }

    private suspend fun awaitDiagnosticToken(host: String): String = suspendCancellableCoroutine { continuation ->
        apiClient.fetchDiagnosticToken(host) { result ->
            if (continuation.isActive) {
                result
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            }
        }
    }

    private suspend fun awaitTurnCredentials(host: String, token: String) = suspendCancellableCoroutine<Unit> { continuation ->
        apiClient.fetchTurnCredentials(host, token) { result ->
            if (continuation.isActive) {
                result
                    .onSuccess { continuation.resume(Unit) }
                    .onFailure { continuation.resumeWithException(it) }
            }
        }
    }

    private suspend fun testWebSocket(host: String) {
        val url = buildWssUrl(host)
        withTimeout(10_000) {
        suspendCancellableCoroutine<Unit> { continuation ->
            var closed = false
            val request = Request.Builder().url(url).build()
            val webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closed) return
                    closed = true
                    webSocket.close(1000, "diagnostics")
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (closed) return
                    closed = true
                    if (continuation.isActive) {
                        continuation.resumeWithException(t)
                    }
                }
            })
            continuation.invokeOnCancellation {
                webSocket.cancel()
            }
        }
        }
    }

    private suspend fun testSse(host: String) = withTimeout(10_000) {
        val sid = "S-diag-${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val url = buildSseUrl(host, sid) ?: throw IllegalArgumentException("Invalid host")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { streamResponse ->
            if (!streamResponse.isSuccessful) {
                throw IllegalStateException("GET HTTP ${streamResponse.code}")
            }
            val contentType = streamResponse.header("Content-Type").orEmpty().lowercase()
            if (!contentType.contains("text/event-stream")) {
                throw IllegalStateException("Unexpected content-type")
            }

            val pingBody = """{"v":1,"type":"ping","payload":{"ts":${System.currentTimeMillis()}}}"""
                .toRequestBody(SSE_JSON_MEDIA_TYPE)
            val pingRequest = Request.Builder()
                .url(url)
                .post(pingBody)
                .header("Content-Type", "application/json")
                .build()
            okHttpClient.newCall(pingRequest).execute().use { pingResponse ->
                if (!pingResponse.isSuccessful) {
                    throw IllegalStateException("POST HTTP ${pingResponse.code}")
                }
            }
        }
    }

    private fun buildWssUrl(hostInput: String): String {
        val raw = hostInput.trim()
        val isLocal = raw.startsWith("localhost") || raw.startsWith("127.")
        val scheme = if (isLocal) "ws" else "wss"
        val hostPart = raw.removePrefix("https://").removePrefix("http://")
        return "$scheme://$hostPart/ws"
    }

    private fun buildSseUrl(hostInput: String, sid: String): String? {
        if (sid.isBlank()) return null
        val base = buildHttpsUrl(hostInput, "/sse")?.toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addQueryParameter("sid", sid)
            .build()
            .toString()
    }
}

enum class DiagnosticCheckResult {
    AVAILABLE,
    UNAVAILABLE,
    NOT_AUTHORIZED,
    SKIPPED,
}

sealed class SignalingCheckResult {
    data class Connected(val transport: String) : SignalingCheckResult()
    data class Failed(val reason: String) : SignalingCheckResult()
    data class Skipped(val reason: String) : SignalingCheckResult()
}

sealed class TurnCheckResult {
    data class Reachable(val latencyMs: Long) : TurnCheckResult()
    data class Unreachable(val reason: String) : TurnCheckResult()
    data class Skipped(val reason: String) : TurnCheckResult()
}

data class DeviceInfo(
    val id: String,
    val name: String,
    val kind: String,
)

data class DiagnosticsReport(
    val camera: DiagnosticCheckResult,
    val microphone: DiagnosticCheckResult,
    val speaker: DiagnosticCheckResult,
    val network: DiagnosticCheckResult,
    val signaling: SignalingCheckResult,
    val turn: TurnCheckResult,
    val devices: List<DeviceInfo>,
)

sealed class CheckOutcome {
    data object NotRun : CheckOutcome()

    data class Passed(val latencyMs: Int) : CheckOutcome()

    data class Failed(val error: String) : CheckOutcome()
}

data class ConnectivityReport(
    val roomApi: CheckOutcome = CheckOutcome.NotRun,
    val webSocket: CheckOutcome = CheckOutcome.NotRun,
    val sse: CheckOutcome = CheckOutcome.NotRun,
    val diagnosticToken: CheckOutcome = CheckOutcome.NotRun,
    val turnCredentials: CheckOutcome = CheckOutcome.NotRun,
)

data class IceProbeReport(
    val stunPassed: Boolean,
    val turnPassed: Boolean,
    val logs: List<String>,
    val iceServersSummary: String = "n/a",
)

private val SSE_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
