import CoreGraphics
import Foundation
@testable import SerenadaCore
#if canImport(WebRTC)
import WebRTC
#endif

@MainActor
final class FakeMediaEngine: SessionMediaEngine {
    private(set) var startLocalMediaCalls: [Bool] = []
    private(set) var releaseCalls = 0
    private(set) var toggleAudioCalls: [Bool] = []
    private(set) var restartAudioUnitCalls = 0
    private(set) var toggleVideoCalls: [Bool] = []
    private(set) var iceServersSet = false
    private(set) var createdSlotCids: [String] = []
    private(set) var removedSlots: [any PeerConnectionSlotProtocol] = []
    private(set) var fakeSlots: [String: FakePeerConnectionSlot] = [:]
    var failNextCreatedSlotRemoteOffer = false

    private var _iceServers: [IceServerConfig]?
    private var onCameraFacingChanged: ((Bool) -> Void)?
    private var onCameraModeChanged: ((LocalCameraMode) -> Void)?
    private var onFlashlightStateChanged: ((Bool, Bool) -> Void)?
    private var onScreenShareStopped: (() -> Void)?
    private var onZoomFactorChanged: ((Double) -> Void)?
    private var onFeatureDegradation: ((FeatureDegradationState) -> Void)?

    /// Faithful model of local capture-track presence, mirroring WebRtcEngine's
    /// `localAudioTrack`/`localVideoTrack`. Hold releases them; resume reacquires
    /// per desired intent (so a muted/camera-off resume leaves them nil); the
    /// enabling toggles recreate them when missing (the P5 ensure-track fix).
    private(set) var hasLocalAudioTrack = false
    private(set) var hasLocalVideoTrack = false
    /// Count of toggle-driven track RECREATIONS (the resume-then-enable repair),
    /// distinct from the initial acquire / resume acquire. Lets a regression test
    /// assert that a normal unmute with an existing track recreates NOTHING.
    private(set) var toggleAudioTrackRecreations = 0
    private(set) var toggleVideoTrackRecreations = 0

    /// Test seam: simulate a foreground call that has lost its video track (e.g. a
    /// camera-off resume followed by a desired-video-on toggle), so a subsequent
    /// `toggleVideo(true)` must recreate it. Counts as no recreation itself.
    func dropLocalVideoTrackForTesting() { hasLocalVideoTrack = false }

    func startLocalMedia(preferVideo: Bool) {
        startLocalMediaCalls.append(preferVideo)
        // Initial acquire: mic always, camera when preferVideo (mirrors the real
        // engine's startLocalMedia).
        hasLocalAudioTrack = true
        hasLocalVideoTrack = preferVideo
    }

    func release() {
        releaseCalls += 1
        hasLocalAudioTrack = false
        hasLocalVideoTrack = false
    }

    private(set) var suspendLocalMediaForHoldCalls = 0
    private(set) var resumeLocalMediaFromHoldCalls: [(audioEnabled: Bool, videoMode: LocalCameraMode?)] = []
    private(set) var setRemotePlaybackEnabledCalls: [Bool] = []
    private(set) var detachRenderersForHoldCalls = 0

    func suspendLocalMediaForHold() {
        suspendLocalMediaForHoldCalls += 1
        // Mirror the real engine: hold RELEASES the mic + camera tracks.
        hasLocalAudioTrack = false
        hasLocalVideoTrack = false
        // Mirror the real engine: hold deafens remote playout. This also sets the
        // sticky flag so slots created later inherit the deafen.
        setRemotePlaybackEnabled(false)
    }

    func resumeLocalMediaFromHold(audioEnabled: Bool, videoMode: LocalCameraMode?) {
        resumeLocalMediaFromHoldCalls.append((audioEnabled: audioEnabled, videoMode: videoMode))
        // Mirror the real engine: resume reacquires per DESIRED intent only — a
        // muted resume leaves the audio track nil; a camera-off resume leaves the
        // video track nil. This is exactly the state the P5 toggle fix repairs.
        if audioEnabled { hasLocalAudioTrack = true }
        if videoMode != nil { hasLocalVideoTrack = true }
        // Mirror the real engine: resume re-enables remote playout.
        setRemotePlaybackEnabled(true)
    }

