import AVFoundation
import Foundation
import Network
#if canImport(WebRTC)
import WebRTC
#endif

// MARK: - Report types

/// Result of a single device or capability check.
public enum DiagnosticCheckResult: Equatable {
    case available
    case unavailable(reason: String)
    case notAuthorized
    case skipped(reason: String)
}

/// Result of a signaling server connectivity check.
public enum SignalingCheckResult: Equatable {
    case connected(transport: String)
    case failed(reason: String)
    case skipped(reason: String)
}

/// Result of a TURN server reachability check.
public enum TurnCheckResult: Equatable {
    case reachable(latencyMs: Int)
    case unreachable(reason: String)
    case skipped(reason: String)
}

/// Information about a detected media device (camera or microphone).
public struct DeviceInfo: Equatable {
    public let id: String
    public let name: String
    public let kind: String

    public init(id: String, name: String, kind: String) {
        self.id = id
        self.name = name
        self.kind = kind
    }
}

/// Full diagnostic report covering device capabilities and server connectivity.
public struct DiagnosticsReport: Equatable {
    public var camera: DiagnosticCheckResult = .skipped(reason: "not run")
    public var microphone: DiagnosticCheckResult = .skipped(reason: "not run")
    public var speaker: DiagnosticCheckResult = .skipped(reason: "not run")
    public var network: DiagnosticCheckResult = .skipped(reason: "not run")
    public var signaling: SignalingCheckResult = .skipped(reason: "not run")
    public var turn: TurnCheckResult = .skipped(reason: "not run")
    public var devices: [DeviceInfo] = []

    public init() {}
}

/// Outcome of a timed connectivity check (not run, passed with latency, or failed).
public enum CheckOutcome: Equatable {
    case notRun
    case passed(latencyMs: Int)
    case failed(error: String)
}

/// Results of server connectivity checks (room API, WebSocket, SSE, TURN).
public struct ConnectivityReport: Equatable {
    public var roomApi: CheckOutcome = .notRun
    public var webSocket: CheckOutcome = .notRun
    public var sse: CheckOutcome = .notRun
    public var diagnosticToken: CheckOutcome = .notRun
    public var turnCredentials: CheckOutcome = .notRun

    public init() {}
}

/// Results of an ICE candidate gathering probe (STUN and TURN connectivity).
public struct IceProbeReport: Equatable {
    public let stunPassed: Bool
    public let turnPassed: Bool
    public let logs: [String]

    public init(stunPassed: Bool, turnPassed: Bool, logs: [String]) {
        self.stunPassed = stunPassed
        self.turnPassed = turnPassed
        self.logs = logs
    }
}

private final class LockedContinuationGate: @unchecked Sendable {
    private let lock = NSLock()
    private var didResume = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !didResume else { return false }
        didResume = true
        return true
    }
}

// MARK: - SerenadaDiagnostics

/// Pre-flight diagnostics utility. Checks device capabilities and server connectivity.
@MainActor
public final class SerenadaDiagnostics {
    private let config: SerenadaConfig
    private let apiClient: CoreAPIClient
    private let resolvedConfig: ResolvedSerenadaConfig

    public init(config: SerenadaConfig) {
        self.config = config
        self.apiClient = CoreAPIClient()
        do {
            self.resolvedConfig = try resolveSerenadaConfig(config)
        } catch {
            preconditionFailure(error.localizedDescription)
        }
        #if canImport(WebRTC)
        // Eagerly warm up the shared RTCPeerConnectionFactory so its network
        // thread is ready by the time the user runs an ICE probe.
        IceGatheringProbe.warmUpFactory()
        #endif
    }

    // MARK: - High-level reports

    /// Run all diagnostic checks and return a full report via completion handler.
    public func runAll(completion: @escaping (DiagnosticsReport) -> Void) {
        Task {
            var report = DiagnosticsReport()
            report.camera = checkCameraSync()
            report.microphone = checkMicrophoneSync()
            report.speaker = checkSpeakerSync()
            report.network = await checkNetworkAsync()
            report.signaling = resolvedConfig.serverHost == nil
                ? .skipped(reason: "requires serverHost")
                : await checkSignalingAsync()
            report.turn = await checkTurnAsync()
            report.devices = enumerateDevices()
            completion(report)
        }
    }

