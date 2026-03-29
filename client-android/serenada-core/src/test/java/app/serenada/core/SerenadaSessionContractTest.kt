package app.serenada.core

import app.serenada.core.call.CallPhase
import app.serenada.core.call.WebRtcResilienceConstants
import android.os.Looper
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
import org.webrtc.PeerConnection
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

        assertTrue("Should request room join", factory.fakeProvider.joinCalls.isNotEmpty())

        factory.simulateJoinedResponse(cid = "my-cid")

        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)
        assertEquals("my-cid", factory.session.state.value.localCid)
    }

    @Test
    fun `joined without hostPeerId falls back to local participant`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.fakeProvider.simulateJoined(
            peerId = "my-cid",
            participants = listOf("my-cid" to 1L),
            hostPeerId = null,
        )
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)
        assertTrue(factory.session.state.value.isHost)
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
        assertNotNull(factory.session.state.value.error)
        assertTrue(factory.session.state.value.error is CallError.RoomFull)
        assertTrue("Engine should be released", factory.fakeMedia.releaseCalls > 0)
        assertTrue("Provider should be disconnected", factory.fakeProvider.disconnectCalls > 0)
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

    @Test
    fun `incremental peer join and leave work without room_state snapshots`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        factory.fakeProvider.simulatePeerJoined(peerId = "remote-cid", joinedAt = 2L)
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.InCall, factory.session.state.value.phase)
        assertEquals(listOf("remote-cid"), factory.session.state.value.remoteParticipants.map { it.cid })

        factory.fakeProvider.simulatePeerLeft(peerId = "remote-cid", joinedAt = 2L)
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)
        assertTrue(factory.session.state.value.remoteParticipants.isEmpty())
    }

    // ── Reconnect on close ──────────────────────────────────────────

    @Test
    fun `signaling close during call schedules reconnect`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        factory.fakeProvider.simulateDisconnected(reason = "connection lost")
        ShadowLooper.idleMainLooper()

        assertNotEquals(CallPhase.Idle, factory.session.state.value.phase)
        assertFalse(factory.session.diagnostics.value.isSignalingConnected)
    }

    @Test
    fun `self-managed reconnect rejoins with reconnect peer id`() {
        factory.tearDown()
        factory = TestSessionFactory(handlesReconnection = false)
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )
        assertEquals(CallPhase.InCall, factory.session.state.value.phase)

        factory.fakeProvider.simulateDisconnected(reason = "connection lost")
        ShadowLooper.idleMainLooper()

        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper()

        assertEquals(2, factory.fakeProvider.connectCalls.size)

        factory.fakeProvider.simulateConnected()
        ShadowLooper.idleMainLooper()

        val reconnectJoin = factory.fakeProvider.joinCalls.last()
        assertEquals("test-room-id", reconnectJoin.first)
        assertEquals("my-cid", reconnectJoin.second.reconnectPeerId)
    }

    @Test
    fun `room ended resets session to idle`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )
        assertEquals(CallPhase.InCall, factory.session.state.value.phase)

        factory.fakeProvider.simulateRoomEnded(by = "remote-cid", reason = "host ended")
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Idle, factory.session.state.value.phase)
        assertTrue(factory.session.state.value.remoteParticipants.isEmpty())
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

        assertTrue("Should call provider leaveRoom", factory.fakeProvider.leaveCalls > 0)
        assertEquals(CallPhase.Idle, factory.session.state.value.phase)
        assertTrue("Provider should be disconnected", factory.fakeProvider.disconnectCalls > 0)
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

        assertTrue("Should call provider endRoom", factory.fakeProvider.endCalls > 0)
        assertTrue("Should still leave after end", factory.fakeProvider.leaveCalls > 0)
        assertEquals(CallPhase.Idle, factory.session.state.value.phase)
    }

    // ── TURN credential fetch ───────────────────────────────────────

    @Test
    fun `joined triggers initial ICE fetch and sets ICE servers`() {
        factory.fakeProvider.enqueueIceServers(
            Result.success(
                listOf(
                    org.webrtc.PeerConnection.IceServer.builder("turn:turn.example.com:3478")
                        .setUsername("user")
                        .setPassword("pass")
                        .createIceServer()
                )
            )
        )
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")

        assertEquals(1, factory.fakeProvider.getIceServersCalls)
        assertTrue("ICE servers should be set", factory.fakeMedia.iceServersSet)
    }

    // ── TURN credential failure ─────────────────────────────────────

    @Test
    fun `empty ICE server list falls back to default STUN servers`() {
        factory.fakeProvider.enqueueIceServers(Result.success(emptyList()))
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")

        assertTrue("Default STUN servers should be applied", factory.fakeMedia.iceServersSet)
    }

    @Test
    fun `iceServersChanged updates existing and future peer slots`() {
        factory.fakeProvider.enqueueIceServers(
            Result.success(
                listOf(
                    PeerConnection.IceServer.builder("turn:initial.example.com:3478")
                        .setUsername("user")
                        .setPassword("pass")
                        .createIceServer()
                )
            )
        )
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-a" to 2L),
            hostCid = "my-cid",
        )

        val existingSlot = factory.fakeMedia.fakeSlots.getValue("remote-a")
        assertTrue(existingSlot.appliedIceServerUrls.any { it == listOf("turn:initial.example.com:3478") })

        factory.fakeProvider.simulateIceServersChanged(
            listOf(
                PeerConnection.IceServer.builder("turn:refreshed.example.com:3478")
                    .setUsername("next-user")
                    .setPassword("next-pass")
                    .createIceServer()
            )
        )
        ShadowLooper.idleMainLooper()

        assertEquals(
            listOf("turn:refreshed.example.com:3478"),
            existingSlot.appliedIceServerUrls.last()
        )

        factory.fakeProvider.simulatePeerJoined(peerId = "remote-b", joinedAt = 3L)
        ShadowLooper.idleMainLooper()

        val futureSlot = factory.fakeMedia.fakeSlots.getValue("remote-b")
        assertEquals(
            listOf("turn:refreshed.example.com:3478"),
            futureSlot.appliedIceServerUrls.last()
        )
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

        val connectCountBefore = factory.fakeProvider.connectCalls.size
        factory.fakeProvider.simulateDisconnected(reason = "test-disconnect")
        ShadowLooper.idleMainLooper()

        // Advance past the base backoff
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS,
            TimeUnit.MILLISECONDS
        )

        assertTrue(
            "Should reconnect after backoff",
            factory.fakeProvider.connectCalls.size > connectCountBefore
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
        assertTrue("Provider should connect", factory.fakeProvider.connectCalls.isNotEmpty())
    }
}
