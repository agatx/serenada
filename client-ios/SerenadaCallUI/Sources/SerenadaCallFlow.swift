import SerenadaCore
import SwiftUI

/// The main entry point for the Serenada call UI flow.
/// Handles the entire visual sequence from joining through call end.
///
/// URL-first (simplest):
/// ```swift
/// SerenadaCallFlow(url: serenadaURL, onDismiss: { dismiss() })
/// ```
///
/// Session-first (for pre-observation):
/// ```swift
/// let session = serenada.join(url: url)
/// SerenadaCallFlow(session: session, onDismiss: { dismiss() })
/// ```
public struct SerenadaCallFlow: View {
    private let mode: Mode
    private let config: SerenadaCallFlowConfig
    private let strings: [SerenadaString: String]?
    private let onDismiss: (() -> Void)?
    private let onEndCall: (() -> Void)?
    private var onCallEnded: ((EndReason) -> Void)?
    private var onSnapshotCaptured: ((SnapshotResult) -> Void)?
    private var onSnapshotError: ((SnapshotError) -> Void)?

    @Environment(\.serenadaTheme) private var theme

    private enum Mode {
        case urlFirst(url: URL, serenadaConfig: SerenadaConfig)
        case sessionFirst(SessionParams)
    }

    struct SessionParams {
        let session: SerenadaSession
        let roomName: String?
        let initialRemoteVideoFitCover: Bool
        let onInviteToRoom: (() async -> Result<Void, Error>)?
        let onRemoteVideoFitChanged: ((Bool) -> Void)?
        /// Local `SerenadaConfig.enableIndependentContentVideo`. Drives whether
        /// the UI renders a remote share as a dedicated independent content tile
        /// (per-peer, gated further by the peer's advertised capability) or via
        /// the legacy single-video path. `config` is private on the session, so
        /// the URL-first flow (which owns the config) threads it here; the
        /// session-first public initializer defaults to `false` so the bundled
        /// flow stays byte-identical to the legacy presentation. Hosts that flip
        /// the SDK flag pass it via the `independentContentVideo:` initializer.
        var independentContentEnabled: Bool = false
        /// Local `SerenadaConfig.videoMediaEnabled`. When `false` the local user
        /// is an audio-only receiver and ALL content UI is suppressed. Defaults
        /// to `true` (the SDK default), matching the legacy behavior.
        var videoMediaEnabled: Bool = true
    }

    // MARK: - URL-first init

    public init(
        url: URL,
        serenadaConfig: SerenadaConfig = SerenadaConfig(serverHost: "serenada.app"),
        config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
        strings: [SerenadaString: String]? = nil,
        onEndCall: (() -> Void)? = nil,
        onDismiss: (() -> Void)? = nil
    ) {
        self.mode = .urlFirst(url: url, serenadaConfig: serenadaConfig)
        self.config = config
        self.strings = strings
        self.onDismiss = onDismiss
        self.onEndCall = onEndCall
        self.onCallEnded = nil
        self._avatarCache = StateObject(wrappedValue: AvatarCache(provider: config.avatarProvider))
    }

    // MARK: - Session-first init

    public init(
        session: SerenadaSession,
        roomName: String? = nil,
        initialRemoteVideoFitCover: Bool = true,
        config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
        strings: [SerenadaString: String]? = nil,
        onInviteToRoom: (() async -> Result<Void, Error>)? = nil,
        onRemoteVideoFitChanged: ((Bool) -> Void)? = nil,
        onEndCall: (() -> Void)? = nil,
        onDismiss: (() -> Void)? = nil,
        // Independent screen share (Phase 4b). `SerenadaConfig` is private on the
        // session, so a host that builds its own session with
        // `enableIndependentContentVideo=true` must echo the same two flags here
        // to opt the prebuilt UI into independent content rendering. Default
        // off/on keeps the bundled flow byte-identical to the legacy
        // presentation. The SDK flag is the single switch gating both core and UI.
        independentContentVideo: Bool = false,
        videoMediaEnabled: Bool = true
    ) {
        self.mode = .sessionFirst(SessionParams(
            session: session,
            roomName: roomName,
            initialRemoteVideoFitCover: initialRemoteVideoFitCover,
            onInviteToRoom: onInviteToRoom,
            onRemoteVideoFitChanged: onRemoteVideoFitChanged,
            independentContentEnabled: independentContentVideo,
            videoMediaEnabled: videoMediaEnabled
        ))
        self.config = config
        self.strings = strings
        self.onDismiss = onDismiss
        self.onEndCall = onEndCall
        self.onCallEnded = nil
        self._avatarCache = StateObject(wrappedValue: AvatarCache(provider: config.avatarProvider))
    }

