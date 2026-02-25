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
    private struct RealtimeStatsSample {
        let timestampMs: Int64
        let audioRxBytes: Int64
        let audioTxBytes: Int64
        let videoRxBytes: Int64
        let videoTxBytes: Int64
        let videoFramesDecoded: Int64
        let videoNackCount: Int64
        let videoPliCount: Int64
        let videoFirCount: Int64
    }

    private struct FreezeSample {
        let timestampMs: Int64
        let freezeCount: Int64
        let freezeDurationSeconds: Double
    }

    private struct MediaTotals {
        var inboundPacketsReceived: Int64 = 0
        var inboundPacketsLost: Int64 = 0
        var inboundBytes: Int64 = 0

        var outboundPacketsSent: Int64 = 0
        var outboundBytes: Int64 = 0
        var outboundPacketsRetransmitted: Int64 = 0

        var remoteInboundPacketsLost: Int64 = 0

        var inboundJitterSumSeconds: Double = 0
        var inboundJitterCount: Int64 = 0

        var inboundJitterBufferDelaySeconds: Double = 0
        var inboundJitterBufferEmittedCount: Int64 = 0
        var inboundConcealedSamples: Int64 = 0
        var inboundTotalSamples: Int64 = 0

        var inboundFpsSum: Double = 0
        var inboundFpsCount: Int64 = 0
        var inboundFrameWidth: Int = 0
        var inboundFrameHeight: Int = 0
        var inboundFramesDecoded: Int64 = 0

        var inboundFreezeCount: Int64 = 0
        var inboundFreezeDurationSeconds: Double = 0

        var inboundNackCount: Int64 = 0
        var inboundPliCount: Int64 = 0
        var inboundFirCount: Int64 = 0
    }

    private enum Constants {
        static let maxCaptureZoom: CGFloat = 4
        static let minZoomDeltaEpsilon: CGFloat = 0.01
        static let freezeWindowMs: Int64 = 60_000
    }

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
    private let onZoomFactorChanged: (Double) -> Void

    private var isHdVideoExperimentalEnabled: Bool

    private var localCameraSource: LocalCameraSource = .selfie
    private var preScreenShareCameraSource: LocalCameraSource = .selfie
    private var isScreenSharing = false
    private var isTorchPreferenceEnabled = false
    private var isTorchEnabled = false
    private var compositeDisabledAfterFailure = false
    private var cachedCompositeSupport: Bool?
    private var activeCaptureDevice: AVCaptureDevice?
    private var currentZoomFactor: CGFloat = 1

    private var iceServers: [IceServerConfig]?
    private var pendingRemoteIceCandidates: [IceCandidatePayload] = []
    private var lastRealtimeStatsSample: RealtimeStatsSample?
    private var freezeSamples: [FreezeSample] = []
    private let rendererAttachmentQueue = DispatchQueue(label: "serenada.ios.webrtc.renderer-attachment", qos: .userInitiated)

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
    private var compositeVideoCapturer: CompositeCameraVideoCapturer?
    #if canImport(ReplayKit)
    private var replayKitCapturer: ReplayKitVideoCapturer?
    #endif

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
        onZoomFactorChanged: @escaping (Double) -> Void,
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
        self.onZoomFactorChanged = onZoomFactorChanged
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

    func closePeerConnection() {
#if canImport(WebRTC)
        detachRemoteTrackFromRegisteredRenderers()
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

    func setRemoteDescription(type: SessionDescriptionType, sdp: String, onComplete: ((Bool) -> Void)? = nil) {
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
                onComplete?(true)
            } else {
                onComplete?(false)
            }
        }
#else
        onComplete?(true)
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

    func collectRealtimeCallStats(onComplete: @escaping (RealtimeCallStats) -> Void) {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete(.empty)
            return
        }
        peerConnection.statistics { [weak self] report in
            guard let self else {
                onComplete(.empty)
                return
            }
            onComplete(self.buildRealtimeCallStats(report))
        }
#else
        onComplete(.empty)
#endif
    }

