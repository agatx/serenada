import Foundation

/// Reason a call ended.
public enum EndReason: Equatable, Sendable {
    case localLeft
    case remoteEnded
    case error(String)
}

/// Reason a dropout began, carried so hosts can distinguish recovery causes.
public enum DropoutTrigger: Equatable, Sendable {
    /// Dropout began with signaling/network loss.
    case networkLost
    /// Dropout cause could not be attributed to network loss (e.g. ICE/peer-level).
    case unknown
}

/// Connection-quality event emitted by the SDK through
/// ``SerenadaCoreDelegate/sessionDidEmitConnectionEvent(_:event:)``.
public enum ConnectionEvent: Equatable, Sendable {
    /// A dropout recovered. Maps to the host's reconnect analytics.
    /// - Parameters:
    ///   - downtimeMs: downtime of the recovered dropout, in ms.
    ///   - reason: `networkLost` if the dropout began with signaling/network loss, else `unknown`.
    case reconnected(downtimeMs: Int64, reason: DropoutTrigger)

    /// Recovery was abandoned. Maps to the host's reconnect-failed analytics.
    case reconnectFailed(reason: ReconnectFailedReason)

    public enum ReconnectFailedReason: Equatable, Sendable {
        /// Recovery window elapsed.
        case timeout
        /// No network / transport available.
        case networkConnectivity
    }
}

/// Delegate for session lifecycle events (state changes, permissions, call end).
@MainActor
public protocol SerenadaCoreDelegate: AnyObject {
    func sessionRequiresPermissions(_ session: SerenadaSession, permissions: [MediaCapability])
    func sessionDidChangeState(_ session: SerenadaSession, state: CallState)
    func sessionDidEnd(_ session: SerenadaSession, reason: EndReason)
    /// Called when the SDK raises a connection-quality event.
    /// Additive, default no-op — read aggregate quality via
    /// ``SerenadaSession/qualitySummary``.
    func sessionDidEmitConnectionEvent(_ session: SerenadaSession, event: ConnectionEvent)
}

public extension SerenadaCoreDelegate {
    func sessionRequiresPermissions(_ session: SerenadaSession, permissions: [MediaCapability]) {}
    func sessionDidChangeState(_ session: SerenadaSession, state: CallState) {}
    func sessionDidEnd(_ session: SerenadaSession, reason: EndReason) {}
    func sessionDidEmitConnectionEvent(_ session: SerenadaSession, event: ConnectionEvent) {}
}

/// Result of creating a new room. Call `join` to start the call.
public struct CreateRoomResult {
    /// Full URL for the created room.
    public let url: URL
    /// Room identifier.
    public let roomId: String

    public init(url: URL, roomId: String) {
        self.url = url
        self.roomId = roomId
    }
}

/// Main entry point for the Serenada SDK. Create an instance with ``SerenadaConfig``,
/// then use ``join(url:displayName:peerId:)`` or ``createRoom()`` to start a call.
@MainActor
public final class SerenadaCore {
    /// SDK version string.
    public static let version = "0.9.1"

    /// SDK configuration.
    public let config: SerenadaConfig
    private let resolvedConfig: ResolvedSerenadaConfig
    /// Delegate for session lifecycle callbacks.
    public weak var delegate: SerenadaCoreDelegate?
    /// Optional logger for SDK diagnostics.
    public var logger: SerenadaLogger?

    /// Cross-launch recovery store for in-flight call state. Default
    /// `UserDefaults.standard`; the host app can replace it with an
    /// app-group-scoped store before opening any session.
    public let recoveryStorage: RecoveryStorage

    public init(config: SerenadaConfig, recoveryStorage: RecoveryStorage = RecoveryStorage()) {
        self.config = config
        self.recoveryStorage = recoveryStorage
        do {
            self.resolvedConfig = try resolveSerenadaConfig(config)
        } catch {
            preconditionFailure(error.localizedDescription)
        }
    }

    /// Returns a recoverable session if the previous process ended abruptly
    /// (force-quit, jetsam, OS kill) while a call was active and the
    /// persisted reconnect token is still within its TTL. Host apps should
    /// call this on launch and surface a "Rejoin call?" prompt — calling
    /// `join(roomId:)` with the returned `roomId` reattaches under the same
    /// CID. Returns `nil` when there is nothing to recover.
    public func getRecoverableSession() -> RecoveryRecord? {
        recoveryStorage.load()
    }

    /// Drops any persisted recovery record. Call this when the user
    /// explicitly declines the rejoin prompt so subsequent launches do not
    /// keep offering the same dead session.
    public func discardRecoverableSession() {
        recoveryStorage.clear()
    }

