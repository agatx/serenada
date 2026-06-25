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

private struct StreamKeyedStageState {
    let tiles: [StageTile]
    let spotlightId: String?
    let active: Bool
}

struct CallScreenView: View {
    let roomId: String
    let uiState: CallUiState
    let roomShareURL: URL?
    let screenShareExtensionBundleId: String?
    let screenShareAvailable: Bool
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
    /// Resolved content (screen share) scene for this render. With the
    /// independent flag off (default) every owner is LEGACY and the existing
    /// single-video-as-content presentation is preserved byte-identically.
    let contentScene: ContentScene
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
    /// Stream-keyed pin for the INDEPENDENT content stage: pin ANY tile (camera OR
    /// content) of ANY participant. Distinct from `pinnedParticipantId` (the legacy
    /// multi-party / no-content focus pin, which is participant-keyed and stays
    /// byte-identical). `nil` = default spotlight (the resolver's most-recent share).
    @State private var pinnedTile: StageTileKey?

    init(
        roomId: String,
        uiState: CallUiState,
        roomShareURL: URL?,
        screenShareExtensionBundleId: String? = nil,
        screenShareAvailable: Bool = true,
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
        contentScene: ContentScene = ContentScene(primary: nil, local: nil, remotes: []),
        initialRemoteVideoFitCover: Bool = true,
        onRemoteVideoFitChanged: ((Bool) -> Void)? = nil,
        onSystemPictureInPictureSourceChanged: ((SystemPictureInPictureSource) -> Void)? = nil,
        onSystemPictureInPictureSourceFrameChanged: ((CGRect?) -> Void)? = nil
    ) {
        self.roomId = roomId
        self.uiState = uiState
        self.roomShareURL = roomShareURL
        self.screenShareExtensionBundleId = screenShareExtensionBundleId
        self.screenShareAvailable = screenShareAvailable
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
        self.contentScene = contentScene
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
        let streamStage = streamKeyedStageState
        let streamKeyedStageActive = streamStage.active
        let stageSpotlightId = streamStage.spotlightId
        let stageTiles = streamStage.tiles
        // In the stream-keyed stage prefer the spotlight's CAMERA owner (content
        // tiles have no PiP-able camera target, so fall back to a remote camera).
        let stageSpotlightCameraCid: String? = {
            guard streamKeyedStageActive, let id = stageSpotlightId, let key = parseStageTileId(id), key.kind == .camera else { return nil }
            return key.cid
        }()
        let systemPictureInPictureSource = selectSystemPictureInPictureSource(
            localSourceId: uiState.localCid,
            localIsPrimary: !isMultiParty && !streamKeyedStageActive && showLocalAsPrimarySurface,
            localVideoEnabled: uiState.localVideoEnabled,
            remoteParticipants: uiState.remoteParticipants,
            preferredSourceIds: streamKeyedStageActive ? [stageSpotlightCameraCid] : (isMultiParty ? [pinnedParticipantId] : [])
        )

        ZStack {
            Color.black.ignoresSafeArea()

            if streamKeyedStageActive {
                // STREAM-KEYED STAGE: an INDEPENDENT content stream is active
                // (1:1 or group). Every stream is its own tile keyed {cid, kind} —
                // a camera tile per camera-on participant and a content tile per
                // sharer (incl the local user's own screen). A sharer's camera and
                // screen are TWO EQUAL peer tiles (not a camera-over-content PIP).
                // Single spotlight = the resolver's most-recent share by default;
                // tap any tile to pin/unpin. Mirrors web's filmstrip pivot. Never
                // reachable flag-off / legacy (the resolver yields no independent
                // content), so the branches below stay byte-identical.
                StreamKeyedStage(
                    remoteParticipants: uiState.remoteParticipants,
                    remoteTileAspectRatios: $remoteTileAspectRatios,
                    localCid: uiState.localCid,
                    localVideoEnabled: uiState.localVideoEnabled,
                    localAudioEnabled: uiState.localAudioEnabled,
                    localAudioLevel: uiState.localAudioLevel,
                    localDisplayName: uiState.localDisplayName,
                    localMirror: uiState.isFrontCamera,
                    localCameraMode: uiState.localCameraMode,
                    contentScene: contentScene,
                    remoteVideoFitCover: $remoteVideoFitCover,
                    bottomPadding: pipBottomPadding(isLandscape: isLandscape, areControlsVisible: areControlsVisible),
                    rendererProvider: rendererProvider,
                    pinnedTile: $pinnedTile,
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
            } else if isMultiParty {
                // Multi-party stage WITHOUT independent content (pin focus or a
                // legacy single-video-as-content tile). Byte-identical to today.
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
                    contentScene: contentScene,
                    resolvedContentSource: resolvedContentSource,
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
            } else if uiState.phase == .waiting {
                // Waiting, NOT routed to the content stage. A 1:1 INDEPENDENT
                // share while waiting is handled above by `streamKeyedStageActive`
                // (the local content tile + "waiting for participants" hold);
                // legacy/flag-off waiting is unchanged (byte-identical).
                if showLocalAsPrimarySurface {
                    largeLocalView
                        .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
                } else {
                    waitingMainSurface
                        .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
                    smallLocalView
                }
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

            // The bespoke tap/pinch background layer is only for the single-
            // surface layouts. Both stages (stream-keyed and multi-party) own
            // their own background tap handling, so suppress it there.
            if !isMultiParty && !streamKeyedStageActive {
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
        .onChange(of: stageTiles.map(\.id)) { ids in
            // Drop a stale stream-key pin when its tile disappears (the pinned
            // sharer stopped, left, or the pinned camera turned off) so the
            // spotlight reverts to the most-recent-share default.
            if let pinnedTile, !ids.contains(stageTileId(pinnedTile)) {
                self.pinnedTile = nil
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
        // Stream-keyed content stage: the spotlight may be a content stream, but
        // snapshot capture is currently camera-source based. Keep the shutter
        // available by targeting the spotlight owner's camera when that camera is
        // enabled instead of hiding it whenever their content is large.
        let streamStage = streamKeyedStageState
        if streamStage.active {
            guard let spotlightId = streamStage.spotlightId, let key = parseStageTileId(spotlightId) else { return nil }
            if key.cid == uiState.localCid {
                return uiState.localVideoEnabled ? .local : nil
            }
            if let remote = uiState.remoteParticipants.first(where: { $0.cid == key.cid }), remote.videoEnabled {
                return .remote(cid: remote.cid)
            }
            return nil
        }
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
        ) && !isMultiParty && !streamKeyedStageActive
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
                ) && !isMultiParty && !streamKeyedStageActive {
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

            if config.screenSharingEnabled && screenShareAvailable {
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

    // MARK: - Independent content stage routing (Phase 4b)

    /// The content-role source for the LEGACY multi-party content path, or nil. In
    /// the stream-keyed pivot this only feeds `MultiPartyStage` for a LEGACY
    /// single-video-as-content tile (independent content routes through the
    /// stream-keyed stage instead). Legacy 1:1 returns nil (byte-identical).
    private var resolvedContentSource: ResolvedContentSource? {
        resolveContentSource(contentScene.primary, isMultiParty: isMultiParty)
    }

    /// Snapshot of the stream-keyed stage derivation for one render. This keeps
    /// body-level branch decisions from recomputing content resolution, tile
    /// derivation, and spotlight selection independently.
    private var streamKeyedStageState: StreamKeyedStageState {
        let stageContentAll = stageContent(for: contentScene)
        guard stageContentAll.contains(where: { $0.mode == .independent }),
              let localCid = uiState.localCid else {
            return StreamKeyedStageState(tiles: [], spotlightId: nil, active: false)
        }
        let cameras: [StageCameraParticipant] =
            uiState.remoteParticipants.map {
                StageCameraParticipant(cid: $0.cid, isLocal: false)
            }
            + [StageCameraParticipant(cid: localCid, isLocal: true)]
        let tiles = deriveStageTiles(cameras: cameras, content: stageContentAll)
        let spotlightId = pickStageSpotlightTileId(tiles: tiles, pinnedTile: pinnedTile, contentPrimary: contentScene.primary)
        let active = !tiles.isEmpty && spotlightId != nil && shouldRenderContentStage(
            phase: contentStagePhase(uiState.phase),
            isMultiParty: isMultiParty,
            hasContentStageLayout: true
        )
        return StreamKeyedStageState(tiles: tiles, spotlightId: spotlightId, active: active)
    }

    private var stageTiles: [StageTile] {
        streamKeyedStageState.tiles
    }

    private var stageSpotlightId: String? {
        streamKeyedStageState.spotlightId
    }

    private var streamKeyedStageActive: Bool {
        streamKeyedStageState.active
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
    /// Resolved content scene (Phase 4b). Drives independent content tiles.
    let contentScene: ContentScene
    /// The content-role source for this render (the resolver's chosen primary),
    /// or nil. In independent mode this carries `mode == .independent`.
    let resolvedContentSource: ResolvedContentSource?
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

    /// Whether a content-stage layout should be computed this render. Driven by
    /// the resolver's chosen primary (`resolvedContentSource`) instead of
    /// inferring content from `cameraMode`. A pin also forces the computed
    /// layout. Flag-off ⇒ `resolvedContentSource` reproduces the legacy
    /// single-sharer content-stage entry (byte-identical for the common case).
    private var hasContentSource: Bool {
        resolvedContentSource != nil || remoteContentCid != nil
    }

    var body: some View {
        GeometryReader { geometry in
            let availableWidth = geometry.size.width - outerPadding * 2
            let availableHeight = max(0, geometry.size.height - (20 + bottomPadding + 4))
            let useComputedLayout = localCid != nil && (pinnedParticipantId != nil || hasContentSource)

            if useComputedLayout, let localCid {
                // Content source for the layout, driven by the resolver's chosen
                // primary. Falls back to the legacy diagnostics pointer only when
                // the resolver yielded nothing (e.g. a pin with no active share),
                // preserving the existing multi-party content-stage behavior.
                let activeContentSource: ContentSource? = {
                    if let resolved = resolvedContentSource {
                        return ContentSource(type: resolved.type, ownerParticipantId: resolved.ownerCid, aspectRatio: nil)
                    } else if let remoteCid = remoteContentCid {
                        let type = ContentType.fromWire(remoteContentType)
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

/// Stream-keyed filmstrip + spotlight stage for active INDEPENDENT content.
///
/// Every stream is its own tile keyed `{cid, kind}`: a camera tile per camera-on
/// participant (local + remote) and a content tile per independent sharer (local
/// + remote, including the local user's own screen). A sharer's camera and screen
/// are TWO EQUAL peer tiles — NOT a camera-over-content PIP. A single spotlight is
/// the resolver's most-recent share by default; tap any tile to pin it as the
/// spotlight, tap the spotlight again to unpin (revert to default).
///
/// Geometry reuses the conformance-locked ``computeLayout`` via a composite-id
/// FOCUS scene: each `SceneParticipant.id` is the opaque `"cid::kind"` tile id and
/// `pinnedParticipantId` is the spotlight id, so the engine runs
/// `computePrimaryWithFilmstrip` (single spotlight + filmstrip) over the
/// stream-keyed tiles. The lone-tile edge (1:1 share with both cameras off) would
/// derive `.solo` in the engine, so it is emitted directly as a full-area
/// spotlight (no filmstrip), matching web. Tile ids are opaque to the engine; the
/// content/focus engine code paths are untouched.
private struct StreamKeyedStage: View {
    let remoteParticipants: [RemoteParticipant]
    @Binding var remoteTileAspectRatios: [String: CGFloat]
    let localCid: String?
    let localVideoEnabled: Bool
    let localAudioEnabled: Bool
    let localAudioLevel: Float
    let localDisplayName: String?
    let localMirror: Bool
    let localCameraMode: LocalCameraMode
    let contentScene: ContentScene
    @Binding var remoteVideoFitCover: Bool
    let bottomPadding: CGFloat
    let rendererProvider: CallRendererProvider
    @Binding var pinnedTile: StageTileKey?
    let strings: [SerenadaString: String]?
    let onAdjustCameraZoom: (CGFloat) -> Void
    let onTapBackground: () -> Void

    @State private var lastMagnificationValue: CGFloat = 1

    private func str(_ key: SerenadaString) -> String {
        resolveString(key, overrides: strings)
    }

    private var tiles: [StageTile] {
        guard let localCid else { return [] }
        let cameras: [StageCameraParticipant] =
            remoteParticipants.map { StageCameraParticipant(cid: $0.cid, isLocal: false) }
            + [StageCameraParticipant(cid: localCid, isLocal: true)]
        return deriveStageTiles(cameras: cameras, content: stageContent(for: contentScene))
    }

    private var spotlightId: String? {
        pickStageSpotlightTileId(tiles: tiles, pinnedTile: pinnedTile, contentPrimary: contentScene.primary)
    }

    private func resolvedContent(for cid: String) -> ResolvedContent? {
        if let local = contentScene.local, local.ownerCid == cid { return local }
        return contentScene.remotes.first(where: { $0.ownerCid == cid })
    }

    private func remote(for cid: String) -> RemoteParticipant? {
        remoteParticipants.first(where: { $0.cid == cid })
    }

    var body: some View {
        GeometryReader { geometry in
            let tiles = self.tiles
            ZStack {
                frontlineFallbackBackground
                if let spotlightId, !tiles.isEmpty, let layoutTiles = computedTiles(tiles: tiles, spotlightId: spotlightId, geometry: geometry) {
                    ForEach(layoutTiles, id: \.id) { tile in
                        tileView(tile, spotlightId: spotlightId)
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
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .contentShape(Rectangle())
        .onTapGesture { onTapBackground() }
    }

    private var frontlineFallbackBackground: some View {
        Color.black
    }

    /// Build the composite-id FOCUS scene and run ``computeLayout``. Handles the
    /// lone-tile edge by emitting a full-area spotlight directly (the engine would
    /// otherwise derive `.solo`, which yields no tiles + a PIP). Mirrors web.
    private func computedTiles(tiles: [StageTile], spotlightId: String, geometry: GeometryProxy) -> [TileLayout]? {
        if tiles.count == 1 {
            return [TileLayout(
                id: spotlightId,
                type: .participant,
                frame: LayoutRect(x: 0, y: 0, width: geometry.size.width, height: geometry.size.height),
                fit: remoteVideoFitCover ? .cover : .contain,
                cornerRadius: 0,
                zOrder: 0
            )]
        }

        let participants: [SceneParticipant] = tiles.map { tile in
            SceneParticipant(
                // Spotlight is `.local` so the engine never demotes it to a PIP;
                // filmstrip tiles are `.remote`. Role only affects PIP/order here,
                // and FOCUS emits no PIP, so this is purely structural.
                id: tile.id,
                role: tile.id == spotlightId ? .local : .remote,
                videoEnabled: true,
                videoAspectRatio: tile.kind == .camera ? remoteTileAspectRatios[tile.cid] : nil
            )
        }

        let scene = CallScene(
            viewportWidth: geometry.size.width,
            viewportHeight: geometry.size.height,
            safeAreaInsets: LayoutInsets(top: 20, bottom: bottomPadding + 4, left: 0, right: 0),
            participants: participants,
            localParticipantId: spotlightId,
            activeSpeakerId: nil,
            // Pin the spotlight tile → FOCUS mode (single spotlight + filmstrip).
            pinnedParticipantId: spotlightId,
            contentSource: nil,
            userPrefs: UserLayoutPrefs(dominantFit: remoteVideoFitCover ? .cover : .contain)
        )
        return computeLayout(scene: scene).tiles
    }

    @ViewBuilder
    private func tileView(_ tile: TileLayout, spotlightId: String) -> some View {
        if let key = parseStageTileId(tile.id) {
            let isLocal = key.cid == localCid
            let isContent = key.kind == .content
            let isSpotlight = tile.zOrder == 0
            let pinned = stageTileKeyEquals(pinnedTile, key)

            ZStack {
                Color.black

                if isContent {
                    contentTileBody(key: key, fit: tile.fit)
                } else if isLocal {
                    cameraTileBody(isLocal: true, cid: key.cid, fit: tile.fit)
                } else {
                    cameraTileBody(isLocal: false, cid: key.cid, fit: tile.fit)
                }

                if pinned {
                    pinIndicator
                }

                // Fit/cover toggle on the spotlight tile only (matches MultiPartyStage).
                if isSpotlight {
                    fitButton
                }

                if !isContent {
                    cameraBadge(isLocal: isLocal, cid: key.cid)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: tile.cornerRadius))
            // Tap any tile to pin it; tap the spotlight again to unpin (revert to
            // most-recent-share default).
            .onTapGesture {
                pinnedTile = stageTileKeyEquals(pinnedTile, key) ? nil : key
            }
            .simultaneousGesture(
                MagnificationGesture()
                    .onChanged { value in
                        guard isContent, isLocal, localCameraMode.isContentMode else { return }
                        let delta = value / max(lastMagnificationValue, 0.001)
                        lastMagnificationValue = value
                        onAdjustCameraZoom(delta)
                    }
                    .onEnded { _ in lastMagnificationValue = 1 }
            )
        }
    }

    @ViewBuilder
    private func contentTileBody(key: StageTileKey, fit: FitMode) -> some View {
        let owner = resolvedContent(for: key.cid)
        let isLocal = key.cid == localCid
        // Receiver-side hold: content active but media not arrived → status overlay
        // over the content sink (never a stale frame). The sink renders frames as
        // they arrive; the overlay covers the brief connecting gap.
        WebRTCVideoView(
            kind: isLocal ? .localContent : .remoteContentForCid(key.cid),
            rendererProvider: rendererProvider,
            // Content respects the fit/cover toggle: cover crops the share to fill.
            videoContentMode: fit == .contain ? .scaleAspectFit : .scaleAspectFill
        )
        if let owner, owner.loading || owner.waitingForParticipants {
            ContentStatusOverlay(
                text: owner.waitingForParticipants
                    ? str(.callContentWaitingForParticipants)
                    : str(.callContentConnecting)
            )
        }
    }

    @ViewBuilder
    private func cameraTileBody(isLocal: Bool, cid: String, fit: FitMode) -> some View {
        if isLocal {
            if localVideoEnabled {
                WebRTCVideoView(
                    kind: .local,
                    rendererProvider: rendererProvider,
                    videoContentMode: fit == .contain ? .scaleAspectFit : .scaleAspectFill,
                    isMirrored: localMirror
                )
            } else {
                VideoPlaceholderTile(text: str(.callCameraOff), compact: true)
            }
        } else if let remote = remote(for: cid) {
            if remote.videoEnabled {
                WebRTCVideoView(
                    kind: .remoteForCid(cid),
                    rendererProvider: rendererProvider,
                    videoContentMode: fit == .contain ? .scaleAspectFit : .scaleAspectFill,
                    onVideoSizeChanged: { size in
                        remoteTileAspectRatios[cid] = quantizedStageTileAspectRatio(size)
                    }
                )
            } else {
                VideoPlaceholderTile(text: str(.callVideoOff), compact: false, displayName: remote.displayName, peerId: remote.peerId)
            }
        }
    }

    @ViewBuilder
    private func cameraBadge(isLocal: Bool, cid: String) -> some View {
        let tileRemote = isLocal ? nil : remote(for: cid)
        let muted = isLocal ? !localAudioEnabled : tileRemote?.audioEnabled == false
        let name = isLocal ? localDisplayName : tileRemote?.displayName
        let nameForBadge: String? = (!isLocal && tileRemote?.videoEnabled == false) ? nil : name
        let level: Float? = isLocal ? localAudioLevel : tileRemote?.audioLevel
        ParticipantBadge(muted: muted, displayName: nameForBadge, audioLevel: level)
    }

    private var pinIndicator: some View {
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

    private var fitButton: some View {
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
                .buttonStyle(.plain)
                .padding(8)
            }
        }
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

/// Status overlay drawn ON TOP of an independent content tile while the content
/// is connecting (media not arrived yet — receiver-side hold) or the local user
/// is sharing with no participants receiving yet ("waiting for participants",
/// pitfall #8). Never replaces the content sink with a stale frame.
private struct ContentStatusOverlay: View {
    let text: String

    var body: some View {
        ZStack {
            Color.black.opacity(0.55)
            VStack(spacing: 12) {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(.white)
                Text(text)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white.opacity(0.9))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
        }
        .allowsHitTesting(false)
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