    /// Sticky deafen state, mirroring the real engine: held sessions set this
    /// `false` and a slot created AFTER hold must inherit it. Defaults enabled.
    private(set) var remotePlaybackEnabled = true
    func setRemotePlaybackEnabled(_ enabled: Bool) {
        remotePlaybackEnabled = enabled
        setRemotePlaybackEnabledCalls.append(enabled)
        fakeSlots.values.forEach { $0.setRemotePlaybackEnabled(enabled) }
    }

    func detachRenderersForHold() {
        detachRenderersForHoldCalls += 1
    }

    @discardableResult
    func toggleAudio(_ enabled: Bool) -> Bool {
        toggleAudioCalls.append(enabled)
        // P5 ensure-track: enabling with no audio track recreates it (resume-then-
        // unmute repair). When a track already exists this recreates NOTHING — the
        // single-call mute/unmute regression case. Returns the effective,
        // track-backed state.
        if enabled, !hasLocalAudioTrack {
            hasLocalAudioTrack = true
            toggleAudioTrackRecreations += 1
        }
        return enabled && hasLocalAudioTrack
    }

    func restartAudioUnit() {
        restartAudioUnitCalls += 1
    }

    @discardableResult
    func toggleVideo(_ enabled: Bool) -> Bool {
        toggleVideoCalls.append(enabled)
        // P5 ensure-track: enabling with no video track recreates it (resume-then-
        // video-on repair). When a track already exists this recreates NOTHING.
        if enabled, !hasLocalVideoTrack {
            hasLocalVideoTrack = true
            toggleVideoTrackRecreations += 1
        }
        return enabled && hasLocalVideoTrack
    }

    func flipCamera() {}
    func setHdVideoExperimentalEnabled(_ enabled: Bool) {}
    @discardableResult func toggleFlashlight() -> Bool { false }
    private(set) var startScreenShareCalls = 0
    private(set) var stopScreenShareCalls = 0
    /// Drives the result delivered to `startScreenShare`'s sync return AND the
    /// `onComplete` callback, so session-level tests can exercise both confirmed
    /// start (content_state broadcast) and cancellation/failure branches.
    var startScreenShareResult = false
    /// When true, model the broadcast PENDING window: return `true`
    /// (request accepted, reader listening) WITHOUT firing `onComplete`, so the
    /// share is not yet confirmed. A subsequent `stopScreenShare` then fires
    /// `onScreenShareStopped` (cancel the pending start) like the real controller.
    var deferStartScreenShareCompletion = false
    private var deferredStartScreenShareCompletion: ((Bool) -> Void)?
    func startScreenShare(onComplete: ((Bool) -> Void)?) -> Bool {
        startScreenShareCalls += 1
        if deferStartScreenShareCompletion {
            deferredStartScreenShareCompletion = onComplete
            return true
        }
        onComplete?(startScreenShareResult)
        return startScreenShareResult
    }
    func completeDeferredStartScreenShare(started: Bool) {
        let completion = deferredStartScreenShareCompletion
        deferredStartScreenShareCompletion = nil
        completion?(started)
    }
    func stopScreenShare() -> Bool {
        stopScreenShareCalls += 1
        // Mirror the real controller: drive the session's screen-share-stopped
        // callback so the session-side stop signaling path runs in tests.
        onScreenShareStopped?()
        return true
    }
    private(set) var adjustCaptureZoomCalls: [CGFloat] = []
    var adjustCaptureZoomResult: Double? = 1.25
    @discardableResult func adjustCaptureZoom(by scaleDelta: CGFloat) -> Double? {
        adjustCaptureZoomCalls.append(scaleDelta)
        return adjustCaptureZoomResult
    }
    @discardableResult func resetCaptureZoom() -> Double { 1.0 }

