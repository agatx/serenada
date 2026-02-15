package app.serenada.android.call

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

internal class WebSocketSignalingTransport(
    private val okHttpClient: OkHttpClient
) : SignalingTransport {
    override val kind: SignalingClient.TransportKind = SignalingClient.TransportKind.WS

    @Volatile
    private var webSocket: WebSocket? = null

    override fun connect(
        host: String,
        onOpen: () -> Unit,
        onMessage: (SignalingMessage) -> Unit,
        onClosed: (String) -> Unit
    ) {
        val request = Request.Builder().url(buildWssUrl(host)).build()
        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = try {
                        SignalingMessage.fromJson(text)
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Failed to parse signaling message from WebSocket", e)
                        null
                    } ?: return
                    onMessage(msg)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Ignore binary.
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onClosed(reason.ifBlank { "close" })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onClosed(t.message ?: "failure")
                }
            }
        )
    }

    override fun send(message: SignalingMessage) {
        webSocket?.send(message.toJson())
    }

    override fun close() {
        webSocket?.cancel()
        webSocket = null
    }

    private companion object {
        const val TAG = "WsSignalingTransport"
    }

    private fun buildWssUrl(host: String): String = "wss://$host/ws"
}
