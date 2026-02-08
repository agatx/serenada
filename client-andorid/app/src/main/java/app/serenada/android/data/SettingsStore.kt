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

    var language: String
        get() = normalizeLanguage(prefs.getString(KEY_LANGUAGE, LANGUAGE_AUTO))
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, normalizeLanguage(value)).apply()
        }

    companion object {
        const val DEFAULT_HOST = "serenada.app"
        const val LANGUAGE_AUTO = "auto"
        const val LANGUAGE_EN = "en"
        const val LANGUAGE_RU = "ru"
        const val LANGUAGE_ES = "es"
        const val LANGUAGE_FR = "fr"
        private const val KEY_HOST = "host"
        private const val KEY_RECONNECT_CID = "reconnect_cid"
        private const val KEY_LANGUAGE = "language"

        fun normalizeLanguage(value: String?): String =
            when (value) {
                LANGUAGE_AUTO, LANGUAGE_EN, LANGUAGE_RU, LANGUAGE_ES, LANGUAGE_FR -> value
                else -> LANGUAGE_AUTO
            }
    }
}
