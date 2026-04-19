/**
 * End-to-end test validating the SignalingProvider contract with a
 * LoopbackSignalingProvider — an in-memory provider that routes messages
 * between two SerenadaSession instances without any server.
 */
package app.serenada.core

import app.serenada.core.call.CallPhase
import app.serenada.core.fakes.FakeAudioController
import app.serenada.core.fakes.FakeMediaEngine
import app.serenada.core.fakes.FakeSessionClock
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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

// ---------------------------------------------------------------------------
// LoopbackRoom — shared in-memory room state
// ---------------------------------------------------------------------------

private class LoopbackRoom {
    private val participants = mutableListOf<Pair<String, LoopbackSignalingProvider>>()
    private var hostPeerId: String? = null

    fun join(provider: LoopbackSignalingProvider) {
        val peerId = provider.peerId
        participants.add(peerId to provider)
        if (hostPeerId == null) hostPeerId = peerId

        val list = participants.mapIndexed { i, (id, _) ->
            SignalingProviderParticipant(peerId = id, joinedAt = (i + 1).toLong())
        }

        // Notify existing participants about the new peer.
        for ((existingId, existing) in participants) {
            if (existingId != peerId) {
                existing.listener?.onPeerJoined(
                    PeerEvent(peerId = peerId, joinedAt = list.size.toLong())
                )
            }
        }

        // Tell the joining provider it has joined.
        provider.listener?.onJoined(
            JoinedEvent(
                peerId = peerId,
                participants = list,
                hostPeerId = hostPeerId,
                maxParticipants = 4,
            )
        )
    }

    fun routeToPeer(from: String, to: String, type: String, payload: JSONObject?) {
        val target = participants.firstOrNull { it.first == to } ?: return
        target.second.listener?.onMessage(PeerMessage(from = from, type = type, payload = payload))
    }

    fun routeBroadcast(from: String, type: String, payload: JSONObject?) {
        for ((peerId, provider) in participants) {
            if (peerId != from) {
                provider.listener?.onMessage(PeerMessage(from = from, type = type, payload = payload))
            }
        }
    }

    fun leave(peerId: String) {
        participants.removeAll { it.first == peerId }
        if (hostPeerId == peerId) {
            hostPeerId = participants.firstOrNull()?.first
        }
        for ((_, provider) in participants) {
            provider.listener?.onPeerLeft(PeerEvent(peerId = peerId, joinedAt = null))
        }
    }

    fun end(by: String) {
        for ((_, provider) in participants) {
            provider.listener?.onRoomEnded(RoomEndedEvent(by = by, reason = "host_ended"))
        }
        participants.clear()
        hostPeerId = null
    }
}

// ---------------------------------------------------------------------------
// LoopbackSignalingProvider — routes through LoopbackRoom
// ---------------------------------------------------------------------------

