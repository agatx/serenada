package app.serenada.core

import app.serenada.core.call.CallPhase
import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.fakes.TestSessionFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SerenadaSessionContractTest {

    private lateinit var factory: TestSessionFactory

    @Before
    fun setUp() {
        factory = TestSessionFactory()
    }

    @After
    fun tearDown() {
        factory.tearDown()
    }

    // ── Permission gating ───────────────────────────────────────────

    @Test
    fun `start without permissions sets AwaitingPermissions`() {
        factory.startSession()
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.AwaitingPermissions, factory.session.state.value.phase)
        assertTrue(factory.session.state.value.requiredPermissions.isNotEmpty())
    }

    // ── Join → Joined → Waiting ─────────────────────────────────────

    @Test
    fun `join then joined with single participant transitions to Waiting`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        val joinMessages = factory.fakeSignaling.sentMessages("join")
        assertTrue("Should send join message", joinMessages.isNotEmpty())

        factory.simulateJoinedResponse(cid = "my-cid")

        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)
        assertEquals("my-cid", factory.session.state.value.localCid)
    }

    // ── Join → Joined → InCall ──────────────────────────────────────

    @Test
    fun `join then joined with two participants transitions to InCall`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )

        assertEquals(CallPhase.InCall, factory.session.state.value.phase)
        assertEquals(1, factory.session.state.value.remoteParticipants.size)
        assertEquals("remote-cid", factory.session.state.value.remoteParticipants.first().cid)
        assertTrue(
            "Should create slot for remote participant",
            factory.fakeMedia.createdSlotCids.contains("remote-cid")
        )
    }

    // ── Server error ────────────────────────────────────────────────

    @Test
    fun `server error transitions to Error and cleans up`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateError(code = "ROOM_CAPACITY_UNSUPPORTED", message = "Room is full")

        assertEquals(CallPhase.Error, factory.session.state.value.phase)
        assertNotNull(factory.session.state.value.errorMessage)
        assertTrue("Engine should be released", factory.fakeMedia.releaseCalls > 0)
        assertTrue("Signaling should be closed", factory.fakeSignaling.closeCalls > 0)
    }

    // ── Room state update ───────────────────────────────────────────

    @Test
    fun `room_state with new remote participant transitions from Waiting to InCall`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        factory.simulateRoomState(
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )

        assertEquals(CallPhase.InCall, factory.session.state.value.phase)
        assertEquals(1, factory.session.state.value.remoteParticipants.size)
    }

    // ── Reconnect on close ──────────────────────────────────────────

    @Test
    fun `signaling close during call schedules reconnect`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        factory.fakeSignaling.simulateClosed(reason = "connection lost")
        ShadowLooper.idleMainLooper()

        assertNotEquals(CallPhase.Idle, factory.session.state.value.phase)
        assertFalse(factory.session.diagnostics.value.isSignalingConnected)
    }

    // ── Leave cleanup ───────────────────────────────────────────────

    @Test
    fun `leave sends leave message and cleans up resources`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )
        assertEquals(CallPhase.InCall, factory.session.state.value.phase)

        val releaseBefore = factory.fakeMedia.releaseCalls
        val deactivateBefore = factory.fakeAudio.deactivateCalls

        factory.session.leave()
        ShadowLooper.idleMainLooper()

        val leaveMessages = factory.fakeSignaling.sentMessages("leave")
        assertTrue("Should send leave message", leaveMessages.isNotEmpty())
        assertEquals(CallPhase.Idle, factory.session.state.value.phase)
        assertTrue("Signaling should be closed", factory.fakeSignaling.closeCalls > 0)
        assertTrue("Engine should be released", factory.fakeMedia.releaseCalls > releaseBefore)
        assertTrue("Audio should be deactivated", factory.fakeAudio.deactivateCalls > deactivateBefore)
    }

    // ── End cleanup ─────────────────────────────────────────────────

    @Test
    fun `end sends end_room then leave and cleans up`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")

        factory.session.end()
        ShadowLooper.idleMainLooper()

        val endMessages = factory.fakeSignaling.sentMessages("end_room")
        assertTrue("Should send end_room message", endMessages.isNotEmpty())
        assertEquals(CallPhase.Idle, factory.session.state.value.phase)
    }

    // ── TURN credential fetch ───────────────────────────────────────

    @Test
    fun `joined with turnToken fetches TURN credentials and sets ICE servers`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid", turnToken = "test-turn-token")

        assertTrue(
            "Should fetch TURN credentials",
            factory.fakeAPI.fetchTurnCredentialsCalls.isNotEmpty()
        )
        val call = factory.fakeAPI.fetchTurnCredentialsCalls.first()
        assertEquals("test-turn-token", call.second)
        assertTrue("ICE servers should be set", factory.fakeMedia.iceServersSet)
    }

    // ── TURN credential failure ─────────────────────────────────────

    @Test
    fun `TURN fetch failure falls back to default STUN servers`() {
        factory.fakeAPI.turnCredentialsResult =
            Result.failure(RuntimeException("TURN fetch failed"))

        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid", turnToken = "bad-token")

        assertTrue("Default STUN servers should be applied", factory.fakeMedia.iceServersSet)
    }

    // ── Join timeout ────────────────────────────────────────────────

    @Test
    fun `join hard timeout transitions to Error`() {
        factory.grantPermissionsAndStart()
        // Do not open signaling — session stays in Joining

        assertEquals(CallPhase.Joining, factory.session.state.value.phase)

        // Advance past the join hard timeout
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )

        assertEquals(CallPhase.Error, factory.session.state.value.phase)
    }

    // ── Reconnect backoff ───────────────────────────────────────────

    @Test
    fun `signaling close triggers reconnect after backoff`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        val connectCountBefore = factory.fakeSignaling.connectCalls.size
        factory.fakeSignaling.simulateClosed(reason = "test-disconnect")
        ShadowLooper.idleMainLooper()

        // Advance past the base backoff
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS,
            TimeUnit.MILLISECONDS
        )

        assertTrue(
            "Should reconnect after backoff",
            factory.fakeSignaling.connectCalls.size > connectCountBefore
        )
    }

    // ── Permission grant and resume ─────────────────────────────────

    @Test
    fun `granting permissions and calling resumeJoin transitions to Joining`() {
        factory.startSession()
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.AwaitingPermissions, factory.session.state.value.phase)

        // Grant permissions
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
        )

        factory.session.resumeJoin()
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Joining, factory.session.state.value.phase)
        assertTrue("Media engine should start", factory.fakeMedia.startLocalMediaCalls > 0)
        assertTrue("Audio should be activated", factory.fakeAudio.activateCalls > 0)
        assertTrue("Signaling should connect", factory.fakeSignaling.connectCalls.isNotEmpty())
    }
}