    @StateObject private var avatarCache: AvatarCache

    public var body: some View {
        Group {
            switch mode {
            case .urlFirst(let url, let serenadaConfig):
                URLFirstCallFlow(
                    url: url,
                    serenadaConfig: serenadaConfig,
                    config: config,
                    strings: strings,
                    onDismiss: onDismiss,
                    onEndCall: onEndCall,
                    onCallEnded: onCallEnded,
                    onSnapshotCaptured: onSnapshotCaptured,
                    onSnapshotError: onSnapshotError
                )

            case .sessionFirst(let params):
                SessionFirstCallFlow(
                    params: params,
                    config: config,
                    strings: strings,
                    onDismiss: onDismiss,
                    onEndCall: onEndCall,
                    onCallEnded: onCallEnded,
                    onSnapshotCaptured: onSnapshotCaptured,
                    onSnapshotError: onSnapshotError
                )
            }
        }
        .environment(\.avatarCache, avatarCache)
    }

    /// Callback for when the call ends.
    public func onCallEnded(_ handler: @escaping (EndReason) -> Void) -> SerenadaCallFlow {
        var copy = self
        copy.onCallEnded = handler
        return copy
    }

    /// Callback fired when the user taps the snapshot shutter (gated by
    /// `SerenadaCallFlowConfig.snapshotEnabled`) and a frame is captured.
    public func onSnapshotCaptured(_ handler: @escaping (SnapshotResult) -> Void) -> SerenadaCallFlow {
        var copy = self
        copy.onSnapshotCaptured = handler
        return copy
    }

    /// Callback fired when a snapshot capture fails — for example because the
    /// chosen stream's video is off.
    public func onSnapshotError(_ handler: @escaping (SnapshotError) -> Void) -> SerenadaCallFlow {
        var copy = self
        copy.onSnapshotError = handler
        return copy
    }
}

// MARK: - URL-first flow

private struct URLFirstCallFlow: View {
    let url: URL
    let serenadaConfig: SerenadaConfig
    let config: SerenadaCallFlowConfig
    let strings: [SerenadaString: String]?
    let onDismiss: (() -> Void)?
    let onEndCall: (() -> Void)?
    let onCallEnded: ((EndReason) -> Void)?
    let onSnapshotCaptured: ((SnapshotResult) -> Void)?
    let onSnapshotError: ((SnapshotError) -> Void)?

    @State private var session: SerenadaSession?
    @State private var core: SerenadaCore?

    var body: some View {
        Group {
            if let session {
                SessionFirstCallFlow(
                    params: SerenadaCallFlow.SessionParams(
                        session: session,
                        roomName: nil,
                        initialRemoteVideoFitCover: true,
                        onInviteToRoom: nil,
                        onRemoteVideoFitChanged: nil,
                        // The URL-first flow owns the SerenadaConfig, so it knows
                        // both independent-content flags and threads them to the
                        // UI directly (the session does not expose its config).
                        independentContentEnabled: serenadaConfig.enableIndependentContentVideo,
                        videoMediaEnabled: serenadaConfig.videoMediaEnabled
                    ),
                    config: config,
                    strings: strings,
                    onDismiss: onDismiss,
                    onEndCall: onEndCall,
                    onCallEnded: onCallEnded,
                    onSnapshotCaptured: onSnapshotCaptured,
                    onSnapshotError: onSnapshotError
                )
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black)
            }
        }
        .task {
            var effectiveConfig = serenadaConfig
            if !config.videoEnabled {
                effectiveConfig.cameraModes = []
            } else if config.uiVariant == .frontline {
                effectiveConfig.defaultVideoEnabled = false
                effectiveConfig.cameraModes = [.world, .selfie, .composite]
            }
            let newCore = SerenadaCore(config: effectiveConfig)
            core = newCore
            let newSession = newCore.join(url: url)
            newSession.onPermissionsRequired = { permissions in
                Task {
                    let granted = await SerenadaPermissions.request(permissions)
                    if granted {
                        newSession.resumeJoin()
                    } else {
                        newSession.cancelJoin()
                        onDismiss?()
                    }
                }
            }
            session = newSession
        }
    }
}

