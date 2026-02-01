package app.serenada.android

import android.app.Application
import app.serenada.android.call.CallManager

class SerenadaApp : Application() {
    lateinit var callManager: CallManager
        private set

    override fun onCreate() {
        super.onCreate()
        callManager = CallManager(this)
    }
}
