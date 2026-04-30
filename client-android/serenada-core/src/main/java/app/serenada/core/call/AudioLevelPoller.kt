package app.serenada.core.call

import android.os.Handler
import java.util.concurrent.ExecutorService

/**
 * Polls WebRTC stats every [AudioLevelMonitor.UPDATE_INTERVAL_MS] for each
 * peer connection's inbound audio level and the local media-source level,
 * pushes them through per-cid [AudioLevelMonitor]s for dBFS+EMA smoothing,
 * and reports the results on the main handler.
 *
 * Mirrors the web SDK's `AudioLevelMonitor`, but uses WebRTC stats instead
 * of Web Audio API. The smoothed output range is identical (0..1) so the
 * indicator visual is consistent across platforms.
 */
internal class AudioLevelPoller(
    private val handler: Handler,
    private val statsExecutorProvider: () -> ExecutorService?,
    private val isActivePhase: () -> Boolean,
    private val getPeerSlots: () -> List<PeerConnectionSlotProtocol>,
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
        if (slots.isEmpty() || executor == null) {
            // No peers (or executor torn down): emit a fully decayed sample so the
            // indicator drops to silence rather than freezing on the last value.
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
        val rawLevels = mutableMapOf<String, Float?>()
        var rawLocal: Float? = null
        var remaining = slots.size
        slots.forEach { slot ->
            slot.collectAudioLevels { inbound, mediaSource ->
                synchronized(rawLevels) {
                    rawLevels[slot.remoteCid] = inbound
                    // media-source is the same local mic across all peers; take the
                    // first non-null value seen this round.
                    if (rawLocal == null && mediaSource != null) rawLocal = mediaSource
                    remaining -= 1
                    if (remaining == 0) {
                        val capturedLocal = rawLocal
                        val capturedRemote = rawLevels.toMap()
                        handler.post { applyAndEmit(capturedLocal, capturedRemote) }
                    }
                }
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
