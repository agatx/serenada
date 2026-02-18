import Foundation

enum L10n {
    private static let languageKey = "language"

    static func text(_ key: String) -> String {
        localizedBundle().localizedString(forKey: key, value: key, table: nil)
    }

    private static func localizedBundle() -> Bundle {
        guard let language = selectedLanguageCode() else { return .main }
        guard let path = Bundle.main.path(forResource: language, ofType: "lproj"),
              let bundle = Bundle(path: path) else {
            return .main
        }
        return bundle
    }

    private static func selectedLanguageCode() -> String? {
        let raw = UserDefaults.standard.string(forKey: languageKey)?
            .lowercased()
            .trimmingCharacters(in: .whitespacesAndNewlines)

        guard let raw, !raw.isEmpty else { return nil }
        guard raw != AppConstants.languageAuto else { return nil }
        guard AppConstants.supportedLanguages.contains(raw) else { return nil }
        return raw
    }

    static var appName: String { text("app_name") }
    static var joinSubtitle: String { text("join_subtitle") }
    static var joinEnterCodeOrLink: String { text("join_enter_code_or_link") }
    static var joinStartCall: String { text("join_start_call") }
    static var joinSettings: String { text("join_settings") }
    static var joinWithCodeTitle: String { text("join_with_code_title") }
    static var joinWithCodeAction: String { text("join_with_code_action") }
    static var joinWithCodeHint: String { text("join_with_code_hint") }
    static var joinWithCodePlaceholder: String { text("join_with_code_placeholder") }

    static var recentCallsTitle: String { text("recent_calls_title") }
    static var recentCallsAt: String { text("recent_calls_at") }
    static var recentCallsRemove: String { text("recent_calls_remove") }
    static var noRecentCalls: String { text("recent_calls_empty") }

    static var settingsTitle: String { text("settings_title") }
    static var settingsSave: String { text("settings_save") }
    static var settingsCancel: String { text("settings_cancel") }
    static var settingsServerHost: String { text("settings_server_host") }
    static var settingsHostGlobal: String { text("settings_host_global") }
    static var settingsHostRussia: String { text("settings_host_russia") }
    static var settingsCustom: String { text("settings_custom") }
    static var settingsLanguage: String { text("settings_language") }
    static var settingsLanguageHelp: String { text("settings_language_help") }
    static var settingsLanguageAuto: String { text("settings_language_auto") }
    static var settingsLanguageEnglish: String { text("settings_language_english") }
    static var settingsLanguageRussian: String { text("settings_language_russian") }
    static var settingsLanguageSpanish: String { text("settings_language_spanish") }
    static var settingsLanguageFrench: String { text("settings_language_french") }
    static var settingsCallDefaults: String { text("settings_call_defaults") }
    static var settingsCameraEnabled: String { text("camera_enabled") }
    static var settingsCameraEnabledInfo: String { text("camera_enabled_info") }
    static var settingsMicrophoneEnabled: String { text("microphone_enabled") }
    static var settingsMicrophoneEnabledInfo: String { text("microphone_enabled_info") }
    static var settingsHdVideoExperimental: String { text("settings_hd_video_experimental") }
    static var settingsHdVideoExperimentalInfo: String { text("settings_hd_video_experimental_info") }
    static var settingsErrorInvalidServerHost: String { text("settings_error_invalid_server_host") }

    static var errorSomethingWentWrong: String { text("error_something_went_wrong") }
    static var errorEnterRoomOrId: String { text("error_enter_room_or_id") }
    static var errorFailedCreateRoom: String { text("error_failed_create_room") }
    static var errorInvalidRoomId: String { text("error_invalid_room_id") }
    static var errorUnknown: String { text("error_unknown") }

    static var callStatusConnected: String { text("call_status_connected") }
    static var callStatusConnecting: String { text("call_status_connecting") }
    static var callStatusDisconnected: String { text("call_status_disconnected") }
    static var callStatusConnectionFailed: String { text("call_status_connection_failed") }
    static var callStatusCallEnded: String { text("call_status_call_ended") }
    static var callStatusCreatingRoom: String { text("call_status_creating_room") }
    static var callStatusJoiningRoom: String { text("call_status_joining_room") }
    static var callStatusWaitingForJoin: String { text("call_status_waiting_for_join") }
    static var callStatusInCall: String { text("call_status_in_call") }
    static var callStatusLeftRoom: String { text("call_status_left_room") }
    static var callStatusRoomEnded: String { text("call_status_room_ended") }

    static var callLocalCameraOff: String { text("call_local_camera_off") }
    static var callCameraOff: String { text("call_camera_off") }
    static var callWaitingShort: String { text("call_waiting_short") }
    static var callVideoOff: String { text("call_video_off") }
    static var callReconnecting: String { text("call_reconnecting") }
    static var callWaitingOverlay: String { text("call_waiting_overlay") }
    static var callQrCode: String { text("call_qr_code") }
    static var callShareInvitation: String { text("call_share_invitation") }

    static var commonBack: String { text("common_back") }
}
