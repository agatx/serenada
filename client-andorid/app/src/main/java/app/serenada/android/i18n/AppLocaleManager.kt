package app.serenada.android.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.serenada.android.data.SettingsStore

object AppLocaleManager {
    fun applyLanguage(language: String) {
        val normalized = SettingsStore.normalizeLanguage(language)
        val locales = if (normalized == SettingsStore.LANGUAGE_AUTO) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(normalized)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
