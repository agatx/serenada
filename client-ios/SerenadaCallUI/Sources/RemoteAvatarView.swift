import SwiftUI
import UIKit

/// Lazily resolves and caches avatars for the lifetime of the call UI. Each
/// `peerId` is sent through `AvatarProvider.resolve` at most once per call,
/// with the resulting `UIImage` (or a sentinel for "no avatar") cached for
/// the rest of the call.
final class AvatarCache: ObservableObject {
    private enum Entry {
        case pending
        case resolved(UIImage?)
    }

    private let provider: AvatarProvider?
    @MainActor
    @Published private var entries: [String: Entry] = [:]

    init(provider: AvatarProvider?) {
        self.provider = provider
    }

    @MainActor
    func image(for peerId: String) -> UIImage? {
        switch entries[peerId] {
        case .resolved(let image):
            return image
        case .pending:
            return nil
        case .none:
            return nil
        }
    }

    @MainActor
    func load(peerId: String) {
        guard entries[peerId] == nil else { return }
        guard let provider else {
            entries[peerId] = .resolved(nil)
            return
        }

        entries[peerId] = .pending
        let task = Task.detached(priority: .userInitiated) { [provider, peerId] in
            let source = await provider.resolve(peerId: peerId)
            return await Self.materialize(source)
        }
        Task { @MainActor [weak self] in
            let image = await task.value
            self?.entries[peerId] = .resolved(image)
        }
    }

    private static func materialize(_ source: AvatarSource?) async -> UIImage? {
        guard let source else { return nil }
        switch source {
        case .image(let image):
            return image
        case .data(let data):
            return UIImage(data: data)
        case .url(let url):
            do {
                let (data, _) = try await URLSession.shared.data(from: url)
                return UIImage(data: data)
            } catch {
                return nil
            }
        }
    }
}

private struct AvatarCacheKey: EnvironmentKey {
    static let defaultValue: AvatarCache? = nil
}

extension EnvironmentValues {
    var avatarCache: AvatarCache? {
        get { self[AvatarCacheKey.self] }
        set { self[AvatarCacheKey.self] = newValue }
    }
}

struct RemoteAvatarView: View {
    let peerId: String?
    let displayName: String?
    let size: CGFloat

    @Environment(\.avatarCache) private var cache

    var body: some View {
        if let cache {
            // `@Environment(\.avatarCache)` reads the cache reference but does not subscribe
            // to its `objectWillChange`, so when `entries` resolves the surrounding view
            // tree may not re-render. The inner `RemoteAvatarBodyView` holds the cache as
            // an `@ObservedObject`, which gives the avatar a real subscription and ensures
            // the photo appears as soon as the resolver completes.
            RemoteAvatarBodyView(cache: cache, peerId: peerId, displayName: displayName, size: size)
        } else {
            InitialsAvatarView(displayName: displayName, size: size)
        }
    }
}

private struct RemoteAvatarBodyView: View {
    @ObservedObject var cache: AvatarCache
    let peerId: String?
    let displayName: String?
    let size: CGFloat

    var body: some View {
        let image = peerId.flatMap { cache.image(for: $0) }
        ZStack {
            Circle().fill(Color(white: 0.16))
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            } else {
                InitialsLabel(displayName: displayName, size: size)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
        .task(id: peerId) {
            guard let peerId else { return }
            cache.load(peerId: peerId)
        }
    }
}

private struct InitialsAvatarView: View {
    let displayName: String?
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Color(white: 0.16))
            InitialsLabel(displayName: displayName, size: size)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

private struct InitialsLabel: View {
    let displayName: String?
    let size: CGFloat

    var body: some View {
        let initials = initialsFor(displayName: displayName)
        Text(initials.isEmpty ? "•" : initials)
            .font(.system(size: size * 0.4, weight: .semibold))
            .foregroundColor(.white.opacity(0.85))
    }
}

func initialsFor(displayName: String?) -> String {
    guard let name = displayName?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else {
        return ""
    }
    var initials: [String] = []
    for part in name.split(whereSeparator: { $0.isWhitespace }) {
        for ch in part where ch.isLetter || ch.isNumber {
            initials.append(String(ch).uppercased())
            break
        }
    }
    if initials.isEmpty { return "" }
    if initials.count == 1 { return initials[0] }
    return initials[0] + initials[initials.count - 1]
}