// MARK: - Session-first flow

private struct SessionFirstCallFlow: View {
    let params: SerenadaCallFlow.SessionParams
    let config: SerenadaCallFlowConfig
    let strings: [SerenadaString: String]?
    let onDismiss: (() -> Void)?
    let onEndCall: (() -> Void)?
    let onCallEnded: ((EndReason) -> Void)?
    let onSnapshotCaptured: ((SnapshotResult) -> Void)?
    let onSnapshotError: ((SnapshotError) -> Void)?

    @ObservedObject private var session: SerenadaSession
    @State private var systemPictureInPictureSource: SystemPictureInPictureSource = .remote(cid: nil)
    @State private var systemPictureInPictureSourceFrame: CGRect?
    /// Local receive order of active remote content owners (most recent LAST),
    /// used to pick the primary among multiple simultaneous sharers (design
    /// "Multiple Sharers", local receive order — no server-stamped ordering).
    @State private var remoteContentOrder: [String] = []

    init(
        params: SerenadaCallFlow.SessionParams,
        config: SerenadaCallFlowConfig,
        strings: [SerenadaString: String]?,
        onDismiss: (() -> Void)?,
        onEndCall: (() -> Void)?,
        onCallEnded: ((EndReason) -> Void)?,
        onSnapshotCaptured: ((SnapshotResult) -> Void)? = nil,
        onSnapshotError: ((SnapshotError) -> Void)? = nil
    ) {
        self.params = params
        self.config = config
        self.strings = strings
        self.onDismiss = onDismiss
        self.onEndCall = onEndCall
        self.onCallEnded = onCallEnded
        self.onSnapshotCaptured = onSnapshotCaptured
        self.onSnapshotError = onSnapshotError
        _session = ObservedObject(wrappedValue: params.session)
    }

    var body: some View {
        let state = session.state
        let phase = state.phase
        let callUiState = mapSessionToUiState(session)

        Group {
            switch phase {
            case .idle, .joining:
                if config.uiVariant == .frontline {
                    frontlineCallScreen(state: state, callUiState: callUiState)
                } else {
                    VStack(spacing: 16) {
                        ProgressView()
                        Text(resolveString(.callJoining, overrides: strings))
                            .foregroundStyle(.white)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black)
                }

            case .awaitingPermissions:
                if config.uiVariant == .frontline {
                    frontlineCallScreen(state: state, callUiState: callUiState)
                } else {
                    VStack(spacing: 16) {
                        Text(resolveString(.callPermissionsRequired, overrides: strings))
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.center)
                        Button(resolveString(.callGrantAccess, overrides: strings)) {
                            requestPermissions(state: state)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black)
                }

            case .waiting, .inCall:
                if config.uiVariant == .frontline {
                    frontlineCallScreen(state: state, callUiState: callUiState)
                } else {
                    // Session-first mode renders through bridge using session as renderer provider
                    let contentScene = resolveScene(state)
                    CallScreenView(
                        roomId: session.roomId,
                        uiState: callUiState,
                        roomShareURL: session.roomUrl,
                        screenShareExtensionBundleId: session.screenShareExtensionBundleId,
                        screenShareAvailable: session.isScreenShareAvailable,
                        roomName: params.roomName,
                        config: config,
                        strings: strings,
                        onToggleAudio: { session.toggleAudio() },
                        onToggleVideo: { session.toggleVideo() },
                        onFlipCamera: { session.flipCamera() },
                        onToggleScreenShare: toggleScreenShare,
                        onAdjustCameraZoom: { _ = session.adjustCameraZoom(by: $0) },
                        onResetCameraZoom: { _ = session.resetCameraZoom() },
                        onToggleFlashlight: { _ = session.toggleFlashlight() },
                        onEndCall: endCall,
                        onInviteToRoom: inviteToRoom,
                        onSnapshotRequested: makeSnapshotHandler(),
                        rendererProvider: session,
                        contentScene: contentScene,
                        initialRemoteVideoFitCover: params.initialRemoteVideoFitCover,
                        onRemoteVideoFitChanged: params.onRemoteVideoFitChanged,
                        onSystemPictureInPictureSourceChanged: { systemPictureInPictureSource = $0 },
                        onSystemPictureInPictureSourceFrameChanged: { systemPictureInPictureSourceFrame = $0 }
                    )
                }

            case .ending:
                if config.uiVariant == .frontline {
                    frontlineCallScreen(state: state, callUiState: callUiState)
                        .onAppear {
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                onDismiss?()
                            }
                        }
                } else {
                    VStack(spacing: 16) {
                        Text(resolveString(.callEnded, overrides: strings))
                            .foregroundStyle(.white)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black)
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                            onDismiss?()
                        }
                    }
                }