    /// Test server connectivity (room API, WebSocket, SSE, TURN credentials).
    ///
    /// - Throws: An error when `serverHost` is unavailable or a required
    ///   connectivity probe cannot be executed.
    public func runConnectivityChecks() async throws -> ConnectivityReport {
        guard let serverHost = resolvedConfig.serverHost else {
            throw APIError.invalidResponse("requires serverHost")
        }
        var report = ConnectivityReport()
        // Run independent probes concurrently. The diagnostic-token result is
        // reused for the TURN credentials check that follows.
        var tokenForTurn: String?
        async let roomApiResult = runTimedCheck {
            _ = try await self.apiClient.createRoomId(host: serverHost)
        }
        async let webSocketResult = runTimedCheck { try await self.testWebSocket(host: serverHost) }
        async let sseResult = runTimedCheck { try await self.testSse(host: serverHost) }
        async let diagnosticTokenResult: (CheckOutcome, String?) = {
            var token: String?
            let outcome = await runTimedCheck { token = try await self.apiClient.fetchDiagnosticToken(host: serverHost) }
            return (outcome, token)
        }()

        report.roomApi = await roomApiResult
        report.webSocket = await webSocketResult
        report.sse = await sseResult
        let (dtOutcome, dtToken) = await diagnosticTokenResult
        report.diagnosticToken = dtOutcome
        tokenForTurn = dtToken

        report.turnCredentials = await runTimedCheck {
            let resolvedToken: String
            if let existing = tokenForTurn {
                resolvedToken = existing
            } else {
                resolvedToken = try await self.apiClient.fetchDiagnosticToken(host: serverHost)
            }
            _ = try await self.apiClient.fetchTurnCredentials(host: serverHost, token: resolvedToken)
        }
        return report
    }

    /// Probe ICE connectivity by gathering candidates against the configured TURN server or provider ICE source.
    public func runTurnProbe(turnsOnly: Bool, onCandidateLog: ((String) -> Void)? = nil) async -> IceProbeReport {
        do {
            let iceServers = try await resolveIceServers()
            let filteredServers = iceServers.compactMap { server -> IceServerConfig? in
                let urls = turnsOnly
                    ? server.urls.filter { $0.lowercased().hasPrefix("turns:") }
                    : server.urls
                return urls.isEmpty ? nil : IceServerConfig(urls: urls, username: server.username, credential: server.credential)
            }
            guard !filteredServers.isEmpty else {
                return IceProbeReport(stunPassed: false, turnPassed: false, logs: ["No ICE servers"])
            }
            return await gatherIceCandidates(
                iceServers: filteredServers,
                onCandidateLog: onCandidateLog
            )
        } catch {
            return IceProbeReport(stunPassed: false, turnPassed: false, logs: [error.localizedDescription])
        }
    }

    /// Probe ICE connectivity (STUN/TURN) by gathering candidates with a real peer connection.
    @available(*, deprecated, message: "Use runTurnProbe(turnsOnly:onCandidateLog:) instead.")
    public func runIceProbe(turnsOnly: Bool, onCandidateLog: ((String) -> Void)? = nil) async -> IceProbeReport {
        await runTurnProbe(turnsOnly: turnsOnly, onCandidateLog: onCandidateLog)
    }

    /// Validate that the configured server host is reachable.
    public func validateServerHost(host: String? = nil) async throws {
        let normalizedHost = host?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        guard let resolvedHost = normalizedHost ?? resolvedConfig.serverHost else {
            throw APIError.invalidResponse("requires serverHost")
        }
        try await apiClient.validateServerHost(resolvedHost)
    }

    // MARK: - Individual checks

    /// Check camera availability and authorization.
    public func checkCamera(completion: @escaping (DiagnosticCheckResult) -> Void) {
        completion(checkCameraSync())
    }

    /// Check microphone availability and authorization.
    public func checkMicrophone(completion: @escaping (DiagnosticCheckResult) -> Void) {
        completion(checkMicrophoneSync())
    }

