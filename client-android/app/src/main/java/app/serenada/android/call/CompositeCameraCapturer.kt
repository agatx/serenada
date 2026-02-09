package app.serenada.android.call

import android.content.Context
import java.nio.ByteBuffer
import kotlin.math.floor
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
        val outerRadiusSq = drawRadius.toFloat() * drawRadius.toFloat()
        val edgeFeatherPx = (drawRadius * EDGE_FEATHER_RATIO).coerceIn(MIN_EDGE_FEATHER_PX, MAX_EDGE_FEATHER_PX)
        val innerRadius = (drawRadius.toFloat() - edgeFeatherPx).coerceAtLeast(0.5f)
        val innerRadiusSq = innerRadius * innerRadius
        val edgeBlendDenominator = (outerRadiusSq - innerRadiusSq).coerceAtLeast(1f)

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
                val distSq = dx.toFloat() * dx.toFloat() + dy.toFloat() * dy.toFloat()
                val edgeAlpha = edgeAlpha(
                    distanceSq = distSq,
                    innerRadiusSq = innerRadiusSq,
                    outerRadiusSq = outerRadiusSq,
                    edgeBlendDenominator = edgeBlendDenominator
                )
                if (edgeAlpha <= 0f) {
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
                    srcLeft + (((displayX - left) + 0.5f) * srcSize / drawWidth.toFloat()) - 0.5f
                val srcDisplayY =
                    srcTop + (((displayY - top) + 0.5f) * srcSize / drawHeight.toFloat()) - 0.5f
                val srcBufferCoord = displayToBufferCoordFloat(
                    displayX = srcDisplayX,
                    displayY = srcDisplayY,
                    bufferWidth = overlayBuffer.width,
                    bufferHeight = overlayBuffer.height,
                    rotation = overlay.rotation
                ) ?: continue
                val yValue = samplePlaneBilinear(
                    plane = overlayBuffer.dataY,
                    stride = overlayBuffer.strideY,
                    width = overlayBuffer.width,
                    height = overlayBuffer.height,
                    x = srcBufferCoord.first,
                    y = srcBufferCoord.second
                )
                blendPlaneValue(
                    plane = output.dataY,
                    stride = output.strideY,
                    x = outputCoord.first,
                    y = outputCoord.second,
                    overlayValue = yValue,
                    alpha = edgeAlpha
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
                val distSq = dx.toFloat() * dx.toFloat() + dy.toFloat() * dy.toFloat()
                val edgeAlpha = edgeAlpha(
                    distanceSq = distSq,
                    innerRadiusSq = innerRadiusSq,
                    outerRadiusSq = outerRadiusSq,
                    edgeBlendDenominator = edgeBlendDenominator
                )
                if (edgeAlpha <= 0f) {
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
                    srcLeft + (((displayLumaX - left) + 0.5f) * srcSize / drawWidth.toFloat()) - 0.5f
                val srcDisplayLumaY =
                    srcTop + (((displayLumaY - top) + 0.5f) * srcSize / drawHeight.toFloat()) - 0.5f
                val srcLumaCoord = displayToBufferCoordFloat(
                    displayX = srcDisplayLumaX,
                    displayY = srcDisplayLumaY,
                    bufferWidth = overlayBuffer.width,
                    bufferHeight = overlayBuffer.height,
                    rotation = overlay.rotation
                ) ?: continue

                val srcUvX = srcLumaCoord.first / 2f
                val srcUvY = srcLumaCoord.second / 2f

                val uValue = samplePlaneBilinear(
                    plane = overlayBuffer.dataU,
                    stride = overlayBuffer.strideU,
                    width = overlayBuffer.width / 2,
                    height = overlayBuffer.height / 2,
                    x = srcUvX,
                    y = srcUvY
                )
                val vValue = samplePlaneBilinear(
                    plane = overlayBuffer.dataV,
                    stride = overlayBuffer.strideV,
                    width = overlayBuffer.width / 2,
                    height = overlayBuffer.height / 2,
                    x = srcUvX,
                    y = srcUvY
                )
                blendPlaneValue(
                    plane = output.dataU,
                    stride = output.strideU,
                    x = outputUvX,
                    y = outputUvY,
                    overlayValue = uValue,
                    alpha = edgeAlpha
                )
                blendPlaneValue(
                    plane = output.dataV,
                    stride = output.strideV,
                    x = outputUvX,
                    y = outputUvY,
                    overlayValue = vValue,
                    alpha = edgeAlpha
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

    private fun getPlaneValueUnsigned(
        plane: ByteBuffer,
        stride: Int,
        x: Int,
        y: Int
    ): Int = plane.get(y * stride + x).toInt() and 0xFF

    private fun samplePlaneBilinear(
        plane: ByteBuffer,
        stride: Int,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Byte {
        if (width <= 0 || height <= 0) return 0
        if (width == 1 || height == 1) {
            val px = x.toInt().coerceIn(0, width - 1)
            val py = y.toInt().coerceIn(0, height - 1)
            return getPlaneValue(plane, stride, px, py)
        }

        val xClamped = x.coerceIn(0f, (width - 1).toFloat())
        val yClamped = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(xClamped).toInt().coerceIn(0, width - 1)
        val y0 = floor(yClamped).toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = xClamped - x0
        val fy = yClamped - y0

        val v00 = getPlaneValueUnsigned(plane, stride, x0, y0)
        val v10 = getPlaneValueUnsigned(plane, stride, x1, y0)
        val v01 = getPlaneValueUnsigned(plane, stride, x0, y1)
        val v11 = getPlaneValueUnsigned(plane, stride, x1, y1)

        val top = v00 + (v10 - v00) * fx
        val bottom = v01 + (v11 - v01) * fx
        val value = (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
        return value.toByte()
    }

    private fun putPlaneValue(
        plane: ByteBuffer,
        stride: Int,
        x: Int,
        y: Int,
        value: Byte
    ) {
        plane.put(y * stride + x, value)
    }

    private fun blendPlaneValue(
        plane: ByteBuffer,
        stride: Int,
        x: Int,
        y: Int,
        overlayValue: Byte,
        alpha: Float
    ) {
        if (alpha <= 0f) return
        if (alpha >= 0.999f) {
            putPlaneValue(plane, stride, x, y, overlayValue)
            return
        }
        val base = getPlaneValueUnsigned(plane, stride, x, y)
        val over = overlayValue.toInt() and 0xFF
        val blended = (base + (over - base) * alpha).toInt().coerceIn(0, 255)
        putPlaneValue(plane, stride, x, y, blended.toByte())
    }

    private fun edgeAlpha(
        distanceSq: Float,
        innerRadiusSq: Float,
        outerRadiusSq: Float,
        edgeBlendDenominator: Float
    ): Float {
        if (distanceSq <= innerRadiusSq) return 1f
        if (distanceSq >= outerRadiusSq) return 0f
        return ((outerRadiusSq - distanceSq) / edgeBlendDenominator).coerceIn(0f, 1f)
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

    private fun displayToBufferCoordFloat(
        displayX: Float,
        displayY: Float,
        bufferWidth: Int,
        bufferHeight: Int,
        rotation: Int
    ): Pair<Float, Float>? {
        val coord = when (rotation) {
            0 -> Pair(displayX, displayY)
            90 -> Pair(displayY, (bufferHeight - 1).toFloat() - displayX)
            180 -> Pair((bufferWidth - 1).toFloat() - displayX, (bufferHeight - 1).toFloat() - displayY)
            270 -> Pair((bufferWidth - 1).toFloat() - displayY, displayX)
            else -> Pair(displayX, displayY)
        }
        if (
            coord.first < 0f || coord.first > (bufferWidth - 1).toFloat() ||
                coord.second < 0f || coord.second > (bufferHeight - 1).toFloat()
        ) {
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
        const val OVERLAY_DIAMETER_RATIO = 0.36f
        const val OVERLAY_BOTTOM_MARGIN_RATIO = 0.04f
        const val MIN_OVERLAY_SIZE = 72
        const val EDGE_FEATHER_RATIO = 0.045f
        const val MIN_EDGE_FEATHER_PX = 1.5f
        const val MAX_EDGE_FEATHER_PX = 6f
    }
}
