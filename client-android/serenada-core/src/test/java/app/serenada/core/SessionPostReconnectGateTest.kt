package app.serenada.core

import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.fakes.TestSessionFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Failure-mode #4 from `docs/resilience-failure-modes.md`: SDK must defer ICE
 * restart on signaling reconnect until the authoritative post-reconnect
 * `room_state` snapshot lands. On a 5s timeout, the SDK falls back to firing
 * against the last-known peer map (graceful degradation to pre-#4 behavior).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPostReconnectGateTest {

    private lateinit var factory: TestSessionFactory

    @Before fun setUp() { factory = TestSessionFactory(handlesReconnection = true) }
    @After fun tearDown() { factory.tearDown() }

    @Test
    fun `reconnect arms gate but does not fire ICE restart immediately`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        val baselineFires = factory.session.postReconnectResyncFireCount()

        factory.fakeProvider.simulateDisconnected()
        ShadowLooper.idleMainLooper()
        factory.fakeProvider.simulateConnected("ws")
        ShadowLooper.idleMainLooper()

        assertTrue("Gate should be armed after reconnect", factory.session.isPostReconnectResyncPending())
        assertEquals(
            "ICE restart should not fire before snapshot",
            baselineFires,
            factory.session.postReconnectResyncFireCount(),
        )
    }

    @Test
    fun `post-reconnect room_state snapshot flushes the gate`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        val baselineFires = factory.session.postReconnectResyncFireCount()

        factory.fakeProvider.simulateDisconnected()
        ShadowLooper.idleMainLooper()
        factory.fakeProvider.simulateConnected("ws")
        ShadowLooper.idleMainLooper()

        factory.simulateRoomState(
            participants = listOf("alpha" to 1L, "remote" to 2L),
            hostCid = "alpha",
        )

        assertFalse("Gate should clear after snapshot", factory.session.isPostReconnectResyncPending())
        assertEquals(
            "Snapshot should fire exactly one ICE restart",
            baselineFires + 1,
            factory.session.postReconnectResyncFireCount(),
        )
    }

    @Test
    fun `gate falls back to ICE restart on epoch resync timeout`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        val baselineFires = factory.session.postReconnectResyncFireCount()

        factory.fakeProvider.simulateDisconnected()
        ShadowLooper.idleMainLooper()
        factory.fakeProvider.simulateConnected("ws")
        ShadowLooper.idleMainLooper()

        // Still pending before timeout.
        assertTrue(factory.session.isPostReconnectResyncPending())
        assertEquals(baselineFires, factory.session.postReconnectResyncFireCount())

        // Advance past the 5s resync timeout.
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.EPOCH_RESYNC_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )

        assertFalse("Gate should clear after timeout", factory.session.isPostReconnectResyncPending())
        assertEquals(
            "Timeout should fire ICE restart fallback",
            baselineFires + 1,
            factory.session.postReconnectResyncFireCount(),
        )
    }

    @Test
    fun `gate timeout does not make non-offerer send recovery offer`() {
        factory.advanceToInCallWithTurn(
            localCid = "zeta",
            remoteCid = "alpha",
            localJoinedAt = 2,
            remoteJoinedAt = 1,
        )
        val slot = factory.fakeMedia.fakeSlots.getValue("alpha")
        val baselineFires = factory.session.postReconnectResyncFireCount()
        val baselineOffers = slot.createOfferCalls

        factory.fakeProvider.simulateDisconnected()
        ShadowLooper.idleMainLooper()
        factory.fakeProvider.simulateConnected("ws")
        ShadowLooper.idleMainLooper()

        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.EPOCH_RESYNC_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )

        assertFalse("Gate should clear after timeout", factory.session.isPostReconnectResyncPending())
        assertEquals(baselineFires + 1, factory.session.postReconnectResyncFireCount())
        assertEquals("Non-offerer must not create recovery offers", baselineOffers, slot.createOfferCalls)
        assertFalse(
            "Non-offerer must not wedge on a pending ICE restart it cannot send",
            slot.pendingIceRestart,
        )
    }

    @Test
    fun `subsequent room_state updates do not double-fire`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        val baselineFires = factory.session.postReconnectResyncFireCount()

        factory.fakeProvider.simulateDisconnected()
        ShadowLooper.idleMainLooper()
        factory.fakeProvider.simulateConnected("ws")
        ShadowLooper.idleMainLooper()

        factory.simulateRoomState(
            participants = listOf("alpha" to 1L, "remote" to 2L),
            hostCid = "alpha",
        )
        val afterFirst = factory.session.postReconnectResyncFireCount()

        // A later room_state (e.g. peer mute/unmute) should not retrigger.
        factory.simulateRoomState(
            participants = listOf("alpha" to 1L, "remote" to 2L),
            hostCid = "alpha",
        )

        assertEquals(
            "Only the first post-reconnect snapshot fires the gated restart",
            afterFirst,
            factory.session.postReconnectResyncFireCount(),
        )
        assertEquals(baselineFires + 1, factory.session.postReconnectResyncFireCount())
    }
}
