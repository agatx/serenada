import Foundation
import UIKit
import UserNotifications
#if canImport(FirebaseCore)
import FirebaseCore
#endif
#if canImport(FirebaseMessaging)
import FirebaseMessaging
#endif

final class AppDelegate: NSObject, UIApplicationDelegate {
    private let settingsStore = SettingsStore()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        configureFirebaseIfPossible()
        configureNotifications(application: application)
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        #if canImport(FirebaseMessaging)
        if FirebaseApp.app() != nil {
            Messaging.messaging().apnsToken = deviceToken
            Messaging.messaging().token { [weak self] token, _ in
                guard let self else { return }
                let clean = token?.trimmingCharacters(in: .whitespacesAndNewlines)
                if let clean, !clean.isEmpty {
                    self.settingsStore.pushEndpoint = clean
                } else {
                    self.settingsStore.pushEndpoint = deviceToken.hexEncodedString()
                }
            }
            return
        }
        #endif

        settingsStore.pushEndpoint = deviceToken.hexEncodedString()
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        _ = error
    }

    private func configureNotifications(application: UIApplication) {
        UNUserNotificationCenter.current().delegate = self

        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in
            DispatchQueue.main.async {
                application.registerForRemoteNotifications()
            }
        }
    }

    private func configureFirebaseIfPossible() {
        #if canImport(FirebaseCore)
        if FirebaseApp.app() != nil {
            #if canImport(FirebaseMessaging)
            Messaging.messaging().delegate = self
            #endif
            return
        }
        guard let path = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist"),
              let options = FirebaseOptions(contentsOfFile: path) else {
            return
        }
        FirebaseApp.configure(options: options)
        #if canImport(FirebaseMessaging)
        Messaging.messaging().delegate = self
        #endif
        #endif
    }
}

#if canImport(FirebaseMessaging)
extension AppDelegate: MessagingDelegate {
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        let clean = fcmToken?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let clean, !clean.isEmpty else { return }
        settingsStore.pushEndpoint = clean
    }
}
#endif

extension AppDelegate: UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .badge])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        defer { completionHandler() }

        let userInfo = response.notification.request.content.userInfo
        guard let url = Self.resolveDeepLinkURL(from: userInfo) else { return }
        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }

    private static func resolveDeepLinkURL(from userInfo: [AnyHashable: Any]) -> URL? {
        let rawPath = (userInfo["url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !rawPath.isEmpty else { return nil }

        if let absolute = URL(string: rawPath), absolute.scheme != nil {
            return absolute
        }

        let host = (userInfo["host"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let resolvedHost = host?.isEmpty == false ? host! : AppConstants.defaultHost
        let normalizedPath = rawPath.hasPrefix("/") ? rawPath : "/\(rawPath)"
        return URL(string: "https://\(resolvedHost)\(normalizedPath)")
    }
}

private extension Data {
    func hexEncodedString() -> String {
        map { String(format: "%02x", $0) }.joined()
    }
}
