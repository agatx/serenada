package app.serenada.android.data

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("serenada_settings", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) {
            val cleanValue = value.trim()
            if (cleanValue.isNotBlank()) {
                prefs.edit().putString(KEY_HOST, cleanValue).apply()
            }
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

    var pushEndpoint: String?
        get() = prefs.getString(KEY_PUSH_ENDPOINT, null)
        set(value) {
            val editor = prefs.edit()
            val normalized = value?.trim()
            if (normalized.isNullOrBlank()) {
                editor.remove(KEY_PUSH_ENDPOINT)
            } else {
                editor.putString(KEY_PUSH_ENDPOINT, normalized)
            }
            editor.apply()
        }

    var language: String
        get() = normalizeLanguage(prefs.getString(KEY_LANGUAGE, LANGUAGE_AUTO))
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, normalizeLanguage(value)).apply()
        }

    var isDefaultCameraEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_CAMERA_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DEFAULT_CAMERA_ENABLED, value).apply()
        }

    var isDefaultMicrophoneEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_MIC_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DEFAULT_MIC_ENABLED, value).apply()
        }

    var isHdVideoExperimentalEnabled: Boolean
        get() = prefs.getBoolean(KEY_HD_VIDEO_EXPERIMENTAL_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HD_VIDEO_EXPERIMENTAL_ENABLED, value).apply()
        }

    var areSavedRoomsShownFirst: Boolean
        get() = prefs.getBoolean(KEY_SAVED_ROOMS_SHOWN_FIRST, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SAVED_ROOMS_SHOWN_FIRST, value).apply()
        }

    var areRoomInviteNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROOM_INVITE_NOTIFICATIONS_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ROOM_INVITE_NOTIFICATIONS_ENABLED, value).apply()
        }


    companion object {
        const val DEFAULT_HOST = "serenada.app"
        const val HOST_RU = "serenada-app.ru"

        val PREDEFINED_HOSTS = listOf(DEFAULT_HOST, HOST_RU)

        const val LANGUAGE_AUTO = "auto"
        const val LANGUAGE_EN = "en"
        const val LANGUAGE_RU = "ru"
        const val LANGUAGE_ES = "es"
        const val LANGUAGE_FR = "fr"

        private const val KEY_HOST = "host"
        private const val KEY_RECONNECT_CID = "reconnect_cid"
        private const val KEY_PUSH_ENDPOINT = "push_endpoint"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_DEFAULT_CAMERA_ENABLED = "default_camera_enabled"
        private const val KEY_DEFAULT_MIC_ENABLED = "default_mic_enabled"
        private const val KEY_HD_VIDEO_EXPERIMENTAL_ENABLED = "hd_video_experimental_enabled"
        private const val KEY_SAVED_ROOMS_SHOWN_FIRST = "saved_rooms_shown_first"
        private const val KEY_ROOM_INVITE_NOTIFICATIONS_ENABLED = "room_invite_notifications_enabled"

        fun normalizeLanguage(value: String?): String =
            when (value) {
                LANGUAGE_AUTO, LANGUAGE_EN, LANGUAGE_RU, LANGUAGE_ES, LANGUAGE_FR -> value
                else -> LANGUAGE_AUTO
            }
    }
}
