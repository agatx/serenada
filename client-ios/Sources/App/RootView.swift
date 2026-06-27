import SerenadaCallUI
import SerenadaCore
import SwiftUI

enum RootScreen: Equatable {
    case join
    case call
    /// No active (foreground) call, but the registry still has live held calls.
    /// Render a "calls on hold" surface so those calls stay reachable instead of
    /// dropping the user to Join (FIX P5-3: Invariant 5, no auto-promote).
    case heldOnly
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

/// Decide the root screen from the active-call flag, the fallback UI phase, and
/// whether live held calls remain. Pure + static so the "held surface vs idle"
/// routing is unit-testable (FIX P5-3). Precedence:
///   1. active call screen wins
///   2. whole-app error screen
///   3. held surface when live held calls remain (no active call) — Invariant 5
///   4. otherwise Join/idle
func rootScreen(
    showActiveCallScreen: Bool,
    uiPhase: CallPhase,
    hasLiveHeldCalls: Bool
) -> RootScreen {
    if showActiveCallScreen { return .call }
    if uiPhase == .error { return .error }
    if hasLiveHeldCalls { return .heldOnly }
    return .join
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

        let currentScreen = rootScreen(
            showActiveCallScreen: showActiveCallScreen,
            uiPhase: uiState.phase,
            hasLiveHeldCalls: !callManager.heldCalls.isEmpty
        )

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

            case .heldOnly:
                // Active call ended (or was held) with live held calls remaining.
                // Render a holding surface so those calls stay reachable instead of
                // dropping to Join (FIX P5-3, Invariant 5: no auto-promote).
                HeldCallsHoldingScreen(
                    heldCalls: callManager.heldCalls,
                    isBusy: callManager.isCallOperationInProgress,
                    onSwitch: { callManager.switchToHeldCall(id: $0) },
                    onLeave: { callManager.leaveHeldCall(id: $0) }
                )

            case .error:
                ErrorScreen(
                    message: uiState.errorMessage ?? L10n.errorUnknown,
                    onDismiss: {
                        callManager.dismissError()
                    }
                )
            }

            // The horizontal held-call switcher floats over the ACTIVE call screen.
            // On the `.heldOnly` surface the holding screen renders its own list, so
            // the floating switcher is not stacked on top of it.
            if currentScreen == .call, !callManager.heldCalls.isEmpty {
                HeldCallsSwitcher(
                    heldCalls: callManager.heldCalls,
                    isBusy: callManager.isCallOperationInProgress,
                    onSwitch: { callManager.switchToHeldCall(id: $0) },
                    onLeave: { callManager.leaveHeldCall(id: $0) }
                )
                .padding(.top, 8)
                .transition(.move(edge: .top).combined(with: .opacity))
                .zIndex(40)
            }

            if let banner = callManager.callErrorBanner {
                CallErrorBannerView(banner: banner)
                    .padding(.top, 16)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .zIndex(55)
            }

            if let banner = callManager.snapshotBanner {
                SnapshotBannerView(banner: banner)
                    .padding(.top, 16)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .zIndex(50)
            }
        }
        .animation(.easeInOut(duration: 0.24), value: callManager.heldCalls)
        .animation(.easeInOut(duration: 0.24), value: callManager.snapshotBanner)
        .animation(.easeInOut(duration: 0.24), value: callManager.callErrorBanner)
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

/// Minimal held-calls switcher (Phase 5, design "UI and UX Contract": held call
/// chips + a switch action). Not a full multi-call UI — a thin affordance driven
/// entirely by registry state. Hidden when there are no held calls (the common
/// single-call case).
private struct HeldCallsSwitcher: View {
    let heldCalls: [ManagedCallState]
    let isBusy: Bool
    let onSwitch: (CallId) -> Void
    let onLeave: (CallId) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(heldCalls, id: \.id) { call in
                    HeldCallChip(
                        call: call,
                        isBusy: isBusy,
                        onSwitch: { onSwitch(call.id) },
                        onLeave: { onLeave(call.id) }
                    )
                }
            }
            .padding(.horizontal, 14)
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }
}

private struct HeldCallChip: View {
    let call: ManagedCallState
    let isBusy: Bool
    let onSwitch: () -> Void
    let onLeave: () -> Void

    private var title: String {
        if let name = call.displayName, !name.isEmpty { return name }
        return call.roomId
    }

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "pause.circle.fill")
                .foregroundStyle(.secondary)
            Button(action: onSwitch) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(L10n.callStatusOnHold)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.plain)
            .disabled(isBusy)

            Button(action: onLeave) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .disabled(isBusy)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: 220)
        .background(.ultraThinMaterial)
        .clipShape(Capsule())
        .shadow(color: Color.black.opacity(0.2), radius: 6, y: 2)
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

/// Transient banner for a recoverable per-call error (e.g. a failed switch that
/// left the live call intact) — FIX P5-2. Distinct from `ErrorScreen`, which is
/// the whole-app terminal error surface.
private struct CallErrorBannerView: View {
    let banner: CallManager.CallErrorBanner

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(banner.message)
                .font(.subheadline.weight(.medium))
                .lineLimit(2)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .foregroundStyle(Color.red)
        .shadow(color: Color.black.opacity(0.25), radius: 8, y: 3)
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.horizontal, 16)
    }
}

/// Full-screen "calls on hold" surface shown when no call is foreground but live
/// held calls remain (FIX P5-3, Invariant 5: no auto-promote). It keeps the held
/// calls reachable — the user resumes one with a tap — instead of being dropped
/// to Join. Reuses ``HeldCallChip`` so it stays visually consistent with the
/// floating switcher.
private struct HeldCallsHoldingScreen: View {
    let heldCalls: [ManagedCallState]
    let isBusy: Bool
    let onSwitch: (CallId) -> Void
    let onLeave: (CallId) -> Void

    var body: some View {
        VStack(spacing: 20) {
            Spacer(minLength: 0)

            Image(systemName: "pause.circle.fill")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)

            Text(L10n.callsOnHoldTitle)
                .font(.title2.weight(.semibold))

            ScrollView {
                VStack(spacing: 10) {
                    ForEach(heldCalls, id: \.id) { call in
                        HeldCallChip(
                            call: call,
                            isBusy: isBusy,
                            onSwitch: { onSwitch(call.id) },
                            onLeave: { onLeave(call.id) }
                        )
                        .frame(maxWidth: 320)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 4)
            }
            .frame(maxWidth: .infinity)

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }
}