    /// Check speaker/audio output availability.
    public func checkSpeaker(completion: @escaping (DiagnosticCheckResult) -> Void) {
        completion(checkSpeakerSync())
    }

    /// Check network reachability to the server.
    public func checkNetwork(completion: @escaping (DiagnosticCheckResult) -> Void) {
        Task { completion(await checkNetworkAsync()) }
    }

    /// Check signaling server connectivity.
    public func checkSignaling(completion: @escaping (SignalingCheckResult) -> Void) {
        Task { completion(await checkSignalingAsync()) }
    }

    /// Check TURN server reachability.
    public func checkTurn(completion: @escaping (TurnCheckResult) -> Void) {
        Task { completion(await checkTurnAsync()) }
    }

    // MARK: - Private helpers

    private func runTimedCheck(_ block: @escaping () async throws -> Void) async -> CheckOutcome {
        let start = CFAbsoluteTimeGetCurrent()
        do {
            try await block()
            let latencyMs = Int((CFAbsoluteTimeGetCurrent() - start) * 1000)
            return .passed(latencyMs: latencyMs)
        } catch {
            return .failed(error: error.localizedDescription)
        }
    }

    // MARK: - WebSocket / SSE tests

    private func testWebSocket(host: String) async throws {
        guard let parsed = EndpointHostParser.splitHostAndPort(from: host) else {
            throw APIError.invalidHost
        }
        var components = URLComponents()
        components.scheme = parsed.host == "localhost" || parsed.host.hasPrefix("127.") ? "ws" : "wss"
        components.host = parsed.host
        components.port = parsed.port
        components.path = "/ws"
        guard let url = components.url else { throw APIError.invalidHost }

        let task = URLSession.shared.webSocketTask(with: url)
        task.resume()
        try await Task.sleep(nanoseconds: 600_000_000)
        task.cancel(with: .goingAway, reason: nil)
    }

    private func testSse(host: String) async throws {
        guard let parsed = EndpointHostParser.splitHostAndPort(from: host) else {
            throw APIError.invalidHost
        }
        let sid = "diag-\(UUID().uuidString)"
        let isLocal = parsed.host == "localhost" || parsed.host.hasPrefix("127.")

        var getComponents = URLComponents()
        getComponents.scheme = isLocal ? "http" : "https"
        getComponents.host = parsed.host
        getComponents.port = parsed.port
        getComponents.path = "/sse"
        getComponents.queryItems = [URLQueryItem(name: "sid", value: sid)]
        guard let getURL = getComponents.url else { throw APIError.invalidHost }

        let (bytes, response) = try await URLSession.shared.bytes(from: getURL)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("SSE open failed")
        }
        _ = try await bytes.lines.first(where: { _ in true })

        var postComponents = URLComponents()
        postComponents.scheme = isLocal ? "http" : "https"
        postComponents.host = parsed.host
        postComponents.port = parsed.port
        postComponents.path = "/sse"
        postComponents.queryItems = [URLQueryItem(name: "sid", value: sid)]
        guard let postURL = postComponents.url else { throw APIError.invalidHost }

