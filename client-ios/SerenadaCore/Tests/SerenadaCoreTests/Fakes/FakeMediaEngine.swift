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
    private(set) var toggleVideoCalls: [Bool] = []
    private(set) var iceServersSet = false
    private(set) var createdSlotCids: [String] = []
    private(set) var removedSlots: [any PeerConnectionSlotProtocol] = []
    private(set) var fakeSlots: [String: FakePeerConnectionSlot] = [:]

    private var _iceServers: [IceServerConfig]?
    private var onCameraFacingChanged: ((Bool) -> Void)?
    private var onCameraModeChanged: ((LocalCameraMode) -> Void)?
    private var onFlashlightStateChanged: ((Bool, Bool) -> Void)?
    private var onScreenShareStopped: (() -> Void)?
    private var onZoomFactorChanged: ((Double) -> Void)?
    private var onFeatureDegradation: ((FeatureDegradationState) -> Void)?

    func startLocalMedia(preferVideo: Bool) {
        startLocalMediaCalls.append(preferVideo)
    }

    func release() { releaseCalls += 1 }

    func toggleAudio(_ enabled: Bool) {
        toggleAudioCalls.append(enabled)
    }

    @discardableResult
    func toggleVideo(_ enabled: Bool) -> Bool {
        toggleVideoCalls.append(enabled)
        return enabled
    }

    func flipCamera() {}
    func setHdVideoExperimentalEnabled(_ enabled: Bool) {}
    @discardableResult func toggleFlashlight() -> Bool { false }
    func startScreenShare(onComplete: ((Bool) -> Void)?) -> Bool { false }
    func stopScreenShare() -> Bool { false }
    @discardableResult func adjustCaptureZoom(by scaleDelta: CGFloat) -> Double? { nil }
    @discardableResult func resetCaptureZoom() -> Double { 1.0 }

    func setIceServers(_ servers: [IceServerConfig]) {
        _iceServers = servers
        iceServersSet = true
    }

    func hasIceServers() -> Bool { _iceServers != nil }

    func createSlot(
        remoteCid: String,
        onLocalIceCandidate: @escaping (String, IceCandidatePayload) -> Void,
        onRemoteVideoTrack: @escaping (String, AnyObject?) -> Void,
        onConnectionStateChange: @escaping (String, String) -> Void,
        onIceConnectionStateChange: @escaping (String, String) -> Void,
        onSignalingStateChange: @escaping (String, String) -> Void,
        onRenegotiationNeeded: @escaping (String) -> Void
    ) -> (any PeerConnectionSlotProtocol)? {
        createdSlotCids.append(remoteCid)
        let slot = FakePeerConnectionSlot(
            remoteCid: remoteCid,
            onConnectionStateChange: onConnectionStateChange,
            onIceConnectionStateChange: onIceConnectionStateChange,
            onSignalingStateChange: onSignalingStateChange
        )
        fakeSlots[remoteCid] = slot
        return slot
    }

    func removeSlot(_ slot: any PeerConnectionSlotProtocol) {
        removedSlots.append(slot)
    }

    func attachLocalRenderer(_ renderer: AnyObject) {}
    func detachLocalRenderer(_ renderer: AnyObject) {}

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
}
