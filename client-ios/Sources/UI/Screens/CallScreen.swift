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

struct CallScreen: View {
    let roomId: String
    let uiState: CallUiState
    let serverHost: String
    let onToggleAudio: () -> Void
    let onToggleVideo: () -> Void
    let onFlipCamera: () -> Void
    let onToggleFlashlight: () -> Void
    let onEndCall: () -> Void
    let callManager: CallManager

    @State private var areControlsVisible = true
    @State private var isLocalLarge = false
    @State private var showShareSheet = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if isLocalLarge {
                mainVideoSurface(
                    kind: .local,
                    showPlaceholder: shouldShowLocalVideoPlaceholder(localVideoEnabled: uiState.localVideoEnabled),
                    placeholderText: L10n.callLocalCameraOff
                )
                smallRemoteView
            } else {
                mainVideoSurface(
                    kind: .remote,
                    showPlaceholder: shouldShowRemoteVideoPlaceholder(
                        phase: uiState.phase,
                        remoteVideoEnabled: uiState.remoteVideoEnabled
                    ),
                    placeholderText: uiState.phase == .inCall ? L10n.callVideoOff : nil
                )
                smallLocalView
            }

            overlays
        }
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(.easeInOut(duration: 0.2)) {
                areControlsVisible.toggle()
            }
        }
        .onChange(of: uiState.isFrontCamera) { isFront in
            isLocalLarge = !isFront
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
    }

    private func mainVideoSurface(kind: WebRTCVideoView.Kind, showPlaceholder: Bool, placeholderText: String?) -> some View {
        ZStack {
            Color.black.ignoresSafeArea()
            WebRTCVideoView(kind: kind, callManager: callManager)
                .ignoresSafeArea()

            if showPlaceholder {
                VideoPlaceholderTile(text: placeholderText, compact: false)
                    .ignoresSafeArea()
            }
        }
    }

    private var smallLocalView: some View {
        ZStack {
            Color.black
            WebRTCVideoView(kind: .local, callManager: callManager)

            if shouldShowLocalVideoPlaceholder(localVideoEnabled: uiState.localVideoEnabled) {
                VideoPlaceholderTile(text: L10n.callCameraOff, compact: true)
            }
        }
            .frame(width: 110, height: 160)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.35), lineWidth: 1))
            .padding(.trailing, 16)
            .padding(.bottom, areControlsVisible ? 170 : 52)
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
            WebRTCVideoView(kind: .remote, callManager: callManager)

            if shouldShowRemoteVideoPlaceholder(phase: uiState.phase, remoteVideoEnabled: uiState.remoteVideoEnabled) {
                VideoPlaceholderTile(text: uiState.phase == .inCall ? L10n.callVideoOff : nil, compact: true)
            }
        }
            .frame(width: 110, height: 160)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.35), lineWidth: 1))
            .padding(.trailing, 16)
            .padding(.bottom, areControlsVisible ? 170 : 52)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isLocalLarge.toggle()
                }
            }
    }

    private var overlays: some View {
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
                    iconButton(system: uiState.isFlashEnabled ? "flashlight.on.fill" : "flashlight.off.fill") {
                        onToggleFlashlight()
                    }
                }

                if uiState.phase == .waiting {
                    iconButton(system: "square.and.arrow.up") {
                        showShareSheet = true
                    }
                }
            }

            if shouldShowWaitingOverlay(phase: uiState.phase) {
                QRCodeImageView(text: "https://\(serverHost)/call/\(roomId)")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .opacity(areControlsVisible ? 1 : 0)
    }

    private var waitingOverlay: some View {
        Text(L10n.callWaitingOverlay)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Color.black.opacity(0.45))
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .padding(.bottom, 20)
    }

    private var controlBar: some View {
        HStack(spacing: 14) {
            iconButton(system: uiState.localAudioEnabled ? "mic.fill" : "mic.slash.fill") {
                onToggleAudio()
            }

            iconButton(system: uiState.localVideoEnabled ? "video.fill" : "video.slash.fill") {
                onToggleVideo()
            }

            iconButton(system: "camera.rotate.fill") {
                onFlipCamera()
            }

            Button(action: onEndCall) {
                Image(systemName: "phone.down.fill")
                    .font(.system(size: 19, weight: .bold))
                    .frame(width: 58, height: 58)
                    .background(Color.red)
                    .clipShape(Circle())
                    .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .padding(.horizontal, 18)
        .padding(.bottom, 26)
    }

    private func iconButton(system: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.system(size: 18, weight: .semibold))
                .frame(width: 48, height: 48)
                .background(Color.black.opacity(0.45))
                .clipShape(Circle())
                .foregroundStyle(.white)
        }
        .buttonStyle(.plain)
    }

    private var statusLabel: String {
        L10n.callReconnecting
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
