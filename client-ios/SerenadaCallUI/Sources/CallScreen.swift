import os.log
import SerenadaCore
import SwiftUI

private let callScreenLog = OSLog(subsystem: "app.serenada.callui", category: "CallScreen")

func shouldShowCallStatusLabel(
    phase: CallPhase,
    connectionStatus: ConnectionStatus
) -> Bool {
    phase == .inCall && connectionStatus != .connected
}

func shouldShowWaitingOverlay(phase: CallPhase) -> Bool {
    phase == .waiting
}

func shouldShowLocalVideoPlaceholder(localVideoEnabled: Bool) -> Bool {
    !localVideoEnabled
}

func shouldShowRemoteVideoPlaceholder(phase: CallPhase, remoteVideoEnabled: Bool) -> Bool {
    !remoteVideoEnabled && phase == .inCall
}

func shouldShowRemoteFitButton(phase: CallPhase, remoteVideoEnabled: Bool, isLocalLarge: Bool, localVideoEnabled: Bool) -> Bool {
    phase == .inCall && remoteVideoEnabled && !(isLocalLarge && localVideoEnabled)
}

// When the local camera is off there's nothing meaningful to enlarge — force
// remote-as-primary so the user doesn't see a giant "Camera off" placeholder.
// The user's swap preference (`isLocalLarge`) is preserved and reapplied
// automatically when video comes back on.
func shouldRenderLocalAsPrimarySurface(phase: CallPhase, isLocalLarge: Bool, localVideoEnabled: Bool) -> Bool {
    (phase == .waiting || phase == .inCall) && isLocalLarge && localVideoEnabled
}

func shouldPreferLargeLocalPreview(localCameraMode: LocalCameraMode) -> Bool {
    localCameraMode == .world || localCameraMode == .composite
}

func shouldEnablePinchZoom(
    phase: CallPhase,
    isScreenSharing: Bool,
    showLocalAsPrimarySurface: Bool,
    localCameraMode: LocalCameraMode
) -> Bool {
    guard phase == .waiting || phase == .inCall else { return false }
    guard !isScreenSharing else { return false }
    guard showLocalAsPrimarySurface else { return false }
    return localCameraMode.isContentMode
}

func shouldUseBroadcastPicker(isScreenSharing: Bool, screenShareExtensionBundleId: String?) -> Bool {
    guard !isScreenSharing else { return false }
    guard let screenShareExtensionBundleId else { return false }
    return !screenShareExtensionBundleId.isEmpty
}

func primaryLocalVideoContentMode(localCameraMode: LocalCameraMode) -> UIView.ContentMode {
    switch localCameraMode {
    case .world, .composite:
        return .scaleAspectFit
    case .selfie, .screenShare:
        return .scaleAspectFill
    }
}

func pipBottomPadding(isLandscape: Bool, areControlsVisible: Bool) -> CGFloat {
    if isLandscape {
        return areControlsVisible ? 80 : 24
    }
    return areControlsVisible ? 140 : 52
}

enum DebugStatus {
    case good
    case warn
    case bad
    case na
}

struct DebugPanelMetric: Identifiable {
    let id = UUID()
    let label: String
    let value: String
    let status: DebugStatus
}

struct DebugPanelSection: Identifiable {
    let id = UUID()
    let title: String
    let metrics: [DebugPanelMetric]
}

func buildDebugPanelSections(uiState: CallUiState) -> [DebugPanelSection] {
    let stats = uiState.realtimeStats
    let signalingStatus: DebugStatus = uiState.isSignalingConnected ? .good : .bad
    let iceStatus: DebugStatus = {
        switch normalizeState(uiState.iceConnectionState) {
        case "connected", "completed":
            return .good
        case "checking", "disconnected":
            return .warn
        default:
            return .bad
        }
    }()
    let pcStatus: DebugStatus = {
        switch normalizeState(uiState.connectionState) {
        case "connected":
            return .good
        case "connecting", "disconnected":
            return .warn
        default:
            return .bad
        }
    }()
    let reconnectStatus: DebugStatus = uiState.connectionStatus == .connected ? .good : .bad

    let transportPathStatus: DebugStatus = {
        guard let path = stats.transportPath else { return .na }
        return path.hasPrefix("TURN relay") ? .warn : .good
    }()

    let rttStatus = lowerIsBetter(stats.rttMs, goodMax: 120, warnMax: 250)
    let outgoingHeadroomStatus = higherIsBetter(stats.availableOutgoingKbps, goodMin: 1500, warnMin: 600)
    let audioLossStatus = worstStatus(
        lowerIsBetter(stats.audioRxPacketLossPct, goodMax: 1, warnMax: 3),
        lowerIsBetter(stats.audioTxPacketLossPct, goodMax: 1, warnMax: 3)
    )
    let audioBitrateStatus = worstStatus(
        higherIsBetter(stats.audioRxKbps, goodMin: 20, warnMin: 12),
        higherIsBetter(stats.audioTxKbps, goodMin: 20, warnMin: 12)
    )
    let videoLossStatus = worstStatus(
        lowerIsBetter(stats.videoRxPacketLossPct, goodMax: 1, warnMax: 3),
        lowerIsBetter(stats.videoTxPacketLossPct, goodMax: 1, warnMax: 3)
    )
    let videoBitrateStatus = worstStatus(
        higherIsBetter(stats.videoRxKbps, goodMin: 900, warnMin: 350),
        higherIsBetter(stats.videoTxKbps, goodMin: 900, warnMin: 350)
    )

    return [
        DebugPanelSection(
            title: "Connection",
            metrics: [
                DebugPanelMetric(label: "Signaling", value: uiState.isSignalingConnected ? "connected" : "disconnected", status: signalingStatus),
                DebugPanelMetric(label: "Transport", value: uiState.activeTransport ?? "n/a", status: signalingStatus),
                DebugPanelMetric(label: "ICE / PC", value: "\(normalizeState(uiState.iceConnectionState)) / \(normalizeState(uiState.connectionState))", status: worstStatus(iceStatus, pcStatus)),
                DebugPanelMetric(label: "SDP", value: normalizeState(uiState.signalingState), status: normalizeState(uiState.signalingState) == "stable" ? .good : .warn),
                DebugPanelMetric(label: "Room", value: uiState.participantCount > 0 ? "\(uiState.participantCount) participants" : "none", status: uiState.participantCount > 0 ? .good : .warn),
                DebugPanelMetric(label: "Reconnecting", value: uiState.connectionStatus == .connected ? "no" : "yes", status: reconnectStatus)
            ]
        ),
        DebugPanelSection(
            title: "Latency",
            metrics: [
                DebugPanelMetric(label: "RTT", value: formatMs(stats.rttMs), status: rttStatus),
                DebugPanelMetric(label: "", value: stats.transportPath ?? "n/a", status: transportPathStatus),
                DebugPanelMetric(label: "Outgoing headroom", value: formatKbps(stats.availableOutgoingKbps), status: outgoingHeadroomStatus),
                DebugPanelMetric(label: "Updated", value: formatTimeLabel(stats.updatedAtMs == 0 ? nil : stats.updatedAtMs), status: .na)
            ]
        ),
        DebugPanelSection(
            title: "Audio Quality",
            metrics: [
                DebugPanelMetric(label: "Packet loss ⇵", value: "\(formatPercent(stats.audioRxPacketLossPct)) / \(formatPercent(stats.audioTxPacketLossPct))", status: audioLossStatus),
                DebugPanelMetric(label: "Jitter", value: formatMs(stats.audioJitterMs), status: lowerIsBetter(stats.audioJitterMs, goodMax: 20, warnMax: 40)),
                DebugPanelMetric(label: "Playout delay", value: formatMs(stats.audioPlayoutDelayMs), status: lowerIsBetter(stats.audioPlayoutDelayMs, goodMax: 80, warnMax: 180)),
                DebugPanelMetric(label: "Concealed audio", value: formatPercent(stats.audioConcealedPct), status: lowerIsBetter(stats.audioConcealedPct, goodMax: 2, warnMax: 8)),
                DebugPanelMetric(label: "Bitrate ⇵", value: "\(formatKbps(stats.audioRxKbps)) / \(formatKbps(stats.audioTxKbps))", status: audioBitrateStatus)
            ]
        ),
        DebugPanelSection(
            title: "Video Quality",
            metrics: [
                DebugPanelMetric(label: "Packet loss ⇵", value: "\(formatPercent(stats.videoRxPacketLossPct)) / \(formatPercent(stats.videoTxPacketLossPct))", status: videoLossStatus),
                DebugPanelMetric(label: "Bitrate ⇵", value: "\(formatKbps(stats.videoRxKbps)) / \(formatKbps(stats.videoTxKbps))", status: videoBitrateStatus),
                DebugPanelMetric(label: "Render FPS", value: formatFps(stats.videoFps), status: higherIsBetter(stats.videoFps, goodMin: 24, warnMin: 15)),
                DebugPanelMetric(label: "Resolution", value: stats.videoResolution ?? "n/a", status: stats.videoResolution == nil ? .na : .good),
                DebugPanelMetric(label: "Freezes (last 60s)", value: formatFreezeWindow(stats.videoFreezeCount60s, stats.videoFreezeDuration60s), status: worstStatus(
                    lowerIsBetter(stats.videoFreezeCount60s.map(Double.init), goodMax: 0, warnMax: 2),
                    lowerIsBetter(stats.videoFreezeDuration60s, goodMax: 0.2, warnMax: 1)
                )),
                DebugPanelMetric(label: "Retransmit", value: formatPercent(stats.videoRetransmitPct), status: lowerIsBetter(stats.videoRetransmitPct, goodMax: 1, warnMax: 3))
            ]
        )
    ]
}

