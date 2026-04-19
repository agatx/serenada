package app.serenada.core.call

import app.serenada.core.ParticipantSignalingStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed payload data classes for inbound signaling messages.
 * Replaces raw JSONObject parsing scattered across SerenadaSession.
 */

internal data class JoinedPayload(
    val hostCid: String?,
    val participants: List<Participant>,
    val turnToken: String?,
    val turnTokenTTLMs: Long?,
    val reconnectToken: String?,
    val maxParticipants: Int?,
)

internal data class RoomStatePayload(
    val hostCid: String?,
    val participants: List<Participant>,
    val maxParticipants: Int?,
)

internal data class ErrorPayload(
    val code: String?,
    val message: String?,
)

internal data class ContentStatePayload(
    val fromCid: String,
    val active: Boolean,
    val contentType: String?,
)

internal data class MediaStatePayload(
    val fromCid: String,
    val audioEnabled: Boolean?,
    val videoEnabled: Boolean?,
)

// --- Extension parsers ---

internal fun JSONObject?.toJoinedPayload(): JoinedPayload? {
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

internal fun JSONObject?.toRoomStatePayload(): RoomStatePayload? {
    this ?: return null
    return RoomStatePayload(
        hostCid = optString("hostCid").ifBlank { null },
        participants = optJSONArray("participants").toParticipantList(),
        maxParticipants = optInt("maxParticipants", 0).takeIf { it > 0 },
    )
}

internal fun JSONObject?.toErrorPayload(): ErrorPayload? {
    this ?: return null
    return ErrorPayload(
        code = optString("code").trim().ifBlank { null },
        message = optString("message").trim().ifBlank { null },
    )
}

internal fun JSONObject?.toContentStatePayload(): ContentStatePayload? {
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

internal fun JSONObject?.toMediaStatePayload(): MediaStatePayload? {
    this ?: return null
    val fromCid = optString("from").ifBlank { return null }
    return MediaStatePayload(
        fromCid = fromCid,
        audioEnabled = if (has("audioEnabled")) optBoolean("audioEnabled") else null,
        videoEnabled = if (has("videoEnabled")) optBoolean("videoEnabled") else null,
    )
}

// --- Helpers ---

internal fun JSONArray?.toParticipantList(): List<Participant> {
    this ?: return emptyList()
    val result = mutableListOf<Participant>()
    for (i in 0 until length()) {
        val p = optJSONObject(i) ?: continue
        val cid = p.optString("cid", "")
        if (cid.isNotBlank()) {
            val statusString = p.optString("connectionStatus").ifBlank { null }
            // Unknown status values fall back to ACTIVE for forward compat.
            val status = if (statusString == "suspended") {
                ParticipantSignalingStatus.SUSPENDED
            } else {
                ParticipantSignalingStatus.ACTIVE
            }
            result.add(Participant(
                cid = cid,
                joinedAt = p.optLong("joinedAt").takeIf { it > 0 },
                displayName = p.optString("displayName").ifBlank { null },
                audioEnabled = if (p.has("audioEnabled")) p.optBoolean("audioEnabled") else null,
                videoEnabled = if (p.has("videoEnabled")) p.optBoolean("videoEnabled") else null,
                signalingStatus = status,
            ))
        }
    }
    return result
}
