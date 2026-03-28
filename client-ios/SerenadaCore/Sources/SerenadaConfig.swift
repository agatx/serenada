import Foundation

/// Signaling transport type.
public enum SerenadaTransport: String, Equatable, Sendable {
    case ws
    case sse
}

/// SDK configuration.
///
/// `SerenadaConfig` is marked `@unchecked Sendable` because a custom
/// `SignalingProvider` is class-bound and cannot be proven sendable by the
/// compiler. Callers are responsible for keeping provider implementations
/// thread-safe according to the provider contract.
public struct SerenadaConfig: Equatable, @unchecked Sendable {
    /// Server host or origin (e.g. "serenada.app" or "localhost:8080").
    public let serverHost: String?
    /// Custom signaling provider. Provide exactly one of `serverHost` or `signalingProvider`.
    public let signalingProvider: SignalingProvider?
    /// Whether audio is enabled when joining a call. Defaults to `true`.
    public var defaultAudioEnabled: Bool
    /// Whether video is enabled when joining a call. Defaults to `true`.
    public var defaultVideoEnabled: Bool
    /// Preferred signaling transports in priority order. Defaults to `[.ws, .sse]`.
    public var transports: [SerenadaTransport]

    public init(
        serverHost: String? = nil,
        signalingProvider: SignalingProvider? = nil,
        defaultAudioEnabled: Bool = true,
        defaultVideoEnabled: Bool = true,
        transports: [SerenadaTransport] = [.ws, .sse]
    ) {
        self.serverHost = serverHost
        self.signalingProvider = signalingProvider
        self.defaultAudioEnabled = defaultAudioEnabled
        self.defaultVideoEnabled = defaultVideoEnabled
        self.transports = transports
    }

    public static func == (lhs: SerenadaConfig, rhs: SerenadaConfig) -> Bool {
        lhs.serverHost == rhs.serverHost
            && lhs.defaultAudioEnabled == rhs.defaultAudioEnabled
            && lhs.defaultVideoEnabled == rhs.defaultVideoEnabled
            && lhs.transports == rhs.transports
            && haveSameProvider(lhs.signalingProvider, rhs.signalingProvider)
    }
}

internal struct ResolvedSerenadaConfig {
    let serverHost: String?
    let signalingProvider: SignalingProvider?
}

internal let SUPPORTED_SIGNALING_PROVIDER_VERSION = 1

internal func resolveSerenadaConfig(_ config: SerenadaConfig) throws -> ResolvedSerenadaConfig {
    let serverHost = config.serverHost?
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .nilIfEmpty
    let signalingProvider = config.signalingProvider

    guard (serverHost == nil) != (signalingProvider == nil) else {
        throw APIError.invalidResponse("Provide exactly one of serverHost or signalingProvider")
    }
    if let signalingProvider, signalingProvider.version != SUPPORTED_SIGNALING_PROVIDER_VERSION {
        throw APIError.invalidResponse("Unsupported signalingProvider version: \(signalingProvider.version)")
    }

    return ResolvedSerenadaConfig(
        serverHost: serverHost,
        signalingProvider: signalingProvider
    )
}

internal func requireServerHost(_ config: SerenadaConfig) throws -> String {
    let resolved = try resolveSerenadaConfig(config)
    guard let serverHost = resolved.serverHost else {
        throw APIError.invalidResponse("requires serverHost")
    }
    return serverHost
}

private func haveSameProvider(_ lhs: SignalingProvider?, _ rhs: SignalingProvider?) -> Bool {
    switch (lhs, rhs) {
    case (nil, nil):
        true
    case let (lhs?, rhs?):
        ObjectIdentifier(lhs) == ObjectIdentifier(rhs)
    default:
        false
    }
}