    /// Join an existing call by URL. Returns a session that begins connecting immediately.
    ///
    /// - Parameters:
    ///   - url: Full Serenada call URL.
    ///   - displayName: Optional display name for the local participant.
    ///   - peerId: Optional host-supplied stable identity for this user
    ///     (distinct from the per-call client ID). Surfaced on remote participants so
    ///     the call UI can resolve avatars via `SerenadaCallFlowConfig.avatarProvider`.
    public func join(url: URL, displayName: String? = nil, peerId: String? = nil) -> SerenadaSession {
        let roomId = DeepLinkParser.extractRoomId(from: url) ?? url.lastPathComponent
        let target = DeepLinkParser.parseTarget(from: url)
        let serverHost = target?.host
            ?? DeepLinkParser.normalizeHostValue(authorityHost(from: url))
            ?? resolvedConfig.serverHost
        let sessionConfig: SerenadaConfig
        if resolvedConfig.serverHost != nil {
            sessionConfig = SerenadaConfig(
                serverHost: serverHost,
                signalingProvider: nil,
                defaultAudioEnabled: config.defaultAudioEnabled,
                defaultVideoEnabled: config.defaultVideoEnabled,
                videoMediaEnabled: config.videoMediaEnabled,
                enableIndependentContentVideo: config.enableIndependentContentVideo,
                cameraModes: config.cameraModes,
                deferInitialAnswer: config.deferInitialAnswer,
                transports: config.transports,
                proximityMonitoringEnabled: config.proximityMonitoringEnabled,
                audioCoordinator: config.audioCoordinator,
                audioIntent: config.audioIntent
            )
        } else {
            sessionConfig = config
        }
        let resolution = resolveSessionProvider(for: sessionConfig, roomId: roomId)
        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: url,
            config: sessionConfig,
            delegateProvider: { [weak self] in self?.delegate },
            logger: logger,
            initialSignalingProvider: resolution.provider,
            displayName: displayName,
            peerId: peerId,
            recoveryStorage: recoveryStorage,
            // Public single-call join owns its own direct foreground lease.
            acquireForegroundLease: true,
            initialStartupError: resolution.startupError
        )
        if resolution.bindsV1 { V1ProviderRegistry.bind(resolution.provider, to: session) }
        return session
    }

    /// Join an existing call by room ID. Returns a session that begins connecting immediately.
    ///
    /// - Parameters:
    ///   - roomId: Bare room identifier.
    ///   - displayName: Optional display name for the local participant.
    ///   - peerId: Optional host-supplied stable identity; see the URL ``join(url:displayName:peerId:)`` overload.
    public func join(roomId: String, displayName: String? = nil, peerId: String? = nil) -> SerenadaSession {
        let url = resolvedConfig.serverHost.flatMap { buildRoomURL(host: $0, roomId: roomId) }

        let resolution = resolveSessionProvider(for: config, roomId: roomId)
        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: url,
            config: config,
            delegateProvider: { [weak self] in self?.delegate },
            logger: logger,
            initialSignalingProvider: resolution.provider,
            displayName: displayName,
            peerId: peerId,
            recoveryStorage: recoveryStorage,
            // Public single-call join owns its own direct foreground lease.
            acquireForegroundLease: true,
            initialStartupError: resolution.startupError
        )
        if resolution.bindsV1 { V1ProviderRegistry.bind(resolution.provider, to: session) }
        return session
    }

    /// Build a registry-managed session for a room URL, with an explicit initial
    /// media role and WITHOUT self-acquiring the foreground lease (the
    /// ``SerenadaCallRegistry`` owns the lease + owning-mode for every session it
    /// creates — contract §3 / design "Join With Initial Media Role").
    ///
    /// This is the single registry-internal join seam: there is NO public
    /// `join(initialMediaRole:)`. The two registry entry points (`joinHeld` /
    /// `joinAndSwitch`) are composites over this. Mirrors the provider/config
    /// wiring of `join(url:)` so a registry-created session signals identically to
    /// a direct one; only the role and lease ownership differ.
    func makeManagedSession(
        roomId: String,
        roomURL: URL?,
        initialMediaRole: CallMediaRole,
        displayName: String? = nil,
        peerId: String? = nil,
        arbiter: ForegroundMediaArbiter
    ) -> SerenadaSession {
        // Resolve a server host only in server mode (a URL is always present
        // then). In provider mode `resolvedConfig.serverHost` is nil and `roomURL`
        // is nil too — the session keeps `roomUrl` nil and opens the provider
        // channel with the bare `roomId` (mirrors the direct `join(roomId:)` path;
        // `roomUrl` is informational only).
        let target = roomURL.flatMap { DeepLinkParser.parseTarget(from: $0) }
        let serverHost = target?.host
            ?? DeepLinkParser.normalizeHostValue(roomURL.flatMap { authorityHost(from: $0) })
            ?? resolvedConfig.serverHost
        let sessionConfig: SerenadaConfig
        if resolvedConfig.serverHost != nil {
            sessionConfig = SerenadaConfig(
                serverHost: serverHost,
                signalingProvider: nil,
                defaultAudioEnabled: config.defaultAudioEnabled,
                defaultVideoEnabled: config.defaultVideoEnabled,
                videoMediaEnabled: config.videoMediaEnabled,
                enableIndependentContentVideo: config.enableIndependentContentVideo,
                cameraModes: config.cameraModes,
                deferInitialAnswer: config.deferInitialAnswer,
                transports: config.transports,
                proximityMonitoringEnabled: config.proximityMonitoringEnabled,
                audioCoordinator: config.audioCoordinator,
                audioIntent: config.audioIntent
            )
        } else {
            sessionConfig = config
        }
        let resolution = resolveSessionProvider(for: sessionConfig, roomId: roomId)
        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: roomURL,
            config: sessionConfig,
            delegateProvider: { [weak self] in self?.delegate },
            logger: logger,
            initialSignalingProvider: resolution.provider,
            displayName: displayName,
            peerId: peerId,
            recoveryStorage: recoveryStorage,
            initialMediaRole: initialMediaRole,
            // The registry owns the lease + owning-mode for managed sessions, so a
            // managed session NEVER self-acquires/self-releases the direct lease.
            acquireForegroundLease: false,
            initialStartupError: resolution.startupError,
            foregroundArbiter: arbiter
        )
        if resolution.bindsV1 { V1ProviderRegistry.bind(resolution.provider, to: session) }
        return session
    }

    /// Create a new room. Returns the room URL and ID. Call ``join(url:displayName:peerId:)`` or ``join(roomId:displayName:peerId:)`` to start the call.
    public func createRoom() async throws -> CreateRoomResult {
        let apiClient = CoreAPIClient()
        let serverHost = try requireServerHost(config)
        let roomId = try await apiClient.createRoomId(host: serverHost)
        guard let url = buildRoomURL(host: serverHost, roomId: roomId) else {
            throw APIError.invalidResponse("Failed to build room URL")
        }
        return CreateRoomResult(url: url, roomId: roomId)
    }

    /// Create a room ID without starting a session.
    /// Use this when you only need a room ID (e.g., for invite links).
    public func createRoomId() async throws -> String {
        let apiClient = CoreAPIClient()
        return try await apiClient.createRoomId(host: requireServerHost(config))
    }

    /// Build a room URL from a bare room id using the configured server host.
    /// Registry-internal: the default ``SerenadaCallRegistry`` session factory
    /// uses this when a `RoomRef` carries only a roomId. Returns `nil` when this
    /// core has no server host (custom-signaling-only config).
    func roomURL(forRoomId roomId: String) -> URL? {
        guard let serverHost = resolvedConfig.serverHost else { return nil }
        return buildRoomURL(host: serverHost, roomId: roomId)
    }

    private func buildRoomURL(host: String, roomId: String) -> URL? {
        guard let parsedHost = EndpointHostParser.splitHostAndPort(from: host) else { return nil }

        let isLocal = parsedHost.host == "localhost" || parsedHost.host.hasPrefix("127.")
        var components = URLComponents()
        components.scheme = isLocal ? "http" : "https"
        components.host = parsedHost.host
        components.port = parsedHost.port
        components.path = "/call/\(roomId)"
        return components.url
    }

    private func authorityHost(from url: URL) -> String? {
        guard let host = url.host else { return nil }
        if let port = url.port {
            return "\(host):\(port)"
        }
        return host
    }

    /// Resolution of which signaling channel a new session should use, plus how the
    /// session should start.
    private struct SessionProviderResolution {
        /// The channel the session installs as its `signalingProvider`.
        let provider: SignalingProvider
        /// Non-nil when the session must fail immediately (v1 single-session
        /// conflict) instead of joining — the session surfaces it as an error
        /// ``CallState`` (registry maps it to a failed join).
        let startupError: CallError?
        /// True when this resolution bound the configured v1 provider to the new
        /// session, so the caller records the binding in ``V1ProviderRegistry``.
        let bindsV1: Bool
    }

    /// Pick the signaling channel for a new session bound to `roomId`.
    ///
    /// - Server mode: a fresh ``SerenadaServerProvider``.
    /// - Multi-session (v2): a fresh channel from `openSession(roomId:)`.
    /// - Single-session (v1): the configured provider, guarded — if another live
    ///   session already holds it, resolve to an inert channel + a
    ///   ``CallError/providerUnavailable`` startup error so the join fails cleanly
    ///   instead of clobbering the in-use channel.
    private func resolveSessionProvider(for sessionConfig: SerenadaConfig, roomId: String) -> SessionProviderResolution {
        let resolved: ResolvedSerenadaConfig
        do {
            resolved = try resolveSerenadaConfig(sessionConfig)
        } catch {
            preconditionFailure(error.localizedDescription)
        }
        if let serverHost = resolved.serverHost {
            let provider = SerenadaServerProvider(
                serverHost: serverHost,
                apiClient: CoreAPIClient(),
                transports: sessionConfig.transports,
                logger: logger
            )
            return SessionProviderResolution(provider: provider, startupError: nil, bindsV1: false)
        }
        if let multiSession = resolved.multiSessionSignalingProvider {
            // Each session gets its OWN channel — no cross-session liveness guard.
            return SessionProviderResolution(
                provider: multiSession.openSession(roomId: roomId),
                startupError: nil,
                bindsV1: false
            )
        }
        guard let signalingProvider = resolved.signalingProvider else {
            preconditionFailure("Provide exactly one of serverHost, signalingProvider, or multiSessionSignalingProvider")
        }
        if V1ProviderRegistry.isInUse(signalingProvider) {
            return SessionProviderResolution(
                provider: UnavailableSignalingProvider(),
                startupError: .providerUnavailable,
                bindsV1: false
            )
        }
        return SessionProviderResolution(provider: signalingProvider, startupError: nil, bindsV1: true)
    }
}

