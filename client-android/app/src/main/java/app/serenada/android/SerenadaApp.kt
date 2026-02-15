package app.serenada.android

import android.app.Application
import app.serenada.android.call.CallManager
import app.serenada.android.data.SettingsStore
import app.serenada.android.i18n.AppLocaleManager
import app.serenada.android.push.FirebasePushInitializer

class SerenadaApp : Application() {
    val callManager: CallManager by lazy { CallManager(this) }

    override fun onCreate() {
        super.onCreate()
        AppLocaleManager.applyLanguage(SettingsStore(this).language)
        FirebasePushInitializer.ensureInitialized(this)
    }
}
