package app.serenada.core

import android.content.Context
import app.serenada.core.network.CoreApiClient
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

    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = CoreApiClient(okHttpClient)

    /**
     * Join a call using a full URL (e.g., "https://serenada.app/call/ABC123").
     */
    fun join(url: String): SerenadaSession {
        val roomId = extractRoomIdFromUrl(url) ?: url
        val session = SerenadaSession(
            roomId = roomId,
            roomUrl = url,
            serverHost = config.serverHost,
            config = config,
            context = context,
            delegate = { delegate },
        )
        session.start()
        return session
    }

    /**
     * Join a call using a room ID.
     */
    fun join(roomId: String, serverHost: String = config.serverHost): SerenadaSession {
        val session = SerenadaSession(
            roomId = roomId,
            roomUrl = null,
            serverHost = serverHost,
            config = config,
            context = context,
            delegate = { delegate },
        )
        session.start()
        return session
    }

    /**
     * Create a new room and immediately join it.
     */
    fun createRoom(callback: (CreateRoomResult) -> Unit) {
        apiClient.createRoomId(config.serverHost) { result ->
            result
                .onSuccess { roomId ->
                    val session = SerenadaSession(
                        roomId = roomId,
                        roomUrl = "https://${config.serverHost}/call/$roomId",
                        serverHost = config.serverHost,
                        config = config,
                        context = context,
                        delegate = { delegate },
                    )
                    session.start()
                    callback(CreateRoomResult(roomId = roomId, session = session))
                }
                .onFailure { error ->
                    callback(CreateRoomResult(roomId = null, session = null, error = error))
                }
        }
    }

    private fun extractRoomIdFromUrl(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.contains("/")) return null
        return trimmed.split("/").lastOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val VERSION = "0.1.0"
    }
}

data class CreateRoomResult(
    val roomId: String?,
    val session: SerenadaSession?,
    val error: Throwable? = null,
)