func normalizeState(_ value: String?) -> String {
    let normalized = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    return normalized.isEmpty ? "n/a" : normalized
}

func formatMs(_ value: Double?) -> String {
    guard let value else { return "n/a" }
    return "\(Int(value.rounded())) ms"
}

func formatPercent(_ value: Double?) -> String {
    guard let value else { return "n/a" }
    return String(format: "%.1f%%", value)
}

func formatKbps(_ value: Double?) -> String {
    guard let value else { return "n/a" }
    return "\(Int(value.rounded())) kbps"
}

func formatFps(_ value: Double?) -> String {
    guard let value else { return "n/a" }
    return String(format: "%.1f fps", value)
}

func formatFreezeWindow(_ count: Int64?, _ durationSeconds: Double?) -> String {
    guard let count, let durationSeconds else { return "n/a" }
    return "\(count) / \(String(format: "%.1f", durationSeconds))s"
}

func formatTimeLabel(_ timestampMs: Int64?) -> String {
    guard let timestampMs else { return "n/a" }
    let formatter = DateFormatter()
    formatter.dateFormat = "HH:mm:ss"
    return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000.0))
}

func lowerIsBetter(_ value: Double?, goodMax: Double, warnMax: Double) -> DebugStatus {
    guard let value else { return .na }
    if value <= goodMax { return .good }
    if value <= warnMax { return .warn }
    return .bad
}

func higherIsBetter(_ value: Double?, goodMin: Double, warnMin: Double) -> DebugStatus {
    guard let value else { return .na }
    if value >= goodMin { return .good }
    if value >= warnMin { return .warn }
    return .bad
}

func worstStatus(_ statuses: DebugStatus...) -> DebugStatus {
    let concrete = statuses.filter { $0 != .na }
    guard !concrete.isEmpty else { return .na }
    if concrete.contains(.bad) { return .bad }
    if concrete.contains(.warn) { return .warn }
    return .good
}

struct CallScreenView: View {
    let roomId: String
    let uiState: CallUiState
    let roomShareURL: URL?
    let screenShareExtensionBundleId: String?
    let roomName: String?
    let config: SerenadaCallFlowConfig
    let strings: [SerenadaString: String]?
    let onToggleAudio: () -> Void
    let onToggleVideo: () -> Void
    let onFlipCamera: () -> Void
    let onToggleScreenShare: () -> Void
    let onAdjustCameraZoom: (CGFloat) -> Void
    let onResetCameraZoom: () -> Void
    let onToggleFlashlight: () -> Void
    let onEndCall: () -> Void
    let onInviteToRoom: () async -> Result<Void, Error>
    let onSnapshotRequested: ((SnapshotSource) -> Void)?
    let rendererProvider: CallRendererProvider
    let initialRemoteVideoFitCover: Bool
    let onRemoteVideoFitChanged: ((Bool) -> Void)?
    let onSystemPictureInPictureSourceChanged: ((SystemPictureInPictureSource) -> Void)?
    let onSystemPictureInPictureSourceFrameChanged: ((CGRect?) -> Void)?

    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var areControlsVisible = true
    @State private var isControlsAutoHideEnabled = true
    @State private var wereControlsLastHiddenByAutoHide = false
    @State private var isLocalLarge = false
    @State private var remoteVideoFitCover: Bool
    @State private var showShareSheet = false
    @State private var inviteStatusMessage: String?
    @State private var showDebugPanel = false
    @State private var lastDebugTapAt: Date?
    @State private var lastMagnificationValue: CGFloat = 1
    @State private var showRecoveringBadge = false
    @State private var remoteTileAspectRatios: [String: CGFloat] = [:]
    @State private var pinnedParticipantId: String?

