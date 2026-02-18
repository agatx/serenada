import AVFoundation
import Foundation
import UIKit
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

enum SessionDescriptionType {
    case offer
    case answer
    case rollback
}

@MainActor
final class WebRtcEngine {
    private enum LocalCameraSource {
        case selfie
        case world
        case composite
    }

    private let onLocalIceCandidate: (IceCandidatePayload) -> Void
    private let onConnectionState: (String) -> Void
    private let onIceConnectionState: (String) -> Void
    private let onSignalingState: (String) -> Void
    private let onRenegotiationNeededCallback: () -> Void
    private let onRemoteVideoTrack: (Bool) -> Void
    private let onCameraFacingChanged: (Bool) -> Void
    private let onCameraModeChanged: (LocalCameraMode) -> Void
    private let onFlashlightStateChanged: (Bool, Bool) -> Void
    private let onScreenShareStopped: () -> Void

    private var isHdVideoExperimentalEnabled: Bool

    private var localCameraSource: LocalCameraSource = .selfie
    private var isScreenSharing = false
    private var isTorchPreferenceEnabled = false
    private var isTorchEnabled = false

    private var iceServers: [IceServerConfig]?
    private var pendingRemoteIceCandidates: [IceCandidatePayload] = []

#if canImport(WebRTC)
    private static var sslInitialized = false
#endif

#if canImport(WebRTC)
    private var peerConnectionFactory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?

    private var localAudioSource: RTCAudioSource?
    private var localAudioTrack: RTCAudioTrack?
    private var localVideoSource: RTCVideoSource?
    private var localVideoTrack: RTCVideoTrack?
    private var localVideoCapturer: RTCCameraVideoCapturer?

    private var remoteVideoTrack: RTCVideoTrack?

    private var localRenderers: [WeakAnyBox] = []
    private var remoteRenderers: [WeakAnyBox] = []

    private var observerProxy: PeerConnectionObserverProxy?
#endif

    init(
        onLocalIceCandidate: @escaping (IceCandidatePayload) -> Void,
        onConnectionState: @escaping (String) -> Void,
        onIceConnectionState: @escaping (String) -> Void,
        onSignalingState: @escaping (String) -> Void,
        onRenegotiationNeededCallback: @escaping () -> Void,
        onRemoteVideoTrack: @escaping (Bool) -> Void,
        onCameraFacingChanged: @escaping (Bool) -> Void,
        onCameraModeChanged: @escaping (LocalCameraMode) -> Void,
        onFlashlightStateChanged: @escaping (Bool, Bool) -> Void,
        onScreenShareStopped: @escaping () -> Void,
        isHdVideoExperimentalEnabled: Bool
    ) {
        self.onLocalIceCandidate = onLocalIceCandidate
        self.onConnectionState = onConnectionState
        self.onIceConnectionState = onIceConnectionState
        self.onSignalingState = onSignalingState
        self.onRenegotiationNeededCallback = onRenegotiationNeededCallback
        self.onRemoteVideoTrack = onRemoteVideoTrack
        self.onCameraFacingChanged = onCameraFacingChanged
        self.onCameraModeChanged = onCameraModeChanged
        self.onFlashlightStateChanged = onFlashlightStateChanged
        self.onScreenShareStopped = onScreenShareStopped
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
        createPeerConnectionIfReady()
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

        localVideoCapturer?.stopCapture()
        localVideoCapturer = nil

        localVideoTrack = nil
        localVideoSource = nil
        localAudioTrack = nil
        localAudioSource = nil
#endif
    }

    func closePeerConnection() {
#if canImport(WebRTC)
        detachTracksFromRegisteredRenderers()
        peerConnection?.close()
        peerConnection = nil
        remoteVideoTrack = nil
        onRemoteVideoTrack(false)
        pendingRemoteIceCandidates.removeAll()
#endif
    }

    func release() {
        stopLocalMedia()
        closePeerConnection()
    }

    func setIceServers(_ servers: [IceServerConfig]) {
        iceServers = servers
        createPeerConnectionIfReady()
    }

