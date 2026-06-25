import AVFoundation
import CoreImage
import Foundation
import UIKit
#if canImport(WebRTC)
@preconcurrency import WebRTC
#endif

public struct IceServerConfig: Equatable, Sendable {
    public let urls: [String]
    public let username: String?
    public let credential: String?

    public init(urls: [String], username: String?, credential: String?) {
        self.urls = urls
        self.username = username
        self.credential = credential
    }
}

internal struct IceCandidatePayload: Equatable {
    public let sdpMid: String?
    public let sdpMLineIndex: Int32
    public let candidate: String

    public init(sdpMid: String?, sdpMLineIndex: Int32, candidate: String) {
        self.sdpMid = sdpMid
        self.sdpMLineIndex = sdpMLineIndex
        self.candidate = candidate
    }
}

internal struct CaptureResolution: Equatable {
    public let width: Int32
    public let height: Int32

    public init(width: Int32, height: Int32) {
        self.width = width
        self.height = height
    }
}

internal func choosePreferredCaptureResolution(
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

/// Whether a screen share should suppress the camera-toggle capturer logic.
///
/// True only for a LEGACY single-video share (the display track has repurposed
/// the single camera sender). In INDEPENDENT mode the screen rides a separate
/// content track, so `toggleVideo` must operate the camera capturer normally:
/// turning ON from OFF restarts capture, turning OFF stops it (pitfall #6).
///
/// Pure so it is unit-testable without the WebRTC framework (mirrors
/// `choosePreferredCaptureResolution`). `WebRtcEngine.isLegacyScreenSharing`
/// and `toggleVideo` both route through this. Flag off ⇒ this equals raw
/// `isScreenSharing`, so the legacy camera-toggle path is byte-identical.
internal func isLegacyScreenSharingGate(
    isScreenSharing: Bool,
    enableIndependentContentVideo: Bool
) -> Bool {
    isScreenSharing && !enableIndependentContentVideo
}

/// FIX 2 (legacy-peer content sender encoding): whether a LEGACY peer's single
/// video sender is currently carrying the content (display) track rather than
/// the camera track — true exactly while an independent share is active and a
/// content track exists. Drives the legacy single sender's encoding profile
/// (content vs camera). Keyed on the active share + a live content track, NOT on
/// `isLegacyScreenSharing` (which is false in independent mode). Pure so it is
/// unit-testable without the WebRTC framework. Inert (false) whenever there is no
/// content track, so the legacy flag-off path is byte-identical.
internal func legacyVideoCarriesContentGate(
    isScreenSharing: Bool,
    hasContentVideoTrack: Bool
) -> Bool {
    isScreenSharing && hasContentVideoTrack
}

internal enum SessionDescriptionType {
    case offer
    case answer
    case rollback
}

@MainActor
internal final class WebRtcEngine: SessionMediaEngine {
    private let logger: SerenadaLogger?
    private let videoMediaEnabled: Bool

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
    // Camera video path (legacy names retained): CameraCaptureController writes
    // this source. Carries the camera track on capable peers and the single
    // legacy video track otherwise.
    private var localVideoSource: RTCVideoSource?
    private var localVideoTrack: RTCVideoTrack?
    // Independent-content path (flag ON only): a SEPARATE source/track carries
    // the screen share. ScreenShareController's BroadcastFrameReader delegates to
    // this source. The content track is also the "pending" track attached to
    // capable peers as their content transceiver binds. Never touched on the
    // legacy path.
    private var localContentVideoSource: RTCVideoSource?
    private var localContentVideoTrack: RTCVideoTrack?
    private var previousUseManualAudio: Bool?

    private var localRenderers: [WeakAnyBox] = []
    private var localContentRenderers: [WeakAnyBox] = []
#endif

    // Local build capability gate. When false (default), every peer uses the
    // legacy single-video screen-share path and behavior is byte-identical to
    // today. When true, screen share rides a SEPARATE content track/transceiver
    // for capable peers (per-peer routing decided at slot creation).
    private let enableIndependentContentVideo: Bool

    private var audioPipelinePrimer: LocalAudioPipelinePrimer?

    public init(
        onCameraFacingChanged: @escaping (Bool) -> Void,
        onCameraModeChanged: @escaping (LocalCameraMode) -> Void,
        onFlashlightStateChanged: @escaping (Bool, Bool) -> Void,
        onScreenShareStopped: @escaping () -> Void,
        onZoomFactorChanged: @escaping (Double) -> Void,
        onFeatureDegradation: @escaping (FeatureDegradationState) -> Void = { _ in },
        logger: SerenadaLogger? = nil,
        isHdVideoExperimentalEnabled: Bool,
        videoMediaEnabled: Bool = true,
        enableIndependentContentVideo: Bool = false,
        screenShareMode: ScreenShareMode = .disabled,
        availableCameraModes: [LocalCameraMode] = defaultCameraModes
    ) {
        self.logger = logger
        self.videoMediaEnabled = videoMediaEnabled
        self.enableIndependentContentVideo = enableIndependentContentVideo

#if canImport(WebRTC)
        self.cameraController = CameraCaptureController(
            localVideoSource: nil,
            isHdVideoExperimentalEnabled: isHdVideoExperimentalEnabled,
            availableCameraModes: availableCameraModes,
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
            availableCameraModes: availableCameraModes,
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
        let factory = RTCPeerConnectionFactory(encoderFactory: encoderFactory, decoderFactory: decoderFactory)
        self.peerConnectionFactory = factory
        self.audioPipelinePrimer = LocalAudioPipelinePrimer(factory: factory, logger: logger)
#endif

#if canImport(WebRTC)
        self.screenShareController = ScreenShareController(
            cameraController: cameraController,
            localVideoSourceProvider: { [weak self] in self?.localVideoSource },
            localContentVideoSourceProvider: { [weak self] in self?.localContentVideoSource },
            isLocalVideoTrackEnabled: { [weak self] in self?.localVideoTrack?.isEnabled == true },
            setLocalVideoTrackEnabled: { [weak self] enabled in self?.localVideoTrack?.isEnabled = enabled },
            onScreenShareStopped: onScreenShareStopped,
            // Independent stop (programmatic OR external broadcast finished/interrupted):
            // the controller has stopped capture, so tear down the content track and
            // detach it from every peer here. This is the single engine-side content
            // teardown point so the external-stop path detaches peers too.
            onStateChanged: { [weak self] isSharing in
                guard let self else { return }
                if !isSharing && self.enableIndependentContentVideo {
                    self.tearDownContentAndDetach()
                }
            },
            independentContentEnabled: enableIndependentContentVideo,
            screenShareMode: screenShareMode,
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

        let audioSession = RTCAudioSession.sharedInstance()
        do {
            audioSession.lockForConfiguration()
            defer { audioSession.unlockForConfiguration() }
            if previousUseManualAudio == nil {
                previousUseManualAudio = audioSession.useManualAudio
            }
            audioSession.useManualAudio = true
            audioSession.isAudioEnabled = true
        }

        localAudioSource = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        localAudioTrack = factory.audioTrack(with: localAudioSource!, trackId: "ARDAMSa0")

        if videoMediaEnabled {
            localVideoSource = factory.videoSource()
            localVideoTrack = factory.videoTrack(with: localVideoSource!, trackId: "ARDAMSv0")

            cameraController.updateLocalVideoSource(localVideoSource)

            let videoCaptureSupported = !cameraController.availableCameraModes.isEmpty
            if preferVideo && videoCaptureSupported {
                let started = cameraController.restartVideoCapturerFromAvailableModes()
                localVideoTrack?.isEnabled = started
            } else {
                localVideoTrack?.isEnabled = false
                cameraController.notifyCameraModeAndFlash()
            }
        } else {
            cameraController.updateLocalVideoSource(nil)
            cameraController.notifyCameraModeAndFlash()
        }

        attachTrackToRegisteredRenderers()
        if let localAudioTrack {
            audioPipelinePrimer?.start(localAudioTrack: localAudioTrack)
        }
        peerSlots.forEach { attachLocalTracksToSlot($0) }
#else
        cameraController.notifyCameraModeAndFlash()
#endif
    }

#if canImport(WebRTC)
    /// True only while a LEGACY single-video screen share is active (the display
    /// track has repurposed the single camera sender). In INDEPENDENT mode this is
    /// always false, so camera ops (toggle/flip) keep working during a share — the
    /// screen rides a separate content track (pitfall #6).
    private var isLegacyScreenSharing: Bool {
        isLegacyScreenSharingGate(
            isScreenSharing: screenShareController.isScreenSharing,
            enableIndependentContentVideo: enableIndependentContentVideo
        )
    }

    /// Route the current local tracks to a slot per its per-peer capability.
    ///
    /// - Capable peer: camera track → camera role, content track → content role
    ///   (camera and screen share simultaneously).
    /// - Legacy peer: a SINGLE video track. While an independent share is active
    ///   the screen takes priority over camera on that connection (matches
    ///   today's legacy precedence), otherwise the camera track is used. This is
    ///   the only place the legacy single-sender precedence is decided, so camera
    ///   ops never clobber a legacy peer's content sender during a share (pitfall #7).
    private func attachLocalTracksToSlot(_ slot: any PeerConnectionSlotProtocol) {
        if slot.supportsIndependentContentVideo {
            slot.attachLocalTracks(
                audioTrack: localAudioTrack,
                cameraTrack: localVideoTrack,
                contentTrack: localContentVideoTrack,
                supportsIndependentContentVideo: true,
                legacyVideoCarriesContent: false
            )
        } else {
            // The legacy single sender carries the content (display) track when a
            // share is active (precedence over camera); flag it so the slot applies
            // the content encoding profile to that sender instead of the camera
            // default, and restores camera params on stop (FIX 2).
            let legacyContent = legacyVideoCarriesContentGate(
                isScreenSharing: screenShareController.isScreenSharing,
                hasContentVideoTrack: localContentVideoTrack != nil
            )
            let legacyVideoTrack: RTCVideoTrack? = legacyContent ? localContentVideoTrack : localVideoTrack
            slot.attachLocalTracks(
                audioTrack: localAudioTrack,
                cameraTrack: legacyVideoTrack,
                contentTrack: nil,
                supportsIndependentContentVideo: false,
                legacyVideoCarriesContent: legacyContent
            )
        }
    }

    private func ensureLocalContentVideoTrack() -> RTCVideoTrack? {
        guard let factory = peerConnectionFactory else { return nil }
        if let track = localContentVideoTrack {
            track.isEnabled = true
            return track
        }
        let source = localContentVideoSource ?? factory.videoSource()
        localContentVideoSource = source
        let track = factory.videoTrack(with: source, trackId: "ARDAMScontent0")
        track.isEnabled = true
        localContentVideoTrack = track
        let renderers = localContentRenderers.compactMap { $0.value as? RTCVideoRenderer }
        rendererAttachmentQueue.async {
            for renderer in renderers { track.add(renderer) }
        }
        return track
    }

    private func disposeLocalContentVideoTrack() {
        if let track = localContentVideoTrack {
            let renderers = localContentRenderers.compactMap { $0.value as? RTCVideoRenderer }
            rendererAttachmentQueue.async {
                for renderer in renderers { track.remove(renderer) }
            }
        }
        localContentVideoTrack = nil
        localContentVideoSource = nil
    }

    /// Engine-side content teardown: detach the content track from every peer
    /// (capable: content sender → nil; legacy: restore camera on the single
    /// sender) and release the content source/track. Idempotent — runs once per
    /// logical stop via the controller's onStateChanged(false), covering both the
    /// programmatic stop and the external broadcast termination.
    private func tearDownContentAndDetach() {
        if localContentVideoTrack == nil && localContentVideoSource == nil { return }
        localContentVideoTrack?.isEnabled = false
        disposeLocalContentVideoTrack()
        peerSlots.forEach { attachLocalTracksToSlot($0) }
    }
#endif

    public func stopLocalMedia() {
#if canImport(WebRTC)
        cameraController.stopAllCapturers()
        detachTracksFromRegisteredRenderers()

        localVideoTrack?.isEnabled = false
        localContentVideoTrack?.isEnabled = false
        localAudioTrack?.isEnabled = false

        screenShareController.stopAllCapturers()

        // Tear down the primer before releasing its audio track reference.
        audioPipelinePrimer?.stop()

        localVideoTrack = nil
        localVideoSource = nil
        disposeLocalContentVideoTrack()
        cameraController.updateLocalVideoSource(nil)
        localAudioTrack = nil
        localAudioSource = nil
        let audioSession = RTCAudioSession.sharedInstance()
        do {
            audioSession.lockForConfiguration()
            defer { audioSession.unlockForConfiguration() }
            audioSession.isAudioEnabled = false
            if let previousUseManualAudio {
                audioSession.useManualAudio = previousUseManualAudio
                self.previousUseManualAudio = nil
            }
        }
#endif
    }

    public func release() {
        stopLocalMedia()
        peerSlots.forEach { $0.closePeerConnection() }
        peerSlots.removeAll()
    }

    public func collectLocalAudioLevel(_ onComplete: @escaping @Sendable (Float?) -> Void) {
        guard let audioPipelinePrimer else {
            onComplete(nil)
            return
        }
        audioPipelinePrimer.collectAudioLevel(onComplete)
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
        onRenegotiationNeeded: @escaping (String) -> Void,
        supportsIndependentContentVideo: Bool,
        isOfferOwner: @escaping () -> Bool
    ) -> (any PeerConnectionSlotProtocol)? {
#if canImport(WebRTC)
        guard let peerConnectionFactory else { return nil }
        // Defense in depth: a slot is only independent-routed when the local
        // build flag is on too (the session already ANDs these, but keep the
        // engine authoritative so flag-off is byte-identical).
        let independentRouted = enableIndependentContentVideo && supportsIndependentContentVideo
        let slot = PeerConnectionSlot(
            remoteCid: remoteCid,
            factory: peerConnectionFactory,
            iceServers: iceServers,
            localAudioTrack: localAudioTrack,
            localVideoTrack: localVideoTrack,
            videoReceiveEnabled: videoMediaEnabled,
            supportsIndependentContentVideo: independentRouted,
            isOfferOwner: isOfferOwner,
            onLocalIceCandidate: onLocalIceCandidate,
            onRemoteVideoTrack: { remoteCid, track in
                onRemoteVideoTrack(remoteCid, track)
            },
            onConnectionStateChange: onConnectionStateChange,
            onIceConnectionStateChange: onIceConnectionStateChange,
            onSignalingStateChange: onSignalingStateChange,
            onRenegotiationNeeded: onRenegotiationNeeded,
            logger: logger
        )
        peerSlots.append(slot)
        // A peer created mid-share must pick up the active content: capable peers
        // via the content sender / pending-track mechanism, legacy peers via the
        // single-sender swap (pitfall #5). attachLocalTracksToSlot routes both.
        if localAudioTrack != nil || localVideoTrack != nil || localContentVideoTrack != nil {
            attachLocalTracksToSlot(slot)
        }
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

    /// Restarts the audio unit by bouncing `RTCAudioSession.isAudioEnabled`, mirroring the
    /// media-services-reset recovery in `DefaultAudioCoordinator`. Needed after a same-app audio
    /// owner held and released the session: that takeover posts no interruption notification, so
    /// WebRTC never restarts the unit on its own. No-op when local media is not running.
    public func restartAudioUnit() {
#if canImport(WebRTC)
        guard localAudioTrack != nil else { return }
        let audioSession = RTCAudioSession.sharedInstance()
        audioSession.lockForConfiguration()
        defer { audioSession.unlockForConfiguration() }
        audioSession.isAudioEnabled = false
        audioSession.isAudioEnabled = true
#endif
    }

    @discardableResult
    public func toggleVideo(_ enabled: Bool) -> Bool {
#if canImport(WebRTC)
        guard videoMediaEnabled else {
            localVideoTrack?.isEnabled = false
            return false
        }
        // Gate the screen-share special-cases on `isLegacyScreenSharing`, NOT raw
        // `isScreenSharing`. During an INDEPENDENT share the screen rides a
        // separate content track, so the camera must toggle normally: turning ON
        // from OFF still restarts capture (otherwise we'd report enabled with no
        // camera capturer) and turning OFF still stops the capturer (otherwise it
        // keeps running). In the LEGACY path `isLegacyScreenSharing == isScreenSharing`
        // (the flag is off), so this is byte-identical. Matches Android, which
        // gates camera ops on isLegacyScreenSharing.
        if enabled && cameraController.availableCameraModes.isEmpty && !isLegacyScreenSharing {
            localVideoTrack?.isEnabled = false
            return false
        }
        if enabled && !cameraController.hasActiveCapturer() && !isLegacyScreenSharing {
            let started = cameraController.restartVideoCapturerFromAvailableModes()
            if !started {
                localVideoTrack?.isEnabled = false
                return false
            }
        }
        if !enabled && !isLegacyScreenSharing {
            cameraController.stopAllCapturers()
        }
        let effectiveEnabled = enabled && (cameraController.hasActiveCapturer() || isLegacyScreenSharing)
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
        guard videoMediaEnabled else {
            onComplete?(false)
            return false
        }
#if canImport(WebRTC)
        guard enableIndependentContentVideo else {
            // Legacy single-video path: the controller repurposes the camera
            // source/track. Byte-identical to today.
            return screenShareController.startScreenShare(onComplete: onComplete)
        }
        // Independent path: create the content source/track first (also the
        // pending track) so the controller's BroadcastFrameReader delegates to
        // the CONTENT source. The camera path is untouched.
        let createdContentTrack = localContentVideoTrack == nil
        _ = ensureLocalContentVideoTrack()
        return screenShareController.startScreenShare { [weak self] started in
            Task { @MainActor in
                guard let self else {
                    onComplete?(started)
                    return
                }
                if started {
                    // Per-peer attach: capable peers get the content track on
                    // their content sender (or pending until bound); legacy peers
                    // get it swapped onto the single video sender (pitfall #5/#7).
                    self.peerSlots.forEach { self.attachLocalTracksToSlot($0) }
                } else if createdContentTrack {
                    self.disposeLocalContentVideoTrack()
                }
                onComplete?(started)
            }
        }
#else
        return screenShareController.startScreenShare(onComplete: onComplete)
#endif
    }

    public func stopScreenShare() -> Bool {
        // Shared idempotent stop path. The controller's stop fires
        // onStateChanged(false), which runs tearDownContentAndDetach() in
        // independent mode (detach peers + dispose the content track). A second
        // entry via external broadcast termination is a no-op (controller latch).
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

    public func attachLocalContentRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        localContentRenderers.append(WeakAnyBox(value: renderer))
        localContentRenderers.removeAll { $0.value == nil }
        if let renderer = renderer as? RTCVideoRenderer {
            let track = localContentVideoTrack
            rendererAttachmentQueue.async {
                track?.add(renderer)
            }
        }
#endif
    }

    public func detachLocalContentRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        if let renderer = renderer as? RTCVideoRenderer {
            let track = localContentVideoTrack
            rendererAttachmentQueue.async {
                track?.remove(renderer)
            }
        }
        localContentRenderers.removeAll { $0.value === renderer || $0.value == nil }
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
