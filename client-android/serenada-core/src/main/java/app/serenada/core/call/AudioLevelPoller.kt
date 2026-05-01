package app.serenada.core.call

import android.os.Handler
import java.util.concurrent.ExecutorService

/**
 * Polls WebRTC stats every [AudioLevelMonitor.UPDATE_INTERVAL_MS] for the
 * local media-source level (via [collectLocalAudioLevel]) and each remote
 * peer's inbound level (via the slot's `collectAudioLevels`), pushes the
 * raw values through per-cid [AudioLevelMonitor]s for dBFS+EMA smoothing,
 * and reports the results on the main handler.
 *
 * The local level is sourced from a primer peer connection that stays alive
 * the entire time the user is in a room, so `media-source.audioLevel`
 * reads consistently — including the Waiting phase before any real peer
 * joins. Mirrors the web SDK's `AudioLevelMonitor` smoothing pipeline so
 * the indicator visual is consistent across platforms.
 */
internal class AudioLevelPoller(
    private val handler: Handler,
    private val statsExecutorProvider: () -> ExecutorService?,
    private val isActivePhase: () -> Boolean,
    private val getPeerSlots: () -> List<PeerConnectionSlotProtocol>,
    private val collectLocalLevel: ((Float?) -> Unit) -> Unit,
    private val onLevelsUpdated: (localLevel: Float, remoteLevels: Map<String, Float>) -> Unit,
) {
    private val localMonitor = AudioLevelMonitor()
    private val remoteMonitors = mutableMapOf<String, AudioLevelMonitor>()
    private var pollRunnable: Runnable? = null
    private var requestInFlight = false

    fun start() {
        if (pollRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                tick()
                handler.postDelayed(this, AudioLevelMonitor.UPDATE_INTERVAL_MS)
            }
        }
        pollRunnable = runnable
        handler.post(runnable)
    }

    fun stop() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        requestInFlight = false
        localMonitor.reset()
        remoteMonitors.clear()
    }

    private fun tick() {
        if (!isActivePhase()) return
        if (requestInFlight) return
        val slots = getPeerSlots()
        val executor = statsExecutorProvider()?.takeIf { !it.isShutdown }
        if (executor == null) {
            // Executor torn down mid-session: emit a fully decayed sample so
            // the indicator drops to silence rather than freezing.
            val local = localMonitor.update(0f)
            val remote = remoteMonitors.mapValues { (_, m) -> m.update(0f) }
            onLevelsUpdated(local, remote)
            return
        }
        requestInFlight = true
        try {
            executor.execute { collectAndReport(slots) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            requestInFlight = false
        }
    }

    private fun collectAndReport(slots: List<PeerConnectionSlotProtocol>) {
        val rawRemote = mutableMapOf<String, Float?>()
        var rawLocal: Float? = null
        var remaining = slots.size + 1  // +1 for the primer query
        val sync = Any()

        val finishOne = {
            synchronized(sync) {
                remaining -= 1
                if (remaining == 0) {
                    val capturedLocal = rawLocal
                    val capturedRemote = rawRemote.toMap()
                    handler.post { applyAndEmit(capturedLocal, capturedRemote) }
                }
            }
        }

        collectLocalLevel { level ->
            synchronized(sync) { rawLocal = level }
            finishOne()
        }

        slots.forEach { slot ->
            slot.collectAudioLevels { inbound, _ ->
                synchronized(sync) { rawRemote[slot.remoteCid] = inbound }
                finishOne()
            }
        }
    }

    private fun applyAndEmit(rawLocal: Float?, rawRemote: Map<String, Float?>) {
        requestInFlight = false
        val local = localMonitor.update(rawLocal ?: 0f)
        // Drop monitors for peers no longer present.
        val activeCids = rawRemote.keys
        remoteMonitors.keys.retainAll(activeCids)
        val remote = rawRemote.mapValues { (cid, raw) ->
            val monitor = remoteMonitors.getOrPut(cid) { AudioLevelMonitor() }
            monitor.update(raw ?: 0f)
        }
        onLevelsUpdated(local, remote)
    }
}