    func isReady() -> Bool {
#if canImport(WebRTC)
        return peerConnection != nil
#else
        return false
#endif
    }

    func ensurePeerConnection() {
        createPeerConnectionIfReady()
    }

    func signalingStateRaw() -> String? {
#if canImport(WebRTC)
        guard let peerConnection else { return nil }
        return signalingStateString(peerConnection.signalingState)
#else
        return nil
#endif
    }

    func hasRemoteDescription() -> Bool {
#if canImport(WebRTC)
        peerConnection?.remoteDescription != nil
#else
        false
#endif
    }

    func rollbackLocalDescription(onComplete: ((Bool) -> Void)? = nil) {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete?(false)
            return
        }

        let rollback = RTCSessionDescription(type: .rollback, sdp: "")
        peerConnection.setLocalDescription(rollback) { error in
            onComplete?(error == nil)
        }
#else
        onComplete?(false)
#endif
    }

    @discardableResult
    func createOffer(
        iceRestart: Bool = false,
        onSdp: @escaping (String) -> Void,
        onComplete: ((Bool) -> Void)? = nil
    ) -> Bool {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete?(false)
            return false
        }

        if peerConnection.signalingState != .stable {
            onComplete?(false)
            return false
        }

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: iceRestart ? ["IceRestart": "true"] : nil
        )

        peerConnection.offer(for: constraints) { [weak self] description, error in
            guard let self else { return }
            guard error == nil, let description else {
                onComplete?(false)
                return
            }

            peerConnection.setLocalDescription(description) { setError in
                if setError == nil {
                    onSdp(description.sdp)
                    onComplete?(true)
                } else {
                    onComplete?(false)
                }
            }
        }

        return true
#else
        onComplete?(false)
        return false
#endif
    }

    func createAnswer(onSdp: @escaping (String) -> Void, onComplete: ((Bool) -> Void)? = nil) {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete?(false)
            return
        }

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection.answer(for: constraints) { description, error in
            guard error == nil, let description else {
                onComplete?(false)
                return
            }

            peerConnection.setLocalDescription(description) { setError in
                if setError == nil {
                    onSdp(description.sdp)
                    onComplete?(true)
                } else {
                    onComplete?(false)
                }
            }
        }
#else
        onComplete?(false)
#endif
    }

    func setRemoteDescription(type: SessionDescriptionType, sdp: String, onComplete: (() -> Void)? = nil) {
#if canImport(WebRTC)
        guard let peerConnection else { return }
        let rtcType: RTCSdpType
        switch type {
        case .offer:
            rtcType = .offer
        case .answer:
            rtcType = .answer
        case .rollback:
            rtcType = .rollback
        }

        let description = RTCSessionDescription(type: rtcType, sdp: sdp)
        peerConnection.setRemoteDescription(description) { [weak self] error in
            guard let self else { return }
            if error == nil {
                self.flushPendingIceCandidates()
                onComplete?()
            }
        }
#else
        onComplete?()
#endif
    }

    func addIceCandidate(_ candidate: IceCandidatePayload) {
#if canImport(WebRTC)
        guard let peerConnection else { return }

        if peerConnection.remoteDescription == nil {
            pendingRemoteIceCandidates.append(candidate)
            return
        }

        let rtcCandidate = RTCIceCandidate(
            sdp: candidate.candidate,
            sdpMLineIndex: candidate.sdpMLineIndex,
            sdpMid: candidate.sdpMid
        )
        peerConnection.add(rtcCandidate)
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
        if enabled && localVideoCapturer == nil {
            let started = restartVideoCapturer(source: localCameraSource)
            if !started {
                localVideoTrack?.isEnabled = false
                return false
            }
        }
        let effectiveEnabled = enabled && localVideoCapturer != nil
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
            _ = restartVideoCapturer(source: localCameraSource)
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

    func startScreenShare() -> Bool {
        false
    }

    func stopScreenShare() -> Bool {
        if isScreenSharing {
            isScreenSharing = false
            onScreenShareStopped()
        }
        return true
    }

    func isRemoteVideoTrackEnabled() -> Bool {
#if canImport(WebRTC)
        remoteVideoTrack?.isEnabled ?? false
#else
        false
#endif
    }

    func remoteVideoDiagnostics() -> String {
#if canImport(WebRTC)
        "trackPresent=\(remoteVideoTrack != nil),trackEnabled=\(remoteVideoTrack?.isEnabled == true)"
#else
        "trackPresent=false,trackEnabled=false"
#endif
    }

    func collectWebRtcStatsSummary(onComplete: @escaping (String) -> Void) {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete("pc=none")
            return
        }
        peerConnection.statistics { report in
            onComplete("stats=\(report.statistics.count)")
        }
#else
        onComplete("pc=stub")
#endif
    }

    func attachLocalRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        localRenderers.append(WeakAnyBox(value: renderer))
        compactRenderers()
        if let renderer = renderer as? RTCVideoRenderer {
            localVideoTrack?.add(renderer)
        }
