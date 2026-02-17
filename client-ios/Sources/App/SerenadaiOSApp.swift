import SwiftUI

@main
struct SerenadaiOSApp: App {
    @StateObject private var callManager = CallManager()

    var body: some Scene {
        WindowGroup {
            RootView(callManager: callManager)
                .environment(\.locale, callManager.locale)
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
