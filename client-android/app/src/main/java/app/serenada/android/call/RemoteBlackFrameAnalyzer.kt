package app.serenada.android.call

import android.os.SystemClock
import org.webrtc.VideoFrame

internal class RemoteBlackFrameAnalyzer(
    private val analysisIntervalMs: Long = 180L,
    private val staleFrameThresholdMs: Long = 2000L,
    private val consecutiveBlackThreshold: Int = 8,
    private val nonBlackWindowMs: Long = 1800L,
    private val blackLumaMax: Int = 20,
    private val blackLumaSpreadMax: Int = 2,
    private val sampleRows: Int = 6,
    private val sampleCols: Int = 6
) {
    @Volatile private var lastFrameAtMs: Long = 0L
    @Volatile private var lastNonBlackFrameAtMs: Long = 0L
    @Volatile private var lastAnalysisAtMs: Long = 0L
    @Volatile private var consecutiveBlackAnalyses: Int = 0
    @Volatile private var syntheticBlackDetected: Boolean = false

    fun onTrackAttached(nowMs: Long = SystemClock.elapsedRealtime()) {
        lastFrameAtMs = 0L
        lastNonBlackFrameAtMs = nowMs
        lastAnalysisAtMs = 0L
        consecutiveBlackAnalyses = 0
        syntheticBlackDetected = false
    }

    fun onTrackDetached() {
        lastFrameAtMs = 0L
        lastNonBlackFrameAtMs = 0L
        lastAnalysisAtMs = 0L
        consecutiveBlackAnalyses = 0
        syntheticBlackDetected = false
    }

    fun onFrame(
        frame: VideoFrame,
        blackFrameAnalysisEnabled: Boolean,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val previousSyntheticBlack = syntheticBlackDetected
        lastFrameAtMs = nowMs

        if (!blackFrameAnalysisEnabled) {
            consecutiveBlackAnalyses = 0
            syntheticBlackDetected = false
            lastNonBlackFrameAtMs = nowMs
            lastAnalysisAtMs = nowMs
            return previousSyntheticBlack != syntheticBlackDetected
        }

        if (nowMs - lastAnalysisAtMs < analysisIntervalMs) {
            return false
        }
        lastAnalysisAtMs = nowMs

        if (isSyntheticBlackFrame(frame)) {
            consecutiveBlackAnalyses += 1
        } else {
            consecutiveBlackAnalyses = 0
            lastNonBlackFrameAtMs = nowMs
        }

        syntheticBlackDetected = isSustainedSyntheticBlack(nowMs)
        return previousSyntheticBlack != syntheticBlackDetected
    }

    fun isVideoConsideredOff(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (lastFrameAtMs <= 0L || nowMs - lastFrameAtMs > staleFrameThresholdMs) {
            return true
        }
        return isSustainedSyntheticBlack(nowMs)
    }

    fun isSyntheticBlackDetected(): Boolean = syntheticBlackDetected

    fun diagnostics(
        trackPresent: Boolean,
        trackEnabled: Boolean,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): String {
        val frameAgeMs = if (lastFrameAtMs > 0L) nowMs - lastFrameAtMs else -1L
        val nonBlackAgeMs = if (lastNonBlackFrameAtMs > 0L) nowMs - lastNonBlackFrameAtMs else -1L
        return "trackPresent=$trackPresent,trackEnabled=$trackEnabled,frameAgeMs=$frameAgeMs,nonBlackAgeMs=$nonBlackAgeMs,blackAnalyses=$consecutiveBlackAnalyses,syntheticBlack=$syntheticBlackDetected"
    }

    private fun isSustainedSyntheticBlack(nowMs: Long): Boolean {
        return consecutiveBlackAnalyses >= consecutiveBlackThreshold &&
            (lastNonBlackFrameAtMs <= 0L || nowMs - lastNonBlackFrameAtMs > nonBlackWindowMs)
    }

    private fun isSyntheticBlackFrame(frame: VideoFrame): Boolean {
        val i420 = frame.buffer.toI420() ?: return false
        return try {
            val width = i420.width
            val height = i420.height
            if (width <= 0 || height <= 0) return false

            val yData = i420.dataY
            val stride = i420.strideY
            var minY = 255
            var maxY = 0

            for (row in 0 until sampleRows) {
                val y = (((row + 0.5f) * height) / sampleRows).toInt().coerceIn(0, height - 1)
                for (col in 0 until sampleCols) {
                    val x = (((col + 0.5f) * width) / sampleCols).toInt().coerceIn(0, width - 1)
                    val sample = yData.get(y * stride + x).toInt() and 0xFF
                    if (sample < minY) minY = sample
                    if (sample > maxY) maxY = sample
                }
            }

            maxY <= blackLumaMax && (maxY - minY) <= blackLumaSpreadMax
        } catch (_: Exception) {
            false
        } finally {
            i420.release()
        }
    }
}
