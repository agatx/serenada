import Foundation
#if canImport(FirebaseCore)
import FirebaseCore
#endif
#if canImport(FirebaseMessaging)
import FirebaseMessaging
#endif

@MainActor
final class PushSubscriptionManager {
    static let pushTransport = "fcm"

    private let apiClient: APIClient
    private let settingsStore: SettingsStore
    private let pushKeyStore: PushKeyStore

    init(
        apiClient: APIClient,
        settingsStore: SettingsStore,
        pushKeyStore: PushKeyStore = PushKeyStore()
    ) {
        self.apiClient = apiClient
        self.settingsStore = settingsStore
        self.pushKeyStore = pushKeyStore
    }

    func cachedEndpoint() -> String? {
        let cached = settingsStore.pushEndpoint?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let cached, !cached.isEmpty else { return nil }
        return cached
    }

    func updateCachedEndpoint(_ endpoint: String?) {
        settingsStore.pushEndpoint = endpoint
    }

    func refreshPushEndpoint() async -> String? {
        if let cached = cachedEndpoint() {
            log("Using cached push endpoint for room subscription")
            return cached
        }

        #if canImport(FirebaseMessaging)
        if FirebaseApp.app() != nil, let token = await fetchFirebaseToken() {
            settingsStore.pushEndpoint = token
            log("Using Firebase Messaging token for push subscriptions")
            return token
        }
        #endif

        log("Push endpoint unavailable for room subscription")
        return nil
    }

    func subscribeRoom(roomId: String, host: String) {
        let normalizedRoomId = roomId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedRoomId.isEmpty else { return }

        Task { [weak self] in
            guard let self else { return }
            guard let endpoint = await self.refreshPushEndpoint() else {
                self.log("Skipping push subscription for room \(normalizedRoomId): missing push endpoint")
                return
            }
            guard let encPublicKeyRaw = self.pushKeyStore.getPublicJwk() else {
                self.log("Skipping push subscription for room \(normalizedRoomId): missing encryption public key")
                return
            }
            let encPublicKey = PushRecipientPublicKey(
                kty: encPublicKeyRaw.kty,
                crv: encPublicKeyRaw.crv,
                x: encPublicKeyRaw.x,
                y: encPublicKeyRaw.y
            )

            self.log("Attempting push subscribe for room \(normalizedRoomId)")
            let request = PushSubscribeRequest(
                transport: Self.pushTransport,
                endpoint: endpoint,
                locale: Locale.current.languageTagCompatible(),
                encPublicKey: encPublicKey,
                auth: nil,
                p256dh: nil
            )

            do {
                try await self.apiClient.subscribePush(host: host, roomId: normalizedRoomId, request: request)
                self.log("Subscribed room \(normalizedRoomId) for push")
            } catch {
                self.log("Push subscribe failed for room \(normalizedRoomId): \(error.localizedDescription)")
            }
        }
    }

    #if canImport(FirebaseMessaging)
    private func fetchFirebaseToken() async -> String? {
        await withCheckedContinuation { continuation in
            Messaging.messaging().token { token, error in
                if let error {
                    PushDiagnostics.append("Failed to fetch Firebase Messaging token: \(error.localizedDescription)")
                }
                let clean = token?.trimmingCharacters(in: .whitespacesAndNewlines)
                continuation.resume(returning: (clean?.isEmpty == false) ? clean : nil)
            }
        }
    }
    #endif

    private func log(_ message: String) {
        PushDiagnostics.append(message)
    }
}

private extension Locale {
    func languageTagCompatible() -> String {
        if #available(iOS 16.0, *) {
            return self.language.languageCode?.identifier ?? identifier.replacingOccurrences(of: "_", with: "-")
        }
        return identifier.replacingOccurrences(of: "_", with: "-")
    }
}
