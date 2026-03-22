package app.serenada.core

import android.content.Context
import android.os.Looper
import app.serenada.core.network.CoreApiClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient

/**
 * Main entry point for the Serenada SDK.
 *
 * Create an instance with a [SerenadaConfig], then use [join] to start a call session
 * or [createRoom] to create a new room.
 */
class SerenadaCore(
    val config: SerenadaConfig,
    private val context: Context,
) {
    var delegate: SerenadaCoreDelegate? = null
    var logger: SerenadaLogger? = null

    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = CoreApiClient(okHttpClient)

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SerenadaCore APIs must be called on the main thread"
        }
    }

    /**
     * Join a call using a full URL (e.g., "https://serenada.app/call/ABC123").
     */
    fun join(url: String): SerenadaSession {
        assertMainThread()
        val resolved = resolveRoomUrl(url)
        val roomId = resolved?.roomId ?: url
        val session = SerenadaSession(
            roomId = roomId,
            roomUrl = resolved?.roomUrl ?: url,
            serverHost = resolved?.serverHost ?: config.serverHost,
            config = config,
            context = context,
            delegate = { delegate },
            okHttpClient = okHttpClient,
            logger = logger,
        )
        session.start()
        return session
    }

    /**
     * Join a call using a room ID.
     */
    fun join(roomId: String, serverHost: String = config.serverHost): SerenadaSession {
        assertMainThread()
        val roomUrl = buildRoomUrl(serverHost, roomId)
        val session = SerenadaSession(
            roomId = roomId,
            roomUrl = roomUrl,
            serverHost = serverHost,
            config = config,
            context = context,
            delegate = { delegate },
            okHttpClient = okHttpClient,
            logger = logger,
        )
        session.start()
        return session
    }

    /**
     * Create a new room and immediately join it.
     */
    suspend fun createRoom(): CreateRoomResult {
        assertMainThread()
        val roomId = suspendCancellableCoroutine<String> { continuation ->
            apiClient.createRoomId(config.serverHost) { result ->
                result
                    .onSuccess { resolvedRoomId ->
                        continuation.resume(resolvedRoomId)
                    }
                    .onFailure { error ->
                        continuation.resumeWithException(error)
                    }
            }
        }

        val roomUrl = buildRoomUrl(config.serverHost, roomId)
        val session = SerenadaSession(
            roomId = roomId,
            roomUrl = roomUrl,
            serverHost = config.serverHost,
            config = config,
            context = context,
            delegate = { delegate },
            okHttpClient = okHttpClient,
            logger = logger,
        )
        session.start()
        return CreateRoomResult(roomId = roomId, roomUrl = roomUrl, session = session)
    }

    /**
     * Create a room ID without starting a session.
     * Use this when you only need a room ID (e.g., for invite links).
     */
    suspend fun createRoomId(): String {
        assertMainThread()
        return suspendCancellableCoroutine { continuation ->
            apiClient.createRoomId(config.serverHost) { result ->
                result
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            }
        }
    }

    private fun resolveRoomUrl(url: String): ResolvedRoomUrl? {
        val trimmed = url.trim()
        if (!trimmed.contains("/")) return null
        return try {
            val uri = android.net.Uri.parse(trimmed)
            val roomId = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
            val authority = uri.authority?.takeIf { it.isNotBlank() } ?: return null
            val scheme = uri.scheme?.takeIf { it.isNotBlank() }
                ?: if (isLocalHost(authority)) "http" else "https"
            ResolvedRoomUrl(
                roomId = roomId,
                serverHost = authority,
                roomUrl = "$scheme://$authority/call/$roomId"
            )
        } catch (_: Exception) {
            val roomId = trimmed.split("/").lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
            ResolvedRoomUrl(
                roomId = roomId,
                serverHost = config.serverHost,
                roomUrl = buildRoomUrl(config.serverHost, roomId)
            )
        }
    }

    private fun buildRoomUrl(serverHost: String, roomId: String): String {
        val scheme = if (isLocalHost(serverHost)) "http" else "https"
        return "$scheme://$serverHost/call/$roomId"
    }

    private fun isLocalHost(serverHost: String): Boolean {
        val normalized = serverHost.trim().lowercase()
        return normalized.startsWith("localhost") ||
            normalized.startsWith("127.") ||
            normalized.startsWith("10.0.2.2")
    }

    private data class ResolvedRoomUrl(
        val roomId: String,
        val serverHost: String,
        val roomUrl: String,
    )

    companion object {
        const val VERSION = "0.1.0"
    }
}

data class CreateRoomResult(
    val roomId: String,
    val roomUrl: String,
    val session: SerenadaSession,
)