    init(
        roomId: String,
        uiState: CallUiState,
        roomShareURL: URL?,
        screenShareExtensionBundleId: String? = nil,
        roomName: String? = nil,
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
        onSnapshotRequested: ((SnapshotSource) -> Void)? = nil,
        rendererProvider: CallRendererProvider,
        initialRemoteVideoFitCover: Bool = true,
        onRemoteVideoFitChanged: ((Bool) -> Void)? = nil,
        onSystemPictureInPictureSourceChanged: ((SystemPictureInPictureSource) -> Void)? = nil,
        onSystemPictureInPictureSourceFrameChanged: ((CGRect?) -> Void)? = nil
    ) {
        self.roomId = roomId
        self.uiState = uiState
        self.roomShareURL = roomShareURL
        self.screenShareExtensionBundleId = screenShareExtensionBundleId
        self.roomName = roomName
        self.config = config
        self.strings = strings
        self.onToggleAudio = onToggleAudio
        self.onToggleVideo = onToggleVideo
        self.onFlipCamera = onFlipCamera
        self.onToggleScreenShare = onToggleScreenShare
        self.onAdjustCameraZoom = onAdjustCameraZoom
        self.onResetCameraZoom = onResetCameraZoom
        self.onToggleFlashlight = onToggleFlashlight
        self.onEndCall = onEndCall
        self.onInviteToRoom = onInviteToRoom
        self.onSnapshotRequested = onSnapshotRequested
        self.rendererProvider = rendererProvider
        self.initialRemoteVideoFitCover = initialRemoteVideoFitCover
        self.onRemoteVideoFitChanged = onRemoteVideoFitChanged
        self.onSystemPictureInPictureSourceChanged = onSystemPictureInPictureSourceChanged
        self.onSystemPictureInPictureSourceFrameChanged = onSystemPictureInPictureSourceFrameChanged
        _remoteVideoFitCover = State(initialValue: initialRemoteVideoFitCover)
        _isControlsAutoHideEnabled = State(initialValue: config.autoHideControls)
        _areControlsVisible = State(initialValue: true)
        _isLocalLarge = State(initialValue: shouldPreferLargeLocalPreview(localCameraMode: uiState.localCameraMode))
    }

    private func str(_ key: SerenadaString) -> String {
        resolveString(key, overrides: strings)
    }

    private var shareLinkURL: URL? {
        roomShareURL
    }

