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
import org.junit.Assert.assertNotNull
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

    private fun startInCall(handlesReconnection: Boolean = false): TestSessionFactory {
        coordinator = CountingAudioCoordinator()
        factory = TestSessionFactory(
            handlesReconnection = handlesReconnection,
            audioCoordinator = coordinator,
        )
        if (handlesReconnection) {
            factory.advanceToInCallWithTurn(
                localCid = "alpha",
                remoteCid = "remote",
                localJoinedAt = 1,
                remoteJoinedAt = 2,
            )
        } else {
            factory.advanceToInCallWithTurn()
        }
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
    fun `muted held call resumes muted without recreating the mic and broadcasts audioEnabled false`() {
        startInCall()
        // User mutes, then holds. The held call had a live mic capture before mute
        // toggling; after suspend the engine releases it.
        factory.session.setMicMuted(true)
        ShadowLooper.idleMainLooper()
        hold()
        assertFalse(
            "mic capture must be released while held",
            factory.fakeMedia.micCaptureTrackPresent,
        )
        val recreatesBefore = factory.fakeMedia.micCaptureRecreateCount

        resume()

        // FIX A1: a muted resume must NOT recreate the mic capture (OS mic
        // indicator stays off) and the sender track stays released.
        assertEquals(
            "muted resume must not recreate the mic capture",
            recreatesBefore,
            factory.fakeMedia.micCaptureRecreateCount,
        )
        assertFalse(
            "mic capture must stay released after a muted resume",
            factory.fakeMedia.micCaptureTrackPresent,
        )
        // The resume broadcast must derive audioEnabled from desired intent + route
        // (false here), not from track presence.
        val last = mediaStateBroadcasts().last().payload!!
        assertFalse("held must be false after resume", last.getBoolean("held"))
        assertFalse(
            "resumed broadcast audioEnabled must be false for a muted call",
            last.getBoolean("audioEnabled"),
        )
    }

    @Test
    fun `unmuted held call recreates the mic on resume`() {
        startInCall()
        // Control case: an unmuted call resumes with the mic recreated.
        hold()
        assertFalse(factory.fakeMedia.micCaptureTrackPresent)
        val recreatesBefore = factory.fakeMedia.micCaptureRecreateCount
        resume()
        assertEquals(
            "unmuted resume must recreate the mic capture",
            recreatesBefore + 1,
            factory.fakeMedia.micCaptureRecreateCount,
        )
        assertTrue(factory.fakeMedia.micCaptureTrackPresent)
    }

    @Test
    fun `post-reconnect resync re-broadcasts held while held`() {
        startInCall(handlesReconnection = true)
        hold()
        val broadcastsBefore = mediaStateBroadcasts().size

        // Reconnect: disconnect, reconnect, then the authoritative room_state
        // snapshot flushes the post-reconnect resync gate.
        factory.fakeProvider.simulateDisconnected()
        ShadowLooper.idleMainLooper()
        factory.fakeProvider.simulateConnected("ws")
        ShadowLooper.idleMainLooper()
        factory.simulateRoomState(
            participants = listOf("alpha" to 1L, "remote" to 2L),
            hostCid = "alpha",
        )

        val broadcasts = mediaStateBroadcasts()
        assertTrue(
            "resync must re-broadcast media state while held",
            broadcasts.size > broadcastsBefore,
        )
        val last = broadcasts.last().payload!!
        assertTrue(
            "post-reconnect resync re-broadcast must include held=true",
            last.getBoolean("held"),
        )
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
    fun `startScreenShare while held is a no-op - no projection start and no broadcast`() {
        // FIX M1 (Core Invariant 2): a held session owns no screen share. A
        // startScreenShare while held must NOT start MediaProjection capture and
        // must NOT broadcast content / participant_media_state.
        startInCall()
        hold()
        val startsBefore = factory.fakeMedia.startScreenShareCalls
        val broadcastsBefore = mediaStateBroadcasts().size
        val contentBefore = factory.fakeProvider.sentMessages("content_state").size

        factory.session.startScreenShare(android.content.Intent())
        ShadowLooper.idleMainLooper()

        assertEquals(
            "held startScreenShare must not start MediaProjection capture",
            startsBefore,
            factory.fakeMedia.startScreenShareCalls,
        )
        assertEquals(
            "held startScreenShare must not broadcast media state",
            broadcastsBefore,
            mediaStateBroadcasts().size,
        )
        assertEquals(
            "held startScreenShare must not broadcast content state",
            contentBefore,
            factory.fakeProvider.sentMessages("content_state").size,
        )
        assertFalse(
            "held startScreenShare must not flip the screen-sharing diagnostic",
            factory.session.diagnostics.value.isScreenSharing,
        )
        assertEquals(CallMediaRole.HELD, factory.session.mediaRoleForTest())
    }

    @Test
    fun `resume emits a single held false with no intermediate held true`() {
        // FIX M3: resume must commit FOREGROUND before the media-applying
        // broadcasts (and suppress the intermediate ones), so peers see exactly
        // one held=false after capture is reacquired and never a redundant
        // held=true emitted while media is mid-resume.
        startInCall()
        hold()
        val broadcastsBeforeResume = mediaStateBroadcasts().size

        resume()

        val resumeBroadcasts = mediaStateBroadcasts().drop(broadcastsBeforeResume)
        assertEquals(
            "resume must emit exactly one participant_media_state broadcast",
            1,
            resumeBroadcasts.size,
        )
        val only = resumeBroadcasts.single().payload!!
        assertFalse("resume broadcast must be held=false", only.getBoolean("held"))
        assertTrue(
            "resume must emit no held=true after capture is reacquired",
            resumeBroadcasts.none { it.payload?.optBoolean("held") == true },
        )
    }

    @Test
    fun `toggleVideo on while held does not request permission and records desired only`() {
        // FIX A-1: while held this session owns no capture (Core Invariant 2).
        // Toggling video ON must handle held FIRST — record the desired intent
        // ONLY, with NO permission prompt, NO camera restart, NO broadcast. The
        // previous order checked/requested camera permission BEFORE the held guard,
        // which could surface a prompt and return before recording intent.
        startInCall()
        // Force video OFF intent first so the toggle requests ON.
        factory.session.toggleVideo()
        ShadowLooper.idleMainLooper()
        assertNull("video intent should be off before re-enabling", factory.session.desiredVideoModeForTest())

        hold()

        // Revoke camera permission so the PRE-FIX order (permission check before
        // the held guard) would have surfaced a prompt here. The held-first guard
        // must skip it entirely.
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
            .denyPermissions(android.Manifest.permission.CAMERA)

        val toggleVideoBefore = factory.fakeMedia.toggleVideoCalls.size
        val broadcastsBefore = mediaStateBroadcasts().size
        var permissionsRequested = false
        factory.session.onPermissionsRequired = { permissionsRequested = true }

        // Toggle video ON while held.
        factory.session.toggleVideo()
        ShadowLooper.idleMainLooper()

        assertFalse(
            "toggleVideo while held must NOT request camera permission",
            permissionsRequested,
        )
        assertEquals(
            "toggleVideo while held must NOT restart camera capture",
            toggleVideoBefore,
            factory.fakeMedia.toggleVideoCalls.size,
        )
        assertEquals(
            "toggleVideo while held must NOT broadcast media state",
            broadcastsBefore,
            mediaStateBroadcasts().size,
        )
        // Desired intent IS recorded (resume will apply it).
        assertNotNull(
            "toggleVideo while held must record the desired video intent",
            factory.session.desiredVideoModeForTest(),
        )
        assertEquals(CallMediaRole.HELD, factory.session.mediaRoleForTest())
    }

    @Test
    fun `audio-environment callback while held does not restart camera or re-enable mic`() {
        // FIX A-2: the media-applying sinks (applyLocalVideoPreference /
        // updateEffectiveMicState) are reachable from audio-environment callbacks
        // (proximity / route change) that fire OUTSIDE the user-toggle guards. A
        // held call must own NO capture via ANY path — the sink itself must refuse
        // to restart camera capture or re-enable mic capture while held.
        startInCall()
        hold()

        val toggleVideoBefore = factory.fakeMedia.toggleVideoCalls.size
        val toggleAudioBefore = factory.fakeMedia.toggleAudioCalls.size
        val broadcastsBefore = mediaStateBroadcasts().size

        // Simulate an audio-environment callback reaching both sinks while held.
        factory.session.applyLocalVideoPreferenceForTest()
        factory.session.updateEffectiveMicStateForTest()
        ShadowLooper.idleMainLooper()

        assertEquals(
            "held audio-environment callback must NOT restart camera capture",
            toggleVideoBefore,
            factory.fakeMedia.toggleVideoCalls.size,
        )
        assertEquals(
            "held audio-environment callback must NOT re-enable mic capture",
            toggleAudioBefore,
            factory.fakeMedia.toggleAudioCalls.size,
        )
        assertEquals(
            "held audio-environment callback must NOT broadcast media state",
            broadcastsBefore,
            mediaStateBroadcasts().size,
        )
        assertFalse(
            "actual published video must stay false while held",
            factory.session.actualVideoPublishedForTest(),
        )
        assertFalse(
            "actual published audio must stay false while held",
            factory.session.actualAudioPublishedForTest(),
        )
    }

    @Test
    fun `hold of a screen-sharing call emits content_state false to peers`() {
        // FIX A-3: hold stops an active screen share but previously never broadcast
        // content_state:false, so peers retained stale active content. Hold must
        // emit content_state:false (the normal stopScreenShare path) during hold.
        startInCall()
        factory.session.startScreenShare(android.content.Intent())
        ShadowLooper.idleMainLooper()
        assertTrue(
            "screen share should be active before hold",
            factory.session.diagnostics.value.isScreenSharing,
        )
        val contentBefore = factory.fakeProvider.sentMessages("content_state").size

        hold()

        val contentStates = factory.fakeProvider.sentMessages("content_state")
        assertTrue(
            "hold of a screen-sharing call must broadcast content_state",
            contentStates.size > contentBefore,
        )
        assertEquals(
            "hold must broadcast content_state active:false",
            false,
            contentStates.last().payload?.optBoolean("active"),
        )
        assertFalse(
            "hold must clear the screen-sharing diagnostic",
            factory.session.diagnostics.value.isScreenSharing,
        )
    }

    @Test
    fun `actual published reflects effective foreground state after a toggle`() {
        // FIX A-4: actual* were only updated at start/resume/hold. A foreground
        // mic/video toggle must keep actual* in sync with the effective state.
        startInCall()
        // Foreground, video on: actualVideoPublished tracks the camera. (Audio's
        // effective state depends on the route, so assert actual* == effective
        // local state rather than a fixed value.)
        assertTrue(factory.session.actualVideoPublishedForTest())
        assertEquals(
            "foreground actualAudioPublished must equal the effective local audio state",
            factory.session.state.value.localAudioEnabled,
            factory.session.actualAudioPublishedForTest(),
        )

        // Toggle the mic: actualAudioPublished stays in sync with effective state.
        factory.session.setMicMuted(true)
        ShadowLooper.idleMainLooper()
        assertEquals(
            "muting must keep actualAudioPublished in sync with the effective state",
            factory.session.state.value.localAudioEnabled,
            factory.session.actualAudioPublishedForTest(),
        )
        assertFalse(
            "a muted foreground mic must not publish audio",
            factory.session.actualAudioPublishedForTest(),
        )

        // Turn video off: actualVideoPublished follows to false.
        factory.session.toggleVideo()
        ShadowLooper.idleMainLooper()
        assertFalse(
            "video off must drop actualVideoPublished",
            factory.session.actualVideoPublishedForTest(),
        )

        // Turn video back on: actualVideoPublished follows to true.
        factory.session.toggleVideo()
        ShadowLooper.idleMainLooper()
        assertTrue(
            "video on must raise actualVideoPublished",
            factory.session.actualVideoPublishedForTest(),
        )
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
