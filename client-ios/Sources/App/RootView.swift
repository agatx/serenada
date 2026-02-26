import SwiftUI

private enum RootScreen {
    case join
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
    @State private var showDiagnostics = false

    var body: some View {
        let uiState = callManager.uiState
        let showActiveCallScreen = shouldShowActiveCallScreen(for: uiState)

        let currentScreen: RootScreen = {
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
                    savedRooms: callManager.savedRooms,
                    areSavedRoomsShownFirst: callManager.areSavedRoomsShownFirst,
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
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        callManager.startNewCall()
                    },
                    onJoinRecentCall: { roomId in
                        callManager.joinRoom(roomId)
                    },
                    onJoinSavedRoom: { room in
                        callManager.joinSavedRoom(room)
                    },
                    onRemoveRecentCall: { roomId in
                        callManager.removeRecentCall(roomId: roomId)
                    },
                    onSaveRoom: { roomId, name in
                        callManager.saveRoom(roomId: roomId, name: name)
                    },
                    onCreateSavedRoomInviteLink: { roomName in
                        await callManager.createSavedRoomInviteLink(roomName: roomName, hostInput: hostInput)
                    },
                    onRemoveSavedRoom: { roomId in
                        callManager.removeSavedRoom(roomId: roomId)
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
                        onToggleScreenShare: { callManager.toggleScreenShare() },
                        onAdjustCameraZoom: { delta in
                            callManager.adjustCameraZoom(scaleDelta: delta)
                        },
                        onResetCameraZoom: { callManager.resetCameraZoom() },
                        onToggleFlashlight: { _ = callManager.toggleFlashlight() },
                        onEndCall: { callManager.endCall() },
                        onInviteToRoom: { await callManager.inviteToCurrentRoom() },
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
                showDiagnostics = false
                settingsSaveInProgress = false
                settingsHostError = nil
                roomInput = ""
            }
        }
        .sheet(isPresented: $showSettings, onDismiss: { closeSettings() }) {
            NavigationStack {
                SettingsScreen(
                    host: $hostInput,
                    showDiagnostics: $showDiagnostics,
                    selectedLanguage: callManager.selectedLanguage,
                    isDefaultCameraEnabled: callManager.isDefaultCameraEnabled,
                    isDefaultMicrophoneEnabled: callManager.isDefaultMicrophoneEnabled,
                    isHdVideoExperimentalEnabled: callManager.isHdVideoExperimentalEnabled,
                    areSavedRoomsShownFirst: callManager.areSavedRoomsShownFirst,
                    areRoomInviteNotificationsEnabled: callManager.areRoomInviteNotificationsEnabled,
                    appVersion: callManager.appVersion,
                    hostError: settingsHostError,
                    isSaving: settingsSaveInProgress,
                    onLanguageSelect: { callManager.updateLanguage($0) },
                    onDefaultCameraChange: { callManager.updateDefaultCamera($0) },
                    onDefaultMicrophoneChange: { callManager.updateDefaultMicrophone($0) },
                    onHdVideoExperimentalChange: { callManager.updateHdVideoExperimental($0) },
                    onSavedRoomsShownFirstChange: { callManager.updateSavedRoomsShownFirst($0) },
                    onRoomInviteNotificationsChange: { callManager.updateRoomInviteNotifications($0) }
                )
                .navigationTitle(L10n.settingsTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L10n.settingsCancel) { closeSettings() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        if settingsSaveInProgress {
                            ProgressView()
                        } else {
                            Button(L10n.settingsSave) { saveSettings() }
                                .disabled(settingsSaveInProgress)
                                .tint(.accentColor)
                        }
                    }
                }
                .navigationDestination(isPresented: $showDiagnostics) {
                    DiagnosticsScreen(host: hostInput)
                }
            }
        }
        .sheet(isPresented: $showJoinWithCode, onDismiss: {
            roomInput = ""
            if callManager.uiState.phase == .error {
                callManager.dismissError()
            }
        }) {
            NavigationStack {
                JoinWithCodeScreen(
                    roomInput: $roomInput,
                    isBusy: uiState.phase == .creatingRoom || uiState.phase == .joining,
                    statusMessage: uiState.statusMessage ?? "",
                    errorMessage: uiState.errorMessage,
                    onJoin: {
                        callManager.joinFromInput(roomInput)
                    }
                )
                .navigationTitle(L10n.joinWithCodeTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L10n.settingsCancel) {
                            showJoinWithCode = false
                            roomInput = ""
                        }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.joinWithCodeAction) {
                            callManager.joinFromInput(roomInput)
                        }
                        .disabled(uiState.phase == .creatingRoom || uiState.phase == .joining || roomInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .tint(.accentColor)
                    }
                }
            }
        }
    }

    private func closeSettings() {
        hostInput = callManager.serverHost
        settingsHostError = nil
        settingsSaveInProgress = false
        showDiagnostics = false
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
