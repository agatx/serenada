package app.serenada.core.call

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Coordinates the join flow: permission check, signaling connection, join message,
 * and reconnection with exponential backoff.
 *
 * Absorbs [JoinTimer] functionality and owns all join-related timer logic.
 * Follows the closure-injection DI pattern established by [PeerNegotiationEngine].
 */
class JoinFlowCoordinator(
    private val handler: Handler,
    private val roomId: String,
    // State readers
    private val getPhase: () -> CallPhase,
    private val isSignalingConnected: () -> Boolean,
    // Mutation callbacks
    private val onStartJoinInternal: () -> Unit,
    private val onPermissionCheckRequired: () -> Unit,
    private val connectSignaling: (host: String) -> Unit,
    private val sendSignalingMessage: (SignalingMessage) -> Unit,
    private val onJoinTimeout: () -> Unit,
    private val onJoinRecovery: () -> Unit,
    // State writers
    private val setPendingJoinRoom: (String?) -> Unit,
    private val getReconnectToken: () -> String?,
    private val serverHost: String,
) {
    // --- Join timer state (absorbed from JoinTimer) ---
    private var joinTimeoutRunnable: Runnable? = null
    private var joinKickstartRunnable: Runnable? = null
    private var joinRecoveryRunnable: Runnable? = null
    var joinAttemptSerial = 0L
        private set
    var hasJoinSignalStarted = false
        private set
    var hasJoinAcknowledged = false
        private set

    // --- Reconnect state ---
    var reconnectAttempts = 0
        private set
    private var reconnectRunnable: Runnable? = null

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "JoinFlowCoordinator must be called on the main thread"
        }
    }

    // --- Public API: Start ---

    fun start(hasPermissions: Boolean) {
        assertMainThread()
        if (!hasPermissions) {
            onPermissionCheckRequired()
            return
        }
        onStartJoinInternal()
    }

    fun prepareJoinAttempt(): Long {
        joinAttemptSerial++
        hasJoinSignalStarted = false
        hasJoinAcknowledged = false
        return joinAttemptSerial
    }

    // --- Public API: Signaling Connection ---

    fun ensureSignalingConnection() {
        hasJoinSignalStarted = true
        if (isSignalingConnected()) {
            setPendingJoinRoom(null)
            sendJoin(roomId)
            return
        }
        setPendingJoinRoom(roomId)
        connectSignaling(serverHost)
    }

    fun sendJoin(roomId: String) {
        val buildPayload = {
            JSONObject().apply {
                put("device", "android")
                put(
                    "capabilities",
                    JSONObject().apply {
                        put("trickleIce", true)
                        put("maxParticipants", 4)
                    }
                )
                put("createMaxParticipants", 4)
                getReconnectToken()?.let { put("reconnectToken", it) }
            }
        }
        if (!isSignalingConnected()) return
        val msg = SignalingMessage(
            type = "join",
            rid = roomId,
            sid = null,
            cid = null,
            to = null,
            payload = buildPayload()
        )
        sendSignalingMessage(msg)
        scheduleJoinRecovery(roomId)
    }

    // --- Join Timers ---

    fun scheduleJoinTimeout(@Suppress("UNUSED_PARAMETER") roomId: String, joinAttemptId: Long) {
        clearJoinTimeout()
        val runnable = Runnable {
            joinTimeoutRunnable = null
            if (getPhase() == CallPhase.Joining && joinAttemptSerial == joinAttemptId) {
                onJoinTimeout()
            }
        }
        joinTimeoutRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS)
    }

    fun clearJoinTimeout() {
        joinTimeoutRunnable?.let { handler.removeCallbacks(it) }
        joinTimeoutRunnable = null
    }

    fun scheduleJoinKickstart(joinAttemptId: Long) {
        clearJoinKickstart()
        val runnable = Runnable {
            joinKickstartRunnable = null
            if (getPhase() != CallPhase.Joining) return@Runnable
            if (joinAttemptSerial != joinAttemptId) return@Runnable
            if (hasJoinSignalStarted) return@Runnable
            ensureSignalingConnection()
        }
        joinKickstartRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_CONNECT_KICKSTART_MS)
    }

    fun clearJoinKickstart() {
        joinKickstartRunnable?.let { handler.removeCallbacks(it) }
        joinKickstartRunnable = null
    }

    fun scheduleJoinRecovery(roomId: String) {
        clearJoinRecovery()
        val runnable = Runnable {
            joinRecoveryRunnable = null
            if (!isSignalingConnected()) return@Runnable
            if (!hasJoinAcknowledged) {
                if (getPhase() == CallPhase.Joining) {
                    setPendingJoinRoom(roomId)
                    ensureSignalingConnection()
                }
                return@Runnable
            }
            onJoinRecovery()
        }
        joinRecoveryRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_RECOVERY_MS)
    }

    fun clearJoinRecovery() {
        joinRecoveryRunnable?.let { handler.removeCallbacks(it) }
        joinRecoveryRunnable = null
    }

    fun clearAllJoinTimers() {
        clearJoinTimeout()
        clearJoinKickstart()
        clearJoinRecovery()
    }

    // --- Reconnect ---

    fun scheduleReconnect() {
        clearReconnect()
        reconnectAttempts += 1
        val backoff = (WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS * (1L shl minOf(reconnectAttempts - 1, 13)))
            .coerceAtMost(WebRtcResilienceConstants.RECONNECT_BACKOFF_CAP_MS)
        val runnable = Runnable {
            reconnectRunnable = null
            if (isSignalingConnected()) return@Runnable
            if (getPhase() != CallPhase.Idle) {
                setPendingJoinRoom(roomId)
                connectSignaling(serverHost)
            }
        }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, backoff)
    }

    fun clearReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // --- Explicit setters for private-set properties ---

    fun markJoinSignalStarted() { hasJoinSignalStarted = true }
    fun markJoinAcknowledged() { hasJoinAcknowledged = true }
    fun resetReconnectAttempts() { reconnectAttempts = 0 }

    // --- Reset ---

    fun reset() {
        clearAllJoinTimers()
        clearReconnect()
        reconnectAttempts = 0
        hasJoinSignalStarted = false
        hasJoinAcknowledged = false
    }
}
