package app.serenada.android.call

import android.os.Handler
import android.util.Log
import app.serenada.android.BuildConfig
import okhttp3.OkHttpClient

class SignalingClient(
    private val okHttpClient: OkHttpClient,
    private val handler: Handler,
    private val listener: Listener
) {
    enum class TransportKind(val wireName: String) {
        WS("ws"),
        SSE("sse")
    }

    interface Listener {
        fun onOpen(activeTransport: String)
        fun onMessage(message: SignalingMessage)
        fun onClosed(reason: String)
    }

    private val transportOrder = if (BuildConfig.FORCE_SSE_SIGNALING) {
        listOf(TransportKind.SSE)
    } else {
        listOf(TransportKind.WS, TransportKind.SSE)
    }
    private val transportConnectedOnce = mutableMapOf(
        TransportKind.WS to false,
        TransportKind.SSE to false
    )

    private val wsTransport = WebSocketSignalingTransport(okHttpClient)
    private val sseTransport = SseSignalingTransport(okHttpClient)
    private val transports = listOf<SignalingTransport>(wsTransport, sseTransport)

    private var connected = false
    private var connecting = false
    private var pingRunnable: Runnable? = null
    private var connectTimeoutRunnable: Runnable? = null
    private var connectionAttemptId = 0
    private var activeAttemptId = 0
    private var transportIndex = 0
    private var activeTransport: TransportKind? = null
    private var activeTransportImpl: SignalingTransport? = null
    private var normalizedHost: String? = null
    private var closedByClient = false

    fun connect(host: String) {
        if (connected || connecting) return
        val normalized = normalizeHost(host) ?: run {
            handler.post { listener.onClosed("invalid_host") }
            return
        }
        if (BuildConfig.FORCE_SSE_SIGNALING) {
            Log.i(TAG, "FORCE_SSE_SIGNALING is enabled; using SSE only")
        }
        resetTransportState()
        if (normalized != normalizedHost) {
            resetTransportSessions()
        }
        normalizedHost = normalized
        closedByClient = false
        connectWithTransport(transportIndex)
    }

    fun isConnected(): Boolean = connected

    fun send(message: SignalingMessage) {
        if (!connected) return
        activeTransportImpl?.send(message)
    }

    fun close() {
        closedByClient = true
        stopPing()
        clearConnectTimeout()
        connecting = false
        connected = false
        activeAttemptId = -kotlin.math.abs(activeAttemptId)
        activeTransport = null
        activeTransportImpl = null
        closeTransports()
        normalizedHost = null
        resetTransportState()
        resetTransportSessions()
    }

    private fun startPing() {
        stopPing()
        val runnable = object : Runnable {
            override fun run() {
                if (!connected) return
                val payload = SignalingMessage(
                    type = "ping",
                    rid = null,
                    sid = null,
                    cid = null,
                    to = null,
                    payload = null
                )
                send(payload)
                handler.postDelayed(this, 12_000)
            }
        }
        pingRunnable = runnable
        handler.postDelayed(runnable, 12_000)
    }

    private fun stopPing() {
        pingRunnable?.let { handler.removeCallbacks(it) }
        pingRunnable = null
    }

    private fun scheduleConnectTimeout(attemptId: Int) {
        clearConnectTimeout()
        val runnable = Runnable {
            val transportKind = activeTransport ?: return@Runnable
            if (!isAttemptActive(attemptId, transportKind)) return@Runnable
            if (connected) return@Runnable
            handleTransportClosed(attemptId, transportKind, "timeout")
        }
        connectTimeoutRunnable = runnable
        handler.postDelayed(runnable, 2000)
    }

    private fun clearConnectTimeout() {
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    private fun connectWithTransport(index: Int) {
        if (connected || connecting) return
        val host = normalizedHost ?: return
        val kind = transportOrder.getOrNull(index) ?: return

        transportIndex = index
        activeTransport = kind
        connecting = true
        connectionAttemptId += 1
        val attemptId = connectionAttemptId
        activeAttemptId = attemptId

        closeTransports()

        val transport = transportForKind(kind)
        activeTransportImpl = transport
        transport.connect(
            host = host,
            onOpen = {
                handleTransportOpen(attemptId, kind)
            },
            onMessage = { msg ->
                if (isAttemptActive(attemptId, kind)) {
                    handler.post {
                        if (!isAttemptActive(attemptId, kind)) return@post
                        listener.onMessage(msg)
                    }
                }
            },
            onClosed = { reason ->
                handleTransportClosed(attemptId, kind, reason)
            }
        )
        scheduleConnectTimeout(attemptId)
    }

    private fun handleTransportOpen(attemptId: Int, kind: TransportKind) {
        if (!isAttemptActive(attemptId, kind)) return
        clearConnectTimeout()
        connecting = false
        connected = true
        transportConnectedOnce[kind] = true
        Log.i(TAG, "Signaling connected via ${kind.wireName}")
        handler.post { listener.onOpen(kind.wireName) }
        startPing()
    }

    private fun handleTransportClosed(attemptId: Int, kind: TransportKind, reason: String) {
        if (!isAttemptActive(attemptId, kind)) return
        clearConnectTimeout()
        stopPing()
        connecting = false
        connected = false
        activeAttemptId = -kotlin.math.abs(attemptId)
        activeTransport = null
        activeTransportImpl = null
        closeTransports()

        if (closedByClient) {
            return
        }

        if (shouldFallback(kind, reason) && tryNextTransport(reason)) {
            return
        }

        handler.post { listener.onClosed(reason) }
    }

    private fun shouldFallback(kind: TransportKind, reason: String): Boolean {
        if (transportOrder.size <= 1) return false
        if (transportIndex >= transportOrder.lastIndex) return false
        if (reason == "unsupported" || reason == "timeout") return true
        return transportConnectedOnce[kind] != true
    }

    private fun tryNextTransport(reason: String): Boolean {
        val current = transportOrder.getOrNull(transportIndex) ?: return false
        val nextIndex = transportIndex + 1
        val next = transportOrder.getOrNull(nextIndex) ?: return false
        Log.w(
            TAG,
            "Transport ${current.wireName} failed ($reason), falling back to ${next.wireName}"
        )
        transportIndex = nextIndex
        connectWithTransport(nextIndex)
        return true
    }

    private fun isAttemptActive(attemptId: Int, kind: TransportKind): Boolean {
        return activeAttemptId == attemptId && activeTransport == kind
    }

    private fun closeTransports() {
        transports.forEach { it.close() }
    }

    private fun resetTransportSessions() {
        transports.forEach { it.resetSession() }
    }

    private fun resetTransportState() {
        transportIndex = 0
        transportConnectedOnce[TransportKind.WS] = false
        transportConnectedOnce[TransportKind.SSE] = false
    }

    private fun normalizeHost(hostInput: String): String? {
        val host = hostInput.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (host.isBlank()) return null
        return host
    }

    private fun transportForKind(kind: TransportKind): SignalingTransport {
        return when (kind) {
            TransportKind.WS -> wsTransport
            TransportKind.SSE -> sseTransport
        }
    }

    private companion object {
        const val TAG = "SignalingClient"
    }
}