        var request = URLRequest(url: postURL)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data("{\"v\":1,\"type\":\"ping\",\"payload\":{\"ts\":\(Int(Date().timeIntervalSince1970 * 1000))}}".utf8)
        let (_, postResponse) = try await URLSession.shared.data(for: request)
        guard let postHTTP = postResponse as? HTTPURLResponse, (200...299).contains(postHTTP.statusCode) else {
            throw APIError.http("SSE ping failed")
        }
    }

    // MARK: - ICE probing

    private func gatherIceCandidates(iceServers: [IceServerConfig], onCandidateLog: ((String) -> Void)?) async -> IceProbeReport {
#if canImport(WebRTC)
        guard !iceServers.isEmpty else {
            return IceProbeReport(stunPassed: false, turnPassed: false, logs: ["No ICE servers"])
        }
        let probe = IceGatheringProbe()
        var report = await probe.run(iceServers: iceServers, onCandidateLog: onCandidateLog)
        // Zero candidates (not even host) means the NetworkMonitor hadn't
        // enumerated interfaces yet — a transient race after the previous
        // PeerConnection was torn down.  Retry once; the monitor will be ready.
        if report.logs.isEmpty {
            onCandidateLog?("Zero candidates gathered — retrying (NetworkMonitor race)...")
            let retryProbe = IceGatheringProbe()
            report = await retryProbe.run(iceServers: iceServers, onCandidateLog: onCandidateLog)
        }
        return report
#else
        return IceProbeReport(stunPassed: false, turnPassed: false, logs: ["WebRTC not available"])
#endif
    }

    // MARK: - Basic checks

    private func checkCameraSync() -> DiagnosticCheckResult {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .authorized:
            let hasCamera = AVCaptureDevice.default(for: .video) != nil
            return hasCamera ? .available : .unavailable(reason: "No camera device found")
        case .notDetermined, .denied, .restricted:
            return .notAuthorized
        @unknown default:
            return .notAuthorized
        }
    }

    private func checkMicrophoneSync() -> DiagnosticCheckResult {
        let status = AVAudioSession.sharedInstance().recordPermission
        switch status {
        case .granted:
            return .available
        case .undetermined, .denied:
            return .notAuthorized
        @unknown default:
            return .notAuthorized
        }
    }

    private func checkSpeakerSync() -> DiagnosticCheckResult {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        if outputs.isEmpty {
            return .unavailable(reason: "No audio output available")
        }
        return .available
    }

    private func checkNetworkAsync() async -> DiagnosticCheckResult {
        await withCheckedContinuation { continuation in
            let monitor = NWPathMonitor()
            let queue = DispatchQueue(label: "SerenadaDiagnostics.Network")
            let gate = LockedContinuationGate()
            monitor.pathUpdateHandler = { path in
                guard gate.claim() else { return }
                let result: DiagnosticCheckResult = path.status == .satisfied
                    ? .available
                    : .unavailable(reason: "No network connection")
                continuation.resume(returning: result)
                monitor.cancel()
            }
            monitor.start(queue: queue)
        }
    }

    private func checkSignalingAsync() async -> SignalingCheckResult {
        guard let serverHost = resolvedConfig.serverHost else {
            return .skipped(reason: "requires serverHost")
        }
        do {
            try await apiClient.validateServerHost(serverHost)
            return .connected(transport: "https")
        } catch {
            return .failed(reason: error.localizedDescription)
        }
    }

    private func checkTurnAsync() async -> TurnCheckResult {
        if resolvedConfig.serverHost == nil {
            let start = CFAbsoluteTimeGetCurrent()
            do {
                guard let signalingProvider = resolvedConfig.signalingProvider else {
                    return .unreachable(reason: "Provide exactly one of serverHost or signalingProvider")
                }
                _ = try await signalingProvider.getIceServers()
                let latencyMs = Int((CFAbsoluteTimeGetCurrent() - start) * 1000)
                return .reachable(latencyMs: latencyMs)
            } catch {
                return .unreachable(reason: error.localizedDescription)
            }
        }
        guard let serverHost = resolvedConfig.serverHost,
              let url = apiClient.buildHTTPSURL(host: serverHost, path: "/api/turn-credentials") else {
            return .unreachable(reason: "Invalid server host")
        }
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "token", value: "probe")]
        guard let probeUrl = components?.url else {
            return .unreachable(reason: "Failed to build TURN probe URL")
        }
        var request = URLRequest(url: probeUrl)
        request.httpMethod = "GET"
        request.timeoutInterval = 5
        let start = CFAbsoluteTimeGetCurrent()
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            let latencyMs = Int((CFAbsoluteTimeGetCurrent() - start) * 1000)
            if let http = response as? HTTPURLResponse, (200...403).contains(http.statusCode) {
                return .reachable(latencyMs: latencyMs)
            }
            return .unreachable(reason: "TURN endpoint returned unexpected status")
        } catch {
            return .unreachable(reason: error.localizedDescription)
        }
    }

    private func resolveIceServers() async throws -> [IceServerConfig] {
        if let serverHost = resolvedConfig.serverHost {
            let token = try await apiClient.fetchDiagnosticToken(host: serverHost)
            let credentials = try await apiClient.fetchTurnCredentials(host: serverHost, token: token)
            return [
                IceServerConfig(
                    urls: credentials.uris,
                    username: credentials.username,
                    credential: credentials.password
                )
            ]
        }
        guard let signalingProvider = resolvedConfig.signalingProvider else {
            throw APIError.invalidResponse("Provide exactly one of serverHost or signalingProvider")
        }
        return try await signalingProvider.getIceServers()
    }

    private func enumerateDevices() -> [DeviceInfo] {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        guard status == .authorized else { return [] }

        var devices: [DeviceInfo] = []
        let videoDevices = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera],
            mediaType: .video,
            position: .unspecified
        ).devices
        for device in videoDevices {
            devices.append(DeviceInfo(id: device.uniqueID, name: device.localizedName, kind: "camera"))
        }

        let audioDevices = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInMicrophone],
            mediaType: .audio,
            position: .unspecified
        ).devices
        for device in audioDevices {
            devices.append(DeviceInfo(id: device.uniqueID, name: device.localizedName, kind: "microphone"))
        }

        return devices
    }
}

