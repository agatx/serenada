package app.serenada.core.call

import android.os.Handler
import app.serenada.core.network.SessionAPIClient
import app.serenada.core.network.TurnCredentials
import org.json.JSONObject
import org.webrtc.PeerConnection

class TurnManager(
    private val handler: Handler,
    private val serverHost: String,
    private val apiClient: SessionAPIClient,
    private val isSignalingConnected: () -> Boolean,
    private val setIceServers: (List<PeerConnection.IceServer>) -> Unit,
    private val onIceServersReady: () -> Unit,
    private val sendTurnRefresh: () -> Unit,
) {
    private var turnRefreshRunnable: Runnable? = null
    private var turnTokenTTLMs: Long? = null

    fun fetchTurnCredentials(token: String) {
        var resolved = false
        val timeoutRunnable = Runnable {
            if (resolved) return@Runnable; resolved = true
            applyDefaultIceServers()
        }
        handler.postDelayed(timeoutRunnable, WebRtcResilienceConstants.TURN_FETCH_TIMEOUT_MS)
        apiClient.fetchTurnCredentials(serverHost, token) { result ->
            handler.post {
                handler.removeCallbacks(timeoutRunnable)
                if (resolved) return@post; resolved = true
                result.onSuccess { applyTurnCredentials(it) }.onFailure { applyDefaultIceServers() }
            }
        }
    }

    fun applyDefaultIceServers() {
        setIceServers(listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()))
        onIceServersReady()
    }

    fun handleTurnRefreshed(msg: SignalingMessage) {
        msg.payload?.optLong("turnTokenTTLMs", 0)?.takeIf { it > 0 }?.let { scheduleTurnRefresh(it) }
        msg.payload?.optString("turnToken").orEmpty().ifBlank { null }?.let { fetchTurnCredentials(it) }
    }

    fun handleJoinedTTL(ttlMs: Long) {
        turnTokenTTLMs = ttlMs
        scheduleTurnRefresh(ttlMs)
    }

    fun reset() {
        cancelRefresh()
        turnTokenTTLMs = null
    }

    fun cancelRefresh() {
        turnRefreshRunnable?.let { handler.removeCallbacks(it) }
        turnRefreshRunnable = null
    }

    private fun applyTurnCredentials(creds: TurnCredentials) {
        val servers = creds.uris.map {
            PeerConnection.IceServer.builder(it).setUsername(creds.username).setPassword(creds.password).createIceServer()
        }
        setIceServers(servers)
        onIceServersReady()
    }

    private fun scheduleTurnRefresh(ttlMs: Long) {
        cancelRefresh()
        if (ttlMs <= 0) return
        val delayMs = (ttlMs * WebRtcResilienceConstants.TURN_REFRESH_TRIGGER_RATIO).toLong()
        val runnable = Runnable {
            turnRefreshRunnable = null
            if (!isSignalingConnected()) return@Runnable
            sendTurnRefresh()
        }
        turnRefreshRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }
}
