package app.serenada.core

import android.os.Handler
import android.os.Looper
import app.serenada.core.call.SessionSignaling
import app.serenada.core.call.SignalingMessage
import app.serenada.core.fakes.FakeAPIClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SerenadaServerProviderTest {

    private class FakeSessionSignaling : SessionSignaling {
        override var listener: SessionSignaling.Listener? = null

        var connected = false
        val connectHosts = mutableListOf<String>()
        val sentMessages = mutableListOf<SignalingMessage>()
        var closeCalls = 0
        var recordPongCalls = 0

        override fun connect(host: String) {
            connectHosts += host
        }

        override fun isConnected(): Boolean = connected

        override fun send(message: SignalingMessage) {
            sentMessages += message
        }

        override fun close() {
            closeCalls += 1
            connected = false
        }

        override fun recordPong() {
            recordPongCalls += 1
        }

        fun open(activeTransport: String = "ws") {
            connected = true
            listener?.onOpen(activeTransport)
        }

        fun receive(message: SignalingMessage) {
            listener?.onMessage(message)
        }
    }

    private class RecordingListener : SignalingProvider.Listener {
        val connectionInfos = mutableListOf<ConnectionInfo>()
        val joinedEvents = mutableListOf<JoinedEvent>()
        val roomStateEvents = mutableListOf<RoomStateEvent>()
        val peerJoinedEvents = mutableListOf<PeerEvent>()
        val peerLeftEvents = mutableListOf<PeerEvent>()
        val peerMessages = mutableListOf<PeerMessage>()
        val roomEndedEvents = mutableListOf<RoomEndedEvent>()

        override fun onConnected(info: ConnectionInfo) {
            connectionInfos += info
        }

        override fun onJoined(event: JoinedEvent) {
            joinedEvents += event
        }

        override fun onRoomStateUpdated(event: RoomStateEvent) {
            roomStateEvents += event
        }

        override fun onPeerJoined(event: PeerEvent) {
            peerJoinedEvents += event
        }

        override fun onPeerLeft(event: PeerEvent) {
            peerLeftEvents += event
        }

        override fun onMessage(message: PeerMessage) {
            peerMessages += message
        }

        override fun onRoomEnded(event: RoomEndedEvent) {
            roomEndedEvents += event
        }
    }

    private lateinit var signaling: FakeSessionSignaling
    private lateinit var apiClient: FakeAPIClient
    private lateinit var listener: RecordingListener
    private lateinit var provider: SerenadaServerProvider

    @Before
    fun setUp() {
        signaling = FakeSessionSignaling()
        apiClient = FakeAPIClient()
        listener = RecordingListener()
        provider = SerenadaServerProvider(
            serverHost = "serenada.app",
            handler = Handler(Looper.getMainLooper()),
            okHttpClient = OkHttpClient(),
            apiClient = apiClient,
            signaling = signaling,
        )
        provider.listener = listener
    }

    @Test
    fun `join waits for open and includes reconnect cid`() {
        provider.joinRoom(
            roomId = "room-1",
            options = JoinOptions(reconnectPeerId = "local-cid", maxParticipants = 6),
        )

        assertEquals(listOf("serenada.app"), signaling.connectHosts)
        assertTrue(signaling.sentMessages.isEmpty())

        signaling.open(activeTransport = "sse")

        assertEquals(listOf(ConnectionInfo(transport = "sse")), listener.connectionInfos)
        val joinMessage = signaling.sentMessages.single()
        assertEquals("join", joinMessage.type)
        assertEquals("room-1", joinMessage.rid)
        assertEquals("local-cid", joinMessage.payload?.optString("reconnectCid"))
        assertEquals(6, joinMessage.payload?.optInt("createMaxParticipants"))
    }

    @Test
    fun `joined turn token is used for ice server fetch`() = runBlocking {
        signaling.receive(
            SignalingMessage(
                type = "joined",
                rid = "room-1",
                sid = null,
                cid = "local-cid",
                to = null,
                payload = JSONObject().apply {
                    put("hostCid", "local-cid")
                    put("turnToken", "turn-token")
                    put(
                        "participants",
                        JSONArray().put(
                            JSONObject().apply {
                                put("cid", "local-cid")
                                put("joinedAt", 1L)
                            }
                        )
                    )
                },
            )
        )

        val iceServers = provider.getIceServers()

        assertEquals(listOf("serenada.app" to "turn-token"), apiClient.fetchTurnCredentialsCalls)
        assertEquals(listOf("turn:turn.example.com:3478"), iceServers.flatMap { it.urls })
        assertEquals("local-cid", listener.joinedEvents.last().peerId)
    }

    @Test
    fun `room state emits participant diffs and room ended uses payload fields`() {
        signaling.receive(
            SignalingMessage(
                type = "joined",
                rid = "room-1",
                sid = null,
                cid = "local-cid",
                to = null,
                payload = JSONObject().apply {
                    put("hostCid", "local-cid")
                    put(
                        "participants",
                        JSONArray()
                            .put(participantJson("local-cid", 1L))
                            .put(participantJson("peer-a", 2L))
                    )
                },
            )
        )

        signaling.receive(
            SignalingMessage(
                type = "room_state",
                rid = "room-1",
                sid = null,
                cid = null,
                to = null,
                payload = JSONObject().apply {
                    put("hostCid", "peer-b")
                    put(
                        "participants",
                        JSONArray()
                            .put(participantJson("local-cid", 1L))
                            .put(participantJson("peer-b", 3L))
                    )
                },
            )
        )

        assertEquals(listOf(PeerEvent(peerId = "peer-b", joinedAt = 3L)), listener.peerJoinedEvents)
        assertEquals(listOf(PeerEvent(peerId = "peer-a", joinedAt = 2L)), listener.peerLeftEvents)
        assertEquals("peer-b", listener.roomStateEvents.last().hostPeerId)

        signaling.receive(
            SignalingMessage(
                type = "room_ended",
                rid = "room-1",
                sid = null,
                cid = null,
                to = null,
                payload = JSONObject().apply {
                    put("by", "peer-b")
                    put("reason", "host ended")
                },
            )
        )

        assertEquals(RoomEndedEvent(by = "peer-b", reason = "host ended"), listener.roomEndedEvents.last())
    }

    @Test
    fun `sendToPeer and broadcast forward raw messages`() {
        provider.sendToPeer(
            peerId = "peer-1",
            type = "offer",
            payload = JSONObject().apply { put("sdp", "offer-sdp") },
        )
        provider.broadcast(
            type = "content_state",
            payload = JSONObject().apply { put("active", true) },
        )

        assertEquals(listOf("offer", "content_state"), signaling.sentMessages.map { it.type })
        assertEquals("peer-1", signaling.sentMessages.first().to)
        assertEquals("offer-sdp", signaling.sentMessages.first().payload?.optString("sdp"))
        assertNull(signaling.sentMessages.last().to)
        assertTrue(signaling.sentMessages.last().payload?.optBoolean("active") == true)
    }

    private fun participantJson(cid: String, joinedAt: Long): JSONObject {
        return JSONObject().apply {
            put("cid", cid)
            put("joinedAt", joinedAt)
        }
    }
}
