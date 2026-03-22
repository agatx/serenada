import AVFoundation
import CoreImage
import Foundation
#if canImport(WebRTC)
import WebRTC
#endif

@MainActor
final class CameraCaptureController {

    private enum Constants {
        static let maxCaptureZoom: CGFloat = 4
        static let minZoomDeltaEpsilon: CGFloat = 0.01
    }

    enum LocalCameraSource {
        case selfie
        case world
        case composite
    }

    // MARK: - Camera State

    var localCameraSource: LocalCameraSource = .selfie
    var preScreenShareCameraSource: LocalCameraSource = .selfie
    private(set) var isSwitchingCameraSource = false
    private(set) var activeCaptureDevice: AVCaptureDevice?
    private var compositeDisabledAfterFailure = false
    private var cachedCompositeSupport: Bool?
    private var isTorchPreferenceEnabled = false
    private(set) var isTorchEnabled = false
    private(set) var currentZoomFactor: CGFloat = 1

#if canImport(WebRTC)
    private(set) var localVideoCapturer: RTCCameraVideoCapturer?
    private(set) var compositeVideoCapturer: CompositeCameraVideoCapturer?
#endif

    // MARK: - External State (set by WebRtcEngine)

    var isScreenSharing = false

    // MARK: - Dependencies

#if canImport(WebRTC)
    private weak var localVideoSource: RTCVideoSource?
#endif
    private(set) var isHdVideoExperimentalEnabled: Bool

    // MARK: - Callbacks

    private var onCameraFacingChanged: (Bool) -> Void
    private var onCameraModeChanged: (LocalCameraMode) -> Void
    private var onFlashlightStateChanged: (Bool, Bool) -> Void
    private var onZoomFactorChanged: (Double) -> Void
    private var onFeatureDegradation: (FeatureDegradationState) -> Void
    private let logger: SerenadaLogger?

    /// Called by switchVideoCapturer's async completion to verify the video track
    /// is still alive before restarting the capturer. Provided by WebRtcEngine.
    var canResumeCapturer: () -> Bool = { true }

    // MARK: - Init

#if canImport(WebRTC)
    init(
        localVideoSource: RTCVideoSource?,
        isHdVideoExperimentalEnabled: Bool,
        onCameraFacingChanged: @escaping (Bool) -> Void,
        onCameraModeChanged: @escaping (LocalCameraMode) -> Void,
        onFlashlightStateChanged: @escaping (Bool, Bool) -> Void,
        onZoomFactorChanged: @escaping (Double) -> Void,
        onFeatureDegradation: @escaping (FeatureDegradationState) -> Void,
        logger: SerenadaLogger? = nil
    ) {
        self.localVideoSource = localVideoSource
        self.isHdVideoExperimentalEnabled = isHdVideoExperimentalEnabled
        self.onCameraFacingChanged = onCameraFacingChanged
        self.onCameraModeChanged = onCameraModeChanged
        self.onFlashlightStateChanged = onFlashlightStateChanged
        self.onZoomFactorChanged = onZoomFactorChanged
        self.onFeatureDegradation = onFeatureDegradation
        self.logger = logger
    }
#else
    init(
        isHdVideoExperimentalEnabled: Bool,
        onCameraFacingChanged: @escaping (Bool) -> Void,
        onCameraModeChanged: @escaping (LocalCameraMode) -> Void,
        onFlashlightStateChanged: @escaping (Bool, Bool) -> Void,
        onZoomFactorChanged: @escaping (Double) -> Void,
        onFeatureDegradation: @escaping (FeatureDegradationState) -> Void,
        logger: SerenadaLogger? = nil
    ) {
        self.isHdVideoExperimentalEnabled = isHdVideoExperimentalEnabled
        self.onCameraFacingChanged = onCameraFacingChanged
        self.onCameraModeChanged = onCameraModeChanged
        self.onFlashlightStateChanged = onFlashlightStateChanged
        self.onZoomFactorChanged = onZoomFactorChanged
        self.onFeatureDegradation = onFeatureDegradation
        self.logger = logger
    }
#endif

    // MARK: - Callback Setters

    func setOnCameraFacingChanged(_ handler: @escaping (Bool) -> Void) {
        onCameraFacingChanged = handler
    }

    func setOnCameraModeChanged(_ handler: @escaping (LocalCameraMode) -> Void) {
        onCameraModeChanged = handler
    }

    func setOnFlashlightStateChanged(_ handler: @escaping (Bool, Bool) -> Void) {
        onFlashlightStateChanged = handler
    }

