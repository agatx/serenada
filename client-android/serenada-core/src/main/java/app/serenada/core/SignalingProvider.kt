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
    val displayName: String? = null,
    /**
     * Host-supplied stable identity for this user. Distinct from `peerId`/cid
     * (per-call, server-issued) — lets host applications correlate a participant
     * to their own user identity (avatar lookup, telemetry).
     */
    val appPeerId: String? = null,
    /**
     * Advertised as `capabilities.independentContentVideo` at `join`. Mirrors
     * [SerenadaConfig.enableIndependentContentVideo].
     */
    val independentContentVideo: Boolean = false,
    /**
     * Advertised as `mediaPolicy.videoMediaEnabled` at `join`. Mirrors
     * [SerenadaConfig.videoMediaEnabled]. Lets remote peers avoid offering
     * video toward a strict audio-only participant.
     */
    val videoMediaEnabled: Boolean = true,
)

/**
 * Wire-level status values for a remote participant's signaling transport.
 * The server uses "suspended" to indicate a participant whose transport
 * dropped but whose room slot is being held open for reconnect. "active" is
 * the default for participants with a live transport attached.
 */
enum class ParticipantSignalingStatus { ACTIVE, SUSPENDED }

data class SignalingProviderParticipantContentState(
    val active: Boolean,
    val contentType: String? = null,
    val updatedAtMs: Long? = null,
    val epoch: Long? = null,
    /** Per-`(cid, sid)` monotonic revision; null on older senders. */
    val revision: Long? = null,
)

/**
 * Allowlisted static capabilities a participant advertised at `join`, surfaced
 * verbatim by the provider. Unknown keys are dropped upstream.
 */
data class SignalingProviderParticipantCapabilities(
    /** Whether the participant supports an independent content video stream. */
    val independentContentVideo: Boolean = false,
)

/**
 * Allowlisted per-session media policy a participant advertised at `join`.
 */
data class SignalingProviderParticipantMediaPolicy(
    /** Whether the participant negotiates any video media. Defaults true. */
    val videoMediaEnabled: Boolean = true,
)

data class SignalingProviderParticipant(
    val peerId: String,
    val joinedAt: Long? = null,
    val displayName: String? = null,
    /** Host-supplied stable identity — see [JoinOptions.appPeerId]. */
    val appPeerId: String? = null,
    val audioEnabled: Boolean? = null,
    val videoEnabled: Boolean? = null,
    val connectionStatus: ParticipantSignalingStatus = ParticipantSignalingStatus.ACTIVE,
    val contentState: SignalingProviderParticipantContentState? = null,
    /** Static capabilities advertised by this participant; null when absent. */
    val capabilities: SignalingProviderParticipantCapabilities? = null,
    /** Per-session media policy advertised by this participant; null when absent. */
    val mediaPolicy: SignalingProviderParticipantMediaPolicy? = null,
)

/**
 * Disposition of a join, surfaced by the server in `joined.reconnect`.
 * Drives whether the SDK preserves media-active peer connections,
 * schedules dirty-pair renegotiation, or starts ground-up.
 */
enum class JoinReconnectOutcome { FRESH, REATTACHED, RECOVERED }

data class JoinedEvent(
    val peerId: String,
    val participants: List<SignalingProviderParticipant>,
    val hostPeerId: String? = null,
    val maxParticipants: Int? = null,
    /** Server room-state epoch on this transport; monotonic per room. */
    val epoch: Long? = null,
    /** How the server treated this join. Null means an older provider that did not surface this field. */
    val reconnectOutcome: JoinReconnectOutcome? = null,
    /** Server-issued reconnect token from `joined.reconnectToken`. */
    val reconnectToken: String? = null,
    /** How long (ms) the server is willing to honor `reconnectToken`. */
    val reconnectTokenTTLMs: Long? = null,
)