#if canImport(WebRTC)
    private func buildRealtimeCallStats(_ report: RTCStatisticsReport) -> RealtimeCallStats {
        let stats = Array(report.statistics.values)
        var audio = MediaTotals()
        var video = MediaTotals()

        var selectedCandidatePair: RTCStatistics?
        var fallbackCandidatePair: RTCStatistics?
        var remoteInboundRttSumSeconds = 0.0
        var remoteInboundRttCount: Int64 = 0

        for stat in stats {
            if stat.type == "candidate-pair" {
                let isSelected = memberBool(stat, key: "selected") == true
                let isNominated = memberBool(stat, key: "nominated") == true
                let pairState = memberString(stat, key: "state")
                if isSelected {
                    selectedCandidatePair = stat
                } else if fallbackCandidatePair == nil && isNominated && pairState == "succeeded" {
                    fallbackCandidatePair = stat
                }
                continue
            }

            guard let kind = mediaKind(for: stat) else { continue }
            if kind == "audio" {
                collectMediaStat(stat, into: &audio, remoteInboundRttSumSeconds: &remoteInboundRttSumSeconds, remoteInboundRttCount: &remoteInboundRttCount)
            } else {
                collectMediaStat(stat, into: &video, remoteInboundRttSumSeconds: &remoteInboundRttSumSeconds, remoteInboundRttCount: &remoteInboundRttCount)
            }
        }

        let selectedPair = selectedCandidatePair ?? fallbackCandidatePair
        let localCandidate = selectedPair.flatMap { pair -> RTCStatistics? in
            guard let id = memberString(pair, key: "localCandidateId"), !id.isEmpty else { return nil }
            return report.statistics[id]
        }
        let remoteCandidate = selectedPair.flatMap { pair -> RTCStatistics? in
            guard let id = memberString(pair, key: "remoteCandidateId"), !id.isEmpty else { return nil }
            return report.statistics[id]
        }

        let localCandidateType = memberString(localCandidate, key: "candidateType")
        let remoteCandidateType = memberString(remoteCandidate, key: "candidateType")
        let localProtocol = memberString(localCandidate, key: "protocol")
        let remoteProtocol = memberString(remoteCandidate, key: "protocol")
        let isRelay = localCandidateType == "relay" || remoteCandidateType == "relay"
        let transportPath: String? = {
            guard localCandidateType != nil || remoteCandidateType != nil else { return nil }
            return "\(isRelay ? "TURN relay" : "Direct") (\(localCandidateType ?? "n/a") -> \(remoteCandidateType ?? "n/a"), \(localProtocol ?? remoteProtocol ?? "n/a"))"
        }()

        let candidateRttSeconds = memberDouble(selectedPair, key: "currentRoundTripTime")
        let remoteInboundRttSeconds: Double? = remoteInboundRttCount > 0
            ? (remoteInboundRttSumSeconds / Double(remoteInboundRttCount))
            : nil
        let chosenRttSeconds = candidateRttSeconds ?? remoteInboundRttSeconds
        let rttMs = chosenRttSeconds.map { $0 * 1000.0 }
        let availableOutgoingKbps = memberDouble(selectedPair, key: "availableOutgoingBitrate").map { $0 / 1000.0 }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let previousSample = lastRealtimeStatsSample
        let elapsedSeconds: Double = {
            guard let previousSample else { return 0 }
            return max(0, Double(now - previousSample.timestampMs) / 1000.0)
        }()

        let audioRxKbps = previousSample.flatMap { calculateBitrateKbps(previousBytes: $0.audioRxBytes, currentBytes: audio.inboundBytes, elapsedSeconds: elapsedSeconds) }
        let audioTxKbps = previousSample.flatMap { calculateBitrateKbps(previousBytes: $0.audioTxBytes, currentBytes: audio.outboundBytes, elapsedSeconds: elapsedSeconds) }
        let videoRxKbps = previousSample.flatMap { calculateBitrateKbps(previousBytes: $0.videoRxBytes, currentBytes: video.inboundBytes, elapsedSeconds: elapsedSeconds) }
        let videoTxKbps = previousSample.flatMap { calculateBitrateKbps(previousBytes: $0.videoTxBytes, currentBytes: video.outboundBytes, elapsedSeconds: elapsedSeconds) }

        let videoFps: Double? = {
            if video.inboundFpsCount > 0 {
                return video.inboundFpsSum / Double(video.inboundFpsCount)
            }
            if let previousSample, elapsedSeconds > 0, video.inboundFramesDecoded >= previousSample.videoFramesDecoded {
                return Double(video.inboundFramesDecoded - previousSample.videoFramesDecoded) / elapsedSeconds
            }
            return nil
        }()

        freezeSamples.append(
            FreezeSample(
                timestampMs: now,
                freezeCount: video.inboundFreezeCount,
                freezeDurationSeconds: video.inboundFreezeDurationSeconds
            )
        )
        freezeSamples.removeAll { now - $0.timestampMs > Constants.freezeWindowMs }
        let freezeWindowBase = freezeSamples.first
        let videoFreezeCount60s = freezeWindowBase.map { max(0, video.inboundFreezeCount - $0.freezeCount) }
        let videoFreezeDuration60s = freezeWindowBase.map { max(0, video.inboundFreezeDurationSeconds - $0.freezeDurationSeconds) }

        let audioRxPacketLossPct = ratioPercent(numerator: audio.inboundPacketsLost, denominator: audio.inboundPacketsLost + audio.inboundPacketsReceived)
        let audioTxPacketLossPct = ratioPercent(numerator: audio.remoteInboundPacketsLost, denominator: audio.remoteInboundPacketsLost + audio.outboundPacketsSent)
        let videoRxPacketLossPct = ratioPercent(numerator: video.inboundPacketsLost, denominator: video.inboundPacketsLost + video.inboundPacketsReceived)
        let videoTxPacketLossPct = ratioPercent(numerator: video.remoteInboundPacketsLost, denominator: video.remoteInboundPacketsLost + video.outboundPacketsSent)

        let audioJitterMs = audio.inboundJitterCount > 0
            ? ((audio.inboundJitterSumSeconds / Double(audio.inboundJitterCount)) * 1000.0)
            : nil
        let audioPlayoutDelayMs = audio.inboundJitterBufferEmittedCount > 0
            ? ((audio.inboundJitterBufferDelaySeconds / Double(audio.inboundJitterBufferEmittedCount)) * 1000.0)
            : nil
        let audioConcealedPct = ratioPercent(numerator: audio.inboundConcealedSamples, denominator: audio.inboundConcealedSamples + audio.inboundTotalSamples)
        let videoRetransmitPct = ratioPercent(numerator: video.outboundPacketsRetransmitted, denominator: video.outboundPacketsSent)

        let videoNackPerMin = previousSample.flatMap { positiveRatePerMinute(currentValue: video.inboundNackCount, previousValue: $0.videoNackCount, elapsedSeconds: elapsedSeconds) }
        let videoPliPerMin = previousSample.flatMap { positiveRatePerMinute(currentValue: video.inboundPliCount, previousValue: $0.videoPliCount, elapsedSeconds: elapsedSeconds) }
        let videoFirPerMin = previousSample.flatMap { positiveRatePerMinute(currentValue: video.inboundFirCount, previousValue: $0.videoFirCount, elapsedSeconds: elapsedSeconds) }

        let videoResolution: String? = (video.inboundFrameWidth > 0 && video.inboundFrameHeight > 0)
            ? "\(video.inboundFrameWidth)x\(video.inboundFrameHeight)"
            : nil

        lastRealtimeStatsSample = RealtimeStatsSample(
            timestampMs: now,
            audioRxBytes: audio.inboundBytes,
            audioTxBytes: audio.outboundBytes,
            videoRxBytes: video.inboundBytes,
            videoTxBytes: video.outboundBytes,
            videoFramesDecoded: video.inboundFramesDecoded,
            videoNackCount: video.inboundNackCount,
            videoPliCount: video.inboundPliCount,
            videoFirCount: video.inboundFirCount
        )

        return RealtimeCallStats(
            transportPath: transportPath,
            rttMs: rttMs,
            availableOutgoingKbps: availableOutgoingKbps,
            audioRxPacketLossPct: audioRxPacketLossPct,
            audioTxPacketLossPct: audioTxPacketLossPct,
            audioJitterMs: audioJitterMs,
            audioPlayoutDelayMs: audioPlayoutDelayMs,
            audioConcealedPct: audioConcealedPct,
            audioRxKbps: audioRxKbps,
            audioTxKbps: audioTxKbps,
            videoRxPacketLossPct: videoRxPacketLossPct,
            videoTxPacketLossPct: videoTxPacketLossPct,
            videoRxKbps: videoRxKbps,
            videoTxKbps: videoTxKbps,
            videoFps: videoFps,
            videoResolution: videoResolution,
            videoFreezeCount60s: videoFreezeCount60s,
            videoFreezeDuration60s: videoFreezeDuration60s,
            videoRetransmitPct: videoRetransmitPct,
            videoNackPerMin: videoNackPerMin,
            videoPliPerMin: videoPliPerMin,
            videoFirPerMin: videoFirPerMin,
            updatedAtMs: now
        )
    }

    private func collectMediaStat(
        _ stat: RTCStatistics,
        into totals: inout MediaTotals,
        remoteInboundRttSumSeconds: inout Double,
        remoteInboundRttCount: inout Int64
    ) {
        switch stat.type {
        case "inbound-rtp":
            totals.inboundPacketsReceived += memberInt64(stat, key: "packetsReceived") ?? 0
            totals.inboundPacketsLost += max(0, memberInt64(stat, key: "packetsLost") ?? 0)
            totals.inboundBytes += memberInt64(stat, key: "bytesReceived") ?? 0

            if let jitter = memberDouble(stat, key: "jitter") {
                totals.inboundJitterSumSeconds += jitter
                totals.inboundJitterCount += 1
            }

            totals.inboundJitterBufferDelaySeconds += memberDouble(stat, key: "jitterBufferDelay") ?? 0
            totals.inboundJitterBufferEmittedCount += memberInt64(stat, key: "jitterBufferEmittedCount") ?? 0
            totals.inboundConcealedSamples += memberInt64(stat, key: "concealedSamples") ?? 0
            totals.inboundTotalSamples += memberInt64(stat, key: "totalSamplesReceived") ?? 0

            if let fps = memberDouble(stat, key: "framesPerSecond") {
                totals.inboundFpsSum += fps
                totals.inboundFpsCount += 1
            }

            let frameWidth = Int(memberInt64(stat, key: "frameWidth") ?? 0)
            let frameHeight = Int(memberInt64(stat, key: "frameHeight") ?? 0)
            totals.inboundFrameWidth = max(totals.inboundFrameWidth, frameWidth)
            totals.inboundFrameHeight = max(totals.inboundFrameHeight, frameHeight)
            totals.inboundFramesDecoded += memberInt64(stat, key: "framesDecoded") ?? 0

            totals.inboundFreezeCount += memberInt64(stat, key: "freezeCount") ?? 0
            totals.inboundFreezeDurationSeconds += memberDouble(stat, key: "totalFreezesDuration") ?? 0
            totals.inboundNackCount += memberInt64(stat, key: "nackCount") ?? 0
            totals.inboundPliCount += memberInt64(stat, key: "pliCount") ?? 0
            totals.inboundFirCount += memberInt64(stat, key: "firCount") ?? 0

        case "outbound-rtp":
            totals.outboundPacketsSent += memberInt64(stat, key: "packetsSent") ?? 0
            totals.outboundBytes += memberInt64(stat, key: "bytesSent") ?? 0
            totals.outboundPacketsRetransmitted += memberInt64(stat, key: "retransmittedPacketsSent") ?? 0

        case "remote-inbound-rtp":
            totals.remoteInboundPacketsLost += max(0, memberInt64(stat, key: "packetsLost") ?? 0)
            if let remoteRtt = memberDouble(stat, key: "roundTripTime") {
                remoteInboundRttSumSeconds += remoteRtt
                remoteInboundRttCount += 1
            }

        default:
            break
        }
    }

    private func mediaKind(for stat: RTCStatistics) -> String? {
        let kind = memberString(stat, key: "kind") ?? memberString(stat, key: "mediaType")
        if kind == "audio" || kind == "video" {
            return kind
        }
        return nil
    }

    private func memberString(_ stat: RTCStatistics?, key: String) -> String? {
        guard let value = stat?.values[key] else { return nil }
        if let str = value as? String {
            let trimmed = str.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        let text = value.description.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }

    private func memberDouble(_ stat: RTCStatistics?, key: String) -> Double? {
        guard let value = stat?.values[key] else { return nil }
        if let number = value as? NSNumber {
            return number.doubleValue
        }
        if let text = value as? String {
            return Double(text)
        }
        return nil
    }

    private func memberInt64(_ stat: RTCStatistics?, key: String) -> Int64? {
        guard let value = stat?.values[key] else { return nil }
        if let number = value as? NSNumber {
            return number.int64Value
        }
        if let text = value as? String {
            return Int64(text)
        }
        return nil
    }

    private func memberBool(_ stat: RTCStatistics?, key: String) -> Bool? {
        guard let value = stat?.values[key] else { return nil }
        if let number = value as? NSNumber {
            return number.boolValue
        }
        if let text = value as? String {
            switch text.lowercased() {
            case "true":
                return true
            case "false":
                return false
            default:
                return nil
            }
        }
        return nil
    }

    private func calculateBitrateKbps(previousBytes: Int64, currentBytes: Int64, elapsedSeconds: Double) -> Double? {
        guard elapsedSeconds > 0, currentBytes >= previousBytes else { return nil }
        let bits = Double(currentBytes - previousBytes) * 8
        return bits / elapsedSeconds / 1000.0
    }

    private func ratioPercent(numerator: Int64, denominator: Int64) -> Double? {
        guard denominator > 0 else { return nil }
        return (Double(numerator) / Double(denominator)) * 100.0
    }

    private func positiveRatePerMinute(currentValue: Int64, previousValue: Int64, elapsedSeconds: Double) -> Double? {
        guard elapsedSeconds > 0, currentValue >= previousValue else { return nil }
        return (Double(currentValue - previousValue) / elapsedSeconds) * 60.0
    }
#endif

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

    func attachRemoteRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        remoteRenderers.append(WeakAnyBox(value: renderer))
        compactRenderers()
        if let renderer = renderer as? RTCVideoRenderer {
            let track = remoteVideoTrack
            rendererAttachmentQueue.async {
                track?.add(renderer)
            }
        }
#endif
    }

    func detachRemoteRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        if let renderer = renderer as? RTCVideoRenderer {
            let track = remoteVideoTrack
            rendererAttachmentQueue.async {
                track?.remove(renderer)
            }
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
        if source == .composite && !canUseCompositeSource() {
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
            let compositeCapturer = CompositeCameraVideoCapturer(delegate: localVideoSource)
            guard compositeCapturer.startCapture() else {
                compositeDisabledAfterFailure = true
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
        let renderers = localRenderers.compactMap { $0.value as? RTCVideoRenderer }
        rendererAttachmentQueue.async {
            for renderer in renderers {
                localVideoTrack.add(renderer)
            }
        }
    }

    private func attachRemoteTrackToRegisteredRenderers() {
        compactRenderers()
        guard let remoteVideoTrack else { return }
        let renderers = remoteRenderers.compactMap { $0.value as? RTCVideoRenderer }
        rendererAttachmentQueue.async {
            for renderer in renderers {
                remoteVideoTrack.add(renderer)
            }
        }
    }

    private func compactRenderers() {
        localRenderers.removeAll { $0.value == nil }
        remoteRenderers.removeAll { $0.value == nil }
    }

    private func detachTracksFromRegisteredRenderers() {
        compactRenderers()
        let localTrack = localVideoTrack
        let remoteTrack = remoteVideoTrack
        let localRendererList = localRenderers.compactMap { $0.value as? RTCVideoRenderer }
        let remoteRendererList = remoteRenderers.compactMap { $0.value as? RTCVideoRenderer }
        rendererAttachmentQueue.async {
            if let localTrack {
                for renderer in localRendererList {
                    localTrack.remove(renderer)
                }
            }
            if let remoteTrack {
                for renderer in remoteRendererList {
                    remoteTrack.remove(renderer)
                }
            }
        }
    }

    private func detachRemoteTrackFromRegisteredRenderers() {
        compactRenderers()

        guard let remoteVideoTrack else { return }
        let renderers = remoteRenderers.compactMap { $0.value as? RTCVideoRenderer }
        rendererAttachmentQueue.async {
            for renderer in renderers {
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
#if canImport(WebRTC)
        if compositeDisabledAfterFailure {
            return false
        }
        if let cachedCompositeSupport {
            return cachedCompositeSupport
        }
        let hasMultiCam = AVCaptureMultiCamSession.isMultiCamSupported
        let hasFrontCamera = selectCameraDevice(for: .selfie) != nil
        let hasBackCamera = selectCameraDevice(for: .world) != nil
        let supported = hasMultiCam && hasFrontCamera && hasBackCamera
        cachedCompositeSupport = supported
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

private final class CompositeCameraVideoCapturer: RTCVideoCapturer, AVCaptureVideoDataOutputSampleBufferDelegate {
    private enum Constants {
        static let overlaySizeRatio: CGFloat = 0.28
        static let overlayMarginRatio: CGFloat = 0.04
        static let maxOverlayAgeNs: Int64 = 350_000_000
    }

    private let captureQueue = DispatchQueue(label: "serenada.ios.composite-capture", qos: .userInitiated)
    private let queueKey = DispatchSpecificKey<Void>()
    private let ciContext = CIContext()
    private let rgbColorSpace = CGColorSpaceCreateDeviceRGB()
    private let session = AVCaptureMultiCamSession()
    private let backOutput = AVCaptureVideoDataOutput()
    private let frontOutput = AVCaptureVideoDataOutput()

    private var configured = false
    private var isRunning = false
    private var latestFrontPixelBuffer: CVPixelBuffer?
    private var latestFrontTimestampNs: Int64 = 0

    private(set) var primaryCaptureDevice: AVCaptureDevice?

    override init(delegate: RTCVideoCapturerDelegate) {
        super.init(delegate: delegate)
        captureQueue.setSpecific(key: queueKey, value: ())
    }

    @discardableResult
    func startCapture() -> Bool {
        var started = false
        runOnCaptureQueueSync {
            guard configureSessionIfNeeded() else {
                started = false
                return
            }
            if !session.isRunning {
                session.startRunning()
            }
            isRunning = session.isRunning
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
        guard AVCaptureMultiCamSession.isMultiCamSupported else { return false }

        guard let backCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let frontCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) else {
            return false
        }

        let backInput: AVCaptureDeviceInput
        let frontInput: AVCaptureDeviceInput
        do {
            backInput = try AVCaptureDeviceInput(device: backCamera)
            frontInput = try AVCaptureDeviceInput(device: frontCamera)
        } catch {
            return false
        }

        backOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        frontOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        backOutput.alwaysDiscardsLateVideoFrames = true
        frontOutput.alwaysDiscardsLateVideoFrames = true
        backOutput.setSampleBufferDelegate(self, queue: captureQueue)
        frontOutput.setSampleBufferDelegate(self, queue: captureQueue)

        session.beginConfiguration()
        defer { session.commitConfiguration() }

        session.sessionPreset = .inputPriority

        guard session.canAddInput(backInput), session.canAddInput(frontInput) else { return false }
        session.addInputWithNoConnections(backInput)
        session.addInputWithNoConnections(frontInput)

        guard session.canAddOutput(backOutput), session.canAddOutput(frontOutput) else { return false }
        session.addOutputWithNoConnections(backOutput)
        session.addOutputWithNoConnections(frontOutput)

        guard let backPort = videoPort(for: backInput, position: .back),
              let frontPort = videoPort(for: frontInput, position: .front) else {
            return false
        }

        let backConnection = AVCaptureConnection(inputPorts: [backPort], output: backOutput)
        let frontConnection = AVCaptureConnection(inputPorts: [frontPort], output: frontOutput)

        if backConnection.isVideoOrientationSupported {
            backConnection.videoOrientation = .portrait
        }
        if frontConnection.isVideoOrientationSupported {
            frontConnection.videoOrientation = .portrait
        }
        if frontConnection.isVideoMirroringSupported {
            frontConnection.isVideoMirrored = true
        }

        guard session.canAddConnection(backConnection), session.canAddConnection(frontConnection) else {
            return false
        }

        session.addConnection(backConnection)
        session.addConnection(frontConnection)

        primaryCaptureDevice = backCamera
        configured = true
        return true
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
        let mirrored = cropped.transformed(
            by: CGAffineTransform(scaleX: -1, y: 1).translatedBy(x: -cropRect.width, y: 0)
        )

        let baseSize = min(targetWidth, targetHeight)
        let overlaySize = max(1, baseSize * Constants.overlaySizeRatio)
        let margin = baseSize * Constants.overlayMarginRatio
        let targetRect = CGRect(
            x: targetWidth - overlaySize - margin,
            y: margin,
            width: overlaySize,
            height: overlaySize
        )

        let scaleX = targetRect.width / mirrored.extent.width
        let scaleY = targetRect.height / mirrored.extent.height
        let scaled = mirrored.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
        let translation = CGAffineTransform(
            translationX: targetRect.minX - scaled.extent.minX,
            y: targetRect.minY - scaled.extent.minY
        )
        return scaled.transformed(by: translation)
    }

    private func makeOutputPixelBuffer(width: Int, height: Int) -> CVPixelBuffer? {
        var pixelBuffer: CVPixelBuffer?
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey: width,
            kCVPixelBufferHeightKey: height,
            kCVPixelBufferIOSurfacePropertiesKey: [:],
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true
        ]
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32BGRA,
            attributes as CFDictionary,
            &pixelBuffer
        )
        guard status == kCVReturnSuccess else { return nil }
        return pixelBuffer
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

#if !canImport(WebRTC)
private extension WebRtcEngine {
    func createPeerConnectionIfReady() {}
}
#endif
