package app.serenada.core.call

import android.os.Handler
import app.serenada.core.CallDiagnostics
import app.serenada.core.IceConnectionState
import app.serenada.core.PeerConnectionState

class ConnectionStatusTracker(
    private val handler: Handler,
    private val getPhase: () -> CallPhase,
    private val getDiagnostics: () -> CallDiagnostics,
    private val getCurrentStatus: () -> ConnectionStatus,
    private val setConnectionStatus: (ConnectionStatus) -> Unit,
) {
    private var connectionStatusRetryingRunnable: Runnable? = null

    fun update() {
        if (getPhase() != CallPhase.InCall) { reset(); return }
        if (isConnectionDegraded()) { markConnectionDegraded(); return }
        reset()
    }

    fun reset() {
        cancelTimer()
        if (getCurrentStatus() != ConnectionStatus.Connected) {
            setConnectionStatus(ConnectionStatus.Connected)
        }
    }

    fun cancelTimer() {
        connectionStatusRetryingRunnable?.let { handler.removeCallbacks(it) }
        connectionStatusRetryingRunnable = null
    }

    fun isConnectionDegraded(): Boolean {
        val diag = getDiagnostics()
        return !diag.isSignalingConnected ||
            diag.iceConnectionState == IceConnectionState.DISCONNECTED ||
            diag.iceConnectionState == IceConnectionState.FAILED ||
            diag.peerConnectionState == PeerConnectionState.DISCONNECTED ||
            diag.peerConnectionState == PeerConnectionState.FAILED
    }

    private fun markConnectionDegraded() {
        if (getPhase() != CallPhase.InCall) { reset(); return }
        when (getCurrentStatus()) {
            ConnectionStatus.Connected -> { setConnectionStatus(ConnectionStatus.Recovering); scheduleRetryingTimer() }
            ConnectionStatus.Recovering -> scheduleRetryingTimer()
            ConnectionStatus.Retrying -> Unit
        }
    }

    private fun scheduleRetryingTimer() {
        if (connectionStatusRetryingRunnable != null) return
        val runnable = Runnable {
            connectionStatusRetryingRunnable = null
            if (getPhase() != CallPhase.InCall) { reset(); return@Runnable }
            if (getCurrentStatus() == ConnectionStatus.Recovering) setConnectionStatus(ConnectionStatus.Retrying)
        }
        connectionStatusRetryingRunnable = runnable
        handler.postDelayed(runnable, 10_000)
    }
}
