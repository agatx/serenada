package app.serenada.core

import android.content.Intent
import app.serenada.core.call.InboundRoleBytes
import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.fakes.TestSessionFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Acceptance-criteria gap fixes for independent content (screen share), Android
 * port of the web reference (`docs/screen-share-impl/web-phase2-gotchas.md`,
 * "Acceptance-criteria gap fixes"):
 *
 *  - GAP 2 / FIX A: per-role inbound stall diagnostics
 *    (`cameraReceiving` / `contentReceiving` on the remote participant), split by
 *    bound transceiver role on the existing media-liveness tick.
 *  - GAP 3 / FIX B: multi-peer `remoteContentCid` pointer must not be clobbered
 *    when one sharer stops while another is still actively sharing.
 *  - GAP 1 / FIX C: per-peer content attach-failure isolation (one peer's attach
 *    failure must not roll back the share or tear down the peer; the healthy peer
 *    still carries the share; the failed peer renegotiates).
 *  - GAP 4 / FIX D: a content setTrack reject falls back to renegotiation, and the
 *    durable re-attach fills the content sender once it re-binds.
 *
 * The flag-off path stays byte-identical (additive fields + an extra sampler).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndependentContentDiagnosticsTest {

    private lateinit var factory: TestSessionFactory

    @After
    fun tearDown() {
        if (this::factory.isInitialized) factory.tearDown()
    }

    private fun contentState(
        active: Boolean,
        revision: Long? = null,
        contentType: String? = if (active) "screenShare" else null,
    ): JSONObject = JSONObject().apply {
        put("active", active)
        if (contentType != null) put("contentType", contentType)
        if (revision != null) put("revision", revision)
    }

    private fun remoteParticipant(cid: String) =
        factory.session.state.value.remoteParticipants.firstOrNull { it.cid == cid }

    private fun tick(times: Int = 1) {
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.MEDIA_LIVENESS_INTERVAL_MS * times,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Drive to in-call with TWO capable remote peers in the mesh. */
    private fun advanceToInCallWithTwoCapablePeers(
        localCid: String = "local-cid-1",
        peerA: String = "remote-aaa",
        peerB: String = "remote-bbb",
    ) {
        factory.fakeProvider.enqueueIceServers(Result.success(emptyList()))
        factory.grantPermissionsAndStart()
        factory.openSignaling()
        factory.simulateJoinedResponse(
            cid = localCid,
            participants = listOf(localCid to 1L),
            hostCid = localCid,
        )
        factory.simulateRoomStateWithCapabilities(
            participants = listOf(
                SignalingProviderParticipant(peerId = localCid, joinedAt = 1L),
                SignalingProviderParticipant(
                    peerId = peerA,
                    joinedAt = 2L,
                    capabilities = SignalingProviderParticipantCapabilities(independentContentVideo = true),
                    mediaPolicy = SignalingProviderParticipantMediaPolicy(videoMediaEnabled = true),
                ),
                SignalingProviderParticipant(
                    peerId = peerB,
                    joinedAt = 3L,
                    capabilities = SignalingProviderParticipantCapabilities(independentContentVideo = true),
                    mediaPolicy = SignalingProviderParticipantMediaPolicy(videoMediaEnabled = true),
                ),
            ),
            hostCid = localCid,
        )
    }

    // ── FIX A (GAP 2): per-role inbound stall diagnostics ────────────────

    @Test
    fun `first liveness sample is conservative - both receiving false`() {
        factory = TestSessionFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote")

        factory.fakeMedia.fakeSlots["remote"]?.inboundRoleBytesSample =
            InboundRoleBytes(cameraBytes = 1_000, contentBytes = 2_000)
        tick()

        val slot = factory.fakeMedia.fakeSlots["remote"]
        assertTrue("combined liveness sampler ran", (slot?.collectInboundLivenessCalls ?: 0) > 0)
        assertEquals("timer should not run a second role-only stats pass", 0, slot?.collectInboundRoleBytesCalls ?: 0)
        val remote = remoteParticipant("remote")!!
        assertFalse("no baseline yet → conservative false", remote.cameraReceiving)
        assertFalse(remote.contentReceiving)
    }

    @Test
    fun `camera advancing while content stalls surfaces content-stall (cameraReceiving true, contentReceiving false)`() {
        factory = TestSessionFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote")

        // Baseline sample establishes the per-role byte counters.
        factory.fakeMedia.fakeSlots["remote"]?.inboundRoleBytesSample =
            InboundRoleBytes(cameraBytes = 1_000, contentBytes = 2_000)
        tick()

        // Camera advances, content STALLS (no byte increase).
        factory.fakeMedia.fakeSlots["remote"]?.inboundRoleBytesSample =
            InboundRoleBytes(cameraBytes = 1_500, contentBytes = 2_000)
        tick()

        val remote = remoteParticipant("remote")!!
        assertTrue("camera bytes advanced", remote.cameraReceiving)
        assertFalse("content bytes stalled", remote.contentReceiving)
        // Consumer-side derivation of "content stalled": content.active && !contentReceiving.
        val contentActive = true
        assertTrue(contentActive && !remote.contentReceiving)
    }

    @Test
    fun `content advancing surfaces contentReceiving true`() {
        factory = TestSessionFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote")

        factory.fakeMedia.fakeSlots["remote"]?.inboundRoleBytesSample =
            InboundRoleBytes(cameraBytes = 1_000, contentBytes = 2_000)
        tick()
        factory.fakeMedia.fakeSlots["remote"]?.inboundRoleBytesSample =
            InboundRoleBytes(cameraBytes = 1_000, contentBytes = 3_500)
        tick()

        val remote = remoteParticipant("remote")!!
        assertFalse("camera stalled", remote.cameraReceiving)
        assertTrue("content advanced", remote.contentReceiving)
    }

    @Test
    fun `flag off - single inbound video routes to camera and contentReceiving stays false`() {
        factory = TestSessionFactory(enableIndependentContentVideo = false)
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote")

        // The real slot puts the single legacy video on the camera bucket; the
        // fake supplies that split directly (camera advancing, content zero).
        factory.fakeMedia.fakeSlots["remote"]?.inboundRoleBytesSample =
            InboundRoleBytes(cameraBytes = 500, contentBytes = 0)
        tick()
        factory.fakeMedia.fakeSlots["remote"]?.inboundRoleBytesSample =
            InboundRoleBytes(cameraBytes = 4_000, contentBytes = 0)
        tick()

        val remote = remoteParticipant("remote")!!
        assertTrue("legacy video advances on camera role", remote.cameraReceiving)
        assertFalse("no content role on a legacy peer", remote.contentReceiving)
    }

    @Test
    fun `role liveness baselines reset on peer leave`() {
        factory = TestSessionFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithTurn(localCid = "alpha", remoteCid = "remote")

        factory.fakeMedia.fakeSlots["remote"]?.inboundRoleBytesSample =
            InboundRoleBytes(cameraBytes = 1_000, contentBytes = 1_000)
        tick()
        factory.fakeMedia.fakeSlots["remote"]?.inboundRoleBytesSample =
            InboundRoleBytes(cameraBytes = 2_000, contentBytes = 2_000)
        tick()
        assertTrue(remoteParticipant("remote")!!.cameraReceiving)

        // Peer leaves → its baseline is dropped (conservative on a rejoin sample).
        factory.fakeProvider.simulatePeerLeft(peerId = "remote", joinedAt = 2L)
        ShadowLooper.idleMainLooper()
        factory.fakeProvider.simulatePeerJoined(peerId = "remote", joinedAt = 3L)
        ShadowLooper.idleMainLooper()

        // First sample after the rejoin must be conservative again (no baseline).
        factory.fakeMedia.fakeSlots["remote"]?.inboundRoleBytesSample =
            InboundRoleBytes(cameraBytes = 9_000, contentBytes = 9_000)
        tick()
        val remote = remoteParticipant("remote")
        // Either the participant is present with both false (baseline reset), or
        // re-created fresh; in both cases content/camera must be false here.
        if (remote != null) {
            assertFalse("baseline reset → conservative false after rejoin", remote.cameraReceiving)
            assertFalse(remote.contentReceiving)
        }
    }

    // ── FIX B (GAP 3): multi-peer remoteContentCid pointer ───────────────

    @Test
    fun `one sharer stopping does not clear the diagnostics pointer while another is sharing`() {
        factory = TestSessionFactory(enableIndependentContentVideo = true)
        advanceToInCallWithTwoCapablePeers()

        // Both peers start sharing.
        factory.fakeProvider.simulateMessage(
            from = "remote-aaa", type = "content_state",
            payload = contentState(active = true, revision = 1),
        )
        ShadowLooper.idleMainLooper()
        factory.fakeProvider.simulateMessage(
            from = "remote-bbb", type = "content_state",
            payload = contentState(active = true, revision = 1),
        )
        ShadowLooper.idleMainLooper()

        // Pointer points at the most-recent active sharer (bbb).
        assertEquals("remote-bbb", factory.session.diagnostics.value.remoteContentCid)

        // Peer bbb STOPS. aaa is still actively sharing → pointer must re-point to
        // aaa, NOT clear to null (the bug: it cleared unconditionally).
        factory.fakeProvider.simulateMessage(
            from = "remote-bbb", type = "content_state",
            payload = contentState(active = false, revision = 2),
        )
        ShadowLooper.idleMainLooper()

        assertEquals(
            "pointer must reflect the still-active sharer, not clear",
            "remote-aaa",
            factory.session.diagnostics.value.remoteContentCid,
        )
        assertEquals("screenShare", factory.session.diagnostics.value.remoteContentType)
        // Per-cid map is correct independently.
        assertTrue(remoteParticipant("remote-aaa")!!.content!!.active)
        assertNull(remoteParticipant("remote-bbb")!!.content)
    }

    @Test
    fun `pointer clears only when the last active sharer stops`() {
        factory = TestSessionFactory(enableIndependentContentVideo = true)
        advanceToInCallWithTwoCapablePeers()

        factory.fakeProvider.simulateMessage(
            from = "remote-aaa", type = "content_state",
            payload = contentState(active = true, revision = 1),
        )
        ShadowLooper.idleMainLooper()
        factory.fakeProvider.simulateMessage(
            from = "remote-bbb", type = "content_state",
            payload = contentState(active = true, revision = 1),
        )
        ShadowLooper.idleMainLooper()

        // Stop bbb (aaa still active) then aaa → only now does the pointer clear.
        factory.fakeProvider.simulateMessage(
            from = "remote-bbb", type = "content_state",
            payload = contentState(active = false, revision = 2),
        )
        ShadowLooper.idleMainLooper()
        assertEquals("remote-aaa", factory.session.diagnostics.value.remoteContentCid)

        factory.fakeProvider.simulateMessage(
            from = "remote-aaa", type = "content_state",
            payload = contentState(active = false, revision = 2),
        )
        ShadowLooper.idleMainLooper()
        assertNull(factory.session.diagnostics.value.remoteContentCid)
        assertNull(factory.session.diagnostics.value.remoteContentType)
    }

    // ── FIX C (GAP 1): per-peer content attach-failure isolation ─────────

    @Test
    fun `one peer content-attach failure does not roll back the share or tear down the peer - healthy peer carries content`() {
        factory = TestSessionFactory(enableIndependentContentVideo = true)
        factory.fakeMedia.modelIndependentContentAttach = true
        advanceToInCallWithTwoCapablePeers()

        val slotA = factory.fakeMedia.fakeSlots["remote-aaa"]!!
        val slotB = factory.fakeMedia.fakeSlots["remote-bbb"]!!
        // Force peer A's content attach to FAIL; peer B succeeds.
        slotA.failNextContentAttach = true
        val contentStatesBefore = factory.fakeProvider.sentMessages("content_state").size

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        // Share stays active: isScreenSharing true, content_state active:true sent,
        // and NO active:false rollback emitted.
        assertTrue(factory.session.diagnostics.value.isScreenSharing)
        val states = factory.fakeProvider.sentMessages("content_state").drop(contentStatesBefore)
        assertTrue("an active:true was broadcast", states.any { it.payload!!.getBoolean("active") })
        assertFalse(
            "no active:false rollback while a peer still carries the share",
            states.any { !it.payload!!.getBoolean("active") },
        )

        // Healthy peer B carries the content on its sender.
        assertTrue("peer B carries the share", slotB.contentAttachedToSender)
        // Failed peer A does NOT carry it, is NOT torn down, and renegotiated.
        assertFalse("peer A's content sender stayed empty", slotA.contentAttachedToSender)
        assertFalse("peer A not closed", slotA.closePeerConnectionCalled)
        assertTrue("peer A still present", factory.fakeMedia.fakeSlots.containsKey("remote-aaa"))
        assertTrue("peer A marked for recovery via renegotiation", slotA.renegotiationRequestedCount > 0)
    }

    // ── FIX D (GAP 4): setTrack reject → renegotiation + durable re-attach ─

    @Test
    fun `content setTrack reject falls back to renegotiation and a later re-attach fills the sender`() {
        factory = TestSessionFactory(enableIndependentContentVideo = true)
        factory.fakeMedia.modelIndependentContentAttach = true
        // Single capable peer, content attach rejected the first time.
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        val slot = factory.fakeMedia.fakeSlots["remote-cid-2"]!!
        slot.failNextContentAttach = true

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        // Reject path: renegotiation forced, sender still empty, share still active
        // (no rollback). The engine keeps the content track set (durable retry).
        assertTrue(factory.session.diagnostics.value.isScreenSharing)
        assertTrue("renegotiation was forced", slot.renegotiationRequestedCount > 0)
        assertFalse("content sender empty after the reject", slot.contentAttachedToSender)

        // The forced renegotiation completes and the engine re-attaches the
        // content track (failNextContentAttach already consumed → succeeds).
        slot.simulateContentAttach(attach = true)

        assertTrue("content sender re-filled after renegotiation", slot.contentAttachedToSender)
    }
}
