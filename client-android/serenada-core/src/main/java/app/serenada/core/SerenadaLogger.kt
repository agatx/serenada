package app.serenada.core

import android.util.Log

enum class SerenadaLogLevel { DEBUG, INFO, WARNING, ERROR }

interface SerenadaLogger {
    fun log(level: SerenadaLogLevel, tag: String, message: String)
}

class AndroidSerenadaLogger : SerenadaLogger {
    override fun log(level: SerenadaLogLevel, tag: String, message: String) {
        when (level) {
            SerenadaLogLevel.DEBUG -> Log.d(tag, message)
            SerenadaLogLevel.INFO -> Log.i(tag, message)
            SerenadaLogLevel.WARNING -> Log.w(tag, message)
            SerenadaLogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
