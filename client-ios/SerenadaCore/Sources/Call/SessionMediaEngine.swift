import CoreGraphics
import Foundation

@MainActor
protocol SessionMediaEngine: AnyObject {
    func startLocalMedia(preferVideo: Bool)
    func release()
    func toggleAudio(_ enabled: Bool)
    @discardableResult func toggleVideo(_ enabled: Bool) -> Bool
    func flipCamera()
    func setHdVideoExperimentalEnabled(_ enabled: Bool)
    @discardableResult func toggleFlashlight() -> Bool
    func startScreenShare(onComplete: ((Bool) -> Void)?) -> Bool
    func stopScreenShare() -> Bool
    @discardableResult func adjustCaptureZoom(by scaleDelta: CGFloat) -> Double?
    @discardableResult func resetCaptureZoom() -> Double
    func setIceServers(_ servers: [IceServerConfig])
    func hasIceServers() -> Bool
    func createSlot(
        remoteCid: String,
        onLocalIceCandidate: @escaping (String, IceCandidatePayload) -> Void,
        onRemoteVideoTrack: @escaping (String, AnyObject?) -> Void,
        onConnectionStateChange: @escaping (String, String) -> Void,
        onIceConnectionStateChange: @escaping (String, String) -> Void,
        onSignalingStateChange: @escaping (String, String) -> Void,
        onRenegotiationNeeded: @escaping (String) -> Void
    ) -> (any PeerConnectionSlotProtocol)?
    func removeSlot(_ slot: any PeerConnectionSlotProtocol)
    func attachLocalRenderer(_ renderer: AnyObject)
    func detachLocalRenderer(_ renderer: AnyObject)
    func setOnCameraFacingChanged(_ handler: @escaping (Bool) -> Void)
    func setOnCameraModeChanged(_ handler: @escaping (LocalCameraMode) -> Void)
    func setOnFlashlightStateChanged(_ handler: @escaping (Bool, Bool) -> Void)
    func setOnScreenShareStopped(_ handler: @escaping () -> Void)
    func setOnZoomFactorChanged(_ handler: @escaping (Double) -> Void)
    func setOnFeatureDegradation(_ handler: @escaping (FeatureDegradationState) -> Void)
}