    var body: some View {
        let showLocalAsPrimarySurface = shouldRenderLocalAsPrimarySurface(
            phase: uiState.phase,
            isLocalLarge: isLocalLarge,
            localVideoEnabled: uiState.localVideoEnabled
        )
        let isPinchZoomEnabled = shouldEnablePinchZoom(
            phase: uiState.phase,
            isScreenSharing: uiState.isScreenSharing,
            showLocalAsPrimarySurface: showLocalAsPrimarySurface,
            localCameraMode: uiState.localCameraMode
        )
        let shouldRunAutoHideTask = areControlsVisible && uiState.phase == .inCall && isControlsAutoHideEnabled
        let systemPictureInPictureSource = selectSystemPictureInPictureSource(
            localSourceId: uiState.localCid,
            localIsPrimary: !isMultiParty && showLocalAsPrimarySurface,
            localVideoEnabled: uiState.localVideoEnabled,
            remoteParticipants: uiState.remoteParticipants,
            preferredSourceIds: isMultiParty ? [pinnedParticipantId] : []
        )

        ZStack {
            Color.black.ignoresSafeArea()

            if uiState.phase == .waiting {
                if showLocalAsPrimarySurface {
                    largeLocalView
                        .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
                } else {
                    waitingMainSurface
                        .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
                    smallLocalView
                }
            } else if isMultiParty {
                MultiPartyStage(
                    remoteParticipants: uiState.remoteParticipants,
                    remoteTileAspectRatios: $remoteTileAspectRatios,
                    localCid: uiState.localCid,
                    localVideoEnabled: uiState.localVideoEnabled,
                    localAudioEnabled: uiState.localAudioEnabled,
                    localAudioLevel: uiState.localAudioLevel,
                    localDisplayName: uiState.localDisplayName,
                    localMirror: uiState.isFrontCamera,
                    localCameraMode: uiState.localCameraMode,
                    isScreenSharing: uiState.isScreenSharing,
                    remoteContentCid: uiState.remoteContentCid,
                    remoteContentType: uiState.remoteContentType,
                    remoteVideoFitCover: $remoteVideoFitCover,
                    bottomPadding: pipBottomPadding(isLandscape: isLandscape, areControlsVisible: areControlsVisible),
                    rendererProvider: rendererProvider,
                    pinnedParticipantId: $pinnedParticipantId,
                    strings: strings,
                    onAdjustCameraZoom: onAdjustCameraZoom,
                    onTapBackground: {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            if areControlsVisible {
                                areControlsVisible = false
                                wereControlsLastHiddenByAutoHide = false
                            } else {
                                areControlsVisible = true
                                if wereControlsLastHiddenByAutoHide {
                                    isControlsAutoHideEnabled = false
                                    wereControlsLastHiddenByAutoHide = false
                                }
                            }
                        }
                    }
                )
                .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
            } else if showLocalAsPrimarySurface {
                largeLocalView
                    .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
                smallRemoteView
            } else {
                let inCall = uiState.phase == .inCall
                let firstRemote = uiState.remoteParticipants.first
                ZStack(alignment: .bottomLeading) {
                    mainVideoSurface(
                        kind: .remote,
                        videoContentMode: remoteVideoFitCover ? .scaleAspectFill : .scaleAspectFit,
                        showPlaceholder: shouldShowRemoteVideoPlaceholder(
                            phase: uiState.phase,
                            remoteVideoEnabled: uiState.remoteVideoEnabled
                        ),
                        placeholderText: inCall ? str(.callVideoOff) : nil,
                        placeholderDisplayName: inCall ? firstRemote?.displayName : nil,
                        placeholderPeerId: inCall ? firstRemote?.peerId : nil
                    )
                    ParticipantBadge(
                        muted: firstRemote?.audioEnabled == false,
                        displayName: firstRemote?.videoEnabled == false ? nil : firstRemote?.displayName,
                        audioLevel: firstRemote?.audioLevel
                    )
                }
                .padding(.bottom, areControlsVisible ? pipBottomPadding(isLandscape: isLandscape, areControlsVisible: true) + 4 : 0)
                .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
                smallLocalView
            }

            if !isMultiParty {
                backgroundInteractionLayer(isPinchZoomEnabled: isPinchZoomEnabled)
            }
            overlays
        }
        .onChange(of: uiState.localCameraMode) { mode in
            isLocalLarge = shouldPreferLargeLocalPreview(localCameraMode: mode)
        }
        .onChange(of: uiState.remoteParticipants.map(\.cid)) { remoteCids in
            let active = Set(remoteCids)
            remoteTileAspectRatios = remoteTileAspectRatios.filter { active.contains($0.key) }
            if let pinned = pinnedParticipantId, pinned != uiState.localCid, !active.contains(pinned) {
                pinnedParticipantId = nil
            }
        }
        .onChange(of: isPinchZoomEnabled) { enabled in
            if !enabled {
                lastMagnificationValue = 1
                onResetCameraZoom()
            }
        }
        .task(id: shouldRunAutoHideTask) {
            guard shouldRunAutoHideTask else { return }
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard !Task.isCancelled else { return }
            guard areControlsVisible, uiState.phase == .inCall, isControlsAutoHideEnabled else { return }
            withAnimation(.easeInOut(duration: 0.25)) {
                wereControlsLastHiddenByAutoHide = true
                areControlsVisible = false
            }
        }
        .onChange(of: uiState.connectionStatus) { status in
            if status != .recovering {
                showRecoveringBadge = false
            }
        }
        .onChange(of: remoteVideoFitCover) { value in
            onRemoteVideoFitChanged?(value)
        }
        .task(id: systemPictureInPictureSource) {
            onSystemPictureInPictureSourceChanged?(systemPictureInPictureSource)
        }
        .task(id: uiState.connectionStatus == .recovering && uiState.phase == .inCall) {
            guard uiState.connectionStatus == .recovering, uiState.phase == .inCall else { return }
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard !Task.isCancelled else { return }
            guard uiState.connectionStatus == .recovering, uiState.phase == .inCall else { return }
            showRecoveringBadge = true
        }
        .sheet(isPresented: $showShareSheet) {
            if let shareURL = shareLinkURL {
                ActivityView(items: [shareURL])
            }
        }
        .overlay(alignment: .topLeading) {
            VStack(spacing: 0) {
                Color.clear
                    .frame(width: 1, height: 1)
                    .accessibilityIdentifier("call.screen")

                Color.clear
                    .frame(width: 1, height: 1)
                    .accessibilityElement(children: .ignore)
                    .accessibilityIdentifier("call.participantCount")
                    .accessibilityValue("\(uiState.participantCount)")

                Color.clear
                    .frame(width: 1, height: 1)
                    .accessibilityElement(children: .ignore)
                    .accessibilityIdentifier("call.phase")
                    .accessibilityValue(uiState.phase.rawValue)
            }
        }
    }

    private func backgroundInteractionLayer(isPinchZoomEnabled: Bool) -> some View {
        Color.clear
            .contentShape(Rectangle())
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.2)) {
                    if areControlsVisible {
                        areControlsVisible = false
                        wereControlsLastHiddenByAutoHide = false
                    } else {
                        areControlsVisible = true
                        if wereControlsLastHiddenByAutoHide {
                            isControlsAutoHideEnabled = false
                            wereControlsLastHiddenByAutoHide = false
                        }
                    }
                }
            }
            .simultaneousGesture(
                MagnificationGesture()
                    .onChanged { value in
                        guard isPinchZoomEnabled else { return }
                        let delta = value / max(lastMagnificationValue, 0.001)
                        lastMagnificationValue = value
                        onAdjustCameraZoom(delta)
                    }
                    .onEnded { _ in
                        lastMagnificationValue = 1
                    }
            )
    }

    private func mainVideoSurface(
        kind: WebRTCVideoView.Kind,
        videoContentMode: UIView.ContentMode = .scaleAspectFill,
        showPlaceholder: Bool,
        placeholderText: String?,
        placeholderDisplayName: String? = nil,
        placeholderPeerId: String? = nil
    ) -> some View {
        ZStack {
            Color.black.ignoresSafeArea()
            WebRTCVideoView(
                kind: kind,
                rendererProvider: rendererProvider,
                videoContentMode: videoContentMode,
                isMirrored: kind.isLocal && uiState.isFrontCamera
            )
                .ignoresSafeArea()

            if showPlaceholder {
                VideoPlaceholderTile(text: placeholderText, compact: false, displayName: placeholderDisplayName, peerId: placeholderPeerId)
                    .ignoresSafeArea()
            }
        }
    }

    private var waitingMainSurface: some View {
        Color.black.ignoresSafeArea()
    }

    private var largeLocalView: some View {
        ZStack(alignment: .bottomLeading) {
            mainVideoSurface(
                kind: .local,
                videoContentMode: primaryLocalVideoContentMode(localCameraMode: uiState.localCameraMode),
                showPlaceholder: shouldShowLocalVideoPlaceholder(localVideoEnabled: uiState.localVideoEnabled),
                placeholderText: str(.callLocalCameraOff)
            )
            ParticipantBadge(muted: !uiState.localAudioEnabled, displayName: uiState.localDisplayName, audioLevel: uiState.localAudioLevel)
        }
        .padding(.bottom, areControlsVisible ? pipBottomPadding(isLandscape: isLandscape, areControlsVisible: true) + 4 : 0)
    }

    private var smallLocalView: some View {
        ZStack(alignment: .bottomLeading) {
            Color.black
            WebRTCVideoView(
                kind: .local,
                rendererProvider: rendererProvider,
                videoContentMode: .scaleAspectFill,
                isMirrored: uiState.isFrontCamera
            )

            if shouldShowLocalVideoPlaceholder(localVideoEnabled: uiState.localVideoEnabled) {
                VideoPlaceholderTile(text: str(.callCameraOff), compact: true)
            }

            ParticipantBadge(muted: !uiState.localAudioEnabled, displayName: uiState.localDisplayName, audioLevel: uiState.localAudioLevel)
        }
            .frame(width: 110, height: 160)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.35), lineWidth: 1))
            .padding(.trailing, 16)
            .padding(.bottom, pipBottomPadding(isLandscape: isLandscape, areControlsVisible: areControlsVisible))
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isLocalLarge.toggle()
                }
            }
    }

    private var smallRemoteView: some View {
        ZStack(alignment: .bottomLeading) {
            Color.black
            WebRTCVideoView(kind: .remote, rendererProvider: rendererProvider, videoContentMode: .scaleAspectFill)

            if shouldShowRemoteVideoPlaceholder(phase: uiState.phase, remoteVideoEnabled: uiState.remoteVideoEnabled) {
                VideoPlaceholderTile(
                    text: uiState.phase == .inCall ? str(.callVideoOff) : nil,
                    compact: true,
                    displayName: uiState.phase == .inCall ? uiState.remoteParticipants.first?.displayName : nil,
                    peerId: uiState.phase == .inCall ? uiState.remoteParticipants.first?.peerId : nil
                )
            }

            ParticipantBadge(
                muted: uiState.remoteParticipants.first?.audioEnabled == false,
                displayName: uiState.remoteParticipants.first?.videoEnabled == false ? nil : uiState.remoteParticipants.first?.displayName,
                audioLevel: uiState.remoteParticipants.first?.audioLevel
            )
        }
            .frame(width: 110, height: 160)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.35), lineWidth: 1))
            .padding(.trailing, 16)
            .padding(.bottom, pipBottomPadding(isLandscape: isLandscape, areControlsVisible: areControlsVisible))
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isLocalLarge.toggle()
                }
            }
    }

    private var overlays: some View {
        ZStack(alignment: .topLeading) {
            VStack(spacing: 0) {
                topStatus

                Spacer()

                if areControlsVisible {
                    controlBar
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }

            if config.debugOverlayEnabled {
                Color.clear
                    .frame(width: 72, height: 72)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        handleDebugTap()
                    }
            }

            if showDebugPanel && config.debugOverlayEnabled && uiState.phase == .inCall {
                debugPanelView
                    .padding(.top, 80)
                    .padding(.leading, 12)
            }

        }
        .animation(.easeInOut(duration: 0.2), value: areControlsVisible)
    }

    private var shouldShowSnapshotButton: Bool {
        config.snapshotEnabled
            && onSnapshotRequested != nil
            && currentSnapshotSource != nil
            && (uiState.phase == .inCall || uiState.phase == .waiting)
    }

    private var currentSnapshotSource: SnapshotSource? {
        // Multi-party stage has no single dominant preview unless the user
        // pins one. With a pinned tile the stage layout treats that
        // participant as the large preview, so the shutter targets them.
        if isMultiParty {
            guard let pinned = pinnedParticipantId else { return nil }
            if pinned == uiState.localCid, uiState.localVideoEnabled {
                return .local
            }
            if let remote = uiState.remoteParticipants.first(where: { $0.cid == pinned }),
               remote.videoEnabled {
                return .remote(cid: remote.cid)
            }
            return nil
        }
        if isLocalLarge && uiState.localVideoEnabled {
            return .local
        }
        if let remote = uiState.remoteParticipants.first, remote.videoEnabled {
            return .remote(cid: remote.cid)
        }
        return nil
    }

    private var hasOtherTopRightCornerButtons: Bool {
        let shareShown = uiState.phase == .waiting
            && config.inviteControlsEnabled
            && shareLinkURL != nil
        let fitCoverShown = shouldShowRemoteFitButton(
            phase: uiState.phase,
            remoteVideoEnabled: uiState.remoteVideoEnabled,
            isLocalLarge: isLocalLarge,
            localVideoEnabled: uiState.localVideoEnabled
        ) && !isMultiParty
        return uiState.isFlashAvailable || shareShown || fitCoverShown
    }

    private var snapshotIconButton: some View {
        iconButton(system: "camera.fill", accessibilityLabel: str(.callA11yTakeSnapshot)) {
            guard let source = currentSnapshotSource else { return }
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            onSnapshotRequested?(source)
        }
        .accessibilityIdentifier("call.takeSnapshot")
    }

    private var topStatus: some View {
        let autoHideOpacity: Double = uiState.phase == .waiting ? 1 : (areControlsVisible ? 1 : 0)
        return VStack(spacing: 8) {
            HStack(spacing: 8) {
                if uiState.phase == .inCall &&
                    (uiState.connectionStatus == .retrying || showRecoveringBadge) {
                    HStack(spacing: 8) {
                        Text(str(.callReconnecting))
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.white)

                        if uiState.connectionStatus == .retrying {
                            Text(str(.callTakingLongerThanUsual))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.white.opacity(0.92))
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.black.opacity(0.45))
                    .clipShape(Capsule())
                    .opacity(autoHideOpacity)
                }

                Spacer()

                // Landscape (or empty corner) keeps the cascade on the same row, so the
                // snapshot button sits to the left of any companion icons in the corner.
                if shouldShowSnapshotButton && (isLandscape || !hasOtherTopRightCornerButtons) {
                    snapshotIconButton
                        .opacity(autoHideOpacity)
                }

                if uiState.isFlashAvailable {
                    iconButton(system: uiState.isFlashEnabled ? "flashlight.on.fill" : "flashlight.off.fill", accessibilityLabel: uiState.isFlashEnabled ? str(.callA11yFlashlightOn) : str(.callA11yFlashlightOff)) {
                        onToggleFlashlight()
                    }
                    .opacity(autoHideOpacity)
                }

                if uiState.phase == .waiting && config.inviteControlsEnabled && shareLinkURL != nil {
                    iconButton(system: "square.and.arrow.up", accessibilityLabel: str(.callA11yShareInvite)) {
                        showShareSheet = true
                    }
                }

                if shouldShowRemoteFitButton(
                    phase: uiState.phase,
                    remoteVideoEnabled: uiState.remoteVideoEnabled,
                    isLocalLarge: isLocalLarge,
                    localVideoEnabled: uiState.localVideoEnabled
                ) && !isMultiParty {
                    iconButton(system: remoteVideoFitCover ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right", accessibilityLabel: remoteVideoFitCover ? str(.callA11yVideoFit) : str(.callA11yVideoFill)) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            remoteVideoFitCover.toggle()
                        }
                    }
                }
            }

            // Portrait drops the snapshot button below the corner cluster when
            // companions are present, so the camera icon doesn't fight the
            // flashlight/fit/share button for the same anchor.
            if shouldShowSnapshotButton && !isLandscape && hasOtherTopRightCornerButtons {
                HStack(spacing: 8) {
                    Spacer()
                    snapshotIconButton
                        .opacity(autoHideOpacity)
                }
            }

            if shouldShowWaitingOverlay(phase: uiState.phase) {
                VStack(spacing: 10) {
                    if let roomName, !roomName.isEmpty {
                        Text(roomName)
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.center)
                    }

                    Text(str(.callWaitingOverlay))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)

                    if config.inviteControlsEnabled {
                        if let shareURL = shareLinkURL {
                            QRCodeImageView(text: shareURL.absoluteString)
                                .padding(.vertical, 6)
                        }

                        Button {
                            Task {
                                let result = await onInviteToRoom()
                                await MainActor.run {
                                    switch result {
                                    case .success:
                                        inviteStatusMessage = str(.callInviteSent)
                                    case .failure:
                                        inviteStatusMessage = str(.callInviteFailed)
                                    }
                                }
                            }
                        } label: {
                            Label(str(.callInviteToRoom), systemImage: "bell.badge.fill")
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 14)
                                .padding(.vertical, 10)
                                .background(Color.white.opacity(0.2))
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                .foregroundStyle(.white)
                        }
                        .buttonStyle(.plain)

                        if let inviteStatusMessage, !inviteStatusMessage.isEmpty {
                            Text(inviteStatusMessage)
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.white.opacity(0.9))
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(Color.black.opacity(0.45))
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .accessibilityIdentifier("call.waitingOverlay")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
    }

    private var controlBar: some View {
        HStack(spacing: 14) {
            iconButton(system: uiState.localAudioEnabled ? "mic.fill" : "mic.slash.fill", accessibilityLabel: uiState.localAudioEnabled ? str(.callA11yMuteOn) : str(.callA11yMuteOff)) {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                onToggleAudio()
            }

            if config.videoEnabled && !uiState.availableCameraModes.isEmpty {
                iconButton(system: uiState.localVideoEnabled ? "video.fill" : "video.slash.fill", accessibilityLabel: uiState.localVideoEnabled ? str(.callA11yVideoOn) : str(.callA11yVideoOff)) {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    onToggleVideo()
                }
            }

            if config.videoEnabled && uiState.availableCameraModes.count > 1 && uiState.localVideoEnabled {
                iconButton(system: "camera.rotate.fill", accessibilityLabel: str(.callA11yFlipCamera)) {
                    onFlipCamera()
                }
            }

            if config.screenSharingEnabled {
                if shouldUseBroadcastPicker(
                    isScreenSharing: uiState.isScreenSharing,
                    screenShareExtensionBundleId: screenShareExtensionBundleId
                ), let screenShareExtensionBundleId {
                    BroadcastPickerButton(
                        preferredExtension: screenShareExtensionBundleId,
                        systemImage: "rectangle.on.rectangle",
                        accessibilityLabel: str(.callA11yScreenShareOff),
                        onPrepareStart: onToggleScreenShare
                    )
                } else {
                    iconButton(system: uiState.isScreenSharing ? "rectangle.on.rectangle.slash" : "rectangle.on.rectangle", accessibilityLabel: uiState.isScreenSharing ? str(.callA11yScreenShareOn) : str(.callA11yScreenShareOff)) {
                        onToggleScreenShare()
                    }
                }
            }

            Button {
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
                onEndCall()
            } label: {
                Image(systemName: "phone.down.fill")
                    .font(.system(size: 19, weight: .bold))
                    .frame(width: 58, height: 58)
                    .background(Color.red)
                    .clipShape(Circle())
                    .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("call.endCall")
            .accessibilityLabel(str(.callA11yEndCall))
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .padding(.horizontal, 18)
        .padding(.bottom, 26)
    }

    private var debugPanelView: some View {
        let sections = buildDebugPanelSections(uiState: uiState)
        return VStack(alignment: .leading, spacing: 8) {
            ForEach(sections) { section in
                VStack(alignment: .leading, spacing: 6) {
                    Text(section.title)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.white.opacity(0.95))
                    ForEach(section.metrics) { metric in
                        HStack(spacing: 8) {
                            Circle()
                                .fill(debugDotColor(metric.status))
                                .frame(width: 8, height: 8)
                            if !metric.label.isEmpty {
                                Text(metric.label)
                                    .font(.caption2)
                                    .foregroundStyle(Color.white.opacity(0.9))
                            }
                            Spacer(minLength: 8)
                            Text(metric.value)
                                .font(.caption2)
                                .foregroundStyle(Color.white.opacity(0.95))
                        }
                    }
                }
            }
        }
        .padding(10)
        .frame(maxWidth: 280)
        .background(Color.black.opacity(0.62))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private func handleDebugTap() {
        guard uiState.phase == .inCall else { return }
        let now = Date()
        let didDoubleTap = lastDebugTapAt.map { now.timeIntervalSince($0) <= 0.45 } ?? false
        lastDebugTapAt = now
        guard didDoubleTap else { return }
        withAnimation(.easeInOut(duration: 0.18)) {
            showDebugPanel.toggle()
        }
    }

    private func debugDotColor(_ status: DebugStatus) -> Color {
        switch status {
        case .good:
            return Color(red: 0.18, green: 0.80, blue: 0.44)
        case .warn:
            return Color(red: 0.94, green: 0.77, blue: 0.06)
        case .bad:
            return Color(red: 0.91, green: 0.30, blue: 0.24)
        case .na:
            return Color(red: 0.58, green: 0.65, blue: 0.65)
        }
    }

    private func iconButton(system: String, accessibilityLabel: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.system(size: 18, weight: .semibold))
                .frame(width: 48, height: 48)
                .background(Color.black.opacity(0.45))
                .clipShape(Circle())
                .foregroundStyle(.white)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }

    private var isLandscape: Bool {
        verticalSizeClass == .compact
    }

    private var isMultiParty: Bool {
        uiState.remoteParticipants.count > 1
    }
}

