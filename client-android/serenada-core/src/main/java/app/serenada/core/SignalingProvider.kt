package app.serenada.core

import org.json.JSONObject
import org.webrtc.PeerConnection

data class ProviderCapabilities(
    val handlesReconnection: Boolean = false,
)

data class ConnectionInfo(
    val transport: String? = null,
)

data class JoinOptions(
    val reconnectPeerId: String? = null,
    val maxParticipants: Int? = null,
)

data class SignalingProviderParticipant(
    val peerId: String,
    val joinedAt: Long? = null,
)

data class JoinedEvent(
    val peerId: String,
    val participants: List<SignalingProviderParticipant>,
    val hostPeerId: String? = null,
    val maxParticipants: Int? = null,
)

data class RoomStateEvent(
    val participants: List<SignalingProviderParticipant>,
    val hostPeerId: String? = null,
    val maxParticipants: Int? = null,
)

data class PeerEvent(
    val peerId: String,
    val joinedAt: Long? = null,
)

data class PeerMessage(
    val from: String,
    val type: String,
    val payload: JSONObject? = null,
)

data class RoomEndedEvent(
    val by: String? = null,
    val reason: String,
)

data class ErrorEvent(
    val code: String,
    val message: String,
)

/**
 * Transport-agnostic signaling contract for Android SDK sessions.
 *
 * Implementations may invoke [listener] callbacks from any thread. The session
 * layer is responsible for main-looper trampolining before mutating SDK state.
 */
interface SignalingProvider {
    val version: Int
        get() = SUPPORTED_SIGNALING_PROVIDER_VERSION

    val capabilities: ProviderCapabilities
        get() = ProviderCapabilities()

    var listener: Listener?

    fun connect()

    fun disconnect()

    fun joinRoom(roomId: String, options: JoinOptions = JoinOptions())

    fun leaveRoom()

    fun endRoom()

    fun sendToPeer(peerId: String, type: String, payload: JSONObject? = null)

    fun broadcast(type: String, payload: JSONObject? = null)

    suspend fun getIceServers(): List<PeerConnection.IceServer>

    interface Listener {
        fun onConnected(info: ConnectionInfo = ConnectionInfo()) {}
        fun onDisconnected(reason: String?) {}
        fun onJoined(event: JoinedEvent) {}
        fun onRoomStateUpdated(event: RoomStateEvent) {}
        fun onPeerJoined(event: PeerEvent) {}
        fun onPeerLeft(event: PeerEvent) {}
        fun onMessage(message: PeerMessage) {}
        fun onRoomEnded(event: RoomEndedEvent) {}
        fun onError(event: ErrorEvent) {}
        fun onIceServersChanged(iceServers: List<PeerConnection.IceServer>) {}
    }
}
