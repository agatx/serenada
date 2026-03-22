package app.serenada.core.call

import android.os.Looper
import app.serenada.core.CallError
import org.json.JSONObject

/**
 * Routes inbound signaling messages to the appropriate handler.
 * Extracted from SerenadaSession to reduce its size; state ownership stays in the session.
 *
 * Follows the closure-injection DI pattern established by [PeerNegotiationEngine].
 */
class SignalingMessageRouter(
    // State readers
    private val getClientId: () -> String?,
    private val getHostCid: () -> String?,
    // Mutation callbacks
    private val onJoined: (clientId: String, hostCid: String?, roomState: RoomState?, turnToken: String?, turnTTL: Long?, reconnectToken: String?) -> Unit,
    private val onRoomStateUpdated: (RoomState) -> Unit,
    private val onError: (CallError) -> Unit,
    private val onRoomEnded: () -> Unit,
    private val onContentStateReceived: (fromCid: String, active: Boolean, contentType: String?) -> Unit,
    private val onTurnRefreshed: (SignalingMessage) -> Unit,
    private val onSignalingPayload: (SignalingMessage) -> Unit,
    private val onPong: () -> Unit,
    private val sendMessage: (type: String, payload: JSONObject?, to: String?) -> Unit,
    private val clearJoinTimers: () -> Unit,
    private val setJoinAcknowledged: () -> Unit,
) {
    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SignalingMessageRouter must be called on the main thread"
        }
    }

    fun processMessage(msg: SignalingMessage) {
        assertMainThread()
        when (msg.type) {
            "joined" -> handleJoined(msg)
            "room_state" -> handleRoomState(msg)
            "room_ended" -> onRoomEnded()
            "pong" -> onPong()
            "turn-refreshed" -> onTurnRefreshed(msg)
            "offer", "answer", "ice" -> onSignalingPayload(msg)
            "content_state" -> handleContentState(msg)
            "error" -> handleError(msg)
        }
    }

    fun broadcastContentState(active: Boolean, contentType: String? = null) {
        val payload = JSONObject().apply {
            put("active", active)
            if (active && contentType != null) put("contentType", contentType)
        }
        sendMessage("content_state", payload, null)
    }

    // --- Private handlers ---

    private fun handleJoined(msg: SignalingMessage) {
        clearJoinTimers()
        setJoinAcknowledged()

        val payload = msg.payload.toJoinedPayload()
        val cid = msg.cid ?: return

        val reconnectToken = payload?.reconnectToken
        val turnTTL = payload?.turnTokenTTLMs
        val turnToken = payload?.turnToken

        val roomState = parseRoomState(msg.payload)

        onJoined(cid, roomState?.hostCid, roomState, turnToken, turnTTL, reconnectToken)
    }

    private fun handleRoomState(msg: SignalingMessage) {
        clearJoinTimers()
        setJoinAcknowledged()

        val roomState = parseRoomState(msg.payload) ?: return
        onRoomStateUpdated(roomState)
    }

    private fun handleContentState(msg: SignalingMessage) {
        val payload = msg.payload.toContentStatePayload() ?: return
        onContentStateReceived(payload.fromCid, payload.active, payload.contentType)
    }

    private fun handleError(msg: SignalingMessage) {
        val payload = msg.payload.toErrorPayload()
        val callError = when (payload?.code) {
            "ROOM_CAPACITY_UNSUPPORTED", "ROOM_FULL" -> CallError.RoomFull
            "CONNECTION_FAILED" -> CallError.ConnectionFailed
            "JOIN_TIMEOUT" -> CallError.SignalingTimeout
            "ROOM_ENDED" -> CallError.RoomEnded
            else -> if (payload?.message != null) CallError.ServerError(payload.message)
            else CallError.Unknown("Unknown error")
        }
        onError(callError)
    }

    private fun parseRoomState(payload: JSONObject?): RoomState? {
        if (payload == null) return null
        val parsed = payload.toRoomStatePayload() ?: return null

        var resolved = parsed.hostCid ?: getHostCid() ?: getClientId()
        if (resolved != null && parsed.participants.isNotEmpty()) {
            if (resolved !in parsed.participants.map { it.cid }.toSet()) resolved = parsed.participants.firstOrNull()?.cid
        }
        if (resolved.isNullOrBlank()) return null
        return RoomState(hostCid = resolved, participants = parsed.participants, maxParticipants = parsed.maxParticipants)
    }
}