/// Inert channel installed on a session that cannot bind the configured v1
/// provider (single-session conflict). It never connects or emits; the session it
/// backs fails immediately with ``CallError/providerUnavailable``. Keeping the
/// session's `signalingProvider` non-nil lets teardown (`disconnect()`) run
/// uniformly without touching the real, in-use v1 provider.
private final class UnavailableSignalingProvider: SignalingProvider {
    weak var delegate: SignalingProviderDelegate?

    func connect() {}
    func disconnect() {}
    func joinRoom(_ roomId: String, options: JoinOptions) {}
    func leaveRoom() {}
    func endRoom() {}
    func sendToPeer(_ peerId: String, type: String, payload: SignalingPayload?) {}
    func broadcast(type: String, payload: SignalingPayload?) {}
    func getIceServers() async throws -> [IceServerConfig] { [] }
}

/// Process-wide binding of single-session (v1) `signalingProvider` OBJECTS to the
/// live session that currently holds each one. The v1 contract is per provider
/// object (one `delegate` slot, room-less ops), so the guard must be keyed by
/// provider identity across ALL ``SerenadaCore`` instances — two cores sharing the
/// same v1 provider object must not both bind it and clobber each other's channel.
///
/// Entries are weak and pruned when the bound session reaches a terminal phase
/// (`.idle`/`.ending`/`.error`) — that session has already run its teardown
/// (`disconnect()` + delegate unbind), so it no longer holds the channel. This lets
/// sequential reuse work and never retains providers or sessions. `@MainActor`
/// isolation (every ``SerenadaCore`` join path is main-actor) is the sole
/// synchronization discipline.
@MainActor
private enum V1ProviderRegistry {
    private final class WeakSessionBox {
        weak var session: SerenadaSession?
        init(_ session: SerenadaSession) { self.session = session }
    }

    private static var bindings: [ObjectIdentifier: WeakSessionBox] = [:]

    /// Whether `provider` is currently held by a live (non-terminal) session.
    /// Prunes the entry when the bound session is gone or terminal.
    static func isInUse(_ provider: SignalingProvider) -> Bool {
        let key = ObjectIdentifier(provider)
        guard let bound = bindings[key]?.session else {
            bindings[key] = nil
            return false
        }
        switch bound.state.phase {
        case .idle, .ending, .error:
            bindings[key] = nil
            return false
        case .awaitingPermissions, .joining, .waiting, .inCall:
            return true
        }
    }

    /// Record `session` as the holder of `provider`.
    static func bind(_ provider: SignalingProvider, to session: SerenadaSession) {
        bindings[ObjectIdentifier(provider)] = WeakSessionBox(session)
    }
}
