package app.serenada.core

import android.os.Handler
import app.serenada.core.call.SessionSignaling
import app.serenada.core.call.SignalingClient
import app.serenada.core.call.SignalingMessage
import app.serenada.core.call.WebRtcResilienceConstants
import app.serenada.core.call.toErrorPayload
import app.serenada.core.call.toJoinedPayload
import app.serenada.core.call.toRoomStatePayload
import app.serenada.core.network.CoreApiClient
import app.serenada.core.network.SessionAPIClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.PeerConnection

internal class SerenadaServerProvider(
    private val serverHost: String,
    private val handler: Handler,
    okHttpClient: OkHttpClient,
    private val apiClient: SessionAPIClient = CoreApiClient(okHttpClient),
    signaling: SessionSignaling? = null,
    private val transports: List<SerenadaTransport> = listOf(SerenadaTransport.WS, SerenadaTransport.SSE),
    private val logger: SerenadaLogger? = null,
) : SignalingProvider {
    override val capabilities: ProviderCapabilities = ProviderCapabilities(handlesReconnection = true)
    override var listener: SignalingProvider.Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val signaling: SessionSignaling = signaling ?: SignalingClient(
        okHttpClient = okHttpClient,
        handler = handler,
        initialListener = null,
        forceSse = transports == listOf(SerenadaTransport.SSE),
        logger = logger,
    )

    private var reconnectAttempts = 0
    private var reconnectRunnable: Runnable? = null
    private var turnRefreshRunnable: Runnable? = null
    private var currentRoomId: String? = null
    private var currentMaxParticipants: Int = 4
    private var currentReconnectPeerId: String? = null
    private var currentTurnToken: String? = null
    private var reconnectToken: String? = null
    private var clientId: String? = null
    private var currentHostPeerId: String? = null
    private var previousParticipants = linkedMapOf<String, SignalingProviderParticipant>()
    private var closedByClient = false
    private var pendingJoinRoomId: String? = null
    private var currentDisplayName: String? = null

    override fun connect() {
        closedByClient = false
        signaling.connect(serverHost)
    }

    override fun disconnect() {
        closedByClient = true
        clearReconnect()
        clearTurnRefresh()
        pendingJoinRoomId = null
        previousParticipants.clear()
        signaling.close()
        scope.coroutineContext.cancelChildren()
    }

    override fun joinRoom(roomId: String, options: JoinOptions) {
        currentRoomId = roomId
        pendingJoinRoomId = roomId
        currentMaxParticipants = options.maxParticipants ?: currentMaxParticipants
        currentReconnectPeerId = options.reconnectPeerId
        if (options.displayName != null) {
            currentDisplayName = options.displayName
        }
        if (signaling.isConnected()) {
            pendingJoinRoomId = null
            sendJoin(roomId)
        } else {
            connect()
        }
    }

    override fun leaveRoom() {
        sendRawMessage(type = "leave")
        clearRoomState()
    }

    override fun endRoom() {
        sendRawMessage(type = "end_room")
    }

    override fun sendToPeer(peerId: String, type: String, payload: JSONObject?) {
        sendRawMessage(type = type, payload = payload, to = peerId)
    }

    override fun broadcast(type: String, payload: JSONObject?) {
        sendRawMessage(type = type, payload = payload)
    }

    override suspend fun getIceServers(): List<PeerConnection.IceServer> {
        val token = currentTurnToken?.takeIf { it.isNotBlank() } ?: return emptyList()
        return suspendCancellableCoroutine { continuation ->
            apiClient.fetchTurnCredentials(serverHost, token) { result ->
                if (!continuation.isActive) {
                    return@fetchTurnCredentials
                }
                result
                    .onSuccess { credentials ->
                        val servers = credentials.uris.map { uri ->
                            PeerConnection.IceServer.builder(uri)
                                .setUsername(credentials.username)
                                .setPassword(credentials.password)
                                .createIceServer()
                        }
                        continuation.resume(servers)
                    }
                    .onFailure { continuation.resumeWithException(it) }
            }
        }
    }

    private val signalingListener = object : SessionSignaling.Listener {
        override fun onOpen(activeTransport: String) {
            reconnectAttempts = 0
            clearReconnect()
            listener?.onConnected(ConnectionInfo(transport = activeTransport))
            pendingJoinRoomId?.let { roomId ->
                pendingJoinRoomId = null
                sendJoin(roomId)
            }
        }

        override fun onMessage(message: SignalingMessage) {
            handleIncomingMessage(message)
        }

        override fun onClosed(reason: String) {
            listener?.onDisconnected(reason)
            if (!closedByClient && currentRoomId != null) {
                pendingJoinRoomId = currentRoomId
                scheduleReconnect()
            }
        }
    }

    init {
        this.signaling.listener = signalingListener
    }

    private fun handleIncomingMessage(message: SignalingMessage) {
        when (message.type) {
            "joined" -> handleJoined(message)
            "room_state" -> handleRoomState(message)
            "room_ended" -> {
                val endedBy = message.payload?.optString("by").orEmpty().ifBlank { currentHostPeerId }
                val reason = message.payload?.optString("reason").orEmpty().ifBlank { "room ended" }
                clearReconnect()
                clearTurnRefresh()
                currentRoomId = null
                previousParticipants.clear()
                listener?.onRoomEnded(RoomEndedEvent(by = endedBy, reason = reason))
            }
            "error" -> {
                message.payload.toErrorPayload()?.let { payload ->
                    listener?.onError(
                        ErrorEvent(
                            code = payload.code ?: "UNKNOWN",
                            message = payload.message ?: "Unknown error",
                        )
                    )
                }
            }
            "turn-refreshed" -> {
                currentTurnToken = message.payload?.optString("turnToken").orEmpty().ifBlank { null }
                val ttlMs = message.payload?.optLong("turnTokenTTLMs", 0L)?.takeIf { it > 0L }
                if (ttlMs != null) {
                    scheduleTurnRefresh(ttlMs)
                }
                scope.launch {
                    runCatching { getIceServers() }
                        .onSuccess { iceServers -> listener?.onIceServersChanged(iceServers) }
                        .onFailure { error ->
                            listener?.onError(
                                ErrorEvent(
                                    code = "TURN_REFRESH_FAILED",
                                    message = error.message ?: "TURN refresh failed",
                                )
                            )
                        }
                }
            }
            "offer", "answer", "ice", "content_state" -> emitPeerMessage(message)
            "pong" -> signaling.recordPong()
        }
    }

    private fun handleJoined(message: SignalingMessage) {
        val payload = message.payload.toJoinedPayload() ?: return
        val peerId = message.cid?.takeIf { it.isNotBlank() } ?: clientId ?: return
        clientId = peerId
        reconnectToken = payload.reconnectToken ?: reconnectToken
        currentHostPeerId = payload.hostCid
        currentTurnToken = payload.turnToken
        payload.turnTokenTTLMs?.let { scheduleTurnRefresh(it) } ?: clearTurnRefresh()
        val participants = payload.participants.map { participant ->
            SignalingProviderParticipant(peerId = participant.cid, joinedAt = participant.joinedAt, displayName = participant.displayName)
        }
        previousParticipants = linkedMapOf<String, SignalingProviderParticipant>().apply {
            participants.forEach { put(it.peerId, it) }
        }
        listener?.onJoined(
            JoinedEvent(
                peerId = peerId,
                participants = participants,
                hostPeerId = payload.hostCid,
                maxParticipants = payload.maxParticipants,
            )
        )
    }

    private fun handleRoomState(message: SignalingMessage) {
        val payload = message.payload.toRoomStatePayload() ?: return
        currentHostPeerId = payload.hostCid
        val participants = payload.participants.map { participant ->
            SignalingProviderParticipant(peerId = participant.cid, joinedAt = participant.joinedAt, displayName = participant.displayName)
        }
        emitParticipantDiffs(participants)
        previousParticipants = linkedMapOf<String, SignalingProviderParticipant>().apply {
            participants.forEach { put(it.peerId, it) }
        }
        listener?.onRoomStateUpdated(
            RoomStateEvent(
                participants = participants,
                hostPeerId = payload.hostCid,
                maxParticipants = payload.maxParticipants,
            )
        )
    }

    private fun emitParticipantDiffs(participants: List<SignalingProviderParticipant>) {
        val nextParticipants = participants.associateBy { it.peerId }
        for ((peerId, participant) in nextParticipants) {
            if (!previousParticipants.containsKey(peerId)) {
                listener?.onPeerJoined(PeerEvent(peerId = peerId, joinedAt = participant.joinedAt, displayName = participant.displayName))
            }
        }
        for ((peerId, participant) in previousParticipants) {
            if (!nextParticipants.containsKey(peerId)) {
                listener?.onPeerLeft(PeerEvent(peerId = peerId, joinedAt = participant.joinedAt, displayName = participant.displayName))
            }
        }
    }

    private fun emitPeerMessage(message: SignalingMessage) {
        val from = message.payload?.optString("from").orEmpty().ifBlank { message.cid ?: return }
        listener?.onMessage(PeerMessage(from = from, type = message.type, payload = message.payload))
    }

    private fun sendJoin(roomId: String) {
        currentRoomId = roomId
        val payload = JSONObject().apply {
            put("device", "android")
            put(
                "capabilities",
                JSONObject().apply {
                    put("trickleIce", true)
                    put("maxParticipants", currentMaxParticipants)
                }
            )
            put("createMaxParticipants", currentMaxParticipants)
            currentDisplayName?.let { put("displayName", it) }
            reconnectToken?.let { put("reconnectToken", it) }
            currentReconnectPeerId?.let { put("reconnectCid", it) }
        }
        sendRawMessage(type = "join", rid = roomId, payload = payload)
    }

    private fun sendRawMessage(
        type: String,
        rid: String? = currentRoomId,
        payload: JSONObject? = null,
        to: String? = null,
    ) {
        signaling.send(
            SignalingMessage(
                type = type,
                rid = rid,
                sid = null,
                cid = clientId,
                to = to,
                payload = payload,
            )
        )
    }

    private fun clearRoomState() {
        clearReconnect()
        clearTurnRefresh()
        currentRoomId = null
        currentReconnectPeerId = null
        currentTurnToken = null
        currentHostPeerId = null
        previousParticipants.clear()
        clientId = null
        reconnectToken = null
    }

    private fun scheduleReconnect() {
        if (reconnectRunnable != null || currentRoomId == null) {
            return
        }
        reconnectAttempts += 1
        val backoffMs = (WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS * (1L shl minOf(reconnectAttempts - 1, 13)))
            .coerceAtMost(WebRtcResilienceConstants.RECONNECT_BACKOFF_CAP_MS)
        val runnable = Runnable {
            reconnectRunnable = null
            if (currentRoomId != null && !closedByClient) {
                connect()
            }
        }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, backoffMs)
    }

    private fun clearReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun scheduleTurnRefresh(ttlMs: Long) {
        clearTurnRefresh()
        val delayMs = (ttlMs * WebRtcResilienceConstants.TURN_REFRESH_TRIGGER_RATIO).toLong()
        val runnable = Runnable {
            turnRefreshRunnable = null
            if (signaling.isConnected() && currentRoomId != null) {
                sendRawMessage(type = "turn-refresh")
            }
        }
        turnRefreshRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearTurnRefresh() {
        turnRefreshRunnable?.let { handler.removeCallbacks(it) }
        turnRefreshRunnable = null
    }
}
