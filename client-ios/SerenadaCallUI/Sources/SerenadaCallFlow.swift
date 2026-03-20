import SerenadaCore
import SwiftUI

func shouldTerminateRoomOnEndTap(isHost: Bool) -> Bool {
    isHost
}

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
    private var onCallEnded: ((EndReason) -> Void)?

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
    }

    // MARK: - URL-first init

    public init(
        url: URL,
        serenadaConfig: SerenadaConfig = SerenadaConfig(serverHost: "serenada.app"),
        config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
        strings: [SerenadaString: String]? = nil,
        onDismiss: (() -> Void)? = nil
    ) {
        self.mode = .urlFirst(url: url, serenadaConfig: serenadaConfig)
        self.config = config
        self.strings = strings
        self.onDismiss = onDismiss
        self.onCallEnded = nil
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
        onDismiss: (() -> Void)? = nil
    ) {
        self.mode = .sessionFirst(SessionParams(
            session: session,
            roomName: roomName,
            initialRemoteVideoFitCover: initialRemoteVideoFitCover,
            onInviteToRoom: onInviteToRoom,
            onRemoteVideoFitChanged: onRemoteVideoFitChanged
        ))
        self.config = config
        self.strings = strings
        self.onDismiss = onDismiss
        self.onCallEnded = nil
    }

    public var body: some View {
        switch mode {
        case .urlFirst(let url, let serenadaConfig):
            URLFirstCallFlow(
                url: url,
                serenadaConfig: serenadaConfig,
                config: config,
                strings: strings,
                onDismiss: onDismiss,
                onCallEnded: onCallEnded
            )

        case .sessionFirst(let params):
            SessionFirstCallFlow(
                params: params,
                config: config,
                strings: strings,
                onDismiss: onDismiss,
                onCallEnded: onCallEnded
            )
        }
    }

    /// Callback for when the call ends.
    public func onCallEnded(_ handler: @escaping (EndReason) -> Void) -> SerenadaCallFlow {
        var copy = self
        copy.onCallEnded = handler
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
    let onCallEnded: ((EndReason) -> Void)?

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
                        onRemoteVideoFitChanged: nil
                    ),
                    config: config,
                    strings: strings,
                    onDismiss: onDismiss,
                    onCallEnded: onCallEnded
                )
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black)
            }
        }
        .task {
            let newCore = SerenadaCore(config: serenadaConfig)
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
    let onCallEnded: ((EndReason) -> Void)?

    @ObservedObject private var session: SerenadaSession

    init(
        params: SerenadaCallFlow.SessionParams,
        config: SerenadaCallFlowConfig,
        strings: [SerenadaString: String]?,
        onDismiss: (() -> Void)?,
        onCallEnded: ((EndReason) -> Void)?
    ) {
        self.params = params
        self.config = config
        self.strings = strings
        self.onDismiss = onDismiss
        self.onCallEnded = onCallEnded
        _session = ObservedObject(wrappedValue: params.session)
    }

    var body: some View {
        let state = session.state
        let phase = state.phase

        Group {
            switch phase {
            case .idle, .joining:
                VStack(spacing: 16) {
                    ProgressView()
                    Text(resolveString(.callJoining, overrides: strings))
                        .foregroundStyle(.white)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)

            case .awaitingPermissions:
                VStack(spacing: 16) {
                    Text(resolveString(.callPermissionsRequired, overrides: strings))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                    Button("Grant Access") {
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
                    .buttonStyle(.borderedProminent)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)

            case .waiting, .inCall:
                // Session-first mode renders through bridge using session as renderer provider
                CallScreenView(
                    roomId: session.roomId,
                    uiState: mapSessionToUiState(session),
                    serverHost: session.serverHost,
                    screenShareExtensionBundleId: session.screenShareExtensionBundleId,
                    roomName: params.roomName,
                    config: config,
                    strings: strings,
                    onToggleAudio: { session.toggleAudio() },
                    onToggleVideo: { session.toggleVideo() },
                    onFlipCamera: { session.flipCamera() },
                    onToggleScreenShare: {
                        if session.state.localParticipant.cameraMode == .screenShare {
                            session.stopScreenShare()
                        } else {
                            session.startScreenShare()
                        }
                    },
                    onAdjustCameraZoom: { _ = session.adjustCameraZoom(by: $0) },
                    onResetCameraZoom: { _ = session.resetCameraZoom() },
                    onToggleFlashlight: { _ = session.toggleFlashlight() },
                    onEndCall: {
                        if shouldTerminateRoomOnEndTap(isHost: session.state.localParticipant.isHost) {
                            session.end()
                        } else {
                            session.leave()
                        }
                        onCallEnded?(.localLeft)
                        onDismiss?()
                    },
                    onInviteToRoom: params.onInviteToRoom ?? {
                        .failure(NSError(domain: "SerenadaCallUI", code: 0, userInfo: [NSLocalizedDescriptionKey: "Not implemented"]))
                    },
                    rendererProvider: session,
                    initialRemoteVideoFitCover: params.initialRemoteVideoFitCover,
                    onRemoteVideoFitChanged: params.onRemoteVideoFitChanged
                )

            case .ending:
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

            case .error:
                VStack(spacing: 16) {
                    Text(resolveString(.callErrorGeneric, overrides: strings))
                        .foregroundStyle(.white)
                    if let onDismiss {
                        Button("Dismiss") { onDismiss() }
                            .buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)
            }
        }
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
        uiState.cameraZoomFactor = diagnostics.cameraZoomFactor
        uiState.isFlashAvailable = diagnostics.isFlashAvailable
        uiState.isFlashEnabled = diagnostics.isFlashEnabled
        uiState.remoteContentCid = diagnostics.remoteContentParticipantId
        uiState.remoteContentType = diagnostics.remoteContentType
        uiState.remoteParticipants = state.remoteParticipants.map { rp in
            RemoteParticipant(
                cid: rp.cid,
                videoEnabled: rp.videoEnabled,
                connectionState: rp.connectionState
            )
        }
        uiState.participantCount = 1 + state.remoteParticipants.count
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
}