private func quantizedStageTileAspectRatio(_ size: CGSize) -> CGFloat {
    guard size.width > 0, size.height > 0 else {
        return clampStageTileAspectRatio(nil)
    }
    let rawRatio = size.width / size.height
    let quantized = (rawRatio / 0.05).rounded() * 0.05
    return clampStageTileAspectRatio(max(0.1, quantized))
}

private struct MultiPartyStage: View {
    let remoteParticipants: [RemoteParticipant]
    @Binding var remoteTileAspectRatios: [String: CGFloat]
    let localCid: String?
    let localVideoEnabled: Bool
    let localAudioEnabled: Bool
    let localAudioLevel: Float
    let localDisplayName: String?
    let localMirror: Bool
    let localCameraMode: LocalCameraMode
    let isScreenSharing: Bool
    let remoteContentCid: String?
    let remoteContentType: String?
    @Binding var remoteVideoFitCover: Bool
    let bottomPadding: CGFloat
    let rendererProvider: CallRendererProvider
    @Binding var pinnedParticipantId: String?
    let strings: [SerenadaString: String]?
    let onAdjustCameraZoom: (CGFloat) -> Void
    let onTapBackground: () -> Void

    @State private var lastMagnificationValue: CGFloat = 1

    private let gap: CGFloat = 12
    private let outerPadding: CGFloat = 16
    private let tileCornerRadius: CGFloat = 16
    private let pipCornerRadius: CGFloat = 12

