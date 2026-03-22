package app.serenada.core.call

import android.os.Handler

class JoinTimer(
    private val handler: Handler,
    private val getPhase: () -> CallPhase,
    private val getJoinAttemptSerial: () -> Long,
    private val hasJoinSignalStarted: () -> Boolean,
    private val hasJoinAcknowledged: () -> Boolean,
    private val isSignalingConnected: () -> Boolean,
    private val onJoinTimeout: () -> Unit,
    private val ensureSignalingConnection: () -> Unit,
    private val onRecovery: () -> Unit,
    private val setPendingJoinRoom: (String) -> Unit,
) {
    private var joinTimeoutRunnable: Runnable? = null
    private var joinKickstartRunnable: Runnable? = null
    private var joinRecoveryRunnable: Runnable? = null

    fun scheduleTimeout(@Suppress("UNUSED_PARAMETER") roomId: String, joinAttemptId: Long) {
        clearTimeout()
        val runnable = Runnable {
            joinTimeoutRunnable = null
            if (getPhase() == CallPhase.Joining && getJoinAttemptSerial() == joinAttemptId) {
                onJoinTimeout()
            }
        }
        joinTimeoutRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS)
    }

    fun clearTimeout() {
        joinTimeoutRunnable?.let { handler.removeCallbacks(it) }
        joinTimeoutRunnable = null
    }

    fun scheduleKickstart(joinAttemptId: Long) {
        clearKickstart()
        val runnable = Runnable {
            joinKickstartRunnable = null
            if (getPhase() != CallPhase.Joining) return@Runnable
            if (getJoinAttemptSerial() != joinAttemptId) return@Runnable
            if (hasJoinSignalStarted()) return@Runnable
            ensureSignalingConnection()
        }
        joinKickstartRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_CONNECT_KICKSTART_MS)
    }

    fun clearKickstart() {
        joinKickstartRunnable?.let { handler.removeCallbacks(it) }
        joinKickstartRunnable = null
    }

    fun scheduleRecovery(roomId: String) {
        clearRecovery()
        val runnable = Runnable {
            joinRecoveryRunnable = null
            if (!isSignalingConnected()) return@Runnable
            if (!hasJoinAcknowledged()) {
                if (getPhase() == CallPhase.Joining) {
                    setPendingJoinRoom(roomId)
                    ensureSignalingConnection()
                }
                return@Runnable
            }
            onRecovery()
        }
        joinRecoveryRunnable = runnable
        handler.postDelayed(runnable, WebRtcResilienceConstants.JOIN_RECOVERY_MS)
    }

    fun clearRecovery() {
        joinRecoveryRunnable?.let { handler.removeCallbacks(it) }
        joinRecoveryRunnable = null
    }

    fun clearAll() {
        clearTimeout()
        clearKickstart()
        clearRecovery()
    }
}
