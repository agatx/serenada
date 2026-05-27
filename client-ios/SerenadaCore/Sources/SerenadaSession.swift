import AVFoundation
import Combine
import CoreGraphics
import Foundation
import Network
import UIKit

struct JoinRecoveryState: Equatable {
    let phase: CallPhase
    let participantCount: Int
}

private struct AudioCoordinatorTimeoutError: LocalizedError {
    var errorDescription: String? {
        "Audio coordinator operation timed out"
    }
}

func resolveJoinRecoveryState(
    currentPhase: CallPhase,
    participantHint: Int?,
    preferInCall: Bool
) -> JoinRecoveryState? {
    guard currentPhase == .joining else { return nil }
    let normalizedHint = max(1, participantHint ?? 1)
    if preferInCall { return JoinRecoveryState(phase: .inCall, participantCount: max(2, normalizedHint)) }
    if normalizedHint > 1 { return JoinRecoveryState(phase: .inCall, participantCount: normalizedHint) }
    return JoinRecoveryState(phase: .waiting, participantCount: 1)
}

private final class SignalingProviderDelegateProxy: SignalingProviderDelegate {
    weak var session: SerenadaSession?

    init(session: SerenadaSession? = nil) {
        self.session = session
    }

    func signalingProviderDidConnect(_ info: ConnectionInfo) {
        Task { @MainActor [weak session] in
            session?.handleProviderConnected(info)
        }
    }

    func signalingProviderDidDisconnect(reason: String?) {
        Task { @MainActor [weak session] in
            session?.handleProviderDisconnected(reason: reason)
        }
    }

    func signalingProviderDidJoin(_ event: JoinedEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderJoined(event)
        }
    }

    func signalingProviderDidUpdateRoomState(_ event: RoomStateEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderRoomStateUpdated(event)
        }
    }

    func signalingProviderDidJoinPeer(_ event: PeerEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderPeerJoined(event)
        }
    }

    func signalingProviderDidLeavePeer(_ event: PeerEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderPeerLeft(event)
        }
    }

    func signalingProviderDidReceiveMessage(_ message: PeerMessage) {
        Task { @MainActor [weak session] in
            session?.handleProviderMessage(message)
        }
    }

    func signalingProviderDidEndRoom(_ event: RoomEndedEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderRoomEnded(event)
        }
    }

    func signalingProviderDidReceiveError(_ event: ErrorEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderError(event)
        }
    }

    func signalingProviderDidChangeIceServers(_ iceServers: [IceServerConfig]) {
        Task { @MainActor [weak session] in
            session?.handleProviderIceServersChanged(iceServers)
        }
    }

    func signalingProviderDidReceiveNegotiationDirty(_ event: NegotiationDirtyEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderNegotiationDirty(event)
        }
    }

    func signalingProviderDidReceiveRelayFailed(_ event: RelayFailedEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderRelayFailed(event)
        }
    }

    func signalingProviderDidRefreshReconnectToken(_ event: ReconnectTokenRefreshedEvent) {
        Task { @MainActor [weak session] in
            session?.handleProviderReconnectTokenRefreshed(event)
        }
    }
}

/// Represents an active call session. Created via ``SerenadaCore/join(url:displayName:peerId:)`` or ``SerenadaCore/createRoom()``.
/// Publishes state via `@Published` properties for SwiftUI integration.
@MainActor
public final class SerenadaSession: ObservableObject {
    /// Current call state. Observe with SwiftUI or Combine for UI updates.
    @Published public private(set) var state = CallState()
    /// Real-time connection diagnostics.
    @Published public private(set) var diagnostics = CallDiagnostics()

    /// Audio routes currently published by the active coordinator.
    @Published public private(set) var availableAudioDevices: [AudioDevice] = []
    /// Current selected or active output route, or nil when no route is available yet.
    @Published public private(set) var currentAudioDevice: AudioDevice?
    /// Whether the microphone is effectively muted by user action, external audio, or missing input.
    @Published public private(set) var isMicMuted: Bool = false
    /// Whether the microphone is muted specifically because external audio, such as push-to-talk, is active.
    @Published public private(set) var isMicMutedByExternalAudio: Bool = false

    /// Room identifier for this session.
    public let roomId: String
    /// Full URL for this room, if available.
    public let roomUrl: URL?
    /// Server host used for signaling. `nil` when the session was configured with a custom
    /// ``SignalingProvider`` and no Serenada-hosted server.
    public var serverHost: String? {
        resolvedConfig.serverHost
    }
    /// Bundle ID for the broadcast upload extension used in screen sharing.
    public var screenShareExtensionBundleId: String? {
        #if BROADCAST_EXTENSION
        BroadcastShared.extensionBundleId
        #else
        nil
        #endif
    }

    /// Callback invoked when camera/microphone permissions are needed before joining.
    public var onPermissionsRequired: (([MediaCapability]) -> Void)?

    // Core dependencies
    private let signalingProvider: SignalingProvider
    private let providerDelegateProxy: SignalingProviderDelegateProxy
    private let webRtcEngine: SessionMediaEngine
    private let callAudioSessionController: SessionAudioController
    private let audioCoordinator: SerenadaAudioCoordinator
    private var audioCoordinatorLifecycleTask: Task<Void, Error>?
    private var joinLifecycleTask: Task<Void, Never>?
    private var coordinatorTasks: [Task<Void, Never>] = []
    private var userMuted = false
    private var externalAudioMuted = false
    private var playbackDuckingActive = false
    private var routeInputAvailable = true
    private var sessionActivated = false
    private var localMediaReadyForNegotiation = false
    // Latched true once the call's media has connected at least once. The network monitor
    // below only restarts ICE for an established call: NWPathMonitor delivers the current
    // path once at start(), and treating that initial callback as a network change would
    // otherwise force a redundant renegotiation during the first offer (a "pending-retry").
    private var hasEverConnectedPeer = false
    private let apiClient: SessionAPIClient
    private let clock: SessionClock
    private let config: SerenadaConfig
    private let resolvedConfig: ResolvedSerenadaConfig
    private let availableCameraModes: [LocalCameraMode]
    private let displayName: String?
    private let peerId: String?
    private let delegateProvider: (() -> SerenadaCoreDelegate?)?
    private let logger: SerenadaLogger?

    // Sub-engines
    private var signalingMessageRouter: SignalingMessageRouter?
    private var joinFlowCoordinator: JoinFlowCoordinator?
    private var peerNegotiationEngine: PeerNegotiationEngine?
    private var connectionStatusTracker: ConnectionStatusTracker?
    private var statsPoller: StatsPoller?
    private var audioLevelPoller: AudioLevelPoller?

    // Network
    private let pathMonitor = NWPathMonitor()
    private let pathMonitorQueue = DispatchQueue(label: "SerenadaSession.PathMonitor")

    // App lifecycle (foreground force-ping — see resilience #8)
    private var foregroundObserver: NSObjectProtocol?
    private var backgroundObserver: NSObjectProtocol?
    private var lastBackgroundedAtMs: Int64?

    // Session state
    private var internalPhase: CallPhase = .joining
    private var participantCount = 0
    private var currentRequiredPermissions: [MediaCapability]?
    private var currentError: CallError?
    private var clientId: String?
    private var hostCid: String?
    private var currentRoomState: RoomState?
    private var remoteMediaStates: [String: (audioEnabled: Bool?, videoEnabled: Bool?)] = [:]
    private var peerSlots: [String: any PeerConnectionSlotProtocol] = [:]
    private var pendingMessages: [SignalingMessage] = []
    private var pendingJoinRoom: String?
    private var joinAttemptSerial: Int64 = 0
    private var reconnectAttempts = 0
    private var reconnectCid: String?
    private var hasBegunJoin = false
    private var hasJoinSignalStartedForAttempt = false
    private var hasJoinAcknowledgedCurrentAttempt = false
    private var userPreferredVideoEnabled = true
    private var cameraPermissionRequestInFlight = false
    private var isVideoPausedByProximity = false
    private var reconnectRecoveryPending = false
    // True between transport reconnect and the first authoritative room_state
    // snapshot; gates ICE restart so it runs against a confirmed peer set.
    private var pendingPostReconnectResync = false
    private var postReconnectResyncTask: Task<Void, Never>?
    private var iceRestartCallsFromGate = 0
    private var iceFetchGeneration = 0
    private var reconnectTask: Task<Void, Never>?

    /// Test-only accessor for the post-reconnect snapshot gate state.
    internal var isPostReconnectResyncPending: Bool { pendingPostReconnectResync }
    /// Test-only counter incremented each time the gate fires an ICE restart.
    internal var postReconnectResyncFireCount: Int { iceRestartCallsFromGate }

    // Wall-clock ms when the local transport last dropped while a roomState
    // was present (i.e. mid-call). Cleared on reconnect.
    private var localSuspendedSinceMs: Int64?

    // After a remote peer transitions to suspended, we start a timer; on
    // expiry we flip `presumedLost=true` for that CID. Timers cancel when
    // the peer goes back to active or is removed from the room.
    private var suspendedPresentationTasks: [String: Task<Void, Never>] = [:]
    private var presumedLostRemoteCids: Set<String> = []

    /// Test-only count of remote CIDs currently flagged as `presumedLost`.
    internal var presumedLostRemoteCount: Int { presumedLostRemoteCids.count }

    // #3 — periodic `media_liveness` emission. Active across the in-call
    // window so the server can defer hard-eviction of suspended peers
    // whose media is still flowing locally. Ticks no-op while transport
    // is disconnected (baseline samples preserved so the next post-
    // reconnect tick can detect flow).
    private var lastInboundBytesByCid: [String: Int64] = [:]
    private var mediaLivenessTask: Task<Void, Never>?
    private var mediaLivenessEmitInFlight = false
    private var mediaLivenessEmitCount = 0
    private var outboundMediaWatchdogCancellable: AnyCancellable?

    /// Test-only counter incremented on each `media_liveness` broadcast.
    internal var mediaLivenessBroadcastCount: Int { mediaLivenessEmitCount }

    private let recoveryStorage: RecoveryStorage
    private var sessionStartTs: Int64?
    private var callStartedAtMs: Int64?

