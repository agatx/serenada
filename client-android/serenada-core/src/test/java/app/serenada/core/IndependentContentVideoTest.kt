package app.serenada.core

import android.content.Intent
import app.serenada.core.call.CallPhase
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Phase 1 foundation for independent content (screen share) video: capability /
 * media-policy signaling at join, `content_state` revision tracking, and the new
 * public `cameraEnabled` / `content` state fields. No media behavior changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndependentContentVideoTest {

    private lateinit var factory: TestSessionFactory

    @Before
    fun setUp() {
        factory = TestSessionFactory()
    }

    @After
    fun tearDown() {
        factory.tearDown()
    }

    private fun grantAudio() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
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

    private fun remoteContent(cid: String) =
        factory.session.state.value.remoteParticipants.firstOrNull { it.cid == cid }?.content

    // ── Join signaling ──────────────────────────────────────────────

    @Test
    fun `join options carry capability flag and media policy from config`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        val join = factory.fakeProvider.joinCalls.last()
        // Default config: flag off, video media enabled.
        assertFalse(join.second.independentContentVideo)
        assertTrue(join.second.videoMediaEnabled)
    }

    @Test
    fun `join options reflect config overrides`() {
        factory.tearDown()
        factory = TestSessionFactory(
            videoMediaEnabled = false,
            enableIndependentContentVideo = true,
        )
        grantAudio()
        factory.session.start()
        ShadowLooper.idleMainLooper()
        factory.openSignaling()

        val join = factory.fakeProvider.joinCalls.last()
        assertTrue(join.second.independentContentVideo)
        assertFalse(join.second.videoMediaEnabled)
    }

    @Test
    fun `enableIndependentContentVideo defaults to false`() {
        assertFalse(SerenadaConfig(serverHost = "serenada.app").enableIndependentContentVideo)
    }

    // ── Remote content state population ──────────────────────────────

    @Test
    fun `remote content_state populates participant content and mirrors cameraEnabled`() {
        factory.advanceToInCallWithTurn(localCid = "local", remoteCid = "remote")
        assertEquals(CallPhase.InCall, factory.session.state.value.phase)

        factory.fakeProvider.simulateMessage(
            from = "remote",
            type = "content_state",
            payload = contentState(active = true, revision = 1),
        )
        ShadowLooper.idleMainLooper()

        val content = remoteContent("remote")!!
        assertTrue(content.active)
        assertEquals("screenShare", content.type)
        assertEquals(1L, content.revision)

        // Phase 1 (flag off): cameraEnabled mirrors legacy videoEnabled.
        val remote = factory.session.state.value.remoteParticipants.single()
        assertEquals(remote.videoEnabled, remote.cameraEnabled)
    }

    @Test
    fun `remote content_state inactive clears content`() {
        factory.advanceToInCallWithTurn(localCid = "local", remoteCid = "remote")
        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = true, revision = 1),
        )
        ShadowLooper.idleMainLooper()
        assertTrue(remoteContent("remote")!!.active)

        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = false, revision = 2),
        )
        ShadowLooper.idleMainLooper()
        assertNull(remoteContent("remote"))
    }

    // ── Revision supersede ──────────────────────────────────────────

    @Test
    fun `stale revision is discarded`() {
        factory.advanceToInCallWithTurn(localCid = "local", remoteCid = "remote")
        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = true, revision = 5),
        )
        ShadowLooper.idleMainLooper()

        // Out-of-order stale stop (revision 3 <= tracked 5) must NOT clear it.
        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = false, revision = 3),
        )
        ShadowLooper.idleMainLooper()
        assertTrue("stale active=false must be ignored", remoteContent("remote")!!.active)
        assertEquals(5L, remoteContent("remote")!!.revision)

        // Equal revision is also discarded.
        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = false, revision = 5),
        )
        ShadowLooper.idleMainLooper()
        assertTrue(remoteContent("remote")!!.active)

        // Strictly greater revision supersedes.
        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = false, revision = 6),
        )
        ShadowLooper.idleMainLooper()
        assertNull(remoteContent("remote"))
    }

    @Test
    fun `missing revision is always accepted`() {
        factory.advanceToInCallWithTurn(localCid = "local", remoteCid = "remote")
        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = true, revision = 9),
        )
        ShadowLooper.idleMainLooper()

        // A revisionless update (older sender) is accepted regardless of tracked.
        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = false, revision = null),
        )
        ShadowLooper.idleMainLooper()
        assertNull(remoteContent("remote"))
    }

    @Test
    fun `malformed revision is treated as revisionless without lowering high water`() {
        factory.advanceToInCallWithTurn(localCid = "local", remoteCid = "remote")
        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = true, revision = 9),
        )
        ShadowLooper.idleMainLooper()
        assertTrue(remoteContent("remote")!!.active)

        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = JSONObject().apply {
                put("active", false)
                put("revision", "bad")
            },
        )
        ShadowLooper.idleMainLooper()
        assertNull(remoteContent("remote"))

        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = true, revision = 7),
        )
        ShadowLooper.idleMainLooper()
        assertNull(remoteContent("remote"))
    }

    @Test
    fun `peer leave resets revision tracking so a rejoin restarting low is accepted`() {
        factory.advanceToInCallWithTurn(localCid = "local", remoteCid = "remote")
        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = true, revision = 7),
        )
        ShadowLooper.idleMainLooper()
        assertTrue(remoteContent("remote")!!.active)

        // Peer leaves, then a fresh peer (same cid, new session) rejoins and
        // restarts its revision at 1. Tracking was reset on leave, so accept it.
        factory.fakeProvider.simulatePeerLeft(peerId = "remote", joinedAt = 2L)
        ShadowLooper.idleMainLooper()
        factory.fakeProvider.simulatePeerJoined(peerId = "remote", joinedAt = 3L)
        ShadowLooper.idleMainLooper()

        factory.fakeProvider.simulateMessage(
            from = "remote", type = "content_state",
            payload = contentState(active = true, revision = 1),
        )
        ShadowLooper.idleMainLooper()
        assertTrue("rejoin restarting at revision 1 must be accepted", remoteContent("remote")!!.active)
        assertEquals(1L, remoteContent("remote")!!.revision)
    }

    // ── Outgoing revision ───────────────────────────────────────────

    @Test
    fun `screen share start and stop carry incrementing revisions and update local content`() {
        factory.advanceToInCallWithTurn(localCid = "local", remoteCid = "remote")

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        val afterStart = factory.session.state.value.localContent
        assertTrue(afterStart!!.active)
        assertEquals("screenShare", afterStart.type)
        val startRevision = afterStart.revision

        factory.session.stopScreenShare()
        ShadowLooper.idleMainLooper()

        assertNull(factory.session.state.value.localContent)

        val sent = factory.fakeProvider.sentMessages("content_state")
        assertEquals(2, sent.size)
        val r0 = sent[0].payload!!.getLong("revision")
        val r1 = sent[1].payload!!.getLong("revision")
        assertTrue("revision must strictly increase: $r0 -> $r1", r1 > r0)
        assertEquals(startRevision, r0)
        assertTrue(sent[0].payload!!.getBoolean("active"))
        assertFalse(sent[1].payload!!.getBoolean("active"))
    }

    @Test
    fun `local content revision seeds from recovered snapshot`() {
        factory.fakeProvider.enqueueIceServers(Result.success(emptyList()))
        factory.grantPermissionsAndStart()
        factory.openSignaling()
        factory.simulateJoinedResponse(cid = "local")
        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                SignalingProviderParticipant(
                    peerId = "local",
                    joinedAt = 1L,
                    contentState = SignalingProviderParticipantContentState(
                        active = false,
                        contentType = null,
                        revision = 7,
                    ),
                ),
                SignalingProviderParticipant(peerId = "remote", joinedAt = 2L),
            ),
            hostPeerId = "local",
        )
        ShadowLooper.idleMainLooper()

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        val sent = factory.fakeProvider.sentMessages("content_state")
        assertEquals(8L, sent.last().payload!!.getLong("revision"))
    }

    // ── Capabilities / policy storage ───────────────────────────────

    @Test
    fun `remote capabilities and media policy from room_state are stored with defaults`() {
        factory.fakeProvider.enqueueIceServers(Result.success(emptyList()))
        factory.grantPermissionsAndStart()
        factory.openSignaling()
        factory.simulateJoinedResponse(cid = "local")

        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                SignalingProviderParticipant(peerId = "local", joinedAt = 1L),
                SignalingProviderParticipant(
                    peerId = "cap-peer",
                    joinedAt = 2L,
                    capabilities = SignalingProviderParticipantCapabilities(independentContentVideo = true),
                    mediaPolicy = SignalingProviderParticipantMediaPolicy(videoMediaEnabled = false),
                ),
                SignalingProviderParticipant(peerId = "legacy-peer", joinedAt = 3L),
            ),
            hostPeerId = "local",
        )
        ShadowLooper.idleMainLooper()

        assertTrue(factory.session.remoteSupportsIndependentContentVideo("cap-peer"))
        assertFalse(factory.session.remoteVideoMediaEnabled("cap-peer"))

        // Legacy peer never advertised either: defaults apply.
        assertFalse(factory.session.remoteSupportsIndependentContentVideo("legacy-peer"))
        assertTrue(factory.session.remoteVideoMediaEnabled("legacy-peer"))
    }

    @Test
    fun `a later snapshot omitting caps and policy for a present cid clears stored values to defaults`() {
        factory.fakeProvider.enqueueIceServers(Result.success(emptyList()))
        factory.grantPermissionsAndStart()
        factory.openSignaling()
        factory.simulateJoinedResponse(cid = "local")

        // First snapshot: cap-peer advertises the capability + a non-default policy.
        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                SignalingProviderParticipant(peerId = "local", joinedAt = 1L),
                SignalingProviderParticipant(
                    peerId = "cap-peer",
                    joinedAt = 2L,
                    capabilities = SignalingProviderParticipantCapabilities(independentContentVideo = true),
                    mediaPolicy = SignalingProviderParticipantMediaPolicy(videoMediaEnabled = false),
                ),
            ),
            hostPeerId = "local",
        )
        ShadowLooper.idleMainLooper()

        assertTrue(factory.session.remoteSupportsIndependentContentVideo("cap-peer"))
        assertFalse(factory.session.remoteVideoMediaEnabled("cap-peer"))

        // A new authoritative snapshot for the SAME, still-present cid omits both
        // capabilities and mediaPolicy. The stored values must be cleared so the
        // accessors fall back to contract defaults (false / true) instead of the
        // stale advertised values.
        factory.fakeProvider.simulateRoomStateUpdatedWith(
            participants = listOf(
                SignalingProviderParticipant(peerId = "local", joinedAt = 1L),
                SignalingProviderParticipant(peerId = "cap-peer", joinedAt = 2L),
            ),
            hostPeerId = "local",
        )
        ShadowLooper.idleMainLooper()

        assertFalse(
            "omitted capabilities must reset to default false",
            factory.session.remoteSupportsIndependentContentVideo("cap-peer"),
        )
        assertTrue(
            "omitted mediaPolicy must reset to default true",
            factory.session.remoteVideoMediaEnabled("cap-peer"),
        )
    }
}