    func setOnZoomFactorChanged(_ handler: @escaping (Double) -> Void) {
        onZoomFactorChanged = handler
    }

    func setOnFeatureDegradation(_ handler: @escaping (FeatureDegradationState) -> Void) {
        onFeatureDegradation = handler
    }

    @available(*, deprecated, message: "Use SerenadaLogger instead. This method is a no-op.")
    func setOnDebugTrace(_ handler: ((String) -> Void)?) {
    }

#if canImport(WebRTC)
    func updateLocalVideoSource(_ source: RTCVideoSource?) {
        localVideoSource = source
    }
#endif

    // MARK: - Public Interface

    func currentMode() -> LocalCameraMode {
        return activeCameraMode()
    }

    func hasActiveCapturer() -> Bool {
        return hasActiveCameraCapturer()
    }

    func stopAllCapturers() {
#if canImport(WebRTC)
        setTorchEnabled(false)
        localVideoCapturer?.stopCapture()
        localVideoCapturer = nil
        compositeVideoCapturer?.stopCapture()
        compositeVideoCapturer = nil
        activeCaptureDevice = nil
        currentZoomFactor = 1
        onZoomFactorChanged(1)
#endif
    }

    @discardableResult
    func startCapturer(source: LocalCameraSource) -> Bool {
        return restartVideoCapturer(source: source)
    }

    func flipCamera() {
        guard !isScreenSharing else { return }

        let compositeAvailable = canUseCompositeSource()
        let targetMode = nextFlipCameraMode(current: activeCameraMode(), compositeAvailable: compositeAvailable)
        let targetSource = cameraSource(from: targetMode)
        debugTrace(
            "webrtc flipCamera current=\(activeCameraMode().rawValue) target=\(targetMode.rawValue) compositeAvailable=\(compositeAvailable)"
        )

#if canImport(WebRTC)
        let fallbackSource: LocalCameraSource? = targetMode == .composite ? .selfie : nil
        switchVideoCapturer(source: targetSource, fallbackSource: fallbackSource)
#else
        localCameraSource = targetSource
        notifyCameraModeAndFlash()
#endif
    }

    func toggleFlashlight() -> Bool {
        isTorchPreferenceEnabled.toggle()
        let result = applyTorchForCurrentMode()
        if !result {
            isTorchPreferenceEnabled = isTorchEnabled
        }
        notifyCameraModeAndFlash()
        return result
    }

    @discardableResult
    func adjustCaptureZoom(by scaleDelta: CGFloat) -> Double? {
        guard !isScreenSharing else { return nil }
        guard localCameraSource == .world || localCameraSource == .composite else { return nil }
        guard let device = activeCaptureDevice else { return nil }
        guard device.activeFormat.videoMaxZoomFactor > 1 else { return nil }

        let maxZoom = min(device.activeFormat.videoMaxZoomFactor, Constants.maxCaptureZoom)
        let next = max(1, min(maxZoom, currentZoomFactor * scaleDelta))
        guard abs(next - currentZoomFactor) >= Constants.minZoomDeltaEpsilon else {
            return Double(currentZoomFactor)
        }

        do {
            try device.lockForConfiguration()
            device.videoZoomFactor = next
            device.unlockForConfiguration()
            currentZoomFactor = next
            onZoomFactorChanged(Double(next))
            return Double(next)
        } catch {
            return nil
        }
    }

    @discardableResult
    func resetCaptureZoom() -> Double {
        currentZoomFactor = 1
        if let device = activeCaptureDevice, device.activeFormat.videoMaxZoomFactor > 1 {
            do {
                try device.lockForConfiguration()
                device.videoZoomFactor = 1
                device.unlockForConfiguration()
            } catch {}
        }
        onZoomFactorChanged(1)
        return 1
    }

    func setHdVideoExperimentalEnabled(_ enabled: Bool) {
        isHdVideoExperimentalEnabled = enabled
#if canImport(WebRTC)
        if !isScreenSharing {
            switchVideoCapturer(source: localCameraSource)
        }
#endif
    }

    func compositeSupportDebugState() -> String {
#if canImport(WebRTC)
        let snapshot = compositeSupportSnapshot()
        let cached = cachedCompositeSupport.map(String.init(describing:)) ?? "nil"
        return "disabled=\(compositeDisabledAfterFailure) cached=\(cached) switching=\(isSwitchingCameraSource) multi=\(snapshot.hasMultiCam) front=\(snapshot.hasFrontCamera) back=\(snapshot.hasBackCamera) supported=\(snapshot.supported)"
#else
        return "unavailable"
#endif
    }

