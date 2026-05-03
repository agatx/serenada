import Foundation

/// Signaling transport type.
public enum SerenadaTransport: String, Equatable, Sendable {
    case ws
    case sse
}

/// Default preference order for camera modes when `SerenadaConfig.cameraModes` is `nil`.
public let defaultCameraModes: [LocalCameraMode] = [.selfie, .world, .composite]

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
    /// Whether video is enabled when joining a call. Defaults to `true`. When this is `false`,
    /// camera permission is not required for the initial join even when camera modes are
    /// available; the SDK requests camera access lazily if the user enables video later.
    public var defaultVideoEnabled: Bool
    /// Camera modes available in the call UI, in preference order. The first
    /// entry is the initial mode. When only one mode is listed the flip-camera
    /// control is hidden; an empty array disables video entirely (the video
    /// toggle is hidden and the camera is never requested). Modes unsupported
    /// on the current device are silently dropped (`.composite` is dropped on
    /// devices without multi-cam). `.screenShare` is always ignored — screen
    /// sharing is controlled separately. Defaults to `[.selfie, .world, .composite]`.
    public var cameraModes: [LocalCameraMode]?
    /// Preferred signaling transports in priority order. Defaults to `[.ws, .sse]`.
    public var transports: [SerenadaTransport]
    /// Whether the proximity sensor is used to switch audio to the earpiece and pause video.
    /// Defaults to `false`.
    public var proximityMonitoringEnabled: Bool

    public init(
        serverHost: String? = nil,
        signalingProvider: SignalingProvider? = nil,
        defaultAudioEnabled: Bool = true,
        defaultVideoEnabled: Bool = true,
        cameraModes: [LocalCameraMode]? = nil,
        transports: [SerenadaTransport] = [.ws, .sse],
        proximityMonitoringEnabled: Bool = false
    ) {
        self.serverHost = serverHost
        self.signalingProvider = signalingProvider
        self.defaultAudioEnabled = defaultAudioEnabled
        self.defaultVideoEnabled = defaultVideoEnabled
        self.cameraModes = cameraModes
        self.transports = transports
        self.proximityMonitoringEnabled = proximityMonitoringEnabled
    }

    public static func == (lhs: SerenadaConfig, rhs: SerenadaConfig) -> Bool {
        lhs.serverHost == rhs.serverHost
            && lhs.defaultAudioEnabled == rhs.defaultAudioEnabled
            && lhs.defaultVideoEnabled == rhs.defaultVideoEnabled
            && lhs.cameraModes == rhs.cameraModes
            && lhs.transports == rhs.transports
            && lhs.proximityMonitoringEnabled == rhs.proximityMonitoringEnabled
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
