package app.serenada.core.call

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import app.serenada.core.SnapshotError
import app.serenada.core.SnapshotResult
import app.serenada.core.SnapshotSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Captures a single full-resolution JPEG frame from a renderer-attachable
 * source. Caller supplies [attachSink]/[detachSink] bound to the chosen
 * track; this type owns the sink, the timeout, and JPEG encoding.
 *
 * Designed to be called from the main thread; the sink callback runs on
 * WebRTC's render thread, and JPEG encoding is offloaded to a worker thread
 * before the result is delivered back to the main thread via [handler].
 */
internal class FrameSnapshotCapture(
    private val handler: Handler,
    private val source: SnapshotSource,
    private val attachSink: (VideoSink) -> Unit,
    private val detachSink: (VideoSink) -> Unit,
    private val jpegQuality: Int = DEFAULT_QUALITY,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun capture(): SnapshotResult = suspendCancellableCoroutine { cont ->
        val completed = AtomicBoolean(false)
        // Separate latch so only the FIRST inbound frame for this capture
        // gets handed to the encoder. Without it, a 30 fps stream can spawn
        // multiple full-resolution encoding threads in parallel before the
        // first one finishes and flips `completed`.
        val frameClaimed = AtomicBoolean(false)
        lateinit var sink: VideoSink
        var timeoutRunnable: Runnable? = null

        fun finishSuccess(result: SnapshotResult) {
            if (!completed.compareAndSet(false, true)) return
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            handler.post {
                detachSink(sink)
                if (cont.isActive) cont.resume(result)
            }
        }

        fun finishError(error: Throwable) {
            if (!completed.compareAndSet(false, true)) return
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            handler.post {
                detachSink(sink)
                if (cont.isActive) cont.resumeWithException(error)
            }
        }

        timeoutRunnable = Runnable { finishError(SnapshotError.CaptureTimeout) }

        sink = VideoSink { frame ->
            if (completed.get()) return@VideoSink
            // Atomically claim this frame; later frames are skipped while the
            // first one is still encoding so we never run two parallel encodes.
            if (!frameClaimed.compareAndSet(false, true)) return@VideoSink
            frame.retain()
            thread(name = "snapshot-encode", start = true) {
                val encoded = runCatching { encodeFullResolutionJpeg(frame, jpegQuality) }
                    .getOrNull()
                frame.release()
                if (encoded == null) {
                    // Buffer was unencodable (e.g., zero dimensions). Reset the
                    // claim so the next frame can try.
                    frameClaimed.set(false)
                    return@thread
                }
                finishSuccess(
                    SnapshotResult(
                        jpeg = encoded.bytes,
                        width = encoded.width,
                        height = encoded.height,
                        timestampMs = System.currentTimeMillis(),
                        source = source,
                    )
                )
            }
        }

        cont.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) {
                handler.post {
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    detachSink(sink)
                }
            }
        }

        attachSink(sink)
        handler.postDelayed(timeoutRunnable, timeoutMs)
    }

    private data class EncodedFrame(val bytes: ByteArray, val width: Int, val height: Int)

    private fun encodeFullResolutionJpeg(frame: VideoFrame, quality: Int): EncodedFrame? {
        val i420 = frame.buffer.toI420() ?: return null
        return try {
            val width = i420.width
            val height = i420.height
            if (width <= 0 || height <= 0) return null

            val nv21 = i420ToNv21(i420)
            val rawJpeg = ByteArrayOutputStream().use { output ->
                val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                if (!image.compressToJpeg(Rect(0, 0, width, height), quality, output)) {
                    return null
                }
                output.toByteArray()
            }

            val rotation = frame.rotation
            if (rotation == 0) {
                EncodedFrame(rawJpeg, width, height)
            } else {
                val source = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return null
                var rotated: Bitmap? = null
                try {
                    rotated = rotateBitmap(source, rotation)
                    val output = ByteArrayOutputStream()
                    val ok = rotated.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    if (!ok) return null
                    EncodedFrame(output.toByteArray(), rotated.width, rotated.height)
                } finally {
                    if (rotated != null && rotated !== source && !rotated.isRecycled) rotated.recycle()
                    if (!source.isRecycled) source.recycle()
                }
            }
        } finally {
            i420.release()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
        val normalized = ((rotation % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrElse { bitmap }
    }

    private fun i420ToNv21(buffer: VideoFrame.I420Buffer): ByteArray {
        val width = buffer.width
        val height = buffer.height
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val uvSize = chromaWidth * chromaHeight

        val out = ByteArray(ySize + uvSize * 2)
        copyPlane(buffer.dataY, buffer.strideY, width, height, out, 0, width)

        val u = ByteArray(uvSize)
        val v = ByteArray(uvSize)
        copyPlane(buffer.dataU, buffer.strideU, chromaWidth, chromaHeight, u, 0, chromaWidth)
        copyPlane(buffer.dataV, buffer.strideV, chromaWidth, chromaHeight, v, 0, chromaWidth)

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

    companion object {
        const val DEFAULT_QUALITY: Int = 95
        const val DEFAULT_TIMEOUT_MS: Long = 2_000L
    }
}
