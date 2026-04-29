package app.serenada.core

import android.content.Context
import android.os.Handler
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
    /** SDK configuration. */
    val config: SerenadaConfig,
    private val context: Context,
) {
    /** Callback delegate for session lifecycle events. */
    var delegate: SerenadaCoreDelegate? = null

    /** Logger instance for debug output. */
    var logger: SerenadaLogger? = null

    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = CoreApiClient(okHttpClient)
    private val resolvedConfig = resolveSerenadaConfig(config)

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SerenadaCore APIs must be called on the main thread"
        }
    }

    /**
     * Join a call using a full URL (e.g., "https://serenada.app/call/ABC123").
     *
     * @param peerId optional host-supplied stable identity for this user (distinct from
     *   the per-call client ID). Surfaced on remote participants so the call UI can
     *   resolve avatars via [SerenadaCallFlowConfig.avatarProvider].
     */
    fun join(url: String, displayName: String? = null, peerId: String? = null): SerenadaSession {
        assertMainThread()
        val resolved = resolveRoomUrl(url)
        val roomId = resolved?.roomId ?: url
        val sessionConfig = sessionConfigFor(resolved?.serverHost)
        val session = SerenadaSession(
            roomId = roomId,
            roomUrl = resolved?.roomUrl ?: url,
            config = sessionConfig,
            context = context,
            delegate = { delegate },
            okHttpClient = okHttpClient,
            initialSignalingProvider = createSignalingProvider(sessionConfig),
            logger = logger,
            displayName = displayName,
            peerId = peerId,
        )
        session.start()
        return session
    }

    /**
     * Join a call using a room ID.
     *
     * @param peerId optional host-supplied stable identity — see the URL [join] overload.
     */
    fun join(
        roomId: String,
        serverHost: String? = resolvedConfig.serverHost,
        displayName: String? = null,
        peerId: String? = null,
    ): SerenadaSession {
        assertMainThread()
        val sessionConfig = sessionConfigFor(serverHost)
        val roomUrl = resolvedConfig.serverHost?.let { buildRoomUrl(serverHost ?: it, roomId) }
        val session = SerenadaSession(
            roomId = roomId,
            roomUrl = roomUrl,
            config = sessionConfig,
            context = context,
            delegate = { delegate },
            okHttpClient = okHttpClient,
            initialSignalingProvider = createSignalingProvider(sessionConfig),
            logger = logger,
            displayName = displayName,
            peerId = peerId,
        )
        session.start()
        return session
    }

    /**
     * Create a new room. Returns the room URL and ID. Call [join] to start the call.
     */
    suspend fun createRoom(): CreateRoomResult {
        assertMainThread()
        val serverHost = requireServerHost(config)
        val roomId = suspendCancellableCoroutine<String> { continuation ->
            apiClient.createRoomId(serverHost) { result ->
                result
                    .onSuccess { resolvedRoomId ->
                        continuation.resume(resolvedRoomId)
                    }
                    .onFailure { error ->
                        continuation.resumeWithException(error)
                    }
            }
        }

        val roomUrl = buildRoomUrl(serverHost, roomId)
        return CreateRoomResult(roomId = roomId, roomUrl = roomUrl)
    }

    /**
     * Create a room ID without starting a session.
     * Use this when you only need a room ID (e.g., for invite links).
     */
    suspend fun createRoomId(): String {
        assertMainThread()
        val serverHost = requireServerHost(config)
        return suspendCancellableCoroutine { continuation ->
            apiClient.createRoomId(serverHost) { result ->
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
            val fallbackHost = resolvedConfig.serverHost ?: return null
            ResolvedRoomUrl(
                roomId = roomId,
                serverHost = fallbackHost,
                roomUrl = buildRoomUrl(fallbackHost, roomId)
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

    private fun sessionConfigFor(serverHostOverride: String?): SerenadaConfig {
        if (resolvedConfig.serverHost == null) {
            return config
        }
        val serverHost = serverHostOverride?.trim()?.takeIf { it.isNotEmpty() } ?: resolvedConfig.serverHost
        return config.copy(serverHost = serverHost, signalingProvider = null)
    }

    private fun createSignalingProvider(sessionConfig: SerenadaConfig): SignalingProvider {
        val resolved = resolveSerenadaConfig(sessionConfig)
        val serverHost = resolved.serverHost
        return if (serverHost != null) {
            SerenadaServerProvider(
                serverHost = serverHost,
                handler = Handler(Looper.getMainLooper()),
                okHttpClient = okHttpClient,
                apiClient = apiClient,
                transports = sessionConfig.transports,
                logger = logger,
            )
        } else {
            resolved.signalingProvider ?: throw IllegalStateException("Provide exactly one of serverHost or signalingProvider")
        }
    }

    companion object {
        const val VERSION = "0.3.0"
    }
}

data class CreateRoomResult(
    val roomId: String,
    val roomUrl: String,
)
