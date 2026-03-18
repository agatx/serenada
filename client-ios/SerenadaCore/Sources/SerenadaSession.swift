import Combine
import Foundation

@MainActor
public final class SerenadaSession: ObservableObject {
    @Published public private(set) var state = CallState()
    @Published public private(set) var callStats = CallStats()

    public let roomId: String
    public let roomUrl: URL?

    let signalingClient: SignalingClient
    let webRtcEngine: WebRtcEngine
    let callAudioSessionController: CallAudioSessionController
    let apiClient: CoreAPIClient
    public let serverHost: String

    public init(
        roomId: String,
        roomUrl: URL? = nil,
        serverHost: String,
        config: SerenadaConfig
    ) {
        self.roomId = roomId
        self.roomUrl = roomUrl
        self.serverHost = serverHost
        self.signalingClient = SignalingClient(forceSseSignaling: !config.transports.contains(.ws))
        self.webRtcEngine = WebRtcEngine(
            onCameraFacingChanged: { _ in },
            onCameraModeChanged: { _ in },
            onFlashlightStateChanged: { _, _ in },
            onScreenShareStopped: {},
            onZoomFactorChanged: { _ in },
            onDebugTrace: { _ in },
            isHdVideoExperimentalEnabled: false
        )
        self.callAudioSessionController = CallAudioSessionController(
            onProximityChanged: { _ in },
            onAudioEnvironmentChanged: {}
        )
        self.apiClient = CoreAPIClient()
        updateState { $0.roomId = roomId }
        updateState { $0.roomUrl = roomUrl }
    }

    // MARK: - Public API

    public func leave() {
        // Will be wired to signaling when logic migrates from CallManager
    }

    public func end() {
        leave()
    }

    public func toggleAudio() {
        let enabled = !state.localParticipant.audioEnabled
        webRtcEngine.toggleAudio(enabled)
        updateState { $0.localParticipant.audioEnabled = enabled }
    }

    public func toggleVideo() {
        let enabled = !state.localParticipant.videoEnabled
        _ = webRtcEngine.toggleVideo(enabled)
        updateState { $0.localParticipant.videoEnabled = enabled }
    }

    public func flipCamera() {
        webRtcEngine.flipCamera()
    }

    public func setCameraMode(_ mode: LocalCameraMode) {
        updateState { $0.localParticipant.cameraMode = mode }
    }

    public func setAudioEnabled(_ enabled: Bool) {
        webRtcEngine.toggleAudio(enabled)
        updateState { $0.localParticipant.audioEnabled = enabled }
    }

    public func setVideoEnabled(_ enabled: Bool) {
        _ = webRtcEngine.toggleVideo(enabled)
        updateState { $0.localParticipant.videoEnabled = enabled }
    }

    public func startScreenShare() {
        _ = webRtcEngine.startScreenShare(onComplete: nil)
    }

    public func stopScreenShare() {
        _ = webRtcEngine.stopScreenShare()
    }

    public func resumeJoin() {
        // Resume after permissions granted
        updateState { $0.phase = .joining }
    }

    public func cancelJoin() {
        updateState { $0.phase = .idle }
    }

    public func attachLocalRenderer(_ renderer: AnyObject) {
        webRtcEngine.attachLocalRenderer(renderer)
    }

    public func detachLocalRenderer(_ renderer: AnyObject) {
        webRtcEngine.detachLocalRenderer(renderer)
    }

    public func attachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String) {
        // Delegate to peer slot when available
    }

    // MARK: - Internal

    func updateState(_ mutate: (inout CallState) -> Void) {
        var next = state
        mutate(&next)
        state = next
    }
}
