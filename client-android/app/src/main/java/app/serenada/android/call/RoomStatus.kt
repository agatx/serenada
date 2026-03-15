package app.serenada.android.call

import kotlin.math.max
import org.json.JSONObject

data class RoomStatus(
    val count: Int,
    val maxParticipants: Int? = null,
)

enum class RoomStatusIndicatorState {
    Hidden,
    Waiting,
    Full,
}

object RoomStatuses {
    private fun JSONObject.toMap(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = opt(key)
        }
        return result
    }

    private fun normalizeMaxParticipants(value: Int?, fallback: Int?): Int? {
        if (value != null && value >= 2) {
            return value
        }
        if (fallback != null && fallback >= 2) {
            return fallback
        }
        return null
    }

    private fun parseStatus(value: Any?, fallback: RoomStatus?): RoomStatus? {
        return when (value) {
            is Number ->
                RoomStatus(
                    count = max(0, value.toInt()),
                    maxParticipants = normalizeMaxParticipants(null, fallback?.maxParticipants)
                )

            is JSONObject -> {
                if (!value.has("count")) {
                    null
                } else {
                    val maxParticipants =
                        if (value.has("maxParticipants")) value.optInt("maxParticipants") else null
                    RoomStatus(
                        count = max(0, value.optInt("count", 0)),
                        maxParticipants = normalizeMaxParticipants(maxParticipants, fallback?.maxParticipants)
                    )
                }
            }

            is Map<*, *> -> {
                val count = (value["count"] as? Number)?.toInt() ?: return null
                val maxParticipants = (value["maxParticipants"] as? Number)?.toInt()
                RoomStatus(
                    count = max(0, count),
                    maxParticipants = normalizeMaxParticipants(maxParticipants, fallback?.maxParticipants)
                )
            }

            else -> null
        }
    }

    internal fun mergeStatusesPayload(
        previous: Map<String, RoomStatus>,
        payload: Map<String, Any?>?
    ): Map<String, RoomStatus> {
        if (payload == null) return previous

        val next = previous.toMutableMap()
        for ((rid, value) in payload) {
            val status = parseStatus(value, previous[rid]) ?: continue
            next[rid] = status
        }
        return next
    }

    fun mergeStatusesPayload(previous: Map<String, RoomStatus>, payload: JSONObject?): Map<String, RoomStatus> {
        return mergeStatusesPayload(previous = previous, payload = payload?.toMap())
    }

    internal fun mergeStatusUpdatePayload(
        previous: Map<String, RoomStatus>,
        payload: Map<String, Any?>?
    ): Map<String, RoomStatus> {
        if (payload == null) return previous

        val rid = (payload["rid"] as? String).orEmpty()
        val count = (payload["count"] as? Number)?.toInt() ?: return previous
        val maxParticipants = (payload["maxParticipants"] as? Number)?.toInt()
        if (rid.isBlank()) return previous

        return previous.toMutableMap().apply {
            this[rid] =
                RoomStatus(
                    count = max(0, count),
                    maxParticipants = normalizeMaxParticipants(maxParticipants, previous[rid]?.maxParticipants)
                )
        }
    }

    fun mergeStatusUpdatePayload(previous: Map<String, RoomStatus>, payload: JSONObject?): Map<String, RoomStatus> {
        return mergeStatusUpdatePayload(previous = previous, payload = payload?.toMap())
    }

    fun indicatorState(status: RoomStatus?): RoomStatusIndicatorState {
        val count = status?.count ?: 0
        if (count <= 0) {
            return RoomStatusIndicatorState.Hidden
        }

        val maxParticipants = normalizeMaxParticipants(status?.maxParticipants, 2) ?: 2
        return if (count >= maxParticipants) {
            RoomStatusIndicatorState.Full
        } else {
            RoomStatusIndicatorState.Waiting
        }
    }
}
