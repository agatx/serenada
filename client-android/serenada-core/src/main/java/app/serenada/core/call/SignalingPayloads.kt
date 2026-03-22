package app.serenada.core.call

import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed payload data classes for inbound signaling messages.
 * Replaces raw JSONObject parsing scattered across SerenadaSession.
 */

data class JoinedPayload(
    val hostCid: String?,
    val participants: List<Participant>,
    val turnToken: String?,
    val turnTokenTTLMs: Long?,
    val reconnectToken: String?,
    val maxParticipants: Int?,
)

data class RoomStatePayload(
    val hostCid: String?,
    val participants: List<Participant>,
    val maxParticipants: Int?,
)

data class ErrorPayload(
    val code: String?,
    val message: String?,
)

data class ContentStatePayload(
    val fromCid: String,
    val active: Boolean,
    val contentType: String?,
)

// --- Extension parsers ---

fun JSONObject?.toJoinedPayload(): JoinedPayload? {
    this ?: return null
    return JoinedPayload(
        hostCid = optString("hostCid").ifBlank { null },
        participants = optJSONArray("participants").toParticipantList(),
        turnToken = optString("turnToken").ifBlank { null },
        turnTokenTTLMs = if (has("turnTokenTTLMs")) optLong("turnTokenTTLMs", 0).takeIf { it > 0 } else null,
        reconnectToken = optString("reconnectToken").ifBlank { null },
        maxParticipants = optInt("maxParticipants", 0).takeIf { it > 0 },
    )
}

fun JSONObject?.toRoomStatePayload(): RoomStatePayload? {
    this ?: return null
    return RoomStatePayload(
        hostCid = optString("hostCid").ifBlank { null },
        participants = optJSONArray("participants").toParticipantList(),
        maxParticipants = optInt("maxParticipants", 0).takeIf { it > 0 },
    )
}

fun JSONObject?.toErrorPayload(): ErrorPayload? {
    this ?: return null
    return ErrorPayload(
        code = optString("code").trim().ifBlank { null },
        message = optString("message").trim().ifBlank { null },
    )
}

fun JSONObject?.toContentStatePayload(): ContentStatePayload? {
    this ?: return null
    val fromCid = optString("from").ifBlank { return null }
    val active = optBoolean("active")
    val contentType = if (active) optString("contentType").ifBlank { null } else null
    return ContentStatePayload(
        fromCid = fromCid,
        active = active,
        contentType = contentType,
    )
}

// --- Helpers ---

fun JSONArray?.toParticipantList(): List<Participant> {
    this ?: return emptyList()
    val result = mutableListOf<Participant>()
    for (i in 0 until length()) {
        val p = optJSONObject(i) ?: continue
        val cid = p.optString("cid", "")
        if (cid.isNotBlank()) {
            result.add(Participant(cid, p.optLong("joinedAt").takeIf { it > 0 }))
        }
    }
    return result
}
