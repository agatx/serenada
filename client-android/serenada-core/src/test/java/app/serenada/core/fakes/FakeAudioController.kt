package app.serenada.core.fakes

import app.serenada.core.call.SessionAudioController

class FakeAudioController : SessionAudioController {
    var activateCalls = 0
        private set
    var deactivateCalls = 0
        private set
    var proximityPausesVideo = false

    override fun activate() { activateCalls++ }
    override fun deactivate() { deactivateCalls++ }

    override fun shouldPauseVideoForProximity(isScreenSharing: Boolean): Boolean =
        proximityPausesVideo
}