    public convenience init(
        roomId: String,
        roomUrl: URL? = nil,
        serverHost: String,
        config: SerenadaConfig,
        delegateProvider: (() -> SerenadaCoreDelegate?)? = nil,
        logger: SerenadaLogger? = nil,
        displayName: String? = nil,
        peerId: String? = nil,
        recoveryStorage: RecoveryStorage = RecoveryStorage()
    ) {
        let sessionConfig = config.signalingProvider == nil
            ? SerenadaConfig(
                serverHost: serverHost,
                signalingProvider: nil,
                defaultAudioEnabled: config.defaultAudioEnabled,
                defaultVideoEnabled: config.defaultVideoEnabled,
                cameraModes: config.cameraModes,
                transports: config.transports,
                proximityMonitoringEnabled: config.proximityMonitoringEnabled,
                audioCoordinator: config.audioCoordinator,
                audioIntent: config.audioIntent
            )
            : config
        self.init(
            roomId: roomId, roomUrl: roomUrl, config: sessionConfig,
            delegateProvider: delegateProvider, logger: logger,
            initialSignalingProvider: nil, signaling: nil, apiClient: nil, audioController: nil, mediaEngine: nil, clock: nil,
            displayName: displayName,
            peerId: peerId,
            recoveryStorage: recoveryStorage
        )
    }

    init(
        roomId: String,
        roomUrl: URL? = nil,
        config: SerenadaConfig,
        delegateProvider: (() -> SerenadaCoreDelegate?)? = nil,
        logger: SerenadaLogger? = nil,
        initialSignalingProvider: SignalingProvider? = nil,
        signaling: SessionSignaling? = nil,
        apiClient: SessionAPIClient? = nil,
        audioController: SessionAudioController? = nil,
        mediaEngine: SessionMediaEngine? = nil,
        clock: SessionClock? = nil,
        displayName: String? = nil,
        peerId: String? = nil,
        recoveryStorage: RecoveryStorage = RecoveryStorage()
    ) {
        self.recoveryStorage = recoveryStorage
        self.roomId = roomId
        self.roomUrl = roomUrl
        self.config = config
        self.availableCameraModes = SerenadaSession.resolveAvailableCameraModes(config.cameraModes)
        self.displayName = displayName
        self.peerId = peerId
        self.delegateProvider = delegateProvider
        self.logger = logger
        self.clock = clock ?? LiveSessionClock()
        self.apiClient = apiClient ?? CoreAPIClient()
        do {
            self.resolvedConfig = try resolveSerenadaConfig(config)
        } catch {
            preconditionFailure(error.localizedDescription)
        }
        if let initialSignalingProvider {
            self.signalingProvider = initialSignalingProvider
        } else if let signalingProvider = resolvedConfig.signalingProvider {
            self.signalingProvider = signalingProvider
        } else if let serverHost = resolvedConfig.serverHost {
            self.signalingProvider = SerenadaServerProvider(
                serverHost: serverHost,
                apiClient: self.apiClient,
                signaling: signaling,
                transports: config.transports,
                clock: self.clock,
                logger: logger
            )
        } else {
            preconditionFailure("Provide exactly one of serverHost or signalingProvider")
        }
        self.providerDelegateProxy = SignalingProviderDelegateProxy(session: nil)
        let defaultController = DefaultAudioCoordinator(
            proximityMonitoringEnabled: config.proximityMonitoringEnabled,
            onProximityChanged: { _ in }, onAudioEnvironmentChanged: {}, logger: logger
        )
        self.audioCoordinator = config.audioCoordinator ?? defaultController
        self.callAudioSessionController = audioController ?? (config.audioCoordinator.map { CustomAudioCoordinatorAdapter(coordinator: $0, proximityMonitoringEnabled: config.proximityMonitoringEnabled) } ?? defaultController)

        self.webRtcEngine = mediaEngine ?? WebRtcEngine(
            onCameraFacingChanged: { _ in }, onCameraModeChanged: { _ in },
            onFlashlightStateChanged: { _, _ in }, onScreenShareStopped: {},
            onZoomFactorChanged: { _ in }, onFeatureDegradation: { _ in },
            logger: logger, isHdVideoExperimentalEnabled: false,
            availableCameraModes: self.availableCameraModes
        )

        providerDelegateProxy.session = self
        signalingProvider.delegate = providerDelegateProxy
        configureRuntimeBridges()
        buildSubEngines()

        startCoordinatorTasks()

        // Skip periodic TURN refresh while every peer is on a direct ICE path —
        // the credentials go unused and the call survives arbitrary-length
        // signaling outages. A failover to relay triggers the next refresh.
        // Gate returns `true` to allow the refresh, so direct paths must
        // return `false` to skip; unknown (session deallocated) defaults to
        // refreshing to avoid silently dropping credentials.
        if let serverProvider = signalingProvider as? SerenadaServerProvider {
            serverProvider.setTurnRefreshGate { [weak self] in
                guard let self else { return true }
                return !self.arePeerPathsAllDirect()
            }
        }

        internalPhase = .joining
        let videoCaptureSupported = !self.availableCameraModes.isEmpty
        let initialCameraMode = self.availableCameraModes.first ?? .selfie
        commitSnapshot { s, _ in
            s.localParticipant.displayName = self.displayName
            s.localParticipant.audioEnabled = config.defaultAudioEnabled
            s.localParticipant.videoEnabled = videoCaptureSupported && config.defaultVideoEnabled
            s.localParticipant.cameraMode = initialCameraMode
            s.localParticipant.availableCameraModes = self.availableCameraModes
        }
        startNetworkMonitoring()

        joinLifecycleTask = Task { @MainActor [weak self] in
            await self?.beginJoinIfNeeded()
        }
    }

    private func startCoordinatorTasks() {
        guard coordinatorTasks.isEmpty else { return }
        let coordinator = self.audioCoordinator
        let availableDevicesTask = Task { @MainActor [weak self] in
            for await devices in coordinator.availableDevices {
                guard let self else { return }
                self.availableAudioDevices = devices
            }
        }

        let effectiveInputTask = Task { @MainActor [weak self] in
            for await device in coordinator.effectiveInputDevice {
                guard let self else { return }
                guard self.sessionActivated else { continue }
                self.routeInputAvailable = (device != nil)
                self.updateEffectiveMicState()
            }
        }

        let effectiveOutputTask = Task { @MainActor [weak self] in
            for await device in coordinator.effectiveOutputDevice {
                guard let self else { return }
                self.currentAudioDevice = device
            }
        }

        let eventsTask = Task { @MainActor [weak self] in
            for await event in coordinator.events {
                guard let self else { return }
                self.handleCoordinatorEvent(event)
            }
        }
        self.coordinatorTasks = [availableDevicesTask, effectiveInputTask, effectiveOutputTask, eventsTask]
    }

    private static func resolveAvailableCameraModes(_ configuredModes: [LocalCameraMode]?) -> [LocalCameraMode] {
        let normalizedModes = resolveCameraModes(configuredModes)
        guard normalizedModes.contains(.composite) else { return normalizedModes }
        return resolveCameraModes(
            configuredModes,
            compositeAvailable: CameraCaptureController.isCompositeCameraModeAvailable()
        )
    }

    deinit {
        pathMonitor.cancel()
        reconnectTask?.cancel()
        postReconnectResyncTask?.cancel()
        for task in suspendedPresentationTasks.values { task.cancel() }
        suspendedPresentationTasks.removeAll()
        mediaLivenessTask?.cancel()
        outboundMediaWatchdogCancellable?.cancel()
        joinLifecycleTask?.cancel()
        audioCoordinatorLifecycleTask?.cancel()
        coordinatorTasks.forEach { $0.cancel() }
        if let foregroundObserver { NotificationCenter.default.removeObserver(foregroundObserver) }
        if let backgroundObserver { NotificationCenter.default.removeObserver(backgroundObserver) }
    }

    // MARK: - Public API

    /// Leave the call gracefully. The other participant stays connected.
    public func leave() {
        guard state.phase != .idle else { return }
        if currentRoomState != nil || diagnostics.isSignalingConnected { signalingProvider.leaveRoom() }
        cleanupCall(reason: .localLeft, transitionToEnding: false)
    }

    /// End the call for all participants.
    public func end() {
        guard state.phase != .idle else { return }
        signalingProvider.endRoom()
        leave()
    }

    /// Toggle local audio on or off.
    public func toggleAudio() {
        setMicMuted(!userMuted)
    }

    /// Toggle local video on or off.
    public func toggleVideo() {
        guard !availableCameraModes.isEmpty else { return }
        setVideoEnabled(!state.localParticipant.videoEnabled, broadcastMediaState: true)
    }

    /// Cycle to the next camera mode (selfie -> world -> composite).
    public func flipCamera() {
        guard !diagnostics.isScreenSharing else { return }
        if state.localParticipant.cameraMode.isContentMode {
            signalingMessageRouter?.broadcastContentState(active: false)
        }
        webRtcEngine.flipCamera()
    }

    /// Set a specific camera mode. Ignored when `mode` is not in the configured list.
    public func setCameraMode(_ mode: LocalCameraMode) {
        guard mode != state.localParticipant.cameraMode else { return }
        guard availableCameraModes.contains(mode) else { return }
        for _ in 0..<availableCameraModes.count where state.localParticipant.cameraMode != mode { flipCamera() }
    }

    /// Set local audio enabled state.
    public func setAudioEnabled(_ enabled: Bool) {
        setMicMuted(!enabled)
    }

