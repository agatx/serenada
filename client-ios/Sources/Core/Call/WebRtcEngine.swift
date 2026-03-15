import AVFoundation
import CoreImage
import Foundation
import UIKit
#if canImport(ReplayKit)
import ReplayKit
#endif
#if canImport(WebRTC)
import WebRTC
#endif

struct IceServerConfig: Equatable {
    let urls: [String]
    let username: String?
    let credential: String?
}

struct IceCandidatePayload: Equatable {
    let sdpMid: String?
    let sdpMLineIndex: Int32
    let candidate: String
}

struct CaptureResolution: Equatable {
    let width: Int32
    let height: Int32
}

func choosePreferredCaptureResolution(
    from resolutions: [CaptureResolution],
    isHdVideoExperimentalEnabled: Bool
) -> CaptureResolution? {
    guard !resolutions.isEmpty else { return nil }

    func normalized(_ resolution: CaptureResolution) -> (longSide: Int32, shortSide: Int32) {
        (
            longSide: max(resolution.width, resolution.height),
            shortSide: min(resolution.width, resolution.height)
        )
    }

    if isHdVideoExperimentalEnabled {
        return resolutions.max {
            let lhs = normalized($0)
            let rhs = normalized($1)
            if lhs.longSide != rhs.longSide {
                return lhs.longSide < rhs.longSide
            }
            if lhs.shortSide != rhs.shortSide {
                return lhs.shortSide < rhs.shortSide
            }
            return $0.width < $1.width
        }
    }

    // Non-HD mode targets 480p (640x480) for a clearer default preview.
    let targetLongSide: Int64 = 640
    let targetShortSide: Int64 = 480

    func nonHdScore(_ resolution: CaptureResolution) -> (distance: Int64, pixels: Int64, longSide: Int64) {
        let dims = normalized(resolution)
        let longSide = Int64(dims.longSide)
        let shortSide = Int64(dims.shortSide)
        let distance = abs(longSide - targetLongSide) + abs(shortSide - targetShortSide)
        let pixels = longSide * shortSide
        return (distance: distance, pixels: pixels, longSide: longSide)
    }

    return resolutions.min {
        let lhs = nonHdScore($0)
        let rhs = nonHdScore($1)
        if lhs.distance != rhs.distance {
            return lhs.distance < rhs.distance
        }
        if lhs.pixels != rhs.pixels {
            return lhs.pixels < rhs.pixels
        }
        return lhs.longSide < rhs.longSide
    }
}

enum SessionDescriptionType {
    case offer
    case answer
    case rollback
}

@MainActor
final class WebRtcEngine {
    private enum Constants {
        static let maxCaptureZoom: CGFloat = 4
        static let minZoomDeltaEpsilon: CGFloat = 0.01
    }

    private enum LocalCameraSource {
        case selfie
        case world
        case composite
    }

    private let onCameraFacingChanged: (Bool) -> Void
    private let onCameraModeChanged: (LocalCameraMode) -> Void
    private let onFlashlightStateChanged: (Bool, Bool) -> Void
    private let onScreenShareStopped: () -> Void
    private let onZoomFactorChanged: (Double) -> Void
    private let onDebugTrace: ((String) -> Void)?

    private var isHdVideoExperimentalEnabled: Bool

    private var localCameraSource: LocalCameraSource = .selfie
    private var preScreenShareCameraSource: LocalCameraSource = .selfie
    private var isScreenSharing = false
    private var isTorchPreferenceEnabled = false
    private var isTorchEnabled = false
    private var compositeDisabledAfterFailure = false
    private var cachedCompositeSupport: Bool?
    private var isSwitchingCameraSource = false
    private var activeCaptureDevice: AVCaptureDevice?
    private var currentZoomFactor: CGFloat = 1

    private var iceServers: [IceServerConfig]?
    private let rendererAttachmentQueue = DispatchQueue(label: "serenada.ios.webrtc.renderer-attachment", qos: .userInitiated)

#if canImport(WebRTC)
    private static var sslInitialized = false
#endif

#if canImport(WebRTC)
    private var peerConnectionFactory: RTCPeerConnectionFactory?
    private var peerSlots: [PeerConnectionSlot] = []

    private var localAudioSource: RTCAudioSource?
    private var localAudioTrack: RTCAudioTrack?
    private var localVideoSource: RTCVideoSource?
    private var localVideoTrack: RTCVideoTrack?
    private var localVideoCapturer: RTCCameraVideoCapturer?
    private var compositeVideoCapturer: CompositeCameraVideoCapturer?
    #if canImport(ReplayKit)
    private var replayKitCapturer: ReplayKitVideoCapturer?
    #endif

