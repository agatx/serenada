package app.serenada.android.call

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

internal class SseSignalingTransport(
    private val okHttpClient: OkHttpClient
) : SignalingTransport {
    override val kind: SignalingClient.TransportKind = SignalingClient.TransportKind.SSE

    // Server keepalive comments are every 12s; stream reads should not time out.
    private val sseStreamClient = okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val random = SecureRandom()

    @Volatile
    private var sid: String = createSid()
    @Volatile
    private var streamCall: Call? = null
    private val postCalls = mutableSetOf<Call>()
    private val postCallsLock = Any()
    @Volatile
    private var currentHost: String? = null
    @Volatile
    private var onMessageCallback: ((SignalingMessage) -> Unit)? = null
    @Volatile
    private var onClosedCallback: ((String) -> Unit)? = null

    override fun connect(
        host: String,
        onOpen: () -> Unit,
        onMessage: (SignalingMessage) -> Unit,
        onClosed: (String) -> Unit
    ) {
        currentHost = host
        onMessageCallback = onMessage
        onClosedCallback = onClosed
        val request = Request.Builder()
            .url(buildSseUrl(host, sid))
            .header("Accept", "text/event-stream")
            .build()
        val call = sseStreamClient.newCall(request)
        streamCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onClosed(e.message ?: "failure")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        onClosed("http_${res.code}")
                        return
                    }
                    val body = res.body
                    if (body == null) {
                        onClosed("empty_body")
                        return
                    }
                    onOpen()
                    readSseStream(body.source(), onClosed)
                }
            }
        })
    }

    override fun send(message: SignalingMessage) {
        val host = currentHost ?: return
        val body = message.toJson().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(buildSseUrl(host, sid))
            .post(body)
            .header("Content-Type", "application/json")
            .build()
        val call = okHttpClient.newCall(request)
        synchronized(postCallsLock) {
            postCalls.add(call)
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(postCallsLock) {
                    postCalls.remove(call)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    synchronized(postCallsLock) {
                        postCalls.remove(call)
                    }
                    if (res.code == 410) {
                        onClosedCallback?.invoke("gone")
                    }
                }
            }
        })
    }

    override fun close() {
        streamCall?.cancel()
        streamCall = null
        val inFlightPosts = synchronized(postCallsLock) {
            val copy = postCalls.toList()
            postCalls.clear()
            copy
        }
        inFlightPosts.forEach { it.cancel() }
        currentHost = null
        onMessageCallback = null
        onClosedCallback = null
    }

    override fun resetSession() {
        sid = createSid()
        currentHost = null
    }

    private fun readSseStream(
        source: BufferedSource,
        onClosed: (String) -> Unit
    ) {
        val dataBuffer = StringBuilder()
        try {
            while (true) {
                val rawLine = source.readUtf8Line() ?: break
                val line = rawLine.trimEnd('\r')
                if (line.isEmpty()) {
                    dispatchSseMessage(dataBuffer)
                    continue
                }
                if (line.startsWith(":")) continue
                if (line.startsWith("data:")) {
                    var dataPart = line.removePrefix("data:")
                    if (dataPart.startsWith(" ")) {
                        dataPart = dataPart.drop(1)
                    }
                    if (dataBuffer.isNotEmpty()) {
                        dataBuffer.append('\n')
                    }
                    dataBuffer.append(dataPart)
                }
            }
            dispatchSseMessage(dataBuffer)
            onClosed("close")
        } catch (_: IOException) {
            onClosed("failure")
        }
    }

    private fun dispatchSseMessage(dataBuffer: StringBuilder) {
        if (dataBuffer.isEmpty()) return
        val payload = dataBuffer.toString()
        dataBuffer.setLength(0)
        val msg = try {
            SignalingMessage.fromJson(payload)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to parse signaling message from SSE stream", e)
            null
        } ?: return
        onMessageCallback?.invoke(msg)
    }

    private fun createSid(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return "S-" + bytes.joinToString(separator = "") { b ->
            ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1)
        }
    }

    private companion object {
        const val TAG = "SseSignalingTransport"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private fun buildSseUrl(host: String, sid: String): String {
        return "https://$host/sse".toHttpUrl()
            .newBuilder()
            .addQueryParameter("sid", sid)
            .build()
            .toString()
    }
}
