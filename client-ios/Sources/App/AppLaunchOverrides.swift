import Foundation

enum AppLaunchOverrides {
    private enum Key {
        static let uiTestingFlag = "-ui-testing"
        static let clearStateFlag = "-ui-testing-clear-state"
        static let deepLinkEnv = "SERENADA_UI_TEST_DEEPLINK"
    }

    static func applyIfNeeded() {
        let arguments = ProcessInfo.processInfo.arguments
        guard arguments.contains(Key.uiTestingFlag) else { return }

        if arguments.contains(Key.clearStateFlag) {
            clearPersistedState()
        }
    }

    static func pendingDeepLinkURL() -> URL? {
        let arguments = ProcessInfo.processInfo.arguments
        guard arguments.contains(Key.uiTestingFlag) else { return nil }
        guard let rawValue = ProcessInfo.processInfo.environment[Key.deepLinkEnv],
              let url = URL(string: rawValue) else {
            return nil
        }
        return url
    }

    private static func clearPersistedState() {
        if let bundleIdentifier = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: bundleIdentifier)
        }
        UserDefaults.standard.synchronize()

        if let sharedDefaults = UserDefaults(suiteName: AppConstants.appGroupIdentifier) {
            sharedDefaults.removePersistentDomain(forName: AppConstants.appGroupIdentifier)
            sharedDefaults.synchronize()
        }
    }
}