// MARK: - ICE Gathering Probe

#if canImport(WebRTC)
@MainActor
private final class IceGatheringProbe: NSObject, RTCPeerConnectionDelegate {
    /// Shared factory — creating a new one per probe and letting it be deallocated
    /// tears down the native NetworkMonitor, causing a race where the next probe's
    /// monitor hasn't enumerated interfaces yet and ICE gathering completes with
    /// zero candidates.
    private static var sharedFactory: RTCPeerConnectionFactory?

    static func warmUpFactory() {
        if sharedFactory == nil {
            let encoderFactory = RTCDefaultVideoEncoderFactory()
            let decoderFactory = RTCDefaultVideoDecoderFactory()
            sharedFactory = RTCPeerConnectionFactory(encoderFactory: encoderFactory, decoderFactory: decoderFactory)
        }
    }

    private var continuation: CheckedContinuation<IceProbeReport, Never>?
    private var peerConnection: RTCPeerConnection?
    private var hasSrflx = false
    private var hasRelay = false
    private var logs: [String] = []
    private var finished = false
    private var onCandidateLog: ((String) -> Void)?

    func run(iceServers: [IceServerConfig], onCandidateLog: ((String) -> Void)?) async -> IceProbeReport {
        self.onCandidateLog = onCandidateLog
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            Task { @MainActor [weak self] in
                await self?.start(iceServers: iceServers)
            }
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                self?.finish()
            }
        }
    }

    private func start(iceServers: [IceServerConfig]) async {
        Self.warmUpFactory()
        let factory = Self.sharedFactory!

        let config = RTCConfiguration()
        config.iceServers = iceServers.map {
            RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential)
        }
        config.sdpSemantics = .unifiedPlan

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = factory.peerConnection(with: config, constraints: constraints, delegate: self) else {
            logs.append("peerConnection creation failed")
            finish()
            return
        }

        peerConnection = connection
        _ = connection.dataChannel(forLabel: "diag", configuration: RTCDataChannelConfiguration())

        do {
            let description = try await connection.offer(for: constraints)
            do {
                try await connection.setLocalDescription(description)
            } catch {
                logs.append("setLocalDescription failed: \(error.localizedDescription)")
                finish()
            }
        } catch {
            logs.append("offer failed: \(error.localizedDescription)")
            finish()
        }
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        peerConnection?.close()
        continuation?.resume(returning: IceProbeReport(stunPassed: hasSrflx, turnPassed: hasRelay, logs: logs))
        continuation = nil
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        let sdp = candidate.sdp
        Task { @MainActor [weak self] in
            guard let self else { return }
            let normalizedSdp = sdp.lowercased()
            if normalizedSdp.contains(" typ srflx") { hasSrflx = true }
            if normalizedSdp.contains(" typ relay") { hasRelay = true }
            logs.append(sdp)
            onCandidateLog?(sdp)
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        guard newState == .complete else { return }
        Task { @MainActor [weak self] in
            self?.finish()
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChangeLocalCandidate local: RTCIceCandidate, remoteCandidate remote: RTCIceCandidate, lastReceivedMs: Int32, changeReason reason: String) {}
}
#endif