    /// Request routing to a coordinator-published audio device.
    ///
    /// The call is asynchronous; failures are logged and the current route is left unchanged.
    public func selectAudioDevice(_ device: AudioDevice) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                if let lifecycleTask = audioCoordinatorLifecycleTask {
                    try await lifecycleTask.value
                }
                guard sessionActivated else { return }
                try await audioCoordinator.applyRouting(device)
            } catch {
                logger?.log(.error, tag: "Audio", "Failed to apply routing to device \(device.displayName): \(error)")
            }
        }
    }

    /// Set the user-requested microphone mute state.
    ///
    /// The effective mute state may still be true when external audio is active or no input route is available.
    public func setMicMuted(_ muted: Bool) {
        self.userMuted = muted
        self.updateEffectiveMicState()
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                if let lifecycleTask = audioCoordinatorLifecycleTask {
                    try await lifecycleTask.value
                }
                guard sessionActivated else { return }
                try await audioCoordinator.setMicMuted(muted)
            } catch (let error) {
                logger?.log(.error, tag: "Audio", "Failed to set mic muted state on coordinator to \(muted): \(error)")
            }
        }
    }

    private func updateEffectiveMicState() {
        let effectiveEnabled = !userMuted && !externalAudioMuted && routeInputAvailable
        if sessionActivated {
            webRtcEngine.toggleAudio(effectiveEnabled)
        }
        commitSnapshot { s, _ in s.localParticipant.audioEnabled = effectiveEnabled }
        self.isMicMuted = self.userMuted || self.externalAudioMuted || !self.routeInputAvailable
        self.isMicMutedByExternalAudio = self.externalAudioMuted
        if sessionActivated {
            broadcastLocalMediaState()
        }
    }

    private func handleCoordinatorEvent(_ event: AudioCoordinatorEvent) {
        if !sessionActivated {
            guard case .availableDevicesChanged = event else { return }
        }
        switch event {
        case .availableDevicesChanged(let devices):
            self.availableAudioDevices = devices
        case .effectiveRouteChanged(let input, let output):
            self.routeInputAvailable = (input != nil)
            self.currentAudioDevice = output
            self.updateEffectiveMicState()
        case .externalAudioStarted:
            if config.audioIntent.muteDuringExternalAudio {
                self.externalAudioMuted = true
                self.updateEffectiveMicState()
            }
            if config.audioIntent.duckDuringExternalAudio {
                playbackDuckingActive = true
                peerSlots.values.forEach { $0.duckPlayback(ducked: true) }
            }
        case .externalAudioEnded:
            self.externalAudioMuted = false
            self.updateEffectiveMicState()
            if playbackDuckingActive {
                playbackDuckingActive = false
                peerSlots.values.forEach { $0.duckPlayback(ducked: false) }
            }
        case .playbackDuckingStarted:
            if config.audioIntent.duckDuringExternalAudio {
                playbackDuckingActive = true
                peerSlots.values.forEach { $0.duckPlayback(ducked: true) }
            }
        case .playbackDuckingEnded:
            if playbackDuckingActive {
                playbackDuckingActive = false
                peerSlots.values.forEach { $0.duckPlayback(ducked: false) }
            }
        }
    }

    /// Set local video enabled state.
    public func setVideoEnabled(_ enabled: Bool) {
        setVideoEnabled(enabled, broadcastMediaState: false)
    }

    private func setVideoEnabled(_ enabled: Bool, broadcastMediaState: Bool) {
        if enabled && availableCameraModes.isEmpty { return }
        if enabled && !ensureCameraPermissionForVideoEnable(broadcastMediaStateOnGrant: broadcastMediaState) {
            return
        }
        userPreferredVideoEnabled = enabled
        applyLocalVideoPreference()
        if broadcastMediaState {
            broadcastLocalMediaState()
        }
    }

    /// Start screen sharing via the broadcast upload extension.
    public func startScreenShare() {
        guard !diagnostics.isScreenSharing else { return }
        _ = webRtcEngine.startScreenShare { [weak self] started in
            Task { @MainActor in
                guard let self, started else { return }
                self.commitSnapshot { s, d in
                    d.isScreenSharing = true; s.localParticipant.cameraMode = .screenShare; d.cameraZoomFactor = 1
                }
                self.signalingMessageRouter?.broadcastContentState(active: true, contentType: ContentTypeWire.screenShare)
                self.applyLocalVideoPreference()
            }
        }
    }

    /// Stop screen sharing.
    public func stopScreenShare() { _ = webRtcEngine.stopScreenShare() }

    /// Capture the current video frame from the chosen stream as JPEG data
    /// at the source track's full intrinsic resolution.
    ///
    /// - Parameter source: `.local` for the user's own stream, `.remote(cid:)`
    ///   for a specific participant. Defaults to `.local`.
    /// - Throws: `SnapshotError.streamNotActive` when the chosen stream's
    ///   video is off or the participant is not connected;
    ///   `SnapshotError.captureTimeout` when no frame arrives within the
    ///   resilience window; `SnapshotError.captureFailed` for encode errors.
    public func captureSnapshot(source: SnapshotSource = .local) async throws -> SnapshotResult {
        let timestampMs = Int64(Date().timeIntervalSince1970 * 1000)
        let engine = webRtcEngine
        let attachRenderer: @MainActor (AnyObject) -> Void
        let detachRenderer: @MainActor (AnyObject) -> Void

        switch source {
        case .local:
            guard state.localParticipant.videoEnabled,
                  internalPhase == .inCall || internalPhase == .waiting else {
                throw SnapshotError.streamNotActive
            }
            attachRenderer = { renderer in engine.attachLocalRenderer(renderer) }
            detachRenderer = { renderer in engine.detachLocalRenderer(renderer) }
        case .remote(let cid):
            guard let slot = peerSlots[cid], slot.isRemoteVideoTrackEnabled() else {
                throw SnapshotError.streamNotActive
            }
            let capturedSlot = slot
            attachRenderer = { renderer in capturedSlot.attachRemoteRenderer(renderer) }
            detachRenderer = { renderer in capturedSlot.detachRemoteRenderer(renderer) }
        }

        let capturer = FrameSnapshotCapturer(
            attachRenderer: attachRenderer,
            detachRenderer: detachRenderer
        )
        let frame = try await capturer.capture(timeoutMs: WebRtcResilience.snapshotPrepareTimeoutMs)
        return SnapshotResult(
            jpegData: frame.jpegData,
            width: frame.width,
            height: frame.height,
            timestampMs: timestampMs,
            source: source
        )
    }

    public func setHdVideoExperimentalEnabled(_ enabled: Bool) { webRtcEngine.setHdVideoExperimentalEnabled(enabled) }
    /// Toggle the device flashlight. Returns whether the flashlight is now on.
    @discardableResult public func toggleFlashlight() -> Bool { webRtcEngine.toggleFlashlight() }

    /// Adjust camera zoom by a relative scale delta. Returns the new zoom factor, or nil if inactive.
    @discardableResult
    public func adjustCameraZoom(by scaleDelta: CGFloat) -> Double? {
        guard (internalPhase == .inCall || internalPhase == .waiting),
              state.localParticipant.cameraMode.isContentMode else { return nil }
        return webRtcEngine.adjustCaptureZoom(by: scaleDelta)
    }

    /// Reset camera zoom to 1x.
    @discardableResult public func resetCameraZoom() -> Double { webRtcEngine.resetCaptureZoom() }

    /// Resume joining after camera/microphone permissions have been granted.
    public func resumeJoin() {
        currentRequiredPermissions = nil
        currentError = nil
        internalPhase = .joining
        callStartedAtMs = Self.nowMs()
        commitSnapshot()
        joinLifecycleTask?.cancel()
        joinLifecycleTask = Task { @MainActor [weak self] in
            await self?.prepareMediaAndConnect()
        }
    }

    /// Cancel an in-progress join and return to idle.
    public func cancelJoin() {
        currentRequiredPermissions = nil
        resetResources()
        internalPhase = .idle
        commitSnapshot()
    }

    /// Attach a view for rendering local video.
    public func attachLocalRenderer(_ renderer: AnyObject) { webRtcEngine.attachLocalRenderer(renderer) }
    /// Detach a previously attached local video renderer.
    public func detachLocalRenderer(_ renderer: AnyObject) { webRtcEngine.detachLocalRenderer(renderer) }

    /// Attach a view for rendering remote video.
    ///
    /// In a 1:1 call, the host app calls this without a CID and we pick a
    /// peer for them. Prefer an ACTIVE (non-suspended) participant: picking a
    /// suspended one attaches the renderer to a frozen peer connection — the
    /// last frame stays on screen as a "ghost" — while a co-existing fresh
    /// CID for the same physical device that joined without a reconnect
    /// token gets no renderer at all. Falls back to any non-self
    /// participant, then to any peer slot, before giving up.
    public func attachRemoteRenderer(_ renderer: AnyObject) {
        let participants = currentRoomState?.participants ?? []
        let cid = participants.first(where: { $0.cid != clientId && $0.signalingStatus != .suspended })?.cid
            ?? participants.first(where: { $0.cid != clientId })?.cid
            ?? peerSlots.keys.first
        guard let cid else { return }
        attachRemoteRenderer(renderer, forParticipant: cid)
    }

    /// Detach a previously attached remote video renderer.
    public func detachRemoteRenderer(_ renderer: AnyObject) { peerSlots.values.forEach { $0.detachRemoteRenderer(renderer) } }
    public func attachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String) { peerSlots[cid]?.attachRemoteRenderer(renderer) }
    public func detachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String) { peerSlots[cid]?.detachRemoteRenderer(renderer) }

    // MARK: - Join Flow

    private func beginJoinIfNeeded() async {
        guard !hasBegunJoin else { return }
        hasBegunJoin = true
        joinAttemptSerial += 1
        currentError = nil
        currentRequiredPermissions = nil
        let videoCaptureSupported = !self.availableCameraModes.isEmpty
        userPreferredVideoEnabled = videoCaptureSupported && config.defaultVideoEnabled
        internalPhase = .joining
        participantCount = 0
        let initialCameraMode = self.availableCameraModes.first ?? .selfie
        commitSnapshot { s, d in
            s.localParticipant = LocalParticipant(
                cid: nil, displayName: self.displayName, peerId: self.peerId,
                audioEnabled: self.config.defaultAudioEnabled,
                videoEnabled: videoCaptureSupported && self.config.defaultVideoEnabled,
                cameraMode: initialCameraMode,
                availableCameraModes: self.availableCameraModes
            )
            s.remoteParticipants = []; s.connectionStatus = .connected
            d.activeTransport = nil; d.isSignalingConnected = false
            d.iceConnectionState = .new; d.peerConnectionState = .new; d.rtcSignalingState = .stable
            d.cameraZoomFactor = 1; d.isFlashAvailable = false; d.isFlashEnabled = false
            d.remoteContentParticipantId = nil; d.remoteContentType = nil; d.realtimeStats = .empty
        }

        let needsCamera = videoCaptureSupported && config.defaultVideoEnabled
        let required = JoinFlowCoordinator.missingPermissions(includeCamera: needsCamera)
        if !required.isEmpty {
            currentRequiredPermissions = required
            internalPhase = .idle
            commitSnapshot()
            onPermissionsRequired?(required)
            delegateProvider?()?.sessionRequiresPermissions(self, permissions: required)
            return
        }
        callStartedAtMs = Self.nowMs()
        await prepareMediaAndConnect()
    }

    private func prepareMediaAndConnect() async {
        guard state.phase == .joining || state.phase == .awaitingPermissions || internalPhase == .joining else { return }

        startCoordinatorTasks()
        localMediaReadyForNegotiation = false
        hasEverConnectedPeer = false
        let videoCaptureSupported = !availableCameraModes.isEmpty
        let shouldEnableAudio = config.defaultAudioEnabled
        let shouldEnableVideo = videoCaptureSupported && config.defaultVideoEnabled
        commitSnapshot { s, _ in
            s.localParticipant.audioEnabled = shouldEnableAudio
            s.localParticipant.videoEnabled = shouldEnableVideo
        }

        joinFlowCoordinator?.clearJoinConnectKickstart()

        do {
            try await activateAudioCoordinator()
            try Task.checkCancellation()
        } catch is CancellationError {
            return
        } catch {
            logger?.log(.error, tag: "Audio", "Failed to activate audio session: \(error)")
            handleError(.unknown("Audio session activation failed: \(error.localizedDescription)"))
            return
        }

        guard state.phase == .joining || state.phase == .awaitingPermissions || internalPhase == .joining else { return }

        joinFlowCoordinator?.scheduleJoinTimeout(roomId: roomId, joinAttempt: joinAttemptSerial)
        callAudioSessionController.activate()
        webRtcEngine.startLocalMedia(preferVideo: shouldEnableVideo)
        localMediaReadyForNegotiation = true
        self.userMuted = !shouldEnableAudio
        self.sessionActivated = true
        self.updateEffectiveMicState()

        userPreferredVideoEnabled = shouldEnableVideo
        applyLocalVideoPreference()
        statsPoller?.start()
        audioLevelPoller?.start()

        peerNegotiationEngine?.onLocalMediaReady()
        joinFlowCoordinator?.scheduleJoinConnectKickstart(roomId: roomId, joinAttempt: joinAttemptSerial)
        ensureSignalingConnection()
    }

    private func activateAudioCoordinator() async throws {
        let previous = audioCoordinatorLifecycleTask
        let coordinator = audioCoordinator
        let intent = config.audioIntent
        let task = Task<Void, Error> { [weak self] in
            if let previous {
                do {
                    try await self?.awaitAudioCoordinatorLifecycleTask(previous)
                } catch {
                    await MainActor.run {
                        self?.logger?.log(.warning, tag: "Audio", "Previous audio coordinator operation did not finish before activation: \(error.localizedDescription)")
                    }
                }
            }
            try Task.checkCancellation()
            try await coordinator.activateCallSession(intent: intent)
        }
        audioCoordinatorLifecycleTask = task
        try await awaitAudioCoordinatorLifecycleTask(task)
    }

    private func deactivateAudioCoordinator() {
        let previous = audioCoordinatorLifecycleTask
        let coordinator = audioCoordinator
        let task = Task<Void, Error> { [weak self] in
            if let previous {
                do {
                    try await self?.awaitAudioCoordinatorLifecycleTask(previous)
                } catch {
                    await MainActor.run {
                        self?.logger?.log(.warning, tag: "Audio", "Previous audio coordinator operation did not finish before deactivation: \(error.localizedDescription)")
                    }
                }
            }
            await coordinator.deactivateCallSession()
        }
        audioCoordinatorLifecycleTask = task
        Task { @MainActor [weak self] in
            do {
                try await self?.awaitAudioCoordinatorLifecycleTask(task)
            } catch is CancellationError {
            } catch {
                self?.logger?.log(.warning, tag: "Audio", "Audio coordinator deactivation did not finish: \(error.localizedDescription)")
            }
        }
    }

    private func awaitAudioCoordinatorLifecycleTask(_ task: Task<Void, Error>) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                try await task.value
            }
            group.addTask {
                try await Task.sleep(nanoseconds: WebRtcResilience.audioCoordinatorTimeoutNs)
                task.cancel()
                throw AudioCoordinatorTimeoutError()
            }

            do {
                _ = try await group.next()
                group.cancelAll()
            } catch {
                group.cancelAll()
                throw error
            }
        }
    }

    private func ensureSignalingConnection() {
        hasJoinSignalStartedForAttempt = true
        if diagnostics.isSignalingConnected {
            pendingJoinRoom = nil
            sendJoin(roomId: roomId)
            return
        }
        pendingJoinRoom = roomId
        signalingProvider.connect()
    }

    private func sendJoin(roomId: String) {
        guard diagnostics.isSignalingConnected else {
            pendingJoinRoom = roomId
            ensureSignalingConnection()
            return
        }
        signalingProvider.joinRoom(
            roomId,
            options: JoinOptions(
                reconnectPeerId: reconnectCid,
                maxParticipants: 4,
                displayName: self.displayName,
                appPeerId: self.peerId
            )
        )
        joinFlowCoordinator?.scheduleJoinRecovery(for: roomId)
    }

    // MARK: - Signaling Message Handling

    private func handleJoined(cid: String?, roomState: RoomState?, participantCountHint: Int?) {
        joinFlowCoordinator?.clearAllTimers()
        hasJoinAcknowledgedCurrentAttempt = true

        if let cid { clientId = cid; reconnectCid = cid }
        commitSnapshot { s, _ in s.localParticipant.cid = self.clientId }

        if let roomState {
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        } else {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint)
        }
        broadcastLocalMediaState()
        loadInitialIceServers()
    }

    private func handleRoomState(_ roomState: RoomState?, participantCountHint: Int?) {
        joinFlowCoordinator?.clearAllTimers()
        hasJoinAcknowledgedCurrentAttempt = true

        guard let roomState else {
            recoverFromJoiningIfNeeded(participantHint: participantCountHint)
            return
        }
        hostCid = roomState.hostCid
        updateParticipants(roomState)
    }

    private func handleSignalingPayload(_ message: SignalingMessage) {
        recoverFromJoiningIfNeeded(
            participantHint: SignalingMessageRouter.participantCountHint(payload: message.payload),
            preferInCall: true
        )
        guard webRtcEngine.hasIceServers() else { pendingMessages.append(message); return }
        peerNegotiationEngine?.processSignalingPayload(message)
    }

    private func handleParticipantMediaState(_ payload: MediaStatePayload) {
        guard let fromCid = payload.fromCid, !fromCid.isEmpty else { return }
        let existing = remoteMediaStates[fromCid]
        remoteMediaStates[fromCid] = (
            audioEnabled: payload.audioEnabled ?? existing?.audioEnabled,
            videoEnabled: payload.videoEnabled ?? existing?.videoEnabled
        )
        refreshRemoteParticipants()
    }

    private func broadcastLocalMediaState() {
        signalingMessageRouter?.broadcastMediaState(
            audioEnabled: state.localParticipant.audioEnabled,
            videoEnabled: state.localParticipant.videoEnabled
        )
    }

    private func handleContentState(_ payload: ContentStatePayload) {
        guard let fromCid = payload.fromCid, !fromCid.isEmpty else { return }
        commitSnapshot { _, d in
            d.remoteContentParticipantId = payload.active ? fromCid : nil
            d.remoteContentType = payload.contentType
        }
    }

    private func handleError(_ error: CallError) {
        currentError = error
        joinFlowCoordinator?.clearAllTimers()
        resetResources(clearRecovery: shouldClearRecovery(for: error))
        internalPhase = .error
        let nextSignalingState = computeSignalingState(connected: false)
        commitSnapshot { s, _ in s.signalingState = nextSignalingState }
    }

    // MARK: - Provider Events

    fileprivate func handleProviderConnected(_ info: ConnectionInfo) {
        let wasConnected = diagnostics.isSignalingConnected
        reconnectAttempts = 0
        reconnectTask?.cancel()
        reconnectTask = nil
        localSuspendedSinceMs = nil
        let nextSignalingState = computeSignalingState(connected: true)
        commitSnapshot { s, d in
            d.isSignalingConnected = true
            d.activeTransport = info.transport
            s.signalingState = nextSignalingState
        }
        connectionStatusTracker?.update()
        if let join = pendingJoinRoom {
            pendingJoinRoom = nil
            sendJoin(roomId: join)
        } else if !wasConnected, signalingProvider.capabilities.handlesReconnection, reconnectRecoveryPending, currentRoomState != nil {
            reconnectRecoveryPending = false
            armPostReconnectResync()
        }
    }

    fileprivate func handleProviderDisconnected(reason: String?) {
        _ = reason
        if currentRoomState != nil, localSuspendedSinceMs == nil {
            localSuspendedSinceMs = clock.nowMs()
        }
        let nextSignalingState = computeSignalingState(connected: false)
        commitSnapshot { s, d in
            d.isSignalingConnected = false
            d.activeTransport = nil
            s.signalingState = nextSignalingState
        }
        connectionStatusTracker?.update()
        let phase = state.phase
        if phase == .joining || phase == .waiting || phase == .inCall {
            if signalingProvider.capabilities.handlesReconnection {
                reconnectRecoveryPending = currentRoomState != nil
            } else {
                scheduleReconnect()
            }
        }
    }

    fileprivate func handleProviderJoined(_ event: JoinedEvent) {
        currentError = nil
        signalingMessageRouter?.processJoinedEvent(event)
        persistRecoveryRecord(token: event.reconnectToken, ttlMs: event.reconnectTokenTTLMs)
    }

    fileprivate func handleProviderReconnectTokenRefreshed(_ event: ReconnectTokenRefreshedEvent) {
        persistRecoveryRecord(token: event.reconnectToken, ttlMs: event.reconnectTokenTTLMs)
    }

    fileprivate func handleProviderRoomStateUpdated(_ event: RoomStateEvent) {
        currentError = nil
        signalingMessageRouter?.processRoomStateEvent(event)
        flushPostReconnectResync(reason: .snapshot)
    }

    fileprivate func handleProviderPeerJoined(_ event: PeerEvent) {
        currentError = nil
        currentRoomState = upsertParticipant(roomState: currentRoomState, event: event, localPeerId: clientId)
        if let roomState = currentRoomState {
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        }
        broadcastLocalMediaState()
    }

    fileprivate func handleProviderPeerLeft(_ event: PeerEvent) {
        remoteMediaStates.removeValue(forKey: event.peerId)
        currentRoomState = removeParticipant(roomState: currentRoomState, peerId: event.peerId, localPeerId: clientId)
        if let roomState = currentRoomState {
            hostCid = roomState.hostCid
            updateParticipants(roomState)
        } else {
            refreshRemoteParticipants()
        }
    }

    fileprivate func handleProviderMessage(_ message: PeerMessage) {
        guard ["content_state", "participant_media_state", "offer", "answer", "ice", "media_restart_request"].contains(message.type) else { return }
        signalingMessageRouter?.processPeerMessage(message)
    }

    fileprivate func handleProviderRoomEnded(_ event: RoomEndedEvent) {
        _ = event
        cleanupCall(reason: .remoteEnded, transitionToEnding: true)
    }

    fileprivate func handleProviderError(_ event: ErrorEvent) {
        signalingMessageRouter?.processErrorEvent(event)
    }

    fileprivate func handleProviderIceServersChanged(_ iceServers: [IceServerConfig]) {
        applyIceServers(iceServers)
    }

    fileprivate func handleProviderNegotiationDirty(_ event: NegotiationDirtyEvent) {
        logger?.log(.debug, tag: "Session", "RX negotiation_dirty with=\(event.withCid)")
        peerNegotiationEngine?.scheduleDirtyPairRestart(remoteCid: event.withCid)
    }

    fileprivate func handleProviderRelayFailed(_ event: RelayFailedEvent) {
        // Server has the dirty-pair record; once the suspended target reattaches
        // we'll get `negotiation_dirty` and renegotiate then. For now, surface
        // in logs so suppressed offers/ICE are visible.
        logger?.log(
            .debug,
            tag: "Session",
            "RX relay_failed reason=\(event.reason) of=\(event.of ?? "n/a") targets=\(event.targets.joined(separator: ","))"
        )
    }

    // MARK: - Participants

    private func updateParticipants(_ roomState: RoomState) {
        currentRoomState = roomState
        let count = max(1, roomState.participants.count)
        let phase: CallPhase = count <= 1 ? .waiting : .inCall
        if phase != .joining { joinFlowCoordinator?.clearJoinTimeout() }
        let localJoinedAtMs = roomState.participants
            .first(where: { $0.cid == clientId })?
            .joinedAt
            .flatMap { Self.isPlausibleJoinedAtMs($0, nowMs: Self.nowMs()) ? $0 : nil }
        if let localJoinedAtMs {
            callStartedAtMs = localJoinedAtMs
        }

        internalPhase = phase
        participantCount = count
        commitSnapshot { s, _ in s.localParticipant.isHost = self.clientId != nil && self.clientId == roomState.hostCid }

        peerNegotiationEngine?.syncPeers(roomState: roomState)
        refreshRemoteParticipants()
        connectionStatusTracker?.update()
        // Start media-liveness emission only once we have remote peers — there's
        // nothing to report when alone in the room, and the timer is otherwise
        // a noisy no-op.
        if phase == .inCall {
            startMediaLivenessTimer()
            startOutboundMediaWatchdog()
        }
    }

    /// True only when at least one peer exists and every slot's last observed
    /// candidate pair is direct. `nil` cached values (no stats yet) count as
    /// "not confirmed direct" so the gate errs on the side of refreshing.
    private func arePeerPathsAllDirect() -> Bool {
        if peerSlots.isEmpty { return false }
        for (_, slot) in peerSlots {
            guard let direct = slot.isPathDirect(), direct else { return false }
        }
        return true
    }

    private func refreshRemoteParticipants() {
        guard let roomState = currentRoomState else {
            clearAllRemoteSuspensionTracking()
            commitSnapshot { s, _ in s.remoteParticipants = [] }
            return
        }
        let remotes = roomState.participants.filter { $0.cid != clientId }
        reconcileRemoteSuspensionTimers(remotes)
        let previousLevels = Dictionary(uniqueKeysWithValues: state.remoteParticipants.map { ($0.cid, $0.audioLevel) })
        let participants = remotes.map { p in
            let slot = peerSlots[p.cid]
            let peerState = remoteMediaStates[p.cid]
            let audioEnabled = peerState?.audioEnabled ?? p.audioEnabled ?? true
            return SerenadaRemoteParticipant(
                cid: p.cid, displayName: p.displayName, peerId: p.peerId,
                audioEnabled: audioEnabled,
                videoEnabled: peerState?.videoEnabled ?? p.videoEnabled ?? (slot?.isRemoteVideoTrackEnabled() ?? false),
                connectionState: slot?.getConnectionState() ?? .new,
                signalingStatus: p.signalingStatus,
                presumedLost: p.signalingStatus == .suspended && presumedLostRemoteCids.contains(p.cid),
                audioLevel: audioEnabled ? (previousLevels[p.cid] ?? 0) : 0
            )
        }
        let activeCids = Set(participants.map(\.cid))
        let clearContent = diagnostics.remoteContentParticipantId != nil && !activeCids.contains(diagnostics.remoteContentParticipantId!)
        commitSnapshot { s, d in
            s.remoteParticipants = participants
            if clearContent { d.remoteContentParticipantId = nil; d.remoteContentType = nil }
        }
    }

    private func applyAudioLevels(localLevel: Float, remoteLevels: [String: Float]) {
        // Compute updates without touching state so we can skip commitSnapshot
        // entirely when nothing changed. Otherwise this fires the SDK
        // delegate's `sessionDidChangeState` 10×/sec during sustained silence.
        let nextLocal: Float = state.localParticipant.audioEnabled ? localLevel : 0
        let localChanged = nextLocal != state.localParticipant.audioLevel

        var updatedRemote: [SerenadaRemoteParticipant]?
        if !state.remoteParticipants.isEmpty {
            var draft = state.remoteParticipants
            var anyChanged = false
            for index in draft.indices {
                let raw = remoteLevels[draft[index].cid] ?? 0
                let target: Float = draft[index].audioEnabled ? raw : 0
                if draft[index].audioLevel != target {
                    draft[index].audioLevel = target
                    anyChanged = true
                }
            }
            if anyChanged { updatedRemote = draft }
        }

        guard localChanged || updatedRemote != nil else { return }

        commitSnapshot { s, _ in
            if localChanged { s.localParticipant.audioLevel = nextLocal }
            if let updatedRemote { s.remoteParticipants = updatedRemote }
        }
    }

    // MARK: - Recovery

    private func failJoinWithError(_ error: CallError) {
        joinFlowCoordinator?.clearAllTimers()
        currentError = error
        resetResources(clearRecovery: shouldClearRecovery(for: error))
        internalPhase = .error
        commitSnapshot()
    }

    private func recoverFromJoiningIfNeeded(participantHint: Int?, preferInCall: Bool = false) {
        guard let recovered = resolveJoinRecoveryState(
            currentPhase: internalPhase, participantHint: participantHint ?? participantCount, preferInCall: preferInCall
        ) else { return }
        joinFlowCoordinator?.clearJoinTimeout()
        internalPhase = recovered.phase
        participantCount = recovered.participantCount
        commitSnapshot()
        connectionStatusTracker?.update()
    }

    // MARK: - Messaging

    private func sendMessage(type: String, payload: JSONValue? = nil, to: String? = nil) {
        let objectPayload = payload?.objectValue
        if let to {
            signalingProvider.sendToPeer(to, type: type, payload: objectPayload)
        } else {
            signalingProvider.broadcast(type: type, payload: objectPayload)
        }
    }

    private func flushPendingMessages() {
        guard webRtcEngine.hasIceServers() else { return }
        let pending = pendingMessages
        pendingMessages.removeAll()
        for message in pending { peerNegotiationEngine?.processSignalingPayload(message) }
    }

    // Adapter functions (joinedMessageFromEvent, roomStateMessageFromEvent,
    // signalingMessageFromPeerMessage, errorMessageFromEvent, participantsJSONValue,
    // resolveHostPeerId) removed — the router now accepts provider events directly.

    private func upsertParticipant(
        roomState: RoomState?,
        event: PeerEvent,
        localPeerId: String?
    ) -> RoomState? {
        let participants = dedupeParticipants(
            participants: (roomState?.participants ?? []) + [Participant(
                cid: event.peerId,
                joinedAt: event.joinedAt,
                displayName: event.displayName,
                peerId: event.appPeerId
            )],
            localPeerId: localPeerId,
            makeLocalParticipant: { Participant(cid: $0, joinedAt: nil) }
        )
        let nextHost = roomState?.hostCid ?? localPeerId ?? participants.first?.cid
        guard let nextHost else { return nil }
        let resolvedHost = participants.contains(where: { $0.cid == nextHost }) ? nextHost : participants.first?.cid
        guard let resolvedHost else { return nil }
        return RoomState(hostCid: resolvedHost, participants: participants, maxParticipants: roomState?.maxParticipants)
    }

    private func removeParticipant(
        roomState: RoomState?,
        peerId: String,
        localPeerId: String?
    ) -> RoomState? {
        guard let roomState else { return nil }
        let participants = dedupeParticipants(
            participants: roomState.participants.filter { $0.cid != peerId },
            localPeerId: localPeerId,
            makeLocalParticipant: { Participant(cid: $0, joinedAt: nil) }
        )
        guard !participants.isEmpty else { return nil }
        let nextHost: String
        if roomState.hostCid != peerId, participants.contains(where: { $0.cid == roomState.hostCid }) {
            nextHost = roomState.hostCid
        } else if let localPeerId, participants.contains(where: { $0.cid == localPeerId }) {
            nextHost = localPeerId
        } else {
            nextHost = participants[0].cid
        }
        return RoomState(hostCid: nextHost, participants: participants, maxParticipants: roomState.maxParticipants)
    }

    private func defaultIceServers() -> [IceServerConfig] {
        [IceServerConfig(urls: ["stun:stun.l.google.com:19302"], username: nil, credential: nil)]
    }

    private func applyIceServers(_ iceServers: [IceServerConfig]) {
        let resolvedIceServers = iceServers.isEmpty ? defaultIceServers() : iceServers
        webRtcEngine.setIceServers(resolvedIceServers)
        flushPendingMessages()
        peerNegotiationEngine?.onIceServersReady()
    }

    private func loadInitialIceServers() {
        iceFetchGeneration += 1
        let fetchGeneration = iceFetchGeneration
        Task { @MainActor [weak self] in
            guard let self else { return }
            var lastError: Error?
            for delayMs in WebRtcResilience.iceFetchRetryDelaysMs {
                if delayMs > 0 {
                    try? await clock.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
                }
                guard fetchGeneration == iceFetchGeneration else { return }
                do {
                    let iceServers = try await signalingProvider.getIceServers()
                    guard fetchGeneration == iceFetchGeneration else { return }
                    applyIceServers(iceServers)
                    return
                } catch {
                    lastError = error
                }
            }

            guard fetchGeneration == iceFetchGeneration else { return }
            let message = lastError?.localizedDescription ?? "Failed to fetch ICE servers"
            currentError = .serverError(message)
            joinFlowCoordinator?.clearAllTimers()
            resetResources()
            internalPhase = .error
            commitSnapshot()
            delegateProvider?()?.sessionDidEnd(self, reason: .error(message))
        }
    }

    // MARK: - Cleanup

    private func cleanupCall(reason: EndReason, transitionToEnding: Bool) {
        if transitionToEnding {
            internalPhase = .ending
            commitSnapshot { s, _ in s.localParticipant.videoEnabled = false; s.remoteParticipants = [] }
        }
        resetResources(clearRecovery: true)
        if transitionToEnding {
            delegateProvider?()?.sessionDidEnd(self, reason: reason)
            Task { @MainActor [weak self] in
                guard let clock = self?.clock else { return }
                try? await clock.sleep(nanoseconds: 1_500_000_000)
                guard let self, self.state.phase == .ending else { return }
                self.internalPhase = .idle; self.commitSnapshot()
            }
        } else {
            internalPhase = .idle; commitSnapshot()
            delegateProvider?()?.sessionDidEnd(self, reason: reason)
        }
    }

    private func resetResources(clearRecovery: Bool = false) {
        joinLifecycleTask?.cancel()
        joinLifecycleTask = nil
        statsPoller?.stop()
        audioLevelPoller?.stop()
        peerNegotiationEngine?.resetAll()
        signalingProvider.disconnect()
        peerSlots.values.forEach { $0.closePeerConnection() }
        peerSlots.removeAll()
        webRtcEngine.release()
        callAudioSessionController.deactivate()
        deactivateAudioCoordinator()

        currentRoomState = nil; clientId = nil; hostCid = nil
        pendingJoinRoom = nil; pendingMessages.removeAll(); reconnectAttempts = 0; remoteMediaStates.removeAll()

        reconnectTask?.cancel(); reconnectTask = nil
        cancelPostReconnectResync()
        clearAllRemoteSuspensionTracking()
        stopMediaLivenessTimer()
        stopOutboundMediaWatchdog()
        lastInboundBytesByCid.removeAll()
        mediaLivenessEmitInFlight = false
        localSuspendedSinceMs = nil
        joinFlowCoordinator?.clearAllTimers()
        connectionStatusTracker?.cancelTimer()
        iceFetchGeneration += 1
        sessionStartTs = nil
        callStartedAtMs = nil
        sessionActivated = false
        localMediaReadyForNegotiation = false
        playbackDuckingActive = false
        externalAudioMuted = false
        routeInputAvailable = true
        if clearRecovery { recoveryStorage.clear() }

        let videoCaptureSupported = !availableCameraModes.isEmpty
        userPreferredVideoEnabled = videoCaptureSupported && config.defaultVideoEnabled
        isVideoPausedByProximity = false
        hasJoinSignalStartedForAttempt = false
        hasJoinAcknowledgedCurrentAttempt = false
        reconnectRecoveryPending = false
        participantCount = 0

        let initialCameraMode = availableCameraModes.first ?? .selfie
        commitSnapshot { s, d in
            s.localParticipant = LocalParticipant(
                cid: nil,
                cameraMode: initialCameraMode,
                availableCameraModes: self.availableCameraModes
            )
            s.remoteParticipants = []; s.connectionStatus = .connected
            d.isSignalingConnected = false; d.activeTransport = nil
            d.iceConnectionState = .new; d.peerConnectionState = .new; d.rtcSignalingState = .stable
            d.isScreenSharing = false; d.cameraZoomFactor = 1
            d.isFlashAvailable = false; d.isFlashEnabled = false
            d.remoteContentParticipantId = nil; d.remoteContentType = nil
            d.realtimeStats = .empty; d.featureDegradations = []
        }
    }

    private func shouldClearRecovery(for error: CallError) -> Bool {
        switch error {
        case .roomEnded, .sessionExpired:
            return true
        default:
            return false
        }
    }

    // MARK: - Video & Audio

    private func applyLocalVideoPreference() {
        let shouldPause = callAudioSessionController.shouldPauseVideoForProximity(isScreenSharing: diagnostics.isScreenSharing)
        if shouldPause != isVideoPausedByProximity { isVideoPausedByProximity = shouldPause }
        let effectiveEnabled = webRtcEngine.toggleVideo(userPreferredVideoEnabled && !shouldPause)
        commitSnapshot { s, _ in s.localParticipant.videoEnabled = effectiveEnabled }
    }

    private func ensureCameraPermissionForVideoEnable(broadcastMediaStateOnGrant: Bool) -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return true
        case .notDetermined:
            requestCameraPermissionForVideoEnable(broadcastMediaStateOnGrant: broadcastMediaStateOnGrant)
            return false
        case .denied, .restricted:
            return false
        @unknown default:
            return false
        }
    }

    private func requestCameraPermissionForVideoEnable(broadcastMediaStateOnGrant: Bool) {
        guard !cameraPermissionRequestInFlight else { return }
        cameraPermissionRequestInFlight = true
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            Task { @MainActor in
                guard let self = self else { return }
                self.cameraPermissionRequestInFlight = false
                guard granted, self.state.phase != .idle else { return }
                self.userPreferredVideoEnabled = true
                self.applyLocalVideoPreference()
                if broadcastMediaStateOnGrant {
                    self.broadcastLocalMediaState()
                }
            }
        }
    }

    // MARK: - Reconnection

    private func scheduleReconnect() {
        reconnectAttempts += 1
        refreshSignalingState()
        let backoff = Backoff.reconnectDelayMs(attempt: reconnectAttempts)
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: UInt64(backoff) * 1_000_000)
            guard !Task.isCancelled, let self, !self.diagnostics.isSignalingConnected else { return }
            self.pendingJoinRoom = self.roomId
            self.signalingProvider.connect()
        }
    }

    // MARK: - Post-reconnect snapshot gate

    private enum PostReconnectFlushReason {
        case snapshot
        case timeout
    }

    private func armPostReconnectResync() {
        pendingPostReconnectResync = true
        postReconnectResyncTask?.cancel()
        postReconnectResyncTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            let timeoutMs = UInt64(WebRtcResilience.epochResyncTimeoutMs)
            try? await clock.sleep(nanoseconds: timeoutMs * 1_000_000)
            guard !Task.isCancelled, let self else { return }
            self.flushPostReconnectResync(reason: .timeout)
        }
    }

    private func flushPostReconnectResync(reason: PostReconnectFlushReason) {
        guard pendingPostReconnectResync else { return }
        pendingPostReconnectResync = false
        postReconnectResyncTask?.cancel()
        postReconnectResyncTask = nil
        if reason == .timeout {
            logger?.log(
                .warning,
                tag: "Session",
                "Post-reconnect snapshot timeout after \(WebRtcResilience.epochResyncTimeoutMs)ms; firing ICE restart against last-known peer map"
            )
        }
        iceRestartCallsFromGate += 1
        peerNegotiationEngine?.handleSignalingReconnect()
    }

    private func cancelPostReconnectResync() {
        pendingPostReconnectResync = false
        postReconnectResyncTask?.cancel()
        postReconnectResyncTask = nil
    }

    // MARK: - Suspended-peer presentation

    /// Walks the latest authoritative remote participant list and starts/cancels
    /// per-CID suspended-presentation timers. Cancels cleanly when peers go back
    /// to active or are removed; flips `presumedLost=true` on timer expiry.
    ///
    /// "Already presumed lost" is a sticky state: once the timer has fired, we
    /// don't reschedule a new one if the peer remains suspended across
    /// subsequent room_state updates. The flag clears the moment the peer
    /// transitions back to active or leaves the room.
    private func reconcileRemoteSuspensionTimers(_ remotes: [Participant]) {
        let seen = Set(remotes.map(\.cid))
        for participant in remotes {
            let isSuspended = participant.signalingStatus == .suspended
            let hasTimer = suspendedPresentationTasks[participant.cid] != nil
            let isPresumedLost = presumedLostRemoteCids.contains(participant.cid)
            if isSuspended {
                if !hasTimer, !isPresumedLost {
                    startRemoteSuspensionTimer(cid: participant.cid)
                }
            } else {
                clearRemoteSuspensionTracking(cid: participant.cid)
            }
        }
        // Snapshot keys before iterating — `clearRemoteSuspensionTracking`
        // mutates both collections, which would otherwise trap.
        let trackedTasks = Array(suspendedPresentationTasks.keys)
        for cid in trackedTasks where !seen.contains(cid) {
            clearRemoteSuspensionTracking(cid: cid)
        }
        let trackedPresumed = Array(presumedLostRemoteCids)
        for cid in trackedPresumed where !seen.contains(cid) {
            clearRemoteSuspensionTracking(cid: cid)
        }
    }

    private func startRemoteSuspensionTimer(cid: String) {
        let task = Task { [weak self] in
            guard let clock = self?.clock else { return }
            let timeoutMs = UInt64(WebRtcResilience.peerSuspendedUiTimeoutMs)
            try? await clock.sleep(nanoseconds: timeoutMs * 1_000_000)
            guard !Task.isCancelled, let self else { return }
            self.handleRemoteSuspensionTimerFired(cid: cid)
        }
        suspendedPresentationTasks[cid] = task
    }

    private func handleRemoteSuspensionTimerFired(cid: String) {
        suspendedPresentationTasks.removeValue(forKey: cid)
        presumedLostRemoteCids.insert(cid)
        logger?.log(
            .info,
            tag: "Session",
            "Remote \(cid) presumed lost after \(WebRtcResilience.peerSuspendedUiTimeoutMs)ms suspended"
        )
        refreshRemoteParticipants()
    }

    /// Clear all per-CID suspension state (timer + presumed-lost flag).
    /// Called when a peer transitions back to active, leaves the room, or
    /// the session is reset.
    private func clearRemoteSuspensionTracking(cid: String) {
        suspendedPresentationTasks.removeValue(forKey: cid)?.cancel()
        presumedLostRemoteCids.remove(cid)
    }

    private func clearAllRemoteSuspensionTracking() {
        for task in suspendedPresentationTasks.values { task.cancel() }
        suspendedPresentationTasks.removeAll()
        presumedLostRemoteCids.removeAll()
    }

    // MARK: - Media-liveness emission (#3)

    /// Periodic `media_liveness{cids}` broadcast for #3. Started on a
    /// successful join; runs across reconnects (ticks no-op while
    /// disconnected but baseline samples persist so the next post-reconnect
    /// tick can detect flow). Stopped on session reset/destroy.
    private func startMediaLivenessTimer() {
        guard mediaLivenessTask == nil else { return }
        mediaLivenessTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            let intervalNs = UInt64(WebRtcResilience.mediaLivenessIntervalMs) * 1_000_000
            while !Task.isCancelled {
                try? await clock.sleep(nanoseconds: intervalNs)
                if Task.isCancelled { return }
                guard let self else { return }
                self.emitMediaLiveness()
            }
        }
    }

    private func stopMediaLivenessTimer() {
        mediaLivenessTask?.cancel()
        mediaLivenessTask = nil
    }

    private func startOutboundMediaWatchdog() {
        guard outboundMediaWatchdogCancellable == nil else { return }
        let interval = TimeInterval(WebRtcResilience.outboundMediaWatchdogIntervalMs) / 1000.0
        outboundMediaWatchdogCancellable = clock.scheduleRepeating(intervalSeconds: interval) { [weak self] in
            self?.peerNegotiationEngine?.recoverStalledOutboundMedia()
        }
    }

    private func stopOutboundMediaWatchdog() {
        outboundMediaWatchdogCancellable?.cancel()
        outboundMediaWatchdogCancellable = nil
    }

    private func emitMediaLiveness() {
        if mediaLivenessEmitInFlight { return }
        guard diagnostics.isSignalingConnected, currentRoomState != nil else { return }
        let slots = peerSlots
        if slots.isEmpty { return }

        mediaLivenessEmitInFlight = true
        var newSamples: [String: Int64] = [:]
        var remaining = slots.count
        for (cid, slot) in slots {
            slot.collectInboundBytes { [weak self] bytes in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    newSamples[cid] = bytes
                    remaining -= 1
                    if remaining == 0 { self.finalizeMediaLivenessEmit(newSamples: newSamples) }
                }
            }
        }
    }

    private func finalizeMediaLivenessEmit(newSamples: [String: Int64]) {
        mediaLivenessEmitInFlight = false
        var flowing: [String] = []
        for (cid, bytes) in newSamples {
            if let previous = lastInboundBytesByCid[cid], bytes > previous {
                flowing.append(cid)
            }
            lastInboundBytesByCid[cid] = bytes
        }
        // Drop tracking for peers that left.
        for cid in Array(lastInboundBytesByCid.keys) where peerSlots[cid] == nil {
            lastInboundBytesByCid.removeValue(forKey: cid)
        }
        guard !flowing.isEmpty, diagnostics.isSignalingConnected else { return }
        let cidsArray = JSONValue.array(flowing.map(JSONValue.string))
        signalingProvider.broadcast(type: "media_liveness", payload: ["cids": cidsArray])
        mediaLivenessEmitCount += 1
    }

    // MARK: - Local signaling state

    private func computeSignalingState(connected: Bool) -> SignalingState {
        if let error = currentError { return .failed(reason: error) }
        if connected { return .connected }
        if let suspendedSince = localSuspendedSinceMs {
            return .suspended(
                suspendedSinceMs: suspendedSince,
                estimatedHardEvictionAtMs: suspendedSince + Int64(WebRtcResilience.suspendHardEvictionTimeoutMs)
            )
        }
        return .reconnecting(attempt: reconnectAttempts, nextRetryAtMs: nil)
    }

    fileprivate func refreshSignalingState() {
        let next = computeSignalingState(connected: diagnostics.isSignalingConnected)
        if state.signalingState != next {
            commitSnapshot { s, _ in s.signalingState = next }
        }
    }

    // MARK: - Snapshot Management

    private func commitSnapshot(
        _ mutate: (_ state: inout CallState, _ diagnostics: inout CallDiagnostics) -> Void = { _, _ in }
    ) {
        var nextState = state; var nextDiag = diagnostics
        mutate(&nextState, &nextDiag)
        nextState.phase = currentRequiredPermissions != nil ? .awaitingPermissions : mapPhase(internalPhase)
        nextState.roomId = roomId; nextState.roomUrl = roomUrl
        nextState.error = currentError; nextState.requiredPermissions = currentRequiredPermissions
        nextState.callStartedAtMs = callStartedAtMs
        nextDiag.callStats = CallStats(from: nextDiag.realtimeStats)
        if nextState != state { state = nextState }
        if nextDiag != diagnostics { diagnostics = nextDiag }
        syncIdleTimerPolicy(for: internalPhase)
        delegateProvider?()?.sessionDidChangeState(self, state: state)
    }

    private func setFeatureDegradation(_ degradation: FeatureDegradationState) {
        var nextDiag = diagnostics
        if let idx = nextDiag.featureDegradations.firstIndex(where: { $0.kind == degradation.kind }) {
            nextDiag.featureDegradations[idx] = degradation
        } else {
            nextDiag.featureDegradations.append(degradation)
        }
        if nextDiag != diagnostics { diagnostics = nextDiag }
    }

    private func mapPhase(_ phase: CallPhase) -> SerenadaCallPhase {
        switch phase {
        case .idle: .idle
        case .creatingRoom, .joining: .joining
        case .waiting: .waiting
        case .inCall: .inCall
        case .ending: .ending
        case .error: .error
        }
    }

    private func syncIdleTimerPolicy(for phase: CallPhase) {
        switch phase {
        case .creatingRoom, .joining, .waiting, .inCall: UIApplication.shared.isIdleTimerDisabled = true
        default: UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    // MARK: - Sub-Engine Setup

    private func buildSubEngines() {
        signalingMessageRouter = SignalingMessageRouter(
            getClientId: { [weak self] in self?.clientId },
            getHostCid: { [weak self] in self?.hostCid },
            getRoomId: { [weak self] in self?.roomId },
            onJoined: { [weak self] cid, roomState, hint in self?.handleJoined(cid: cid, roomState: roomState, participantCountHint: hint) },
            onRoomState: { [weak self] roomState, hint in self?.handleRoomState(roomState, participantCountHint: hint) },
            onRoomEnded: { [weak self] in self?.cleanupCall(reason: .remoteEnded, transitionToEnding: true) },
            onPong: {},
            onTurnRefreshed: { _ in },
            onSignalingPayload: { [weak self] message in self?.handleSignalingPayload(message) },
            onContentState: { [weak self] payload in self?.handleContentState(payload) },
            onParticipantMediaState: { [weak self] payload in self?.handleParticipantMediaState(payload) },
            onError: { [weak self] error in self?.handleError(error) },
            sendMessage: { [weak self] type, payload, to in self?.sendMessage(type: type, payload: payload, to: to) }
        )

        joinFlowCoordinator = JoinFlowCoordinator(
            clock: clock,
            getRoomId: { [weak self] in self?.roomId ?? "" },
            getJoinAttemptSerial: { [weak self] in self?.joinAttemptSerial ?? 0 },
            getInternalPhase: { [weak self] in self?.internalPhase ?? .idle },
            hasJoinSignalStarted: { [weak self] in self?.hasJoinSignalStartedForAttempt ?? false },
            hasJoinAcknowledged: { [weak self] in self?.hasJoinAcknowledgedCurrentAttempt ?? false },
            isSignalingConnected: { [weak self] in self?.diagnostics.isSignalingConnected ?? false },
            onJoinTimeout: { [weak self] in self?.failJoinWithError(.connectionFailed) },
            onEnsureSignalingConnection: { [weak self] in self?.ensureSignalingConnection() },
            onRecovery: { [weak self] hint, preferInCall in
                self?.recoverFromJoiningIfNeeded(
                    participantHint: hint ?? self?.currentRoomState?.participants.count, preferInCall: preferInCall
                )
            }
        )

        connectionStatusTracker = ConnectionStatusTracker(
            clock: clock,
            getInternalPhase: { [weak self] in self?.internalPhase ?? .idle },
            getDiagnostics: { [weak self] in self?.diagnostics ?? CallDiagnostics() },
            getCurrentStatus: { [weak self] in self?.state.connectionStatus ?? .connected },
            setConnectionStatus: { [weak self] status in
                guard let self, self.state.connectionStatus != status else { return }
                self.commitSnapshot { s, _ in s.connectionStatus = status }
            }
        )

        statsPoller = StatsPoller(
            clock: clock,
            isActivePhase: { [weak self] in
                guard let p = self?.internalPhase else { return false }
                return p == .inCall || p == .waiting || p == .joining
            },
            getPeerSlots: { [weak self] in
                guard let self else { return [] }
                return Array(self.peerSlots.values)
            },
            onStatsUpdated: { [weak self] merged in self?.commitSnapshot { _, d in d.realtimeStats = merged } },
            onRefreshRemoteParticipants: { [weak self] in self?.refreshRemoteParticipants() }
        )

        audioLevelPoller = AudioLevelPoller(
            clock: clock,
            // Run while local media is live, including the Waiting phase
            // before a peer joins. The primer peer connection keeps
            // `media-source` stat available throughout, so sensitivity
            // matches InCall.
            isActivePhase: { [weak self] in
                guard let p = self?.internalPhase else { return false }
                return p == .inCall || p == .waiting
            },
            getPeerSlots: { [weak self] in
                guard let self else { return [] }
                return Array(self.peerSlots.values)
            },
            collectLocalLevel: { [weak self] onComplete in
                guard let self else {
                    onComplete(nil)
                    return
                }
                self.webRtcEngine.collectLocalAudioLevel(onComplete)
            },
            onLevelsUpdated: { [weak self] localLevel, remoteLevels in
                self?.applyAudioLevels(localLevel: localLevel, remoteLevels: remoteLevels)
            }
        )

        peerNegotiationEngine = PeerNegotiationEngine(
            clock: clock,
            getClientId: { [weak self] in self?.clientId },
            getHostCid: { [weak self] in self?.hostCid },
            getInternalPhase: { [weak self] in self?.internalPhase ?? .idle },
            getParticipantCount: { [weak self] in self?.participantCount ?? 0 },
            getCurrentRoomState: { [weak self] in self?.currentRoomState },
            isSignalingConnected: { [weak self] in self?.diagnostics.isSignalingConnected ?? false },
            hasIceServers: { [weak self] in self?.webRtcEngine.hasIceServers() ?? false },
            isLocalMediaReady: { [weak self] in self?.localMediaReadyForNegotiation ?? false },
            getSlot: { [weak self] cid in self?.peerSlots[cid] },
            getAllSlots: { [weak self] in self?.peerSlots ?? [:] },
            setSlot: { [weak self] cid, slot in
                self?.peerSlots[cid] = slot
                if let self, self.playbackDuckingActive {
                    slot.duckPlayback(ducked: true)
                }
            },
            removeSlotEntry: { [weak self] cid in self?.peerSlots.removeValue(forKey: cid) },
            createSlotViaEngine: { [weak self] remoteCid, onLocalIce, onRemoteVideo, onConnState, onIceConnState, onSigState, onRenegotiation in
                self?.webRtcEngine.createSlot(
                    remoteCid: remoteCid, onLocalIceCandidate: onLocalIce,
                    onRemoteVideoTrack: onRemoteVideo, onConnectionStateChange: onConnState,
                    onIceConnectionStateChange: onIceConnState, onSignalingStateChange: onSigState,
                    onRenegotiationNeeded: onRenegotiation
                )
            },
            engineRemoveSlot: { [weak self] slot in self?.webRtcEngine.removeSlot(slot) },
            sendMessage: { [weak self] type, payload, to in self?.sendMessage(type: type, payload: payload, to: to) },
            onRemoteParticipantsChanged: { [weak self] in self?.refreshRemoteParticipants() },
            onAggregatePeerStateChanged: { [weak self] ice, conn, sig in
                if conn == .connected { self?.hasEverConnectedPeer = true }
                self?.commitSnapshot { _, d in d.iceConnectionState = ice; d.peerConnectionState = conn; d.rtcSignalingState = sig }
            },
            onConnectionStatusUpdate: { [weak self] in self?.connectionStatusTracker?.update() },
            logger: logger
        )
    }

    // MARK: - Runtime Bridges

    private func configureRuntimeBridges() {
        callAudioSessionController.setOnAudioEnvironmentChanged { [weak self] in
            Task { @MainActor in self?.applyLocalVideoPreference() }
        }
        webRtcEngine.setOnCameraFacingChanged { [weak self] isFront in
            Task { @MainActor in self?.commitSnapshot { _, d in d.isFrontCamera = isFront } }
        }
        webRtcEngine.setOnCameraModeChanged { [weak self] mode in
            Task { @MainActor in
                guard let self else { return }
                let prev = self.state.localParticipant.cameraMode
                self.commitSnapshot { s, _ in s.localParticipant.cameraMode = mode }
                if mode.isContentMode {
                    let type = mode == .world ? ContentTypeWire.worldCamera : ContentTypeWire.compositeCamera
                    self.signalingMessageRouter?.broadcastContentState(active: true, contentType: type)
                } else if prev.isContentMode {
                    self.signalingMessageRouter?.broadcastContentState(active: false)
                }
            }
        }
        webRtcEngine.setOnFlashlightStateChanged { [weak self] available, enabled in
            Task { @MainActor in self?.commitSnapshot { _, d in d.isFlashAvailable = available; d.isFlashEnabled = enabled } }
        }
        webRtcEngine.setOnScreenShareStopped { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.commitSnapshot { _, d in d.isScreenSharing = false; d.cameraZoomFactor = 1 }
                self.signalingMessageRouter?.broadcastContentState(active: false)
                self.applyLocalVideoPreference()
            }
        }
        webRtcEngine.setOnZoomFactorChanged { [weak self] z in
            Task { @MainActor in self?.commitSnapshot { _, d in d.cameraZoomFactor = z } }
        }
        webRtcEngine.setOnFeatureDegradation { [weak self] degradation in
            Task { @MainActor in self?.setFeatureDegradation(degradation) }
        }
    }

    // MARK: - Network Monitoring

    private func startNetworkMonitoring() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            Task { @MainActor in
                guard self.internalPhase == .inCall else { return }
                if self.connectionStatusTracker?.isConnectionDegraded() == true { self.connectionStatusTracker?.update() }
                // Skip until the call has connected once; the initial NWPathMonitor callback
                // at start() is the baseline network, not a handover (see hasEverConnectedPeer).
                if path.status == .satisfied && self.hasEverConnectedPeer {
                    self.peerNegotiationEngine?.scheduleIceRestart(reason: "network-online", delayMs: 0)
                }
            }
        }
        pathMonitor.start(queue: pathMonitorQueue)
        startAppLifecycleMonitoring()
    }

    private func startAppLifecycleMonitoring() {
        let center = NotificationCenter.default
        backgroundObserver = center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.handleAppBackgrounded() }
        }
        foregroundObserver = center.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.handleAppForegrounded() }
        }
    }

    private func handleAppBackgrounded() {
        lastBackgroundedAtMs = clock.nowMs()
    }

    /// Force-ping hook for resilience #8: when iOS resumes the app after a
    /// long enough background, the WS the OS killed silently is detected
    /// inside `foregroundForcePingTimeoutMs` instead of waiting a full
    /// `pingIntervalMs` cycle.
    private func handleAppForegrounded() {
        let backgroundedAt = lastBackgroundedAtMs
        lastBackgroundedAtMs = nil
        guard let backgroundedAt else { return }
        guard internalPhase == .inCall || internalPhase == .joining || internalPhase == .waiting else { return }
        let backgroundedMs = clock.nowMs() - backgroundedAt
        guard backgroundedMs >= Self.foregroundResumeMinBackgroundMs else { return }
        signalingProvider.forceReconnectIfStale(timeoutMs: WebRtcResilience.foregroundForcePingTimeoutMs)
    }

    /// Background duration that triggers a foreground force-ping. Anything
    /// shorter is short enough that pings would have noticed the failure on
    /// their own; longer is the OS window where iOS may have killed the WS.
    private static let foregroundResumeMinBackgroundMs: Int64 = 5_000

    /// Snapshots the in-memory reconnect state into the cross-launch
    /// recovery store so a relaunched process can offer a "Rejoin call?"
    /// prompt. No-op until the join handshake has produced a CID + token.
    private func persistRecoveryRecord(token: String?, ttlMs: Int64?) {
        guard let cid = clientId, let token = token else { return }
        if sessionStartTs == nil { sessionStartTs = Self.nowMs() }
        let ttl = ttlMs.flatMap { $0 > 0 ? $0 : nil } ?? Self.defaultRecoveryTokenTTLMs
        let record = RecoveryRecord(
            roomId: roomId,
            cid: cid,
            reconnectToken: token,
            lastEpoch: currentRoomState?.epoch,
            sessionStartTs: sessionStartTs ?? Self.nowMs(),
            expiresAtMs: Self.nowMs() + ttl
        )
        recoveryStorage.save(record)
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private static func isPlausibleJoinedAtMs(_ joinedAtMs: Int64, nowMs: Int64) -> Bool {
        joinedAtMs >= plausibleEpochMs && joinedAtMs <= nowMs + joinedAtFutureSkewMs
    }

    /// Used when the server did not surface `reconnectTokenTTLMs`.
    private static let defaultRecoveryTokenTTLMs: Int64 = Int64(WebRtcResilience.reconnectTokenTtlFallbackMs)
    private static let plausibleEpochMs: Int64 = 946_684_800_000
    private static let joinedAtFutureSkewMs: Int64 = 5 * 60 * 1000
}

