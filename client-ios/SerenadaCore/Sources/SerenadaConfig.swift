import Foundation
import SerenadaBroadcastExtensionSupport

/// Signaling transport type.
public enum SerenadaTransport: String, Equatable, Sendable {
    case ws
    case sse
}

/// How the SDK captures screen-share video during a call.
///
/// Screen sharing is runtime-configured, not a compile-time variant: both
/// capture paths are always built and the SDK selects one from this value. The
/// default is `.disabled`, so a host that has not provisioned a broadcast
/// extension never accidentally targets another app's IPC channels.
public enum ScreenShareMode: Equatable, Sendable {
    /// Screen sharing is unavailable: `screenShareExtensionBundleId` is `nil` and
    /// the share control stays hidden. Default — hosts opt in explicitly.
    case disabled
    /// System-wide capture via a Broadcast Upload Extension, delivered over the
    /// app-group shared memory described by `BroadcastIPCConfig`. Captures the
    /// whole device screen and survives backgrounding.
    case broadcast(BroadcastIPCConfig)
    /// In-app ReplayKit capture (`RPScreenRecorder`): only this app's own content,
    /// foreground-only. SDK/reference scope; not for hosts needing full-device
    /// sharing.
    case inAppOnly
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
    /// Whether this call can negotiate any video media. Set to `false` for strict
    /// audio-only calls such as PSTN: camera capture, screen sharing, and remote
    /// video are all disabled. Defaults to `true`.
    public var videoMediaEnabled: Bool
    /// Static capability gate for the independent content (screen-share) video
    /// stream. When `true`, this client advertises `independentContentVideo` at
    /// `join` and (in later phases) negotiates a separate content transceiver.
    /// Immutable per session. Defaults to `false` until the platform's media
    /// engine and UI/API surface ship — a client with the flag off behaves
    /// exactly like today, including the legacy `cameraMode=screenShare` path.
    public var enableIndependentContentVideo: Bool
    /// How screen-share video is captured. Defaults to `.disabled`; set
    /// `.broadcast(...)` with the host's app group + extension bundle ID to enable
    /// full-device sharing, or `.inAppOnly` for in-app ReplayKit capture.
    public var screenShareMode: ScreenShareMode
    /// Camera modes available in the call UI, in preference order. The first
    /// entry is the initial mode. When only one mode is listed the flip-camera
    /// control is hidden; an empty array disables camera capture (the video
    /// toggle is hidden and the camera is never requested). Remote video and
    /// screen sharing remain available unless `videoMediaEnabled` is `false`.
    /// Modes unsupported on the current device are silently dropped (`.composite`
    /// is dropped on devices without multi-cam). `.screenShare` is always ignored
    /// — screen sharing is controlled separately. Defaults to `[.selfie, .world, .composite]`.
    public var cameraModes: [LocalCameraMode]?
    /// When `true`, defer the initial-negotiation offer-timeout/ICE-restart while the host peer
    /// awaits its first answer. Use for app-owned calls whose answer is gated on a remote action
    /// that may take longer than the offer timeout, such as PSTN pickup. Defaults to `false`.
    public var deferInitialAnswer: Bool
    /// Preferred signaling transports in priority order. Defaults to `[.ws, .sse]`.
    public var transports: [SerenadaTransport]
    /// Whether the proximity sensor is used to switch audio to the earpiece and pause video.
    /// Defaults to `false`.
    public var proximityMonitoringEnabled: Bool
    /// Custom audio coordinator. If `nil`, the SDK uses its internal default coordinator.
    public var audioCoordinator: SerenadaAudioCoordinator?
    /// Audio policy passed to the coordinator when a call session activates.
    public var audioIntent: AudioIntent

    public init(
        serverHost: String? = nil,
        signalingProvider: SignalingProvider? = nil,
        defaultAudioEnabled: Bool = true,
        defaultVideoEnabled: Bool = true,
        videoMediaEnabled: Bool = true,
        enableIndependentContentVideo: Bool = false,
        screenShareMode: ScreenShareMode = .disabled,
        cameraModes: [LocalCameraMode]? = nil,
        deferInitialAnswer: Bool = false,
        transports: [SerenadaTransport] = [.ws, .sse],
        proximityMonitoringEnabled: Bool = false,
        audioCoordinator: SerenadaAudioCoordinator? = nil,
        audioIntent: AudioIntent = AudioIntent()
    ) {
        self.serverHost = serverHost
        self.signalingProvider = signalingProvider
        self.defaultAudioEnabled = defaultAudioEnabled
        self.defaultVideoEnabled = defaultVideoEnabled
        self.videoMediaEnabled = videoMediaEnabled
        self.enableIndependentContentVideo = enableIndependentContentVideo
        self.screenShareMode = screenShareMode
        self.cameraModes = cameraModes
        self.deferInitialAnswer = deferInitialAnswer
        self.transports = transports
        self.proximityMonitoringEnabled = proximityMonitoringEnabled
        self.audioCoordinator = audioCoordinator
        self.audioIntent = audioIntent
    }

    public static func == (lhs: SerenadaConfig, rhs: SerenadaConfig) -> Bool {
        lhs.serverHost == rhs.serverHost
            && lhs.defaultAudioEnabled == rhs.defaultAudioEnabled
            && lhs.defaultVideoEnabled == rhs.defaultVideoEnabled
            && lhs.videoMediaEnabled == rhs.videoMediaEnabled
            && lhs.enableIndependentContentVideo == rhs.enableIndependentContentVideo
            && lhs.screenShareMode == rhs.screenShareMode
            && lhs.cameraModes == rhs.cameraModes
            && lhs.deferInitialAnswer == rhs.deferInitialAnswer
            && lhs.transports == rhs.transports
            && lhs.proximityMonitoringEnabled == rhs.proximityMonitoringEnabled
            && haveSameProvider(lhs.signalingProvider, rhs.signalingProvider)
            && lhs.audioCoordinator === rhs.audioCoordinator
            && lhs.audioIntent == rhs.audioIntent
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
