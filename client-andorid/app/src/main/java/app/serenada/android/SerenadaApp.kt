package app.serenada.android

import android.app.Application
import app.serenada.android.call.CallManager
import app.serenada.android.data.SettingsStore
import app.serenada.android.i18n.AppLocaleManager

class SerenadaApp : Application() {
    lateinit var callManager: CallManager
        private set

    override fun onCreate() {
        super.onCreate()
        AppLocaleManager.applyLanguage(SettingsStore(this).language)
        callManager = CallManager(this)
    }
}