    private var localRenderers: [WeakAnyBox] = []
#endif

    init(
        onCameraFacingChanged: @escaping (Bool) -> Void,
        onCameraModeChanged: @escaping (LocalCameraMode) -> Void,
        onFlashlightStateChanged: @escaping (Bool, Bool) -> Void,
        onScreenShareStopped: @escaping () -> Void,
        onZoomFactorChanged: @escaping (Double) -> Void,
        onDebugTrace: ((String) -> Void)? = nil,
        isHdVideoExperimentalEnabled: Bool
    ) {
        self.onCameraFacingChanged = onCameraFacingChanged
        self.onCameraModeChanged = onCameraModeChanged
        self.onFlashlightStateChanged = onFlashlightStateChanged
        self.onScreenShareStopped = onScreenShareStopped
        self.onZoomFactorChanged = onZoomFactorChanged
        self.onDebugTrace = onDebugTrace
        self.isHdVideoExperimentalEnabled = isHdVideoExperimentalEnabled

#if canImport(WebRTC)
        Self.initializeSslIfNeeded()
        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        self.peerConnectionFactory = RTCPeerConnectionFactory(encoderFactory: encoderFactory, decoderFactory: decoderFactory)
#endif

        notifyCameraModeAndFlash()
    }

    func startLocalMedia(preferVideo: Bool = true) {
#if canImport(WebRTC)
        guard let factory = peerConnectionFactory else { return }
        guard localAudioTrack == nil && localVideoTrack == nil else { return }

        localAudioSource = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        localAudioTrack = factory.audioTrack(with: localAudioSource!, trackId: "ARDAMSa0")

        localVideoSource = factory.videoSource()
        localVideoTrack = factory.videoTrack(with: localVideoSource!, trackId: "ARDAMSv0")

        if preferVideo {
            let started = restartVideoCapturer(source: .selfie)
            localVideoTrack?.isEnabled = started
        } else {
            localVideoTrack?.isEnabled = false
            notifyCameraModeAndFlash()
        }

        attachTrackToRegisteredRenderers()
        peerSlots.forEach { $0.attachLocalTracks(audioTrack: localAudioTrack, videoTrack: localVideoTrack) }
#else
        onCameraFacingChanged(true)
        onCameraModeChanged(.selfie)
#endif
    }

    func stopLocalMedia() {
#if canImport(WebRTC)
        setTorchEnabled(false)
        detachTracksFromRegisteredRenderers()

        localVideoTrack?.isEnabled = false
        localAudioTrack?.isEnabled = false

        #if canImport(ReplayKit)
        replayKitCapturer?.stopCapture()
        replayKitCapturer = nil
        #endif
        isScreenSharing = false
        localVideoCapturer?.stopCapture()
        localVideoCapturer = nil
        compositeVideoCapturer?.stopCapture()
        compositeVideoCapturer = nil
        activeCaptureDevice = nil
        currentZoomFactor = 1
        onZoomFactorChanged(1)

        localVideoTrack = nil
        localVideoSource = nil
        localAudioTrack = nil
        localAudioSource = nil
#endif
    }

    func release() {
        stopLocalMedia()
        peerSlots.forEach { $0.closePeerConnection() }
        peerSlots.removeAll()
    }

    func setIceServers(_ servers: [IceServerConfig]) {
        iceServers = servers
        peerSlots.forEach { $0.setIceServers(servers) }
    }

    func hasIceServers() -> Bool {
        iceServers != nil
    }

    func createSlot(
        remoteCid: String,
        onLocalIceCandidate: @escaping (String, IceCandidatePayload) -> Void,
        onRemoteVideoTrack: @escaping (String, AnyObject?) -> Void,
        onConnectionStateChange: @escaping (String, String) -> Void,
        onIceConnectionStateChange: @escaping (String, String) -> Void,
        onSignalingStateChange: @escaping (String, String) -> Void,
        onRenegotiationNeeded: @escaping (String) -> Void
    ) -> PeerConnectionSlot? {
#if canImport(WebRTC)
        guard let peerConnectionFactory else { return nil }
        let slot = PeerConnectionSlot(
            remoteCid: remoteCid,
            factory: peerConnectionFactory,
            iceServers: iceServers,
            localAudioTrack: localAudioTrack,
            localVideoTrack: localVideoTrack,
            onLocalIceCandidate: onLocalIceCandidate,
            onRemoteVideoTrack: { remoteCid, track in
                onRemoteVideoTrack(remoteCid, track)
            },
            onConnectionStateChange: onConnectionStateChange,
            onIceConnectionStateChange: onIceConnectionStateChange,
            onSignalingStateChange: onSignalingStateChange,
            onRenegotiationNeeded: onRenegotiationNeeded
        )
        peerSlots.append(slot)
        return slot
#else
        return nil
#endif
    }

