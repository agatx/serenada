package app.serenada.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import app.serenada.core.call.SignalingClient
import app.serenada.core.call.SignalingMessage
import app.serenada.core.network.CoreApiClient
import okhttp3.OkHttpClient
import org.webrtc.Camera2Enumerator
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

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
