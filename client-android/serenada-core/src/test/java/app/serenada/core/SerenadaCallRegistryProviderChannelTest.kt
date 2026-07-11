package app.serenada.core

import app.serenada.core.fakes.FakeMultiSessionSignalingProvider
import app.serenada.core.fakes.RegistryTestHarness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * F2 (contract §"Custom provider"): two managed sessions driven through the REAL
 * [SerenadaCallRegistry] path, each backed by a per-session channel vended by ONE
 * global [FakeMultiSessionSignalingProvider] via the real
 * [SerenadaCore.createSignalingProvider] seam. Asserts the per-session channels stay
 * fully isolated (CIDs, room state, peer events, outbound ops) and that tearing one
 * down never touches the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SerenadaCallRegistryProviderChannelTest {

    private val harnesses = mutableListOf<RegistryTestHarness>()

    @Before
    fun setUp() {
        ForegroundMediaArbiter.resetForTests()
    }

    @After
    fun tearDown() {
        harnesses.forEach { it.tearDown() }
        harnesses.clear()
        ForegroundMediaArbiter.resetForTests()
    }

    private fun room(id: String): RoomRef = RoomRef.Id(roomId = id)

    private fun harness(service: FakeMultiSessionSignalingProvider): RegistryTestHarness {
        val h = RegistryTestHarness(multiSessionProvider = service)
        harnesses.add(h)
        return h
    }

    /** Foreground call "a" + held call "b", each on its own vended channel. */
    private fun twoCalls(
        service: FakeMultiSessionSignalingProvider,
        h: RegistryTestHarness,
    ): Pair<String, String> {
        val a = (h.joinAndSwitch(room("a")) as JoinAndSwitchResult.Active).callId
        ShadowLooper.idleMainLooper()
        val b = (h.joinHeld(room("b")) as JoinResult.Joined).callId
        ShadowLooper.idleMainLooper()
        return a to b
    }

    @Test
    fun `openSession is called once per session with the canonical room id`() {
        val service = FakeMultiSessionSignalingProvider()
        val h = harness(service)
        twoCalls(service, h)

        // One channel per session, distinct, bound to the canonical room ids.
        assertEquals(listOf("a", "b"), service.openSessionRoomIds)
        assertEquals(2, service.channels.size)
        assertNotSame(service.channelFor("a"), service.channelFor("b"))
    }

    @Test
    fun `joined CIDs and outbound joins stay isolated per channel`() {
        val service = FakeMultiSessionSignalingProvider()
        val h = harness(service)
        twoCalls(service, h)

        val sessA = h.created["a"]!!.session
        val sessB = h.created["b"]!!.session
        // Each session sees ONLY its own CID (settle vends "cid-<room>").
        assertEquals("cid-a", sessA.state.value.localCid)
        assertEquals("cid-b", sessB.state.value.localCid)

        // Outbound joinRoom hit the correct channel only.
        assertEquals(listOf("a"), service.channelFor("a")!!.joinCalls.map { it.first })
        assertEquals(listOf("b"), service.channelFor("b")!!.joinCalls.map { it.first })
    }

    @Test
    fun `a peer event on one channel never reaches the other session`() {
        val service = FakeMultiSessionSignalingProvider()
        val h = harness(service)
        twoCalls(service, h)

        val sessA = h.created["a"]!!.session
        val sessB = h.created["b"]!!.session
        val beforeA = sessA.state.value.participantCount
        val beforeB = sessB.state.value.participantCount

        service.channelFor("a")!!.simulatePeerJoined("remote-a-2", 9L)
        ShadowLooper.idleMainLooper()

        assertTrue("a saw its own peer", sessA.state.value.participantCount > beforeA)
        assertEquals("b unaffected by a's peer", beforeB, sessB.state.value.participantCount)
    }

    @Test
    fun `leaving one call closes only its channel and leaves the other connected`() {
        val service = FakeMultiSessionSignalingProvider()
        val h = harness(service)
        val (a, _) = twoCalls(service, h)

        val chA = service.channelFor("a")!!
        val chB = service.channelFor("b")!!
        val sessB = h.created["b"]!!.session

        h.leaveCall(a)
        ShadowLooper.idleMainLooper()

        // A's channel got the outbound leave + a single close; B's channel is untouched.
        assertTrue(chA.leaveCalls >= 1)
        assertEquals(1, chA.disconnectCalls)
        assertEquals(0, chB.leaveCalls)
        assertEquals(0, chB.disconnectCalls)

        // B still receives events on its live channel.
        val beforeB = sessB.state.value.participantCount
        chB.simulatePeerJoined("remote-b-2", 11L)
        ShadowLooper.idleMainLooper()
        assertTrue("b still live", sessB.state.value.participantCount > beforeB)
    }

    @Test
    fun `diagnostic getIceServers is service-scoped not channel-scoped`() {
        val service = FakeMultiSessionSignalingProvider()
        val h = harness(service)
        twoCalls(service, h)

        val chA = service.channelFor("a")!!
        val chB = service.channelFor("b")!!
        val chAIceBefore = chA.getIceServersCalls
        val chBIceBefore = chB.getIceServersCalls

        // The session-less diagnostics ICE path hits the service, NOT any channel.
        assertEquals(0, service.getIceServersCalls)
        runBlocking { service.getIceServers() }
        assertEquals(1, service.getIceServersCalls)

        // The diagnostics fetch left both per-session channels untouched: ICE is
        // channel-scoped for calls and service-scoped for diagnostics, never crossed.
        assertEquals(chAIceBefore, chA.getIceServersCalls)
        assertEquals(chBIceBefore, chB.getIceServersCalls)
    }

    @Test
    fun `each channel is closed exactly once at teardown and tolerates a repeat close`() {
        val service = FakeMultiSessionSignalingProvider()
        val h = harness(service)
        val (a, b) = twoCalls(service, h)

        h.leaveCall(a)
        h.leaveCall(b)
        ShadowLooper.idleMainLooper()

        val chA = service.channelFor("a")!!
        val chB = service.channelFor("b")!!
        assertEquals(1, chA.disconnectCalls)
        assertEquals(1, chB.disconnectCalls)

        // Idempotence: a second close is tolerated (no throw).
        chA.disconnect()
        assertEquals(2, chA.disconnectCalls)
    }

    @Test
    fun `a stale event into a closed channel is observed by no session`() {
        val service = FakeMultiSessionSignalingProvider()
        val h = harness(service)
        val (a, _) = twoCalls(service, h)

        val sessA = h.created["a"]!!.session
        val chA = service.channelFor("a")!!

        h.leaveCall(a)
        ShadowLooper.idleMainLooper()
        val phaseAfterLeave = sessA.state.value.phase
        val countAfterLeave = sessA.state.value.participantCount

        // The channel is closed; a late/queued event must not reach the torn-down
        // session (channel-generation guard). No crash, no state change.
        chA.simulatePeerJoined("ghost", 99L)
        ShadowLooper.idleMainLooper()

        assertEquals(phaseAfterLeave, sessA.state.value.phase)
        assertEquals(countAfterLeave, sessA.state.value.participantCount)
    }
}
