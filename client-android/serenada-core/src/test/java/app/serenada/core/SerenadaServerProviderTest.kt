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
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

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

        fun simulateClosed(reason: String = "test") {
            connected = false
            listener?.onClosed(reason)
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
        val errors = mutableListOf<ErrorEvent>()
        val reconnectTokenRefreshedEvents = mutableListOf<ReconnectTokenRefreshedEvent>()

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

        override fun onError(event: ErrorEvent) {
            errors += event
        }

        override fun onReconnectTokenRefreshed(event: ReconnectTokenRefreshedEvent) {
            reconnectTokenRefreshedEvents += event
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
    fun `auto rejoin after transport drop carries server-assigned cid and token`() {
        // Initial fresh join — no reconnect peer id from the host app.
        provider.joinRoom(
            roomId = "room-1",
            options = JoinOptions(maxParticipants = 4),
        )
        signaling.open(activeTransport = "ws")

        // Server assigns a CID and reconnect token via JOINED.
        signaling.receive(
            SignalingMessage(
                type = "joined",
                rid = "room-1",
                sid = null,
                cid = "server-assigned",
                to = null,
                payload = JSONObject().apply {
                    put("reconnectToken", "token-xyz")
                    put(
                        "participants",
                        JSONArray().put(
                            JSONObject().apply {
                                put("cid", "server-assigned")
                                put("joinedAt", 1L)
                            },
                        ),
                    )
                },
            ),
        )
        signaling.sentMessages.clear()

        // Transport drop and reopen — auto-rejoin path.
        signaling.simulateClosed()
        signaling.open(activeTransport = "ws")

        val rejoin = signaling.sentMessages.single()
        assertEquals("join", rejoin.type)
        assertEquals("server-assigned", rejoin.payload?.optString("reconnectCid"))
        assertEquals("token-xyz", rejoin.payload?.optString("reconnectToken"))
    }

    @Test
    fun `reconnect token refresh is scheduled ten minutes before expiry`() {
        provider.joinRoom(
            roomId = "room-1",
            options = JoinOptions(maxParticipants = 4),
        )
        signaling.open(activeTransport = "ws")

        signaling.receive(
            SignalingMessage(
                type = "joined",
                rid = "room-1",
                sid = null,
                cid = "server-assigned",
                to = null,
                payload = JSONObject().apply {
                    put("reconnectToken", "token-1")
                    put("reconnectTokenTTLMs", 1_200_000L)
                    put(
                        "participants",
                        JSONArray().put(JSONObject().apply { put("cid", "server-assigned") }),
                    )
                },
            ),
        )
        signaling.sentMessages.clear()

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(599_999L, TimeUnit.MILLISECONDS)
        assertTrue(signaling.sentMessages.none { it.type == "reconnect-token-refresh" })

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.MILLISECONDS)
        assertEquals(1, signaling.sentMessages.count { it.type == "reconnect-token-refresh" })
    }

    @Test
    fun `room ended clears reconnect token refresh timer`() {
        provider.joinRoom(
            roomId = "room-1",
            options = JoinOptions(maxParticipants = 4),
        )
        signaling.open(activeTransport = "ws")

        signaling.receive(
            SignalingMessage(
                type = "joined",
                rid = "room-1",
                sid = null,
                cid = "server-assigned",
                to = null,
                payload = JSONObject().apply {
                    put("reconnectToken", "token-1")
                    put("reconnectTokenTTLMs", 1_200_000L)
                    put(
                        "participants",
                        JSONArray().put(JSONObject().apply { put("cid", "server-assigned") }),
                    )
                },
            ),
        )
        signaling.sentMessages.clear()

        signaling.receive(
            SignalingMessage(
                type = "room_ended",
                rid = "room-1",
                sid = null,
                cid = null,
                to = null,
                payload = JSONObject().apply { put("reason", "host_ended") },
            ),
        )
        signaling.sentMessages.clear()

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(600_000L, TimeUnit.MILLISECONDS)
        assertTrue(signaling.sentMessages.none { it.type == "reconnect-token-refresh" })
        assertEquals(1, listener.roomEndedEvents.size)
    }

    @Test
    fun `reconnect token refreshed updates token used by next auto rejoin`() {
        provider.joinRoom(
            roomId = "room-1",
            options = JoinOptions(maxParticipants = 4),
        )
        signaling.open(activeTransport = "ws")

        signaling.receive(
            SignalingMessage(
                type = "joined",
                rid = "room-1",
                sid = null,
                cid = "server-assigned",
                to = null,
                payload = JSONObject().apply {
                    put("reconnectToken", "token-1")
                    put("reconnectTokenTTLMs", 1_200_000L)
                    put(
                        "participants",
                        JSONArray().put(JSONObject().apply { put("cid", "server-assigned") }),
                    )
                },
            ),
        )

        signaling.receive(
            SignalingMessage(
                type = "reconnect-token-refreshed",
                rid = "room-1",
                sid = null,
                cid = null,
                to = null,
                payload = JSONObject().apply {
                    put("reconnectToken", "token-2")
                    put("reconnectTokenTTLMs", 1_200_000L)
                },
            ),
        )
        assertEquals("token-2", listener.reconnectTokenRefreshedEvents.single().reconnectToken)
        signaling.sentMessages.clear()

        signaling.simulateClosed()
        signaling.open(activeTransport = "ws")

        val rejoin = signaling.sentMessages.single()
        assertEquals("server-assigned", rejoin.payload?.optString("reconnectCid"))
        assertEquals("token-2", rejoin.payload?.optString("reconnectToken"))
    }

    @Test
    fun `invalid reconnect token retries as fresh join without surfacing error`() {
        provider.joinRoom(
            roomId = "room-1",
            options = JoinOptions(maxParticipants = 4),
        )
        signaling.open(activeTransport = "ws")
        signaling.receive(
            SignalingMessage(
                type = "joined",
                rid = "room-1",
                sid = null,
                cid = "server-assigned",
                to = null,
                payload = JSONObject().apply {
                    put("reconnectToken", "expired-token")
                    put("reconnectTokenTTLMs", 1_200_000L)
                    put(
                        "participants",
                        JSONArray().put(JSONObject().apply { put("cid", "server-assigned") }),
                    )
                },
            ),
        )

        signaling.sentMessages.clear()
        signaling.simulateClosed()
        signaling.open(activeTransport = "ws")
        val reconnectJoin = signaling.sentMessages.single()
        assertEquals("server-assigned", reconnectJoin.payload?.optString("reconnectCid"))
        assertEquals("expired-token", reconnectJoin.payload?.optString("reconnectToken"))

        signaling.receive(
            SignalingMessage(
                type = "error",
                rid = "room-1",
                sid = null,
                cid = null,
                to = null,
                payload = JSONObject().apply {
                    put("code", "INVALID_RECONNECT_TOKEN")
                    put("message", "Reconnect token validation failed")
                },
            ),
        )

        val freshJoin = signaling.sentMessages.last()
        assertEquals("join", freshJoin.type)
        assertNull(freshJoin.payload?.optString("reconnectCid")?.ifBlank { null })
        assertNull(freshJoin.payload?.optString("reconnectToken")?.ifBlank { null })
        assertTrue(listener.errors.isEmpty())
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
    fun `tokenless turn-refreshed keeps the current turn token`() = runBlocking {
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

        signaling.receive(
            SignalingMessage(
                type = "turn-refreshed",
                rid = "room-1",
                sid = null,
                cid = "local-cid",
                to = null,
                payload = JSONObject().apply { put("turnToken", "") },
            )
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val iceServers = provider.getIceServers()

        assertEquals(listOf("serenada.app" to "turn-token"), apiClient.fetchTurnCredentialsCalls)
        assertEquals(listOf("turn:turn.example.com:3478"), iceServers.flatMap { it.urls })
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
    fun `room_state forwards suspended connectionStatus to delegate`() {
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
                    put("hostCid", "local-cid")
                    put(
                        "participants",
                        JSONArray()
                            .put(participantJson("local-cid", 1L))
                            .put(participantJson("peer-a", 2L).apply { put("connectionStatus", "suspended") })
                    )
                },
            )
        )

        val lastEvent = listener.roomStateEvents.last()
        val peer = lastEvent.participants.firstOrNull { it.peerId == "peer-a" }
        // If this fails, the suspended status is being dropped at the provider
        // boundary and the reconnecting UI cannot render.
        assertEquals(ParticipantSignalingStatus.SUSPENDED, peer?.connectionStatus)
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
