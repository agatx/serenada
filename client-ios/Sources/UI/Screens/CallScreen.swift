import SwiftUI

func shouldShowCallStatusLabel(
    phase: CallPhase,
    isSignalingConnected: Bool,
    iceConnectionState: String?,
    connectionState: String?
) -> Bool {
    guard phase == .inCall else { return false }

    return !isSignalingConnected ||
        iceConnectionState == "DISCONNECTED" ||
        iceConnectionState == "FAILED" ||
        connectionState == "DISCONNECTED" ||
        connectionState == "FAILED"
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

func shouldShowRemoteFitButton(phase: CallPhase, remoteVideoEnabled: Bool, isLocalLarge: Bool) -> Bool {
    phase == .inCall && remoteVideoEnabled && !isLocalLarge
}

func shouldRenderLocalAsPrimarySurface(phase: CallPhase, isLocalLarge: Bool) -> Bool {
    phase == .inCall && isLocalLarge
}

func pipBottomPadding(isLandscape: Bool, areControlsVisible: Bool) -> CGFloat {
    if isLandscape {
        return areControlsVisible ? 92 : 24
    }
    return areControlsVisible ? 170 : 52
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
    let reconnectStatus: DebugStatus = uiState.isReconnecting ? .bad : .good

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
                DebugPanelMetric(label: "Reconnecting", value: uiState.isReconnecting ? "yes" : "no", status: reconnectStatus)
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

struct CallScreen: View {
    let roomId: String
    let uiState: CallUiState
    let serverHost: String
    let onToggleAudio: () -> Void
    let onToggleVideo: () -> Void
    let onFlipCamera: () -> Void
    let onToggleScreenShare: () -> Void
    let onAdjustCameraZoom: (CGFloat) -> Void
    let onResetCameraZoom: () -> Void
    let onToggleFlashlight: () -> Void
    let onEndCall: () -> Void
    let onInviteToRoom: () async -> Result<Void, Error>
    let callManager: CallManager

    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var areControlsVisible = true
    @State private var isLocalLarge = false
    @State private var remoteVideoFitCover = true
    @State private var showShareSheet = false
    @State private var inviteStatusMessage: String?
    @State private var showDebugPanel = false
    @State private var lastDebugTapAt: Date?
    @State private var lastMagnificationValue: CGFloat = 1

    var body: some View {
        let showLocalAsPrimarySurface = shouldRenderLocalAsPrimarySurface(
            phase: uiState.phase,
            isLocalLarge: isLocalLarge
        )
        let isPinchZoomEnabled = shouldEnablePinchZoom(showLocalAsPrimarySurface: showLocalAsPrimarySurface)

        ZStack {
            Color.black.ignoresSafeArea()

            if uiState.phase == .waiting {
                waitingMainSurface
                smallLocalView
            } else if showLocalAsPrimarySurface {
                mainVideoSurface(
                    kind: .local,
                    showPlaceholder: shouldShowLocalVideoPlaceholder(localVideoEnabled: uiState.localVideoEnabled),
                    placeholderText: L10n.callLocalCameraOff
                )
                smallRemoteView
            } else {
                mainVideoSurface(
                    kind: .remote,
                    videoContentMode: remoteVideoFitCover ? .scaleAspectFill : .scaleAspectFit,
                    showPlaceholder: shouldShowRemoteVideoPlaceholder(
                        phase: uiState.phase,
                        remoteVideoEnabled: uiState.remoteVideoEnabled
                    ),
                    placeholderText: uiState.phase == .inCall ? L10n.callVideoOff : nil
                )
                smallLocalView
            }

            backgroundInteractionLayer(isPinchZoomEnabled: isPinchZoomEnabled)
            overlays
        }
        .onChange(of: uiState.isFrontCamera) { isFront in
            isLocalLarge = !isFront
        }
        .onChange(of: isPinchZoomEnabled) { enabled in
            if !enabled {
                lastMagnificationValue = 1
                onResetCameraZoom()
            }
        }
        .task(id: areControlsVisible) {
            guard areControlsVisible, uiState.phase == .inCall else { return }
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard uiState.phase == .inCall else { return }
            withAnimation(.easeInOut(duration: 0.25)) {
                areControlsVisible = false
            }
        }
        .sheet(isPresented: $showShareSheet) {
            ActivityView(items: ["https://\(serverHost)/call/\(roomId)"])
        }
        .overlay(alignment: .topLeading) {
            Color.clear
                .frame(width: 1, height: 1)
                .accessibilityIdentifier("call.screen")
        }
    }

    private func backgroundInteractionLayer(isPinchZoomEnabled: Bool) -> some View {
        Color.clear
            .contentShape(Rectangle())
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.2)) {
                    areControlsVisible.toggle()
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
        placeholderText: String?
    ) -> some View {
        ZStack {
            Color.black.ignoresSafeArea()
            WebRTCVideoView(
                kind: kind,
                callManager: callManager,
                videoContentMode: videoContentMode,
                isMirrored: kind == .local && uiState.isFrontCamera
            )
                .ignoresSafeArea()

            if showPlaceholder {
                VideoPlaceholderTile(text: placeholderText, compact: false)
                    .ignoresSafeArea()
            }
        }
    }

    private var waitingMainSurface: some View {
        Color.black.ignoresSafeArea()
    }

    private var smallLocalView: some View {
        ZStack {
            Color.black
            WebRTCVideoView(
                kind: .local,
                callManager: callManager,
                videoContentMode: .scaleAspectFill,
                isMirrored: uiState.isFrontCamera
            )

            if shouldShowLocalVideoPlaceholder(localVideoEnabled: uiState.localVideoEnabled) {
                VideoPlaceholderTile(text: L10n.callCameraOff, compact: true)
            }
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
        ZStack {
            Color.black
            WebRTCVideoView(kind: .remote, callManager: callManager, videoContentMode: .scaleAspectFill)

            if shouldShowRemoteVideoPlaceholder(phase: uiState.phase, remoteVideoEnabled: uiState.remoteVideoEnabled) {
                VideoPlaceholderTile(text: uiState.phase == .inCall ? L10n.callVideoOff : nil, compact: true)
            }
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

                if shouldShowWaitingOverlay(phase: uiState.phase) {
                    waitingOverlay
                }

                if areControlsVisible {
                    controlBar
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }

            Color.clear
                .frame(width: 72, height: 72)
                .contentShape(Rectangle())
                .onTapGesture {
                    handleDebugTap()
                }

            if showDebugPanel && uiState.phase == .inCall {
                debugPanelView
                    .padding(.top, 80)
                    .padding(.leading, 12)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: areControlsVisible)
    }

    private var topStatus: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                if shouldShowCallStatusLabel(
                    phase: uiState.phase,
                    isSignalingConnected: uiState.isSignalingConnected,
                    iceConnectionState: uiState.iceConnectionState,
                    connectionState: uiState.connectionState
                ) {
                    Text(statusLabel)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color.black.opacity(0.45))
                        .clipShape(Capsule())
                }

                Spacer()

                if uiState.isFlashAvailable {
                    iconButton(system: uiState.isFlashEnabled ? "flashlight.on.fill" : "flashlight.off.fill", accessibilityLabel: uiState.isFlashEnabled ? L10n.callA11yFlashlightOn : L10n.callA11yFlashlightOff) {
                        onToggleFlashlight()
                    }
                }

                if uiState.phase == .waiting {
                    iconButton(system: "square.and.arrow.up", accessibilityLabel: L10n.callA11yShareInvite) {
                        showShareSheet = true
                    }
                }

                if shouldShowRemoteFitButton(
                    phase: uiState.phase,
                    remoteVideoEnabled: uiState.remoteVideoEnabled,
                    isLocalLarge: isLocalLarge
                ) {
                    iconButton(system: remoteVideoFitCover ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right", accessibilityLabel: remoteVideoFitCover ? L10n.callA11yVideoFit : L10n.callA11yVideoFill) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            remoteVideoFitCover.toggle()
                        }
                    }
                }
            }

            if shouldShowWaitingOverlay(phase: uiState.phase) {
                QRCodeImageView(text: "https://\(serverHost)/call/\(roomId)")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .opacity(uiState.phase == .waiting ? 1 : (areControlsVisible ? 1 : 0))
    }

    private var waitingOverlay: some View {
        VStack(spacing: 12) {
            Text(L10n.callWaitingOverlay)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)

            Button {
                Task {
                    let result = await onInviteToRoom()
                    await MainActor.run {
                        switch result {
                        case .success:
                            inviteStatusMessage = L10n.callInviteSent
                        case .failure:
                            inviteStatusMessage = L10n.callInviteFailed
                        }
                    }
                }
            } label: {
                Label(L10n.callInviteToRoom, systemImage: "bell.badge.fill")
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
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color.black.opacity(0.45))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .padding(.bottom, 20)
    }

    private var controlBar: some View {
        HStack(spacing: 14) {
            iconButton(system: uiState.localAudioEnabled ? "mic.fill" : "mic.slash.fill", accessibilityLabel: uiState.localAudioEnabled ? L10n.callA11yMuteOn : L10n.callA11yMuteOff) {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                onToggleAudio()
            }

            iconButton(system: uiState.localVideoEnabled ? "video.fill" : "video.slash.fill", accessibilityLabel: uiState.localVideoEnabled ? L10n.callA11yVideoOn : L10n.callA11yVideoOff) {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                onToggleVideo()
            }

            iconButton(system: "camera.rotate.fill", accessibilityLabel: L10n.callA11yFlipCamera) {
                onFlipCamera()
            }

            iconButton(system: uiState.isScreenSharing ? "rectangle.on.rectangle.slash" : "rectangle.on.rectangle", accessibilityLabel: uiState.isScreenSharing ? L10n.callA11yScreenShareOn : L10n.callA11yScreenShareOff) {
                onToggleScreenShare()
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
            .accessibilityLabel(L10n.callA11yEndCall)
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

    private func shouldEnablePinchZoom(showLocalAsPrimarySurface: Bool) -> Bool {
        if uiState.phase != .inCall { return false }
        if uiState.isScreenSharing { return false }
        if !showLocalAsPrimarySurface { return false }
        return uiState.localCameraMode == .world || uiState.localCameraMode == .composite
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

    private var statusLabel: String {
        L10n.callReconnecting
    }

    private var isLandscape: Bool {
        verticalSizeClass == .compact
    }
}

private struct VideoPlaceholderTile: View {
    let text: String?
    let compact: Bool

    var body: some View {
        ZStack {
            Color.black
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