            case .error:
                if config.uiVariant == .frontline {
                    frontlineCallScreen(state: state, callUiState: callUiState)
                } else {
                    VStack(spacing: 16) {
                        Text(resolveString(.callErrorGeneric, overrides: strings))
                            .foregroundStyle(.white)
                        if let onDismiss {
                            Button(resolveString(.callDismiss, overrides: strings)) { onDismiss() }
                                .buttonStyle(.borderedProminent)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black)
                }
            }
        }
        .coordinateSpace(name: SystemPictureInPictureCoordinateSpace.name)
        .overlay {
            if config.systemPictureInPictureEnabled {
                SystemPictureInPictureLayer(
                    enabled: true,
                    uiState: callUiState,
                    source: systemPictureInPictureSource,
                    sourceFrame: systemPictureInPictureSourceFrame,
                    rendererProvider: session
                )
            }
        }
        .onChange(of: activeRemoteContentCids(state)) { activeCids in
            // Maintain LOCAL receive order, most recent LAST: drop owners that
            // stopped sharing / left, then append newly-active cids at the end.
            // pickPrimaryContent walks this back-to-front to choose the primary
            // among multiple simultaneous sharers (design "Multiple Sharers").
            let active = Set(activeCids)
            var next = remoteContentOrder.filter { active.contains($0) }
            for cid in activeCids where !next.contains(cid) {
                next.append(cid)
            }
            if next != remoteContentOrder {
                remoteContentOrder = next
            }
        }
    }

    private func frontlineCallScreen(state: CallState, callUiState: CallUiState) -> some View {
        FrontlineCallScreenView(
            roomId: session.roomId,
            uiState: callUiState,
            sessionPhase: state.phase,
            roomShareURL: session.roomUrl,
            screenShareExtensionBundleId: session.screenShareExtensionBundleId,
            screenShareAvailable: session.isScreenShareAvailable,
            roomName: params.roomName,
            config: config,
            strings: strings,
            // Resolved independent-content scene (Phase 4b). Flag-off (the default)
            // ⇒ every owner LEGACY and `resolveFrontlineIndependentContent` returns
            // nil, so the Frontline legacy single-video-as-content path stays
            // byte-identical. Reuses the same pure resolver as the standard screen.
            contentScene: resolveScene(state),
            availableAudioDevices: session.availableAudioDevices,
            currentAudioDevice: session.currentAudioDevice,
            onToggleAudio: { session.toggleAudio() },
            onSelectAudioDevice: { session.selectAudioDevice($0) },
            onToggleVideo: { session.toggleVideo() },
            onFlipCamera: { session.flipCamera() },
            onToggleScreenShare: toggleScreenShare,
            onAdjustCameraZoom: { _ = session.adjustCameraZoom(by: $0) },
            onResetCameraZoom: { _ = session.resetCameraZoom() },
            onToggleFlashlight: { _ = session.toggleFlashlight() },
            onEndCall: endCall,
            onInviteToRoom: inviteToRoom,
            onRequestPermissions: { requestPermissions(state: state) },
            onDismiss: onDismiss,
            onSnapshotRequested: makeSnapshotHandler(),
            rendererProvider: session,
            initialRemoteVideoFitCover: params.initialRemoteVideoFitCover,
            onRemoteVideoFitChanged: params.onRemoteVideoFitChanged,
            onSystemPictureInPictureSourceChanged: { systemPictureInPictureSource = $0 },
            onSystemPictureInPictureSourceFrameChanged: { systemPictureInPictureSourceFrame = $0 }
        )
    }

    private func toggleScreenShare() {
        // Decide stop-vs-start from the ACTUAL sharing state, not
        // `cameraMode == .screenShare`. In INDEPENDENT mode the core never sets
        // `cameraMode = .screenShare` (the screen rides a separate content
        // track), so keying on cameraMode would make the slash button re-call
        // `startScreenShare()` and bail at the `isScreenSharing` guard. The
        // diagnostics flag is the authoritative signal and is true in BOTH the
        // legacy (cameraMode = .screenShare) and independent paths, so the
        // legacy stop decision stays identical.
        if session.diagnostics.isScreenSharing {
            session.stopScreenShare()
        } else {
            session.startScreenShare()
        }
    }

    private func endCall() {
        if let onEndCall {
            onEndCall()
            return
        }
        session.leave()
        onCallEnded?(.localLeft)
        onDismiss?()
    }

    private func inviteToRoom() async -> Result<Void, Error> {
        if let onInviteToRoom = params.onInviteToRoom {
            return await onInviteToRoom()
        }
        return .failure(NSError(domain: "SerenadaCallUI", code: 0, userInfo: [NSLocalizedDescriptionKey: "Not implemented"]))
    }

    private func requestPermissions(state: CallState) {
        Task {
            let granted = await SerenadaPermissions.request(
                state.requiredPermissions ?? [.camera, .microphone]
            )
            if granted {
                session.resumeJoin()
            } else {
                session.cancelJoin()
                onDismiss?()
            }
        }
    }

    private func makeSnapshotHandler() -> ((SnapshotSource) -> Void)? {
        guard config.snapshotEnabled,
              onSnapshotCaptured != nil || onSnapshotError != nil else {
            return nil
        }
        let session = self.session
        let onCaptured = onSnapshotCaptured
        let onError = onSnapshotError
        return { source in
            Task { @MainActor in
                do {
                    let result = try await session.captureSnapshot(source: source)
                    onCaptured?(result)
                } catch let snapshotError as SnapshotError {
                    onError?(snapshotError)
                } catch {
                    onError?(.captureFailed(error.localizedDescription))
                }
            }
        }
    }

    /// The set of remote cids that are currently presenting active content,
    /// in the call's stable participant order.
    private func activeRemoteContentCids(_ state: CallState) -> [String] {
        state.remoteParticipants
            .filter { !$0.presumedLost && $0.content?.active == true }
            .map { $0.cid }
    }

    /// Resolve the content scene from the rich session `CallState` + the local
    /// independent-content flags threaded via `SessionParams`. Pure resolver
    /// (``resolveContentScene``); flag-off ⇒ every owner LEGACY, byte-identical.
    private func resolveScene(_ state: CallState) -> ContentScene {
        let local = state.localParticipant
        let visibleRemotes = state.remoteParticipants.filter { !$0.presumedLost }
        let input = ResolveContentInput(
            local: local.cid.map { cid in
                ContentLocalParticipant(
                    cid: cid,
                    isScreenSharing: session.diagnostics.isScreenSharing,
                    cameraMode: local.cameraMode,
                    content: local.content
                )
            },
            remotes: visibleRemotes.map { rp in
                ContentRemoteParticipant(
                    cid: rp.cid,
                    content: rp.content,
                    supportsIndependentContentVideo: rp.supportsIndependentContentVideo
                )
            },
            independentContentEnabled: params.independentContentEnabled,
            localVideoMediaEnabled: params.videoMediaEnabled,
            // Core exposes no public "is a content track present" query (same as
            // Android). Independent mode is gated by the flag + per-peer
            // capability + content.active in the resolver; once active, we assume
            // media is present and the content sink renders as frames arrive (a
            // status overlay covers the brief connecting gap). On-device frame
            // liveness is not observable here.
            remoteContentHasMedia: { _ in true },
            localContentHasMedia: { true },
            remoteContentOrder: remoteContentOrder
        )
        return resolveContentScene(input)
    }

    private func mapSessionToUiState(_ session: SerenadaSession) -> CallUiState {
        let state = session.state
        let diagnostics = session.diagnostics
        var uiState = CallUiState()
        uiState.phase = mapPhase(state.phase)
        uiState.roomId = state.roomId
        uiState.localCid = state.localParticipant.cid
        uiState.isHost = state.localParticipant.isHost
        uiState.localAudioEnabled = state.localParticipant.audioEnabled
        uiState.localVideoEnabled = state.localParticipant.videoEnabled
        uiState.localDisplayName = state.localParticipant.displayName
        uiState.localAudioLevel = state.localParticipant.audioLevel
        uiState.localCameraMode = state.localParticipant.cameraMode
        uiState.connectionStatus = mapConnectionStatus(state.connectionStatus)
        uiState.activeTransport = diagnostics.activeTransport
        uiState.isSignalingConnected = diagnostics.isSignalingConnected
        uiState.iceConnectionState = diagnostics.iceConnectionState.rawValue
        uiState.connectionState = diagnostics.peerConnectionState.rawValue
        uiState.signalingState = diagnostics.rtcSignalingState.rawValue
        uiState.realtimeStats = diagnostics.realtimeStats
        uiState.isFrontCamera = diagnostics.isFrontCamera
        uiState.isScreenSharing = diagnostics.isScreenSharing
        uiState.availableCameraModes = state.localParticipant.availableCameraModes
        uiState.cameraZoomFactor = diagnostics.cameraZoomFactor
        uiState.isFlashAvailable = diagnostics.isFlashAvailable
        uiState.isFlashEnabled = diagnostics.isFlashEnabled
        uiState.remoteContentCid = diagnostics.remoteContentParticipantId
        uiState.remoteContentType = diagnostics.remoteContentType
        uiState.callStartedAtMs = state.callStartedAtMs
        // Hide presumed-lost remotes from the call grid — the SDK keeps their
        // peer connections open in case they reattach, but the active grid
        // should not display them. Host apps wanting different presentation
        // (e.g., a "connection lost" tile) can read presumedLost off the
        // SDK's state directly instead of using SerenadaCallFlow.
        let visibleRemotes = state.remoteParticipants.filter { !$0.presumedLost }
        uiState.remoteParticipants = visibleRemotes.map { rp in
            RemoteParticipant(
                cid: rp.cid,
                displayName: rp.displayName,
                peerId: rp.peerId,
                audioEnabled: rp.audioEnabled,
                videoEnabled: rp.videoEnabled,
                connectionState: rp.connectionState,
                audioLevel: rp.audioLevel
            )
        }
        uiState.participantCount = 1 + visibleRemotes.count
        return uiState
    }

    private func mapPhase(_ phase: SerenadaCallPhase) -> CallPhase {
        switch phase {
        case .idle: return .idle
        case .awaitingPermissions: return .idle
        case .joining: return .joining
        case .waiting: return .waiting
        case .inCall: return .inCall
        case .ending: return .idle
        case .error: return .error
        }
    }

    private func mapConnectionStatus(_ status: SerenadaConnectionStatus) -> ConnectionStatus {
        switch status {
        case .connected: return .connected
        case .recovering: return .recovering
        case .retrying: return .retrying
        }
    }
}

extension SerenadaSession: CallRendererProvider {
    public func attachRemoteRenderer(_ renderer: AnyObject, forCid cid: String) {
        attachRemoteRenderer(renderer, forParticipant: cid)
    }

    public func detachRemoteRenderer(_ renderer: AnyObject, forCid cid: String) {
        detachRemoteRenderer(renderer, forParticipant: cid)
    }

    // CallRendererProvider content bridge → SerenadaSession's content renderer
    // API (`forParticipant:`). Phase 4b independent screen share. The local
    // content renderer methods (`attachLocalContentRenderer(_:)` /
    // `detachLocalContentRenderer(_:)`) are already provided by SerenadaSession
    // (Phase 4a) and satisfy the protocol directly. No-ops when the SDK is
    // flag-off (the session has no content track / no content slot).
    public func attachRemoteContentRenderer(_ renderer: AnyObject, forCid cid: String) {
        attachRemoteContentRenderer(renderer, forParticipant: cid)
    }

    public func detachRemoteContentRenderer(_ renderer: AnyObject, forCid cid: String) {
        detachRemoteContentRenderer(renderer, forParticipant: cid)
    }
}
