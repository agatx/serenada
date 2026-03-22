import AVFoundation
import CoreImage
import Foundation
import UIKit
#if canImport(WebRTC)
import WebRTC
#endif

public struct IceServerConfig: Equatable {
    public let urls: [String]
    public let username: String?
    public let credential: String?

    public init(urls: [String], username: String?, credential: String?) {
        self.urls = urls
        self.username = username
        self.credential = credential
    }
}

public struct IceCandidatePayload: Equatable {
    public let sdpMid: String?
    public let sdpMLineIndex: Int32
    public let candidate: String

    public init(sdpMid: String?, sdpMLineIndex: Int32, candidate: String) {
        self.sdpMid = sdpMid
        self.sdpMLineIndex = sdpMLineIndex
        self.candidate = candidate
    }
}

public struct CaptureResolution: Equatable {
    public let width: Int32
    public let height: Int32

    public init(width: Int32, height: Int32) {
        self.width = width
        self.height = height
    }
}

public func choosePreferredCaptureResolution(
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

public enum SessionDescriptionType {
    case offer
    case answer
    case rollback
}

@MainActor
public final class WebRtcEngine: SessionMediaEngine {
    private let logger: SerenadaLogger?

    private let cameraController: CameraCaptureController
    private var screenShareController: ScreenShareController!

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

    private var localRenderers: [WeakAnyBox] = []
#endif

    public init(
        onCameraFacingChanged: @escaping (Bool) -> Void,
        onCameraModeChanged: @escaping (LocalCameraMode) -> Void,
        onFlashlightStateChanged: @escaping (Bool, Bool) -> Void,
        onScreenShareStopped: @escaping () -> Void,
        onZoomFactorChanged: @escaping (Double) -> Void,
        onFeatureDegradation: @escaping (FeatureDegradationState) -> Void = { _ in },
        logger: SerenadaLogger? = nil,
        isHdVideoExperimentalEnabled: Bool
    ) {
        self.logger = logger

#if canImport(WebRTC)
        self.cameraController = CameraCaptureController(
            localVideoSource: nil,
            isHdVideoExperimentalEnabled: isHdVideoExperimentalEnabled,
            onCameraFacingChanged: onCameraFacingChanged,
            onCameraModeChanged: onCameraModeChanged,
            onFlashlightStateChanged: onFlashlightStateChanged,
            onZoomFactorChanged: onZoomFactorChanged,
            onFeatureDegradation: onFeatureDegradation,
            logger: logger
        )
#else
        self.cameraController = CameraCaptureController(
            isHdVideoExperimentalEnabled: isHdVideoExperimentalEnabled,
            onCameraFacingChanged: onCameraFacingChanged,
            onCameraModeChanged: onCameraModeChanged,
            onFlashlightStateChanged: onFlashlightStateChanged,
            onZoomFactorChanged: onZoomFactorChanged,
            onFeatureDegradation: onFeatureDegradation,
            logger: logger
        )
#endif

#if canImport(WebRTC)
        Self.initializeSslIfNeeded()
        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        self.peerConnectionFactory = RTCPeerConnectionFactory(encoderFactory: encoderFactory, decoderFactory: decoderFactory)
#endif

#if canImport(WebRTC)
        self.screenShareController = ScreenShareController(
            cameraController: cameraController,
            localVideoSourceProvider: { [weak self] in self?.localVideoSource },
            isLocalVideoTrackEnabled: { [weak self] in self?.localVideoTrack?.isEnabled == true },
            setLocalVideoTrackEnabled: { [weak self] enabled in self?.localVideoTrack?.isEnabled = enabled },
            onScreenShareStopped: onScreenShareStopped,
            onStateChanged: { _ in },
            logger: logger
        )
#else
        self.screenShareController = ScreenShareController(
            cameraController: cameraController,
            setLocalVideoTrackEnabled: { _ in },
            onScreenShareStopped: onScreenShareStopped,
            onStateChanged: { _ in },
            logger: logger
        )
#endif

        cameraController.canResumeCapturer = { [weak self] in
            self?.localVideoTrack != nil
        }

        cameraController.notifyCameraModeAndFlash()
    }

    public func setOnCameraFacingChanged(_ handler: @escaping (Bool) -> Void) {
        cameraController.setOnCameraFacingChanged(handler)
    }

    public func setOnCameraModeChanged(_ handler: @escaping (LocalCameraMode) -> Void) {
        cameraController.setOnCameraModeChanged(handler)
    }

    public func setOnFlashlightStateChanged(_ handler: @escaping (Bool, Bool) -> Void) {
        cameraController.setOnFlashlightStateChanged(handler)
    }

    public func setOnScreenShareStopped(_ handler: @escaping () -> Void) {
        screenShareController.onScreenShareStopped = handler
    }

    public func setOnZoomFactorChanged(_ handler: @escaping (Double) -> Void) {
        cameraController.setOnZoomFactorChanged(handler)
    }

    public func setOnFeatureDegradation(_ handler: @escaping (FeatureDegradationState) -> Void) {
        cameraController.setOnFeatureDegradation(handler)
    }

    @available(*, deprecated, message: "Use SerenadaLogger instead. This method is a no-op.")
    public func setOnDebugTrace(_ handler: ((String) -> Void)?) {
    }

    public func startLocalMedia(preferVideo: Bool = true) {
#if canImport(WebRTC)
        guard let factory = peerConnectionFactory else { return }
        guard localAudioTrack == nil && localVideoTrack == nil else { return }

        localAudioSource = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        localAudioTrack = factory.audioTrack(with: localAudioSource!, trackId: "ARDAMSa0")

        localVideoSource = factory.videoSource()
        localVideoTrack = factory.videoTrack(with: localVideoSource!, trackId: "ARDAMSv0")

        cameraController.updateLocalVideoSource(localVideoSource)

        if preferVideo {
            let started = cameraController.restartVideoCapturer(source: .selfie)
            localVideoTrack?.isEnabled = started
        } else {
            localVideoTrack?.isEnabled = false
            cameraController.notifyCameraModeAndFlash()
        }

        attachTrackToRegisteredRenderers()
        peerSlots.forEach { $0.attachLocalTracks(audioTrack: localAudioTrack, videoTrack: localVideoTrack) }
#else
        cameraController.notifyCameraModeAndFlash()
#endif
    }

    public func stopLocalMedia() {
#if canImport(WebRTC)
        cameraController.stopAllCapturers()
        detachTracksFromRegisteredRenderers()

        localVideoTrack?.isEnabled = false
        localAudioTrack?.isEnabled = false

        screenShareController.stopAllCapturers()

        localVideoTrack = nil
        localVideoSource = nil
        cameraController.updateLocalVideoSource(nil)
        localAudioTrack = nil
        localAudioSource = nil
#endif
    }

    public func release() {
        stopLocalMedia()
        peerSlots.forEach { $0.closePeerConnection() }
        peerSlots.removeAll()
    }

    public func setIceServers(_ servers: [IceServerConfig]) {
        iceServers = servers
        peerSlots.forEach { $0.setIceServers(servers) }
    }

    public func hasIceServers() -> Bool {
        iceServers != nil
    }

    public func createSlot(
        remoteCid: String,
        onLocalIceCandidate: @escaping (String, IceCandidatePayload) -> Void,
        onRemoteVideoTrack: @escaping (String, AnyObject?) -> Void,
        onConnectionStateChange: @escaping (String, String) -> Void,
        onIceConnectionStateChange: @escaping (String, String) -> Void,
        onSignalingStateChange: @escaping (String, String) -> Void,
        onRenegotiationNeeded: @escaping (String) -> Void
    ) -> (any PeerConnectionSlotProtocol)? {
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

    public func removeSlot(_ slot: any PeerConnectionSlotProtocol) {
#if canImport(WebRTC)
        peerSlots.removeAll { $0 === (slot as AnyObject) }
#endif
    }

    public func toggleAudio(_ enabled: Bool) {
#if canImport(WebRTC)
        localAudioTrack?.isEnabled = enabled
#endif
    }

    @discardableResult
    public func toggleVideo(_ enabled: Bool) -> Bool {
#if canImport(WebRTC)
        if enabled && !cameraController.hasActiveCapturer() && !screenShareController.isScreenSharing {
            let started = cameraController.restartVideoCapturer(source: cameraController.localCameraSource)
            if !started {
                localVideoTrack?.isEnabled = false
                return false
            }
        }
        let effectiveEnabled = enabled && (cameraController.hasActiveCapturer() || screenShareController.isScreenSharing)
        localVideoTrack?.isEnabled = effectiveEnabled
        return effectiveEnabled
#else
        return false
#endif
    }

    public func setHdVideoExperimentalEnabled(_ enabled: Bool) {
        cameraController.setHdVideoExperimentalEnabled(enabled)
    }

    public func toggleFlashlight() -> Bool {
        cameraController.toggleFlashlight()
    }

    public func startScreenShare(onComplete: ((Bool) -> Void)? = nil) -> Bool {
        screenShareController.startScreenShare(onComplete: onComplete)
    }

    public func stopScreenShare() -> Bool {
        screenShareController.stopScreenShare()
    }

    @discardableResult
    public func adjustCaptureZoom(by scaleDelta: CGFloat) -> Double? {
        cameraController.adjustCaptureZoom(by: scaleDelta)
    }

    @discardableResult
    public func resetCaptureZoom() -> Double {
        cameraController.resetCaptureZoom()
    }

    public func attachLocalRenderer(_ renderer: AnyObject) {
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

    public func detachLocalRenderer(_ renderer: AnyObject) {
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

    public func flipCamera() {
        cameraController.flipCamera()
    }

    public func compositeSupportDebugState() -> String {
        cameraController.compositeSupportDebugState()
    }

#if canImport(WebRTC)
    private static func initializeSslIfNeeded() {
        guard !sslInitialized else { return }
        RTCInitializeSSL()
        sslInitialized = true
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
}

#if canImport(WebRTC)
private final class WeakAnyBox {
    weak var value: AnyObject?

    init(value: AnyObject) {
        self.value = value
    }
}

#endif
