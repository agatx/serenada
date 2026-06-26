package app.serenada.core

import app.serenada.core.call.AudioCoordinatorEvent
import app.serenada.core.call.AudioDevice
import app.serenada.core.call.AudioIntent
import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.LocalCameraMode
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * FIX N1 — held-toggle guard (Core Invariant 2: a held call owns NO capture).
 *
 * While `mediaRole == HELD`, every user media toggle (setMicMuted / toggleAudio /
 * toggleVideo / setCameraMode / flipCamera) must update `desired*` intent ONLY:
 * no capture, no camera restart, no `participant_media_state` broadcast, and
 * `actual*` stays false. Resume (applyForegroundRoleInternal) then applies the
 * latest desired intent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeldToggleGuardTest {

    private lateinit var factory: TestSessionFactory

    private fun startInCallHeld(): TestSessionFactory {
        factory = TestSessionFactory(
            audioCoordinator = InstantAudioCoordinator(),
            // Two non-composite modes so a held flip has a next mode to advance to.
            cameraModes = listOf(LocalCameraMode.SELFIE, LocalCameraMode.WORLD),
        )
        factory.advanceToInCallWithTurn()
        ShadowLooper.idleMainLooper()
        hold()
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
    fun `mic mute toggles while held update desired only - no capture or broadcast`() {
        startInCallHeld()
        val toggleAudioBefore = factory.fakeMedia.toggleAudioCalls.size
        val broadcastsBefore = mediaStateBroadcasts().size

        // Unmute while held: desired audio flips true, but no capture / broadcast.
        factory.session.setMicMuted(false)
        ShadowLooper.idleMainLooper()

        assertTrue(
            "held unmute must record desired audio intent",
            factory.session.desiredAudioEnabledForTest(),
        )
        assertEquals(
            "held unmute must not toggle audio capture",
            toggleAudioBefore,
            factory.fakeMedia.toggleAudioCalls.size,
        )
        assertEquals(
            "held unmute must not broadcast media state",
            broadcastsBefore,
            mediaStateBroadcasts().size,
        )
        assertFalse("actual audio stays false while held", factory.session.actualAudioPublishedForTest())
        assertEquals(CallMediaRole.HELD, factory.session.mediaRoleForTest())
    }

    @Test
    fun `toggleAudio while held flips desired relative to intent`() {
        startInCallHeld()
        // Default desired audio is true; a held toggle must flip it to false.
        assertTrue(factory.session.desiredAudioEnabledForTest())
        factory.session.toggleAudio()
        ShadowLooper.idleMainLooper()
        assertFalse(
            "held toggleAudio must flip desired audio to false",
            factory.session.desiredAudioEnabledForTest(),
        )
        // And again back to true.
        factory.session.toggleAudio()
        ShadowLooper.idleMainLooper()
        assertTrue(factory.session.desiredAudioEnabledForTest())
    }

    @Test
    fun `video toggle while held updates desired only - no camera restart or broadcast`() {
        startInCallHeld()
        val toggleVideoBefore = factory.fakeMedia.toggleVideoCalls.size
        val broadcastsBefore = mediaStateBroadcasts().size
        // Default desired video is on (SELFIE). A held toggle turns it OFF.
        assertEquals(LocalCameraMode.SELFIE, factory.session.desiredVideoModeForTest())

        factory.session.toggleVideo()
        ShadowLooper.idleMainLooper()

        assertEquals(
            "held video off must record desired video mode off",
            null,
            factory.session.desiredVideoModeForTest(),
        )
        assertEquals(
            "held video toggle must not restart the camera",
            toggleVideoBefore,
            factory.fakeMedia.toggleVideoCalls.size,
        )
        assertEquals(
            "held video toggle must not broadcast media state",
            broadcastsBefore,
            mediaStateBroadcasts().size,
        )
        assertFalse("actual video stays false while held", factory.session.actualVideoPublishedForTest())

        // Toggle back ON while held: desired restores to a camera mode, still no restart.
        factory.session.toggleVideo()
        ShadowLooper.idleMainLooper()
        assertTrue(
            "held video on must record a desired camera mode",
            factory.session.desiredVideoModeForTest() != null,
        )
        assertEquals(
            "held video toggle must still not restart the camera",
            toggleVideoBefore,
            factory.fakeMedia.toggleVideoCalls.size,
        )
    }

    @Test
    fun `flipCamera while held advances desired mode only - no engine flip or broadcast`() {
        startInCallHeld()
        val flipsBefore = factory.fakeMedia.flipCameraCalls
        val broadcastsBefore = mediaStateBroadcasts().size
        assertEquals(LocalCameraMode.SELFIE, factory.session.desiredVideoModeForTest())

        factory.session.flipCamera()
        ShadowLooper.idleMainLooper()

        assertEquals(
            "held flip must advance the desired camera mode",
            LocalCameraMode.WORLD,
            factory.session.desiredVideoModeForTest(),
        )
        assertEquals(
            "held flip must not flip the engine camera",
            flipsBefore,
            factory.fakeMedia.flipCameraCalls,
        )
        assertEquals(
            "held flip must not broadcast media state",
            broadcastsBefore,
            mediaStateBroadcasts().size,
        )
    }

    @Test
    fun `setCameraMode while held records desired mode only - no engine flip`() {
        startInCallHeld()
        val flipsBefore = factory.fakeMedia.flipCameraCalls
        assertEquals(LocalCameraMode.SELFIE, factory.session.desiredVideoModeForTest())

        factory.session.setCameraMode(LocalCameraMode.WORLD)
        ShadowLooper.idleMainLooper()

        assertEquals(
            "held setCameraMode must record the desired mode",
            LocalCameraMode.WORLD,
            factory.session.desiredVideoModeForTest(),
        )
        assertEquals(
            "held setCameraMode must not flip the engine camera",
            flipsBefore,
            factory.fakeMedia.flipCameraCalls,
        )
    }

    @Test
    fun `held toggles are applied on resume`() {
        startInCallHeld()
        // Mutate intent while held: mute audio + turn video off.
        factory.session.setMicMuted(true)
        factory.session.toggleVideo()
        ShadowLooper.idleMainLooper()
        assertFalse(factory.session.desiredAudioEnabledForTest())
        assertEquals(null, factory.session.desiredVideoModeForTest())

        resume()

        // Resume applies the LATEST desired intent (muted, video off).
        val resumeCall = factory.fakeMedia.resumeLocalMediaFromHoldCalls.last()
        assertEquals("resume must apply held audio intent (muted)", false, resumeCall.first)
        assertEquals("resume must apply held video intent (off)", null, resumeCall.second)
        assertEquals(CallMediaRole.FOREGROUND, factory.session.mediaRoleForTest())
    }

    @Test
    fun `held broadcast always sends audio and video false regardless of state`() {
        startInCallHeld()
        // The hold broadcast (held=true) must carry audio=false/video=false.
        val last = mediaStateBroadcasts().last().payload!!
        assertTrue("held must be true", last.getBoolean("held"))
        assertFalse("audio false while held", last.getBoolean("audioEnabled"))
        assertFalse("video false while held", last.getBoolean("videoEnabled"))
    }
}

/**
 * Audio coordinator that completes activate/deactivate instantly (no async route
 * work), so held-toggle assertions are deterministic.
 */
private class InstantAudioCoordinator : SerenadaAudioCoordinator {
    override suspend fun activateCallSession(intent: AudioIntent) {}
    override suspend fun deactivateCallSession() {}
    override suspend fun applyRouting(device: AudioDevice) {}
    override suspend fun setMicMuted(muted: Boolean) {}

    override val availableDevices: StateFlow<List<AudioDevice>> = MutableStateFlow(emptyList())
    override val effectiveInputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val effectiveOutputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val events: SharedFlow<AudioCoordinatorEvent> = MutableSharedFlow()
}
