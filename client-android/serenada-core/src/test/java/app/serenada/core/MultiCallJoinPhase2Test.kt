package app.serenada.core

import app.serenada.core.call.AudioCoordinatorEvent
import app.serenada.core.call.AudioDevice
import app.serenada.core.call.AudioIntent
import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.MediaActivationState
import app.serenada.core.call.SerenadaAudioCoordinator
import app.serenada.core.fakes.TestSessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Multi-call session, Phase 2: held-initial join (stable senders, no capture, no
 * audio coordinator, no lease), the direct single-call join routed through the
 * process-wide arbiter, and the pure preflight check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiCallJoinPhase2Test {

    private val factories = mutableListOf<TestSessionFactory>()

    // Same Robolectric main looper the session's providerScope uses, for driving
    // the suspend token-gated activateForeground.
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        ForegroundMediaArbiter.resetForTests()
    }

    @After
    fun tearDown() {
        factories.forEach { it.tearDown() }
        factories.clear()
        ForegroundMediaArbiter.resetForTests()
    }

    private fun track(factory: TestSessionFactory): TestSessionFactory {
        factories.add(factory)
        return factory
    }

    // --- Held-initial join ---

    @Test
    fun `held-initial join creates stable senders without capture and never activates the coordinator`() {
        val coordinator = CountingCoordinator()
        val factory = track(
            TestSessionFactory(
                defaultVideoEnabled = false,
                audioCoordinator = coordinator,
                initialMediaRole = CallMediaRole.HELD,
            )
        )
        factory.advanceToHeldInCall()
        ShadowLooper.idleMainLooper()

        // Role/activation reflect held.
        assertEquals(CallMediaRole.HELD, factory.session.mediaRoleForTest())
        assertEquals(MediaActivationState.INACTIVE, factory.session.mediaActivationStateForTest())

        // Stable senders were created WITHOUT any capture (Core Invariant 3).
        assertEquals(
            "held join must create stable senders",
            1,
            factory.fakeMedia.createSendersForHoldCalls,
        )
        assertEquals(
            "held join must NOT start local capture",
            0,
            factory.fakeMedia.startLocalMediaCalls,
        )

        // No audio coordinator / controller activation for a held call.
        assertEquals("held join must not activate the audio coordinator", 0, coordinator.activateCalls)
        assertEquals("held join must not activate the audio controller", 0, factory.fakeAudio.activateCalls)

        // Remote playout deafened while held.
        assertTrue(
            "held join deafens remote playout",
            factory.fakeMedia.setRemotePlaybackEnabledCalls.contains(false),
        )

        // actual* published as false while held.
        assertFalse(factory.session.actualAudioPublishedForTest())
        assertFalse(factory.session.actualVideoPublishedForTest())
    }

    @Test
    fun `held-initial join holds no foreground lease`() {
        val factory = track(
            TestSessionFactory(
                defaultVideoEnabled = false,
                initialMediaRole = CallMediaRole.HELD,
            )
        )
        factory.advanceToHeldInCall()
        ShadowLooper.idleMainLooper()

        // No lease was taken: a direct acquire still succeeds (the process is free).
        assertEquals("a held join takes no lease", null, ForegroundMediaArbiter.currentMode)
        val token = ForegroundMediaArbiter.acquireForeground("probe")
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))
        ForegroundMediaArbiter.releaseLease(token)
    }

    @Test
    fun `held-initial join broadcasts held true`() {
        val factory = track(
            TestSessionFactory(
                defaultVideoEnabled = false,
                initialMediaRole = CallMediaRole.HELD,
            )
        )
        factory.advanceToHeldInCall()
        ShadowLooper.idleMainLooper()

        val heldBroadcasts = factory.fakeProvider
            .sentMessages("participant_media_state")
            .filter { it.payload?.optBoolean("held", false) == true }
        assertTrue("held join broadcasts held=true", heldBroadcasts.isNotEmpty())
        // The held broadcast forces audio/video false so old peers degrade safely.
        val last = heldBroadcasts.last().payload
        assertFalse(last?.optBoolean("audioEnabled", true) ?: true)
        assertFalse(last?.optBoolean("videoEnabled", true) ?: true)
    }

    // --- Direct single-call join through the arbiter ---

    @Test
    fun `direct join acquires the lease and a second concurrent direct join fails with ForegroundLeaseUnavailable`() {
        val first = track(
            TestSessionFactory(
                defaultVideoEnabled = false,
                acquireForegroundLease = true,
            )
        )
        first.advanceToInCallWithTurn()
        ShadowLooper.idleMainLooper()

        // First direct join owns DIRECT mode + the lease and is foreground/active.
        assertEquals(ForegroundArbiterMode.DIRECT, ForegroundMediaArbiter.currentMode)
        assertEquals(CallMediaRole.FOREGROUND, first.session.mediaRoleForTest())

        // A second concurrent direct join cannot acquire the lease; the session
        // surfaces an error and never reaches foreground/active. The second factory
        // opts OUT of the construction reset so the first session's live lease
        // survives — modeling two sessions live in one process.
        val second = track(
            TestSessionFactory(
                roomId = "second-room",
                defaultVideoEnabled = false,
                acquireForegroundLease = true,
                resetArbiterOnInit = false,
            )
        )
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO,
        )
        second.session.start()
        ShadowLooper.idleMainLooper()

        // The acquire threw ForegroundLeaseUnavailable BEFORE any media work: the
        // second session surfaces an error and never started capture or activated
        // the audio coordinator (so the first call keeps sole ownership).
        assertNotNull("second join surfaces an error", second.session.state.value.error)
        assertEquals(
            "second join must not start local capture",
            0,
            second.fakeMedia.startLocalMediaCalls,
        )
        assertEquals(
            "second join must not activate the audio controller",
            0,
            second.fakeAudio.activateCalls,
        )
        // The first session still owns the lease (DIRECT mode unchanged).
        assertEquals(ForegroundArbiterMode.DIRECT, ForegroundMediaArbiter.currentMode)
    }

    @Test
    fun `single-call join then leave releases the lease so a reacquire works`() {
        val factory = track(
            TestSessionFactory(
                defaultVideoEnabled = false,
                acquireForegroundLease = true,
            )
        )
        factory.advanceToInCallWithTurn()
        ShadowLooper.idleMainLooper()
        assertEquals(ForegroundArbiterMode.DIRECT, ForegroundMediaArbiter.currentMode)

        // Leave/close tears the session down, which releases the lease + mode.
        factory.session.close()
        ShadowLooper.idleMainLooper()

        assertEquals("leaving a direct call clears the process mode", null, ForegroundMediaArbiter.currentMode)
        // A fresh direct acquire now succeeds.
        val token = ForegroundMediaArbiter.acquireForeground(
            ownerId = "next",
            mode = ForegroundArbiterMode.DIRECT,
            modeOwnerRef = Any(),
        )
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))
    }

    // --- Direct-lease vs registry-issued fence token on teardown (Q6) ---

    @Test
    fun `a registry-activated session does not release the lease on teardown`() {
        // A held-initial session that the (Phase 3) registry activates under a
        // lease the REGISTRY acquired and owns. The session's foregroundOwnerToken
        // is the registry-issued FENCE token, not a self-owned direct lease, so
        // teardown must NOT release it (parity with iOS directLeaseToken split).
        val coordinator = CountingCoordinator()
        val factory = track(
            TestSessionFactory(
                defaultVideoEnabled = false,
                audioCoordinator = coordinator,
                initialMediaRole = CallMediaRole.HELD,
            )
        )
        factory.advanceToHeldInCall()
        ShadowLooper.idleMainLooper()
        assertEquals(CallMediaRole.HELD, factory.session.mediaRoleForTest())

        // The registry acquires the lease (mode REGISTRY) and activates the held
        // session under that registry-owned token.
        val registryRef = Any()
        val registryToken = ForegroundMediaArbiter.acquireForeground(
            ownerId = "managed-call",
            mode = ForegroundArbiterMode.REGISTRY,
            modeOwnerRef = registryRef,
        )
        val gen = ForegroundMediaArbiter.nextOperationGeneration()
        val activateJob = mainScope.launch {
            runCatching { factory.session.activateForeground(registryToken, gen) }
        }
        ShadowLooper.idleMainLooper()
        assertTrue("activation should settle", activateJob.isCompleted)
        assertEquals(CallMediaRole.FOREGROUND, factory.session.mediaRoleForTest())
        assertTrue(
            "precondition: registry token is the live lease owner",
            ForegroundMediaArbiter.isCurrentOwner(registryToken),
        )

        // Tear the session down. Because the session never self-acquired a direct
        // lease, teardown must NOT release the registry-owned lease.
        factory.session.close()
        ShadowLooper.idleMainLooper()

        assertTrue(
            "teardown of a registry-activated session must NOT release the registry-owned lease",
            ForegroundMediaArbiter.isCurrentOwner(registryToken),
        )

        // The registry — the true owner — releases it.
        ForegroundMediaArbiter.releaseLease(registryToken)
        ForegroundMediaArbiter.releaseMode(registryRef)
        assertFalse(ForegroundMediaArbiter.isCurrentOwner(registryToken))
    }

    @Test
    fun `a direct-lease session self-releases its lease on teardown`() {
        // The mirror of the registry case: a direct single-call join holds its OWN
        // lease (directLeaseToken) and MUST self-release it on teardown.
        val factory = track(
            TestSessionFactory(
                defaultVideoEnabled = false,
                acquireForegroundLease = true,
            )
        )
        factory.advanceToInCallWithTurn()
        ShadowLooper.idleMainLooper()
        assertEquals(ForegroundArbiterMode.DIRECT, ForegroundMediaArbiter.currentMode)
        assertEquals(CallMediaRole.FOREGROUND, factory.session.mediaRoleForTest())

        factory.session.close()
        ShadowLooper.idleMainLooper()

        // The direct lease + mode were self-released: the process is free again.
        assertEquals(
            "teardown of a direct-lease session releases its mode",
            null,
            ForegroundMediaArbiter.currentMode,
        )
        val token = ForegroundMediaArbiter.acquireForeground("after-direct")
        assertTrue(
            "the direct lease was released, so a fresh acquire succeeds",
            ForegroundMediaArbiter.isCurrentOwner(token),
        )
        ForegroundMediaArbiter.releaseLease(token)
    }

    // --- preflightForeground (pure) ---

    @Test
    fun `preflight returns OK for a muted camera-off desired call regardless of permissions`() {
        // No permission grants. Desired audio off + video off ⇒ no prompt needed.
        val factory = track(
            TestSessionFactory(
                defaultVideoEnabled = false,
                initialMediaRole = CallMediaRole.HELD,
            )
        )
        factory.advanceToHeldInCall()
        ShadowLooper.idleMainLooper()
        // Drive desired to fully muted + camera off via held toggles (desired-only).
        if (factory.session.desiredAudioEnabledForTest()) factory.session.toggleAudio()
        // Camera already off for a video-disabled config.
        ShadowLooper.idleMainLooper()

        assertEquals(
            SerenadaSession.ForegroundPreflight.OK,
            factory.session.preflightForeground(),
        )
    }

    @Test
    fun `preflight returns NEEDS_PERMISSION when desired audio needs an ungranted mic`() {
        // Audio desired (default), mic NOT granted ⇒ needsPermission.
        val factory = track(
            TestSessionFactory(
                defaultVideoEnabled = false,
                initialMediaRole = CallMediaRole.HELD,
            )
        )
        factory.advanceToHeldInCall()
        ShadowLooper.idleMainLooper()
        assertTrue("default audio desired", factory.session.desiredAudioEnabledForTest())

        assertEquals(
            SerenadaSession.ForegroundPreflight.NEEDS_PERMISSION,
            factory.session.preflightForeground(),
        )

        // Granting the mic flips preflight to OK (pure re-check, no prompt).
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO,
        )
        assertEquals(
            SerenadaSession.ForegroundPreflight.OK,
            factory.session.preflightForeground(),
        )
    }
}

/** Audio coordinator that counts activate/deactivate; never suspends. */
private class CountingCoordinator : SerenadaAudioCoordinator {
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
