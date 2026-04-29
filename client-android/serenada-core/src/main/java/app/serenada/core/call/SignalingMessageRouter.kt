package app.serenada.core.call

import android.os.Looper
import app.serenada.core.CallError
import app.serenada.core.ErrorEvent
import app.serenada.core.JoinedEvent
import app.serenada.core.PeerMessage
import app.serenada.core.RoomStateEvent
import org.json.JSONObject

/**
 * Routes inbound signaling messages to the appropriate handler.
 * Extracted from SerenadaSession to reduce its size; state ownership stays in the session.
 *
 * Follows the closure-injection DI pattern established by [PeerNegotiationEngine].
 */
internal data class RemoteMediaState(
    val audioEnabled: Boolean? = null,
    val videoEnabled: Boolean? = null,
)

internal class SignalingMessageRouter(
    // State readers
    private val getClientId: () -> String?,
    private val getHostCid: () -> String?,
    // Mutation callbacks
    private val onJoined: (clientId: String, hostCid: String?, roomState: RoomState?, turnToken: String?, turnTTL: Long?, reconnectToken: String?) -> Unit,
    private val onRoomStateUpdated: (RoomState) -> Unit,
    private val onError: (CallError) -> Unit,
    private val onRoomEnded: () -> Unit,
    private val onContentStateReceived: (fromCid: String, active: Boolean, contentType: String?) -> Unit,
    private val onMediaStateReceived: (fromCid: String, audioEnabled: Boolean?, videoEnabled: Boolean?) -> Unit,
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

    fun broadcastMediaState(audioEnabled: Boolean, videoEnabled: Boolean) {
        val payload = JSONObject().apply {
            put("audioEnabled", audioEnabled)
            put("videoEnabled", videoEnabled)
        }
        sendMessage("participant_media_state", payload, null)
    }

    // --- Direct-dispatch methods for provider events ---

    fun processJoinedEvent(event: JoinedEvent) {
        assertMainThread()
        clearJoinTimers()
        setJoinAcknowledged()

        val cid = event.peerId
        val participants = dedupeParticipants(
            event.participants.map { Participant(
                cid = it.peerId,
                joinedAt = it.joinedAt,
                displayName = it.displayName,
                peerId = it.appPeerId,
                audioEnabled = it.audioEnabled,
                videoEnabled = it.videoEnabled,
                signalingStatus = it.connectionStatus,
            ) },
            cid,
        )
        val hostPeerId = resolveHostPeerId(event.hostPeerId, participants, getHostCid(), cid)
        val roomState = if (!hostPeerId.isNullOrBlank()) {
            RoomState(hostCid = hostPeerId, participants = participants, maxParticipants = event.maxParticipants)
        } else null
        onJoined(cid, roomState?.hostCid, roomState, null, null, null)
    }

    fun processRoomStateEvent(event: RoomStateEvent) {
        assertMainThread()
        clearJoinTimers()
        setJoinAcknowledged()

        val localPeerId = getClientId()
        val participants = dedupeParticipants(
            event.participants.map { Participant(
                cid = it.peerId,
                joinedAt = it.joinedAt,
                displayName = it.displayName,
                peerId = it.appPeerId,
                audioEnabled = it.audioEnabled,
                videoEnabled = it.videoEnabled,
                signalingStatus = it.connectionStatus,
            ) },
            localPeerId,
        )
        val hostPeerId = resolveHostPeerId(event.hostPeerId, participants, getHostCid(), localPeerId)
        if (hostPeerId.isNullOrBlank()) return
        onRoomStateUpdated(RoomState(hostCid = hostPeerId, participants = participants, maxParticipants = event.maxParticipants))
    }

    fun processPeerMessage(message: PeerMessage) {
        assertMainThread()
        when (message.type) {
            "content_state" -> {
                val payload = message.payload
                val fromCid = payload?.optString("from")?.ifBlank { null } ?: message.from
                val active = payload?.optBoolean("active") ?: false
                val contentType = if (active) payload?.optString("contentType")?.ifBlank { null } else null
                onContentStateReceived(fromCid, active, contentType)
            }
            "participant_media_state" -> {
                val parsed = message.payload.toMediaStatePayload() ?: return
                onMediaStateReceived(parsed.fromCid, parsed.audioEnabled, parsed.videoEnabled)
            }
            "offer", "answer", "ice" -> {
                val base = message.payload ?: JSONObject()
                if (base.optString("from").isBlank()) base.put("from", message.from)
                onSignalingPayload(SignalingMessage(
                    type = message.type,
                    rid = null,
                    sid = null,
                    cid = message.from,
                    to = null,
                    payload = base,
                ))
            }
        }
    }

    fun processErrorEvent(event: ErrorEvent) {
        assertMainThread()
        onError(mapError(event.code, event.message))
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
        onError(mapError(payload?.code, payload?.message))
    }

    private fun mapError(code: String?, message: String?): CallError = when (code) {
        "ROOM_CAPACITY_UNSUPPORTED", "ROOM_FULL" -> CallError.RoomFull
        "CONNECTION_FAILED" -> CallError.ConnectionFailed
        "JOIN_TIMEOUT" -> CallError.SignalingTimeout
        "ROOM_ENDED" -> CallError.RoomEnded
        "INVALID_RECONNECT_TOKEN" -> CallError.SessionExpired
        else -> if (!message.isNullOrBlank()) CallError.ServerError(message)
        else CallError.Unknown("Unknown error")
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
