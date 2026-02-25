import SwiftUI

struct SettingsScreen: View {
    @Binding var host: String
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
    let onSave: () -> Void
    let onOpenDiagnostics: () -> Void
    let onCancel: () -> Void

    private let languageOptions: [(String, String)] = [
        (AppConstants.languageAuto, L10n.settingsLanguageAuto),
        (AppConstants.languageEn, L10n.settingsLanguageEnglish),
        (AppConstants.languageRu, L10n.settingsLanguageRussian),
        (AppConstants.languageEs, L10n.settingsLanguageSpanish),
        (AppConstants.languageFr, L10n.settingsLanguageFrench)
    ]

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(L10n.settingsCancel, action: onCancel)
                Spacer()
                Text(L10n.settingsTitle)
                    .font(.headline)
                Spacer()
                Button(L10n.settingsSave, action: onSave)
                    .disabled(isSaving)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)

            Form {
                Section(L10n.settingsServerHost) {
                    HostChoiceRow(selected: host == AppConstants.defaultHost, title: String(format: L10n.settingsHostGlobal, AppConstants.defaultHost)) {
                        host = AppConstants.defaultHost
                    }
                    HostChoiceRow(selected: host == AppConstants.ruHost, title: String(format: L10n.settingsHostRussia, AppConstants.ruHost)) {
                        host = AppConstants.ruHost
                    }
                    HostChoiceRow(selected: host != AppConstants.defaultHost && host != AppConstants.ruHost, title: L10n.settingsCustom) {}

                    TextField(L10n.settingsServerHost, text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

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

                Section {
                    Text(String(format: L10n.settingsAppVersion, appVersion))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section(L10n.settingsDiagnosticsTitle) {
                    Button(action: onOpenDiagnostics) {
                        Label(L10n.settingsDiagnosticsAction, systemImage: "stethoscope")
                    }
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
    }
}

private struct HostChoiceRow: View {
    let selected: Bool
    let title: String
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                Text(title)
            }
        }
        .buttonStyle(.plain)
    }
}
