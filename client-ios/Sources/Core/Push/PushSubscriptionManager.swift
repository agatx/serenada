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
            return cached
        }

        #if canImport(FirebaseMessaging)
        if FirebaseApp.app() != nil, let token = await fetchFirebaseToken() {
            settingsStore.pushEndpoint = token
            return token
        }
        #endif

        return cachedEndpoint()
    }

    func subscribeRoom(roomId: String, host: String) {
        let normalizedRoomId = roomId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedRoomId.isEmpty else { return }

        Task { [weak self] in
            guard let self else { return }
            guard let endpoint = await self.refreshPushEndpoint() else { return }
            guard let encPublicKeyRaw = self.pushKeyStore.getPublicJwk() else { return }
            let encPublicKey = PushRecipientPublicKey(
                kty: encPublicKeyRaw.kty,
                crv: encPublicKeyRaw.crv,
                x: encPublicKeyRaw.x,
                y: encPublicKeyRaw.y
            )

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
            } catch {
                // Keep this fire-and-forget, matching Android behavior.
                _ = error
            }
        }
    }

    #if canImport(FirebaseMessaging)
    private func fetchFirebaseToken() async -> String? {
        await withCheckedContinuation { continuation in
            Messaging.messaging().token { token, _ in
                let clean = token?.trimmingCharacters(in: .whitespacesAndNewlines)
                continuation.resume(returning: (clean?.isEmpty == false) ? clean : nil)
            }
        }
    }
    #endif
}

private extension Locale {
    func languageTagCompatible() -> String {
        if #available(iOS 16.0, *) {
            return self.language.languageCode?.identifier ?? identifier.replacingOccurrences(of: "_", with: "-")
        }
        return identifier.replacingOccurrences(of: "_", with: "-")
    }
}