@MainActor
private final class CustomAudioCoordinatorAdapter: SessionAudioController {
    private let coordinator: SerenadaAudioCoordinator
    private let proximityMonitoringEnabled: Bool
    private var proximityMonitoringActive = false
    private var isProximityNear = false
    private var currentOutputDevice: AudioDevice?
    private var streamTask: Task<Void, Never>?
    private var onAudioEnvironmentChanged: (() -> Void)?

    init(coordinator: SerenadaAudioCoordinator, proximityMonitoringEnabled: Bool) {
        self.coordinator = coordinator
        self.proximityMonitoringEnabled = proximityMonitoringEnabled
    }

    func activate() {
        if proximityMonitoringEnabled {
            startProximityMonitoring()
        }
        streamTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for await device in coordinator.effectiveOutputDevice {
                self.currentOutputDevice = device
                self.onAudioEnvironmentChanged?()
            }
        }
    }

    func deactivate() {
        stopProximityMonitoring()
        streamTask?.cancel()
        streamTask = nil
    }

    func shouldPauseVideoForProximity(isScreenSharing: Bool) -> Bool {
        proximityMonitoringActive && isProximityNear && !isScreenSharing && !isBluetoothHeadsetConnected()
    }

    func setOnAudioEnvironmentChanged(_ handler: @escaping () -> Void) {
        onAudioEnvironmentChanged = handler
    }

    private func startProximityMonitoring() {
        guard !proximityMonitoringActive else { return }
        UIDevice.current.isProximityMonitoringEnabled = true
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleProximityStateChange(_:)),
            name: UIDevice.proximityStateDidChangeNotification,
            object: nil
        )
        proximityMonitoringActive = true
        isProximityNear = UIDevice.current.proximityState
    }

    private func isBluetoothHeadsetConnected() -> Bool {
        if case .bluetooth = currentOutputDevice?.kind {
            return true
        }
        return AVAudioSession.sharedInstance().currentRoute.outputs.contains { output in
            switch output.portType {
            case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE:
                return true
            default:
                return false
            }
        }
    }

    private func stopProximityMonitoring() {
        guard proximityMonitoringActive else { return }
        NotificationCenter.default.removeObserver(self, name: UIDevice.proximityStateDidChangeNotification, object: nil)
        UIDevice.current.isProximityMonitoringEnabled = false
        proximityMonitoringActive = false
        isProximityNear = false
    }

    @objc private func handleProximityStateChange(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self, self.proximityMonitoringActive else { return }
            let near = UIDevice.current.proximityState
            guard near != self.isProximityNear else { return }
            self.isProximityNear = near
            self.onAudioEnvironmentChanged?()
        }
    }
}
