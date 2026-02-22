package app.serenada.android.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class SavedRoom(
    val roomId: String,
    val name: String,
    val createdAt: Long,
    val host: String? = null,
    val lastJoinedAt: Long? = null
)

class SavedRoomStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveRoom(room: SavedRoom) {
        if (!isValidRoomId(room.roomId)) return
        val cleanName = normalizeName(room.name) ?: return
        val cleanHost = normalizeHost(room.host)

        val rooms = getSavedRooms().toMutableList()
        val existing = rooms.firstOrNull { it.roomId == room.roomId }
        rooms.removeAll { it.roomId == room.roomId }
        rooms.add(
            index = 0,
            element = room.copy(
                name = cleanName,
                createdAt = room.createdAt.coerceAtLeast(1L),
                host = cleanHost,
                lastJoinedAt = (room.lastJoinedAt ?: existing?.lastJoinedAt)?.takeIf { it > 0L }
            )
        )
        persist(rooms.take(MAX_SAVED_ROOMS))
    }

    fun getSavedRooms(): List<SavedRoom> {
        val raw = prefs.getString(KEY_ROOMS, null) ?: return emptyList()
        val parsed = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()

        val rooms = mutableListOf<SavedRoom>()
        for (index in 0 until parsed.length()) {
            val item = parsed.optJSONObject(index) ?: continue
            val roomId = item.optString("roomId").orEmpty()
            if (!isValidRoomId(roomId)) continue

            val name = normalizeName(item.optString("name").orEmpty()) ?: continue
            val createdAt = item.optLong("createdAt", 0L).coerceAtLeast(1L)
            val host = normalizeHost(item.opt("host")?.toString())
            val lastJoinedAt = item.optLong("lastJoinedAt", 0L).takeIf { it > 0L }
            rooms.add(
                SavedRoom(
                    roomId = roomId,
                    name = name,
                    createdAt = createdAt,
                    host = host,
                    lastJoinedAt = lastJoinedAt
                )
            )
        }

        val deduped = rooms.distinctBy { it.roomId }.take(MAX_SAVED_ROOMS)
        if (deduped.size != rooms.size) {
            persist(deduped)
        }
        return deduped
    }

    fun removeRoom(roomId: String) {
        if (roomId.isBlank()) return
        val rooms = getSavedRooms()
        val filtered = rooms.filterNot { it.roomId == roomId }
        if (rooms.size == filtered.size) return
        persist(filtered)
    }

    fun markRoomJoined(roomId: String, joinedAt: Long = System.currentTimeMillis()): Boolean {
        if (roomId.isBlank()) return false
        val rooms = getSavedRooms().toMutableList()
        val index = rooms.indexOfFirst { it.roomId == roomId }
        if (index == -1) return false
        val cleanJoinedAt = joinedAt.coerceAtLeast(1L)
        val room = rooms[index]
        if (room.lastJoinedAt == cleanJoinedAt) return false
        rooms[index] = room.copy(lastJoinedAt = cleanJoinedAt)
        persist(rooms)
        return true
    }

    private fun persist(rooms: List<SavedRoom>) {
        val json = JSONArray()
        rooms.forEach { room ->
            json.put(
                JSONObject().apply {
                    put("roomId", room.roomId)
                    put("name", room.name)
                    put("createdAt", room.createdAt)
                    room.host?.let { put("host", it) }
                    room.lastJoinedAt?.let { put("lastJoinedAt", it) }
                }
            )
        }
        prefs.edit().putString(KEY_ROOMS, json.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "serenada_saved_rooms"
        private const val KEY_ROOMS = "entries"
        private const val MAX_SAVED_ROOMS = 50
        private const val MAX_ROOM_NAME_LENGTH = 120
        private val ROOM_ID_REGEX = Regex("^[A-Za-z0-9_-]{27}$")

        private fun normalizeName(name: String): String? {
            val trimmed = name.trim()
            if (trimmed.isBlank()) return null
            return trimmed.take(MAX_ROOM_NAME_LENGTH)
        }

        private fun normalizeHost(hostInput: String?): String? {
            val raw = hostInput?.trim().orEmpty()
            if (raw.isBlank()) return null
            val withScheme = if (raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)
            ) {
                raw
            } else {
                "https://$raw"
            }
            val parsed = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
            val host = parsed.host?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (host.isBlank()) return null
            if (!parsed.userInfo.isNullOrBlank()) return null
            if (!parsed.query.isNullOrBlank()) return null
            if (!parsed.fragment.isNullOrBlank()) return null
            val path = parsed.path
            if (!path.isNullOrBlank() && path != "/") return null
            val port = parsed.port
            if (port == -1) return host
            if (port <= 0 || port > 65535) return null
            return "$host:$port"
        }

        private fun isValidRoomId(roomId: String): Boolean = ROOM_ID_REGEX.matches(roomId)
    }
}
