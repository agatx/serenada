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
///
/// With the host app's existing CallManager (bridge mode):
/// ```swift
/// SerenadaCallFlow(
///     uiState: callManager.uiState,
///     roomId: roomId,
///     serverHost: serverHost,
///     rendererProvider: callManager,
///     onToggleAudio: { callManager.toggleAudio() },
///     ...
///     onDismiss: { dismiss() }
/// )
/// ```
public struct SerenadaCallFlow: View {
    private let mode: Mode
    private let config: SerenadaCallFlowConfig
    private let strings: [SerenadaString: String]?
    private let onDismiss: (() -> Void)?
    private let onCallEnded: ((EndReason) -> Void)?

    @Environment(\.serenadaTheme) private var theme

    private enum Mode {
        case urlFirst(url: URL, serenadaConfig: SerenadaConfig)
        case sessionFirst(session: SerenadaSession)
        case bridge(BridgeParams)
    }

    struct BridgeParams {
        let uiState: CallUiState
        let roomId: String
        let serverHost: String
        let roomName: String?
        let rendererProvider: CallRendererProvider
        let initialRemoteVideoFitCover: Bool
        let onToggleAudio: () -> Void
        let onToggleVideo: () -> Void
        let onFlipCamera: () -> Void
        let onToggleScreenShare: () -> Void
        let onAdjustCameraZoom: (CGFloat) -> Void
        let onResetCameraZoom: () -> Void
        let onToggleFlashlight: () -> Void
        let onEndCall: () -> Void
        let onInviteToRoom: () async -> Result<Void, Error>
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
        config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
        strings: [SerenadaString: String]? = nil,
        onDismiss: (() -> Void)? = nil
    ) {
        self.mode = .sessionFirst(session: session)
        self.config = config
        self.strings = strings
        self.onDismiss = onDismiss
        self.onCallEnded = nil
    }

    // MARK: - Bridge init (for host apps using their own CallManager)

    public init(
        uiState: CallUiState,
        roomId: String,
        serverHost: String,
        roomName: String? = nil,
        rendererProvider: CallRendererProvider,
        initialRemoteVideoFitCover: Bool = true,
        config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
        strings: [SerenadaString: String]? = nil,
        onToggleAudio: @escaping () -> Void,
        onToggleVideo: @escaping () -> Void,
        onFlipCamera: @escaping () -> Void,
        onToggleScreenShare: @escaping () -> Void,
        onAdjustCameraZoom: @escaping (CGFloat) -> Void,
        onResetCameraZoom: @escaping () -> Void,
        onToggleFlashlight: @escaping () -> Void,
        onEndCall: @escaping () -> Void,
        onInviteToRoom: @escaping () async -> Result<Void, Error>,
        onRemoteVideoFitChanged: ((Bool) -> Void)? = nil,
        onDismiss: (() -> Void)? = nil,
        onCallEnded: ((EndReason) -> Void)? = nil
    ) {
        self.mode = .bridge(BridgeParams(
            uiState: uiState,
            roomId: roomId,
            serverHost: serverHost,
            roomName: roomName,
            rendererProvider: rendererProvider,
            initialRemoteVideoFitCover: initialRemoteVideoFitCover,
            onToggleAudio: onToggleAudio,
            onToggleVideo: onToggleVideo,
            onFlipCamera: onFlipCamera,
            onToggleScreenShare: onToggleScreenShare,
            onAdjustCameraZoom: onAdjustCameraZoom,
            onResetCameraZoom: onResetCameraZoom,
            onToggleFlashlight: onToggleFlashlight,
            onEndCall: onEndCall,
            onInviteToRoom: onInviteToRoom,
            onRemoteVideoFitChanged: onRemoteVideoFitChanged
        ))
        self.config = config
        self.strings = strings
        self.onDismiss = onDismiss
        self.onCallEnded = onCallEnded
    }

