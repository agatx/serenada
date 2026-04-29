package app.serenada.core

import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.fakes.TestSessionFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Failure modes #3 (per-CID UI presentation timer for suspended remote peers)
 * and #6 ([SignalingState] surface for the local transport) from
 * `docs/resilience-failure-modes.md`. Verifies that:
 *  - A remote peer in `SUSPENDED` flips `presumedLost=true` after 30s.
 *  - The flag clears when the peer reattaches or leaves the room.
 *  - Local [SignalingState] tracks connected → suspended transitions with
 *    a hard-eviction estimate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionSuspendedSurfaceTest {

    private lateinit var factory: TestSessionFactory

    @Before fun setUp() { factory = TestSessionFactory(handlesReconnection = true) }
    @After fun tearDown() { factory.tearDown() }

    private fun participant(cid: String, joinedAt: Long, status: ParticipantSignalingStatus): SignalingProviderParticipant {
        return SignalingProviderParticipant(peerId = cid, joinedAt = joinedAt, connectionStatus = status)
    }

    @Test
    fun `remote peer flips presumedLost after PEER_SUSPENDED_UI_TIMEOUT_MS suspended`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )

        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                participant("alpha", 1, ParticipantSignalingStatus.ACTIVE),
                participant("remote", 2, ParticipantSignalingStatus.SUSPENDED),
            ),
            hostPeerId = "alpha",
        )
        ShadowLooper.idleMainLooper()

        val remote = factory.session.state.value.remoteParticipants.single { it.cid == "remote" }
        assertEquals(ParticipantSignalingStatus.SUSPENDED, remote.signalingStatus)
        assertFalse("Should not be presumed lost yet", remote.presumedLost)

        // Just before timeout: still not presumed lost
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.PEER_SUSPENDED_UI_TIMEOUT_MS - 1,
            TimeUnit.MILLISECONDS,
        )
        assertEquals(0, factory.session.presumedLostRemoteCount())

        // Cross the threshold
        ShadowLooper.idleMainLooper(2, TimeUnit.MILLISECONDS)
        assertEquals(1, factory.session.presumedLostRemoteCount())
        val flagged = factory.session.state.value.remoteParticipants.single { it.cid == "remote" }
        assertTrue("Should now be presumed lost", flagged.presumedLost)
    }

    @Test
    fun `presumedLost clears when peer reattaches as active`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )

        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                participant("alpha", 1, ParticipantSignalingStatus.ACTIVE),
                participant("remote", 2, ParticipantSignalingStatus.SUSPENDED),
            ),
            hostPeerId = "alpha",
        )
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.PEER_SUSPENDED_UI_TIMEOUT_MS + 100,
            TimeUnit.MILLISECONDS,
        )
        assertEquals(1, factory.session.presumedLostRemoteCount())

        // Peer reattaches
        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                participant("alpha", 1, ParticipantSignalingStatus.ACTIVE),
                participant("remote", 2, ParticipantSignalingStatus.ACTIVE),
            ),
            hostPeerId = "alpha",
        )
        ShadowLooper.idleMainLooper()

        val remote = factory.session.state.value.remoteParticipants.single { it.cid == "remote" }
        assertEquals(ParticipantSignalingStatus.ACTIVE, remote.signalingStatus)
        assertFalse(remote.presumedLost)
        assertEquals(0, factory.session.presumedLostRemoteCount())
    }

    @Test
    fun `local signalingState transitions to Suspended on transport drop and back to Connected`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        assertEquals(SignalingState.Connected, factory.session.currentSignalingState())

        factory.fakeProvider.simulateDisconnected()
        ShadowLooper.idleMainLooper()

        val suspended = factory.session.currentSignalingState()
        assertTrue(
            "Expected Suspended, got $suspended",
            suspended is SignalingState.Suspended,
        )
        if (suspended is SignalingState.Suspended) {
            assertEquals(
                suspended.suspendedSinceMs + WebRtcResilienceConstants.SUSPEND_HARD_EVICTION_TIMEOUT_MS,
                suspended.estimatedHardEvictionAtMs,
            )
        }

        factory.fakeProvider.simulateConnected("ws")
        ShadowLooper.idleMainLooper()
        assertEquals(SignalingState.Connected, factory.session.currentSignalingState())
    }

    @Test
    fun `subsequent room_state updates with peer still suspended do not reschedule timer`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )

        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                participant("alpha", 1, ParticipantSignalingStatus.ACTIVE),
                participant("remote", 2, ParticipantSignalingStatus.SUSPENDED),
            ),
            hostPeerId = "alpha",
        )
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.PEER_SUSPENDED_UI_TIMEOUT_MS + 100,
            TimeUnit.MILLISECONDS,
        )
        assertEquals(1, factory.session.presumedLostRemoteCount())

        // Several more room_state updates arrive while peer remains suspended.
        // Should not re-arm a new timer (presumed-lost is sticky).
        repeat(3) {
            factory.fakeProvider.simulateRoomStateUpdatedWith(
                participants = listOf(
                    participant("alpha", 1, ParticipantSignalingStatus.ACTIVE),
                    participant("remote", 2, ParticipantSignalingStatus.SUSPENDED),
                ),
                hostPeerId = "alpha",
            )
            ShadowLooper.idleMainLooper(
                WebRtcResilienceConstants.PEER_SUSPENDED_UI_TIMEOUT_MS + 100,
                TimeUnit.MILLISECONDS,
            )
        }

        assertEquals(1, factory.session.presumedLostRemoteCount())
    }

    @Test
    fun `presumedLost tracking clears when a presumed-lost peer leaves the room`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )

        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                participant("alpha", 1, ParticipantSignalingStatus.ACTIVE),
                participant("remote", 2, ParticipantSignalingStatus.SUSPENDED),
            ),
            hostPeerId = "alpha",
        )
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.PEER_SUSPENDED_UI_TIMEOUT_MS + 100,
            TimeUnit.MILLISECONDS,
        )
        assertEquals(1, factory.session.presumedLostRemoteCount())

        // Peer leaves entirely
        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                participant("alpha", 1, ParticipantSignalingStatus.ACTIVE),
            ),
            hostPeerId = "alpha",
        )
        ShadowLooper.idleMainLooper()

        assertEquals(0, factory.session.presumedLostRemoteCount())
    }

    @Test
    fun `local signalingState reports Failed on terminal error`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )

        factory.fakeProvider.simulateError(code = "ROOM_ENDED", message = "Room is gone")
        ShadowLooper.idleMainLooper()

        val state = factory.session.currentSignalingState()
        assertNotNull(state)
        assertTrue("Expected Failed, got $state", state is SignalingState.Failed)
        if (state is SignalingState.Failed) {
            assertEquals(CallError.RoomEnded, state.reason)
        }
    }
}
