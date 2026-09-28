import Foundation

enum AppConstants {
    static let defaultHost = "serenada.app"
    static let ruHost = "ru.serenada.app"
    /// Previous Russia hostname. Still trusted so existing links and saved hosts keep working.
    static let legacyRuHost = "serenada-app.ru"
    static let predefinedHosts = [defaultHost, ruHost]

    static func canonicalHost(_ host: String) -> String {
        if host.compare(legacyRuHost, options: .caseInsensitive) == .orderedSame {
            return ruHost
        }
        return host
    }

    static func isRussiaHost(_ host: String) -> Bool {
        let normalized = host.lowercased()
        return normalized == ruHost || normalized == legacyRuHost
    }
    static let appGroupIdentifier = "group.app.serenada.ios"
    static let broadcastExtensionBundleIdentifier = "app.serenada.ios.broadcast"

    static let languageAuto = "auto"
    static let languageEn = "en"
    static let languageRu = "ru"
    static let languageEs = "es"
    static let languageFr = "fr"

    static let supportedLanguages = [languageAuto, languageEn, languageRu, languageEs, languageFr]
}
