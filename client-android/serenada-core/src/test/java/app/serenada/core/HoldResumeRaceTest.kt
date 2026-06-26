package app.serenada.core

import app.serenada.core.call.AudioCoordinatorEvent
import app.serenada.core.call.AudioDevice
import app.serenada.core.call.AudioIntent
import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.MediaActivationState
import app.serenada.core.call.SerenadaAudioCoordinator
import app.serenada.core.fakes.TestSessionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * FIX N2 — stale-activation race fence, and FIX N3 — cancellation swallow.
 *
 * Drives the session-internal hold/resume mechanics under a controllable audio
 * coordinator so a hold can land while a resume awaits the (async) coordinator
 * activation, and so a coordinator op can be cancelled mid-flight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HoldResumeRaceTest {

    // Same Robolectric main looper the session's providerScope uses.
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)
    private lateinit var factory: TestSessionFactory

    @After
    fun tearDown() {
        if (::factory.isInitialized) factory.tearDown()
    }

    private fun mediaStateBroadcasts() =
        factory.fakeProvider.sentMessages("participant_media_state")

    /**
     * FIX N2: a hold that lands while a resume is awaiting the audio coordinator
     * must leave the session HELD — the resume detects the bumped op generation
     * on its post-await fence and rolls back instead of committing foreground.
     */
    @Test
    fun `hold during in-flight resume keeps session held and never broadcasts held false`() {
        val coordinator = GatedAudioCoordinator()
        factory = TestSessionFactory(defaultVideoEnabled = false, audioCoordinator = coordinator)
        factory.advanceToInCallWithTurn()
        ShadowLooper.idleMainLooper()

        // Hold first (synchronously to fully-held).
        val firstHold = mainScope.launch { factory.session.applyHeldRoleInternal() }
        ShadowLooper.idleMainLooper()
        assertTrue("first hold should complete", firstHold.isCompleted)
        assertEquals(CallMediaRole.HELD, factory.session.mediaRoleForTest())
        val broadcastsAfterHold = mediaStateBroadcasts().size
        val activatesBeforeResume = coordinator.activateCalls

        // Arm the gate so the resume's coordinator activation suspends mid-flight.
        coordinator.armActivationGate()
        val resumeJob = mainScope.launch { factory.session.applyForegroundRoleInternal() }
        ShadowLooper.idleMainLooper()
        assertEquals(
            "resume should be awaiting coordinator activation",
            activatesBeforeResume + 1,
            coordinator.activateCalls,
        )
        assertFalse("resume must not have completed yet", resumeJob.isCompleted)

        // A second hold lands while resume is in-flight. It bumps the op
        // generation (superseding the resume) and tears foreground back down.
        val secondHold = mainScope.launch { factory.session.applyHeldRoleInternal() }
        ShadowLooper.idleMainLooper()

        // Release the coordinator activation so the resume continues to its fence.
        coordinator.completeActivation()
        ShadowLooper.idleMainLooper()

        // Drain any blocked coordinator ops (hold's deactivation).
        coordinator.completeDeactivation()
        ShadowLooper.idleMainLooper()

        assertTrue("resume should have settled", resumeJob.isCompleted)
        assertTrue("second hold should have settled", secondHold.isCompleted)

        // The hold won the race: session stays HELD, foreground never committed.
        assertEquals(
            "session must remain HELD after a hold superseded the resume",
            CallMediaRole.HELD,
            factory.session.mediaRoleForTest(),
        )
        assertEquals(
            "stale resume must not commit ACTIVE",
            MediaActivationState.INACTIVE,
            factory.session.mediaActivationStateForTest(),
        )
        // No held=false broadcast should have been emitted by the stale resume.
        val heldFalseBroadcasts = mediaStateBroadcasts()
            .drop(broadcastsAfterHold)
            .count { it.payload?.optBoolean("held", true) == false }
        assertEquals(
            "a superseded resume must not broadcast held=false",
            0,
            heldFalseBroadcasts,
        )
    }

    /**
     * FIX N3: cancelling the coordinator op (structured-concurrency cancellation)
     * must NOT be swallowed — the resume must abort and must not proceed to
     * activate the controller / resume capture / commit foreground.
     */
    @Test
    fun `cancellation of a coordinator op is not swallowed and resume does not activate media`() {
        val coordinator = GatedAudioCoordinator()
        factory = TestSessionFactory(defaultVideoEnabled = false, audioCoordinator = coordinator)
        factory.advanceToInCallWithTurn()
        ShadowLooper.idleMainLooper()

        // Hold to fully-held first.
        val firstHold = mainScope.launch { factory.session.applyHeldRoleInternal() }
        ShadowLooper.idleMainLooper()
        assertTrue(firstHold.isCompleted)

        val controllerActivatesBefore = factory.fakeAudio.activateCalls
        val resumeCallsBefore = factory.fakeMedia.resumeLocalMediaFromHoldCalls.size
        val activatesBeforeResume = coordinator.activateCalls

        // Arm the gate so the resume's coordinator activation suspends mid-flight.
        coordinator.armActivationGate()
        val resumeJob: Job = mainScope.launch { factory.session.applyForegroundRoleInternal() }
        ShadowLooper.idleMainLooper()
        assertEquals(activatesBeforeResume + 1, coordinator.activateCalls)
        assertFalse(resumeJob.isCompleted)

        // Cancel the resume while it is suspended in the coordinator op. The
        // CancellationException must propagate (FIX N3) and the resume must NOT
        // continue to activate the controller or resume capture.
        resumeJob.cancel(CancellationException("test cancel"))
        ShadowLooper.idleMainLooper()

        assertTrue("cancelled resume must settle", resumeJob.isCompleted)
        assertTrue("resume job should be cancelled", resumeJob.isCancelled)
        assertEquals(
            "cancelled resume must not activate the audio controller",
            controllerActivatesBefore,
            factory.fakeAudio.activateCalls,
        )
        assertEquals(
            "cancelled resume must not resume local capture",
            resumeCallsBefore,
            factory.fakeMedia.resumeLocalMediaFromHoldCalls.size,
        )
        assertEquals(
            "cancelled resume must not commit FOREGROUND",
            CallMediaRole.HELD,
            factory.session.mediaRoleForTest(),
        )
    }
}

