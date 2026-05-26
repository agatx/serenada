package app.serenada.core

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

/**
 * Failure-mode #1 from `docs/resilience-failure-modes.md`: when the server
 * sends `negotiation_dirty{with: cid}` after a previously-suspended peer
 * reattaches, the SDK must schedule glare-safe ICE restart for that peer.
 * `relay_failed` is informational — the SDK should not act on it directly
 * (the same dirty-pair condition will surface as `negotiation_dirty` after
 * the target reattaches).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDirtyPairTest {

    private lateinit var factory: TestSessionFactory

    @Before fun setUp() { factory = TestSessionFactory(handlesReconnection = true) }
    @After fun tearDown() { factory.tearDown() }

    @Test
    fun `negotiation_dirty schedules ICE restart for the named peer`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        val slot = factory.fakeMedia.fakeSlots.getValue("remote")
        val baselineOffers = slot.createOfferCalls

        factory.fakeProvider.simulateNegotiationDirty(withCid = "remote")
        ShadowLooper.idleMainLooper()

        // ICE restart machinery either fires a new offer immediately, or
        // marks the slot pending (depending on slot ready state).
        assertTrue(
            "negotiation_dirty should trigger ICE restart for the named peer",
            slot.createOfferCalls > baselineOffers || slot.pendingIceRestart || slot.iceRestartTask != null,
        )
    }

    @Test
    fun `negotiation_dirty is no-op when local is not offerer`() {
        factory.advanceToInCallWithTurn(
            localCid = "zeta",
            remoteCid = "alpha",
            localJoinedAt = 2,
            remoteJoinedAt = 1,
        )
        val slot = factory.fakeMedia.fakeSlots.getValue("alpha")
        val baselineOffers = slot.createOfferCalls

        factory.fakeProvider.simulateNegotiationDirty(withCid = "alpha")
        ShadowLooper.idleMainLooper()

        assertEquals("Non-offerer must not create recovery offers", baselineOffers, slot.createOfferCalls)
        assertFalse(
            "Non-offerer must not wedge on a pending ICE restart it cannot send",
            slot.pendingIceRestart,
        )
    }

    @Test
    fun `negotiation_dirty for unknown peer is a no-op`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        val slot = factory.fakeMedia.fakeSlots.getValue("remote")
        val baselineOffers = slot.createOfferCalls

        factory.fakeProvider.simulateNegotiationDirty(withCid = "stranger")
        ShadowLooper.idleMainLooper()

        assertEquals(
            "Stranger CID should not affect known peer's slot",
            baselineOffers,
            slot.createOfferCalls,
        )
    }

    @Test
    fun `relay_failed is informational and does not schedule ICE restart`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        val slot = factory.fakeMedia.fakeSlots.getValue("remote")
        val baselineOffers = slot.createOfferCalls
        val baselinePending = slot.pendingIceRestart

        factory.fakeProvider.simulateRelayFailed(
            reason = "target_suspended",
            targets = listOf("remote"),
            of = "offer",
        )
        ShadowLooper.idleMainLooper()

        assertEquals(
            "relay_failed should be logged only — no immediate ICE restart",
            baselineOffers,
            slot.createOfferCalls,
        )
        assertEquals(
            "relay_failed should not mark the slot pending",
            baselinePending,
            slot.pendingIceRestart,
        )
    }
}
