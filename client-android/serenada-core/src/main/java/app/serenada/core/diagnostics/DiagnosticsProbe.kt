package app.serenada.core.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import app.serenada.core.network.CoreApiClient
import app.serenada.core.network.TurnCredentials
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

enum class DiagnosticsCheckState {
    Pass,
    Warn,
    Fail,
    Running,
    Idle,
}

data class DiagnosticsCheckResult(
    val state: DiagnosticsCheckState,
    val detail: String,
)

data class DiagnosticsMediaReport(
    val cameraHardware: DiagnosticsCheckResult,
    val frontCamera: DiagnosticsCheckResult,
    val backCamera: DiagnosticsCheckResult,
    val compositeModePrerequisite: DiagnosticsCheckResult,
    val microphoneFeature: DiagnosticsCheckResult,
    val echoCancellation: DiagnosticsCheckResult,
    val noiseSuppression: DiagnosticsCheckResult,
    val autoGainControl: DiagnosticsCheckResult,
    val audioSampleRate: String,
    val audioFramesPerBuffer: String,
)

data class DiagnosticsIceReport(
    val turnsOnly: Boolean,
    val stun: DiagnosticsCheckResult,
    val turn: DiagnosticsCheckResult,
    val iceServersSummary: String,
    val logs: List<String>,
)

fun buildDiagnosticsMediaReport(context: Context): DiagnosticsMediaReport {
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

    return DiagnosticsMediaReport(
        cameraHardware = if (hasCameraHardware) {
            DiagnosticsCheckResult(DiagnosticsCheckState.Pass, "Available")
        } else {
            DiagnosticsCheckResult(DiagnosticsCheckState.Fail, "Not available")
        },
        frontCamera = if (front != null) {
            DiagnosticsCheckResult(DiagnosticsCheckState.Pass, cameraSummary(front))
        } else {
            DiagnosticsCheckResult(DiagnosticsCheckState.Fail, "Missing")
        },
        backCamera = if (back != null) {
            DiagnosticsCheckResult(DiagnosticsCheckState.Pass, cameraSummary(back))
        } else {
            DiagnosticsCheckResult(DiagnosticsCheckState.Warn, "Missing")
        },
        compositeModePrerequisite = if (front != null && back != null) {
            DiagnosticsCheckResult(DiagnosticsCheckState.Pass, "Front + back detected")
        } else {
            DiagnosticsCheckResult(DiagnosticsCheckState.Warn, "Requires both front and back")
        },
        microphoneFeature = if (hasMicrophone) {
            DiagnosticsCheckResult(DiagnosticsCheckState.Pass, "Available")
        } else {
            DiagnosticsCheckResult(DiagnosticsCheckState.Fail, "Not available")
        },
        echoCancellation = effectAvailability(AcousticEchoCanceler.isAvailable()),
        noiseSuppression = effectAvailability(NoiseSuppressor.isAvailable()),
        autoGainControl = effectAvailability(AutomaticGainControl.isAvailable()),
        audioSampleRate = sampleRate,
        audioFramesPerBuffer = framesPerBuffer,
    )
}

