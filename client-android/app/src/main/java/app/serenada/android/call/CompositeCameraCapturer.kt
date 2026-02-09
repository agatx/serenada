package app.serenada.android.call

import android.content.Context
import java.nio.ByteBuffer
import kotlin.math.min
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame

class CompositeCameraCapturer(
    context: Context,
    private val eglContext: EglBase.Context,
    private val mainCapturer: CameraVideoCapturer,
    private val overlayCapturer: CameraVideoCapturer,
    private val onStartFailure: (() -> Unit)? = null
) : VideoCapturer {
    private enum class ChildCapturer {
        MAIN,
        OVERLAY
    }

    private data class OverlayFrame(
        val buffer: VideoFrame.I420Buffer,
        val rotation: Int
    )

    private val appContext = context.applicationContext
    private val frameLock = Any()

    private var outputObserver: CapturerObserver? = null
    private var overlayTextureHelper: SurfaceTextureHelper? = null
    private var latestOverlayFrame: OverlayFrame? = null
    private var mainStartResult: Boolean? = null
    private var overlayStartResult: Boolean? = null
    private var startReported = false
    private var started = false

    private val mainObserver = object : CapturerObserver {
        override fun onCapturerStarted(success: Boolean) {
            onChildCapturerStarted(ChildCapturer.MAIN, success)
        }

        override fun onCapturerStopped() = Unit

        override fun onFrameCaptured(frame: VideoFrame) {
            val observer = outputObserver ?: return
            val composed = composeFrame(frame)
            observer.onFrameCaptured(composed)
            composed.release()
        }
    }

    private val overlayObserver = object : CapturerObserver {
        override fun onCapturerStarted(success: Boolean) {
            onChildCapturerStarted(ChildCapturer.OVERLAY, success)
        }

        override fun onCapturerStopped() = Unit

        override fun onFrameCaptured(frame: VideoFrame) {
            val converted = frame.buffer.toI420() ?: return
            val overlayFrame = OverlayFrame(
                buffer = converted,
                rotation = normalizeRotation(frame.rotation)
            )
            synchronized(frameLock) {
                latestOverlayFrame?.buffer?.release()
                latestOverlayFrame = overlayFrame
            }
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?
    ) {
        requireNotNull(surfaceTextureHelper) { "surfaceTextureHelper is required" }
        requireNotNull(capturerObserver) { "capturerObserver is required" }
        outputObserver = capturerObserver
        overlayTextureHelper = SurfaceTextureHelper.create("CaptureThreadOverlay", eglContext)
        mainCapturer.initialize(surfaceTextureHelper, applicationContext ?: appContext, mainObserver)
        overlayCapturer.initialize(
            overlayTextureHelper,
            applicationContext ?: appContext,
            overlayObserver
        )
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        if (started) return
        started = true
        mainStartResult = null
        overlayStartResult = null
        startReported = false
        var mainStarted = false
        var overlayStarted = false
        try {
            mainCapturer.startCapture(width, height, framerate)
            mainStarted = true
            overlayCapturer.startCapture(width, height, framerate)
            overlayStarted = true
        } catch (e: Exception) {
            if (overlayStarted) {
                runCatching { overlayCapturer.stopCapture() }
            }
            if (mainStarted) {
                runCatching { mainCapturer.stopCapture() }
            }
            notifyStartFailed()
            throw e
        }
    }

    override fun stopCapture() {
        if (!started) return
        runCatching { overlayCapturer.stopCapture() }
        runCatching { mainCapturer.stopCapture() }
        started = false
        mainStartResult = null
        overlayStartResult = null
        startReported = false
        outputObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        mainCapturer.changeCaptureFormat(width, height, framerate)
        overlayCapturer.changeCaptureFormat(width, height, framerate)
    }

    override fun dispose() {
        runCatching { stopCapture() }
        mainCapturer.dispose()
        overlayCapturer.dispose()
        overlayTextureHelper?.dispose()
        overlayTextureHelper = null
        synchronized(frameLock) {
            latestOverlayFrame?.buffer?.release()
            latestOverlayFrame = null
        }
        outputObserver = null
    }

    override fun isScreencast(): Boolean = false

    private fun onChildCapturerStarted(capturer: ChildCapturer, success: Boolean) {
        if (!started) return
        if (!success) {
            notifyStartFailed()
            return
        }
        var notifySuccess = false
        synchronized(frameLock) {
            if (!started || startReported) return
            when (capturer) {
                ChildCapturer.MAIN -> mainStartResult = true
                ChildCapturer.OVERLAY -> overlayStartResult = true
            }
            if (mainStartResult == true && overlayStartResult == true) {
                startReported = true
                notifySuccess = true
            }
        }
        if (notifySuccess) {
            outputObserver?.onCapturerStarted(true)
        }
    }

    private fun notifyStartFailed() {
        synchronized(frameLock) {
            if (!started || startReported) return
            startReported = true
        }
        started = false
        runCatching { overlayCapturer.stopCapture() }
        runCatching { mainCapturer.stopCapture() }
        outputObserver?.onCapturerStarted(false)
        onStartFailure?.invoke()
    }

    private fun composeFrame(mainFrame: VideoFrame): VideoFrame {
        val mainI420 = requireNotNull(mainFrame.buffer.toI420())
        val width = mainI420.width
        val height = mainI420.height
        val output = requireNotNull(JavaI420Buffer.allocate(width, height))
        copyPlane(
            src = mainI420.dataY,
            srcStride = mainI420.strideY,
            dst = output.dataY,
            dstStride = output.strideY,
            width = width,
            height = height
        )
        copyPlane(
            src = mainI420.dataU,
            srcStride = mainI420.strideU,
            dst = output.dataU,
            dstStride = output.strideU,
            width = width / 2,
            height = height / 2
        )
        copyPlane(
            src = mainI420.dataV,
            srcStride = mainI420.strideV,
            dst = output.dataV,
            dstStride = output.strideV,
            width = width / 2,
            height = height / 2
        )

        val overlay = synchronized(frameLock) {
            latestOverlayFrame?.let {
                it.buffer.retain()
                OverlayFrame(buffer = it.buffer, rotation = it.rotation)
            }
        }

        if (overlay != null) {
            drawCircularOverlay(
                output = output,
                outputRotation = normalizeRotation(mainFrame.rotation),
                overlay = overlay
            )
            overlay.buffer.release()
        }
        mainI420.release()
        return VideoFrame(output, mainFrame.rotation, mainFrame.timestampNs)
    }

    private fun drawCircularOverlay(
        output: JavaI420Buffer,
        outputRotation: Int,
        overlay: OverlayFrame
    ) {
        val outputWidth = output.width
        val outputHeight = output.height
        val outputDisplayWidth = if (outputRotation % 180 == 0) outputWidth else outputHeight
        val outputDisplayHeight = if (outputRotation % 180 == 0) outputHeight else outputWidth

        val diameter = (min(outputDisplayWidth, outputDisplayHeight) * OVERLAY_DIAMETER_RATIO).toInt()
            .coerceAtLeast(MIN_OVERLAY_SIZE)
            .coerceAtMost(min(outputDisplayWidth, outputDisplayHeight) - 2)
        if (diameter <= 2) return

        val radius = diameter / 2
        val margin = (outputDisplayHeight * OVERLAY_BOTTOM_MARGIN_RATIO).toInt().coerceAtLeast(8)
        val centerX = outputDisplayWidth / 2
        val centerY = (outputDisplayHeight - margin - radius).coerceAtLeast(radius + 1)
        val left = (centerX - radius).coerceAtLeast(0)
        val top = (centerY - radius).coerceAtLeast(0)
        val right = (centerX + radius).coerceAtMost(outputDisplayWidth)
        val bottom = (centerY + radius).coerceAtMost(outputDisplayHeight)
        val drawWidth = right - left
        val drawHeight = bottom - top
        if (drawWidth <= 2 || drawHeight <= 2) return

        val drawRadius = min(drawWidth, drawHeight) / 2
        if (drawRadius <= 1) return
        val drawCenterX = left + drawWidth / 2
        val drawCenterY = top + drawHeight / 2
        val drawRadiusSq = drawRadius * drawRadius

        val overlayBuffer = overlay.buffer
        val overlayDisplayWidth =
            if (overlay.rotation % 180 == 0) overlayBuffer.width else overlayBuffer.height
        val overlayDisplayHeight =
            if (overlay.rotation % 180 == 0) overlayBuffer.height else overlayBuffer.width

        val srcSize = min(overlayDisplayWidth, overlayDisplayHeight)
        val srcLeft = (overlayDisplayWidth - srcSize) / 2
        val srcTop = (overlayDisplayHeight - srcSize) / 2
        if (srcSize <= 1) return

        for (displayY in top until bottom) {
            val dy = displayY - drawCenterY
            for (displayX in left until right) {
                val dx = displayX - drawCenterX
                if (dx * dx + dy * dy > drawRadiusSq) {
                    continue
                }

                val outputCoord = displayToBufferCoord(
                    displayX = displayX,
                    displayY = displayY,
                    bufferWidth = outputWidth,
                    bufferHeight = outputHeight,
                    rotation = outputRotation
                ) ?: continue

                val srcDisplayX =
                    srcLeft + ((displayX - left) * srcSize / drawWidth).coerceIn(0, srcSize - 1)
                val srcDisplayY =
                    srcTop + ((displayY - top) * srcSize / drawHeight).coerceIn(0, srcSize - 1)
                val srcBufferCoord = displayToBufferCoord(
                    displayX = srcDisplayX,
                    displayY = srcDisplayY,
                    bufferWidth = overlayBuffer.width,
                    bufferHeight = overlayBuffer.height,
                    rotation = overlay.rotation
                ) ?: continue
                val yValue = getPlaneValue(
                    plane = overlayBuffer.dataY,
                    stride = overlayBuffer.strideY,
                    x = srcBufferCoord.first,
                    y = srcBufferCoord.second
                )
                putPlaneValue(
                    plane = output.dataY,
                    stride = output.strideY,
                    x = outputCoord.first,
                    y = outputCoord.second,
                    value = yValue
                )
            }
        }

        if (srcSize / 2 <= 0) return
        val uvLeft = left / 2
        val uvTop = top / 2
        val uvRight = right / 2
        val uvBottom = bottom / 2
        for (displayUvY in uvTop until uvBottom) {
            val displayLumaY = displayUvY * 2 + 1
            val dy = displayLumaY - drawCenterY
            for (displayUvX in uvLeft until uvRight) {
                val displayLumaX = displayUvX * 2 + 1
                val dx = displayLumaX - drawCenterX
                if (dx * dx + dy * dy > drawRadiusSq) {
                    continue
                }

                val outputLumaCoord = displayToBufferCoord(
                    displayX = displayLumaX,
                    displayY = displayLumaY,
                    bufferWidth = outputWidth,
                    bufferHeight = outputHeight,
                    rotation = outputRotation
                ) ?: continue
                val outputUvX = (outputLumaCoord.first / 2).coerceIn(0, (outputWidth / 2) - 1)
                val outputUvY = (outputLumaCoord.second / 2).coerceIn(0, (outputHeight / 2) - 1)

                val srcDisplayLumaX =
                    srcLeft + ((displayLumaX - left) * srcSize / drawWidth).coerceIn(0, srcSize - 1)
                val srcDisplayLumaY =
                    srcTop + ((displayLumaY - top) * srcSize / drawHeight).coerceIn(0, srcSize - 1)
                val srcLumaCoord = displayToBufferCoord(
                    displayX = srcDisplayLumaX,
                    displayY = srcDisplayLumaY,
                    bufferWidth = overlayBuffer.width,
                    bufferHeight = overlayBuffer.height,
                    rotation = overlay.rotation
                ) ?: continue

                val srcUvX = (srcLumaCoord.first / 2).coerceIn(0, (overlayBuffer.width / 2) - 1)
                val srcUvY = (srcLumaCoord.second / 2).coerceIn(0, (overlayBuffer.height / 2) - 1)

                val uValue = getPlaneValue(
                    plane = overlayBuffer.dataU,
                    stride = overlayBuffer.strideU,
                    x = srcUvX,
                    y = srcUvY
                )
                val vValue = getPlaneValue(
                    plane = overlayBuffer.dataV,
                    stride = overlayBuffer.strideV,
                    x = srcUvX,
                    y = srcUvY
                )
                putPlaneValue(
                    plane = output.dataU,
                    stride = output.strideU,
                    x = outputUvX,
                    y = outputUvY,
                    value = uValue
                )
                putPlaneValue(
                    plane = output.dataV,
                    stride = output.strideV,
                    x = outputUvX,
                    y = outputUvY,
                    value = vValue
                )
            }
        }
    }

    private fun copyPlane(
        src: ByteBuffer,
        srcStride: Int,
        dst: ByteBuffer,
        dstStride: Int,
        width: Int,
        height: Int
    ) {
        val srcRow = src.duplicate()
        val dstRow = dst.duplicate()
        for (row in 0 until height) {
            val srcOffset = row * srcStride
            srcRow.position(srcOffset)
            srcRow.limit(srcOffset + width)
            dstRow.position(row * dstStride)
            dstRow.put(srcRow)
            srcRow.limit(srcRow.capacity())
        }
    }

    private fun getPlaneValue(
        plane: ByteBuffer,
        stride: Int,
        x: Int,
        y: Int
    ): Byte = plane.get(y * stride + x)

    private fun putPlaneValue(
        plane: ByteBuffer,
        stride: Int,
        x: Int,
        y: Int,
        value: Byte
    ) {
        plane.put(y * stride + x, value)
    }

    private fun displayToBufferCoord(
        displayX: Int,
        displayY: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rotation: Int
    ): Pair<Int, Int>? {
        val coord = when (rotation) {
            0 -> Pair(displayX, displayY)
            90 -> Pair(displayY, bufferHeight - 1 - displayX)
            180 -> Pair(bufferWidth - 1 - displayX, bufferHeight - 1 - displayY)
            270 -> Pair(bufferWidth - 1 - displayY, displayX)
            else -> Pair(displayX, displayY)
        }
        if (coord.first !in 0 until bufferWidth || coord.second !in 0 until bufferHeight) {
            return null
        }
        return coord
    }

    private fun normalizeRotation(rotation: Int): Int {
        val normalized = ((rotation % 360) + 360) % 360
        return when (normalized) {
            0, 90, 180, 270 -> normalized
            else -> 0
        }
    }

    private companion object {
        const val OVERLAY_DIAMETER_RATIO = 0.30f
        const val OVERLAY_BOTTOM_MARGIN_RATIO = 0.04f
        const val MIN_OVERLAY_SIZE = 72
    }
}
