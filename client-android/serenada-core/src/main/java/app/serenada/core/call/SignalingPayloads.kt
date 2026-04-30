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
    val reconnectTokenTTLMs: Long?,
    val maxParticipants: Int?,
    val epoch: Long?,
    val reconnect: ReconnectOutcome?,
)

internal data class RoomStatePayload(
    val hostCid: String?,
    val participants: List<Participant>,
    val maxParticipants: Int?,
    val epoch: Long?,
)

internal data class ErrorPayload(
    val code: String?,
    val message: String?,
    val reason: String?,
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

internal data class RelayFailedPayload(
    val reason: String,
    val targets: List<String>,
    val of: String?,
)

internal data class NegotiationDirtyPayload(
    val withCid: String,
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
        reconnectTokenTTLMs = if (has("reconnectTokenTTLMs")) optLong("reconnectTokenTTLMs", 0).takeIf { it > 0 } else null,
        maxParticipants = optInt("maxParticipants", 0).takeIf { it > 0 },
        epoch = if (has("epoch")) optLong("epoch", -1).takeIf { it >= 0 } else null,
        reconnect = ReconnectOutcome.fromWireValue(optString("reconnect").ifBlank { null }),
    )
}

internal fun JSONObject?.toRoomStatePayload(): RoomStatePayload? {
    this ?: return null
    return RoomStatePayload(
        hostCid = optString("hostCid").ifBlank { null },
        participants = optJSONArray("participants").toParticipantList(),
        maxParticipants = optInt("maxParticipants", 0).takeIf { it > 0 },
        epoch = if (has("epoch")) optLong("epoch", -1).takeIf { it >= 0 } else null,
    )
}

internal fun JSONObject?.toErrorPayload(): ErrorPayload? {
    this ?: return null
    return ErrorPayload(
        code = optString("code").trim().ifBlank { null },
        message = optString("message").trim().ifBlank { null },
        reason = optString("reason").trim().ifBlank { null },
    )
}

internal fun JSONObject?.toRelayFailedPayload(): RelayFailedPayload? {
    this ?: return null
    val reason = optString("reason").ifBlank { return null }
    val targetsArray = optJSONArray("targets") ?: return null
    val targets = mutableListOf<String>()
    for (i in 0 until targetsArray.length()) {
        val item = targetsArray.optString(i)
        if (item.isNotBlank()) targets.add(item)
    }
    if (targets.isEmpty()) return null
    return RelayFailedPayload(
        reason = reason,
        targets = targets,
        of = optString("of").ifBlank { null },
    )
}

internal fun JSONObject?.toNegotiationDirtyPayload(): NegotiationDirtyPayload? {
    this ?: return null
    val withCid = optString("with").ifBlank { return null }
    return NegotiationDirtyPayload(withCid = withCid)
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
            val contentState = p.optJSONObject("contentState")?.toParticipantContentState()
            result.add(Participant(
                cid = cid,
                joinedAt = p.optLong("joinedAt").takeIf { it > 0 },
                displayName = p.optString("displayName").ifBlank { null },
                peerId = p.optString("peerId").ifBlank { null },
                audioEnabled = if (p.has("audioEnabled")) p.optBoolean("audioEnabled") else null,
                videoEnabled = if (p.has("videoEnabled")) p.optBoolean("videoEnabled") else null,
                signalingStatus = status,
                contentState = contentState,
            ))
        }
    }
    return result
}

private fun JSONObject.toParticipantContentState(): ParticipantContentState? {
    if (!has("active")) return null
    val active = optBoolean("active")
    val rawType = optString("contentType").ifBlank { null }
    return ParticipantContentState(
        active = active,
        contentType = if (active) rawType else null,
        updatedAtMs = if (has("updatedAtMs")) optLong("updatedAtMs", -1).takeIf { it >= 0 } else null,
        epoch = if (has("epoch")) optLong("epoch", -1).takeIf { it >= 0 } else null,
    )
}