suspend fun runDiagnosticsIceCheck(
    context: Context,
    host: String,
    turnsOnly: Boolean,
    onIceServersSummary: (String) -> Unit = {},
    onLogLine: (String) -> Unit = {},
): DiagnosticsIceReport = withContext(Dispatchers.IO) {
    val okHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    val coreApiClient = CoreApiClient(okHttpClient)
    val logs = Collections.synchronizedList(mutableListOf<String>())

    fun log(message: String) {
        val line = "[${System.currentTimeMillis() / 1000}] $message"
        logs.add(line)
        onLogLine(line)
    }

    log("Starting ICE test (turnsOnly=$turnsOnly)...")
    log("Requesting diagnostic token...")

    val token = coreApiClient.awaitDiagnosticToken(host).getOrElse { error ->
        val message = error.message ?: "Diagnostic token failed"
        log("Token error: $message")
        return@withContext DiagnosticsIceReport(
            turnsOnly = turnsOnly,
            stun = DiagnosticsCheckResult(DiagnosticsCheckState.Fail, message),
            turn = DiagnosticsCheckResult(DiagnosticsCheckState.Fail, message),
            iceServersSummary = "n/a",
            logs = logs.toList(),
        )
    }
    log("Diagnostic token received.")

    val creds = coreApiClient.awaitTurnCredentials(host, token).getOrElse { error ->
        val message = error.message ?: "TURN credentials failed"
        log("TURN credentials error: $message")
        return@withContext DiagnosticsIceReport(
            turnsOnly = turnsOnly,
            stun = DiagnosticsCheckResult(DiagnosticsCheckState.Fail, message),
            turn = DiagnosticsCheckResult(DiagnosticsCheckState.Fail, message),
            iceServersSummary = "n/a",
            logs = logs.toList(),
        )
    }
    log("TURN credentials: ttl=${creds.ttl}s, usernameTs=${creds.username.substringBefore(':', "n/a")}, uris=${creds.uris.size}")
    creds.uris.forEachIndexed { index, uri ->
        log("ICE URI[$index]: ${describeIceServerUri(uri)}")
    }

    val filteredUris =
        if (turnsOnly) {
            creds.uris.filter { it.startsWith("turns:", ignoreCase = true) }
        } else {
            creds.uris
        }
    if (filteredUris.isEmpty()) {
        onIceServersSummary("n/a")
        return@withContext DiagnosticsIceReport(
            turnsOnly = turnsOnly,
            stun = if (turnsOnly) {
                DiagnosticsCheckResult(DiagnosticsCheckState.Warn, "Skipped (TURNS only)")
            } else {
                DiagnosticsCheckResult(DiagnosticsCheckState.Fail, "No ICE servers")
            },
            turn = DiagnosticsCheckResult(DiagnosticsCheckState.Fail, "No compatible ICE servers"),
            iceServersSummary = "n/a",
            logs = listOf("No compatible ICE servers for this mode."),
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

    var gather = runIceGathering(context, servers, turnsOnly, ::log)
    // Zero total candidates (not even host) means the NetworkMonitor hadn't
    // enumerated interfaces yet — a transient race after the previous
    // PeerConnection was torn down.  Retry once; the monitor will be ready.
    if (gather.third == 0) {
        log("Zero candidates gathered — retrying (NetworkMonitor race)...")
        gather = runIceGathering(context, servers, turnsOnly, ::log)
    }
    DiagnosticsIceReport(
        turnsOnly = turnsOnly,
        stun = gather.first,
        turn = gather.second,
        iceServersSummary = iceServersSummary,
        logs = logs.toList(),
    )
}

private fun effectAvailability(isAvailable: Boolean): DiagnosticsCheckResult {
    return if (isAvailable) {
        DiagnosticsCheckResult(DiagnosticsCheckState.Pass, "Available")
    } else {
        DiagnosticsCheckResult(DiagnosticsCheckState.Warn, "Unavailable")
    }
}

private fun normalizeFps(rawFps: Int): Int {
    return if (rawFps > 1000) rawFps / 1000 else rawFps
}

/**
 * Returns (stunResult, turnResult, totalCandidateCount).
 */
private fun runIceGathering(
    context: Context,
    servers: List<PeerConnection.IceServer>,
    turnsOnly: Boolean,
    log: (String) -> Unit,
): Triple<DiagnosticsCheckResult, DiagnosticsCheckResult, Int> {
    val factory = getOrCreatePeerConnectionFactory(context)
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
    log("RTC config: policy=${config.iceTransportsType}, semantics=${config.sdpSemantics}, servers=${servers.size}")

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

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceCandidateError(event: IceCandidateErrorEvent) {
            val count = candidateErrorCount.incrementAndGet()
            val category = classifyIceError(event.errorCode)
            log(
                "ICE candidate error#$count: code=${event.errorCode}($category), text=${event.errorText}, url=${event.url}, address=${event.address}, port=${event.port}",
            )
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            val local = formatCandidateBrief(event.local)
            val remote = formatCandidateBrief(event.remote)
            log(
                "Selected pair: local={$local} remote={$remote} reason=${event.reason} lastDataMs=${event.lastDataReceivedMs} estDisconnectedMs=${event.estimatedDisconnectedTimeMs}",
            )
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            log("ICE gathering state: $newState")
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                gatherDone.countDown()
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(dc: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) = Unit
    })

    if (peerConnection == null) {
        return Triple(
            DiagnosticsCheckResult(DiagnosticsCheckState.Fail, "PeerConnection creation failed"),
            DiagnosticsCheckResult(DiagnosticsCheckState.Fail, "PeerConnection creation failed"),
            0,
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
                override fun onCreateSuccess(desc: SessionDescription?) = Unit

                override fun onSetSuccess() {
                    log("Local description set.")
                }

                override fun onCreateFailure(error: String?) = Unit

                override fun onSetFailure(error: String?) {
                    failed.set(true)
                    failureReason = error ?: "setLocalDescription failed"
                    gatherDone.countDown()
                }
            }, desc)
        }

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String?) {
            failed.set(true)
            failureReason = error ?: "createOffer failed"
            gatherDone.countDown()
        }

        override fun onSetFailure(error: String?) = Unit
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
        "ICE candidate summary: total=${candidateSeq.get()}, host=${hostCount.get()}, srflx=${srflxCount.get()}, relay=${relayCount.get()}, prflx=${prflxCount.get()}, other=${otherCount.get()}, errors=${candidateErrorCount.get()}",
    )
    if (turnsOnly && !turnFound.get()) {
        if (candidateErrorCount.get() > 0) {
            log("TURNS-only result: relay missing and candidate errors were reported (see error lines above).")
        } else {
            log("TURNS-only result: relay missing with no candidate errors reported by libwebrtc.")
        }
    }

    // close() stops ICE and media but does not immediately free the native
    // NetworkMonitor.  Skipping dispose() lets the monitor stay alive until
    // GC, so the next PeerConnection's monitor can register without a gap
    // that would cause zero-candidate ICE gathering.
    runCatching { peerConnection.close() }

    val stunResult = when {
        turnsOnly -> DiagnosticsCheckResult(DiagnosticsCheckState.Warn, "Skipped (TURNS only)")
        failed.get() -> DiagnosticsCheckResult(DiagnosticsCheckState.Fail, failureReason)
        stunFound.get() -> DiagnosticsCheckResult(DiagnosticsCheckState.Pass, "Detected")
        else -> DiagnosticsCheckResult(DiagnosticsCheckState.Fail, "No server-reflexive candidate")
    }
    val turnResult = when {
        failed.get() -> DiagnosticsCheckResult(DiagnosticsCheckState.Fail, failureReason)
        turnFound.get() -> DiagnosticsCheckResult(DiagnosticsCheckState.Pass, "Detected")
        else -> DiagnosticsCheckResult(DiagnosticsCheckState.Fail, "No relay candidate")
    }
    return Triple(stunResult, turnResult, candidateSeq.get())
}

