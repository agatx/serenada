import Foundation

internal enum SerenadaDefaults {
    public static let defaultHost = "serenada.app"
    public static let ruHost = "ru.serenada.app"
    /// Previous Russia hostname. Still trusted so existing links and saved hosts keep working.
    public static let legacyRuHost = "serenada-app.ru"
    public static let predefinedHosts = [defaultHost, ruHost]

    public static func canonicalHost(_ host: String) -> String {
        if host.compare(legacyRuHost, options: .caseInsensitive) == .orderedSame {
            return ruHost
        }
        return host
    }

    public static func isRussiaHost(_ host: String) -> Bool {
        let normalized = host.lowercased()
        return normalized == ruHost || normalized == legacyRuHost
    }
}
