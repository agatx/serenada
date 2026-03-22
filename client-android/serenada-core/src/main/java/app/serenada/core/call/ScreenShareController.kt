package app.serenada.core.call

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import kotlin.math.min
import kotlin.math.roundToInt
import org.webrtc.EglBase
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper

internal class ScreenShareController(
    private val appContext: Context,
    private val eglBase: EglBase,
    private val cameraController: CameraCaptureController,
    private val capturerObserverProvider: () -> org.webrtc.CapturerObserver?,
    private val videoSourceProvider: () -> org.webrtc.VideoSource?,
    private val onScreenShareStopped: () -> Unit,
    private val onStateChanged: (Boolean) -> Unit,
    private val logger: SerenadaLogger? = null,
) {
    var isScreenSharing: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    fun startScreenShare(intent: Intent): Boolean {
        if (isScreenSharing) return true
        val observer = capturerObserverProvider() ?: return false
        val previousSource = cameraController.currentCameraSource
        cameraController.resetScreenShareCameraState()
        cameraController.disposeVideoCapturer()
        val capturer = ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    if (isScreenSharing) {
                        stopScreenShare()
                        onScreenShareStopped()
                    }
                }
            }
        })
        val textureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
        val captureProfile = selectScreenShareCaptureProfile()
        return try {
            capturer.initialize(textureHelper, appContext, observer)
            capturer.startCapture(captureProfile.width, captureProfile.height, captureProfile.fps)
            cameraController.setScreenShareVideoCapturer(capturer, textureHelper)
            cameraController.cameraSourceBeforeScreenShare = previousSource
            isScreenSharing = true
            cameraController.isScreenSharing = true
            onStateChanged(true)
            logger?.log(
                SerenadaLogLevel.DEBUG,
                "ScreenShare",
                "Screen share capture profile: ${captureProfile.width}x${captureProfile.height}@${captureProfile.fps}fps"
            )
            cameraController.applyTorchForCurrentMode()
            true
        } catch (e: Exception) {
            logger?.log(SerenadaLogLevel.WARNING, "ScreenShare", "Failed to start screen sharing: ${e.message}")
            runCatching { capturer.dispose() }
            runCatching { textureHelper.dispose() }
            val videoSource = videoSourceProvider()
            if (!cameraController.restartVideoCapturer(previousSource, videoSource)) {
                cameraController.restartVideoCapturer(CameraCaptureController.LocalCameraSource.SELFIE, videoSource)
            }
            false
        }
    }

    fun stopScreenShare(): Boolean {
        if (!isScreenSharing) return true
        val sourceToRestore = cameraController.cameraSourceBeforeScreenShare ?: cameraController.currentCameraSource
        isScreenSharing = false
        cameraController.isScreenSharing = false
        cameraController.cameraSourceBeforeScreenShare = null
        cameraController.disposeVideoCapturer()
        val videoSource = videoSourceProvider()
        if (!cameraController.restartVideoCapturer(sourceToRestore, videoSource) &&
            !cameraController.restartVideoCapturer(CameraCaptureController.LocalCameraSource.SELFIE, videoSource)) {
            logger?.log(SerenadaLogLevel.WARNING, "ScreenShare", "Failed to restore camera after screen sharing stop")
        }
        return true
    }

    fun reset() {
        isScreenSharing = false
        cameraController.isScreenSharing = false
        cameraController.cameraSourceBeforeScreenShare = null
    }

    private fun selectScreenShareCaptureProfile(): CameraCaptureController.CaptureProfile {
        val (rawWidth, rawHeight) = readDisplaySize()
        val (width, height) = clampResolutionToTarget(
            width = rawWidth,
            height = rawHeight,
            targetWidth = SCREEN_SHARE_MAX_WIDTH,
            targetHeight = SCREEN_SHARE_MAX_HEIGHT
        )
        val displayFps = readDisplayFps()
        val fps = displayFps.coerceIn(SCREEN_SHARE_MIN_FPS, SCREEN_SHARE_TARGET_FPS)
        return CameraCaptureController.CaptureProfile(
            width = width,
            height = height,
            fps = cameraController.normalizeFps(fps)
        )
    }

    private fun readDisplaySize(): Pair<Int, Int> {
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        if (windowManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return Pair(bounds.width(), bounds.height())
                }
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return Pair(metrics.widthPixels, metrics.heightPixels)
                }
            }
        }
        return Pair(SCREEN_SHARE_MAX_WIDTH, SCREEN_SHARE_MAX_HEIGHT)
    }

    private fun readDisplayFps(): Int {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        @Suppress("DEPRECATION")
        val refreshRate = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate
        if (refreshRate != null && refreshRate > 0f) {
            return refreshRate.roundToInt()
        }
        return SCREEN_SHARE_TARGET_FPS
    }

    private fun clampResolutionToTarget(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Pair<Int, Int> {
        val safeWidth = width.coerceAtLeast(2)
        val safeHeight = height.coerceAtLeast(2)
        val scale = min(
            1.0,
            min(
                targetWidth.toDouble() / safeWidth.toDouble(),
                targetHeight.toDouble() / safeHeight.toDouble()
            )
        )
        val scaledWidth = cameraController.normalizeDimension((safeWidth * scale).roundToInt())
        val scaledHeight = cameraController.normalizeDimension((safeHeight * scale).roundToInt())
        return Pair(scaledWidth.coerceAtLeast(2), scaledHeight.coerceAtLeast(2))
    }

    companion object {
        private const val TAG = "ScreenShareController"

        const val SCREEN_SHARE_MAX_WIDTH = 1920
        const val SCREEN_SHARE_MAX_HEIGHT = 1080
        const val SCREEN_SHARE_TARGET_FPS = 30
        const val SCREEN_SHARE_MIN_FPS = 15
    }
}