    func removeSlot(_ slot: PeerConnectionSlot) {
#if canImport(WebRTC)
        peerSlots.removeAll { $0 === slot }
#endif
    }

    func toggleAudio(_ enabled: Bool) {
#if canImport(WebRTC)
        localAudioTrack?.isEnabled = enabled
#endif
    }

    @discardableResult
    func toggleVideo(_ enabled: Bool) -> Bool {
#if canImport(WebRTC)
        if enabled && !hasActiveCameraCapturer() && !isScreenSharing {
            let started = restartVideoCapturer(source: localCameraSource)
            if !started {
                localVideoTrack?.isEnabled = false
                return false
            }
        }
        let effectiveEnabled = enabled && (hasActiveCameraCapturer() || isScreenSharing)
        localVideoTrack?.isEnabled = effectiveEnabled
        return effectiveEnabled
#else
        return false
#endif
    }

    func setHdVideoExperimentalEnabled(_ enabled: Bool) {
        isHdVideoExperimentalEnabled = enabled
#if canImport(WebRTC)
        if !isScreenSharing {
            switchVideoCapturer(source: localCameraSource)
        }
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

    func startScreenShare(onComplete: ((Bool) -> Void)? = nil) -> Bool {
#if canImport(WebRTC) && canImport(ReplayKit)
        guard let localVideoSource else {
            onComplete?(false)
            return false
        }
        if isScreenSharing {
            onComplete?(true)
            return true
        }

        let previousSource = localCameraSource
        preScreenShareCameraSource = previousSource
        setTorchEnabled(false)
        localVideoCapturer?.stopCapture()
        localVideoCapturer = nil
        compositeVideoCapturer?.stopCapture()
        compositeVideoCapturer = nil
        activeCaptureDevice = nil

        let capturer = ReplayKitVideoCapturer(delegate: localVideoSource)
        replayKitCapturer = capturer

        return capturer.startCapture { [weak self] started in
            Task { @MainActor in
                guard let self else { return }
                if started {
                    self.isScreenSharing = true
                    self.currentZoomFactor = 1
                    self.onZoomFactorChanged(1)
                    self.notifyCameraModeAndFlash()
                    self.localVideoTrack?.isEnabled = true
                    onComplete?(true)
                    return
                }

                self.replayKitCapturer = nil
                self.isScreenSharing = false
                _ = self.restartVideoCapturer(source: previousSource)
                self.notifyCameraModeAndFlash()
                onComplete?(false)
            }
        }
#else
        onComplete?(false)
        return false
#endif
    }

    func stopScreenShare() -> Bool {
#if canImport(WebRTC) && canImport(ReplayKit)
        replayKitCapturer?.stopCapture()
        replayKitCapturer = nil
#endif
        if isScreenSharing {
            isScreenSharing = false
            let restoreSource = preScreenShareCameraSource
            preScreenShareCameraSource = .selfie
#if canImport(WebRTC)
            if localVideoTrack?.isEnabled == true {
                _ = restartVideoCapturer(source: restoreSource)
            } else {
                localCameraSource = restoreSource
                notifyCameraModeAndFlash()
            }
#endif
            onScreenShareStopped()
        }
        return true
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

    func attachLocalRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        localRenderers.append(WeakAnyBox(value: renderer))
        compactRenderers()
        if let renderer = renderer as? RTCVideoRenderer {
            let track = localVideoTrack
            rendererAttachmentQueue.async {
                track?.add(renderer)
            }
        }
#endif
    }

    func detachLocalRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        if let renderer = renderer as? RTCVideoRenderer {
            let track = localVideoTrack
            rendererAttachmentQueue.async {
                track?.remove(renderer)
            }
        }
        localRenderers.removeAll { $0.value === renderer || $0.value == nil }
#endif
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

#if canImport(WebRTC)
    private static func initializeSslIfNeeded() {
        guard !sslInitialized else { return }
        RTCInitializeSSL()
        sslInitialized = true
    }

    private func restartVideoCapturer(source: LocalCameraSource) -> Bool {
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
        #if canImport(ReplayKit)
        replayKitCapturer?.stopCapture()
        replayKitCapturer = nil
        #endif
        isScreenSharing = false

        if source == .composite {
            let compositeCapturer = CompositeCameraVideoCapturer(
                delegate: localVideoSource,
                onDebugTrace: { [weak self] message in
                    Task { @MainActor in
                        self?.debugTrace(message)
                    }
                }
            )
            guard compositeCapturer.startCapture() else {
                compositeDisabledAfterFailure = true
                debugTrace("webrtc composite startCapture failed; disabling composite")
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
            }
            return false
        }
        guard let format = selectCaptureFormat(for: camera) else {
            if source == .composite {
                compositeDisabledAfterFailure = true
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
        return true
    }

    private func switchVideoCapturer(source: LocalCameraSource, fallbackSource: LocalCameraSource? = nil) {
#if canImport(WebRTC)
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
                guard self.localVideoTrack != nil else { return }

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
#endif
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

    private func attachTrackToRegisteredRenderers() {
        compactRenderers()
        guard let localVideoTrack else { return }
        let renderers = localRenderers.compactMap { $0.value as? RTCVideoRenderer }
        rendererAttachmentQueue.async {
            for renderer in renderers {
                localVideoTrack.add(renderer)
            }
        }
    }

    private func compactRenderers() {
        localRenderers.removeAll { $0.value == nil }
    }

    private func detachTracksFromRegisteredRenderers() {
        compactRenderers()
        let localTrack = localVideoTrack
        let localRendererList = localRenderers.compactMap { $0.value as? RTCVideoRenderer }
        rendererAttachmentQueue.async {
            if let localTrack {
                for renderer in localRendererList {
                    localTrack.remove(renderer)
                }
            }
        }
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

    func compositeSupportDebugState() -> String {
#if canImport(WebRTC)
        let snapshot = compositeSupportSnapshot()
        let cached = cachedCompositeSupport.map(String.init(describing:)) ?? "nil"
        return "disabled=\(compositeDisabledAfterFailure) cached=\(cached) switching=\(isSwitchingCameraSource) multi=\(snapshot.hasMultiCam) front=\(snapshot.hasFrontCamera) back=\(snapshot.hasBackCamera) supported=\(snapshot.supported)"
#else
        return "unavailable"
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

    private func cameraSource(from mode: LocalCameraMode) -> LocalCameraSource {
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

    private func cameraMode(from source: LocalCameraSource) -> LocalCameraMode {
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
#if DEBUG
        onDebugTrace?(message)
#endif
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

    private func setTorchEnabled(_ enabled: Bool) {
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

    private func notifyCameraModeAndFlash() {
        let mode = activeCameraMode()
        let isFront = mode == .selfie
        onCameraFacingChanged(isFront)
        onCameraModeChanged(mode)
        onFlashlightStateChanged(supportsTorchForCurrentMode(), isTorchEnabled)
    }
}

#if canImport(WebRTC)
private final class WeakAnyBox {
    weak var value: AnyObject?

    init(value: AnyObject) {
        self.value = value
    }
}

private final class CompositeCameraVideoCapturer: RTCVideoCapturer, AVCaptureVideoDataOutputSampleBufferDelegate {
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
    private let onDebugTrace: ((String) -> Void)?

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

    init(delegate: RTCVideoCapturerDelegate, onDebugTrace: ((String) -> Void)? = nil) {
        self.onDebugTrace = onDebugTrace
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
#if DEBUG
        onDebugTrace?(message)
#endif
    }

    private func runOnCaptureQueueSync(_ block: () -> Void) {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            block()
            return
        }
        captureQueue.sync(execute: block)
    }
}

#if canImport(ReplayKit)
private final class ReplayKitVideoCapturer: RTCVideoCapturer {
    private let recorder = RPScreenRecorder.shared()
    private var isRunning = false

    @discardableResult
    func startCapture(onReady: @escaping (Bool) -> Void) -> Bool {
        guard !isRunning else {
            onReady(true)
            return true
        }

        recorder.isMicrophoneEnabled = false
        recorder.startCapture(
            handler: { [weak self] sampleBuffer, sampleType, _ in
                guard let self else { return }
                guard sampleType == .video else { return }
                guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

                let timestampNs = Int64(CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds * 1_000_000_000)
                let rtcBuffer = RTCCVPixelBuffer(pixelBuffer: pixelBuffer)
                let frame = RTCVideoFrame(buffer: rtcBuffer, rotation: ._0, timeStampNs: timestampNs)
                self.delegate?.capturer(self, didCapture: frame)
            },
            completionHandler: { [weak self] error in
                let success = (error == nil)
                self?.isRunning = success
                onReady(success)
            }
        )

        return true
    }

    func stopCapture() {
        guard isRunning else { return }
        recorder.stopCapture { [weak self] _ in
            self?.isRunning = false
        }
    }
}
#endif
#endif