    private func str(_ key: SerenadaString) -> String {
        resolveString(key, overrides: strings)
    }

    private var hasLocalContent: Bool {
        isScreenSharing || localCameraMode.isContentMode
    }

    private var hasContentSource: Bool {
        hasLocalContent || remoteContentCid != nil
    }

    var body: some View {
        GeometryReader { geometry in
            let availableWidth = geometry.size.width - outerPadding * 2
            let availableHeight = max(0, geometry.size.height - (20 + bottomPadding + 4))
            let useComputedLayout = localCid != nil && (pinnedParticipantId != nil || hasContentSource)

            if useComputedLayout, let localCid {
                let activeContentSource: ContentSource? = {
                    if hasLocalContent {
                        let type: ContentType = {
                            if isScreenSharing { return .screenShare }
                            if localCameraMode == .world { return .worldCamera }
                            return .compositeCamera
                        }()
                        return ContentSource(type: type, ownerParticipantId: localCid, aspectRatio: nil)
                    } else if let remoteCid = remoteContentCid {
                        let type: ContentType = {
                            switch remoteContentType {
                            case ContentTypeWire.worldCamera: return .worldCamera
                            case ContentTypeWire.compositeCamera: return .compositeCamera
                            default: return .screenShare
                            }
                        }()
                        return ContentSource(type: type, ownerParticipantId: remoteCid, aspectRatio: nil)
                    }
                    return nil
                }()

                let participants: [SceneParticipant] = remoteParticipants.map { p in
                    SceneParticipant(
                        id: p.cid,
                        role: .remote,
                        videoEnabled: p.videoEnabled,
                        videoAspectRatio: remoteTileAspectRatios[p.cid]
                    )
                } + [SceneParticipant(
                    id: localCid,
                    role: .local,
                    videoEnabled: localVideoEnabled,
                    videoAspectRatio: nil
                )]

                let layoutResult = computeLayout(scene: CallScene(
                    viewportWidth: geometry.size.width,
                    viewportHeight: geometry.size.height,
                    safeAreaInsets: LayoutInsets(top: 20, bottom: bottomPadding + 4, left: 0, right: 0),
                    participants: participants,
                    localParticipantId: localCid,
                    activeSpeakerId: nil,
                    pinnedParticipantId: activeContentSource != nil ? nil : pinnedParticipantId,
                    contentSource: activeContentSource,
                    userPrefs: UserLayoutPrefs(dominantFit: remoteVideoFitCover ? .cover : .contain)
                ))

                ZStack {
                    ForEach(Array(layoutResult.tiles.enumerated()), id: \.element.id) { _, tile in
                        let isContentTile = tile.type == .contentSource
                        let isLocal = tile.id == localCid
                        let isLocalPlaceholder = isLocal && activeContentSource?.ownerParticipantId == localCid

                        let contentOwnerCid = activeContentSource?.ownerParticipantId
                        let isLocalContent = isContentTile && contentOwnerCid == localCid
                        let isRemoteContent = isContentTile && contentOwnerCid != localCid

                        ZStack {
                            Color.black
                            if isLocalContent || (isLocal && !isLocalPlaceholder) {
                                if localVideoEnabled || isLocalContent {
                                    WebRTCVideoView(
                                        kind: .local,
                                        rendererProvider: rendererProvider,
                                        videoContentMode: isLocalContent ? .scaleAspectFit : (tile.fit == .contain ? .scaleAspectFit : .scaleAspectFill),
                                        isMirrored: isLocalContent ? false : localMirror
                                    )
                                } else {
                                    VideoPlaceholderTile(text: str(.callCameraOff), compact: true)
                                }
                            } else if isRemoteContent, let ownerCid = contentOwnerCid {
                                WebRTCVideoView(
                                    kind: .remoteForCid(ownerCid),
                                    rendererProvider: rendererProvider,
                                    videoContentMode: tile.fit == .contain ? .scaleAspectFit : .scaleAspectFill
                                )
                            } else if isLocalPlaceholder {
                                VideoPlaceholderTile(text: str(.callCameraOff), compact: true)
                            } else if let participant = remoteParticipants.first(where: { $0.cid == tile.id }) {
                                WebRTCVideoView(
                                    kind: .remoteForCid(participant.cid),
                                    rendererProvider: rendererProvider,
                                    videoContentMode: tile.fit == .contain ? .scaleAspectFit : .scaleAspectFill,
                                    onVideoSizeChanged: { size in
                                        remoteTileAspectRatios[tile.id] = quantizedStageTileAspectRatio(size)
                                    }
                                )
                                if !participant.videoEnabled {
                                    VideoPlaceholderTile(text: str(.callVideoOff), compact: false, displayName: participant.displayName, peerId: participant.peerId)
                                }
                            }

                            if let pinned = pinnedParticipantId, tile.id == pinned {
                                VStack {
                                    HStack {
                                        Image(systemName: "pin.fill")
                                            .font(.system(size: 12, weight: .semibold))
                                            .foregroundStyle(.white)
                                            .padding(6)
                                            .background(Color.black.opacity(0.56))
                                            .clipShape(RoundedRectangle(cornerRadius: 6))
                                            .padding(8)
                                        Spacer()
                                    }
                                    Spacer()
                                }
                            }

                            if tile.zOrder == 0 {
                                VStack {
                                    Spacer()
                                    HStack {
                                        Spacer()
                                        Button {
                                            withAnimation(.easeInOut(duration: 0.2)) {
                                                remoteVideoFitCover.toggle()
                                            }
                                        } label: {
                                            Image(systemName: remoteVideoFitCover
                                                ? "arrow.down.right.and.arrow.up.left"
                                                : "arrow.up.left.and.arrow.down.right")
                                                .font(.system(size: 16, weight: .semibold))
                                                .foregroundStyle(.white)
                                                .frame(width: 44, height: 44)
                                                .background(Color.black.opacity(0.4))
                                                .clipShape(Circle())
                                        }
                                        .padding(8)
                                    }
                                }
                            }

                            if !isContentTile {
                                let tileRemote = isLocal ? nil : remoteParticipants.first(where: { $0.cid == tile.id })
                                let tileAudioMuted = isLocal ? !localAudioEnabled : tileRemote?.audioEnabled == false
                                let tileName = isLocal ? localDisplayName : tileRemote?.displayName
                                let tileNameForBadge: String? =
                                    (!isLocal && tileRemote?.videoEnabled == false) ? nil : tileName
                                let tileAudioLevel: Float? = isLocal ? localAudioLevel : tileRemote?.audioLevel
                                ParticipantBadge(
                                    muted: tileAudioMuted,
                                    displayName: tileNameForBadge,
                                    audioLevel: tileAudioLevel
                                )
                                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                            }
                        }
                        .frame(width: tile.frame.width, height: tile.frame.height)
                        .clipShape(RoundedRectangle(cornerRadius: tile.cornerRadius))
                        .overlay(
                            RoundedRectangle(cornerRadius: tile.cornerRadius)
                                .stroke(Color.white.opacity(0.14), lineWidth: 1)
                        )
                        .position(
                            x: tile.frame.x + tile.frame.width / 2,
                            y: tile.frame.y + tile.frame.height / 2
                        )
                        .simultaneousGesture(
                            LongPressGesture(minimumDuration: 0.5)
                                .onEnded { _ in
                                    if !isContentTile {
                                        pinnedParticipantId = tile.id == pinnedParticipantId ? nil : tile.id
                                    }
                                }
                        )
                        .simultaneousGesture(
                            MagnificationGesture()
                                .onChanged { value in
                                    guard isLocalContent && localCameraMode.isContentMode else { return }
                                    let delta = value / max(lastMagnificationValue, 0.001)
                                    lastMagnificationValue = value
                                    onAdjustCameraZoom(delta)
                                }
                                .onEnded { _ in
                                    lastMagnificationValue = 1
                                }
                        )
                    }
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
            } else {
                let layout = computeStageLayout(
                    tiles: remoteParticipants.map { participant in
                        StageTileSpec(
                            cid: participant.cid,
                            aspectRatio: clampStageTileAspectRatio(remoteTileAspectRatios[participant.cid])
                        )
                    },
                    availableWidth: availableWidth,
                    availableHeight: availableHeight,
                    gap: gap
                )

                ZStack(alignment: .bottomTrailing) {
                    VStack(spacing: gap) {
                        ForEach(layout) { row in
                            HStack(spacing: gap) {
                                ForEach(row.items) { tile in
                                    if let participant = remoteParticipants.first(where: { $0.cid == tile.cid }) {
                                        RemoteParticipantStageTile(
                                            participant: participant,
                                            size: CGSize(width: tile.width, height: tile.height),
                                            cornerRadius: tileCornerRadius,
                                            rendererProvider: rendererProvider,
                                            strings: strings,
                                            onVideoSizeChanged: { size in
                                                remoteTileAspectRatios[tile.cid] = quantizedStageTileAspectRatio(size)
                                            }
                                        )
                                        .simultaneousGesture(
                                            LongPressGesture(minimumDuration: 0.5)
                                                .onEnded { _ in
                                                    pinnedParticipantId = tile.cid == pinnedParticipantId ? nil : tile.cid
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.horizontal, outerPadding)
                    .padding(.top, 20)
                    .padding(.bottom, bottomPadding + 4)

                    MultiPartyLocalPip(
                        localVideoEnabled: localVideoEnabled,
                        localAudioEnabled: localAudioEnabled,
                        localAudioLevel: localAudioLevel,
                        localDisplayName: localDisplayName,
                        localMirror: localMirror,
                        cornerRadius: pipCornerRadius,
                        rendererProvider: rendererProvider,
                        strings: strings
                    )
                    .padding(.trailing, 16)
                    .padding(.bottom, bottomPadding)
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { onTapBackground() }
    }
}

private struct RemoteParticipantStageTile: View {
    let participant: RemoteParticipant
    let size: CGSize
    let cornerRadius: CGFloat
    var videoContentMode: UIView.ContentMode = .scaleAspectFit
    let rendererProvider: CallRendererProvider
    let strings: [SerenadaString: String]?
    let onVideoSizeChanged: (CGSize) -> Void

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color.black
            WebRTCVideoView(
                kind: .remoteForCid(participant.cid),
                rendererProvider: rendererProvider,
                videoContentMode: videoContentMode,
                onVideoSizeChanged: onVideoSizeChanged
            )
            if !participant.videoEnabled {
                VideoPlaceholderTile(text: resolveString(.callVideoOff, overrides: strings), compact: false, displayName: participant.displayName, peerId: participant.peerId)
            }
            ParticipantBadge(
                muted: participant.audioEnabled == false,
                displayName: participant.videoEnabled ? participant.displayName : nil,
                audioLevel: participant.audioLevel
            )
        }
        .frame(width: size.width, height: size.height)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .overlay(
            RoundedRectangle(cornerRadius: cornerRadius)
                .stroke(Color.white.opacity(0.14), lineWidth: 1)
        )
    }
}

private struct MultiPartyLocalPip: View {
    let localVideoEnabled: Bool
    let localAudioEnabled: Bool
    let localAudioLevel: Float
    let localDisplayName: String?
    let localMirror: Bool
    let cornerRadius: CGFloat
    let rendererProvider: CallRendererProvider
    let strings: [SerenadaString: String]?

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color.black
            if localVideoEnabled {
                WebRTCVideoView(
                    kind: .local,
                    rendererProvider: rendererProvider,
                    videoContentMode: .scaleAspectFill,
                    isMirrored: localMirror
                )
            } else {
                VideoPlaceholderTile(text: resolveString(.callCameraOff, overrides: strings), compact: true)
            }
            ParticipantBadge(muted: !localAudioEnabled, displayName: localDisplayName, audioLevel: localAudioLevel)
        }
        .frame(width: 100, height: 150)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .overlay(
            RoundedRectangle(cornerRadius: cornerRadius)
                .stroke(Color.white.opacity(0.32), lineWidth: 1)
        )
    }
}

struct VideoPlaceholderTile: View {
    let text: String?
    let compact: Bool
    var displayName: String? = nil
    var peerId: String? = nil

    @Environment(\.avatarCache) private var avatarCache

    var body: some View {
        let name = displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayNameToShow = name?.isEmpty == false ? name : nil
        let showIdentityPlaceholder = displayNameToShow != nil || peerId?.isEmpty == false
        ZStack {
            Color.black
            if showIdentityPlaceholder {
                VStack(spacing: compact ? 6 : 12) {
                    if avatarCache != nil {
                        RemoteAvatarView(
                            peerId: peerId,
                            displayName: displayNameToShow,
                            size: compact ? 48 : 96
                        )
                    }
                    Text(displayNameToShow ?? text ?? "")
                        .font(compact ? .system(size: 13, weight: .semibold) : .system(size: 18, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.85))
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .padding(.horizontal, compact ? 6 : 16)
                }
            } else {
                VStack(spacing: compact ? 6 : 10) {
                    Image(systemName: "video.slash.fill")
                        .font(.system(size: compact ? 20 : 34, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.9))

                    if let text, !text.isEmpty {
                        Text(text)
                            .font(compact ? .caption2.weight(.semibold) : .subheadline.weight(.semibold))
                            .foregroundStyle(.white.opacity(0.9))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, compact ? 6 : 16)
                    }
                }
            }
        }
    }
}

private struct ParticipantBadge: View {
    var muted: Bool = false
    var displayName: String? = nil
    /// When non-nil, drives the green activity indicator (and signals that a
    /// real participant is bound to this badge). Pass `nil` for tiles without
    /// a participant — e.g. the remote slot before a peer joins — to suppress
    /// the badge entirely when there's nothing to show.
    var audioLevel: Float? = nil

    var body: some View {
        let showIndicator = !muted && audioLevel != nil
        if muted || showIndicator || displayName != nil {
            HStack(spacing: 4) {
                if muted {
                    Image(systemName: "mic.slash.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Color(red: 0.94, green: 0.27, blue: 0.27))
                } else if let audioLevel {
                    AudioActivityIndicator(level: audioLevel)
                }
                if let name = displayName {
                    Text(name)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 5)
            .background(Color.black.opacity(0.56))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .padding(6)
        }
    }
}
