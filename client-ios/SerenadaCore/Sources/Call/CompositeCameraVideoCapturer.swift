import AVFoundation
import CoreImage
import Foundation
import UIKit
#if canImport(WebRTC)
import WebRTC
#endif

#if canImport(WebRTC)
final class CompositeCameraVideoCapturer: RTCVideoCapturer, AVCaptureVideoDataOutputSampleBufferDelegate {
    private enum Constants {
        static let overlayDiameterRatio: CGFloat = 0.36
        static let overlayBottomMarginRatio: CGFloat = 0.04
        static let overlayEdgeFeatherRatio: CGFloat = 0.045
        static let minOverlaySize: CGFloat = 72
        static let maxOverlayAgeNs: Int64 = 350_000_000
        static let targetLongSide: Int32 = 640
        static let targetShortSide: Int32 = 480
        static let targetMaxFps: Int32 = 24
    }

    private let captureQueue = DispatchQueue(label: "serenada.ios.composite-capture", qos: .userInitiated)
    private let queueKey = DispatchSpecificKey<Void>()
    private let ciContext = CIContext()
    private let rgbColorSpace = CGColorSpaceCreateDeviceRGB()
    private let session = AVCaptureMultiCamSession()
    private let backOutput = AVCaptureVideoDataOutput()
    private let frontOutput = AVCaptureVideoDataOutput()
    private let logger: SerenadaLogger?

    private var configured = false
    private var isRunning = false
    private var latestFrontPixelBuffer: CVPixelBuffer?
    private var latestFrontTimestampNs: Int64 = 0
    private var sessionObserverTokens: [NSObjectProtocol] = []
    private var orientationObserverToken: NSObjectProtocol?
    private var backConnection: AVCaptureConnection?
    private var frontConnection: AVCaptureConnection?
    private var currentVideoOrientation: AVCaptureVideoOrientation = .portrait
    private var currentInterfaceOrientation: UIInterfaceOrientation = .unknown
    private var currentDeviceOrientation: UIDeviceOrientation = .portrait
    private var cachedMask: (width: CGFloat, height: CGFloat, image: CIImage)?
    private var outputBufferPool: CVPixelBufferPool?
    private var outputBufferPoolSize: (width: Int, height: Int) = (0, 0)

    private(set) var primaryCaptureDevice: AVCaptureDevice?

    init(delegate: RTCVideoCapturerDelegate, logger: SerenadaLogger? = nil) {
        self.logger = logger
        super.init(delegate: delegate)
        captureQueue.setSpecific(key: queueKey, value: ())
    }

    deinit {
        for token in sessionObserverTokens {
            NotificationCenter.default.removeObserver(token)
        }
        if let orientationObserverToken {
            NotificationCenter.default.removeObserver(orientationObserverToken)
        }
    }

    @discardableResult
    func startCapture() -> Bool {
        var started = false
        let interfaceOrientation = Self.currentInterfaceOrientation()
        let deviceOrientation = UIDevice.current.orientation
        runOnCaptureQueueSync {
            currentInterfaceOrientation = interfaceOrientation
            currentDeviceOrientation = deviceOrientation
            debugTrace("webrtc composite startCapture begin configured=\(configured) \(sessionCostSummary())")
            guard configureSessionIfNeeded() else {
                debugTrace("webrtc composite configure failed")
                started = false
                return
            }
            if !session.isRunning {
                session.startRunning()
            }
            isRunning = session.isRunning
            if isRunning {
                UIDevice.current.beginGeneratingDeviceOrientationNotifications()
                updateVideoOrientationIfNeeded()
            }
            debugTrace("webrtc composite startCapture end running=\(isRunning) \(sessionCostSummary())")
            started = isRunning
        }
        return started
    }

    func stopCapture() {
        runOnCaptureQueueSync {
            if session.isRunning {
                session.stopRunning()
            }
            isRunning = false
            latestFrontPixelBuffer = nil
            latestFrontTimestampNs = 0
            cachedMask = nil
            outputBufferPool = nil
            outputBufferPoolSize = (0, 0)
            UIDevice.current.endGeneratingDeviceOrientationNotifications()
            debugTrace("webrtc composite stopCapture running=\(session.isRunning)")
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard isRunning else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let timestampNs = Int64(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer)) * 1_000_000_000)
        if output === frontOutput {
            latestFrontPixelBuffer = pixelBuffer
            latestFrontTimestampNs = timestampNs
            return
        }
        guard output === backOutput else { return }

