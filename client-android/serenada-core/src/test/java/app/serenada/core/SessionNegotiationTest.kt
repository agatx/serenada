package app.serenada.core

import app.serenada.core.call.CallPhase
import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.fakes.TestSessionFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionNegotiationTest {

    private lateinit var factory: TestSessionFactory

    @Before fun setUp() { factory = TestSessionFactory() }
    @After fun tearDown() { factory.tearDown() }

    // Group 1: Offer/Answer Exchange

    @Test
    fun `local peer sends offer when its peer ID sorts earlier`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)

        val fakeSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull("Slot should be created", fakeSlot)
        assertTrue("Host should create offer", fakeSlot!!.createOfferCalls > 0)
        assertTrue("Should send offer message", factory.fakeProvider.sentMessages("offer").isNotEmpty())
    }

    @Test
    fun `local peer waits then answers when its peer ID sorts later`() {
        factory.advanceToInCallWithTurn(localCid = "zulu", remoteCid = "alpha", localJoinedAt = 2, remoteJoinedAt = 1)

        assertTrue("Non-host should not send offer", factory.fakeProvider.sentMessages("offer").isEmpty())

        factory.simulateOfferFromRemote("alpha")

        val fakeSlot = factory.fakeMedia.fakeSlots["alpha"]
        assertNotNull(fakeSlot)
        assertEquals(1, fakeSlot!!.setRemoteDescriptionCalls.size)
        assertEquals(org.webrtc.SessionDescription.Type.OFFER, fakeSlot.setRemoteDescriptionCalls.first().first)
        assertTrue("Should create answer", fakeSlot.createAnswerCalls > 0)
        assertTrue("Should send answer", factory.fakeProvider.sentMessages("answer").isNotEmpty())
    }

    @Test
    fun `answer clears pending state`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)

        val fakeSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull(fakeSlot)
        assertTrue(fakeSlot!!.createOfferCalls > 0)

        factory.simulateAnswerFromRemote("remote")

        assertEquals(org.webrtc.SessionDescription.Type.ANSWER, fakeSlot.setRemoteDescriptionCalls.last().first)
        assertFalse("pendingIceRestart should be cleared", fakeSlot.pendingIceRestart)
    }

    // Group 2: ICE Candidate Relay

    @Test
    fun `remote ICE candidate added to slot`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)

        factory.simulateIceCandidateFromRemote("remote", "candidate:test-ice")

        val fakeSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull(fakeSlot)
        assertEquals(1, fakeSlot!!.addedIceCandidates.size)
        assertEquals("candidate:test-ice", fakeSlot.addedIceCandidates.first().sdp)
    }

    // Group 3: Peer Departure

    @Test
    fun `peer leaves via room_state removes slot and transitions to waiting`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)
        assertEquals(CallPhase.InCall, factory.session.state.value.phase)

        factory.simulateRoomState(
            participants = listOf("alpha" to 1L),
            hostCid = "alpha",
        )

        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)
        assertTrue("Slot should be removed", factory.fakeMedia.removedSlots.isNotEmpty())
    }

    // Group 4: Pending Message Buffering

    @Test
    fun `offers processed after ICE servers ready`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()
        factory.simulateJoinedResponse(
            cid = "zulu",
            participants = listOf("zulu" to 2L, "alpha" to 1L),
            hostCid = "alpha",
        )

        val answersBefore = factory.fakeProvider.sentMessages("answer").size
        factory.simulateOfferFromRemote("alpha")

        val answersAfter = factory.fakeProvider.sentMessages("answer").size
        assertTrue("Answer should be sent", answersAfter > answersBefore)
    }

    // Group 5: ICE Restart Triggers

    @Test
    fun `DISCONNECTED schedules ICE restart`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)

        val fakeSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull(fakeSlot)

        fakeSlot!!.simulateConnectionStateChange(PeerConnection.PeerConnectionState.DISCONNECTED)
        ShadowLooper.idleMainLooper()

        assertNotNull("ICE restart task should be scheduled", fakeSlot.iceRestartTask)
    }

    @Test
    fun `FAILED triggers immediate ICE restart`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)

        val fakeSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull(fakeSlot)
        val offersBefore = fakeSlot!!.createOfferCalls

        fakeSlot.simulateConnectionStateChange(PeerConnection.PeerConnectionState.FAILED)
        ShadowLooper.idleMainLooper()
        // Run any immediate tasks
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

        val hasRestarted = fakeSlot.createOfferCalls > offersBefore || fakeSlot.iceRestartTask != null || fakeSlot.pendingIceRestart
        assertTrue("FAILED should trigger ICE restart", hasRestarted)
    }

    @Test
    fun `CONNECTED clears ICE restart`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)

        val fakeSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull(fakeSlot)

        fakeSlot!!.simulateConnectionStateChange(PeerConnection.PeerConnectionState.DISCONNECTED)
        ShadowLooper.idleMainLooper()
        assertNotNull("ICE restart should be scheduled", fakeSlot.iceRestartTask)

        fakeSlot.simulateConnectionStateChange(PeerConnection.PeerConnectionState.CONNECTED)
        ShadowLooper.idleMainLooper()

        assertNull("ICE restart should be cleared", fakeSlot.iceRestartTask)
        assertFalse(fakeSlot.pendingIceRestart)
    }

    // Group 6: shouldIOffer Logic

    @Test
    fun `lexicographically lower peer ID sends offer`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 2, remoteJoinedAt = 1)
        assertTrue("Lower peer ID should offer", factory.fakeProvider.sentMessages("offer").isNotEmpty())
    }

    @Test
    fun `lexicographically higher peer ID does not send offer`() {
        factory.advanceToInCallWithTurn(localCid = "zulu", remoteCid = "alpha", localJoinedAt = 1, remoteJoinedAt = 2)
        assertTrue("Higher peer ID should not offer", factory.fakeProvider.sentMessages("offer").isEmpty())
    }

    // Group 7: Timer-Based (Android-specific via ShadowLooper)

    @Test
    fun `offer timeout triggers ICE restart`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)

        val fakeSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull(fakeSlot)
        val offersBefore = fakeSlot!!.createOfferCalls

        // Advance past offer timeout
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper()

        val hasRestarted = fakeSlot.createOfferCalls > offersBefore || fakeSlot.rollbackCalls > 0 || fakeSlot.pendingIceRestart
        assertTrue("Offer timeout should trigger rollback or ICE restart", hasRestarted)
    }

    @Test
    fun `non-host fallback fires after delay`() {
        factory.advanceToInCallWithTurn(localCid = "zulu", remoteCid = "alpha", localJoinedAt = 2, remoteJoinedAt = 1)

        val fakeSlot = factory.fakeMedia.fakeSlots["alpha"]
        assertNotNull(fakeSlot)

        // Advance past non-host fallback delay
        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.NON_HOST_FALLBACK_DELAY_MS, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper()

        // After fallback, non-host should send an offer
        val offers = factory.fakeProvider.sentMessages("offer")
        assertTrue("Non-host fallback should send offer", offers.isNotEmpty())
    }

    // Group 8: Signaling Reconnect

    @Test
    fun `signaling reconnect with DISCONNECTED peer triggers ICE restart`() {
        factory = TestSessionFactory(handlesReconnection = true)
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)

        val fakeSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull(fakeSlot)
        val offersBefore = fakeSlot!!.createOfferCalls

        // Simulate peer connection degrading when signaling drops
        fakeSlot.simulateConnectionStateChange(PeerConnection.PeerConnectionState.DISCONNECTED)
        ShadowLooper.idleMainLooper()

        factory.fakeProvider.simulateDisconnected("test")
        ShadowLooper.idleMainLooper()

        // Built-in reconnect ownership is provider-managed in this mode.
        factory.openSignaling()
        ShadowLooper.idleMainLooper()

        val hasRestarted = fakeSlot.createOfferCalls > offersBefore || fakeSlot.iceRestartTask != null || fakeSlot.pendingIceRestart
        assertTrue("Reconnect with DISCONNECTED peer should trigger ICE restart", hasRestarted)
    }

    // Additional

    @Test
    fun `slot created for remote participant`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)
        assertTrue(factory.fakeMedia.createdSlotCids.contains("remote"))
        assertNotNull(factory.fakeMedia.fakeSlots["remote"])
    }
}