#endif
    }

    func detachLocalRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        if let renderer = renderer as? RTCVideoRenderer {
            localVideoTrack?.remove(renderer)
        }
        localRenderers.removeAll { $0.value === renderer || $0.value == nil }
#endif
    }

    func attachRemoteRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        remoteRenderers.append(WeakAnyBox(value: renderer))
        compactRenderers()
        if let renderer = renderer as? RTCVideoRenderer {
            remoteVideoTrack?.add(renderer)
        }
#endif
    }

    func detachRemoteRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        if let renderer = renderer as? RTCVideoRenderer {
            remoteVideoTrack?.remove(renderer)
        }
        remoteRenderers.removeAll { $0.value === renderer || $0.value == nil }
#endif
    }

    func flipCamera() {
        guard !isScreenSharing else { return }

        let compositeAvailable = canUseCompositeSource()
        let targetMode = nextFlipCameraMode(current: activeCameraMode(), compositeAvailable: compositeAvailable)
        let targetSource = cameraSource(from: targetMode)

#if canImport(WebRTC)
        guard restartVideoCapturer(source: targetSource) else {
            if targetMode == .composite {
                _ = restartVideoCapturer(source: .selfie)
            }
            return
        }
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

    private func createPeerConnectionIfReady() {
        guard peerConnection == nil else { return }
        guard let factory = peerConnectionFactory else { return }
        guard let iceServers else { return }

        let rtcServers = iceServers.map {
            RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential)
        }

        let config = RTCConfiguration()
        config.iceServers = rtcServers
        config.sdpSemantics = .unifiedPlan

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)

        let observer = PeerConnectionObserverProxy(
            onIceCandidate: { [weak self] candidate in
                guard let self else { return }
                self.onLocalIceCandidate(
                    IceCandidatePayload(
                        sdpMid: candidate.sdpMid,
                        sdpMLineIndex: candidate.sdpMLineIndex,
                        candidate: candidate.sdp
                    )
                )
            },
            onConnectionState: { [weak self] state in
                guard let self else { return }
                self.onConnectionState(self.connectionStateString(state))
            },
            onIceConnectionState: { [weak self] state in
                guard let self else { return }
                self.onIceConnectionState(self.iceConnectionStateString(state))
            },
            onSignalingState: { [weak self] state in
                guard let self else { return }
                self.onSignalingState(self.signalingStateString(state))
            },
            onRenegotiationNeeded: { [weak self] in
                self?.onRenegotiationNeededCallback()
            },
            onRemoteVideoTrack: { [weak self] track in
                guard let self else { return }
                self.remoteVideoTrack = track
                self.attachRemoteTrackToRegisteredRenderers()
                self.onRemoteVideoTrack(track != nil)
            }
        )
        observerProxy = observer

        guard let peerConnection = factory.peerConnection(with: config, constraints: constraints, delegate: observer) else {
            return
        }

        self.peerConnection = peerConnection

        if let localAudioTrack {
            _ = peerConnection.add(localAudioTrack, streamIds: ["serenada"])
        } else {
            addReceiveOnlyTransceiver(mediaType: .audio, to: peerConnection)
        }
        if let localVideoTrack {
            _ = peerConnection.add(localVideoTrack, streamIds: ["serenada"])
        } else {
            addReceiveOnlyTransceiver(mediaType: .video, to: peerConnection)
        }
    }

    private func addReceiveOnlyTransceiver(mediaType: RTCRtpMediaType, to peerConnection: RTCPeerConnection) {
        let transceiverInit = RTCRtpTransceiverInit()
        transceiverInit.direction = .recvOnly
        _ = peerConnection.addTransceiver(of: mediaType, init: transceiverInit)
    }

    private func restartVideoCapturer(source: LocalCameraSource) -> Bool {
        guard let localVideoSource else { return false }
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { return false }

        localVideoCapturer?.stopCapture()
        localVideoCapturer = nil

        let capturer = RTCCameraVideoCapturer(delegate: localVideoSource)
        guard let camera = selectCameraDevice(for: source) else { return false }
        guard let format = selectCaptureFormat(for: camera) else { return false }

        let fps = selectCaptureFPS(for: format)

        capturer.startCapture(with: camera, format: format, fps: fps)
        localVideoCapturer = capturer
        localCameraSource = source

        notifyCameraModeAndFlash()
        applyTorchForCurrentMode()
        return true
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
        if isHdVideoExperimentalEnabled {
            return formats.max {
                CMVideoFormatDescriptionGetDimensions($0.formatDescription).width < CMVideoFormatDescriptionGetDimensions($1.formatDescription).width
            }
        }
        return formats.min {
            CMVideoFormatDescriptionGetDimensions($0.formatDescription).width < CMVideoFormatDescriptionGetDimensions($1.formatDescription).width
        }
    }

    private func selectCaptureFPS(for format: AVCaptureDevice.Format) -> Int {
        let ranges = format.videoSupportedFrameRateRanges
        let maxFps = ranges.map { Int($0.maxFrameRate.rounded()) }.max() ?? 30
        if isHdVideoExperimentalEnabled {
            return min(maxFps, 30)
        }
        return min(maxFps, 24)
    }

    private func flushPendingIceCandidates() {
        guard let peerConnection else { return }
        guard peerConnection.remoteDescription != nil else { return }

        let pending = pendingRemoteIceCandidates
        pendingRemoteIceCandidates.removeAll()
        for candidate in pending {
            let rtcCandidate = RTCIceCandidate(
                sdp: candidate.candidate,
                sdpMLineIndex: candidate.sdpMLineIndex,
                sdpMid: candidate.sdpMid
            )
            peerConnection.add(rtcCandidate)
        }
    }

    private func attachTrackToRegisteredRenderers() {
        compactRenderers()
        guard let localVideoTrack else { return }

        for box in localRenderers {
            guard let renderer = box.value as? RTCVideoRenderer else { continue }
            localVideoTrack.add(renderer)
        }
    }

    private func attachRemoteTrackToRegisteredRenderers() {
        compactRenderers()
        guard let remoteVideoTrack else { return }

        for box in remoteRenderers {
            guard let renderer = box.value as? RTCVideoRenderer else { continue }
            remoteVideoTrack.add(renderer)
        }
    }

    private func compactRenderers() {
        localRenderers.removeAll { $0.value == nil }
        remoteRenderers.removeAll { $0.value == nil }
    }

    private func detachTracksFromRegisteredRenderers() {
        compactRenderers()

        if let localVideoTrack {
            for box in localRenderers {
                guard let renderer = box.value as? RTCVideoRenderer else { continue }
                localVideoTrack.remove(renderer)
            }
        }

        if let remoteVideoTrack {
            for box in remoteRenderers {
                guard let renderer = box.value as? RTCVideoRenderer else { continue }
                remoteVideoTrack.remove(renderer)
            }
        }
    }

    private func connectionStateString(_ state: RTCPeerConnectionState) -> String {
        switch state {
        case .new:
            return "NEW"
        case .connecting:
            return "CONNECTING"
        case .connected:
            return "CONNECTED"
        case .disconnected:
            return "DISCONNECTED"
        case .failed:
            return "FAILED"
        case .closed:
            return "CLOSED"
        @unknown default:
            return "UNKNOWN"
        }
    }

    private func iceConnectionStateString(_ state: RTCIceConnectionState) -> String {
        switch state {
        case .new:
            return "NEW"
        case .checking:
            return "CHECKING"
        case .connected:
            return "CONNECTED"
        case .completed:
            return "COMPLETED"
        case .failed:
            return "FAILED"
        case .disconnected:
            return "DISCONNECTED"
        case .closed:
            return "CLOSED"
        case .count:
            return "COUNT"
        @unknown default:
            return "UNKNOWN"
        }
    }

    private func signalingStateString(_ state: RTCSignalingState) -> String {
        switch state {
        case .stable:
            return "STABLE"
        case .haveLocalOffer:
            return "HAVE_LOCAL_OFFER"
        case .haveLocalPrAnswer:
            return "HAVE_LOCAL_PRANSWER"
        case .haveRemoteOffer:
            return "HAVE_REMOTE_OFFER"
        case .haveRemotePrAnswer:
            return "HAVE_REMOTE_PRANSWER"
        case .closed:
            return "CLOSED"
        @unknown default:
            return "UNKNOWN"
        }
    }