/**
 * Audio coordinator whose activate/deactivate suspend on completable gates, so a
 * test can hold a resume mid-activation and interleave a concurrent hold or a
 * cancellation. Mirrors the BlockingAudioCoordinator pattern.
 */
private class GatedAudioCoordinator : SerenadaAudioCoordinator {
    // Gates start pre-completed so the JOIN activation and any deactivation
    // resolve immediately. A test arms a fresh gate (armActivationGate) right
    // before the resume it wants to suspend mid-flight.
    private var activationGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var deactivationGate = CompletableDeferred<Unit>().apply { complete(Unit) }

    var activateCalls = 0
        private set
    var deactivateCalls = 0
        private set

    override suspend fun activateCallSession(intent: AudioIntent) {
        activateCalls += 1
        activationGate.await()
    }

    override suspend fun deactivateCallSession() {
        deactivateCalls += 1
        deactivationGate.await()
    }

    /** Arm a fresh uncompleted activation gate so the NEXT activate suspends. */
    fun armActivationGate() {
        activationGate = CompletableDeferred()
    }

    fun completeActivation() {
        if (!activationGate.isCompleted) activationGate.complete(Unit)
    }

    fun completeDeactivation() {
        if (!deactivationGate.isCompleted) deactivationGate.complete(Unit)
    }

    override suspend fun applyRouting(device: AudioDevice) {}
    override suspend fun setMicMuted(muted: Boolean) {}

    override val availableDevices: StateFlow<List<AudioDevice>> = MutableStateFlow(emptyList())
    override val effectiveInputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val effectiveOutputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val events: SharedFlow<AudioCoordinatorEvent> = MutableSharedFlow()
}
