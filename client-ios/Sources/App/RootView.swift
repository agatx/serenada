import SerenadaCallUI
import SerenadaCore
import SwiftUI

private enum RootScreen {
    case join
    case call
    case error
}

func shouldShowActiveCallScreen(
    sessionPhase: SerenadaCallPhase?,
    fallbackUiState: CallUiState
) -> Bool {
    if let sessionPhase {
        switch sessionPhase {
        case .awaitingPermissions, .waiting, .inCall, .ending:
            return true
        case .idle, .joining, .error:
            return false
        }
    }

    return fallbackUiState.phase == .waiting || fallbackUiState.phase == .inCall
}

struct RootView: View {
    @ObservedObject var callManager: CallManager

    @State private var hostInput = ""
    @State private var displayNameInput = ""
    @State private var roomInput = ""
    @State private var settingsHostError: String?
    @State private var settingsSaveInProgress = false

    @State private var showSettings = false
    @State private var showJoinWithCode = false
    @State private var showDiagnostics = false
    @State private var showFeedback = false

    var body: some View {
        let uiState = callManager.uiState
        let activeSession = callManager.activeSession
        let sessionPhase = activeSession?.state.phase
        let showActiveCallScreen = shouldShowActiveCallScreen(
            sessionPhase: sessionPhase,
            fallbackUiState: uiState
        )

        let currentScreen: RootScreen = {
            if showActiveCallScreen { return .call }
            if uiState.phase == .error { return .error }
            return .join
        }()

        ZStack(alignment: .top) {
            switch currentScreen {
            case .join:
                JoinScreen(
                    isBusy: uiState.phase == .creatingRoom || uiState.phase == .joining,
                    statusMessage: uiState.statusMessage ?? "",
                    recentCalls: callManager.recentCalls,
                    savedRooms: callManager.savedRooms,
                    areSavedRoomsShownFirst: callManager.areSavedRoomsShownFirst,
                    roomStatuses: callManager.roomStatuses,
                    serverHost: callManager.serverHost,
                    onOpenJoinWithCode: {
                        showJoinWithCode = true
                    },
                    onOpenSettings: {
                        hostInput = callManager.serverHost
                        displayNameInput = callManager.displayName
                        settingsHostError = nil
                        showSettings = true
                    },
                    onStartCall: {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        callManager.startNewCall()
                    },
                    onJoinRecentCall: { call in
                        callManager.joinRecentCall(call)
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
                if let session = activeSession {
                    SerenadaCallFlow(
                        session: session,
                        roomName: callManager.savedRooms.first(where: { $0.roomId == session.roomId })?.name,
                        initialRemoteVideoFitCover: SettingsStore().isRemoteVideoFitCover,
                        config: SerenadaCallFlowConfig(
                            screenSharingEnabled: true,
                            inviteControlsEnabled: true,
                            debugOverlayEnabled: true,
                            snapshotEnabled: true,
                            uiVariant: callManager.callUiVariant,
                            systemPictureInPictureEnabled: true
                        ),
                        strings: L10n.serenadaCallStrings,
                        onInviteToRoom: { await callManager.inviteToCurrentRoom() },
                        onRemoteVideoFitChanged: { value in
                            SettingsStore().isRemoteVideoFitCover = value
                        },
                        onEndCall: {
                            callManager.dismissActiveCall()
                        },
                        onDismiss: { callManager.dismissActiveCall() },
                        // Keep the prebuilt UI in lockstep with the bundled app's
                        // session opt-in from CallManager.
                        independentContentVideo: CallManager.independentContentVideoEnabled
                    )
                    .onSnapshotCaptured { result in
                        SnapshotSaver.save(jpegData: result.jpegData) { outcome in
                            switch outcome {
                            case .success:
                                callManager.presentSnapshotToast(saved: true, reason: nil)
                            case .failure(let failure):
                                callManager.presentSnapshotToast(
                                    saved: false,
                                    reason: failure.toastDescription
                                )
                            }
                        }
                    }
                    .onSnapshotError { error in
                        callManager.presentSnapshotToast(
                            saved: false,
                            reason: error.toastDescription
                        )
                    }
                }

            case .error:
                ErrorScreen(
                    message: uiState.errorMessage ?? L10n.errorUnknown,
                    onDismiss: {
                        callManager.dismissError()
                    }
                )
            }

            if let banner = callManager.snapshotBanner {
                SnapshotBannerView(banner: banner)
                    .padding(.top, 16)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .zIndex(50)
            }
        }
        .animation(.easeInOut(duration: 0.24), value: callManager.snapshotBanner)
        .animation(.easeInOut(duration: 0.24), value: currentScreen)
        .onAppear {
            hostInput = callManager.serverHost
            displayNameInput = callManager.displayName
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
                showFeedback = false
                settingsSaveInProgress = false
                settingsHostError = nil
                roomInput = ""
            }
        }
        .sheet(isPresented: $showSettings, onDismiss: { closeSettings() }) {
            NavigationStack {
                SettingsScreen(
                    host: $hostInput,
                    displayName: $displayNameInput,
                    showDiagnostics: $showDiagnostics,
                    selectedLanguage: callManager.selectedLanguage,
                    isDefaultCameraEnabled: callManager.isDefaultCameraEnabled,
                    isDefaultMicrophoneEnabled: callManager.isDefaultMicrophoneEnabled,
                    isHdVideoExperimentalEnabled: callManager.isHdVideoExperimentalEnabled,
                    callUiVariant: callManager.callUiVariant,
                    areSavedRoomsShownFirst: callManager.areSavedRoomsShownFirst,
                    areRoomInviteNotificationsEnabled: callManager.areRoomInviteNotificationsEnabled,
                    showFeedback: $showFeedback,
                    appVersion: callManager.appVersion,
                    hostError: settingsHostError,
                    isSaving: settingsSaveInProgress,
                    onLanguageSelect: { callManager.updateLanguage($0) },
                    onDefaultCameraChange: { callManager.updateDefaultCamera($0) },
                    onDefaultMicrophoneChange: { callManager.updateDefaultMicrophone($0) },
                    onHdVideoExperimentalChange: { callManager.updateHdVideoExperimental($0) },
                    onCallUiVariantChange: { callManager.updateCallUiVariant($0) },
                    onSavedRoomsShownFirstChange: { callManager.updateSavedRoomsShownFirst($0) },
                    onRoomInviteNotificationsChange: { callManager.updateRoomInviteNotifications($0) },
                    onDisplayNameChange: { callManager.updateDisplayName($0) }
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
                .navigationDestination(isPresented: $showFeedback) {
                    FeedbackScreen(
                        host: hostInput,
                        appVersion: callManager.appVersion,
                        locale: callManager.selectedLanguage,
                        onDismiss: { showFeedback = false }
                    )
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
        displayNameInput = callManager.displayName
        settingsHostError = nil
        settingsSaveInProgress = false
        showDiagnostics = false
        showFeedback = false
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

private struct SnapshotBannerView: View {
    let banner: CallManager.SnapshotBanner

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: banner.success ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
            Text(banner.message)
                .font(.subheadline.weight(.medium))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
        .clipShape(Capsule())
        .foregroundStyle(banner.success ? Color.primary : Color.red)
        .shadow(color: Color.black.opacity(0.25), radius: 8, y: 3)
        .frame(maxWidth: .infinity, alignment: .center)
    }
}
