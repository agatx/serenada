package app.serenada.core.call

import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JoinFlowCoordinatorTest {

    private lateinit var coordinator: JoinFlowCoordinator

    // Configurable state
    private var phase: CallPhase = CallPhase.Joining
    private var signalingConnected = true

    // Callback counters
    private var timeoutCount = 0
    private var recoveryCount = 0
    private var joinRoomCalls = 0
    private var connectProviderCalls = 0
    private var pendingJoinRoom: String? = null

    @Before
    fun setUp() {
        timeoutCount = 0
        recoveryCount = 0
        joinRoomCalls = 0
        connectProviderCalls = 0
        pendingJoinRoom = null

        coordinator = JoinFlowCoordinator(
            handler = Handler(Looper.getMainLooper()),
            roomId = "test-room",
            getPhase = { phase },
            isSignalingConnected = { signalingConnected },
            onStartJoinInternal = {},
            onPermissionCheckRequired = {},
            connectProvider = { connectProviderCalls++ },
            joinRoom = { _, _ -> joinRoomCalls++ },
            onJoinTimeout = { timeoutCount++ },
            onJoinRecovery = { recoveryCount++ },
            setPendingJoinRoom = { pendingJoinRoom = it },
            getReconnectPeerId = { null },
        )
    }

    @After
    fun tearDown() {
        coordinator.reset()
    }

    // ── Join Timeout ────────────────────────────────────────────────

    @Test
    fun `join timeout fires after delay`() {
        val serial = coordinator.prepareJoinAttempt()
        coordinator.scheduleJoinTimeout("test-room", serial)

        idleFor(WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS)

        assertEquals(1, timeoutCount)
    }

    @Test
    fun `join timeout does not fire if phase changed`() {
        val serial = coordinator.prepareJoinAttempt()
        coordinator.scheduleJoinTimeout("test-room", serial)

        phase = CallPhase.InCall
        idleFor(WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS)

        assertEquals(0, timeoutCount)
    }

    @Test
    fun `join timeout does not fire if serial changed`() {
        val serial = coordinator.prepareJoinAttempt()
        coordinator.scheduleJoinTimeout("test-room", serial)

        coordinator.prepareJoinAttempt() // increments serial
        idleFor(WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS)

        assertEquals(0, timeoutCount)
    }

    @Test
    fun `clearJoinTimeout cancels pending timeout`() {
        val serial = coordinator.prepareJoinAttempt()
        coordinator.scheduleJoinTimeout("test-room", serial)

        coordinator.clearJoinTimeout()
        idleFor(WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS)

        assertEquals(0, timeoutCount)
    }

    // ── Join Connect Kickstart ──────────────────────────────────────

    @Test
    fun `kickstart fires when join signal not started`() {
        val serial = coordinator.prepareJoinAttempt()
        coordinator.scheduleJoinKickstart(serial)

        idleFor(WebRtcResilienceConstants.JOIN_CONNECT_KICKSTART_MS)

        assertTrue("Should trigger ensureSignalingConnection", coordinator.hasJoinSignalStarted)
    }

    @Test
    fun `kickstart does not fire when join signal already started`() {
        val serial = coordinator.prepareJoinAttempt()
        coordinator.markJoinSignalStarted()

        val joinRoomsBefore = joinRoomCalls
        val connectBefore = connectProviderCalls
        coordinator.scheduleJoinKickstart(serial)

        idleFor(WebRtcResilienceConstants.JOIN_CONNECT_KICKSTART_MS)

        assertEquals("Should not trigger additional joins", joinRoomsBefore, joinRoomCalls)
        assertEquals("Should not trigger additional connects", connectBefore, connectProviderCalls)
    }

    // ── Join Recovery ───────────────────────────────────────────────

    @Test
    fun `recovery fires when connected and acknowledged`() {
        signalingConnected = true
        coordinator.markJoinAcknowledged()

        coordinator.scheduleJoinRecovery("test-room")

        idleFor(WebRtcResilienceConstants.JOIN_RECOVERY_MS)

        assertEquals(1, recoveryCount)
    }

    @Test
    fun `recovery re-ensures connection when not acknowledged`() {
        signalingConnected = true
        phase = CallPhase.Joining
        // hasJoinAcknowledged is false by default

        val joinRoomsBefore = joinRoomCalls
        coordinator.scheduleJoinRecovery("test-room")

        idleFor(WebRtcResilienceConstants.JOIN_RECOVERY_MS)

        assertEquals(0, recoveryCount)
        assertTrue("Should have started signaling", coordinator.hasJoinSignalStarted)
        assertTrue("Should re-send join since connected", joinRoomCalls > joinRoomsBefore)
    }

    @Test
    fun `recovery does not fire when disconnected`() {
        signalingConnected = false
        coordinator.markJoinAcknowledged()

        coordinator.scheduleJoinRecovery("test-room")

        idleFor(WebRtcResilienceConstants.JOIN_RECOVERY_MS)

        assertEquals(0, recoveryCount)
    }

    // ── Clear All ───────────────────────────────────────────────────

    @Test
    fun `clearAllJoinTimers cancels everything`() {
        val serial = coordinator.prepareJoinAttempt()
        coordinator.markJoinAcknowledged()
        signalingConnected = true

        coordinator.scheduleJoinTimeout("test-room", serial)
        coordinator.scheduleJoinKickstart(serial)
        coordinator.scheduleJoinRecovery("test-room")

        coordinator.clearAllJoinTimers()

        idleFor(WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS)

        assertEquals(0, timeoutCount)
        assertEquals(0, recoveryCount)
    }

    // ── Reconnect Backoff ───────────────────────────────────────────

    @Test
    fun `scheduleReconnect uses exponential backoff`() {
        signalingConnected = false

        // First reconnect: base backoff (500ms)
        coordinator.scheduleReconnect()
        assertEquals(1, coordinator.reconnectAttempts)

        idleFor(WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS)
        assertEquals(1, connectProviderCalls)

        // Second reconnect: 2x base (1000ms)
        coordinator.scheduleReconnect()
        assertEquals(2, coordinator.reconnectAttempts)

        // Should NOT fire before 1000ms
        idleFor(WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS)
        assertEquals("Should not reconnect before doubled backoff", 1, connectProviderCalls)

        // Fire at 1000ms total
        idleFor(WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS)
        assertEquals(2, connectProviderCalls)
    }

    private fun idleFor(ms: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper()
    }
}