private class LoopbackSignalingProvider(
    private val room: LoopbackRoom,
    val peerId: String,
) : SignalingProvider {
    override val capabilities = ProviderCapabilities(handlesReconnection = true)
    override var listener: SignalingProvider.Listener? = null
    private var currentRoomId: String? = null

    override fun connect() {
        listener?.onConnected(ConnectionInfo(transport = "loopback"))
    }

    override fun disconnect() {
        if (currentRoomId != null) {
            room.leave(peerId)
            currentRoomId = null
        }
    }

    override fun joinRoom(roomId: String, options: JoinOptions) {
        currentRoomId = roomId
        room.join(this)
    }

    override fun leaveRoom() {
        if (currentRoomId != null) {
            room.leave(peerId)
            currentRoomId = null
        }
    }

    override fun endRoom() {
        room.end(by = peerId)
        currentRoomId = null
    }

    override fun sendToPeer(peerId: String, type: String, payload: JSONObject?) {
        room.routeToPeer(from = this.peerId, to = peerId, type = type, payload = payload)
    }

    override fun broadcast(type: String, payload: JSONObject?) {
        room.routeBroadcast(from = peerId, type = type, payload = payload)
    }

    override suspend fun getIceServers(): List<PeerConnection.IceServer> = emptyList()
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoopbackSignalingProviderTest {

    private data class SessionBundle(
        val provider: LoopbackSignalingProvider,
        val media: FakeMediaEngine,
        val session: SerenadaSession,
    )

    private val sessions = mutableListOf<SessionBundle>()

    private fun createSession(
        provider: LoopbackSignalingProvider,
        roomId: String = "room-1",
    ): SessionBundle {
        val media = FakeMediaEngine()
        val config = SerenadaConfig(signalingProvider = provider)
        val session = SerenadaSession(
            roomId = roomId,
            roomUrl = null,
            config = config,
            context = RuntimeEnvironment.getApplication(),
            delegate = null,
            okHttpClient = OkHttpClient(),
            initialSignalingProvider = provider,
            audioController = FakeAudioController(),
            mediaEngine = media,
            clock = FakeSessionClock(),
        )
        val bundle = SessionBundle(provider, media, session)
        sessions.add(bundle)
        return bundle
    }

    private fun grantPermissionsAndStart(session: SerenadaSession) {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
        )
        session.start()
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        for (bundle in sessions) {
            bundle.session.cancelJoin()
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `session joins alone and reaches Waiting phase`() {
        val room = LoopbackRoom()
        val provider = LoopbackSignalingProvider(room, "alice")
        val (_, _, session) = createSession(provider)

        grantPermissionsAndStart(session)

        assertEquals(CallPhase.Waiting, session.state.value.phase)
        assertEquals("alice", session.state.value.localCid)
        assertEquals(0, session.state.value.remoteParticipants.size)
    }

    @Test
    fun `two sessions join and both reach InCall phase`() {
        val room = LoopbackRoom()
        val providerA = LoopbackSignalingProvider(room, "alice")
        val providerB = LoopbackSignalingProvider(room, "bob")

        val (_, _, sessionA) = createSession(providerA)
        grantPermissionsAndStart(sessionA)
        assertEquals(CallPhase.Waiting, sessionA.state.value.phase)

        val (_, _, sessionB) = createSession(providerB)
        grantPermissionsAndStart(sessionB)

        assertEquals(CallPhase.InCall, sessionA.state.value.phase)
        assertEquals(1, sessionA.state.value.remoteParticipants.size)
        assertEquals("bob", sessionA.state.value.remoteParticipants.first().cid)

        assertEquals(CallPhase.InCall, sessionB.state.value.phase)
        assertEquals("bob", sessionB.state.value.localCid)
        assertEquals(1, sessionB.state.value.remoteParticipants.size)
        assertEquals("alice", sessionB.state.value.remoteParticipants.first().cid)
    }

    @Test
    fun `sendToPeer delivers offer to remote slot`() {
        val room = LoopbackRoom()
        val providerA = LoopbackSignalingProvider(room, "alice")
        val providerB = LoopbackSignalingProvider(room, "bob")

        val (_, mediaA, sessionA) = createSession(providerA)
        mediaA.setIceServers(emptyList())
        grantPermissionsAndStart(sessionA)
        val (_, mediaB, sessionB) = createSession(providerB)
        mediaB.setIceServers(emptyList())
        grantPermissionsAndStart(sessionB)

        assertTrue(
            "Bob's media engine should have a slot for alice",
            mediaB.createdSlotCids.contains("alice"),
        )

        // Alice sends an offer to Bob.
        val payload = JSONObject().apply {
            put("from", "alice")
            put("sdp", "alice-sdp")
        }
        providerA.sendToPeer("bob", "offer", payload)
        ShadowLooper.idleMainLooper()

        val bobSlotForAlice = mediaB.fakeSlots["alice"]
        assertNotNull("Bob should have a slot for alice", bobSlotForAlice)
        assertTrue(
            "Bob's slot should have received the offer",
            bobSlotForAlice!!.setRemoteDescriptionCalls.isNotEmpty(),
        )
    }

    @Test
    fun `broadcast routes to all other sessions`() {
        val room = LoopbackRoom()
        val providerA = LoopbackSignalingProvider(room, "alice")
        val providerB = LoopbackSignalingProvider(room, "bob")

        val (_, mediaA, sessionA) = createSession(providerA)
        mediaA.setIceServers(emptyList())
        grantPermissionsAndStart(sessionA)
        val (_, mediaB, sessionB) = createSession(providerB)
        mediaB.setIceServers(emptyList())
        grantPermissionsAndStart(sessionB)

        // Alice broadcasts content_state to the room.
        val payload = JSONObject().apply {
            put("from", "alice")
            put("active", true)
            put("contentType", "screenShare")
        }
        providerA.broadcast("content_state", payload)
        ShadowLooper.idleMainLooper()

        // Bob's session should reflect the remote content state.
        assertEquals(
            "Bob should see alice as remote content source",
            "alice",
            sessionB.diagnostics.value.remoteContentCid,
        )

        // Alice should NOT see herself as the remote content source.
        assertTrue(
            "Alice should not see her own broadcast as remote content",
            sessionA.diagnostics.value.remoteContentCid == null,
        )
    }

    @Test
    fun `peer leaving transitions remaining session to Waiting`() {
        val room = LoopbackRoom()
        val providerA = LoopbackSignalingProvider(room, "alice")
        val providerB = LoopbackSignalingProvider(room, "bob")

        val (_, _, sessionA) = createSession(providerA)
        grantPermissionsAndStart(sessionA)
        val (_, _, sessionB) = createSession(providerB)
        grantPermissionsAndStart(sessionB)
        assertEquals(CallPhase.InCall, sessionA.state.value.phase)

        providerB.leaveRoom()
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Waiting, sessionA.state.value.phase)
        assertEquals(0, sessionA.state.value.remoteParticipants.size)
    }

    @Test
    fun `endRoom transitions both sessions through ending`() {
        val room = LoopbackRoom()
        val providerA = LoopbackSignalingProvider(room, "alice")
        val providerB = LoopbackSignalingProvider(room, "bob")

        val (_, _, sessionA) = createSession(providerA)
        grantPermissionsAndStart(sessionA)
        val (_, _, sessionB) = createSession(providerB)
        grantPermissionsAndStart(sessionB)

        assertEquals(CallPhase.InCall, sessionA.state.value.phase)
        assertEquals(CallPhase.InCall, sessionB.state.value.phase)

        // Capture phase changes to detect Ending even if it's transient.
        val phasesA = mutableListOf(sessionA.state.value.phase)
        val phasesB = mutableListOf(sessionB.state.value.phase)

        providerA.endRoom()
        ShadowLooper.idleMainLooper()

        phasesA.add(sessionA.state.value.phase)
        phasesB.add(sessionB.state.value.phase)

        // Both sessions should have left InCall (ending or idle).
        assertTrue(
            "Session A should no longer be InCall, was ${sessionA.state.value.phase}",
            sessionA.state.value.phase != CallPhase.InCall,
        )
        assertTrue(
            "Session B should no longer be InCall, was ${sessionB.state.value.phase}",
            sessionB.state.value.phase != CallPhase.InCall,
        )
    }

    @Test
    fun `media engine creates slot for remote participant`() {
        val room = LoopbackRoom()
        val providerA = LoopbackSignalingProvider(room, "alice")
        val providerB = LoopbackSignalingProvider(room, "bob")

        val (_, mediaA, sessionA) = createSession(providerA)
        grantPermissionsAndStart(sessionA)
        assertEquals(CallPhase.Waiting, sessionA.state.value.phase)

        val (_, _, sessionB) = createSession(providerB)
        grantPermissionsAndStart(sessionB)

        assertTrue(
            "Alice's media engine should create slot for bob",
            mediaA.createdSlotCids.contains("bob")
        )
    }
}