    public var body: some View {
        switch mode {
        case .urlFirst(let url, let serenadaConfig):
            URLFirstCallFlow(
                url: url,
                serenadaConfig: serenadaConfig,
                config: config,
                strings: strings,
                onDismiss: onDismiss
            )

        case .sessionFirst(let session):
            SessionFirstCallFlow(
                session: session,
                config: config,
                strings: strings,
                onDismiss: onDismiss
            )

        case .bridge(let params):
            CallScreenView(
                roomId: params.roomId,
                uiState: params.uiState,
                serverHost: params.serverHost,
                roomName: params.roomName,
                config: config,
                strings: strings,
                onToggleAudio: params.onToggleAudio,
                onToggleVideo: params.onToggleVideo,
                onFlipCamera: params.onFlipCamera,
                onToggleScreenShare: params.onToggleScreenShare,
                onAdjustCameraZoom: params.onAdjustCameraZoom,
                onResetCameraZoom: params.onResetCameraZoom,
                onToggleFlashlight: params.onToggleFlashlight,
                onEndCall: params.onEndCall,
                onInviteToRoom: params.onInviteToRoom,
                rendererProvider: params.rendererProvider,
                initialRemoteVideoFitCover: params.initialRemoteVideoFitCover
            )
        }
    }

    /// Callback for when the call ends.
    public func onCallEnded(_ handler: @escaping (EndReason) -> Void) -> SerenadaCallFlow {
        var copy = self
        // Return a modified copy with the handler set
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

    @State private var session: SerenadaSession?
    @State private var permissionsGranted = false

    var body: some View {
        Group {
            if let session {
                SessionFirstCallFlow(
                    session: session,
                    config: config,
                    strings: strings,
                    onDismiss: onDismiss
                )
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black)
            }
        }
        .task {
            let core = SerenadaCore(config: serenadaConfig)
            let newSession = core.join(url: url)
            session = newSession

            // Auto-prompt for permissions in URL-first mode
            let granted = await SerenadaPermissions.request([.camera, .microphone])
            if granted {
                newSession.resumeJoin()
            } else {
                newSession.cancelJoin()
            }
        }
    }
}

// MARK: - Session-first flow

private struct SessionFirstCallFlow: View {
    @ObservedObject var session: SerenadaSession
    let config: SerenadaCallFlowConfig
    let strings: [SerenadaString: String]?
    let onDismiss: (() -> Void)?

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
                    onAdjustCameraZoom: { _ in },
                    onResetCameraZoom: {},
                    onToggleFlashlight: {},
                    onEndCall: {
                        session.end()
                        onDismiss?()
                    },
                    onInviteToRoom: { .failure(NSError(domain: "SerenadaCallUI", code: 0, userInfo: [NSLocalizedDescriptionKey: "Not implemented"])) },
                    rendererProvider: SessionRendererAdapter(session: session)
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
        var uiState = CallUiState()
        uiState.phase = mapPhase(state.phase)
        uiState.roomId = state.roomId
        uiState.localCid = state.localParticipant.cid
        uiState.isHost = state.localParticipant.isHost
        uiState.localAudioEnabled = state.localParticipant.audioEnabled
        uiState.localVideoEnabled = state.localParticipant.videoEnabled
        uiState.localCameraMode = state.localParticipant.cameraMode
        uiState.connectionStatus = mapConnectionStatus(state.connectionStatus)
        uiState.activeTransport = state.activeTransport
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

// MARK: - Session Renderer Adapter

@MainActor
private final class SessionRendererAdapter: CallRendererProvider {
    private let session: SerenadaSession

    init(session: SerenadaSession) {
        self.session = session
    }

    func attachLocalRenderer(_ renderer: AnyObject) {
        session.attachLocalRenderer(renderer)
    }

    func detachLocalRenderer(_ renderer: AnyObject) {
        session.detachLocalRenderer(renderer)
    }

    func attachRemoteRenderer(_ renderer: AnyObject) {
        if let firstCid = session.state.remoteParticipants.first?.cid {
            session.attachRemoteRenderer(renderer, forParticipant: firstCid)
        }
    }

    func detachRemoteRenderer(_ renderer: AnyObject) {
        // Detach from first remote participant
    }

    func attachRemoteRenderer(_ renderer: AnyObject, forCid cid: String) {
        session.attachRemoteRenderer(renderer, forParticipant: cid)
    }

    func detachRemoteRenderer(_ renderer: AnyObject, forCid cid: String) {
        // Detach from specific remote participant
    }
}
