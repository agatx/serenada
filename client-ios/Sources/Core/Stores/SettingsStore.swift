import Foundation

final class SettingsStore {
    private enum Key {
        static let host = "host"
        static let reconnectCid = "reconnect_cid"
        static let language = "language"
        static let defaultCameraEnabled = "default_camera_enabled"
        static let defaultMicEnabled = "default_mic_enabled"
        static let hdVideoExperimentalEnabled = "hd_video_experimental_enabled"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var host: String {
        get {
            let value = defaults.string(forKey: Key.host)?.trimmingCharacters(in: .whitespacesAndNewlines)
            return value?.isEmpty == false ? value! : AppConstants.defaultHost
        }
        set {
            let normalized = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            if normalized.isEmpty { return }
            defaults.set(normalized, forKey: Key.host)
        }
    }

    var reconnectCid: String? {
        get {
            let value = defaults.string(forKey: Key.reconnectCid)?.trimmingCharacters(in: .whitespacesAndNewlines)
            return value?.isEmpty == false ? value : nil
        }
        set {
            if let value = newValue?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty {
                defaults.set(value, forKey: Key.reconnectCid)
            } else {
                defaults.removeObject(forKey: Key.reconnectCid)
            }
        }
    }

    var language: String {
        get {
            normalizeLanguage(defaults.string(forKey: Key.language))
        }
        set {
            defaults.set(normalizeLanguage(newValue), forKey: Key.language)
        }
    }

    var isDefaultCameraEnabled: Bool {
        get {
            if defaults.object(forKey: Key.defaultCameraEnabled) == nil { return true }
            return defaults.bool(forKey: Key.defaultCameraEnabled)
        }
        set {
            defaults.set(newValue, forKey: Key.defaultCameraEnabled)
        }
    }

    var isDefaultMicrophoneEnabled: Bool {
        get {
            if defaults.object(forKey: Key.defaultMicEnabled) == nil { return true }
            return defaults.bool(forKey: Key.defaultMicEnabled)
        }
        set {
            defaults.set(newValue, forKey: Key.defaultMicEnabled)
        }
    }

    var isHdVideoExperimentalEnabled: Bool {
        get {
            defaults.bool(forKey: Key.hdVideoExperimentalEnabled)
        }
        set {
            defaults.set(newValue, forKey: Key.hdVideoExperimentalEnabled)
        }
    }

    func normalizeLanguage(_ raw: String?) -> String {
        guard let raw else { return AppConstants.languageAuto }
        let value = raw.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        if AppConstants.supportedLanguages.contains(value) {
            return value
        }
        return AppConstants.languageAuto
    }
}
