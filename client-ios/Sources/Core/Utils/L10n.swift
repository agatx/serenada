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
        let sharedDefaults = UserDefaults(suiteName: AppConstants.appGroupIdentifier)
        let raw = (sharedDefaults?.string(forKey: languageKey) ?? UserDefaults.standard.string(forKey: languageKey))?
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
    static var savedRoomsTitle: String { text("saved_rooms_title") }
    static var savedRoomsTitleEmpty: String { text("saved_rooms_title_empty") }
    static var savedRoomsCreate: String { text("saved_rooms_create") }
    static var savedRoomsSave: String { text("saved_rooms_save") }
    static var savedRoomsRename: String { text("saved_rooms_rename") }
    static var savedRoomsRemove: String { text("saved_rooms_remove") }
    static var savedRoomsNameLabel: String { text("saved_rooms_name_label") }
    static var savedRoomsNamePlaceholder: String { text("saved_rooms_name_placeholder") }
    static var savedRoomsDialogTitleNew: String { text("saved_rooms_dialog_title_new") }
    static var savedRoomsDialogTitleRename: String { text("saved_rooms_dialog_title_rename") }
    static var savedRoomsDialogTitleCreate: String { text("saved_rooms_dialog_title_create") }
    static var savedRoomsCreateAction: String { text("saved_rooms_create_action") }
    static var savedRoomsShareLinkChooser: String { text("settings_saved_rooms_share_link_chooser") }
    static var savedRoomsLastJoined: String { text("saved_rooms_last_joined") }
    static var savedRoomsNeverJoined: String { text("saved_rooms_never_joined") }

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
    static var settingsSavedRoomsTitle: String { text("settings_saved_rooms_title") }
    static var settingsSavedRoomsHelp: String { text("settings_saved_rooms_help") }
    static var settingsSavedRoomsShowFirst: String { text("settings_saved_rooms_show_first") }
    static var settingsSavedRoomsShowFirstInfo: String { text("settings_saved_rooms_show_first_info") }
    static var settingsInvitesTitle: String { text("settings_invites_title") }
    static var settingsInviteNotifications: String { text("settings_invite_notifications") }
    static var settingsInviteNotificationsInfo: String { text("settings_invite_notifications_info") }
    static var settingsDiagnosticsTitle: String { text("settings_diagnostics_title") }
    static var settingsDiagnosticsAction: String { text("settings_diagnostics_action") }
    static var settingsAppVersion: String { text("settings_app_version") }
    static var settingsErrorInvalidServerHost: String { text("settings_error_invalid_server_host") }

    static var errorSomethingWentWrong: String { text("error_something_went_wrong") }
    static var errorEnterRoomOrId: String { text("error_enter_room_or_id") }
    static var errorFailedCreateRoom: String { text("error_failed_create_room") }
    static var errorInvalidRoomId: String { text("error_invalid_room_id") }
    static var errorInvalidSavedRoomName: String { text("error_invalid_saved_room_name") }
    static var errorFailedCreateSavedRoomLink: String { text("error_failed_create_saved_room_link") }
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
    static var callInviteToRoom: String { text("call_invite_to_room") }
    static var callInviteSent: String { text("call_invite_sent") }
    static var callInviteFailed: String { text("call_invite_failed") }

    static var diagnosticsTitle: String { text("diagnostics_title") }
    static var diagnosticsPermissionsTitle: String { text("diagnostics_permissions_title") }
    static var diagnosticsPermissionCamera: String { text("diagnostics_permission_camera") }
    static var diagnosticsPermissionMicrophone: String { text("diagnostics_permission_microphone") }
    static var diagnosticsPermissionNotifications: String { text("diagnostics_permission_notifications") }
    static var diagnosticsPermissionsRequest: String { text("diagnostics_permissions_request") }
    static var diagnosticsRefresh: String { text("diagnostics_refresh") }
    static var diagnosticsMediaTitle: String { text("diagnostics_media_title") }
    static var diagnosticsMediaAnyCamera: String { text("diagnostics_media_any_camera") }
    static var diagnosticsMediaFrontCamera: String { text("diagnostics_media_front_camera") }
    static var diagnosticsMediaBackCamera: String { text("diagnostics_media_back_camera") }
    static var diagnosticsMediaComposite: String { text("diagnostics_media_composite") }
    static var diagnosticsMediaMicHardware: String { text("diagnostics_media_mic_hardware") }
    static var diagnosticsMediaSampleRate: String { text("diagnostics_media_sample_rate") }
    static var diagnosticsMediaBuffer: String { text("diagnostics_media_buffer") }
    static var diagnosticsRefreshMedia: String { text("diagnostics_refresh_media") }
    static var diagnosticsConnectivityTitle: String { text("diagnostics_connectivity_title") }
    static var diagnosticsConnectivityHost: String { text("diagnostics_connectivity_host") }
    static var diagnosticsConnectivityRoomApi: String { text("diagnostics_connectivity_room_api") }
    static var diagnosticsConnectivityWebSocket: String { text("diagnostics_connectivity_websocket") }
    static var diagnosticsConnectivitySse: String { text("diagnostics_connectivity_sse") }
    static var diagnosticsConnectivityDiagnosticToken: String { text("diagnostics_connectivity_diagnostic_token") }
    static var diagnosticsConnectivityTurnCredentials: String { text("diagnostics_connectivity_turn_credentials") }
    static var diagnosticsRunConnectivity: String { text("diagnostics_run_connectivity") }
    static var diagnosticsRunning: String { text("diagnostics_running") }
    static var diagnosticsCheckPassed: String { text("diagnostics_check_passed") }
    static var diagnosticsIceTitle: String { text("diagnostics_ice_title") }
    static var diagnosticsIceStun: String { text("diagnostics_ice_stun") }
    static var diagnosticsIceTurn: String { text("diagnostics_ice_turn") }
    static var diagnosticsRunIceFull: String { text("diagnostics_run_ice_full") }
    static var diagnosticsRunIceTurnsOnly: String { text("diagnostics_run_ice_turns_only") }
    static var diagnosticsIceNoServers: String { text("diagnostics_ice_no_servers") }
    static var diagnosticsLogsTitle: String { text("diagnostics_logs_title") }
    static var diagnosticsLogsEmpty: String { text("diagnostics_logs_empty") }
    static var diagnosticsStatusAvailable: String { text("diagnostics_status_available") }
    static var diagnosticsStatusMissing: String { text("diagnostics_status_missing") }

    static var callA11yMuteOn: String { text("call_a11y_mute_on") }
    static var callA11yMuteOff: String { text("call_a11y_mute_off") }
    static var callA11yVideoOn: String { text("call_a11y_video_on") }
    static var callA11yVideoOff: String { text("call_a11y_video_off") }
    static var callA11yFlipCamera: String { text("call_a11y_flip_camera") }
    static var callA11yScreenShareOn: String { text("call_a11y_screen_share_on") }
    static var callA11yScreenShareOff: String { text("call_a11y_screen_share_off") }
    static var callA11yEndCall: String { text("call_a11y_end_call") }
    static var callA11yFlashlightOn: String { text("call_a11y_flashlight_on") }
    static var callA11yFlashlightOff: String { text("call_a11y_flashlight_off") }
    static var callA11yShareInvite: String { text("call_a11y_share_invite") }
    static var callA11yVideoFit: String { text("call_a11y_video_fit") }
    static var callA11yVideoFill: String { text("call_a11y_video_fill") }

    static var commonBack: String { text("common_back") }
}
