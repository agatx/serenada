package app.serenada.core

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import app.serenada.core.call.AudioCoordinatorEvent
import app.serenada.core.call.AudioDevice
import app.serenada.core.call.AudioDeviceDirection
import app.serenada.core.call.AudioDeviceKind
import app.serenada.core.call.AudioDeviceStatus
import app.serenada.core.call.AudioIntent
import app.serenada.core.call.DefaultAudioCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Phase 4 (multi-call session, contract §6) — DefaultAudioCoordinator
 * foreground-lease fence.
 *
 * DefaultAudioCoordinator is process-global: every instance mutates the SAME
 * AudioManager. Its DELAYED/ASYNC paths (postDelayed route-refresh + ducking
 * fallback) and its deactivation can fire AFTER a switch handed the foreground to
 * a NEWER call. These tests drive an OLD coordinator, then make a NEWER call the
 * arbiter's lease owner, then advance the looper past the delay / run the
 * deactivation, and assert the stale path does NOT touch the shared AudioManager.
 *
 * The single-call case (no supersession) asserts the SAME callbacks still run, so
 * the fence drops nothing when a session is still the owner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAudioCoordinatorLeaseFenceTest {

    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)
    private lateinit var handler: Handler

    @Before
    fun setUp() {
        ForegroundMediaArbiter.resetForTests()
        handler = Handler(Looper.getMainLooper())
    }

    @After
    fun tearDown() {
        ForegroundMediaArbiter.resetForTests()
    }

    private fun audioManager(): AudioManager =
        RuntimeEnvironment.getApplication()
            .getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

    private fun newCoordinator(
        onProximityChanged: (Boolean) -> Unit = {},
        onAudioEnvironmentChanged: () -> Unit = {},
    ): DefaultAudioCoordinator =
        DefaultAudioCoordinator(
            context = RuntimeEnvironment.getApplication(),
            handler = handler,
            proximityMonitoringEnabled = false,
            onProximityChanged = onProximityChanged,
            onAudioEnvironmentChanged = onAudioEnvironmentChanged,
            logger = null,
        )

    private fun bluetoothOutput(): AudioDevice =
        AudioDevice(
            id = "bt-1",
            displayName = "Test BT",
            kind = AudioDeviceKind.Bluetooth(app.serenada.core.call.BluetoothProfile.HFP),
            direction = AudioDeviceDirection.OUTPUT,
            status = AudioDeviceStatus.AVAILABLE,
        )

    /** Collect every coordinator event into a list for assertions on the main thread. */
    private fun collectEvents(coordinator: DefaultAudioCoordinator): Pair<MutableList<AudioCoordinatorEvent>, Job> {
        val events = mutableListOf<AudioCoordinatorEvent>()
        val job = mainScope.launch {
            coordinator.events.collect { events += it }
        }
        ShadowLooper.idleMainLooper()
        return events to job
    }

    /**
     * A postDelayed route-refresh scheduled by an OLD activation does NOT emit a
     * route-changed event after a NEWER activation superseded the lease — the
     * stale runnable is dropped once the OLD coordinator is no longer the owner.
     */
    @Test
    fun `stale route-refresh does not run after a newer activation superseded the lease`() {
        val oldToken = ForegroundMediaArbiter.acquireForeground("old-call")
        val oldGen = ForegroundMediaArbiter.nextOperationGeneration()
        val coordinator = newCoordinator()
        coordinator.bindForegroundLease(oldToken, oldGen)

        runBlocking { coordinator.activateCallSession(AudioIntent()) }
        ShadowLooper.idleMainLooper()

        val (events, collectorJob) = collectEvents(coordinator)

        // Schedule a route refresh (postDelayed ~300ms) by applying a route.
        runBlocking { coordinator.applyRouting(bluetoothOutput()) }
        val baselineRouteEvents = events.count { it is AudioCoordinatorEvent.EffectiveRouteChanged }

        // A NEWER call takes the foreground lease at the arbiter. The OLD
        // coordinator's bound token is no longer the current owner.
        ForegroundMediaArbiter.releaseLease(oldToken)
        val newerToken = ForegroundMediaArbiter.acquireForeground("new-call")
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(newerToken))

        // Advance past the route-refresh delay so the stale runnable fires.
        ShadowLooper.idleMainLooper()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)

        val afterRouteEvents = events.count { it is AudioCoordinatorEvent.EffectiveRouteChanged }
        assertEquals(
            "a stale route refresh from a superseded session must not emit a route change",
            baselineRouteEvents,
            afterRouteEvents,
        )

        collectorJob.cancel()
        ForegroundMediaArbiter.releaseLease(newerToken)
    }

    /** Fire the coordinator's private audio-focus-change listener (reflection): the
     * shadow AudioManager does not deliver focus changes on its own. */
    private fun fireFocusChange(coordinator: DefaultAudioCoordinator, focusChange: Int) {
        val field = DefaultAudioCoordinator::class.java.getDeclaredField("audioFocusChangeListener")
        field.isAccessible = true
        val listener = field.get(coordinator) as AudioManager.OnAudioFocusChangeListener
        listener.onAudioFocusChange(focusChange)
    }

    /**
     * A postDelayed ducking-fallback scheduled by an OLD activation (via a focus
     * loss-can-duck event) does NOT emit PlaybackDuckingEnded after a NEWER
     * activation superseded the lease. The fallback runnable is fenced the same way
     * as the route refresh.
     */
    @Test
    fun `stale ducking fallback does not run after a newer activation superseded the lease`() {
        val oldToken = ForegroundMediaArbiter.acquireForeground("old-call")
        val oldGen = ForegroundMediaArbiter.nextOperationGeneration()
        val coordinator = newCoordinator()
        coordinator.bindForegroundLease(oldToken, oldGen)
        runBlocking { coordinator.activateCallSession(AudioIntent()) }
        ShadowLooper.idleMainLooper()

        val (events, collectorJob) = collectEvents(coordinator)

        // Focus loss-can-duck schedules the ~3000ms ducking-fallback runnable.
        fireFocusChange(coordinator, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        ShadowLooper.idleMainLooper()
        val baselineEndedEvents = events.count { it is AudioCoordinatorEvent.PlaybackDuckingEnded }

        // A NEWER call takes the foreground lease.
        ForegroundMediaArbiter.releaseLease(oldToken)
        val newerToken = ForegroundMediaArbiter.acquireForeground("new-call")
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(newerToken))

        // Advance past the ducking-fallback delay so the stale runnable fires.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.SECONDS)

        val afterEndedEvents = events.count { it is AudioCoordinatorEvent.PlaybackDuckingEnded }
        assertEquals(
            "a stale ducking fallback from a superseded session must not emit PlaybackDuckingEnded",
            baselineEndedEvents,
            afterEndedEvents,
        )

        collectorJob.cancel()
        ForegroundMediaArbiter.releaseLease(newerToken)
    }

    /**
     * The async deactivation of an OLD session must NOT restore MODE_NORMAL after a
     * NEWER session activated and set MODE_IN_COMMUNICATION (generation/owner
     * fence). The OLD coordinator and the NEW coordinator mutate the same
     * AudioManager; the stale deactivation must leave the new call's mode intact.
     */
    @Test
    fun `stale async deactivation does not reset mode after a newer activation`() {
        val am = audioManager()

        // OLD call activates and owns the lease: mode becomes MODE_IN_COMMUNICATION.
        val oldToken = ForegroundMediaArbiter.acquireForeground("old-call")
        val oldGen = ForegroundMediaArbiter.nextOperationGeneration()
        val oldCoordinator = newCoordinator()
        oldCoordinator.bindForegroundLease(oldToken, oldGen)
        runBlocking { oldCoordinator.activateCallSession(AudioIntent()) }
        ShadowLooper.idleMainLooper()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode)

        // Switch hands off: release the OLD lease, a NEWER call acquires it and
        // activates its own coordinator (sets MODE_IN_COMMUNICATION again).
        ForegroundMediaArbiter.releaseLease(oldToken)
        val newToken = ForegroundMediaArbiter.acquireForeground("new-call")
        val newGen = ForegroundMediaArbiter.nextOperationGeneration()
        val newCoordinator = newCoordinator()
        newCoordinator.bindForegroundLease(newToken, newGen)
        runBlocking { newCoordinator.activateCallSession(AudioIntent()) }
        ShadowLooper.idleMainLooper()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode)

        // The OLD session's async deactivation fires LATE, after the NEW call
        // already owns the lease. It must NOT restore MODE_NORMAL (which would
        // clobber the NEW call).
        val deactivationJob = mainScope.launch { oldCoordinator.deactivateCallSession() }
        ShadowLooper.idleMainLooper()
        assertTrue(deactivationJob.isCompleted)

        assertEquals(
            "a superseded session's async deactivation must not reset the mode the newer call set",
            AudioManager.MODE_IN_COMMUNICATION,
            am.mode,
        )

        ForegroundMediaArbiter.releaseLease(newToken)
    }

    /**
     * Single-call (no supersession): the SAME postDelayed route-refresh DOES run
     * when the session is still the lease owner. The fence must not drop a callback
     * for the live owner.
     */
    @Test
    fun `route-refresh runs for the current lease owner (no false drop)`() {
        val token = ForegroundMediaArbiter.acquireForeground("only-call")
        val gen = ForegroundMediaArbiter.nextOperationGeneration()
        val coordinator = newCoordinator()
        coordinator.bindForegroundLease(token, gen)

        runBlocking { coordinator.activateCallSession(AudioIntent()) }
        ShadowLooper.idleMainLooper()

        val (events, collectorJob) = collectEvents(coordinator)
        val baselineRouteEvents = events.count { it is AudioCoordinatorEvent.EffectiveRouteChanged }

        // Apply a route -> schedules the postDelayed route refresh. The session is
        // STILL the current owner, so the refresh must fire.
        runBlocking { coordinator.applyRouting(bluetoothOutput()) }
        ShadowLooper.idleMainLooper()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)

        val afterRouteEvents = events.count { it is AudioCoordinatorEvent.EffectiveRouteChanged }
        assertTrue(
            "route refresh must still run for the current lease owner",
            afterRouteEvents > baselineRouteEvents,
        )

        collectorJob.cancel()
        ForegroundMediaArbiter.releaseLease(token)
    }

    /**
     * Pass-through: a coordinator with NO bound lease (single-call without arbiter
     * routing, or a fake in tests) behaves exactly as before — its deactivation
     * restores MODE_NORMAL. This is the single-call-identical guarantee.
     */
    @Test
    fun `unbound coordinator restores mode on deactivate (single-call pass-through)`() {
        val am = audioManager()
        val coordinator = newCoordinator()
        // No bindForegroundLease() call: leaseToken stays null -> pass-through.

        runBlocking { coordinator.activateCallSession(AudioIntent()) }
        ShadowLooper.idleMainLooper()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode)

        runBlocking { coordinator.deactivateCallSession() }
        ShadowLooper.idleMainLooper()
        assertEquals(
            "an unbound (pass-through) coordinator must restore MODE_NORMAL on deactivate",
            AudioManager.MODE_NORMAL,
            am.mode,
        )
    }

    /**
     * Clean single-call END (no newer owner): the lease is released BEFORE the
     * async deactivation runs, so the bound token is no longer "current" — but the
     * coordinator must STILL restore MODE_NORMAL, because no DIFFERENT call took
     * the foreground. (Regression guard for the deactivation fence using
     * hasOtherOwner, not isCurrentOwner.)
     */
    @Test
    fun `lease-bound coordinator restores mode on a clean end with no newer owner`() {
        val am = audioManager()
        val token = ForegroundMediaArbiter.acquireForeground("solo-call")
        val gen = ForegroundMediaArbiter.nextOperationGeneration()
        val coordinator = newCoordinator()
        coordinator.bindForegroundLease(token, gen)

        runBlocking { coordinator.activateCallSession(AudioIntent()) }
        ShadowLooper.idleMainLooper()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode)

        // Single-call leave: the lease is released first (no newer owner), THEN the
        // async deactivation runs.
        ForegroundMediaArbiter.releaseLease(token)
        val deactivationJob = mainScope.launch { coordinator.deactivateCallSession() }
        ShadowLooper.idleMainLooper()
        assertTrue(deactivationJob.isCompleted)

        assertEquals(
            "a clean single-call end with no newer owner must still restore MODE_NORMAL",
            AudioManager.MODE_NORMAL,
            am.mode,
        )
    }

    /**
     * PA-1 generation fence (contract §3 two-fence rule): a stale async
     * deactivation armed under generation N must NOT restore MODE_NORMAL after a
     * SAME-OWNER re-activation advanced the coordinator's generation to N+1. The
     * token is IDENTICAL across the rollback (same call re-acquired the lease), so
     * the token fence alone would let the stale deactivation through; only the
     * generation comparison drops it.
     */
    @Test
    fun `stale deactivation does not reset mode after a same-owner re-activation bumped the generation`() {
        val am = audioManager()

        // Attempt N: the call activates under generation N and owns the lease.
        val token = ForegroundMediaArbiter.acquireForeground("rollback-call")
        val genN = ForegroundMediaArbiter.nextOperationGeneration()
        val coordinator = newCoordinator()
        coordinator.bindForegroundLease(token, genN)
        runBlocking { coordinator.activateCallSession(AudioIntent()) }
        ShadowLooper.idleMainLooper()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode)

        // SAME-OWNER rollback: the SAME token is re-bound under a FRESH generation
        // N+1 (the call re-activates after a failed switch). The coordinator's
        // current generation advances; the deactivation armed under N is now stale.
        val genNPlus1 = ForegroundMediaArbiter.nextOperationGeneration()
        coordinator.bindForegroundLease(token, genNPlus1)
        assertTrue("same-owner rollback keeps the token current", ForegroundMediaArbiter.isCurrentOwner(token))

        // The stale deactivation from attempt N fires. The token still matches, so
        // only the generation fence can stop it: it must NOT restore MODE_NORMAL.
        val deactivationJob = mainScope.launch { coordinator.deactivateCallSession() }
        ShadowLooper.idleMainLooper()
        assertTrue(deactivationJob.isCompleted)

        assertEquals(
            "a stale same-owner deactivation (generation N) must not clobber the N+1 re-activation's mode",
            AudioManager.MODE_IN_COMMUNICATION,
            am.mode,
        )

        ForegroundMediaArbiter.releaseLease(token)
    }

    /**
     * PA-1 generation fence — delayed route-refresh path. A postDelayed route
     * refresh scheduled under generation N is dropped after a SAME-OWNER
     * re-activation bumped the generation to N+1 (token identical). A
     * current-generation refresh (scheduled under N+1) still runs — the fence drops
     * only the stale attempt, never the live one.
     */
    @Test
    fun `stale route-refresh is dropped but a current-generation one runs after a same-owner re-activation`() {
        val token = ForegroundMediaArbiter.acquireForeground("rollback-call")
        val genN = ForegroundMediaArbiter.nextOperationGeneration()
        val coordinator = newCoordinator()
        coordinator.bindForegroundLease(token, genN)
        runBlocking { coordinator.activateCallSession(AudioIntent()) }
        ShadowLooper.idleMainLooper()

        val (events, collectorJob) = collectEvents(coordinator)

        // Schedule a route refresh under generation N (postDelayed ~300ms).
        runBlocking { coordinator.applyRouting(bluetoothOutput()) }
        val baselineRouteEvents = events.count { it is AudioCoordinatorEvent.EffectiveRouteChanged }

        // SAME-OWNER re-activation under a FRESH generation N+1 (token identical).
        // The previously-scheduled refresh is now stale.
        val genNPlus1 = ForegroundMediaArbiter.nextOperationGeneration()
        coordinator.bindForegroundLease(token, genNPlus1)
        assertTrue(ForegroundMediaArbiter.isCurrentOwner(token))

        // Advance past the route-refresh delay: the stale (generation N) runnable
        // fires but must drop — no route event emitted.
        ShadowLooper.idleMainLooper()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)
        val afterStaleRouteEvents = events.count { it is AudioCoordinatorEvent.EffectiveRouteChanged }
        assertEquals(
            "a route refresh scheduled under the OLD generation must drop after a same-owner re-activation",
            baselineRouteEvents,
            afterStaleRouteEvents,
        )

        // A refresh scheduled under the CURRENT generation N+1 still runs.
        runBlocking { coordinator.applyRouting(bluetoothOutput()) }
        ShadowLooper.idleMainLooper()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)
        val afterCurrentRouteEvents = events.count { it is AudioCoordinatorEvent.EffectiveRouteChanged }
        assertTrue(
            "a route refresh scheduled under the current generation must still run",
            afterCurrentRouteEvents > afterStaleRouteEvents,
        )

        collectorJob.cancel()
        ForegroundMediaArbiter.releaseLease(token)
    }
}
