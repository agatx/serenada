import SwiftUI

@main
struct SerenadaiOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var callManager = CallManager()
    @State private var launchDeepLinkURL: URL?

    init() {
        AppLaunchOverrides.applyIfNeeded()
        _launchDeepLinkURL = State(
            initialValue: AppLaunchOverrides.pendingDeepLinkURL() ?? NotificationDeepLinkRouter.consumePendingURL()
        )
    }

    var body: some Scene {
        WindowGroup {
            RootView(callManager: callManager)
                .environment(\.locale, callManager.locale)
                .task {
                    guard let url = launchDeepLinkURL else { return }
                    callManager.handleDeepLink(url)
                    launchDeepLinkURL = nil
                }
                .onOpenURL { url in
                    callManager.handleDeepLink(url)
                }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    guard let url = activity.webpageURL else { return }
                    callManager.handleDeepLink(url)
                }
                .onReceive(NotificationCenter.default.publisher(for: .serenadaNotificationDeepLinkDidChange)) { _ in
                    guard let url = NotificationDeepLinkRouter.consumePendingURL() else { return }
                    callManager.handleDeepLink(url)
                }
        }
    }
}
