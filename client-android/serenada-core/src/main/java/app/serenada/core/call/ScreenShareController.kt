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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt
import org.webrtc.EglBase
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

internal class ScreenShareController(
    private val appContext: Context,
    private val eglBase: EglBase,
    private val cameraController: CameraCaptureController,
    private val capturerObserverProvider: () -> org.webrtc.CapturerObserver?,
    private val videoSourceProvider: () -> org.webrtc.VideoSource?,
    private val onScreenShareStopped: () -> Unit,
    private val onStateChanged: (Boolean) -> Unit,
    // When true, screen share rides a SEPARATE content source/capturer and the
    // camera capture is left untouched. [contentCapturerObserverProvider]
    // returns the capturer observer of the dedicated content source.
    private val independentContentEnabled: Boolean = false,
    private val contentCapturerObserverProvider: () -> org.webrtc.CapturerObserver? = { null },
    private val logger: SerenadaLogger? = null,
) {
    var isScreenSharing: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    // Independent-mode screen capturer + its texture helper, owned here so the
    // shared stop path releases them exactly once.
    private var contentCapturer: VideoCapturer? = null
    private var contentTextureHelper: SurfaceTextureHelper? = null
    // Idempotency latch for the shared stop path (API / external onStop). Ensures
    // capture stops once, MediaProjection.stop() runs once, and the capturer is
    // released once per logical stop, regardless of how stop is entered.
    private val stopInFlight = AtomicBoolean(false)

    fun startScreenShare(intent: Intent): Boolean {
        if (isScreenSharing) return true
        return if (independentContentEnabled) {
            startScreenShareIndependent(intent)
        } else {
            startScreenShareLegacy(intent)
        }
    }

    /**
     * Shared capturer bring-up for both screen-share paths: constructs the
     * [ScreenCapturerAndroid] (wiring the external-stop [MediaProjection.Callback]
     * into the shared idempotent stop path), creates the [SurfaceTextureHelper],
     * selects the capture profile, then initializes and starts capture. On any
     * failure it disposes both the capturer and texture helper and returns null.
     * Callers own the path-specific observer choice plus the success side effects.
     */
    private fun createAndStartScreenCapturer(
        intent: Intent,
        observer: org.webrtc.CapturerObserver,
        threadName: String,
        profileLogLabel: String,
    ): Pair<VideoCapturer, SurfaceTextureHelper>? {
        val capturer = ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
            override fun onStop() {
                // External stop (OS revokes / user stops via the system control).
                // Route into the SHARED idempotent stop path, then notify so the
                // session can broadcast content_state once (pitfall #9).
                mainHandler.post {
                    if (isScreenSharing) {
                        stopScreenShare()
                        onScreenShareStopped()
                    }
                }
            }
        })
        val textureHelper = SurfaceTextureHelper.create(threadName, eglBase.eglBaseContext)
        val captureProfile = selectScreenShareCaptureProfile()
        return try {
            capturer.initialize(textureHelper, appContext, observer)
            capturer.startCapture(captureProfile.width, captureProfile.height, captureProfile.fps)
            logger?.log(
                SerenadaLogLevel.DEBUG,
                "ScreenShare",
                "$profileLogLabel: ${captureProfile.width}x${captureProfile.height}@${captureProfile.fps}fps"
            )
            Pair(capturer, textureHelper)
        } catch (e: Exception) {
            logger?.log(SerenadaLogLevel.WARNING, "ScreenShare", "Failed to start screen sharing: ${e.message}")
            runCatching { capturer.dispose() }
            runCatching { textureHelper.dispose() }
            null
        }
    }

    // --- Legacy single-video screen share (flag OFF): byte-identical to today ---

    private fun startScreenShareLegacy(intent: Intent): Boolean {
        val observer = capturerObserverProvider() ?: return false
        val previousSource = cameraController.currentCameraSource
        cameraController.resetScreenShareCameraState()
        cameraController.disposeVideoCapturer()
        val started = createAndStartScreenCapturer(
            intent,
            observer,
            "ScreenCaptureThread",
            "Screen share capture profile",
        )
        if (started == null) {
            val videoSource = videoSourceProvider()
            if (cameraController.canCaptureVideo &&
                !cameraController.restartVideoCapturer(previousSource, videoSource)) {
                cameraController.restartVideoCapturer(CameraCaptureController.LocalCameraSource.SELFIE, videoSource)
            }
            return false
        }
        val (capturer, textureHelper) = started
        cameraController.setScreenShareVideoCapturer(capturer, textureHelper)
        cameraController.cameraSourceBeforeScreenShare = previousSource
        isScreenSharing = true
        cameraController.isScreenSharing = true
        onStateChanged(true)
        cameraController.applyTorchForCurrentMode()
        return true
    }

    // --- Independent content screen share (flag ON): camera untouched ---

    private fun startScreenShareIndependent(intent: Intent): Boolean {
        val observer = contentCapturerObserverProvider() ?: return false
        val started = createAndStartScreenCapturer(
            intent,
            observer,
            "ContentCaptureThread",
            "Independent content capture profile",
        ) ?: return false
        val (capturer, textureHelper) = started
        contentCapturer = capturer
        contentTextureHelper = textureHelper
        stopInFlight.set(false)
        isScreenSharing = true
        // Camera capture is intentionally left running.
        onStateChanged(true)
        return true
    }

    /**
     * Shared idempotent stop path (API, external MediaProjection onStop). The
     * latch makes a second entry (e.g. the onStop re-entry after a programmatic
     * stop) a no-op: capture stops once, the capturer/MediaProjection is released
     * once. Returns true once stopped (or already stopped).
     */
    fun stopScreenShare(): Boolean {
        if (!isScreenSharing) return true
        if (independentContentEnabled) {
            if (!stopInFlight.compareAndSet(false, true)) return true
            isScreenSharing = false
            cameraController.isScreenSharing = false
            // Dispose the content capturer (calls MediaProjection.stop() under the
            // hood) and its texture helper exactly once. Camera is left untouched.
            runCatching { contentCapturer?.stopCapture() }
            runCatching { contentCapturer?.dispose() }
            contentCapturer = null
            runCatching { contentTextureHelper?.dispose() }
            contentTextureHelper = null
            onStateChanged(false)
            stopInFlight.set(false)
            return true
        }
        return stopScreenShareLegacy()
    }

    private fun stopScreenShareLegacy(): Boolean {
        val sourceToRestore = cameraController.cameraSourceBeforeScreenShare ?: cameraController.currentCameraSource
        isScreenSharing = false
        cameraController.isScreenSharing = false
        cameraController.cameraSourceBeforeScreenShare = null
        cameraController.disposeVideoCapturer()
        if (!cameraController.canCaptureVideo) return true
        val videoSource = videoSourceProvider()
        if (!cameraController.restartVideoCapturer(sourceToRestore, videoSource) &&
            !cameraController.restartVideoCapturer(CameraCaptureController.LocalCameraSource.SELFIE, videoSource)) {
            logger?.log(SerenadaLogLevel.WARNING, "ScreenShare", "Failed to restore camera after screen sharing stop")
        }
        return true
    }

    fun reset() {
        if (independentContentEnabled) {
            isScreenSharing = false
            cameraController.isScreenSharing = false
            runCatching { contentCapturer?.stopCapture() }
            runCatching { contentCapturer?.dispose() }
            contentCapturer = null
            runCatching { contentTextureHelper?.dispose() }
            contentTextureHelper = null
            stopInFlight.set(false)
            return
        }
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

        // Conservative independent-content sender profile (design "Content
        // encoding profile"): legibility of mostly-static content over motion.
        const val CONTENT_TARGET_FPS = 5
        const val CONTENT_MAX_BITRATE_BPS = 1_500_000
        const val CONTENT_MIN_BITRATE_BPS = 300_000
    }
}