data class RoomStateEvent(
    val participants: List<SignalingProviderParticipant>,
    val hostPeerId: String? = null,
    val maxParticipants: Int? = null,
    /** Server room-state epoch on this transport; monotonic per room. */
    val epoch: Long? = null,
)

data class PeerEvent(
    val peerId: String,
    val joinedAt: Long? = null,
    val displayName: String? = null,
    /** Host-supplied stable identity — see [JoinOptions.appPeerId]. */
    val appPeerId: String? = null,
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
 * Server tells an active peer that a previously-suspended peer has reattached
 * AND there was pending negotiation traffic to it during the suspension. The
 * SDK should perform glare-safe fresh negotiation / ICE restart for the named
 * CID.
 */
data class NegotiationDirtyEvent(
    /** The CID that needs fresh renegotiation. */
    val withCid: String,
)

/** Server tells the sender it could not deliver a relay because the target had no transport. */
data class RelayFailedEvent(
    /** Server-assigned reason code, e.g. `"target_suspended"`. */
    val reason: String,
    /** Target CIDs the relay could not reach. */
    val targets: List<String>,
    /** Original signaling type that failed, e.g. `"offer" | "answer" | "ice"`. */
    val of: String? = null,
)

data class ReconnectTokenRefreshedEvent(
    val reconnectToken: String,
    val reconnectTokenTTLMs: Long? = null,
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

    /**
     * Hook the SDK calls when the host app returns to foreground after a
     * background period long enough that the OS may have silently killed
     * the underlying transport (e.g. Doze release or process freeze). The
     * expected behavior for transport-owning providers is to send a
     * synthetic ping and arm a `timeoutMs` deadline, then force-close the
     * transport on miss so the normal reconnect path runs. Default is
     * no-op for providers that manage their own lifecycle.
     */
    fun forceReconnectIfStale(timeoutMs: Long) {}

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
        fun onNegotiationDirty(event: NegotiationDirtyEvent) {}
        fun onRelayFailed(event: RelayFailedEvent) {}
        fun onReconnectTokenRefreshed(event: ReconnectTokenRefreshedEvent) {}
    }
}

/**
 * App-global signaling provider for hosts that run MULTIPLE concurrent sessions
 * (multi-call session, contract §"Custom provider"). A plain [SignalingProvider]
 * is a single-session surface: one [SignalingProvider.listener] slot and room-less
 * ops ([SignalingProvider.leaveRoom]/[SignalingProvider.endRoom]/
 * [SignalingProvider.disconnect]/…). Handing the SAME v1 object to two live
 * sessions cross-wires their CIDs, overwrites the listener, and lets one session's
 * teardown disconnect the other. A v2 provider instead VENDS one v1-shaped channel
 * per session via [openSession]; each channel is permanently bound to one room and
 * carries that session's events only.
 *
 * Implementor obligations (the SDK cannot enforce another process's transport):
 * - Each channel is permanently bound to the [openSession] room; deliver its events
 *   only to that channel's listener.
 * - `leaveRoom`/`endRoom`/`disconnect`/TURN refresh/reconnect are channel-local.
 * - The app-global service owns the physical transport: closing one channel
 *   (its [SignalingProvider.disconnect]) must NOT disconnect the others; tear the
 *   physical transport down only after the LAST channel closes.
 * - Drop events queued for a channel once it has closed (channel-generation guard).
 */
interface MultiSessionSignalingProvider {
    val version: Int
        get() = MULTI_SESSION_SIGNALING_PROVIDER_VERSION

    val capabilities: ProviderCapabilities
        get() = ProviderCapabilities()

    /**
     * Vend a [SignalingProvider] channel permanently bound to [roomId] (the
     * canonical room id). Called exactly once per session join; the SDK calls the
     * channel's [SignalingProvider.disconnect] exactly once at session teardown.
     */
    fun openSession(roomId: String): SignalingProvider

    /** ICE servers WITHOUT an active call (diagnostics/preflight). */
    suspend fun getIceServers(): List<PeerConnection.IceServer>
}
