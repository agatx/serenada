import AVFoundation
import Combine
import CoreGraphics
import Foundation
import Network
import SerenadaBroadcastExtensionSupport
import UIKit

struct JoinRecoveryState: Equatable {
    let phase: CallPhase
    let participantCount: Int
}

private final class SessionRendererBox {
    weak var value: AnyObject?

    init(value: AnyObject) {
        self.value = value
    }
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

    /// Aggregate call-quality summary. Reflects the live
    /// tracker while in-call and the finalized snapshot after the call ends;
    /// stays readable after teardown. Nil before sampling begins (first
    /// inCall).
    ///
    /// `@Published`: backed by a stored snapshot refreshed on
    /// each tracker recompute so a SwiftUI host bound to `qualitySummary` for a
    /// live MOS/loss readout gets `objectWillChange` mid-call.
    @Published public private(set) var qualitySummary: CallQualitySummary?

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
        if case let .broadcast(ipcConfig) = config.screenShareMode {
            return ipcConfig.extensionBundleId
        }
        return nil
    }
    /// Whether screen sharing is configured for this session. `false` when
    /// `screenShareMode` is `.disabled`, so the UI hides the screen-share control
    /// instead of offering a button that no-ops.
    public var isScreenShareAvailable: Bool {
        if case .disabled = config.screenShareMode { return false }
        return true
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

    // Aggregate call-quality tracker, driven by explicit
    // inputs. `finalizedQualitySummary` is snapshotted at finalize and
    // survives teardown so hosts can read it after the session stops.
    private lazy var qualityTracker = CallQualityTracker { [weak self] event in
        guard let self else { return }
        self.delegateProvider?()?.sessionDidEmitConnectionEvent(self, event: event)
    }
    private var finalizedQualitySummary: CallQualitySummary?
    private var lastTrackedPhase: CallPhase = .joining
    private var lastTrackedConnectionStatus: SerenadaConnectionStatus = .connected

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
    /// Latest received content (screen-share) state per remote cid, with the
    /// owning sid and tracked revision used to discard stale/out-of-order
    /// updates. Cleared when the participant leaves or goes inactive.
    private var remoteContentStates: [String: (sid: String?, content: ParticipantContent, revision: Int64)] = [:]
    /// Latest local content (screen-share) public state, mirrored into
    /// `localParticipant.content` on every snapshot. Carries the revision of
    /// the last broadcast `content_state`. `nil` when not sharing content.
    private var localContent: ParticipantContent?
    private var peerSlots: [String: any PeerConnectionSlotProtocol] = [:]
    private var defaultRemoteRendererRegistrations: [SessionRendererBox] = []
    private var remoteRendererRegistrations: [String: [SessionRendererBox]] = [:]
    private var remoteContentRendererRegistrations: [String: [SessionRendererBox]] = [:]
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
    private var isScreenShareStartPending = false
    private var screenShareStartRequestSerial: Int64 = 0
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
    // whose media is still flowing locally. The broadcast no-ops while
    // transport is disconnected, preserving media-liveness baselines so the
    // next post-reconnect tick can detect flow.
    private var lastInboundBytesByCid: [String: Int64] = [:]
    // Per-role inbound-video stall diagnostics (GAP 2). Sampled on the SAME
    // media-liveness tick as `lastInboundBytesByCid`, but kept SEPARATE: the
    // server-facing `media_liveness` signal sums all RTP (audio-inclusive),
    // whereas these split VIDEO bytes by camera vs content role to drive the
    // public `cameraReceiving` / `contentReceiving` per-participant flags. Both
    // default false (conservative) until a peer has two successive samples; both
    // cleared on peer-leave. Read synchronously while assembling participant
    // state in `refreshRemoteParticipants`.
    private var lastInboundRoleBytesByCid: [String: RoleInboundBytes] = [:]
    private var roleLivenessByCid: [String: RoleLiveness] = [:]
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
                videoMediaEnabled: config.videoMediaEnabled,
                enableIndependentContentVideo: config.enableIndependentContentVideo,
                cameraModes: config.cameraModes,
                deferInitialAnswer: config.deferInitialAnswer,
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
        self.availableCameraModes = config.videoMediaEnabled ? SerenadaSession.resolveAvailableCameraModes(config.cameraModes) : []
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
            videoMediaEnabled: config.videoMediaEnabled,
            enableIndependentContentVideo: config.enableIndependentContentVideo,
            screenShareMode: config.screenShareMode,
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
        if config.enableIndependentContentVideo {
            // Independent mode: the camera is a SEPARATE track, so flipping it
            // during a screen share is valid and leaves the content track
            // untouched (pitfall #6). The camera-framing content_state is owned
            // by the screen share while sharing — onCameraModeChanged suppresses
            // it — so do not broadcast it here.
            webRtcEngine.flipCamera()
            return
        }
        // Legacy mode: a single video track carries the share, so flip is blocked
        // while sharing (it would clobber the display track).
        guard !diagnostics.isScreenSharing else { return }
        if state.localParticipant.cameraMode.isContentMode {
            broadcastLocalContentState(active: false)
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
        case .audioSessionRestarted:
            // External-audio policy reset (as in .externalAudioEnded), plus an audio-unit restart:
            // a same-app owner held and released the session with no interruption notification, so
            // WebRTC will not recover capture/playback on its own.
            self.externalAudioMuted = false
            self.updateEffectiveMicState()
            if playbackDuckingActive {
                playbackDuckingActive = false
                peerSlots.values.forEach { $0.duckPlayback(ducked: false) }
            }
            webRtcEngine.restartAudioUnit()
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
        guard config.videoMediaEnabled else { return }
        // `.disabled` is a clean no-op: no capture, pending start, or
        // content_state signaling.
        guard isScreenShareAvailable else { return }
        guard !diagnostics.isScreenSharing else { return }
        guard !isScreenShareStartPending else { return }
        if config.enableIndependentContentVideo {
            startScreenShareIndependent()
        } else {
            startScreenShareLegacy()
        }
    }

    /// Legacy single-video path (flag off): byte-identical to today. The screen
    /// reuses the camera track, so the camera preference is forced on and
    /// `cameraMode` becomes `.screenShare`; content_state is signaled after the
    /// broadcast confirms.
    private func startScreenShareLegacy() {
        let wasVideoPreferred = userPreferredVideoEnabled
        userPreferredVideoEnabled = true
        let requestSerial = beginScreenShareStartRequest()
        let startedRequest = webRtcEngine.startScreenShare { [weak self] started in
            Task { @MainActor in
                guard let self else { return }
                guard self.completeScreenShareStartRequest(requestSerial) else { return }
                guard started else {
                    self.userPreferredVideoEnabled = wasVideoPreferred
                    return
                }
                self.commitSnapshot { s, d in
                    d.isScreenSharing = true; s.localParticipant.cameraMode = .screenShare; d.cameraZoomFactor = 1
                }
                self.broadcastLocalContentState(active: true, contentType: ContentTypeWire.screenShare)
                self.applyLocalVideoPreference()
            }
        }
        if !startedRequest {
            if completeScreenShareStartRequest(requestSerial) {
                userPreferredVideoEnabled = wasVideoPreferred
            }
        }
    }

    /// Independent content path (flag on): the screen rides a SEPARATE content
    /// track, so the camera preference is NOT touched (pitfall #6) and
    /// `cameraMode` is never set to `.screenShare`. The session publishes the
    /// local sharing state only after the capture path confirms that the
    /// ReplayKit stream started; pending/cancelled picker flows stay silent so
    /// peers do not render a black content tile while the system dialog is up.
    private func startScreenShareIndependent() {
        let requestSerial = beginScreenShareStartRequest()
        let startedRequest = webRtcEngine.startScreenShare { [weak self] started in
            Task { @MainActor in
                guard let self else { return }
                guard self.completeScreenShareStartRequest(requestSerial) else { return }
                guard started else { return }
                self.commitSnapshot { _, d in d.isScreenSharing = true }
                self.broadcastLocalContentState(active: true, contentType: ContentTypeWire.screenShare)
            }
        }
        if !startedRequest {
            _ = completeScreenShareStartRequest(requestSerial)
        }
    }

    private func beginScreenShareStartRequest() -> Int64 {
        screenShareStartRequestSerial += 1
        isScreenShareStartPending = true
        return screenShareStartRequestSerial
    }

    private func completeScreenShareStartRequest(_ serial: Int64) -> Bool {
        guard isScreenShareStartPending, screenShareStartRequestSerial == serial else { return false }
        isScreenShareStartPending = false
        return true
    }

    private func cancelPendingScreenShareStartRequest() {
        guard isScreenShareStartPending else { return }
        screenShareStartRequestSerial += 1
        isScreenShareStartPending = false
    }

    /// Stop screen sharing. The engine's stop fires the controller's
    /// `onScreenShareStopped` callback (wired in `configureRuntimeBridges`),
    /// which owns the session-side state + `content_state` broadcast for BOTH the
    /// programmatic stop here and an external broadcast termination — so the
    /// signaling happens exactly once per logical stop (pitfall #9).
    public func stopScreenShare() {
        cancelPendingScreenShareStartRequest()
        _ = webRtcEngine.stopScreenShare()
    }

    /// Independent-stop only: content type that should remain active when the
    /// camera is still in a world/composite content framing. SELFIE has no
    /// content framing, so stopping the share emits inactive instead.
    private func cameraContentTypeAfterIndependentStop() -> String? {
        switch state.localParticipant.cameraMode {
        case .world:
            return ContentTypeWire.worldCamera
        case .composite:
            return ContentTypeWire.compositeCamera
        default:
            return nil
        }
    }

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
        rememberRenderer(renderer, in: &defaultRemoteRendererRegistrations)
        compactRendererRegistrations()
        guard let cid = preferredRemoteRendererCid() else { return }
        peerSlots[cid]?.attachRemoteRenderer(renderer)
    }

    /// Detach a previously attached remote video renderer.
    public func detachRemoteRenderer(_ renderer: AnyObject) {
        forgetRenderer(renderer, in: &defaultRemoteRendererRegistrations)
        peerSlots.values.forEach { $0.detachRemoteRenderer(renderer) }
    }

    public func attachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String) {
        rememberRenderer(renderer, for: cid, in: &remoteRendererRegistrations)
        peerSlots[cid]?.attachRemoteRenderer(renderer)
    }

    public func detachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String) {
        forgetRenderer(renderer, for: cid, in: &remoteRendererRegistrations)
        peerSlots[cid]?.detachRemoteRenderer(renderer)
    }

    // MARK: - Content (screen share) renderer APIs
    // Camera renderers stay on attachRemoteRenderer / attachLocalRenderer above.
    // These render the independent CONTENT (screen share) stream separately.

    /// Attach a renderer to a specific peer's remote CONTENT (screen share) track.
    public func attachRemoteContentRenderer(_ renderer: AnyObject, forParticipant cid: String) {
        rememberRenderer(renderer, for: cid, in: &remoteContentRendererRegistrations)
        peerSlots[cid]?.attachRemoteContentRenderer(renderer)
    }

    /// Detach a previously attached remote content renderer for a peer.
    public func detachRemoteContentRenderer(_ renderer: AnyObject, forParticipant cid: String) {
        forgetRenderer(renderer, for: cid, in: &remoteContentRendererRegistrations)
        peerSlots[cid]?.detachRemoteContentRenderer(renderer)
    }

    /// Attach a renderer to the LOCAL content (screen share) track for local preview.
    public func attachLocalContentRenderer(_ renderer: AnyObject) { webRtcEngine.attachLocalContentRenderer(renderer) }
    /// Detach a previously attached local content renderer.
    public func detachLocalContentRenderer(_ renderer: AnyObject) { webRtcEngine.detachLocalContentRenderer(renderer) }

    private func preferredRemoteRendererCid() -> String? {
        let participants = currentRoomState?.participants ?? []
        return participants.first(where: { $0.cid != clientId && $0.signalingStatus != .suspended })?.cid
            ?? participants.first(where: { $0.cid != clientId })?.cid
            ?? peerSlots.keys.first
    }

    private func replayRendererRegistrations(to slot: any PeerConnectionSlotProtocol, cid: String) {
        compactRendererRegistrations()
        var attachedCameraRenderers = Set<ObjectIdentifier>()

        func attachCamera(_ renderer: AnyObject) {
            guard attachedCameraRenderers.insert(ObjectIdentifier(renderer)).inserted else { return }
            slot.attachRemoteRenderer(renderer)
        }

        if preferredRemoteRendererCid() == cid {
            for box in defaultRemoteRendererRegistrations {
                if let renderer = box.value {
                    attachCamera(renderer)
                }
            }
        }

        for box in remoteRendererRegistrations[cid] ?? [] {
            if let renderer = box.value {
                attachCamera(renderer)
            }
        }

        var attachedContentRenderers = Set<ObjectIdentifier>()
        for box in remoteContentRendererRegistrations[cid] ?? [] {
            guard let renderer = box.value,
                  attachedContentRenderers.insert(ObjectIdentifier(renderer)).inserted else {
                continue
            }
            slot.attachRemoteContentRenderer(renderer)
        }
    }

    private func rememberRenderer(_ renderer: AnyObject, in boxes: inout [SessionRendererBox]) {
        boxes.removeAll { $0.value == nil }
        guard !boxes.contains(where: { $0.value === renderer }) else { return }
        boxes.append(SessionRendererBox(value: renderer))
    }

    private func rememberRenderer(
        _ renderer: AnyObject,
        for cid: String,
        in registrations: inout [String: [SessionRendererBox]]
    ) {
        var boxes = registrations[cid] ?? []
        rememberRenderer(renderer, in: &boxes)
        registrations[cid] = boxes
    }

    private func forgetRenderer(_ renderer: AnyObject, in boxes: inout [SessionRendererBox]) {
        boxes.removeAll { $0.value == nil || $0.value === renderer }
    }

    private func forgetRenderer(
        _ renderer: AnyObject,
        for cid: String,
        in registrations: inout [String: [SessionRendererBox]]
    ) {
        guard var boxes = registrations[cid] else { return }
        forgetRenderer(renderer, in: &boxes)
        if boxes.isEmpty {
            registrations.removeValue(forKey: cid)
        } else {
            registrations[cid] = boxes
        }
    }

    private func compactRendererRegistrations() {
        defaultRemoteRendererRegistrations.removeAll { $0.value == nil }
        compactRendererRegistrations(&remoteRendererRegistrations)
        compactRendererRegistrations(&remoteContentRendererRegistrations)
    }

    private func compactRendererRegistrations(_ registrations: inout [String: [SessionRendererBox]]) {
        for cid in Array(registrations.keys) {
            registrations[cid]?.removeAll { $0.value == nil }
            if registrations[cid]?.isEmpty == true {
                registrations.removeValue(forKey: cid)
            }
        }
    }

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
                appPeerId: self.peerId,
                independentContentVideo: config.enableIndependentContentVideo,
                videoMediaEnabled: config.videoMediaEnabled
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

    private func seedLocalContentRevision(from roomState: RoomState) {
        guard let clientId else { return }
        let revision = roomState.participants.first(where: { $0.cid == clientId })?.contentState?.revision
        signalingMessageRouter?.seedContentRevision(revision)
    }

    /// Broadcast a local `content_state` and mirror the result into local
    /// public state. The router stamps a strictly-increasing revision; we keep
    /// the same value on `localParticipant.content` so observers and remote
    /// peers agree on ordering. `active:false` clears local content.
    private func broadcastLocalContentState(active: Bool, contentType: String? = nil) {
        let revision = signalingMessageRouter?.broadcastContentState(active: active, contentType: contentType) ?? 0
        if active {
            localContent = ParticipantContent(
                active: true,
                type: contentType ?? ContentTypeWire.screenShare,
                revision: revision
            )
        } else {
            localContent = nil
        }
        commitSnapshot()
    }

    private func handleContentState(_ payload: ContentStatePayload) {
        guard let fromCid = payload.fromCid, !fromCid.isEmpty else { return }

        // Live `content_state`: reconcile into the cid-keyed cache with the
        // sender's `sid`. A revisionless live update is always applied within a
        // session (`isSnapshot: false`).
        let result = applyRemoteContentState(
            cid: fromCid,
            sid: payload.sid,
            active: payload.active,
            type: payload.contentType,
            revision: payload.revision,
            isSnapshot: false
        )
        if result.changed {
            refreshRemoteParticipants(preferredContentCid: payload.active ? fromCid : nil)
        }
    }

    /// Reconcile a single remote content (screen share) state into
    /// `remoteContentStates`, enforcing the wire-contract ordering rules shared
    /// by the live `content_state` path and the `joined`/`room_state` snapshot
    /// seed (mirrors web's `applyRemoteContentState`). Returns the now-tracked
    /// content for `cid` plus whether this input actually changed the cache.
    ///
    /// Revision ordering, scoped to the sender's `(cid, sid)`:
    ///   - a NEW sid for this cid supersedes by identity (reset tracked rev),
    ///     so a rejoin restarting at revision:1 is accepted;
    ///   - within the same `(cid, sid)` keep only the highest revision and
    ///     discard any revision <= the tracked one (out-of-order / duplicate /
    ///     a stale snapshot behind a newer live update).
    ///
    /// Revisionless edge case differs by caller and MUST stay identical to the
    /// historical two-method behavior:
    ///   - live (`isSnapshot: false`): a revisionless update is ALWAYS applied
    ///     (legacy senders advance presentation state without revision gating);
    ///   - snapshot (`isSnapshot: true`): a revisionless snapshot KEEPS the
    ///     cache when one exists (a live message is the more authoritative
    ///     recent source); with no cache it is adopted.
    ///
    /// Hosted signaling currently relays `content_state` without sender `sid`,
    /// but custom `SignalingProvider` implementations can surface one; keep the
    /// sid branch so those providers get the documented supersede-by-session
    /// behavior while hosted traffic follows the cid-keyed path.
    @discardableResult
    private func applyRemoteContentState(
        cid: String,
        sid: String?,
        active: Bool,
        type: String?,
        revision: Int64?,
        isSnapshot: Bool
    ) -> (content: ParticipantContent?, changed: Bool) {
        let existing = remoteContentStates[cid]

        if let incomingRevision = revision, let existing {
            let sameSession = sidsMatch(existing.sid, sid)
            if sameSession, incomingRevision <= existing.revision {
                return (existing.content, false)
            }
        } else if revision == nil, isSnapshot, let existing {
            // Snapshot-only: a revisionless snapshot does not override the cache.
            return (existing.content, false)
        }

        let resolvedContent = ParticipantContent(
            active: active,
            type: type ?? ContentTypeWire.screenShare,
            revision: revision ?? 0
        )
        let trackedRevision = revision ?? (existing.map { sidsMatch($0.sid, sid) ? $0.revision : 0 } ?? 0)
        remoteContentStates[cid] = (sid: sid, content: resolvedContent, revision: trackedRevision)
        return (resolvedContent, true)
    }

    /// Two sids belong to the same session when both are present and equal.
    /// A nil on either side is treated as "unknown, not a new session" so a
    /// transport that does not surface sid still benefits from revision
    /// ordering within a single connection.
    private func sidsMatch(_ lhs: String?, _ rhs: String?) -> Bool {
        guard let lhs, let rhs else { return true }
        return lhs == rhs
    }

    private func handleError(_ error: CallError, serverCode: String? = nil) {
        currentError = error
        joinFlowCoordinator?.clearAllTimers()
        maybeReportReconnectFailed(serverCode: serverCode)
        finalizeQuality()
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
        remoteContentStates.removeValue(forKey: event.peerId)
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
        if event.code == "TURN_REFRESH_FAILED" {
            // Non-fatal: media keeps flowing on the existing credentials until
            // expiry. Built-in providers no longer emit this code, but custom
            // SignalingProviders may (web and Android take the same early-out).
            logger?.log(.warning, tag: "Session", "TURN refresh failed: \(event.message)")
            return
        }
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
        seedLocalContentRevision(from: roomState)
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

    /// Whether `cid` advertised independent content video at join
    /// (`capabilities.independentContentVideo`). Defaults to false when absent.
    /// Sourced from the typed `Participant` carried in `currentRoomState`.
    internal func remoteSupportsIndependentContentVideo(_ cid: String) -> Bool {
        currentRoomState?.participants.first(where: { $0.cid == cid })?
            .capabilities?.independentContentVideo ?? false
    }

    /// Whether `cid` permits any video media (signaled `mediaPolicy`). Defaults
    /// to true when absent, per the audio-only compatibility boundary.
    internal func remoteVideoMediaEnabled(_ cid: String) -> Bool {
        currentRoomState?.participants.first(where: { $0.cid == cid })?
            .mediaPolicy?.videoMediaEnabled ?? true
    }

    /// Resolve the per-peer independent-content routing gate for a slot. A peer
    /// is routed via the independent camera+content path only when ALL hold: the
    /// local build flag is on, BOTH ends' `videoMediaEnabled` are true, and the
    /// peer advertised `independentContentVideo`. When the flag is off this is
    /// always false, so every peer uses the legacy single-video path
    /// (byte-identical to today). Mirrors Android's
    /// `resolvePeerIndependentContentCapability` / web's `isPeerIndependentCapable`.
    private func resolvePeerIndependentContentSupported(_ cid: String) -> Bool {
        config.enableIndependentContentVideo
            && config.videoMediaEnabled
            && remoteVideoMediaEnabled(cid)
            && remoteSupportsIndependentContentVideo(cid)
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

    private func refreshRemoteParticipants(preferredContentCid: String? = nil) {
        guard let roomState = currentRoomState else {
            clearAllRemoteSuspensionTracking()
            commitSnapshot { s, d in
                s.remoteParticipants = []
                d.remoteContentParticipantId = nil
                d.remoteContentType = nil
            }
            return
        }
        let remotes = roomState.participants.filter { $0.cid != clientId }
        reconcileRemoteSuspensionTimers(remotes)
        let previousLevels = Dictionary(uniqueKeysWithValues: state.remoteParticipants.map { ($0.cid, $0.audioLevel) })
        let participants = remotes.map { p in
            let slot = peerSlots[p.cid]
            let peerState = remoteMediaStates[p.cid]
            let audioEnabled = peerState?.audioEnabled ?? p.audioEnabled ?? true
            let videoEnabled = peerState?.videoEnabled ?? p.videoEnabled ?? (slot?.isRemoteVideoTrackEnabled() ?? false)
            // Reconcile any server-persisted contentState from the snapshot
            // (joined/room_state) against the cached live state with the same
            // cid-keyed keep-highest rule used for live `content_state`. A
            // reconnect snapshot carrying a strictly-higher revision supersedes
            // the cached state (e.g. an `active:false` rollback we missed while
            // disconnected clears a stale cached `active:true`); a lower-or-equal
            // revision leaves the cached state untouched.
            // No snapshot → keep whatever live state is cached for this cid.
            // Otherwise reconcile the persisted snapshot through the shared
            // cid-keyed keep-highest reconciler (snapshots carry no originator
            // `sid`, so `sid: nil`; a revisionless snapshot keeps the cache).
            let content: ParticipantContent?
            if let snapshot = p.contentState {
                content = applyRemoteContentState(
                    cid: p.cid,
                    sid: nil,
                    active: snapshot.active,
                    type: snapshot.contentType,
                    revision: snapshot.revision,
                    isSnapshot: true
                ).content
            } else {
                content = remoteContentStates[p.cid]?.content
            }
            // Per-role inbound stall diagnostics (GAP 2). Both default false until
            // a peer has two successive liveness samples. Flag off / legacy peers:
            // the single inbound video routes to the camera role, so
            // `contentReceiving` stays false (byte-identical, additive fields).
            let liveness = roleLiveness(for: p.cid)
            return SerenadaRemoteParticipant(
                cid: p.cid, displayName: p.displayName, peerId: p.peerId,
                audioEnabled: audioEnabled,
                videoEnabled: videoEnabled,
                // `videoEnabled` remains the camera-specific compatibility signal;
                // `content` carries independent screen-share state.
                cameraEnabled: videoEnabled,
                cameraReceiving: liveness.camera,
                contentReceiving: liveness.content,
                connectionState: slot?.getConnectionState() ?? .new,
                signalingStatus: p.signalingStatus,
                presumedLost: p.signalingStatus == .suspended && presumedLostRemoteCids.contains(p.cid),
                audioLevel: audioEnabled ? (previousLevels[p.cid] ?? 0) : 0,
                content: (content?.active == true) ? content : nil,
                supportsIndependentContentVideo: remoteSupportsIndependentContentVideo(p.cid)
            )
        }
        let activeCids = Set(participants.map(\.cid))
        // Drop tracked content for participants no longer in the room.
        for cid in Array(remoteContentStates.keys) where !activeCids.contains(cid) {
            remoteContentStates.removeValue(forKey: cid)
        }
        let contentDiagnostics = resolveRemoteContentDiagnosticsPointer(preferredCid: preferredContentCid)
        commitSnapshot { s, d in
            s.remoteParticipants = participants
            d.remoteContentParticipantId = contentDiagnostics.cid
            d.remoteContentType = contentDiagnostics.type
        }
    }

    private func resolveRemoteContentDiagnosticsPointer(preferredCid: String?) -> (cid: String?, type: String?) {
        let target: String?
        if let preferredCid, remoteContentStates[preferredCid]?.content.active == true {
            target = preferredCid
        } else if let current = diagnostics.remoteContentParticipantId,
                  remoteContentStates[current]?.content.active == true {
            target = current
        } else {
            target = remoteContentStates.first(where: { $0.value.content.active })?.key
        }
        return (target, target.flatMap { remoteContentStates[$0]?.content.type })
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
        finalizeQuality()
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
            // Transport exhaustion: report via the synthetic code so the
            // shared reason table classifies it as networkConnectivity.
            maybeReportReconnectFailed(serverCode: "ICE_SERVER_FETCH_FAILED")
            finalizeQuality()
            resetResources()
            internalPhase = .error
            commitSnapshot()
            delegateProvider?()?.sessionDidEnd(self, reason: .error(message))
        }
    }

    // MARK: - Cleanup

    /// Finalize the quality summary and snapshot it so it survives teardown.
    /// Must run BEFORE `resetResources()`/`statsPoller.stop()`. Idempotent —
    /// the first call wins.
    private func finalizeQuality() {
        guard finalizedQualitySummary == nil else { return }
        qualityTracker.finalize(nowMs: clock.monotonicMs())
        finalizedQualitySummary = qualityTracker.summarize()
        qualitySummary = finalizedQualitySummary
    }

    /// Emit `reconnectFailed` for a call that reached inCall
    /// when the local termination was driven by a recovery-abandonment error
    /// (transport exhaustion / invalid token / server error). Never for user
    /// hangup or remote-ended. No-op once the tracker is finalized.
    private func maybeReportReconnectFailed(serverCode: String?) {
        guard qualityTracker.hasStartedSampling() else { return }
        // Classify from the original signaling **code** via the shared
        // ReconnectReason table: join hard-timeout /
        // invalid-or-expired token / connection-failed / transport-exhaustion
        // only. Arbitrary server errors (BAD_REQUEST, etc.) map to nil and emit
        // nothing. Never for user hangup or remote-ended.
        guard let reason = ReconnectReason.reasonForCode(serverCode) else { return }
        qualityTracker.reportReconnectFailed(reason)
    }

    private func cleanupCall(reason: EndReason, transitionToEnding: Bool) {
        finalizeQuality()
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
        pendingJoinRoom = nil; pendingMessages.removeAll(); reconnectAttempts = 0; remoteMediaStates.removeAll(); remoteContentStates.removeAll(); localContent = nil

        reconnectTask?.cancel(); reconnectTask = nil
        cancelPostReconnectResync()
        clearAllRemoteSuspensionTracking()
        stopMediaLivenessTimer()
        stopOutboundMediaWatchdog()
        lastInboundBytesByCid.removeAll()
        lastInboundRoleBytesByCid.removeAll()
        roleLivenessByCid.removeAll()
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
                // Total inbound flow and per-role stall diagnostics refresh from
                // one stats sample per peer. Broadcast remains gated by signaling
                // connection, while role diagnostics stay current through blips.
                self.sampleInboundLiveness()
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

    private func sampleInboundLiveness() {
        if mediaLivenessEmitInFlight { return }
        let slots = peerSlots
        guard !slots.isEmpty else {
            if !lastInboundRoleBytesByCid.isEmpty || !roleLivenessByCid.isEmpty {
                lastInboundRoleBytesByCid.removeAll()
                roleLivenessByCid.removeAll()
                refreshRemoteParticipants()
            }
            return
        }
        mediaLivenessEmitInFlight = true
        var samples: [String: InboundLivenessSample] = [:]
        var remaining = slots.count
        for (cid, slot) in slots {
            slot.collectInboundLiveness { [weak self] sample in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    samples[cid] = sample
                    remaining -= 1
                    if remaining == 0 { self.finalizeInboundLiveness(samples: samples) }
                }
            }
        }
    }

    private func finalizeInboundLiveness(samples: [String: InboundLivenessSample]) {
        mediaLivenessEmitInFlight = false
        var flowing: [String] = []
        let canBroadcast = diagnostics.isSignalingConnected && currentRoomState != nil
        for (cid, sample) in samples {
            if canBroadcast {
                if let previous = lastInboundBytesByCid[cid], sample.inboundBytes > previous {
                    flowing.append(cid)
                }
                lastInboundBytesByCid[cid] = sample.inboundBytes
            }

            let roleBytes = sample.roleBytes
            let previous = lastInboundRoleBytesByCid[cid]
            roleLivenessByCid[cid] = RoleLiveness(
                camera: previous != nil && roleBytes.cameraBytes > previous!.cameraBytes,
                content: previous != nil && roleBytes.contentBytes > previous!.contentBytes
            )
            lastInboundRoleBytesByCid[cid] = roleBytes
        }
        if canBroadcast {
            for cid in Array(lastInboundBytesByCid.keys) where peerSlots[cid] == nil {
                lastInboundBytesByCid.removeValue(forKey: cid)
            }
        }
        for cid in Array(lastInboundRoleBytesByCid.keys) where peerSlots[cid] == nil {
            lastInboundRoleBytesByCid.removeValue(forKey: cid)
        }
        for cid in Array(roleLivenessByCid.keys) where peerSlots[cid] == nil {
            roleLivenessByCid.removeValue(forKey: cid)
        }
        // Surface the refreshed booleans on the public remote participants.
        refreshRemoteParticipants()

        guard canBroadcast else { return }
        // Emit even when `flowing` is empty so the server knows this client is
        // still a fresh reporter that sees no media from suspended peers.
        let cidsArray = JSONValue.array(flowing.map(JSONValue.string))
        signalingProvider.broadcast(type: "media_liveness", payload: ["cids": cidsArray])
        mediaLivenessEmitCount += 1
    }

    /// Latest cached per-role inbound liveness for a peer, or both-false when no
    /// sample has been taken yet. Read while assembling participant state.
    private func roleLiveness(for cid: String) -> RoleLiveness {
        roleLivenessByCid[cid] ?? .none
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
        // Keep local camera state and independent content state in sync with the
        // canonical session fields on every snapshot.
        nextState.localParticipant.cameraEnabled = nextState.localParticipant.videoEnabled
        nextState.localParticipant.content = localContent
        nextState.phase = currentRequiredPermissions != nil ? .awaitingPermissions : mapPhase(internalPhase)
        nextState.roomId = roomId; nextState.roomUrl = roomUrl
        nextState.error = currentError; nextState.requiredPermissions = currentRequiredPermissions
        nextState.callStartedAtMs = callStartedAtMs
        nextDiag.callStats = CallStats(from: nextDiag.realtimeStats)
        if nextState != state { state = nextState }
        if nextDiag != diagnostics { diagnostics = nextDiag }
        feedQualityTracker(phase: internalPhase, connectionStatus: state.connectionStatus)
        syncIdleTimerPolicy(for: internalPhase)
        delegateProvider?()?.sessionDidChangeState(self, state: state)
    }

    /// Drive the quality tracker on phase + connection-status
    /// transitions. The dropout **trigger** is derived at the transition: a
    /// degradation driven by lost signaling is `.networkLost`; an ICE/peer-
    /// level degradation while signaling is up is `.unknown`.
    private func feedQualityTracker(phase: CallPhase, connectionStatus: SerenadaConnectionStatus) {
        // Use the injected clock's monotonic source so dropout interval math is
        // deterministic/testable and unaffected by a wall-clock step (#7/#12).
        let now = clock.monotonicMs()
        var changed = false
        if phase != lastTrackedPhase {
            qualityTracker.onPhaseTransition(phase, nowMs: now)
            lastTrackedPhase = phase
            changed = true
        }
        if connectionStatus != lastTrackedConnectionStatus {
            let trigger: DropoutTrigger = diagnostics.isSignalingConnected ? .unknown : .networkLost
            qualityTracker.onConnectionStatusTransition(
                connectionStatus, trigger: trigger, nowMs: now
            )
            lastTrackedConnectionStatus = connectionStatus
            changed = true
        }
        if changed { refreshQualitySummary() }
    }

    /// Refresh the published `qualitySummary` from the tracker. Once
    /// finalized, the snapshot is frozen and survives teardown.
    private func refreshQualitySummary() {
        if finalizedQualitySummary == nil {
            qualitySummary = qualityTracker.summarize()
        }
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
            onError: { [weak self] error, serverCode in self?.handleError(error, serverCode: serverCode) },
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
            onJoinTimeout: { [weak self] in
                guard let self else { return }
                // Join hard-timeout is a concrete recovery-
                // abandonment terminal path -> reconnectFailed(timeout).
                if self.qualityTracker.hasStartedSampling() {
                    self.qualityTracker.reportReconnectFailed(.timeout)
                }
                self.failJoinWithError(.connectionFailed)
            },
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
            onStatsUpdated: { [weak self] merged in
                guard let self else { return }
                self.commitSnapshot { _, d in d.realtimeStats = merged }
                // Feed the quality tracker via the injected
                // clock's monotonic source (#7/#12). Sampling only begins once
                // the tracker has seen the first inCall transition, so pre-call
                // waiting/joining samples are ignored.
                self.qualityTracker.onStatsSample(merged, nowMs: self.clock.monotonicMs())
                self.refreshQualitySummary()
            },
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
            deferInitialAnswer: { [weak self] in self?.config.deferInitialAnswer ?? false },
            getInternalPhase: { [weak self] in self?.internalPhase ?? .idle },
            getParticipantCount: { [weak self] in self?.participantCount ?? 0 },
            getCurrentRoomState: { [weak self] in self?.currentRoomState },
            isSignalingConnected: { [weak self] in self?.diagnostics.isSignalingConnected ?? false },
            hasIceServers: { [weak self] in self?.webRtcEngine.hasIceServers() ?? false },
            isLocalMediaReady: { [weak self] in self?.localMediaReadyForNegotiation ?? false },
            getSlot: { [weak self] cid in self?.peerSlots[cid] },
            getAllSlots: { [weak self] in self?.peerSlots ?? [:] },
            setSlot: { [weak self] cid, slot in
                guard let self else { return }
                self.peerSlots[cid] = slot
                self.replayRendererRegistrations(to: slot, cid: cid)
                if self.playbackDuckingActive {
                    slot.duckPlayback(ducked: true)
                }
            },
            removeSlotEntry: { [weak self] cid in self?.peerSlots.removeValue(forKey: cid) },
            createSlotViaEngine: { [weak self] remoteCid, onLocalIce, onRemoteVideo, onConnState, onIceConnState, onSigState, onRenegotiation, supportsIndependentContentVideo, isOfferOwner in
                self?.webRtcEngine.createSlot(
                    remoteCid: remoteCid, onLocalIceCandidate: onLocalIce,
                    onRemoteVideoTrack: onRemoteVideo, onConnectionStateChange: onConnState,
                    onIceConnectionStateChange: onIceConnState, onSignalingStateChange: onSigState,
                    onRenegotiationNeeded: onRenegotiation,
                    supportsIndependentContentVideo: supportsIndependentContentVideo,
                    isOfferOwner: isOfferOwner
                )
            },
            engineRemoveSlot: { [weak self] slot in self?.webRtcEngine.removeSlot(slot) },
            peerIndependentContentSupported: { [weak self] cid in self?.resolvePeerIndependentContentSupported(cid) ?? false },
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
                // While an INDEPENDENT screen share is active the screen share
                // owns `content_state`; the camera-framing hint is suppressed for
                // the duration of the share and restored on stop
                // (cameraContentTypeAfterIndependentStop). Legacy mode (or
                // not sharing) emits the camera-framing hint as before.
                if self.config.enableIndependentContentVideo, self.diagnostics.isScreenSharing {
                    return
                }
                if mode.isContentMode {
                    let type = mode == .world ? ContentTypeWire.worldCamera : ContentTypeWire.compositeCamera
                    self.broadcastLocalContentState(active: true, contentType: type)
                } else if prev.isContentMode {
                    self.broadcastLocalContentState(active: false)
                }
            }
        }
        webRtcEngine.setOnFlashlightStateChanged { [weak self] available, enabled in
            Task { @MainActor in self?.commitSnapshot { _, d in d.isFlashAvailable = available; d.isFlashEnabled = enabled } }
        }
        webRtcEngine.setOnScreenShareStopped { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.cancelPendingScreenShareStartRequest()
                // Independent stop never preempted the camera, so do NOT re-apply
                // the camera preference (pitfall #6); the content track was
                // separate and torn down by the engine. Restore the
                // world/composite camera hint suppressed during the share.
                if self.config.enableIndependentContentVideo {
                    guard self.diagnostics.isScreenSharing else { return }
                    self.commitSnapshot { _, d in d.isScreenSharing = false }
                    if let type = self.cameraContentTypeAfterIndependentStop() {
                        self.broadcastLocalContentState(active: true, contentType: type)
                    } else {
                        self.broadcastLocalContentState(active: false)
                    }
                    return
                }
                self.commitSnapshot { _, d in d.isScreenSharing = false; d.cameraZoomFactor = 1 }
                self.broadcastLocalContentState(active: false)
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