    func notifyCameraModeAndFlash() {
        let mode = activeCameraMode()
        let isFront = mode == .selfie
        onCameraFacingChanged(isFront)
        onCameraModeChanged(mode)
        onFlashlightStateChanged(supportsTorchForCurrentMode(), isTorchEnabled)
    }

    // MARK: - Internal Camera Logic

#if canImport(WebRTC)
    @discardableResult
    func restartVideoCapturer(source: LocalCameraSource) -> Bool {
        guard let localVideoSource else { return false }
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { return false }
        if source == .composite && !canUseCompositeSource() {
            debugTrace("webrtc restartVideoCapturer composite blocked by support check")
            return false
        }

        localVideoCapturer?.stopCapture()
        localVideoCapturer = nil
        compositeVideoCapturer?.stopCapture()
        compositeVideoCapturer = nil

        if source == .composite {
            let compositeCapturer = CompositeCameraVideoCapturer(
                delegate: localVideoSource,
                logger: logger
            )
            guard compositeCapturer.startCapture() else {
                compositeDisabledAfterFailure = true
                reportCompositeCameraUnavailable(reason: "Composite startCapture failed")
                return false
            }

            compositeVideoCapturer = compositeCapturer
            localCameraSource = source
            activeCaptureDevice = compositeCapturer.primaryCaptureDevice
            _ = adjustCaptureZoom(by: 1)
            notifyCameraModeAndFlash()
            _ = applyTorchForCurrentMode()
            return true
        }

        let capturer = RTCCameraVideoCapturer(delegate: localVideoSource)
        guard let camera = selectCameraDevice(for: source) else {
            if source == .composite {
                compositeDisabledAfterFailure = true
                reportCompositeCameraUnavailable(reason: "Composite camera device unavailable")
            }
            return false
        }
        guard let format = selectCaptureFormat(for: camera) else {
            if source == .composite {
                compositeDisabledAfterFailure = true
                reportCompositeCameraUnavailable(reason: "Composite capture format unavailable")
            }
            return false
        }

        let fps = selectCaptureFPS(for: format)

        capturer.startCapture(with: camera, format: format, fps: fps)
        localVideoCapturer = capturer
        localCameraSource = source
        activeCaptureDevice = camera
        if source == .world || source == .composite {
            _ = adjustCaptureZoom(by: 1)
        } else {
            _ = resetCaptureZoom()
        }

        notifyCameraModeAndFlash()
        _ = applyTorchForCurrentMode()

        enableContinuousAutoFocus(for: camera)

        return true
    }

    private func enableContinuousAutoFocus(for device: AVCaptureDevice) {
        do {
            try device.lockForConfiguration()
            if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
            }
            device.unlockForConfiguration()
        } catch {
            debugTrace("webrtc enableContinuousAutoFocus lock failed error=\(error.localizedDescription)")
        }
    }

    func switchVideoCapturer(source: LocalCameraSource, fallbackSource: LocalCameraSource? = nil) {
        guard localVideoSource != nil else { return }
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { return }
        guard !isSwitchingCameraSource else { return }
        if source == .composite && !canUseCompositeSource() {
            debugTrace("webrtc switchVideoCapturer composite unavailable before switch")
            if let fallbackSource {
                switchVideoCapturer(source: fallbackSource)
            }
            return
        }

        guard let currentCapturer = localVideoCapturer else {
            guard restartVideoCapturer(source: source) else {
                if let fallbackSource {
                    _ = restartVideoCapturer(source: fallbackSource)
                }
                return
            }
            return
        }

        isSwitchingCameraSource = true
        localVideoCapturer = nil
        currentCapturer.stopCapture(completionHandler: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                defer { self.isSwitchingCameraSource = false }
                guard self.localVideoSource != nil else { return }
                guard self.canResumeCapturer() else { return }

                guard self.restartVideoCapturer(source: source) else {
                    self.debugTrace("webrtc switchVideoCapturer failed source=\(self.cameraMode(from: source).rawValue)")
                    if let fallbackSource {
                        self.debugTrace("webrtc switchVideoCapturer applying fallback=\(self.cameraMode(from: fallbackSource).rawValue)")
                        _ = self.restartVideoCapturer(source: fallbackSource)
                    }
                    return
                }
            }
        })
    }

    private func selectCameraDevice(for source: LocalCameraSource) -> AVCaptureDevice? {
        let position: AVCaptureDevice.Position = {
            switch source {
            case .selfie:
                return .front
            case .world, .composite:
                return .back
            }
        }()

        return RTCCameraVideoCapturer.captureDevices().first { $0.position == position }
    }

    private func selectCaptureFormat(for device: AVCaptureDevice) -> AVCaptureDevice.Format? {
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        let paired = formats.map { format -> (format: AVCaptureDevice.Format, resolution: CaptureResolution) in
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            return (
                format: format,
                resolution: CaptureResolution(width: dimensions.width, height: dimensions.height)
            )
        }

        let resolutions = paired.map(\.resolution)
        guard let preferred = choosePreferredCaptureResolution(
            from: resolutions,
            isHdVideoExperimentalEnabled: isHdVideoExperimentalEnabled
        ) else {
            return nil
        }

        return paired.first { $0.resolution == preferred }?.format
    }

    private func selectCaptureFPS(for format: AVCaptureDevice.Format) -> Int {
        let ranges = format.videoSupportedFrameRateRanges
        let maxFps = ranges.map { Int($0.maxFrameRate.rounded()) }.max() ?? 30
        if isHdVideoExperimentalEnabled {
            return min(maxFps, 30)
        }
        return min(maxFps, 24)
    }
