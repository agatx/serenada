package app.serenada.core.call

interface SessionAudioController {
    fun activate()
    fun deactivate()
    fun shouldPauseVideoForProximity(isScreenSharing: Boolean): Boolean
}
