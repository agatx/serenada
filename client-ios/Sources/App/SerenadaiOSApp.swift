import SwiftUI

@main
struct SerenadaiOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var callManager = CallManager()
    @State private var launchDeepLinkURL: URL?

    init() {
        AppLaunchOverrides.applyIfNeeded()
        _launchDeepLinkURL = State(initialValue: AppLaunchOverrides.pendingDeepLinkURL())
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
        }
    }
}
