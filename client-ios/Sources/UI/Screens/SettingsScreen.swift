import SwiftUI

struct SettingsScreen: View {
    @Binding var host: String
    @Binding var showDiagnostics: Bool
    let selectedLanguage: String
    let isDefaultCameraEnabled: Bool
    let isDefaultMicrophoneEnabled: Bool
    let isHdVideoExperimentalEnabled: Bool
    let areSavedRoomsShownFirst: Bool
    let areRoomInviteNotificationsEnabled: Bool
    let appVersion: String
    let hostError: String?
    let isSaving: Bool
    let onLanguageSelect: (String) -> Void
    let onDefaultCameraChange: (Bool) -> Void
    let onDefaultMicrophoneChange: (Bool) -> Void
    let onHdVideoExperimentalChange: (Bool) -> Void
    let onSavedRoomsShownFirstChange: (Bool) -> Void
    let onRoomInviteNotificationsChange: (Bool) -> Void

    private let languageOptions: [(String, String)] = [
        (AppConstants.languageAuto, L10n.settingsLanguageAuto),
        (AppConstants.languageEn, L10n.settingsLanguageEnglish),
        (AppConstants.languageRu, L10n.settingsLanguageRussian),
        (AppConstants.languageEs, L10n.settingsLanguageSpanish),
        (AppConstants.languageFr, L10n.settingsLanguageFrench)
    ]

    var body: some View {
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
