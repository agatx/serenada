package app.serenada.android.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class RecentCall(
    val roomId: String,
    val startTime: Long,
    val durationSeconds: Int
)

class RecentCallStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveCall(call: RecentCall) {
        if (!isValidRoomId(call.roomId)) return

        val history = getRecentCalls().toMutableList()
        history.removeAll { it.roomId == call.roomId }
        history.add(
            index = 0,
            element = call.copy(durationSeconds = call.durationSeconds.coerceAtLeast(0))
        )

        persist(history.take(MAX_RECENT_CALLS))
    }

    fun getRecentCalls(): List<RecentCall> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val parsed = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()

        val calls = mutableListOf<RecentCall>()
        for (i in 0 until parsed.length()) {
            val item = parsed.optJSONObject(i) ?: continue
            val roomId = item.optString("roomId").orEmpty()
            if (!isValidRoomId(roomId)) continue

            val startTime = item.optLong("startTime", 0L)
            val duration = item.optInt("duration", 0)
            if (startTime <= 0L) continue

            calls.add(
                RecentCall(
                    roomId = roomId,
                    startTime = startTime,
                    durationSeconds = duration.coerceAtLeast(0)
                )
            )
        }

        val deduped = calls.distinctBy { it.roomId }.take(MAX_RECENT_CALLS)
        if (deduped.size != calls.size) {
            persist(deduped)
        }

        return deduped
    }

    fun removeCall(roomId: String) {
        if (roomId.isBlank()) return
        val history = getRecentCalls()
        val filtered = history.filterNot { it.roomId == roomId }
        if (filtered.size == history.size) return
        persist(filtered)
    }

    private fun persist(calls: List<RecentCall>) {
        val json = JSONArray()
        calls.forEach { call ->
            json.put(
                JSONObject().apply {
                    put("roomId", call.roomId)
                    put("startTime", call.startTime)
                    put("duration", call.durationSeconds)
                }
            )
        }

        prefs.edit().putString(KEY_HISTORY, json.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "serenada_call_history"
        private const val KEY_HISTORY = "entries"
        private const val MAX_RECENT_CALLS = 3
        private val ROOM_ID_REGEX = Regex("^[A-Za-z0-9_-]{27}$")

        private fun isValidRoomId(roomId: String): Boolean = ROOM_ID_REGEX.matches(roomId)
    }
}
