import Foundation
import UIKit
import UserNotifications
#if canImport(FirebaseCore)
import FirebaseCore
#endif
#if canImport(FirebaseMessaging)
import FirebaseMessaging
#endif

extension Notification.Name {
    static let serenadaPushEndpointDidChange = Notification.Name("SerenadaPushEndpointDidChange")
    static let serenadaNotificationDeepLinkDidChange = Notification.Name("SerenadaNotificationDeepLinkDidChange")
}

enum PushEndpointNotification {
    static let endpointUserInfoKey = "endpoint"
}

enum NotificationDeepLinkRouter {
    static let urlUserInfoKey = "url"

    private static let lock = NSLock()
    private static var pendingURL: URL?

    static func queue(_ url: URL) {
        lock.lock()
        pendingURL = url
        lock.unlock()

        NotificationCenter.default.post(
            name: .serenadaNotificationDeepLinkDidChange,
            object: nil,
            userInfo: [urlUserInfoKey: url]
        )
    }

    static func consumePendingURL() -> URL? {
        lock.lock()
        defer { lock.unlock() }
        let url = pendingURL
        pendingURL = nil
        return url
    }
}

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
        let apnsHexToken = deviceToken.hexEncodedString()

        #if canImport(FirebaseMessaging)
        if FirebaseApp.app() != nil {
            Messaging.messaging().apnsToken = deviceToken
            logPush("Registered APNs device token; requesting FCM token")
            Messaging.messaging().token { [weak self] token, _ in
                guard let self else { return }
                let clean = token?.trimmingCharacters(in: .whitespacesAndNewlines)
                if let clean, !clean.isEmpty {
                    self.updatePushEndpoint(clean, source: "apns-registration")
                } else {
                    self.logPush("FCM token not yet available after APNs registration")
                }
            }
            return
        }
        #endif

        logPush("Firebase Messaging unavailable; using APNs token fallback endpoint")
        updatePushEndpoint(apnsHexToken, source: "apns-fallback")
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        logPush("Remote notification registration failed: \(error.localizedDescription)")
    }

    private func configureNotifications(application: UIApplication) {
        UNUserNotificationCenter.current().delegate = self

        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { [weak self] granted, error in
            if let error {
                self?.logPush("Notification authorization request failed: \(error.localizedDescription)")
            } else {
                self?.logPush("Notification authorization granted=\(granted)")
            }
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
            logPush("GoogleService-Info.plist not found; Firebase Messaging unavailable")
            return
        }
        FirebaseApp.configure(options: options)
        #if canImport(FirebaseMessaging)
        Messaging.messaging().delegate = self
        #endif
        #endif
    }

    private func updatePushEndpoint(_ endpoint: String, source: String) {
        let clean = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        settingsStore.pushEndpoint = clean
        logPush("Cached push endpoint from \(source)")
        NotificationCenter.default.post(
            name: .serenadaPushEndpointDidChange,
            object: nil,
            userInfo: [PushEndpointNotification.endpointUserInfoKey: clean]
        )
    }

    private func logPush(_ message: String) {
        PushDiagnostics.append(message)
    }
}

#if canImport(FirebaseMessaging)
extension AppDelegate: MessagingDelegate {
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        let clean = fcmToken?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let clean, !clean.isEmpty else { return }
        updatePushEndpoint(clean, source: "messaging-delegate")
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
        NotificationDeepLinkRouter.queue(url)
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
