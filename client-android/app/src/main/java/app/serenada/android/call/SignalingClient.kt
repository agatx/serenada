package app.serenada.android.call

import android.os.Handler
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class SignalingClient(
    private val okHttpClient: OkHttpClient,
    private val handler: Handler,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onMessage(message: SignalingMessage)
        fun onClosed(reason: String)
    }

    private var webSocket: WebSocket? = null
    private var connected = false
    private var connecting = false
    private var pingRunnable: Runnable? = null
    private var connectTimeoutRunnable: Runnable? = null
    private var connectionAttemptId = 0
    private var activeAttemptId = 0
    private var closeNotifiedAttemptId: Int? = null

    fun connect(host: String) {
        if (connected || connecting) return
        val url = buildWssUrl(host) ?: run {
            handler.post { listener.onClosed("invalid_host") }
            return
        }
        connecting = true
        connectionAttemptId += 1
        val attemptId = connectionAttemptId
        activeAttemptId = attemptId
        closeNotifiedAttemptId = null
        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (activeAttemptId != attemptId) return
                clearConnectTimeout()
                connecting = false
                connected = true
                handler.post { listener.onOpen() }
                startPing()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (activeAttemptId != attemptId) return
                val msg = SignalingMessage.fromJson(text) ?: return
                handler.post {
                    if (activeAttemptId != attemptId) return@post
                    listener.onMessage(msg)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Ignore binary
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeAttemptId != attemptId) return
                connecting = false
                connected = false
                stopPing()
                clearConnectTimeout()
                this@SignalingClient.webSocket = null
                if (closeNotifiedAttemptId == attemptId) return
                handler.post { listener.onClosed(reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (activeAttemptId != attemptId) return
                connecting = false
                connected = false
                stopPing()
                clearConnectTimeout()
                this@SignalingClient.webSocket = null
                if (closeNotifiedAttemptId == attemptId) return
                handler.post { listener.onClosed(t.message ?: "failure") }
            }
        })
        scheduleConnectTimeout(attemptId)
    }

    fun isConnected(): Boolean = connected

    fun send(message: SignalingMessage) {
        webSocket?.send(message.toJson())
    }

    fun close() {
        stopPing()
        clearConnectTimeout()
        webSocket?.close(1000, "client_close")
        webSocket = null
        connecting = false
        connected = false
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
                webSocket?.send(payload.toJson())
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
            if (activeAttemptId != attemptId) return@Runnable
            if (connected) return@Runnable
            connecting = false
            activeAttemptId = -attemptId
            closeNotifiedAttemptId = attemptId
            webSocket?.cancel()
            webSocket?.close(1000, "timeout")
            webSocket = null
            handler.post { listener.onClosed("timeout") }
        }
        connectTimeoutRunnable = runnable
        handler.postDelayed(runnable, 2000)
    }

    private fun clearConnectTimeout() {
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    private fun buildWssUrl(hostInput: String): String? {
        val host = hostInput.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (host.isBlank()) return null
        return "wss://$host/ws"
    }
}