        let frameBuffer = composeFrame(mainPixelBuffer: pixelBuffer, timestampNs: timestampNs) ?? pixelBuffer
        let rtcBuffer = RTCCVPixelBuffer(pixelBuffer: frameBuffer)
        let frame = RTCVideoFrame(buffer: rtcBuffer, rotation: ._0, timeStampNs: timestampNs)
        delegate?.capturer(self, didCapture: frame)
    }

    private func configureSessionIfNeeded() -> Bool {
        if configured {
            return true
        }
        guard AVCaptureMultiCamSession.isMultiCamSupported else {
            debugTrace("webrtc composite config unsupported multicam=false")
            return false
        }

        guard let backCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let frontCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) else {
            debugTrace("webrtc composite config missing cameras front/back")
            return false
        }
        guard configureDevice(backCamera, label: "back"),
              configureDevice(frontCamera, label: "front") else {
            return false
        }

        let backInput: AVCaptureDeviceInput
        let frontInput: AVCaptureDeviceInput
        do {
            backInput = try AVCaptureDeviceInput(device: backCamera)
            frontInput = try AVCaptureDeviceInput(device: frontCamera)
        } catch {
            debugTrace("webrtc composite config input error=\(error.localizedDescription)")
            return false
        }
        let targetFrameDuration = CMTime(value: 1, timescale: Constants.targetMaxFps)
        backInput.videoMinFrameDurationOverride = targetFrameDuration
        frontInput.videoMinFrameDurationOverride = targetFrameDuration

        backOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        frontOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        backOutput.alwaysDiscardsLateVideoFrames = true
        frontOutput.alwaysDiscardsLateVideoFrames = true
        backOutput.setSampleBufferDelegate(self, queue: captureQueue)
        frontOutput.setSampleBufferDelegate(self, queue: captureQueue)

        installSessionObserversIfNeeded()
        session.beginConfiguration()
        defer { session.commitConfiguration() }

        session.sessionPreset = .inputPriority

        guard session.canAddInput(backInput), session.canAddInput(frontInput) else {
            debugTrace("webrtc composite config cannot add inputs")
            return false
        }
        session.addInputWithNoConnections(backInput)
        session.addInputWithNoConnections(frontInput)

        guard session.canAddOutput(backOutput), session.canAddOutput(frontOutput) else {
            debugTrace("webrtc composite config cannot add outputs")
            return false
        }
        session.addOutputWithNoConnections(backOutput)
        session.addOutputWithNoConnections(frontOutput)

        guard let backPort = videoPort(for: backInput, position: .back),
              let frontPort = videoPort(for: frontInput, position: .front) else {
            debugTrace("webrtc composite config missing input ports")
            return false
        }

        let backConnection = AVCaptureConnection(inputPorts: [backPort], output: backOutput)
        let frontConnection = AVCaptureConnection(inputPorts: [frontPort], output: frontOutput)
        if frontConnection.isVideoMirroringSupported {
            frontConnection.automaticallyAdjustsVideoMirroring = false
            frontConnection.isVideoMirrored = false
        }

        guard session.canAddConnection(backConnection), session.canAddConnection(frontConnection) else {
            debugTrace("webrtc composite config cannot add connections")
            return false
        }

        session.addConnection(backConnection)
        session.addConnection(frontConnection)
        self.backConnection = backConnection
        self.frontConnection = frontConnection
        installOrientationObserverIfNeeded()
        updateVideoOrientationIfNeeded()

        primaryCaptureDevice = backCamera
        configured = true
        debugTrace(
            "webrtc composite config ready back=\(formatSummary(backCamera.activeFormat)) front=\(formatSummary(frontCamera.activeFormat)) \(sessionCostSummary())"
        )
        return true
    }

    private func configureDevice(_ device: AVCaptureDevice, label: String) -> Bool {
        guard let format = preferredFormat(for: device) else {
            debugTrace("webrtc composite config no format label=\(label)")
            return false
        }

        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            device.activeFormat = format

            if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
            }

            let supportedFrameRates = format.videoSupportedFrameRateRanges
            let canTargetFps = supportedFrameRates.contains { range in
                range.minFrameRate <= Double(Constants.targetMaxFps) && range.maxFrameRate >= Double(Constants.targetMaxFps)
            }
            if canTargetFps {
                let frameDuration = CMTime(value: 1, timescale: Constants.targetMaxFps)
                device.activeVideoMinFrameDuration = frameDuration
                device.activeVideoMaxFrameDuration = frameDuration
            }
            debugTrace(
                "webrtc composite config device label=\(label) format=\(formatSummary(format)) targetFps=\(canTargetFps ? Constants.targetMaxFps : 0)"
            )
            return true
        } catch {
            debugTrace("webrtc composite config lock failed label=\(label) error=\(error.localizedDescription)")
            return false
        }
    }

    private func preferredFormat(for device: AVCaptureDevice) -> AVCaptureDevice.Format? {
        let formats = device.formats.filter { format in
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            guard dimensions.width > 0, dimensions.height > 0 else { return false }
            let maxFrameRate = format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 0
            return maxFrameRate >= Double(Constants.targetMaxFps)
        }

        guard !formats.isEmpty else { return nil }

        return formats.min { lhs, rhs in
            let lhsScore = preferredFormatScore(lhs)
            let rhsScore = preferredFormatScore(rhs)
            if lhsScore.distance != rhsScore.distance {
                return lhsScore.distance < rhsScore.distance
            }
            if lhsScore.isBinned != rhsScore.isBinned {
                return lhsScore.isBinned && !rhsScore.isBinned
            }
            if lhsScore.pixels != rhsScore.pixels {
                return lhsScore.pixels < rhsScore.pixels
            }
            return lhsScore.maxFps < rhsScore.maxFps
        }
    }

    private func preferredFormatScore(_ format: AVCaptureDevice.Format) -> (
        distance: Int64,
        isBinned: Bool,
        pixels: Int64,
        maxFps: Double
    ) {
        let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        let longSide = Int64(max(dimensions.width, dimensions.height))
        let shortSide = Int64(min(dimensions.width, dimensions.height))
        let distance = abs(longSide - Int64(Constants.targetLongSide)) + abs(shortSide - Int64(Constants.targetShortSide))
        let pixels = longSide * shortSide
        let maxFps = format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 0
        return (
            distance: distance,
            isBinned: format.isVideoBinned,
            pixels: pixels,
            maxFps: maxFps
        )
    }

    private func installSessionObserversIfNeeded() {
        guard sessionObserverTokens.isEmpty else { return }

        let center = NotificationCenter.default
        sessionObserverTokens.append(
            center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: nil) { [weak self] notification in
                let error = notification.userInfo?[AVCaptureSessionErrorKey] as? NSError
                self?.debugTrace(
                    "webrtc composite runtimeError code=\(error?.code ?? 0) domain=\(error?.domain ?? "-") desc=\(error?.localizedDescription ?? "-") \(self?.sessionCostSummary() ?? "")"
                )
            }
        )
        sessionObserverTokens.append(
            center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: nil) { [weak self] notification in
                let reason = notification.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber
                self?.debugTrace("webrtc composite interrupted reason=\(reason?.intValue ?? 0) \(self?.sessionCostSummary() ?? "")")
            }
        )
        sessionObserverTokens.append(
            center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: nil) { [weak self] _ in
                self?.debugTrace("webrtc composite interruptionEnded \(self?.sessionCostSummary() ?? "")")
            }
        )
    }

    private func installOrientationObserverIfNeeded() {
        guard orientationObserverToken == nil else { return }

        orientationObserverToken = NotificationCenter.default.addObserver(
            forName: UIDevice.orientationDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            let interfaceOrientation = Self.currentInterfaceOrientation()
            let deviceOrientation = UIDevice.current.orientation
            self?.captureQueue.async {
                self?.currentInterfaceOrientation = interfaceOrientation
                self?.currentDeviceOrientation = deviceOrientation
                self?.updateVideoOrientationIfNeeded()
            }
        }
    }

    private func updateVideoOrientationIfNeeded() {
        let orientation = preferredVideoOrientation()
        guard orientation != currentVideoOrientation || !configured else { return }
        currentVideoOrientation = orientation
        applyVideoOrientation(orientation, to: backConnection)
        applyVideoOrientation(orientation, to: frontConnection)
        debugTrace("webrtc composite orientation=\(videoOrientationLabel(orientation))")
    }

    private func preferredVideoOrientation() -> AVCaptureVideoOrientation {
        switch currentInterfaceOrientation {
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .landscapeLeft:
            return .landscapeLeft
        case .landscapeRight:
            return .landscapeRight
        default:
            break
        }

        switch currentDeviceOrientation {
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .landscapeLeft:
            return .landscapeRight
        case .landscapeRight:
            return .landscapeLeft
        default:
            return currentVideoOrientation
        }
    }

    private func applyVideoOrientation(_ orientation: AVCaptureVideoOrientation, to connection: AVCaptureConnection?) {
        guard let connection, connection.isVideoOrientationSupported else { return }
        connection.videoOrientation = orientation
    }

    private static func currentInterfaceOrientation() -> UIInterfaceOrientation {
        guard Thread.isMainThread else { return .unknown }

        let windowScenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return windowScenes
            .first(where: { $0.activationState == .foregroundActive && $0.interfaceOrientation != .unknown })?
            .interfaceOrientation
            ?? windowScenes.first(where: { $0.interfaceOrientation != .unknown })?.interfaceOrientation
            ?? .unknown
    }

    private func videoOrientationLabel(_ orientation: AVCaptureVideoOrientation) -> String {
        switch orientation {
        case .portrait:
            return "portrait"
        case .portraitUpsideDown:
            return "portraitUpsideDown"
        case .landscapeLeft:
            return "landscapeLeft"
        case .landscapeRight:
            return "landscapeRight"
        @unknown default:
            return "unknown"
        }
    }

    private func videoPort(for input: AVCaptureDeviceInput, position: AVCaptureDevice.Position) -> AVCaptureInput.Port? {
        input.ports(for: .video, sourceDeviceType: input.device.deviceType, sourceDevicePosition: position).first
            ?? input.ports.first(where: { $0.mediaType == .video })
    }

    private func composeFrame(mainPixelBuffer: CVPixelBuffer, timestampNs: Int64) -> CVPixelBuffer? {
        guard let frontPixelBuffer = latestFrontPixelBuffer else { return nil }
        if timestampNs - latestFrontTimestampNs > Constants.maxOverlayAgeNs {
            return nil
        }

        let mainWidth = CVPixelBufferGetWidth(mainPixelBuffer)
        let mainHeight = CVPixelBufferGetHeight(mainPixelBuffer)
        guard mainWidth > 0, mainHeight > 0 else { return nil }

        guard let outputPixelBuffer = makeOutputPixelBuffer(width: mainWidth, height: mainHeight) else {
            return nil
        }

        let mainImage = CIImage(cvPixelBuffer: mainPixelBuffer)
        let overlayImage = makeOverlayImage(
            frontPixelBuffer: frontPixelBuffer,
            targetWidth: CGFloat(mainWidth),
            targetHeight: CGFloat(mainHeight)
        )
        let composed = overlayImage.composited(over: mainImage)

        ciContext.render(
            composed,
            to: outputPixelBuffer,
            bounds: CGRect(x: 0, y: 0, width: mainWidth, height: mainHeight),
            colorSpace: rgbColorSpace
        )
        return outputPixelBuffer
    }

    private func makeOverlayImage(frontPixelBuffer: CVPixelBuffer, targetWidth: CGFloat, targetHeight: CGFloat) -> CIImage {
        let frontImage = CIImage(cvPixelBuffer: frontPixelBuffer)
        let sourceExtent = frontImage.extent

        let cropSize = min(sourceExtent.width, sourceExtent.height)
        let cropRect = CGRect(
            x: sourceExtent.midX - (cropSize / 2),
            y: sourceExtent.midY - (cropSize / 2),
            width: cropSize,
            height: cropSize
        )

        let cropped = frontImage.cropped(to: cropRect)
        let normalized = cropped.transformed(by: CGAffineTransform(translationX: -cropped.extent.minX, y: -cropped.extent.minY))
        let mirrored = normalized.transformed(
            by: CGAffineTransform(translationX: normalized.extent.width, y: 0).scaledBy(x: -1, y: 1)
        )

        let targetRect = overlayTargetRect(targetWidth: targetWidth, targetHeight: targetHeight)

        let scaleX = targetRect.width / mirrored.extent.width
        let scaleY = targetRect.height / mirrored.extent.height
        let scaled = mirrored.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
        let translation = CGAffineTransform(
            translationX: targetRect.minX - scaled.extent.minX,
            y: targetRect.minY - scaled.extent.minY
        )
        let translated = scaled.transformed(by: translation)
        let maskImage = overlayMask(for: targetRect)
        let transparentBackground = CIImage(color: CIColor(red: 0, green: 0, blue: 0, alpha: 0))
            .cropped(to: targetRect)

        guard let maskImage else {
            return translated
        }

        return translated
            .applyingFilter(
                "CIBlendWithAlphaMask",
                parameters: [
                    kCIInputBackgroundImageKey: transparentBackground,
                    kCIInputMaskImageKey: maskImage
                ]
            )
            .cropped(to: targetRect)
    }

    private func overlayMask(for rect: CGRect) -> CIImage? {
        if let cached = cachedMask, cached.width == rect.width, cached.height == rect.height {
            return cached.image
        }
        let feather = max(1, rect.width * Constants.overlayEdgeFeatherRatio)
        let radius = rect.width / 2
        guard let mask = CIFilter(
            name: "CIRadialGradient",
            parameters: [
                "inputCenter": CIVector(x: rect.midX, y: rect.midY),
                "inputRadius0": max(0, radius - feather),
                "inputRadius1": radius,
                "inputColor0": CIColor(red: 1, green: 1, blue: 1, alpha: 1),
                "inputColor1": CIColor(red: 0, green: 0, blue: 0, alpha: 0)
            ]
        )?.outputImage?.cropped(to: rect) else {
            return nil
        }
        cachedMask = (width: rect.width, height: rect.height, image: mask)
        return mask
    }

    private func overlayTargetRect(targetWidth: CGFloat, targetHeight: CGFloat) -> CGRect {
        let minDimension = min(targetWidth, targetHeight)
        let overlaySize = min(
            max(Constants.minOverlaySize, minDimension * Constants.overlayDiameterRatio),
            max(2, minDimension - 2)
        )
        let bottomMargin = max(8, targetHeight * Constants.overlayBottomMarginRatio)
        let x = (targetWidth - overlaySize) / 2
        let y = min(max(0, bottomMargin), max(0, targetHeight - overlaySize))
        return CGRect(x: x, y: y, width: overlaySize, height: overlaySize)
    }

    private func makeOutputPixelBuffer(width: Int, height: Int) -> CVPixelBuffer? {
        if outputBufferPoolSize.width != width || outputBufferPoolSize.height != height {
            outputBufferPool = nil
            let attributes: [CFString: Any] = [
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey: width,
                kCVPixelBufferHeightKey: height,
                kCVPixelBufferIOSurfacePropertiesKey: [:],
                kCVPixelBufferCGImageCompatibilityKey: true,
                kCVPixelBufferCGBitmapContextCompatibilityKey: true
            ]
            var pool: CVPixelBufferPool?
            let poolStatus = CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &pool)
            guard poolStatus == kCVReturnSuccess, let pool else { return nil }
            outputBufferPool = pool
            outputBufferPoolSize = (width, height)
        }
        guard let pool = outputBufferPool else { return nil }
        var pixelBuffer: CVPixelBuffer?
        let status = CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &pixelBuffer)
        guard status == kCVReturnSuccess else { return nil }
        return pixelBuffer
    }

    private func formatSummary(_ format: AVCaptureDevice.Format) -> String {
        let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        let maxFps = format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 0
        let roundedMaxFps = Int(maxFps.rounded())
        return "\(dimensions.width)x\(dimensions.height)@\(roundedMaxFps)binned=\(format.isVideoBinned)"
    }

    private func sessionCostSummary() -> String {
        let hardwareCost = String(format: "%.2f", session.hardwareCost)
        let systemPressureCost = String(format: "%.2f", session.systemPressureCost)
        return "hardwareCost=\(hardwareCost) pressureCost=\(systemPressureCost)"
    }

    private func debugTrace(_ message: String) {
        logger?.log(.debug, tag: "Camera", message)
    }

    private func runOnCaptureQueueSync(_ block: () -> Void) {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            block()
            return
        }
        captureQueue.sync(execute: block)
    }
}
#endif
