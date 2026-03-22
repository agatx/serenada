package app.serenada.core.call

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Range
import app.serenada.core.FeatureDegradation
import app.serenada.core.FeatureDegradationState
import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.webrtc.Camera2Enumerator
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource

internal class CameraCaptureController(
    private val appContext: Context,
    private val eglBase: EglBase,
    private val cameraManager: CameraManager?,
    isHdVideoExperimentalEnabled: Boolean,
    private val videoSourceProvider: () -> VideoSource?,
    private val onCameraFacingChanged: (Boolean) -> Unit,
    private val onCameraModeChanged: (LocalCameraMode) -> Unit,
    private val onFlashlightStateChanged: (Boolean, Boolean) -> Unit,
    private val onFeatureDegradation: (FeatureDegradationState) -> Unit,
    private val onVideoSenderParametersChanged: () -> Unit,
    private val logger: SerenadaLogger? = null,
) {
    internal enum class LocalCameraSource {
        SELFIE,
        WORLD,
        COMPOSITE
    }

    internal data class CapturerSelection(
        val capturer: VideoCapturer,
        val isFrontFacing: Boolean,
        val captureProfile: CaptureProfile,
        val torchCameraId: String?,
        val zoomCameraId: String?
    )

    internal data class CaptureProfile(
        val width: Int,
        val height: Int,
        val fps: Int
    )

    internal data class CapturePolicy(
        val targetWidth: Int,
        val targetHeight: Int,
        val targetFps: Int,
        val minFps: Int
    )

    private data class ZoomCapabilities(
        val minRatio: Float,
        val maxRatio: Float,
        val sensorRect: Rect?
    )

    var currentCameraSource = LocalCameraSource.SELFIE
        private set
    var cameraSourceBeforeScreenShare: LocalCameraSource? = null
    var videoCapturer: VideoCapturer? = null
        private set
    var isScreenSharing = false

    var isHdVideoExperimentalEnabled: Boolean = isHdVideoExperimentalEnabled
        private set

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val fallbackTorchCameraId: String? = findTorchCameraId()
    private var activeTorchCameraId: String? = null
    private var activeZoomCameraId: String? = null
    private var activeZoomCapabilities: ZoomCapabilities? = null
    private var isTorchPreferenceEnabled = false
    private var isTorchEnabled = false
    private var torchSyncRequired = false
    private var torchRetryRunnable: Runnable? = null
    private var torchRetryAttempts = 0
    private var desiredCameraZoomRatio = 1f
    private var appliedCameraZoomRatio = 1f
    private var compositeSupportCache: Pair<Pair<String, String>, Boolean>? = null
    private var compositeDisabledAfterFailure = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Public API ──────────────────────────────────────────────────────

    fun restartVideoCapturer(source: LocalCameraSource, videoSource: VideoSource?): Boolean {
        val observer = videoSource?.capturerObserver ?: return false
        disposeVideoCapturer()
        val selection = createVideoCapturer(source) ?: return false
        val textureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        try {
            selection.capturer.initialize(textureHelper, appContext, observer)
            selection.capturer.startCapture(
                selection.captureProfile.width,
                selection.captureProfile.height,
                selection.captureProfile.fps
            )
        } catch (e: Exception) {
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Failed to start capture for $source: ${e.message}")
            runCatching { selection.capturer.dispose() }
            runCatching { textureHelper.dispose() }
            return false
        }
        videoCapturer = selection.capturer
        surfaceTextureHelper = textureHelper
        currentCameraSource = source
        activeTorchCameraId = selection.torchCameraId
        activeZoomCameraId = selection.zoomCameraId
        activeZoomCapabilities = queryZoomCapabilities(activeZoomCameraId)
        desiredCameraZoomRatio = clampZoomRatioForCapabilities(desiredCameraZoomRatio, activeZoomCapabilities)
        appliedCameraZoomRatio = 1f
        isTorchEnabled = false
        torchSyncRequired = true
        onCameraFacingChanged(selection.isFrontFacing)
        applyTorchForCurrentMode()
        if (isZoomAvailableForCurrentMode()) {
            applyZoomForCurrentMode()
        }
        onVideoSenderParametersChanged()
        logger?.log(
            SerenadaLogLevel.DEBUG,
            "Camera",
            "Camera source active: $source (${selection.captureProfile.width}x${selection.captureProfile.height}@${selection.captureProfile.fps}fps)"
        )
        return true
    }

    fun disposeVideoCapturer() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Failed to stop capture: ${e.message}")
        }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
    }

    fun flipCamera(videoSource: VideoSource?) {
        if (isScreenSharing) return
        if (videoSource == null) return
        val compositeAvailable = canUseCompositeSource()
        val targetMode = nextFlipCameraMode(
            current = activeCameraMode(),
            compositeAvailable = compositeAvailable
        )
        val target = cameraSourceFromMode(targetMode)
        if (!compositeAvailable && targetMode == LocalCameraMode.SELFIE && currentCameraSource == LocalCameraSource.WORLD) {
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Transitioning from WORLD to SELFIE (COMPOSITE unavailable)")
        }
        if (restartVideoCapturer(target, videoSource)) {
            return
        }
        logger?.log(SerenadaLogLevel.WARNING, "Camera", "Failed to switch camera source to $target")
        val fallback = if (targetMode == LocalCameraMode.COMPOSITE) {
            compositeDisabledAfterFailure = true
            reportCompositeCameraUnavailable("Composite camera switch failed")
            LocalCameraSource.SELFIE
        } else {
            currentCameraSource
        }
        if (fallback != target && restartVideoCapturer(fallback, videoSource)) {
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Camera source fallback applied: $fallback")
        }
    }

    fun adjustWorldCameraZoom(scaleFactor: Float): Boolean {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return false
        if (!isZoomAvailableForCurrentMode()) return false
        val capabilities = activeZoomCapabilities ?: return false
        val targetRatio =
            (desiredCameraZoomRatio * scaleFactor).coerceIn(
                capabilities.minRatio,
                capabilities.maxRatio
            )
        if (abs(targetRatio - desiredCameraZoomRatio) < ZOOM_RATIO_DELTA_EPSILON) {
            return false
        }
        desiredCameraZoomRatio = targetRatio
        return applyZoomForCurrentMode()
    }

    fun toggleFlashlight(): Boolean {
        if (!isTorchAvailableForCurrentMode()) return false
        isTorchPreferenceEnabled = !isTorchPreferenceEnabled
        logger?.log(
            SerenadaLogLevel.DEBUG,
            "Camera",
            "Flash toggle requested: preference=$isTorchPreferenceEnabled mode=${activeVideoModeLabel()} torchCamera=$activeTorchCameraId"
        )
        if (applyTorchForCurrentMode()) {
            return true
        }
        if (isTorchPreferenceEnabled) {
            scheduleTorchRetry()
        }
        isTorchPreferenceEnabled = isTorchEnabled
        notifyCameraModeAndFlash()
        return false
    }

    fun setHdVideoExperimentalEnabled(enabled: Boolean, videoSource: VideoSource?, localVideoTrack: Any?) {
        if (isHdVideoExperimentalEnabled == enabled) return
        isHdVideoExperimentalEnabled = enabled
        if (!isScreenSharing && localVideoTrack != null && videoSource != null) {
            if (!restartVideoCapturer(currentCameraSource, videoSource)) {
                logger?.log(SerenadaLogLevel.WARNING, "Camera", "Failed to apply HD video setting by restarting capturer")
            }
        }
        onVideoSenderParametersChanged()
    }

    fun resetCameraState() {
        cancelTorchRetry()
        activeTorchCameraId = null
        activeZoomCameraId = null
        activeZoomCapabilities = null
        isTorchPreferenceEnabled = false
        torchSyncRequired = false
        setTorchEnabled(false, notify = false)
        desiredCameraZoomRatio = 1f
        appliedCameraZoomRatio = 1f
    }

    fun resetScreenShareCameraState() {
        cancelTorchRetry()
        activeTorchCameraId = null
        activeZoomCameraId = null
        activeZoomCapabilities = null
        torchSyncRequired = false
        setTorchEnabled(false, notify = false)
        appliedCameraZoomRatio = 1f
    }

    fun setScreenShareVideoCapturer(capturer: VideoCapturer, textureHelper: SurfaceTextureHelper) {
        videoCapturer = capturer
        surfaceTextureHelper = textureHelper
    }

    fun activeCameraMode(): LocalCameraMode {
        if (isScreenSharing) return LocalCameraMode.SCREEN_SHARE
        return cameraModeFromSource(currentCameraSource)
    }

    fun activeVideoModeLabel(): String {
        if (isScreenSharing) return "screen_share"
        return when (currentCameraSource) {
            LocalCameraSource.SELFIE -> "selfie"
            LocalCameraSource.WORLD -> "world"
            LocalCameraSource.COMPOSITE -> "composite"
        }
    }

    fun applyTorchForCurrentMode(): Boolean {
        cancelTorchRetry()
        val shouldEnable = isTorchAvailableForCurrentMode() && isTorchPreferenceEnabled
        val allowGlobalFallback = !shouldEnable || !torchSyncRequired
        val applied = setTorchEnabled(
            enabled = shouldEnable,
            notify = false,
            allowGlobalFallback = allowGlobalFallback
        )
        if (!applied && shouldEnable && torchSyncRequired) {
            scheduleTorchRetry()
        }
        notifyCameraModeAndFlash()
        return applied
    }

    fun resetCameraSourceToSelfie() {
        currentCameraSource = LocalCameraSource.SELFIE
    }

    // ── Camera capture profile selection ────────────────────────────────

    internal fun cameraCapturePolicyFor(source: LocalCameraSource): CapturePolicy {
        if (!isHdVideoExperimentalEnabled) {
            return CapturePolicy(
                targetWidth = LEGACY_CAMERA_WIDTH,
                targetHeight = LEGACY_CAMERA_HEIGHT,
                targetFps = LEGACY_CAMERA_FPS,
                minFps = LEGACY_CAMERA_MIN_FPS
            )
        }
        return when (source) {
            LocalCameraSource.COMPOSITE -> {
                CapturePolicy(
                    targetWidth = COMPOSITE_TARGET_WIDTH,
                    targetHeight = COMPOSITE_TARGET_HEIGHT,
                    targetFps = COMPOSITE_TARGET_FPS,
                    minFps = COMPOSITE_MIN_FPS
                )
            }

            else -> {
                CapturePolicy(
                    targetWidth = CAMERA_TARGET_WIDTH,
                    targetHeight = CAMERA_TARGET_HEIGHT,
                    targetFps = CAMERA_TARGET_FPS,
                    minFps = CAMERA_MIN_FPS
                )
            }
        }
    }

    internal fun selectCameraCaptureProfile(
        enumerator: Camera2Enumerator,
        deviceNames: List<String>,
        policy: CapturePolicy
    ): CaptureProfile {
        if (deviceNames.isEmpty()) {
            return defaultCaptureProfile(policy)
        }
        val formatsByDevice = deviceNames.mapNotNull { deviceName ->
            val bestFpsByResolution = (enumerator.getSupportedFormats(deviceName) ?: emptyList())
                .groupBy { Pair(it.width, it.height) }
                .mapValues { (_, formats) ->
                    formats.maxOfOrNull { format -> normalizeFps(format.framerate.max) } ?: policy.targetFps
                }
            if (bestFpsByResolution.isEmpty()) null else bestFpsByResolution
        }
        if (formatsByDevice.isEmpty()) {
            return defaultCaptureProfile(policy)
        }

        var commonResolutions: Set<Pair<Int, Int>> = formatsByDevice.first().keys
        formatsByDevice.drop(1).forEach { map ->
            commonResolutions = commonResolutions.intersect(map.keys)
        }
        if (commonResolutions.isNotEmpty()) {
            val commonFpsByResolution = commonResolutions.associateWith { size ->
                formatsByDevice.minOf { formatMap ->
                    formatMap[size] ?: policy.targetFps
                }
            }
            chooseProfileForPolicy(commonFpsByResolution, policy)?.let { return it }
        }

        val perDeviceProfiles = formatsByDevice.mapNotNull { map ->
            chooseProfileForPolicy(map, policy)
        }
        if (perDeviceProfiles.isNotEmpty()) {
            return CaptureProfile(
                width = perDeviceProfiles.minOf { it.width },
                height = perDeviceProfiles.minOf { it.height },
                fps = perDeviceProfiles.minOf { it.fps }
            )
        }

        return defaultCaptureProfile(policy)
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private fun createVideoCapturer(source: LocalCameraSource): CapturerSelection? {
        val enumerator = Camera2Enumerator(appContext)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val back = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        return when (source) {
            LocalCameraSource.SELFIE -> {
                if (front != null) {
                    enumerator.createCapturer(front, null)?.let {
                        CapturerSelection(
                            capturer = it,
                            isFrontFacing = true,
                            captureProfile = selectCameraCaptureProfile(
                                enumerator = enumerator,
                                deviceNames = listOf(front),
                                policy = cameraCapturePolicyFor(LocalCameraSource.SELFIE)
                            ),
                            torchCameraId = null,
                            zoomCameraId = front
                        )
                    }
                } else if (back != null) {
                    enumerator.createCapturer(back, null)?.let {
                        CapturerSelection(
                            capturer = it,
                            isFrontFacing = false,
                            captureProfile = selectCameraCaptureProfile(
                                enumerator = enumerator,
                                deviceNames = listOf(back),
                                policy = cameraCapturePolicyFor(LocalCameraSource.SELFIE)
                            ),
                            torchCameraId = back.takeIf { hasFlashUnit(it) },
                            zoomCameraId = back
                        )
                    }
                } else {
                    null
                }
            }

            LocalCameraSource.WORLD -> {
                if (back == null) {
                    null
                } else {
                    enumerator.createCapturer(back, null)?.let {
                        CapturerSelection(
                            capturer = it,
                            isFrontFacing = false,
                            captureProfile = selectCameraCaptureProfile(
                                enumerator = enumerator,
                                deviceNames = listOf(back),
                                policy = cameraCapturePolicyFor(LocalCameraSource.WORLD)
                            ),
                            torchCameraId = back.takeIf { hasFlashUnit(it) },
                            zoomCameraId = back
                        )
                    }
                }
            }

            LocalCameraSource.COMPOSITE -> {
                if (front == null || back == null) {
                    null
                } else if (!canUseCompositeSource(frontDevice = front, backDevice = back)) {
                    null
                } else {
                    val mainCapturer = enumerator.createCapturer(back, null)
                    val overlayCapturer = enumerator.createCapturer(front, null)
                    if (mainCapturer == null || overlayCapturer == null) {
                        mainCapturer?.dispose()
                        overlayCapturer?.dispose()
                        null
                    } else {
                        val profile = selectCameraCaptureProfile(
                            enumerator = enumerator,
                            deviceNames = listOf(back, front),
                            policy = cameraCapturePolicyFor(LocalCameraSource.COMPOSITE)
                        )
                        CapturerSelection(
                            capturer = CompositeCameraCapturer(
                                context = appContext,
                                eglContext = eglBase.eglBaseContext,
                                mainCapturer = mainCapturer,
                                overlayCapturer = overlayCapturer,
                                onStartFailure = { onCompositeStartFailure() },
                                logger = logger,
                            ),
                            isFrontFacing = false,
                            captureProfile = profile,
                            torchCameraId = back.takeIf { hasFlashUnit(it) },
                            zoomCameraId = back
                        )
                    }
                }
            }
        }
    }

    private fun chooseProfileForPolicy(
        fpsByResolution: Map<Pair<Int, Int>, Int>,
        policy: CapturePolicy
    ): CaptureProfile? {
        val profiles = fpsByResolution.map { (size, fps) ->
            CaptureProfile(
                width = normalizeDimension(size.first),
                height = normalizeDimension(size.second),
                fps = normalizeFps(fps)
            )
        }
        if (profiles.isEmpty()) return null
        val inTargetBounds = profiles.filter { profileFitsPolicyBounds(it, policy) }
        val candidatePool = if (inTargetBounds.isNotEmpty()) inTargetBounds else profiles
        val targetArea = policy.targetWidth * policy.targetHeight
        val targetFps = policy.targetFps
        val minFps = policy.minFps
        val chosen = candidatePool.minWithOrNull(
            compareBy<CaptureProfile>(
                { if (it.fps >= minFps) 0 else 1 },
                { if (it.width * it.height <= targetArea) 0 else 1 },
                { abs((it.width * it.height) - targetArea) },
                { abs(it.fps - targetFps) },
                { -(it.width * it.height) },
                { -it.fps }
            )
        ) ?: return null
        val selectedFps = if (chosen.fps >= minFps) {
            min(chosen.fps, targetFps)
        } else {
            chosen.fps
        }
        return chosen.copy(fps = normalizeFps(selectedFps))
    }

    private fun profileFitsPolicyBounds(profile: CaptureProfile, policy: CapturePolicy): Boolean {
        val profileLong = max(profile.width, profile.height)
        val profileShort = min(profile.width, profile.height)
        val targetLong = max(policy.targetWidth, policy.targetHeight)
        val targetShort = min(policy.targetWidth, policy.targetHeight)
        return profileLong <= targetLong && profileShort <= targetShort
    }

    internal fun normalizeDimension(value: Int): Int {
        val positive = value.coerceAtLeast(2)
        return if (positive % 2 == 0) positive else positive - 1
    }

    internal fun normalizeFps(value: Int): Int {
        val normalized = if (value > 1000) value / 1000 else value
        return normalized.coerceIn(1, MAX_CAPTURE_FPS)
    }

    private fun defaultCaptureProfile(policy: CapturePolicy): CaptureProfile {
        return CaptureProfile(
            width = normalizeDimension(policy.targetWidth),
            height = normalizeDimension(policy.targetHeight),
            fps = normalizeFps(policy.targetFps)
        )
    }

    private fun onCompositeStartFailure() {
        mainHandler.post {
            if (currentCameraSource != LocalCameraSource.COMPOSITE) return@post
            if (videoCapturer !is CompositeCameraCapturer) return@post
            compositeDisabledAfterFailure = true
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Composite source failed; disabling composite and falling back to selfie")
            reportCompositeCameraUnavailable("Composite source failed to start")
            if (restartVideoCapturer(LocalCameraSource.SELFIE, videoSourceProvider())) {
                logger?.log(SerenadaLogLevel.WARNING, "Camera", "Camera source fallback applied: ${LocalCameraSource.SELFIE}")
            }
        }
    }

    private fun canUseCompositeSource(): Boolean {
        val enumerator = Camera2Enumerator(appContext)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: return false
        val back = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) } ?: return false
        return canUseCompositeSource(frontDevice = front, backDevice = back)
    }

    private fun canUseCompositeSource(frontDevice: String, backDevice: String): Boolean {
        if (compositeDisabledAfterFailure) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val manager = appContext.getSystemService(CameraManager::class.java) ?: return false
            val capable = setOf(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
            )
            val frontLevel = runCatching {
                manager.getCameraCharacteristics(frontDevice)
                    .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            }.getOrNull()
            val backLevel = runCatching {
                manager.getCameraCharacteristics(backDevice)
                    .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            }.getOrNull()
            if (frontLevel !in capable || backLevel !in capable) {
                logger?.log(SerenadaLogLevel.DEBUG, "Camera",
                    "Composite source skipped on API <30: hardware level insufficient (front=$frontLevel back=$backLevel)")
                return false
            }
            return true
        }
        val cacheKey = Pair(frontDevice, backDevice)
        compositeSupportCache?.let { (savedKey, savedValue) ->
            if (savedKey == cacheKey) {
                return savedValue
            }
        }
        val manager = appContext.getSystemService(CameraManager::class.java) ?: return false
        val supported = runCatching {
            manager.concurrentCameraIds.any { ids ->
                ids.contains(frontDevice) && ids.contains(backDevice)
            }
        }.getOrDefault(false)
        compositeSupportCache = Pair(cacheKey, supported)
        if (!supported) {
            logger?.log(
                SerenadaLogLevel.WARNING,
                "Camera",
                "Composite source unsupported by concurrent camera constraints. front=$frontDevice back=$backDevice"
            )
        }
        return supported
    }

    private fun findTorchCameraId(): String? {
        val manager = cameraManager ?: return null
        return runCatching {
            manager.cameraIdList.firstOrNull { cameraId ->
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                hasFlash && lensFacing == CameraCharacteristics.LENS_FACING_BACK
            }
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Failed to query torch camera id: ${error.message}")
        }.getOrNull()
    }

    private fun hasFlashUnit(cameraId: String): Boolean {
        val manager = cameraManager ?: return false
        return runCatching {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Failed to query flash availability for camera=$cameraId: ${error.message}")
        }.getOrDefault(false)
    }

    private fun supportsZoomForSource(source: LocalCameraSource): Boolean {
        return source == LocalCameraSource.WORLD || source == LocalCameraSource.COMPOSITE
    }

    private fun isZoomAvailableForCurrentMode(): Boolean {
        if (isScreenSharing) return false
        if (!supportsZoomForSource(currentCameraSource)) return false
        val capabilities = activeZoomCapabilities ?: return false
        return capabilities.maxRatio > capabilities.minRatio + ZOOM_RATIO_DELTA_EPSILON
    }

    private fun clampZoomRatioForCapabilities(
        ratio: Float,
        capabilities: ZoomCapabilities?
    ): Float {
        val caps = capabilities ?: return 1f
        return ratio.coerceIn(caps.minRatio, caps.maxRatio)
    }

    private fun requestedZoomRatioForCurrentMode(): Float {
        if (!isZoomAvailableForCurrentMode()) return 1f
        return clampZoomRatioForCapabilities(desiredCameraZoomRatio, activeZoomCapabilities)
    }

    private fun queryZoomCapabilities(cameraId: String?): ZoomCapabilities? {
        val manager = cameraManager ?: return null
        val activeCameraId = cameraId ?: return null
        return runCatching {
            val characteristics = manager.getCameraCharacteristics(activeCameraId)
            val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            var minRatio = 1f
            var maxRatio =
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?.coerceAtLeast(1f)
                    ?: 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    minRatio = max(1f, zoomRange.lower)
                    maxRatio = max(maxRatio, zoomRange.upper)
                }
            }
            if (maxRatio <= minRatio + ZOOM_RATIO_DELTA_EPSILON) {
                null
            } else {
                ZoomCapabilities(
                    minRatio = minRatio,
                    maxRatio = maxRatio,
                    sensorRect = sensorRect
                )
            }
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Failed to query zoom capabilities for camera=$activeCameraId: ${error.message}")
        }.getOrNull()
    }

    private fun supportsTorchForSource(source: LocalCameraSource): Boolean {
        val supportsMode = source == LocalCameraSource.WORLD || source == LocalCameraSource.COMPOSITE
        if (!supportsMode) return false
        return activeTorchCameraId != null || fallbackTorchCameraId != null
    }

    private fun isTorchAvailableForCurrentMode(): Boolean {
        if (isScreenSharing) return false
        return supportsTorchForSource(currentCameraSource)
    }

    private fun applyZoomForCurrentMode(): Boolean {
        if (!isZoomAvailableForCurrentMode()) {
            appliedCameraZoomRatio = 1f
            return false
        }
        val session = resolveActiveCamera2Session() ?: return false
        val cameraHandler = readFieldValue(session, "cameraThreadHandler") as? Handler ?: return false
        val targetZoomRatio = requestedZoomRatioForCurrentMode()
        if (abs(targetZoomRatio - appliedCameraZoomRatio) < ZOOM_RATIO_DELTA_EPSILON) {
            return true
        }
        cameraHandler.post {
            runCatching {
                applyCameraControlsViaCaptureRequestInternal(
                    session = session,
                    cameraHandler = cameraHandler,
                    torchEnabled = isTorchEnabled,
                    zoomRatio = targetZoomRatio
                )
            }.onSuccess { applied ->
                if (applied) {
                    appliedCameraZoomRatio = targetZoomRatio
                }
            }.onFailure { error ->
                logger?.log(SerenadaLogLevel.WARNING, "Camera", "Failed to apply zoom via capture request: ${error.message}")
            }
        }
        return true
    }

    private fun setTorchEnabled(
        enabled: Boolean,
        notify: Boolean = true,
        allowGlobalFallback: Boolean = true
    ): Boolean {
        if (isTorchEnabled == enabled && !torchSyncRequired) {
            if (notify) {
                notifyCameraModeAndFlash()
            }
            return true
        }
        if (applyTorchViaCaptureRequest(enabled, requestedZoomRatioForCurrentMode())) {
            isTorchEnabled = enabled
            torchSyncRequired = false
            logger?.log(
                SerenadaLogLevel.DEBUG,
                "Camera",
                "Torch mode set via capture request: enabled=$enabled mode=${activeVideoModeLabel()} camera=$activeTorchCameraId"
            )
            if (notify) {
                notifyCameraModeAndFlash()
            }
            return true
        }
        if (!allowGlobalFallback) {
            if (!enabled) {
                isTorchEnabled = false
                torchSyncRequired = false
                if (notify) {
                    notifyCameraModeAndFlash()
                }
            }
            return false
        }
        val manager = cameraManager
        val cameraId = activeTorchCameraId ?: fallbackTorchCameraId
        if (manager == null || cameraId == null) {
            if (!enabled) {
                isTorchEnabled = false
                torchSyncRequired = false
                if (notify) {
                    notifyCameraModeAndFlash()
                }
                return true
            }
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Torch unavailable. managerPresent=${manager != null} cameraId=$cameraId")
            return false
        }
        return try {
            manager.setTorchMode(cameraId, enabled)
            isTorchEnabled = enabled
            torchSyncRequired = false
            logger?.log(SerenadaLogLevel.DEBUG, "Camera", "Torch mode set: enabled=$enabled cameraId=$cameraId")
            if (notify) {
                notifyCameraModeAndFlash()
            }
            true
        } catch (error: CameraAccessException) {
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Failed to set torch mode: ${error.message}")
            if (!enabled) {
                isTorchEnabled = false
                torchSyncRequired = false
                if (notify) {
                    notifyCameraModeAndFlash()
                }
            }
            false
        } catch (error: SecurityException) {
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Torch permission denied: ${error.message}")
            if (!enabled) {
                isTorchEnabled = false
                torchSyncRequired = false
                if (notify) {
                    notifyCameraModeAndFlash()
                }
            }
            false
        } catch (error: IllegalArgumentException) {
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Torch camera id invalid: ${error.message}")
            if (!enabled) {
                isTorchEnabled = false
                torchSyncRequired = false
                if (notify) {
                    notifyCameraModeAndFlash()
                }
            }
            false
        }
    }

    private fun scheduleTorchRetry() {
        if (!torchSyncRequired) return
        if (!isTorchPreferenceEnabled) return
        if (!isTorchAvailableForCurrentMode()) return
        if (torchRetryRunnable != null) return
        torchRetryAttempts = 0
        val runnable = object : Runnable {
            override fun run() {
                torchRetryRunnable = null
                if (!torchSyncRequired || !isTorchPreferenceEnabled || !isTorchAvailableForCurrentMode()) {
                    notifyCameraModeAndFlash()
                    return
                }
                val applied = setTorchEnabled(
                    enabled = true,
                    notify = false,
                    allowGlobalFallback = false
                )
                notifyCameraModeAndFlash()
                if (applied) {
                    return
                }
                torchRetryAttempts += 1
                if (torchRetryAttempts >= MAX_TORCH_RETRY_ATTEMPTS) {
                    return
                }
                torchRetryRunnable = this
                mainHandler.postDelayed(this, TORCH_RETRY_DELAY_MS)
            }
        }
        torchRetryRunnable = runnable
        mainHandler.postDelayed(runnable, TORCH_RETRY_DELAY_MS)
    }

    private fun cancelTorchRetry() {
        torchRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        torchRetryRunnable = null
        torchRetryAttempts = 0
    }

    private fun applyTorchViaCaptureRequest(enabled: Boolean, zoomRatio: Float): Boolean {
        val session = resolveActiveCamera2Session() ?: return false
        val cameraHandler = readFieldValue(session, "cameraThreadHandler") as? Handler ?: return false
        val latch = CountDownLatch(1)
        var applied = false
        cameraHandler.post {
            applied = runCatching {
                applyCameraControlsViaCaptureRequestInternal(
                    session = session,
                    cameraHandler = cameraHandler,
                    torchEnabled = enabled,
                    zoomRatio = zoomRatio
                )
            }
                .onFailure { error ->
                    logger?.log(SerenadaLogLevel.WARNING, "Camera", "Failed to apply torch via capture request: ${error.message}")
                }
                .getOrDefault(false)
            latch.countDown()
        }
        if (!latch.await(750, TimeUnit.MILLISECONDS)) {
            logger?.log(SerenadaLogLevel.WARNING, "Camera", "Timed out waiting for torch capture request")
            return false
        }
        if (applied && isZoomAvailableForCurrentMode()) {
            appliedCameraZoomRatio = zoomRatio
        }
        return applied
    }

    private fun applyCameraControlsViaCaptureRequestInternal(
        session: Any,
        cameraHandler: Handler,
        torchEnabled: Boolean,
        zoomRatio: Float
    ): Boolean {
        val captureSession = readFieldValue(session, "captureSession") as? CameraCaptureSession ?: return false
        val cameraDevice = readFieldValue(session, "cameraDevice") as? CameraDevice ?: return false
        val surface = readFieldValue(session, "surface") as? android.view.Surface ?: return false
        val captureFormat = readFieldValue(session, "captureFormat") ?: return false
        val framerate = readFieldValue(captureFormat, "framerate") ?: return false
        val minFps = readFieldValue(framerate, "min") as? Int ?: return false
        val maxFps = readFieldValue(framerate, "max") as? Int ?: return false
        val fpsUnitFactor = readFieldValue(session, "fpsUnitFactor") as? Int ?: return false
        val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.set(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(minFps / fpsUnitFactor, maxFps / fpsUnitFactor)
        )
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        runCatching {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        applyZoomRequest(builder, zoomRatio)
        builder.addTarget(surface)
        captureSession.setRepeatingRequest(builder.build(), null, cameraHandler)
        return true
    }

    private fun applyZoomRequest(builder: CaptureRequest.Builder, zoomRatio: Float) {
        if (!isZoomAvailableForCurrentMode()) return
        val capabilities = activeZoomCapabilities ?: return
        val clampedZoom = clampZoomRatioForCapabilities(zoomRatio, capabilities)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appliedViaRatio = runCatching {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedZoom)
                true
            }.onFailure { error ->
                logger?.log(
                    SerenadaLogLevel.WARNING,
                    "Camera",
                    "Failed to apply CONTROL_ZOOM_RATIO; falling back to SCALER_CROP_REGION: ${error.message}"
                )
            }.getOrDefault(false)
            if (appliedViaRatio) {
                return
            }
        }
        val sensorRect = capabilities.sensorRect ?: return
        val cropRegion = cropRegionForZoom(sensorRect, clampedZoom)
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
    }

    private fun cropRegionForZoom(sensorRect: Rect, zoomRatio: Float): Rect {
        val clampedZoom = zoomRatio.coerceAtLeast(1f)
        val centerX = sensorRect.centerX()
        val centerY = sensorRect.centerY()
        val halfWidth = (sensorRect.width() / (2f * clampedZoom)).roundToInt().coerceAtLeast(1)
        val halfHeight = (sensorRect.height() / (2f * clampedZoom)).roundToInt().coerceAtLeast(1)
        return Rect(
            (centerX - halfWidth).coerceAtLeast(sensorRect.left),
            (centerY - halfHeight).coerceAtLeast(sensorRect.top),
            (centerX + halfWidth).coerceAtMost(sensorRect.right),
            (centerY + halfHeight).coerceAtMost(sensorRect.bottom)
        )
    }

    private fun resolveActiveCamera2Session(): Any? {
        val capturer = when (val activeCapturer = videoCapturer) {
            is CompositeCameraCapturer -> activeCapturer.mainCameraCapturerForTorch()
            else -> activeCapturer
        } ?: return null
        val session = readFieldValueFromHierarchy(capturer, "currentSession") ?: return null
        return if (session.javaClass.name == "org.webrtc.Camera2Session") session else null
    }

    private fun readFieldValue(instance: Any, fieldName: String): Any? {
        return runCatching {
            val field = instance.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(instance)
        }.getOrNull()
    }

    private fun readFieldValueFromHierarchy(instance: Any, fieldName: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            val value = try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                field.get(instance)
            } catch (_: Exception) {
                null
            }
            if (value != null) {
                return value
            }
            current = current.superclass
        }
        return null
    }

    private fun cameraModeFromSource(source: LocalCameraSource): LocalCameraMode {
        return when (source) {
            LocalCameraSource.SELFIE -> LocalCameraMode.SELFIE
            LocalCameraSource.WORLD -> LocalCameraMode.WORLD
            LocalCameraSource.COMPOSITE -> LocalCameraMode.COMPOSITE
        }
    }

    private fun cameraSourceFromMode(mode: LocalCameraMode): LocalCameraSource {
        return when (mode) {
            LocalCameraMode.SELFIE -> LocalCameraSource.SELFIE
            LocalCameraMode.WORLD -> LocalCameraSource.WORLD
            LocalCameraMode.COMPOSITE -> LocalCameraSource.COMPOSITE
            LocalCameraMode.SCREEN_SHARE -> LocalCameraSource.SELFIE
        }
    }

    private fun notifyCameraModeAndFlash() {
        val flashAvailable = isTorchAvailableForCurrentMode()
        onCameraModeChanged(activeCameraMode())
        onFlashlightStateChanged(flashAvailable, flashAvailable && isTorchEnabled)
    }

    private fun reportCompositeCameraUnavailable(reason: String) {
        onFeatureDegradation(
            FeatureDegradationState(
                kind = FeatureDegradation.COMPOSITE_CAMERA_UNAVAILABLE,
                reason = reason,
            ),
        )
    }

    internal companion object {
        private const val TAG = "CameraCaptureController"

        const val LEGACY_CAMERA_WIDTH = 640
        const val LEGACY_CAMERA_HEIGHT = 480
        const val LEGACY_CAMERA_FPS = 30
        const val LEGACY_CAMERA_MIN_FPS = 15

        const val CAMERA_TARGET_WIDTH = 1280
        const val CAMERA_TARGET_HEIGHT = 720
        const val CAMERA_TARGET_FPS = 30
        const val CAMERA_MIN_FPS = 20

        const val COMPOSITE_TARGET_WIDTH = 960
        const val COMPOSITE_TARGET_HEIGHT = 540
        const val COMPOSITE_TARGET_FPS = 24
        const val COMPOSITE_MIN_FPS = 15

        const val CAMERA_MAX_BITRATE_BPS = 2_500_000
        const val CAMERA_MIN_BITRATE_BPS = 350_000
        const val COMPOSITE_MAX_BITRATE_BPS = 1_500_000
        const val COMPOSITE_MIN_BITRATE_BPS = 300_000

        const val MAX_CAPTURE_FPS = 60
        const val ZOOM_RATIO_DELTA_EPSILON = 0.01f
        const val TORCH_RETRY_DELAY_MS = 120L
        const val MAX_TORCH_RETRY_ATTEMPTS = 8
    }
}
