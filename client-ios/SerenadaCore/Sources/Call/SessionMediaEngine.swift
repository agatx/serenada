import CoreGraphics
import Foundation

@MainActor
protocol SessionMediaEngine: AnyObject {
    func startLocalMedia(preferVideo: Bool)
    /// Enter held-senders mode (multi-call held join, contract §5 / Core
    /// Invariant 3): mark the engine so any peer slot created during negotiation
    /// materializes SEND-capable (`.sendRecv`) audio + legacy-video transceivers
    /// carrying NIL tracks, and promote any already-existing slots' senders to
    /// send-capable. No capture is acquired (the OS never reports a held session
    /// capturing), so a later `resumeLocalMediaFromHold` attaches fresh tracks to
    /// these stable senders with NO SDP renegotiation. Cleared by `startLocalMedia`.
    func createSendersForHold()
    func release()
    /// Suspend local foreground media for a HELD role: stop screen share, stop
    /// the camera capturer, and RELEASE mic capture by replacing the audio
    /// sender track with `nil` (not merely `isEnabled = false`, so the OS stops
    /// reporting capture). Also deafens remote audio playout. Peer-connection
    /// identity and the stable senders are preserved so a later resume can
    /// reattach fresh tracks without renegotiation. Idempotent.
    func suspendLocalMediaForHold()
    /// Resume local foreground media after a hold: reacquire mic capture if
    /// `audioEnabled`, restart the camera if `videoMode != nil` (off), attach the
    /// fresh tracks to the existing senders, and re-enable remote audio playout.
    /// `videoMode == nil` means video stays off. Idempotent.
    func resumeLocalMediaFromHold(audioEnabled: Bool, videoMode: LocalCameraMode?)
    /// Gate audible remote playout independently of the volume duck. Held
    /// sessions set this `false` (a real `RTCAudioTrack.isEnabled = false`
    /// deafen on remote receivers); resume sets it `true`.
    func setRemotePlaybackEnabled(_ enabled: Bool)
    /// Detach/pause the registered local renderers for a hold so a held call's
    /// preview surfaces stop receiving frames.
    func detachRenderersForHold()
    /// Toggle mic publication. When ENABLING with no live audio track (a held
    /// call resumed muted owns none), the engine recreates + attaches the mic
    /// track before reporting enabled. Returns the EFFECTIVE, track-backed state
    /// (true only when a track exists and is enabled); the session publishes
    /// exactly this. When a track already exists this only flips `isEnabled`.
    @discardableResult func toggleAudio(_ enabled: Bool) -> Bool
    /// Restart the audio unit after the host re-activated the audio session that a same-app owner
    /// (no interruption notification) held and released. See ``AudioCoordinatorEvent/audioSessionRestarted``.
    func restartAudioUnit()
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
        onRenegotiationNeeded: @escaping (String) -> Void,
        supportsIndependentContentVideo: Bool,
        isOfferOwner: @escaping () -> Bool
    ) -> (any PeerConnectionSlotProtocol)?
    func removeSlot(_ slot: any PeerConnectionSlotProtocol)
    func attachLocalRenderer(_ renderer: AnyObject)
    func detachLocalRenderer(_ renderer: AnyObject)
    /// Attach a renderer to the LOCAL content (screen share) track for local
    /// preview. No-op until an independent share is active. Camera preview
    /// continues to use ``attachLocalRenderer(_:)``.
    func attachLocalContentRenderer(_ renderer: AnyObject)
    func detachLocalContentRenderer(_ renderer: AnyObject)
    func setOnCameraFacingChanged(_ handler: @escaping (Bool) -> Void)
    func setOnCameraModeChanged(_ handler: @escaping (LocalCameraMode) -> Void)
    func setOnFlashlightStateChanged(_ handler: @escaping (Bool, Bool) -> Void)
    func setOnScreenShareStopped(_ handler: @escaping () -> Void)
    func setOnZoomFactorChanged(_ handler: @escaping (Double) -> Void)
    func setOnFeatureDegradation(_ handler: @escaping (FeatureDegradationState) -> Void)
    /// Asynchronously fetches the local audio level from WebRTC's
    /// `media-source.audioLevel` stat. The implementation keeps a primer
    /// peer connection alive so this stat is available even before any real
    /// peer joins. Result is in [0, 1] or `nil` if the stat isn't ready.
    func collectLocalAudioLevel(_ onComplete: @escaping @Sendable (Float?) -> Void)
}