#endif

    private func canUseCompositeSource() -> Bool {
        // iOS v1 ships with mode semantics but no custom multi-camera compositor implementation.
        false
    }

    private func activeCameraMode() -> LocalCameraMode {
        if isScreenSharing { return .screenShare }
        switch localCameraSource {
        case .selfie:
            return .selfie
        case .world:
            return .world
        case .composite:
            return .composite
        }
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
private final class PeerConnectionObserverProxy: NSObject, RTCPeerConnectionDelegate {
    private let onIceCandidate: (RTCIceCandidate) -> Void
    private let onConnectionState: (RTCPeerConnectionState) -> Void
    private let onIceConnectionState: (RTCIceConnectionState) -> Void
    private let onSignalingState: (RTCSignalingState) -> Void
    private let onRenegotiationNeeded: () -> Void
    private let onRemoteVideoTrack: (RTCVideoTrack?) -> Void

    init(
        onIceCandidate: @escaping (RTCIceCandidate) -> Void,
        onConnectionState: @escaping (RTCPeerConnectionState) -> Void,
        onIceConnectionState: @escaping (RTCIceConnectionState) -> Void,
        onSignalingState: @escaping (RTCSignalingState) -> Void,
        onRenegotiationNeeded: @escaping () -> Void,
        onRemoteVideoTrack: @escaping (RTCVideoTrack?) -> Void
    ) {
        self.onIceCandidate = onIceCandidate
        self.onConnectionState = onConnectionState
        self.onIceConnectionState = onIceConnectionState
        self.onSignalingState = onSignalingState
        self.onRenegotiationNeeded = onRenegotiationNeeded
        self.onRemoteVideoTrack = onRemoteVideoTrack
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {
        onSignalingState(stateChanged)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        if let track = stream.videoTracks.first {
            onRemoteVideoTrack(track)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        onRemoteVideoTrack(nil)
    }

    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {
        onRenegotiationNeeded()
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        onIceConnectionState(newState)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        onConnectionState(newState)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        onIceCandidate(candidate)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCIceGatheringState) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChangeLocalCandidate local: RTCIceCandidate, remoteCandidate remote: RTCIceCandidate, lastReceivedMs: Int32, changeReason reason: String) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        if let track = rtpReceiver.track as? RTCVideoTrack {
            onRemoteVideoTrack(track)
        }
    }
}

private final class WeakAnyBox {
    weak var value: AnyObject?

    init(value: AnyObject) {
        self.value = value
    }
}
#endif

#if !canImport(WebRTC)
private extension WebRtcEngine {
    func createPeerConnectionIfReady() {}
}
#endif
