package app.serenada.core.fakes

import app.serenada.core.ConnectionInfo
import app.serenada.core.ErrorEvent
import app.serenada.core.JoinOptions
import app.serenada.core.JoinedEvent
import app.serenada.core.PeerEvent
import app.serenada.core.PeerMessage
import app.serenada.core.ProviderCapabilities
import app.serenada.core.RoomEndedEvent
import app.serenada.core.RoomStateEvent
import app.serenada.core.SignalingProvider
import app.serenada.core.SignalingProviderParticipant
import org.json.JSONObject
import org.webrtc.PeerConnection

internal data class SentProviderMessage(
    val peerId: String?,
    val type: String,
    val payload: JSONObject?,
    val isBroadcast: Boolean,
)

internal class FakeSignalingProvider(
    handlesReconnection: Boolean = false,
) : SignalingProvider {
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        handlesReconnection = handlesReconnection,
    )
    override var listener: SignalingProvider.Listener? = null

    val connectCalls = mutableListOf<Unit>()
    val joinCalls = mutableListOf<Pair<String, JoinOptions>>()
    val sentProviderMessages = mutableListOf<SentProviderMessage>()
    var leaveCalls = 0
        private set
    var endCalls = 0
        private set
    var disconnectCalls = 0
        private set
    var getIceServersCalls = 0
        private set
    var connected = false
        private set

    private val queuedIceServerResults = ArrayDeque<Result<List<PeerConnection.IceServer>>>()

    fun enqueueIceServers(result: Result<List<PeerConnection.IceServer>>) {
        queuedIceServerResults.addLast(result)
    }

    override fun connect() {
        connectCalls += Unit
    }

    override fun disconnect() {
        disconnectCalls += 1
        connected = false
    }

    override fun joinRoom(roomId: String, options: JoinOptions) {
        joinCalls += roomId to options
    }

    override fun leaveRoom() {
        leaveCalls += 1
    }

    override fun endRoom() {
        endCalls += 1
    }

    override fun sendToPeer(peerId: String, type: String, payload: JSONObject?) {
        sentProviderMessages += SentProviderMessage(peerId = peerId, type = type, payload = payload, isBroadcast = false)
    }

    override fun broadcast(type: String, payload: JSONObject?) {
        sentProviderMessages += SentProviderMessage(peerId = null, type = type, payload = payload, isBroadcast = true)
    }

    override suspend fun getIceServers(): List<PeerConnection.IceServer> {
        getIceServersCalls += 1
        val next = if (queuedIceServerResults.size > 1) {
            queuedIceServerResults.removeFirst()
        } else {
            queuedIceServerResults.firstOrNull() ?: Result.success(emptyList())
        }
        return next.getOrThrow()
    }

    fun simulateConnected(transport: String = "ws") {
        connected = true
        listener?.onConnected(ConnectionInfo(transport = transport))
    }

    fun simulateDisconnected(reason: String = "test") {
        connected = false
        listener?.onDisconnected(reason)
    }

    fun simulateJoined(
        peerId: String,
        participants: List<Pair<String, Long>>,
        hostPeerId: String? = peerId,
        maxParticipants: Int? = null,
    ) {
        listener?.onJoined(
            JoinedEvent(
                peerId = peerId,
                participants = participants.map { (participantId, joinedAt) ->
                    SignalingProviderParticipant(peerId = participantId, joinedAt = joinedAt)
                },
                hostPeerId = hostPeerId,
                maxParticipants = maxParticipants,
            ),
        )
    }

    fun simulateRoomStateUpdated(
        participants: List<Pair<String, Long>>,
        hostPeerId: String?,
        maxParticipants: Int? = null,
    ) {
        listener?.onRoomStateUpdated(
            RoomStateEvent(
                participants = participants.map { (participantId, joinedAt) ->
                    SignalingProviderParticipant(peerId = participantId, joinedAt = joinedAt)
                },
                hostPeerId = hostPeerId,
                maxParticipants = maxParticipants,
            ),
        )
    }

    fun simulatePeerJoined(peerId: String, joinedAt: Long? = null) {
        listener?.onPeerJoined(PeerEvent(peerId = peerId, joinedAt = joinedAt))
    }

    fun simulatePeerLeft(peerId: String, joinedAt: Long? = null) {
        listener?.onPeerLeft(PeerEvent(peerId = peerId, joinedAt = joinedAt))
    }

    fun simulateMessage(from: String, type: String, payload: JSONObject? = null) {
        listener?.onMessage(PeerMessage(from = from, type = type, payload = payload))
    }

    fun simulateRoomEnded(by: String? = null, reason: String = "room ended") {
        listener?.onRoomEnded(RoomEndedEvent(by = by, reason = reason))
    }

    fun simulateError(code: String, message: String) {
        listener?.onError(ErrorEvent(code = code, message = message))
    }

    fun simulateIceServersChanged(iceServers: List<PeerConnection.IceServer>) {
        listener?.onIceServersChanged(iceServers)
    }

    fun sentMessages(ofType: String): List<SentProviderMessage> {
        return sentProviderMessages.filter { it.type == ofType }
    }
}
