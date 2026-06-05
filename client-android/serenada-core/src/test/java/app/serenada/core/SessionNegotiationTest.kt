package app.serenada.core

import app.serenada.core.ParticipantSignalingStatus
import app.serenada.core.SignalingProviderParticipant
import app.serenada.core.call.CallPhase
import app.serenada.core.call.OutboundMediaSample
import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.fakes.FakePeerConnectionSlot
import app.serenada.core.fakes.SentProviderMessage
import app.serenada.core.fakes.TestSessionFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionNegotiationTest {

    private lateinit var factory: TestSessionFactory

    private data class NegotiationScenario(
        val id: String,
        val localCid: String,
        val remoteCid: String,
    )

    @Before fun setUp() { factory = TestSessionFactory() }
    @After fun tearDown() { factory.tearDown() }

    private fun sharedNegotiationScenarios(): List<NegotiationScenario> {
        val file = listOf(
            File("test-fixtures/peer-negotiation-scenarios.json"),
            File("../test-fixtures/peer-negotiation-scenarios.json"),
            File("../../test-fixtures/peer-negotiation-scenarios.json"),
        ).firstOrNull { it.isFile } ?: error("Missing shared peer negotiation scenarios")
        val scenarios = JSONObject(file.readText()).getJSONArray("scenarios")
        return (0 until scenarios.length()).map { index ->
            val scenario = scenarios.getJSONObject(index)
            NegotiationScenario(
                id = scenario.getString("id"),
                localCid = scenario.getString("localCid"),
                remoteCid = scenario.getString("remoteCid"),
            )
        }
    }

    private fun resetFactory() {
        factory.tearDown()
        factory = TestSessionFactory()
    }

    private fun latestOfferId(): String {
        return factory.fakeProvider.sentMessages("offer").last().payload!!.getString("offerId")
    }

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

    @Test
    fun `remote ICE candidate without sdpMid is forwarded with null mid so WebRTC uses m-line index`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)

        factory.simulateIceCandidateFromRemote(
            fromCid = "remote",
            candidate = "candidate:test-ice",
            sdpMid = null,
            sdpMLineIndex = 1,
        )

        val fakeSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull(fakeSlot)
        assertEquals(1, fakeSlot!!.addedIceCandidates.size)
        val added = fakeSlot.addedIceCandidates.first()
        assertNull(added.sdpMid)
        assertEquals(1, added.sdpMLineIndex)
    }

    @Test
    fun `remote ICE candidate with blank sdp is dropped before reaching slot`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)

        factory.simulateIceCandidateFromRemote(fromCid = "remote", candidate = "")
        factory.simulateIceCandidateFromRemote(fromCid = "remote", candidate = "   ")

        val fakeSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull(fakeSlot)
        assertTrue("Blank candidates must not reach the slot", fakeSlot!!.addedIceCandidates.isEmpty())
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
        val removedSlot = factory.fakeMedia.removedSlots.single() as FakePeerConnectionSlot
        assertTrue("Removed slot should be closed", removedSlot.closePeerConnectionCalled)
        assertTrue("Peer departure should defer native dispose", removedSlot.closePeerConnectionDeferredDispose)
    }

    @Test
    fun `late signaling from departed peer does not recreate slot`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)
        val removedPeerSlot = factory.fakeMedia.fakeSlots["remote"]
        assertNotNull(removedPeerSlot)

        factory.simulateRoomState(
            participants = listOf("alpha" to 1L),
            hostCid = "alpha",
        )

        val answersBefore = factory.fakeProvider.sentMessages("answer").size
        val createdSlotsBefore = factory.fakeMedia.createdSlotCids.size
        factory.simulateIceCandidateFromRemote("remote", "candidate:late")
        factory.simulateOfferFromRemote("remote", "late-offer-sdp")

        assertFalse("Departed peer slot should stay removed", factory.fakeMedia.fakeSlots.containsKey("remote"))
        assertEquals("Late signaling must not create a new slot", createdSlotsBefore, factory.fakeMedia.createdSlotCids.size)
        assertEquals("Late offer must not be answered", answersBefore, factory.fakeProvider.sentMessages("answer").size)
        assertTrue("Original slot should stay untouched by late ICE", removedPeerSlot!!.addedIceCandidates.isEmpty())
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
    fun `non-offerer does not send fallback offer after delay`() {
        factory.advanceToInCallWithTurn(localCid = "zulu", remoteCid = "alpha", localJoinedAt = 2, remoteJoinedAt = 1)

        val fakeSlot = factory.fakeMedia.fakeSlots["alpha"]
        assertNotNull(fakeSlot)

        ShadowLooper.idleMainLooper(WebRtcResilienceConstants.OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper()

        val offers = factory.fakeProvider.sentMessages("offer")
        assertTrue("Non-offerer must not create fallback offers", offers.isEmpty())
    }

    @Test
    fun `designated offerer restarts when peer reattaches`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "zeta", localJoinedAt = 1, remoteJoinedAt = 2)
        val slot = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(slot)
        factory.simulateAnswerFromRemote("zeta", offerId = latestOfferId())
        val offersBefore = slot!!.createOfferCalls

        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                SignalingProviderParticipant(peerId = "alpha", joinedAt = 1),
                SignalingProviderParticipant(peerId = "zeta", joinedAt = 2, connectionStatus = ParticipantSignalingStatus.SUSPENDED),
            ),
            hostPeerId = "alpha",
        )
        ShadowLooper.idleMainLooper()

        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                SignalingProviderParticipant(peerId = "alpha", joinedAt = 1),
                SignalingProviderParticipant(peerId = "zeta", joinedAt = 2, connectionStatus = ParticipantSignalingStatus.ACTIVE),
            ),
            hostPeerId = "alpha",
        )
        ShadowLooper.idleMainLooper()

        assertTrue("Designated offerer should restart after peer reattaches", slot.createOfferCalls > offersBefore)
    }

    @Test
    fun `designated offerer recreates peer after media restart request`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "zeta", localJoinedAt = 1, remoteJoinedAt = 2)
        val oldSlot = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(oldSlot)
        factory.simulateAnswerFromRemote("zeta", offerId = latestOfferId())
        val offersBefore = factory.fakeProvider.sentMessages("offer").size

        factory.fakeProvider.simulateMessage(
            from = "zeta",
            type = "media_restart_request",
            payload = JSONObject().apply {
                put("from", "zeta")
                put("reason", "stalled outbound media")
            },
        )
        ShadowLooper.idleMainLooper()

        val replacement = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(replacement)
        assertNotSame("Media restart should replace the stale peer slot", oldSlot, replacement)
        assertTrue("Old slot should be closed", oldSlot!!.closePeerConnectionCalled)
        assertTrue("Replacement should send a fresh offer", replacement!!.createOfferCalls > 0)
        assertEquals("Exactly one fresh offer should be sent", offersBefore + 1, factory.fakeProvider.sentMessages("offer").size)
    }

    @Test
    fun `designated offerer sends normal offer for local track negotiation request`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "zeta", localJoinedAt = 1, remoteJoinedAt = 2)
        val slot = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(slot)
        factory.simulateAnswerFromRemote("zeta", offerId = latestOfferId())
        val offersBefore = factory.fakeProvider.sentMessages("offer").size
        val slotOffersBefore = slot!!.createOfferCalls

        factory.fakeProvider.simulateMessage(
            from = "zeta",
            type = "media_restart_request",
            payload = JSONObject().apply {
                put("from", "zeta")
                put("reason", "local track negotiation")
            },
        )
        ShadowLooper.idleMainLooper()

        assertSame("Local track negotiation should keep the existing peer slot", slot, factory.fakeMedia.fakeSlots["zeta"])
        assertFalse("Existing peer slot must not be closed", slot.closePeerConnectionCalled)
        assertEquals("Exactly one normal offer should be sent", offersBefore + 1, factory.fakeProvider.sentMessages("offer").size)
        assertEquals("Existing slot should create the offer", slotOffersBefore + 1, slot.createOfferCalls)
        assertEquals("Local track negotiation must not request ICE restart", false, slot.createOfferIceRestartFlags.last())
    }

    @Test
    fun `non offerer requests local track negotiation offer when renegotiation is needed`() {
        factory.advanceToInCallWithTurn(localCid = "zeta", remoteCid = "alpha", localJoinedAt = 2, remoteJoinedAt = 1)
        factory.simulateOfferFromRemote("alpha", offerId = "remote-offer")
        ShadowLooper.idleMainLooper()
        val slot = factory.fakeMedia.fakeSlots["alpha"]
        assertNotNull(slot)
        val offersBefore = factory.fakeProvider.sentMessages("offer").size

        slot!!.simulateRenegotiationNeeded()
        ShadowLooper.idleMainLooper()

        assertSame("Local track negotiation should keep the existing peer slot", slot, factory.fakeMedia.fakeSlots["alpha"])
        assertFalse("Existing peer slot must not be closed", slot.closePeerConnectionCalled)
        assertEquals("Non-offerer must not send an offer directly", offersBefore, factory.fakeProvider.sentMessages("offer").size)
        val restartRequests = factory.fakeProvider.sentMessages("media_restart_request")
        assertEquals("Non-offerer should ask the deterministic offer owner to renegotiate", 1, restartRequests.size)
        assertEquals("alpha", restartRequests.single().peerId)
        assertEquals("local track negotiation", restartRequests.single().payload?.optString("reason"))
    }

    @Test
    fun `media restart request is rate limited per peer`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "zeta", localJoinedAt = 1, remoteJoinedAt = 2)
        val oldSlot = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(oldSlot)
        factory.simulateAnswerFromRemote("zeta", offerId = latestOfferId())

        factory.fakeProvider.simulateMessage(
            from = "zeta",
            type = "media_restart_request",
            payload = JSONObject().apply {
                put("from", "zeta")
                put("reason", "stalled outbound media")
            },
        )
        ShadowLooper.idleMainLooper()
        val replacement = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(replacement)
        assertNotSame(oldSlot, replacement)
        val offersAfterFirstRequest = factory.fakeProvider.sentMessages("offer").size

        factory.simulateAnswerFromRemote("zeta", offerId = latestOfferId())
        factory.fakeProvider.simulateMessage(
            from = "zeta",
            type = "media_restart_request",
            payload = JSONObject().apply {
                put("from", "zeta")
                put("reason", "stalled outbound media")
            },
        )
        ShadowLooper.idleMainLooper()

        assertSame("Immediate duplicate restart request must keep the current slot", replacement, factory.fakeMedia.fakeSlots["zeta"])
        assertEquals("Immediate duplicate restart request must not send another offer", offersAfterFirstRequest, factory.fakeProvider.sentMessages("offer").size)

        factory.fakeClock.advance(WebRtcResilienceConstants.OUTBOUND_MEDIA_RECOVERY_COOLDOWN_MS + 1)
        factory.fakeProvider.simulateMessage(
            from = "zeta",
            type = "media_restart_request",
            payload = JSONObject().apply {
                put("from", "zeta")
                put("reason", "stalled outbound media")
            },
        )
        ShadowLooper.idleMainLooper()

        assertEquals("Restart request after cooldown should be honored", offersAfterFirstRequest + 1, factory.fakeProvider.sentMessages("offer").size)
    }

    @Test
    fun `designated offerer recreates peer after stalled outbound media`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "zeta", localJoinedAt = 1, remoteJoinedAt = 2)
        val oldSlot = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(oldSlot)
        factory.simulateAnswerFromRemote("zeta", offerId = latestOfferId())
        oldSlot!!.simulateConnectionStateChange(PeerConnection.PeerConnectionState.CONNECTED)
        oldSlot.simulateIceConnectionStateChange(PeerConnection.IceConnectionState.CONNECTED)
        val offersBefore = factory.fakeProvider.sentMessages("offer").size
        oldSlot.outboundMediaSample = OutboundMediaSample(
            expectsAudio = true,
            expectsVideo = true,
            audioBytesSent = 1_000L,
            videoBytesSent = 2_000L,
            videoFramesSent = 10L,
        )

        repeat(WebRtcResilienceConstants.OUTBOUND_MEDIA_STALL_SAMPLES + 2) {
            ShadowLooper.idleMainLooper(
                WebRtcResilienceConstants.OUTBOUND_MEDIA_WATCHDOG_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            ShadowLooper.idleMainLooper()
        }

        val replacement = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(replacement)
        assertNotSame("Stalled media recovery should replace the stale peer slot", oldSlot, replacement)
        assertTrue("Old slot should be closed", oldSlot.closePeerConnectionCalled)
        assertEquals("Recovery should send one fresh offer", offersBefore + 1, factory.fakeProvider.sentMessages("offer").size)
    }

    @Test
    fun `non offerer requests peer media restart after stalled outbound media`() {
        factory.advanceToInCallWithTurn(localCid = "zeta", remoteCid = "alpha", localJoinedAt = 2, remoteJoinedAt = 1)
        factory.simulateOfferFromRemote("alpha", offerId = "remote-offer")
        val slot = factory.fakeMedia.fakeSlots["alpha"]
        assertNotNull(slot)
        slot!!.simulateConnectionStateChange(PeerConnection.PeerConnectionState.CONNECTED)
        slot.simulateIceConnectionStateChange(PeerConnection.IceConnectionState.CONNECTED)
        slot.outboundMediaSample = OutboundMediaSample(
            expectsAudio = true,
            expectsVideo = false,
            audioBytesSent = 1_000L,
            videoBytesSent = 0L,
            videoFramesSent = 0L,
        )

        repeat(WebRtcResilienceConstants.OUTBOUND_MEDIA_STALL_SAMPLES + 2) {
            ShadowLooper.idleMainLooper(
                WebRtcResilienceConstants.OUTBOUND_MEDIA_WATCHDOG_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            ShadowLooper.idleMainLooper()
        }

        val restartRequests = factory.fakeProvider.sentMessages("media_restart_request")
        assertEquals("Non-offerer should ask the deterministic offer owner to restart", 1, restartRequests.size)
        assertEquals("alpha", restartRequests.single().peerId)
    }

    @Test
    fun `answer creation failure resets peer and retries remote offer`() {
        factory.advanceToInCallWithTurn(localCid = "zeta", remoteCid = "alpha", localJoinedAt = 2, remoteJoinedAt = 1)
        val oldSlot = factory.fakeMedia.fakeSlots["alpha"]
        assertNotNull(oldSlot)
        oldSlot!!.failNextAnswer = true

        factory.simulateOfferFromRemote("alpha", offerId = "remote-offer")
        repeat(4) { ShadowLooper.idleMainLooper() }

        val replacement = factory.fakeMedia.fakeSlots["alpha"]
        assertNotNull(replacement)
        assertNotSame("Failed answer creation should replace the peer slot", oldSlot, replacement)
        assertTrue("Old slot should be closed after answer failure", oldSlot.closePeerConnectionCalled)
        assertEquals(org.webrtc.SessionDescription.Type.OFFER, replacement!!.setRemoteDescriptionCalls.last().first)
        assertTrue("Replacement should create the answer", replacement.createAnswerCalls > 0)
        assertTrue("Replacement answer should be sent for the original offer", factory.fakeProvider.sentMessages("answer").any {
            it.payload?.optString("offerId") == "remote-offer"
        })
    }

    @Test
    fun `rollback failure resets peer and retries remote offer`() {
        factory.advanceToInCallWithTurn(localCid = "zeta", remoteCid = "alpha", localJoinedAt = 2, remoteJoinedAt = 1)
        val oldSlot = factory.fakeMedia.fakeSlots["alpha"]
        assertNotNull(oldSlot)
        oldSlot!!.createOffer(iceRestart = false, onSdp = {}, onComplete = null)
        oldSlot.failNextRollback = true
        assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, oldSlot.getSignalingState())

        factory.simulateOfferFromRemote("alpha", sdp = "remote-offer", offerId = "remote-offer")
        repeat(4) { ShadowLooper.idleMainLooper() }

        val replacement = factory.fakeMedia.fakeSlots["alpha"]
        assertNotNull(replacement)
        assertNotSame("Failed rollback should replace the peer slot", oldSlot, replacement)
        assertTrue("Old slot should be closed after rollback failure", oldSlot.closePeerConnectionCalled)
        assertEquals(org.webrtc.SessionDescription.Type.OFFER, replacement!!.setRemoteDescriptionCalls.last().first)
        assertTrue("Replacement should answer the original offer", replacement.createAnswerCalls > 0)
        assertTrue("Replacement answer should be sent for the original offer", factory.fakeProvider.sentMessages("answer").any {
            it.payload?.optString("offerId") == "remote-offer"
        })
    }

    @Test
    fun `remote offer apply failure escalates after replacement fails`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "zeta", localJoinedAt = 1, remoteJoinedAt = 2)
        val oldSlot = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(oldSlot)
        factory.simulateAnswerFromRemote("zeta", offerId = latestOfferId())
        oldSlot!!.failNextRemoteOffer = true
        factory.fakeMedia.failNextCreatedSlotRemoteOffer = true
        val offersBefore = factory.fakeProvider.sentMessages("offer").size

        factory.simulateOfferFromRemote("zeta", sdp = "bad-offer", offerId = "bad-offer")
        repeat(4) { ShadowLooper.idleMainLooper() }

        val replacement = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(replacement)
        assertNotSame("Failed remote offer apply should replace the peer slot first", oldSlot, replacement)
        assertTrue("Old slot should be closed after remote offer apply failure", oldSlot.closePeerConnectionCalled)
        assertEquals(org.webrtc.SessionDescription.Type.OFFER, replacement!!.setRemoteDescriptionCalls.last().first)
        assertEquals("Replacement apply failure should escalate to one new offer", offersBefore + 1, factory.fakeProvider.sentMessages("offer").size)
        assertEquals(true, replacement.createOfferIceRestartFlags.last())
    }

    @Test
    fun `four-party reattach restarts only deterministic offer owners`() {
        val peerIds = listOf("alpha", "bravo", "charlie", "delta")
        val factories = peerIds.associateWith { TestSessionFactory(handlesReconnection = true) }
        val cursors = peerIds.associateWith { 0 }.toMutableMap()
        val joinedParticipants = peerIds.mapIndexed { index, cid -> cid to (index + 1).toLong() }

        fun participants(charlieStatus: ParticipantSignalingStatus = ParticipantSignalingStatus.ACTIVE): List<SignalingProviderParticipant> =
            peerIds.mapIndexed { index, cid ->
                SignalingProviderParticipant(
                    peerId = cid,
                    joinedAt = (index + 1).toLong(),
                    connectionStatus = if (cid == "charlie") charlieStatus else ParticipantSignalingStatus.ACTIVE,
                )
            }
        fun sentOffers(): List<Pair<String, SentProviderMessage>> =
            peerIds.flatMap { fromCid ->
                factories[fromCid]!!.fakeProvider.sentMessages("offer").map { fromCid to it }
            }
        fun offerCountsBySender(): Map<String, Int> =
            peerIds.associateWith { fromCid -> factories[fromCid]!!.fakeProvider.sentMessages("offer").size }
        fun offersAfter(counts: Map<String, Int>): List<Pair<String, SentProviderMessage>> =
            peerIds.flatMap { fromCid ->
                factories[fromCid]!!.fakeProvider.sentMessages("offer")
                    .drop(counts[fromCid] ?: 0)
                    .map { fromCid to it }
            }
        fun nonStableSlots(): List<String> =
            factories.flatMap { (localCid, localFactory) ->
                localFactory.fakeMedia.fakeSlots.mapNotNull { (remoteCid, slot) ->
                    if (slot.getSignalingState() == PeerConnection.SignalingState.STABLE) {
                        null
                    } else {
                        "$localCid->$remoteCid:${slot.getSignalingState()}"
                    }
                }
            }
        fun pumpSignals() {
            repeat(32) {
                var delivered = false
                for ((fromCid, localFactory) in factories) {
                    val messages = localFactory.fakeProvider.sentProviderMessages
                    val startIndex = cursors[fromCid] ?: 0
                    for (index in startIndex until messages.size) {
                        val message = messages[index]
                        val targetCid = message.peerId ?: continue
                        val targetFactory = factories[targetCid] ?: continue
                        val payload = message.payload?.let { JSONObject(it.toString()).apply { put("from", fromCid) } }
                        targetFactory.fakeProvider.simulateMessage(from = fromCid, type = message.type, payload = payload)
                        delivered = true
                    }
                    cursors[fromCid] = messages.size
                }
                ShadowLooper.idleMainLooper()
                if (!delivered) return
            }
            fail("Timed out pumping loopback signaling")
        }

        try {
            val iceServers = listOf(
                PeerConnection.IceServer.builder("turn:turn.example.com:3478")
                    .setUsername("user")
                    .setPassword("pass")
                    .createIceServer()
            )
            for (localFactory in factories.values) {
                localFactory.fakeProvider.enqueueIceServers(Result.success(iceServers))
                localFactory.grantPermissionsAndStart()
                localFactory.openSignaling()
            }
            for ((localCid, localFactory) in factories) {
                localFactory.fakeProvider.simulateJoined(
                    peerId = localCid,
                    participants = joinedParticipants,
                    hostPeerId = "alpha",
                )
                ShadowLooper.idleMainLooper()
            }
            pumpSignals()

            assertEquals(listOf(3, 3, 3, 3), factories.values.map { it.fakeMedia.fakeSlots.size })
            assertEquals(6, sentOffers().size)
            assertTrue("Initial negotiation should settle: ${nonStableSlots()}", nonStableSlots().isEmpty())
            assertTrue("All offers must come from the lexicographically lower peer", sentOffers().all { (fromCid, message) ->
                val targetCid = message.peerId
                targetCid != null && fromCid < targetCid
            })

            val baselineOfferCounts = offerCountsBySender()
            val baselineOfferTotal = sentOffers().size
            for (localFactory in factories.values) {
                localFactory.fakeProvider.simulateRoomStateUpdatedWith(
                    participants = participants(ParticipantSignalingStatus.SUSPENDED),
                    hostPeerId = "alpha",
                )
            }
            ShadowLooper.idleMainLooper()
            pumpSignals()
            assertEquals("Suspending charlie must not send new offers", baselineOfferTotal, sentOffers().size)

            factories["charlie"]!!.fakeProvider.simulateDisconnected("chaos")
            ShadowLooper.idleMainLooper()
            factories["charlie"]!!.fakeProvider.simulateConnected()
            ShadowLooper.idleMainLooper()
            for (localFactory in factories.values) {
                localFactory.fakeProvider.simulateRoomStateUpdatedWith(
                    participants = participants(),
                    hostPeerId = "alpha",
                )
            }
            ShadowLooper.idleMainLooper()
            pumpSignals()

            val reconnectOfferRoutes = offersAfter(baselineOfferCounts)
                .map { (fromCid, message) -> "$fromCid->${message.peerId}" }
                .toSet()
            assertEquals(
                setOf("alpha->charlie", "bravo->charlie", "charlie->delta"),
                reconnectOfferRoutes,
            )
            assertEquals("Reconnect should send exactly one offer per affected pair", baselineOfferTotal + 3, sentOffers().size)
            assertTrue("Reconnect negotiation should settle: ${nonStableSlots()}", nonStableSlots().isEmpty())
        } finally {
            factories.values.forEach { it.tearDown() }
        }
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

    @Test
    fun `ICE restart rolls back stale local offer before retrying`() {
        factory = TestSessionFactory(handlesReconnection = true)
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "zeta", localJoinedAt = 1, remoteJoinedAt = 2)

        val fakeSlot = factory.fakeMedia.fakeSlots["zeta"]
        assertNotNull(fakeSlot)
        assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, fakeSlot!!.getSignalingState())
        val offersBefore = fakeSlot.createOfferCalls

        // Simulate a watchdog that was lost while the app/signaling transport
        // was suspended. A dirty-pair restart must still recover the slot.
        fakeSlot.cancelOfferTimeout()
        factory.fakeProvider.simulateNegotiationDirty(withCid = "zeta")
        ShadowLooper.idleMainLooper()

        assertEquals("Stale local offers should be rolled back before retrying ICE restart", 1, fakeSlot.rollbackCalls)
        assertTrue("ICE restart should retry from STABLE", fakeSlot.createOfferCalls > offersBefore)
        assertEquals("Retry should leave a fresh local offer waiting for answer", PeerConnection.SignalingState.HAVE_LOCAL_OFFER, fakeSlot.getSignalingState())
    }

    @Test
    fun `shared perfect negotiation scenarios`() {
        val handled = mutableSetOf<String>()
        for (scenario in sharedNegotiationScenarios()) {
            resetFactory()
            handled += scenario.id
            when (scenario.id) {
                "impolite-offer-collision-ignores-offer-and-ice" -> {
                    factory.advanceToInCallWithTurn(
                        localCid = scenario.localCid,
                        remoteCid = scenario.remoteCid,
                        localJoinedAt = 1,
                        remoteJoinedAt = 2,
                    )
                    val slot = factory.fakeMedia.fakeSlots[scenario.remoteCid]!!
                    assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, slot.getSignalingState())

                    factory.simulateOfferFromRemote(scenario.remoteCid, sdp = "colliding-offer", offerId = "remote-offer-1")
                    factory.simulateIceCandidateFromRemote(scenario.remoteCid, candidate = "candidate:ignored", offerId = "remote-offer-1")

                    assertTrue("Impolite peer must not apply a colliding offer", slot.setRemoteDescriptionCalls.none { it.first == org.webrtc.SessionDescription.Type.OFFER })
                    assertTrue("ICE for the ignored offer must be dropped", slot.addedIceCandidates.isEmpty())
                    assertTrue("Ignored offer must not be answered", factory.fakeProvider.sentMessages("answer").isEmpty())
                }
                "polite-offer-collision-rolls-back-and-answers" -> {
                    factory.advanceToInCallWithTurn(
                        localCid = scenario.localCid,
                        remoteCid = scenario.remoteCid,
                        localJoinedAt = 2,
                        remoteJoinedAt = 1,
                    )
                    val slot = factory.fakeMedia.fakeSlots[scenario.remoteCid]!!
                    slot.createOffer(iceRestart = false, onSdp = {}, onComplete = null)
                    assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, slot.getSignalingState())

                    factory.simulateOfferFromRemote(scenario.remoteCid, sdp = "remote-offer", offerId = "remote-offer-1")

                    assertEquals("Polite peer must roll back its local offer", 1, slot.rollbackCalls)
                    assertEquals(org.webrtc.SessionDescription.Type.OFFER, slot.setRemoteDescriptionCalls.last().first)
                    assertTrue("Polite peer must answer the accepted remote offer", factory.fakeProvider.sentMessages("answer").any {
                        it.payload?.optString("offerId") == "remote-offer-1"
                    })
                }
                "stale-answer-in-stable-is-dropped" -> {
                    factory.advanceToInCallWithTurn(
                        localCid = scenario.localCid,
                        remoteCid = scenario.remoteCid,
                        localJoinedAt = 1,
                        remoteJoinedAt = 2,
                    )
                    val slot = factory.fakeMedia.fakeSlots[scenario.remoteCid]!!
                    val offerId = latestOfferId()
                    factory.simulateAnswerFromRemote(scenario.remoteCid, offerId = offerId)
                    val answerApplies = slot.setRemoteDescriptionCalls.count { it.first == org.webrtc.SessionDescription.Type.ANSWER }

                    factory.simulateAnswerFromRemote(scenario.remoteCid, sdp = "late-answer", offerId = offerId)

                    assertEquals("Stale answer in STABLE must be dropped", answerApplies, slot.setRemoteDescriptionCalls.count { it.first == org.webrtc.SessionDescription.Type.ANSWER })
                }
                "stale-answer-wrong-offer-id-is-dropped" -> {
                    factory.advanceToInCallWithTurn(
                        localCid = scenario.localCid,
                        remoteCid = scenario.remoteCid,
                        localJoinedAt = 1,
                        remoteJoinedAt = 2,
                    )
                    val slot = factory.fakeMedia.fakeSlots[scenario.remoteCid]!!

                    factory.simulateAnswerFromRemote(scenario.remoteCid, sdp = "wrong-answer", offerId = "wrong-offer-id")

                    assertTrue("Wrong-offer answer must not reach the peer connection", slot.setRemoteDescriptionCalls.none { it.first == org.webrtc.SessionDescription.Type.ANSWER })
                    assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, slot.getSignalingState())
                }
                "early-ice-for-eventual-offer-is-buffered-and-flushed" -> {
                    factory.advanceToInCallWithTurn(
                        localCid = scenario.localCid,
                        remoteCid = scenario.remoteCid,
                        localJoinedAt = 2,
                        remoteJoinedAt = 1,
                    )
                    val slot = factory.fakeMedia.fakeSlots[scenario.remoteCid]!!

                    factory.simulateIceCandidateFromRemote(scenario.remoteCid, candidate = "candidate:future", offerId = "remote-offer-1")
                    assertTrue("Future-offer ICE must be buffered", slot.addedIceCandidates.isEmpty())

                    factory.simulateOfferFromRemote(scenario.remoteCid, sdp = "remote-offer", offerId = "remote-offer-1")

                    assertEquals(1, slot.addedIceCandidates.size)
                    assertEquals("candidate:future", slot.addedIceCandidates.first().sdp)
                }
                "departed-peer-signaling-is-ignored" -> {
                    factory.advanceToInCallWithTurn(
                        localCid = scenario.localCid,
                        remoteCid = scenario.remoteCid,
                        localJoinedAt = 1,
                        remoteJoinedAt = 2,
                    )
                    val createdSlotsBefore = factory.fakeMedia.createdSlotCids.size
                    val answersBefore = factory.fakeProvider.sentMessages("answer").size

                    factory.simulateRoomState(participants = listOf(scenario.localCid to 1L), hostCid = scenario.localCid)
                    factory.simulateOfferFromRemote(scenario.remoteCid, sdp = "late-offer", offerId = "late-offer-id")
                    factory.simulateAnswerFromRemote(scenario.remoteCid, sdp = "late-answer", offerId = "late-offer-id")
                    factory.simulateIceCandidateFromRemote(scenario.remoteCid, candidate = "candidate:late", offerId = "late-offer-id")

                    assertFalse(factory.fakeMedia.fakeSlots.containsKey(scenario.remoteCid))
                    assertEquals(createdSlotsBefore, factory.fakeMedia.createdSlotCids.size)
                    assertEquals(answersBefore, factory.fakeProvider.sentMessages("answer").size)
                }
                "self-signaling-is-ignored" -> {
                    factory.advanceToInCallWithTurn(
                        localCid = scenario.localCid,
                        remoteCid = scenario.remoteCid,
                        localJoinedAt = 1,
                        remoteJoinedAt = 2,
                    )
                    val createdSlotsBefore = factory.fakeMedia.createdSlotCids.size
                    val answersBefore = factory.fakeProvider.sentMessages("answer").size

                    factory.simulateOfferFromRemote(scenario.localCid, sdp = "self-offer", offerId = "self-offer-id")
                    factory.simulateAnswerFromRemote(scenario.localCid, sdp = "self-answer", offerId = "self-offer-id")
                    factory.simulateIceCandidateFromRemote(scenario.localCid, candidate = "candidate:self", offerId = "self-offer-id")

                    assertFalse(factory.fakeMedia.fakeSlots.containsKey(scenario.localCid))
                    assertEquals(createdSlotsBefore, factory.fakeMedia.createdSlotCids.size)
                    assertEquals(answersBefore, factory.fakeProvider.sentMessages("answer").size)
                }
                "remote-offer-apply-failure-recreates-peer-and-answers" -> {
                    factory.advanceToInCallWithTurn(
                        localCid = scenario.localCid,
                        remoteCid = scenario.remoteCid,
                        localJoinedAt = 2,
                        remoteJoinedAt = 1,
                    )
                    val oldSlot = factory.fakeMedia.fakeSlots[scenario.remoteCid]!!
                    oldSlot.failNextRemoteOffer = true

                    factory.simulateIceCandidateFromRemote(scenario.remoteCid, candidate = "candidate:recovered", offerId = "remote-offer-1")
                    factory.simulateOfferFromRemote(scenario.remoteCid, sdp = "remote-offer", offerId = "remote-offer-1")

                    val newSlot = factory.fakeMedia.fakeSlots[scenario.remoteCid]!!
                    assertNotSame("Failed remote offer should recreate the peer slot", oldSlot, newSlot)
                    assertTrue("Old peer slot should be removed", factory.fakeMedia.removedSlots.contains(oldSlot))
                    assertTrue("Old peer slot should be closed", oldSlot.closePeerConnectionCalled)
                    assertEquals(org.webrtc.SessionDescription.Type.OFFER, newSlot.setRemoteDescriptionCalls.last().first)
                    assertEquals("candidate:recovered", newSlot.addedIceCandidates.single().sdp)
                    assertTrue("Replacement peer must answer the remote offer", factory.fakeProvider.sentMessages("answer").any {
                        it.payload?.optString("offerId") == "remote-offer-1"
                    })
                }
                else -> fail("Unhandled shared negotiation scenario: ${scenario.id}")
            }
        }

        assertEquals(sharedNegotiationScenarios().map { it.id }.toSet(), handled)
    }

    // Additional

    @Test
    fun `slot created for remote participant`() {
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote", localJoinedAt = 1, remoteJoinedAt = 2)
        assertTrue(factory.fakeMedia.createdSlotCids.contains("remote"))
        assertNotNull(factory.fakeMedia.fakeSlots["remote"])
    }
}