    func setIceServers(_ servers: [IceServerConfig]) {
        _iceServers = servers
        iceServersSet = true
        fakeSlots.values.forEach { $0.setIceServers(servers) }
    }

    func hasIceServers() -> Bool { _iceServers != nil }

    /// Per-cid record of the independent-content gate + offer-owner resolution
    /// each slot was created with, so routing tests can assert on them.
    private(set) var createdSlotSupportsIndependentContentVideo: [String: Bool] = [:]
    private(set) var createdSlotIsOfferOwner: [String: Bool] = [:]
    func createSlot(
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
        createdSlotCids.append(remoteCid)
        createdSlotSupportsIndependentContentVideo[remoteCid] = supportsIndependentContentVideo
        createdSlotIsOfferOwner[remoteCid] = isOfferOwner()
        let slot = FakePeerConnectionSlot(
            remoteCid: remoteCid,
            supportsIndependentContentVideo: supportsIndependentContentVideo,
            onConnectionStateChange: onConnectionStateChange,
            onIceConnectionStateChange: onIceConnectionStateChange,
            onSignalingStateChange: onSignalingStateChange,
            onRenegotiationNeeded: onRenegotiationNeeded
        )
        if failNextCreatedSlotRemoteOffer {
            slot.failNextRemoteOffer = true
            failNextCreatedSlotRemoteOffer = false
        }
        fakeSlots[remoteCid] = slot
        // Sticky deafen: a slot created while the session is held inherits the
        // disabled remote playback (mirrors WebRtcEngine.createSlot).
        if !remotePlaybackEnabled {
            slot.setRemotePlaybackEnabled(false)
        }
        if let iceServers = _iceServers {
            slot.setIceServers(iceServers)
        }
        return slot
    }

    func removeSlot(_ slot: any PeerConnectionSlotProtocol) {
        removedSlots.append(slot)
    }

    private(set) var attachLocalRendererCalls: [AnyObject] = []
    private(set) var detachLocalRendererCalls: [AnyObject] = []
    func attachLocalRenderer(_ renderer: AnyObject) {
        attachLocalRendererCalls.append(renderer)
    }
    func detachLocalRenderer(_ renderer: AnyObject) {
        detachLocalRendererCalls.append(renderer)
    }

    private(set) var attachLocalContentRendererCalls: [AnyObject] = []
    private(set) var detachLocalContentRendererCalls: [AnyObject] = []
    func attachLocalContentRenderer(_ renderer: AnyObject) {
        attachLocalContentRendererCalls.append(renderer)
    }
    func detachLocalContentRenderer(_ renderer: AnyObject) {
        detachLocalContentRendererCalls.append(renderer)
    }

    func setOnCameraFacingChanged(_ handler: @escaping (Bool) -> Void) {
        onCameraFacingChanged = handler
    }
    func setOnCameraModeChanged(_ handler: @escaping (LocalCameraMode) -> Void) {
        onCameraModeChanged = handler
    }
    func setOnFlashlightStateChanged(_ handler: @escaping (Bool, Bool) -> Void) {
        onFlashlightStateChanged = handler
    }
    func setOnScreenShareStopped(_ handler: @escaping () -> Void) {
        onScreenShareStopped = handler
    }
    func setOnZoomFactorChanged(_ handler: @escaping (Double) -> Void) {
        onZoomFactorChanged = handler
    }
    func setOnFeatureDegradation(_ handler: @escaping (FeatureDegradationState) -> Void) {
        onFeatureDegradation = handler
    }

    var nextLocalAudioLevel: Float?
    func collectLocalAudioLevel(_ onComplete: @escaping @Sendable (Float?) -> Void) {
        onComplete(nextLocalAudioLevel)
    }
}
