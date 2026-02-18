import SwiftUI

private enum RootScreen {
    case join
    case joinWithCode
    case settings
    case call
    case error
}

func shouldShowActiveCallScreen(for uiState: CallUiState) -> Bool {
    uiState.phase == .waiting || uiState.phase == .inCall
}

struct RootView: View {
    @ObservedObject var callManager: CallManager

    @State private var hostInput = ""
    @State private var roomInput = ""
    @State private var settingsHostError: String?
    @State private var settingsSaveInProgress = false

    @State private var showSettings = false
    @State private var showJoinWithCode = false

    var body: some View {
        let uiState = callManager.uiState
        let showActiveCallScreen = shouldShowActiveCallScreen(for: uiState)

        let currentScreen: RootScreen = {
            if showSettings { return .settings }
            if showJoinWithCode { return .joinWithCode }
            if showActiveCallScreen { return .call }
            if uiState.phase == .error { return .error }
            return .join
        }()

        ZStack {
            switch currentScreen {
            case .join:
                JoinScreen(
                    isBusy: uiState.phase == .creatingRoom || uiState.phase == .joining,
                    statusMessage: uiState.statusMessage ?? "",
                    recentCalls: callManager.recentCalls,
                    roomStatuses: callManager.roomStatuses,
                    onOpenJoinWithCode: {
                        showJoinWithCode = true
                    },
                    onOpenSettings: {
                        hostInput = callManager.serverHost
                        settingsHostError = nil
                        showSettings = true
                    },
                    onStartCall: {
                        callManager.startNewCall()
                    },
                    onJoinRecentCall: { roomId in
                        callManager.joinRoom(roomId)
                    },
                    onRemoveRecentCall: { roomId in
                        callManager.removeRecentCall(roomId: roomId)
                    }
                )

            case .joinWithCode:
                JoinWithCodeScreen(
                    roomInput: $roomInput,
                    isBusy: uiState.phase == .creatingRoom || uiState.phase == .joining,
                    statusMessage: uiState.statusMessage ?? "",
                    errorMessage: uiState.errorMessage,
                    onJoinCall: {
                        callManager.joinFromInput(roomInput)
                    },
                    onBack: {
                        if callManager.uiState.phase == .error {
                            callManager.dismissError()
                        }
                        showJoinWithCode = false
                        roomInput = ""
                    }
                )

            case .settings:
                SettingsScreen(
                    host: $hostInput,
                    selectedLanguage: callManager.selectedLanguage,
                    isDefaultCameraEnabled: callManager.isDefaultCameraEnabled,
                    isDefaultMicrophoneEnabled: callManager.isDefaultMicrophoneEnabled,
                    isHdVideoExperimentalEnabled: callManager.isHdVideoExperimentalEnabled,
                    hostError: settingsHostError,
                    isSaving: settingsSaveInProgress,
                    onLanguageSelect: { callManager.updateLanguage($0) },
                    onDefaultCameraChange: { callManager.updateDefaultCamera($0) },
                    onDefaultMicrophoneChange: { callManager.updateDefaultMicrophone($0) },
                    onHdVideoExperimentalChange: { callManager.updateHdVideoExperimental($0) },
                    onSave: {
                        saveSettings()
                    },
                    onCancel: {
                        closeSettings()
                    }
                )

            case .call:
                if let roomId = uiState.roomId {
                    CallScreen(
                        roomId: roomId,
                        uiState: uiState,
                        serverHost: callManager.serverHost,
                        onToggleAudio: { callManager.toggleAudio() },
                        onToggleVideo: { callManager.toggleVideo() },
                        onFlipCamera: { callManager.flipCamera() },
                        onToggleFlashlight: { _ = callManager.toggleFlashlight() },
                        onEndCall: { callManager.endCall() },
                        callManager: callManager
                    )
                }

            case .error:
                ErrorScreen(
                    message: uiState.errorMessage ?? L10n.errorUnknown,
                    onDismiss: {
                        callManager.dismissError()
                    }
                )
            }
        }
        .animation(.easeInOut(duration: 0.24), value: currentScreen)
        .onAppear {
            hostInput = callManager.serverHost
        }
        .onChange(of: callManager.serverHost) { newHost in
            hostInput = newHost
        }
        .onChange(of: callManager.uiState.phase) { phase in
            if phase == .waiting || phase == .inCall {
                showJoinWithCode = false
                roomInput = ""
            }
        }
        .onChange(of: showActiveCallScreen) { isActive in
            if isActive {
                showJoinWithCode = false
                showSettings = false
                settingsSaveInProgress = false
                settingsHostError = nil
                roomInput = ""
            }
        }
    }

    private func closeSettings() {
        hostInput = callManager.serverHost
        settingsHostError = nil
        settingsSaveInProgress = false
        showSettings = false
    }

    private func saveSettings() {
        settingsSaveInProgress = true
        settingsHostError = nil

        Task {
            let result = await callManager.validateServerHost(hostInput)
            switch result {
            case .success(let normalizedHost):
                callManager.updateServerHost(normalizedHost)
                closeSettings()

            case .failure:
                settingsHostError = L10n.settingsErrorInvalidServerHost
                settingsSaveInProgress = false
            }
        }
    }
}
