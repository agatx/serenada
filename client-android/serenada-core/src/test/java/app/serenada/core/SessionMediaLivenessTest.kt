package app.serenada.core

import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.fakes.TestSessionFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Failure mode #3 from `docs/resilience-failure-modes.md` — periodic
 * `media_liveness{cids}` emission so the server can defer hard-eviction of
 * suspended peers whose media is still flowing locally. Verifies that:
 *   - The SDK broadcasts `media_liveness` when inbound bytes advance for a
 *     peer.
 *   - Emission skips peers with no flow.
 *   - Emission stops after `session.leave()` (terminal cleanup path).
 *   - Emission pauses while transport is disconnected and resumes after
 *     reconnect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionMediaLivenessTest {

    private lateinit var factory: TestSessionFactory

    @Before fun setUp() { factory = TestSessionFactory(handlesReconnection = true) }
    @After fun tearDown() { factory.tearDown() }

    private fun livenessBroadcasts() = factory.fakeProvider.sentProviderMessages
        .filter { it.isBroadcast && it.type == "media_liveness" }

    @Test
    fun `broadcasts media_liveness with flowing CIDs after inbound bytes advance`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        // Baseline tick: bytes=0 → no flow.
        factory.fakeMedia.fakeSlots["remote"]?.inboundBytesSample = 0
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        val baseline = livenessBroadcasts().size

        // Bytes advance → flow detected → broadcast.
        factory.fakeMedia.fakeSlots["remote"]?.inboundBytesSample = 5_000
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS, TimeUnit.MILLISECONDS)

        val broadcasts = livenessBroadcasts()
        assertEquals(baseline + 1, broadcasts.size)
        val payload = broadcasts.last().payload
        val cids = payload?.optJSONArray("cids")
        assertEquals(1, cids?.length() ?: 0)
        assertEquals("remote", cids?.optString(0))
    }

    @Test
    fun `skips broadcast when no peer is currently flowing`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        factory.fakeMedia.fakeSlots["remote"]?.inboundBytesSample = 0
        // Several ticks with no growth.
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS * 3, TimeUnit.MILLISECONDS)

        assertEquals(0, livenessBroadcasts().size)
        assertTrue(
            "Slot should have been polled at least once",
            (factory.fakeMedia.fakeSlots["remote"]?.collectInboundBytesCalls ?: 0) > 0,
        )
    }

    @Test
    fun `stops emitting after session leave`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        factory.fakeMedia.fakeSlots["remote"]?.inboundBytesSample = 1_000
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        factory.fakeMedia.fakeSlots["remote"]?.inboundBytesSample = 5_000
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        val baseline = livenessBroadcasts().size
        assertTrue("Expected at least one broadcast before leave", baseline >= 1)

        factory.session.leave()
        ShadowLooper.idleMainLooper()

        // Advance well past several would-be tick intervals — no further emits.
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS * 3, TimeUnit.MILLISECONDS)
        assertEquals(baseline, livenessBroadcasts().size)
    }

    @Test
    fun `pauses while transport is disconnected and resumes after reconnect`() {
        factory.advanceToInCallWithTurn(
            localCid = "alpha",
            remoteCid = "remote",
            localJoinedAt = 1,
            remoteJoinedAt = 2,
        )
        // Establish a baseline so the first broadcast can fire.
        factory.fakeMedia.fakeSlots["remote"]?.inboundBytesSample = 1_000
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        factory.fakeMedia.fakeSlots["remote"]?.inboundBytesSample = 5_000
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        val beforeDisconnect = livenessBroadcasts().size
        assertTrue("Expected at least one broadcast before disconnect", beforeDisconnect >= 1)

        // Transport drops — subsequent ticks must not broadcast.
        factory.fakeProvider.simulateDisconnected()
        ShadowLooper.idleMainLooper()
        factory.fakeMedia.fakeSlots["remote"]?.inboundBytesSample = 10_000
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS * 2, TimeUnit.MILLISECONDS)
        assertEquals(beforeDisconnect, livenessBroadcasts().size)

        // Reconnect — next tick should broadcast again.
        factory.fakeProvider.simulateConnected("ws")
        ShadowLooper.idleMainLooper()
        factory.fakeMedia.fakeSlots["remote"]?.inboundBytesSample = 20_000
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        assertTrue(
            "Expected another broadcast after reconnect",
            livenessBroadcasts().size > beforeDisconnect,
        )
    }
}
