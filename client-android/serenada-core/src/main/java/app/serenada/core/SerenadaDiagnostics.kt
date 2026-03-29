package app.serenada.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import app.serenada.core.call.SignalingClient
import app.serenada.core.call.SignalingMessage
import app.serenada.core.diagnostics.runDiagnosticsIceCheck
import app.serenada.core.diagnostics.runDiagnosticsIceCheckWithIceServers
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
import org.webrtc.PeerConnection
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal typealias ProviderIceProbeRunner = suspend (
    iceServers: List<PeerConnection.IceServer>,
    turnsOnly: Boolean,
    onCandidateLog: ((String) -> Unit)?,
) -> IceProbeReport

/**
 * Pre-flight diagnostics utility. Checks device capabilities and server connectivity
 * without prompting for permissions.
 */
class SerenadaDiagnostics private constructor(
    private val config: SerenadaConfig,
    private val context: Context,
    private val providerIceProbeRunner: ProviderIceProbeRunner,
) {
    private val appContext = context.applicationContext
    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = CoreApiClient(okHttpClient)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolvedConfig = resolveSerenadaConfig(config)

    init {
        // Eagerly warm up the PeerConnectionFactory on a background thread so
        // its network thread is ready by the time the user runs an ICE probe.
        Thread { app.serenada.core.diagnostics.warmUpPeerConnectionFactory(appContext) }.start()
    }

    constructor(
        config: SerenadaConfig,
        context: Context,
    ) : this(
        config = config,
        context = context,
        providerIceProbeRunner = { iceServers: List<PeerConnection.IceServer>, turnsOnly: Boolean, onCandidateLog: ((String) -> Unit)? ->
            val report = runDiagnosticsIceCheckWithIceServers(
                context = context.applicationContext,
                iceServers = iceServers,
                turnsOnly = turnsOnly,
                onLogLine = { line -> onCandidateLog?.invoke(line) },
            )
            IceProbeReport(
                stunPassed = report.stun.state == app.serenada.core.diagnostics.DiagnosticsCheckState.Pass,
                turnPassed = report.turn.state == app.serenada.core.diagnostics.DiagnosticsCheckState.Pass,
                logs = report.logs,
                iceServersSummary = report.iceServersSummary,
            )
        },
    )

    /** Run all diagnostic checks and return a full report. */
    suspend fun runAll(): DiagnosticsReport = suspendCancellableCoroutine { continuation ->
        runAll { report ->
            if (continuation.isActive) {
                continuation.resume(report)
            }
        }
    }

    /** Callback-based variant of [runAll]. Results are delivered on the main thread. */
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
        if (resolvedConfig.serverHost != null) {
            checkSignaling { signalingResult = it; tryComplete() }
        } else {
            signalingResult = SignalingCheckResult.Skipped("requires serverHost")
            tryComplete()
        }
        checkTurn { turnResult = it; tryComplete() }
        enumerateDevices { devices = it; tryComplete() }
    }

    /** Test server connectivity (room API, WebSocket, SSE, TURN). */
    suspend fun runConnectivityChecks(host: String? = resolvedConfig.serverHost): ConnectivityReport = withContext(Dispatchers.IO) {
        val normalizedHost = host?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolvedConfig.serverHost
            ?: throw IllegalStateException("requires serverHost")
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

    /** Probe ICE connectivity (STUN/TURN) and return candidate details. */
    suspend fun runTurnProbe(
        turnsOnly: Boolean,
        host: String? = resolvedConfig.serverHost,
        onCandidateLog: ((String) -> Unit)? = null,
    ): IceProbeReport {
        val resolvedHost = host?.trim()?.takeIf { it.isNotEmpty() }
        if (resolvedHost != null) {
            val report = runDiagnosticsIceCheck(
                context = appContext,
                host = resolvedHost,
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

        return try {
            val iceServers = withContext(Dispatchers.IO) {
                (resolvedConfig.signalingProvider ?: throw IllegalStateException("Provide exactly one of serverHost or signalingProvider"))
                    .getIceServers()
            }
            providerIceProbeRunner(iceServers, turnsOnly, onCandidateLog)
        } catch (error: Throwable) {
            IceProbeReport(
                stunPassed = false,
                turnPassed = false,
                logs = listOf(error.message ?: "ICE probe failed"),
                iceServersSummary = "n/a",
            )
        }
    }

    /** Probe ICE connectivity (STUN/TURN) and return candidate details. */
    @Deprecated(
        message = "Use runTurnProbe(turnsOnly, host, onCandidateLog) instead.",
        replaceWith = ReplaceWith("runTurnProbe(turnsOnly = turnsOnly, host = host, onCandidateLog = onCandidateLog)")
    )
    suspend fun runIceProbe(
        turnsOnly: Boolean,
        host: String? = resolvedConfig.serverHost,
        onCandidateLog: ((String) -> Unit)? = null,
    ): IceProbeReport {
        return runTurnProbe(turnsOnly = turnsOnly, host = host, onCandidateLog = onCandidateLog)
    }

    /** Check whether a camera is available and authorized. */
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

    /** Check whether a microphone is available and authorized. */
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

    /** Check whether an audio output (speaker) is available. */
    fun checkSpeaker(completion: (DiagnosticCheckResult) -> Unit) {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            completion(DiagnosticCheckResult.AVAILABLE)
        } else {
            completion(DiagnosticCheckResult.UNAVAILABLE)
        }
    }

    /** Check whether a network connection is available. */
    fun checkNetwork(completion: (DiagnosticCheckResult) -> Unit) {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        if (cm?.activeNetwork != null) {
            completion(DiagnosticCheckResult.AVAILABLE)
        } else {
            completion(DiagnosticCheckResult.UNAVAILABLE)
        }
    }

    /** Check signaling server connectivity (WebSocket or SSE). */
    fun checkSignaling(completion: (SignalingCheckResult) -> Unit) {
        val serverHost = resolvedConfig.serverHost
        if (serverHost == null) {
            completion(SignalingCheckResult.Skipped("requires serverHost"))
            return
        }
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

        diagClient.connect(serverHost)
    }

    /** Check TURN server reachability and measure latency. */
    fun checkTurn(completion: (TurnCheckResult) -> Unit) {
        val serverHost = resolvedConfig.serverHost
        if (serverHost == null) {
            scope.launch {
                runCatching {
                    (resolvedConfig.signalingProvider ?: throw IllegalStateException("Provide exactly one of serverHost or signalingProvider"))
                        .getIceServers()
                }.onSuccess {
                    handler.post { completion(TurnCheckResult.Reachable(0L)) }
                }.onFailure { error ->
                    handler.post { completion(TurnCheckResult.Unreachable(error.message ?: "unknown")) }
                }
            }
            return
        }
        apiClient.fetchDiagnosticToken(serverHost) { tokenResult ->
            tokenResult
                .onSuccess { token ->
                    val start = System.currentTimeMillis()
                    apiClient.fetchTurnCredentials(serverHost, token) { turnResult ->
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

    /** Validate that a server host is reachable. Throws on failure. */
    suspend fun validateServerHost(host: String? = resolvedConfig.serverHost) {
        val resolvedHost = host?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("requires serverHost")
        suspendCancellableCoroutine<Unit> { continuation ->
            apiClient.validateServerHost(resolvedHost) { result ->
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

    companion object {
        internal fun createForTesting(
            config: SerenadaConfig,
            context: Context,
            providerIceProbeRunner: ProviderIceProbeRunner,
        ): SerenadaDiagnostics {
            return SerenadaDiagnostics(
                config = config,
                context = context,
                providerIceProbeRunner = providerIceProbeRunner,
            )
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
