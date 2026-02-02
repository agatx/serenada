package app.serenada.android.data

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("serenada_settings", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) {
            prefs.edit().putString(KEY_HOST, value.trim()).apply()
        }

    var reconnectCid: String?
        get() = prefs.getString(KEY_RECONNECT_CID, null)
        set(value) {
            val editor = prefs.edit()
            if (value.isNullOrBlank()) {
                editor.remove(KEY_RECONNECT_CID)
            } else {
                editor.putString(KEY_RECONNECT_CID, value)
            }
            editor.apply()
        }

    companion object {
        const val DEFAULT_HOST = "serenada.app"
        private const val KEY_HOST = "host"
        private const val KEY_RECONNECT_CID = "reconnect_cid"
    }
}
