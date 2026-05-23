import SerenadaCallUI
import SwiftUI

struct SettingsScreen: View {
    @Binding var host: String
    @Binding var displayName: String
    @Binding var showDiagnostics: Bool
    @FocusState private var isDisplayNameFocused: Bool
    let selectedLanguage: String
    let isDefaultCameraEnabled: Bool
    let isDefaultMicrophoneEnabled: Bool
    let isHdVideoExperimentalEnabled: Bool
    let callUiVariant: SerenadaCallUiVariant
    let areSavedRoomsShownFirst: Bool
    let areRoomInviteNotificationsEnabled: Bool
    @Binding var showFeedback: Bool
    let appVersion: String
    let hostError: String?
    let isSaving: Bool
    let onLanguageSelect: (String) -> Void
    let onDefaultCameraChange: (Bool) -> Void
    let onDefaultMicrophoneChange: (Bool) -> Void
    let onHdVideoExperimentalChange: (Bool) -> Void
    let onCallUiVariantChange: (SerenadaCallUiVariant) -> Void
    let onSavedRoomsShownFirstChange: (Bool) -> Void
    let onRoomInviteNotificationsChange: (Bool) -> Void
    let onDisplayNameChange: (String) -> Void

    private let languageOptions: [(String, String)] = [
        (AppConstants.languageAuto, L10n.settingsLanguageAuto),
        (AppConstants.languageEn, L10n.settingsLanguageEnglish),
        (AppConstants.languageRu, L10n.settingsLanguageRussian),
        (AppConstants.languageEs, L10n.settingsLanguageSpanish),
        (AppConstants.languageFr, L10n.settingsLanguageFrench)
    ]

    var body: some View {
        ScrollViewReader { proxy in
        Form {
            Section(L10n.settingsServerHost) {
                Picker(L10n.settingsServerHost, selection: Binding(
                    get: { hostPreset },
                    set: { newPreset in
                        switch newPreset {
                        case "global":
                            host = AppConstants.defaultHost
                        case "russia":
                            host = AppConstants.ruHost
                        case "custom":
                            if host == AppConstants.defaultHost || host == AppConstants.ruHost {
                                host = ""
                            }
                        default:
                            break
                        }
                    }
                )) {
                    Text(String(format: L10n.settingsHostGlobal, AppConstants.defaultHost)).tag("global")
                    Text(String(format: L10n.settingsHostRussia, AppConstants.ruHost)).tag("russia")
                    Text(L10n.settingsCustom).tag("custom")
                }
                .pickerStyle(.inline)
                .labelsHidden()

                if hostPreset == "custom" {
                    TextField(L10n.settingsServerHost, text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                if let hostError, !hostError.isEmpty {
                    Text(hostError)
                        .foregroundStyle(.red)
                        .font(.footnote)
                }
            }

            Section(L10n.settingsLanguage) {
                Picker(L10n.settingsLanguage, selection: Binding(
                    get: { selectedLanguage },
                    set: { onLanguageSelect($0) }
                )) {
                    ForEach(languageOptions, id: \.0) { (code, title) in
                        Text(title).tag(code)
                    }
                }
                Text(L10n.settingsLanguageHelp)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section(L10n.settingsDisplayName) {
                TextField(L10n.settingsDisplayNamePlaceholder, text: $displayName)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .focused($isDisplayNameFocused)
                    .id("displayNameField")
                    .onChange(of: displayName) { newValue in
                        let clamped = String(newValue.prefix(40))
                        if clamped != newValue {
                            displayName = clamped
                        }
                        onDisplayNameChange(clamped)
                    }
            }

            Section(L10n.settingsCallScreenStyle) {
                Picker(L10n.settingsCallScreenStyle, selection: Binding(
                    get: { callUiVariant },
                    set: { onCallUiVariantChange($0) }
                )) {
                    Text(L10n.settingsCallScreenStyleStandard).tag(SerenadaCallUiVariant.standard)
                    Text(L10n.settingsCallScreenStyleFrontline).tag(SerenadaCallUiVariant.frontline)
                }
                .pickerStyle(.segmented)
            }

            Section(L10n.settingsCallDefaults) {
                Toggle(isOn: Binding(
                    get: { isDefaultCameraEnabled },
                    set: onDefaultCameraChange
                )) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L10n.settingsCameraEnabled)
                        Text(L10n.settingsCameraEnabledInfo)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Toggle(isOn: Binding(
                    get: { isDefaultMicrophoneEnabled },
                    set: onDefaultMicrophoneChange
                )) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L10n.settingsMicrophoneEnabled)
                        Text(L10n.settingsMicrophoneEnabledInfo)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Toggle(isOn: Binding(
                    get: { isHdVideoExperimentalEnabled },
                    set: onHdVideoExperimentalChange
                )) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L10n.settingsHdVideoExperimental)
                        Text(L10n.settingsHdVideoExperimentalInfo)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section(L10n.settingsSavedRoomsTitle) {
                Toggle(isOn: Binding(
                    get: { areSavedRoomsShownFirst },
                    set: onSavedRoomsShownFirstChange
                )) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L10n.settingsSavedRoomsShowFirst)
                        Text(L10n.settingsSavedRoomsShowFirstInfo)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Text(L10n.settingsSavedRoomsHelp)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section(L10n.settingsInvitesTitle) {
                Toggle(isOn: Binding(
                    get: { areRoomInviteNotificationsEnabled },
                    set: onRoomInviteNotificationsChange
                )) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L10n.settingsInviteNotifications)
                        Text(L10n.settingsInviteNotificationsInfo)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section(L10n.feedbackTitle) {
                Button {
                    showFeedback = true
                } label: {
                    HStack {
                        Label(L10n.feedbackSendAction, systemImage: "envelope")
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                }
            }

            Section(L10n.settingsDiagnosticsTitle) {
                Button {
                    showDiagnostics = true
                } label: {
                    HStack {
                        Label(L10n.settingsDiagnosticsAction, systemImage: "stethoscope")
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                }

                Text(String(format: L10n.settingsAppVersion, appVersion))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .simultaneousGesture(
            TapGesture().onEnded {
                isDisplayNameFocused = false
            }
        )
        .onChange(of: isDisplayNameFocused) { focused in
            if focused {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    withAnimation {
                        proxy.scrollTo("displayNameField", anchor: .center)
                    }
                }
            }
        }
        } // ScrollViewReader
        .overlay {
            if isSaving {
                ZStack {
                    Color.primary.opacity(0.1).ignoresSafeArea()
                    ProgressView()
                }
            }
        }
    }

    private var hostPreset: String {
        if host == AppConstants.defaultHost { return "global" }
        if host == AppConstants.ruHost { return "russia" }
        return "custom"
    }
}
