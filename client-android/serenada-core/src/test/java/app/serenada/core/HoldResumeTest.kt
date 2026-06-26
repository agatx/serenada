package app.serenada.core

import app.serenada.core.call.AudioCoordinatorEvent
import app.serenada.core.call.AudioDevice
import app.serenada.core.call.AudioIntent
import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.MediaActivationState
import app.serenada.core.call.SerenadaAudioCoordinator
import app.serenada.core.fakes.TestSessionFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Phase 1 multi-call session primitives: session-internal hold/resume.
 *
 * Drives a single session to in-call (foreground/active), then exercises the
 * internal [SerenadaSession.applyHeldRoleInternal] /
 * [SerenadaSession.applyForegroundRoleInternal] mechanics and asserts the
 * media-engine suspend/resume, audio coordinator + controller toggling, remote
 * deafen, held broadcast ordering, and desired-intent restoration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HoldResumeTest {

    private lateinit var coordinator: CountingAudioCoordinator
    private lateinit var factory: TestSessionFactory

    private fun startInCall(): TestSessionFactory {
        coordinator = CountingAudioCoordinator()
        factory = TestSessionFactory(audioCoordinator = coordinator)
        factory.advanceToInCallWithTurn()
        ShadowLooper.idleMainLooper()
        return factory
    }

    @After
    fun tearDown() {
        if (::factory.isInitialized) factory.tearDown()
    }

    private fun hold() = runBlocking {
        factory.session.applyHeldRoleInternal()
        ShadowLooper.idleMainLooper()
    }

    private fun resume() = runBlocking {
        factory.session.applyForegroundRoleInternal()
        ShadowLooper.idleMainLooper()
    }

    private fun mediaStateBroadcasts() =
        factory.fakeProvider.sentMessages("participant_media_state")

    @Test
    fun `session starts foreground and active`() {
        startInCall()
        assertEquals(CallMediaRole.FOREGROUND, factory.session.mediaRoleForTest())
        assertEquals(MediaActivationState.ACTIVE, factory.session.mediaActivationStateForTest())
    }

    @Test
    fun `hold suspends local media`() {
        startInCall()
        val suspendsBefore = factory.fakeMedia.suspendLocalMediaForHoldCalls
        hold()
        assertEquals(suspendsBefore + 1, factory.fakeMedia.suspendLocalMediaForHoldCalls)
        assertEquals(CallMediaRole.HELD, factory.session.mediaRoleForTest())
        assertEquals(MediaActivationState.INACTIVE, factory.session.mediaActivationStateForTest())
    }

    @Test
    fun `hold deactivates audio controller and coordinator`() {
        startInCall()
        val controllerDeactivatesBefore = factory.fakeAudio.deactivateCalls
        val coordinatorDeactivatesBefore = coordinator.deactivateCalls
        hold()
        assertEquals(controllerDeactivatesBefore + 1, factory.fakeAudio.deactivateCalls)
        assertEquals(coordinatorDeactivatesBefore + 1, coordinator.deactivateCalls)
    }

    @Test
    fun `hold deafens remote audio playback`() {
        startInCall()
        hold()
        assertTrue(
            "Hold must disable remote playback",
            factory.fakeMedia.setRemotePlaybackEnabledCalls.contains(false),
        )
        assertEquals(false, factory.fakeMedia.setRemotePlaybackEnabledCalls.last())
    }

    @Test
    fun `hold broadcasts held true after capture stops with audio and video false`() {
        startInCall()
        hold()
        val last = mediaStateBroadcasts().last().payload!!
        assertTrue("held must be true", last.getBoolean("held"))
        assertFalse("audio must be false while held", last.getBoolean("audioEnabled"))
        assertFalse("video must be false while held", last.getBoolean("videoEnabled"))
    }

    @Test
    fun `hold is idempotent`() {
        startInCall()
        hold()
        val suspendsAfterFirst = factory.fakeMedia.suspendLocalMediaForHoldCalls
        val coordinatorDeactivatesAfterFirst = coordinator.deactivateCalls
        hold()
        assertEquals(suspendsAfterFirst, factory.fakeMedia.suspendLocalMediaForHoldCalls)
        assertEquals(coordinatorDeactivatesAfterFirst, coordinator.deactivateCalls)
    }

    @Test
    fun `hold sets actual published false but preserves desired intent`() {
        startInCall()
        // User intent: unmuted + video on (factory defaults defaultVideoEnabled true).
        val desiredAudio = factory.session.desiredAudioEnabledForTest()
        val desiredVideo = factory.session.desiredVideoModeForTest()
        hold()
        assertFalse(factory.session.actualAudioPublishedForTest())
        assertFalse(factory.session.actualVideoPublishedForTest())
        // Desired intent unchanged by hold.
        assertEquals(desiredAudio, factory.session.desiredAudioEnabledForTest())
        assertEquals(desiredVideo, factory.session.desiredVideoModeForTest())
    }

    @Test
    fun `resume reactivates audio controller and coordinator`() {
        startInCall()
        hold()
        val controllerActivatesBefore = factory.fakeAudio.activateCalls
        val coordinatorActivatesBefore = coordinator.activateCalls
        resume()
        assertEquals(controllerActivatesBefore + 1, factory.fakeAudio.activateCalls)
        assertEquals(coordinatorActivatesBefore + 1, coordinator.activateCalls)
        assertEquals(CallMediaRole.FOREGROUND, factory.session.mediaRoleForTest())
        assertEquals(MediaActivationState.ACTIVE, factory.session.mediaActivationStateForTest())
    }

    @Test
    fun `resume restores local media per desired intent`() {
        startInCall()
        val desiredAudio = factory.session.desiredAudioEnabledForTest()
        val desiredVideo = factory.session.desiredVideoModeForTest()
        hold()
        resume()
        val resumeCall = factory.fakeMedia.resumeLocalMediaFromHoldCalls.last()
        assertEquals(desiredAudio, resumeCall.first)
        assertEquals(desiredVideo, resumeCall.second)
    }

    @Test
    fun `resume re-enables remote audio playback`() {
        startInCall()
        hold()
        resume()
        assertEquals(true, factory.fakeMedia.setRemotePlaybackEnabledCalls.last())
    }

    @Test
    fun `resume broadcasts held false after media flows`() {
        startInCall()
        hold()
        resume()
        val last = mediaStateBroadcasts().last().payload!!
        assertFalse("held must be false after resume", last.getBoolean("held"))
    }

    @Test
    fun `resume after a muted hold restores muted intent`() {
        startInCall()
        // User mutes before holding; desired audio becomes false and must survive.
        factory.session.setMicMuted(true)
        ShadowLooper.idleMainLooper()
        assertFalse(factory.session.desiredAudioEnabledForTest())
        hold()
        resume()
        assertFalse(
            "resume must restore the muted intent",
            factory.session.desiredAudioEnabledForTest(),
        )
        assertEquals(false, factory.fakeMedia.resumeLocalMediaFromHoldCalls.last().first)
    }

    @Test
    fun `remote held decodes onto the remote participant`() {
        startInCall()
        factory.fakeProvider.simulateMessage(
            from = "remote-cid-1",
            type = "participant_media_state",
            payload = org.json.JSONObject().apply {
                put("from", "remote-cid-1")
                put("audioEnabled", false)
                put("videoEnabled", false)
                put("held", true)
            },
        )
        ShadowLooper.idleMainLooper()
        val remote = factory.session.state.value.remoteParticipants.firstOrNull { it.cid == "remote-cid-1" }
        assertTrue("remote participant should be marked held", remote?.held == true)
    }

    @Test
    fun `default single-call broadcast carries held false`() {
        startInCall()
        // The join handshake broadcasts media state with held=false for a normal
        // foreground call (additive; older peers ignore the field).
        val first = mediaStateBroadcasts().firstOrNull()
        assertNull(
            "no held broadcast should be true before any hold",
            mediaStateBroadcasts().firstOrNull { it.payload?.optBoolean("held") == true },
        )
        assertEquals(false, first?.payload?.getBoolean("held"))
    }
}

/**
 * Fast, deterministic fake [SerenadaAudioCoordinator] that counts activate /
 * deactivate calls and completes instantly, so hold/resume coordinator
 * interactions can be asserted without the real default coordinator's async
 * route/focus work.
 */
private class CountingAudioCoordinator : SerenadaAudioCoordinator {
    var activateCalls = 0
        private set
    var deactivateCalls = 0
        private set

    override suspend fun activateCallSession(intent: AudioIntent) { activateCalls += 1 }
    override suspend fun deactivateCallSession() { deactivateCalls += 1 }
    override suspend fun applyRouting(device: AudioDevice) {}
    override suspend fun setMicMuted(muted: Boolean) {}

    override val availableDevices: StateFlow<List<AudioDevice>> = MutableStateFlow(emptyList())
    override val effectiveInputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val effectiveOutputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val events: SharedFlow<AudioCoordinatorEvent> = MutableSharedFlow()
}