#endif

    private func canUseCompositeSource() -> Bool {
#if canImport(WebRTC)
        if compositeDisabledAfterFailure {
            debugTrace("webrtc composite support disabledAfterFailure=true")
            return false
        }
        if let cached = cachedCompositeSupport {
            debugTrace("webrtc composite support cached=\(cached)")
            return cached
        }
        let snapshot = compositeSupportSnapshot()
        let supported = snapshot.supported
        cachedCompositeSupport = supported
        debugTrace(
            "webrtc composite support multiCam=\(snapshot.hasMultiCam) front=\(snapshot.hasFrontCamera) back=\(snapshot.hasBackCamera) supported=\(supported)"
        )
        return supported
#else
        return false
#endif
    }

    private func hasActiveCameraCapturer() -> Bool {
#if canImport(WebRTC)
        localVideoCapturer != nil || compositeVideoCapturer != nil
#else
        false
#endif
    }

    private func activeCameraMode() -> LocalCameraMode {
        if isScreenSharing { return .screenShare }
        return cameraMode(from: localCameraSource)
    }

    func cameraSource(from mode: LocalCameraMode) -> LocalCameraSource {
        switch mode {
        case .selfie:
            return .selfie
        case .world:
            return .world
        case .composite:
            return .composite
        case .screenShare:
            return .selfie
        }
    }

    func cameraMode(from source: LocalCameraSource) -> LocalCameraMode {
        switch source {
        case .selfie:
            return .selfie
        case .world:
            return .world
        case .composite:
            return .composite
        }
    }

    private func debugTrace(_ message: String) {
        logger?.log(.debug, tag: "Camera", message)
    }

    private func reportCompositeCameraUnavailable(reason: String) {
        debugTrace("webrtc composite unavailable: \(reason)")
        onFeatureDegradation(
            FeatureDegradationState(
                kind: .compositeCameraUnavailable,
                reason: reason
            )
        )
    }

    private func compositeSupportSnapshot() -> (hasMultiCam: Bool, hasFrontCamera: Bool, hasBackCamera: Bool, supported: Bool) {
        let hasMultiCam = AVCaptureMultiCamSession.isMultiCamSupported
        let hasFrontCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) != nil
        let hasBackCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) != nil
        return (
            hasMultiCam: hasMultiCam,
            hasFrontCamera: hasFrontCamera,
            hasBackCamera: hasBackCamera,
            supported: hasMultiCam && hasFrontCamera && hasBackCamera
        )
    }

    private func supportsTorchForCurrentMode() -> Bool {
        switch activeCameraMode() {
        case .world, .composite:
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
                return false
            }
            return device.hasTorch
        case .selfie, .screenShare:
            return false
        }
    }

    private func applyTorchForCurrentMode() -> Bool {
        guard supportsTorchForCurrentMode() else {
            setTorchEnabled(false)
            notifyCameraModeAndFlash()
            return false
        }

        setTorchEnabled(isTorchPreferenceEnabled)
        notifyCameraModeAndFlash()
        return true
    }

    func setTorchEnabled(_ enabled: Bool) {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back), device.hasTorch else {
            isTorchEnabled = false
            return
        }

        do {
            try device.lockForConfiguration()
            if enabled {
                try device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel)
            } else {
                device.torchMode = .off
            }
            device.unlockForConfiguration()
            isTorchEnabled = enabled
        } catch {
            isTorchEnabled = false
        }
    }
}
