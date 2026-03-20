package app.serenada.core.call

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

internal class LocalFrameSnapshotCapture(
    private val handler: Handler,
    private val attachLocalSink: (VideoSink) -> Unit,
    private val detachLocalSink: (VideoSink) -> Unit,
) {
    fun capture(onResult: (ByteArray?) -> Unit) {
        val completed = AtomicBoolean(false)
        val sawLikelyBlackFrame = AtomicBoolean(false)
        lateinit var sink: VideoSink
        var timeoutRunnable: Runnable? = null

        fun complete(snapshot: ByteArray?) {
            if (!completed.compareAndSet(false, true)) return
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            handler.post {
                detachLocalSink(sink)
                onResult(snapshot)
            }
        }

        timeoutRunnable = Runnable { complete(null) }

        sink = VideoSink { frame ->
            if (completed.get()) return@VideoSink
            frame.retain()
            thread(name = "local-snapshot-frame", start = true) {
                val encoded = runCatching { encodeSnapshotFrame(frame) }.getOrNull()
                    ?: EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = false)
                frame.release()
                if (completed.get()) return@thread
                if (encoded.isLikelyBlackFrame) {
                    sawLikelyBlackFrame.set(true)
                    return@thread
                }
                val snapshot = encoded.snapshot ?: return@thread
                complete(snapshot)
            }
        }

        attachLocalSink(sink)
        handler.postDelayed(timeoutRunnable, SNAPSHOT_FRAME_TIMEOUT_MS)
    }

    private fun encodeSnapshotFrame(frame: VideoFrame): EncodedSnapshotResult {
        val i420 = frame.buffer.toI420() ?: return EncodedSnapshotResult(
            snapshot = null,
            isLikelyBlackFrame = false,
        )
        return try {
            val width = i420.width
            val height = i420.height
            if (width <= 0 || height <= 0) {
                return EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = false)
            }
            if (isLikelyBlackFrame(i420)) {
                return EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = true)
            }

            val nv21 = i420ToNv21(i420)
            val rawJpeg = ByteArrayOutputStream().use { output ->
                val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                if (!image.compressToJpeg(Rect(0, 0, width, height), 90, output)) {
                    return EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = false)
                }
                output.toByteArray()
            }

            val source = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size)
                ?: return EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = false)
            var rotated: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                rotated = rotateBitmapIfNeeded(source, frame.rotation)
                scaled = scaleBitmapIfNeeded(rotated, SNAPSHOT_MAX_WIDTH_PX)
                val qualities = intArrayOf(70, 60, 50, 40, 30)
                for (quality in qualities) {
                    val encoded = compressBitmapAsJpeg(scaled, quality) ?: continue
                    if (encoded.size <= SNAPSHOT_MAX_BYTES) {
                        return EncodedSnapshotResult(
                            snapshot = encoded,
                            isLikelyBlackFrame = false,
                        )
                    }
                }
                EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = false)
            } finally {
                if (scaled !== null && scaled !== rotated && !scaled.isRecycled) scaled.recycle()
                if (rotated !== null && rotated !== source && !rotated.isRecycled) rotated.recycle()
                if (!source.isRecycled) source.recycle()
            }
        } finally {
            i420.release()
        }
    }

    private fun isLikelyBlackFrame(buffer: VideoFrame.I420Buffer): Boolean {
        val width = buffer.width
        val height = buffer.height
        if (width <= 0 || height <= 0) return true

        val stepX = (width / 24).coerceAtLeast(1)
        val stepY = (height / 24).coerceAtLeast(1)
        val yPlane = buffer.dataY.duplicate()

        var sampleCount = 0L
        var sampleSum = 0L
        var sampleSumSquares = 0L
        var sampleMax = 0

        for (y in 0 until height step stepY) {
            val rowStart = y * buffer.strideY
            for (x in 0 until width step stepX) {
                val value = yPlane.get(rowStart + x).toInt() and 0xFF
                sampleCount += 1
                sampleSum += value
                sampleSumSquares += value.toLong() * value.toLong()
                if (value > sampleMax) sampleMax = value
            }
        }

        if (sampleCount == 0L) return true

        val meanTimes100 = (sampleSum * 100L) / sampleCount
        val meanSquareTimes100 = (sampleSumSquares * 100L) / sampleCount
        val meanSquaredTimes100 = (meanTimes100 * meanTimes100) / 100L
        val varianceTimes100 = (meanSquareTimes100 - meanSquaredTimes100).coerceAtLeast(0L)

        return meanTimes100 < 800L && varianceTimes100 < 2500L && sampleMax < 32
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, rotation: Int): Bitmap {
        val normalized = ((rotation % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrElse { bitmap }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val scale = maxWidth.toFloat() / bitmap.width.toFloat()
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
        }.getOrElse { bitmap }
    }

    private fun compressBitmapAsJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        return ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                return@use null
            }
            output.toByteArray()
        }
    }

    private fun i420ToNv21(buffer: VideoFrame.I420Buffer): ByteArray {
        val width = buffer.width
        val height = buffer.height
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val uvSize = chromaWidth * chromaHeight

        val out = ByteArray(ySize + uvSize * 2)
        copyPlane(
            src = buffer.dataY,
            srcStride = buffer.strideY,
            width = width,
            height = height,
            dst = out,
            dstOffset = 0,
            dstStride = width,
        )

        val u = ByteArray(uvSize)
        val v = ByteArray(uvSize)
        copyPlane(
            src = buffer.dataU,
            srcStride = buffer.strideU,
            width = chromaWidth,
            height = chromaHeight,
            dst = u,
            dstOffset = 0,
            dstStride = chromaWidth,
        )
        copyPlane(
            src = buffer.dataV,
            srcStride = buffer.strideV,
            width = chromaWidth,
            height = chromaHeight,
            dst = v,
            dstOffset = 0,
            dstStride = chromaWidth,
        )

        var offset = ySize
        for (i in 0 until uvSize) {
            out[offset++] = v[i]
            out[offset++] = u[i]
        }
        return out
    }

    private fun copyPlane(
        src: ByteBuffer,
        srcStride: Int,
        width: Int,
        height: Int,
        dst: ByteArray,
        dstOffset: Int,
        dstStride: Int,
    ) {
        val rowBuffer = ByteArray(width)
        val source = src.duplicate()
        var dstIndex = dstOffset
        for (row in 0 until height) {
            source.position(row * srcStride)
            source.get(rowBuffer, 0, width)
            System.arraycopy(rowBuffer, 0, dst, dstIndex, width)
            dstIndex += dstStride
        }
    }

    private data class EncodedSnapshotResult(
        val snapshot: ByteArray?,
        val isLikelyBlackFrame: Boolean,
    )

    private companion object {
        const val SNAPSHOT_FRAME_TIMEOUT_MS = 900L
        const val SNAPSHOT_MAX_WIDTH_PX = 320
        const val SNAPSHOT_MAX_BYTES = 200 * 1024
    }
}
