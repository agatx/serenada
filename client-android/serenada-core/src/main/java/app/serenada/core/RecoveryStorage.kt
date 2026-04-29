package app.serenada.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject

/**
 * Persisted call-recovery state — surfaced to host apps so a relaunched
 * process can prompt the user to rejoin an in-flight call instead of
 * silently dropping them on the home screen.
 *
 * Backed by app-private [SharedPreferences]; cleared on clean leave,
 * `room_ended`, or `INVALID_RECONNECT_TOKEN`.
 */
data class RecoveryRecord(
    val roomId: String,
    val cid: String,
    val reconnectToken: String,
    val lastEpoch: Long?,
    val sessionStartTs: Long,
    /**
     * Unix-ms after which the host app should NOT offer the rejoin prompt.
     * Computed as `now + reconnectTokenTTLMs` at write time so the SDK
     * does not need to know server clocks.
     */
    val expiresAtMs: Long,
)

/**
 * Lightweight, app-private SharedPreferences-backed store for recovery
 * records. The Android SDK keeps one `RecoveryStorage` instance per
 * `SerenadaCore`; the active session reads/writes via that instance.
 */
internal class RecoveryStorage(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): RecoveryRecord? {
        val raw = prefs.getString(KEY_RECORD, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val record = RecoveryRecord(
                roomId = json.getString("roomId"),
                cid = json.getString("cid"),
                reconnectToken = json.getString("reconnectToken"),
                lastEpoch = if (json.has("lastEpoch") && !json.isNull("lastEpoch"))
                    json.getLong("lastEpoch") else null,
                sessionStartTs = json.getLong("sessionStartTs"),
                expiresAtMs = json.getLong("expiresAtMs"),
            )
            if (System.currentTimeMillis() > record.expiresAtMs) {
                clear()
                return null
            }
            record
        } catch (_: JSONException) {
            clear()
            null
        }
    }

    fun save(record: RecoveryRecord) {
        val json = JSONObject().apply {
            put("roomId", record.roomId)
            put("cid", record.cid)
            put("reconnectToken", record.reconnectToken)
            if (record.lastEpoch != null) put("lastEpoch", record.lastEpoch)
            put("sessionStartTs", record.sessionStartTs)
            put("expiresAtMs", record.expiresAtMs)
        }
        prefs.edit().putString(KEY_RECORD, json.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_RECORD).apply()
    }

    companion object {
        private const val PREFS_NAME = "serenada_recovery"
        private const val KEY_RECORD = "record_v1"
    }
}