private val diagnosticsPcFactoryLock = Any()
@Volatile private var diagnosticsPcFactory: PeerConnectionFactory? = null

/**
 * Eagerly initialize the [PeerConnectionFactory] so that its internal network
 * thread is warmed up before the first ICE probe runs.  Without this, the very
 * first probe can complete before network interfaces are enumerated, causing
 * relay (TURN) candidates to be missed.
 */
internal fun warmUpPeerConnectionFactory(context: Context) {
    getOrCreatePeerConnectionFactory(context)
}

private fun getOrCreatePeerConnectionFactory(context: Context): PeerConnectionFactory {
    diagnosticsPcFactory?.let { return it }
    synchronized(diagnosticsPcFactoryLock) {
        diagnosticsPcFactory?.let { return it }
        // Initialize must happen before enableVerboseWebRtcLogging — it loads
        // the native library that the logging JNI calls depend on.
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        enableVerboseWebRtcLoggingForDiagnostics()
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        diagnosticsPcFactory = factory
        return factory
    }
}

private val diagnosticsWebRtcLoggingEnabled = AtomicBoolean(false)

private fun enableVerboseWebRtcLoggingForDiagnostics() {
    if (diagnosticsWebRtcLoggingEnabled.get()) return
    runCatching {
        Logging.enableLogThreads()
        Logging.enableLogTimeStamps()
        Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE)
        diagnosticsWebRtcLoggingEnabled.set(true)
        Log.i("Diagnostics", "Verbose native WebRTC logging enabled")
    }.onFailure { error ->
        Log.w("Diagnostics", "Failed to enable WebRTC verbose logging", error)
    }
}

private fun extractCandidateType(candidateSdp: String): String? {
    val parts = candidateSdp.split(' ')
    val typIndex = parts.indexOf("typ")
    return if (typIndex != -1 && typIndex + 1 < parts.size) parts[typIndex + 1] else null
}

private fun formatIceCandidateLog(candidate: IceCandidate, sequence: Int?): String {
    val prefix = sequence?.let { "#$it " }.orEmpty()
    return prefix + formatCandidateBrief(candidate)
}

private fun formatCandidateBrief(candidate: IceCandidate): String {
    val type = extractCandidateType(candidate.sdp).orEmpty()
    return "mid=${candidate.sdpMid}, mline=${candidate.sdpMLineIndex}, type=$type, sdp=${candidate.sdp}"
}

private fun classifyIceError(errorCode: Int): String {
    return when (errorCode) {
        300 -> "Try alternate"
        400 -> "Bad request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        437 -> "Allocation mismatch"
        438 -> "Stale nonce"
        486 -> "Allocation quota reached"
        500 -> "Server error"
        else -> "Other"
    }
}

private fun describeIceServerUri(uri: String): String {
    return when {
        uri.startsWith("turns:", ignoreCase = true) -> "TURNS $uri"
        uri.startsWith("turn:", ignoreCase = true) -> "TURN $uri"
        uri.startsWith("stun:", ignoreCase = true) -> "STUN $uri"
        else -> uri
    }
}

private suspend fun CoreApiClient.awaitDiagnosticToken(host: String): Result<String> {
    return suspendCancellableCoroutine { continuation ->
        fetchDiagnosticToken(host) { result ->
            continuation.resume(result)
        }
    }
}

private suspend fun CoreApiClient.awaitTurnCredentials(host: String, token: String): Result<TurnCredentials> {
    return suspendCancellableCoroutine { continuation ->
        fetchTurnCredentials(host, token) { result ->
            continuation.resume(result)
        }
    }
}
