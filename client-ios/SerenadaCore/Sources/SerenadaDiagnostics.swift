import AVFoundation
import Foundation
#if canImport(WebRTC)
import WebRTC
#endif

// MARK: - Report types

public enum DiagnosticCheckResult: Equatable {
    case available
    case unavailable(reason: String)
    case notAuthorized
    case skipped(reason: String)
}

public enum SignalingCheckResult: Equatable {
    case connected(transport: String)
    case failed(reason: String)
    case skipped(reason: String)
}

public enum TurnCheckResult: Equatable {
    case reachable(latencyMs: Int)
    case unreachable(reason: String)
    case skipped(reason: String)
}

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

public enum CheckOutcome: Equatable {
    case notRun
    case passed(latencyMs: Int)
    case failed(error: String)
}

public struct ConnectivityReport: Equatable {
    public var roomApi: CheckOutcome = .notRun
    public var webSocket: CheckOutcome = .notRun
    public var sse: CheckOutcome = .notRun
    public var diagnosticToken: CheckOutcome = .notRun
    public var turnCredentials: CheckOutcome = .notRun

    public init() {}
}

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

// MARK: - SerenadaDiagnostics

@MainActor
public final class SerenadaDiagnostics {
    private let config: SerenadaConfig
    private let apiClient: CoreAPIClient

    public init(config: SerenadaConfig) {
        self.config = config
        self.apiClient = CoreAPIClient()
        #if canImport(WebRTC)
        // Eagerly warm up the shared RTCPeerConnectionFactory so its network
        // thread is ready by the time the user runs an ICE probe.
        IceGatheringProbe.warmUpFactory()
        #endif
    }

    // MARK: - High-level reports

    public func runAll(completion: @escaping (DiagnosticsReport) -> Void) {
        Task {
            var report = DiagnosticsReport()
            report.camera = checkCameraSync()
            report.microphone = checkMicrophoneSync()
            report.speaker = checkSpeakerSync()
            report.network = await checkNetworkAsync()
            report.signaling = await checkSignalingAsync()
            report.turn = await checkTurnAsync()
            report.devices = enumerateDevices()
            completion(report)
        }
    }

    public func runConnectivityChecks() async -> ConnectivityReport {
        var report = ConnectivityReport()
        // Fetch the diagnostic token once and reuse it for the TURN credentials check.
        var tokenForTurn: String?
        report.roomApi = await runTimedCheck { try await self.apiClient.createRoomId(host: self.config.serverHost); return }
        report.webSocket = await runTimedCheck { try await self.testWebSocket() }
        report.sse = await runTimedCheck { try await self.testSse() }
        report.diagnosticToken = await runTimedCheck { tokenForTurn = try await self.apiClient.fetchDiagnosticToken(host: self.config.serverHost) }
        report.turnCredentials = await runTimedCheck {
            let resolvedToken: String
            if let existing = tokenForTurn {
                resolvedToken = existing
            } else {
                resolvedToken = try await self.apiClient.fetchDiagnosticToken(host: self.config.serverHost)
            }
            _ = try await self.apiClient.fetchTurnCredentials(host: self.config.serverHost, token: resolvedToken)
        }
        return report
    }

    public func runIceProbe(turnsOnly: Bool, onCandidateLog: ((String) -> Void)? = nil) async -> IceProbeReport {
        do {
            let token = try await apiClient.fetchDiagnosticToken(host: config.serverHost)
            let credentials = try await apiClient.fetchTurnCredentials(host: config.serverHost, token: token)
            let urls = turnsOnly ? credentials.uris.filter { $0.lowercased().hasPrefix("turns:") } : credentials.uris
            return await gatherIceCandidates(urls: urls, username: credentials.username, credential: credentials.password, onCandidateLog: onCandidateLog)
        } catch {
            return IceProbeReport(stunPassed: false, turnPassed: false, logs: [error.localizedDescription])
        }
    }

    public func validateServerHost() async throws {
        try await apiClient.validateServerHost(config.serverHost)
    }

    // MARK: - Individual checks

    public func checkCamera(completion: @escaping (DiagnosticCheckResult) -> Void) {
        completion(checkCameraSync())
    }

    public func checkMicrophone(completion: @escaping (DiagnosticCheckResult) -> Void) {
        completion(checkMicrophoneSync())
    }

    public func checkSpeaker(completion: @escaping (DiagnosticCheckResult) -> Void) {
        completion(checkSpeakerSync())
    }

    public func checkNetwork(completion: @escaping (DiagnosticCheckResult) -> Void) {
        Task { completion(await checkNetworkAsync()) }
    }

    public func checkSignaling(completion: @escaping (SignalingCheckResult) -> Void) {
        Task { completion(await checkSignalingAsync()) }
    }

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

    private func testWebSocket() async throws {
        guard let parsed = EndpointHostParser.splitHostAndPort(from: config.serverHost) else {
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

    private func testSse() async throws {
        guard let parsed = EndpointHostParser.splitHostAndPort(from: config.serverHost) else {
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

    private func gatherIceCandidates(urls: [String], username: String, credential: String, onCandidateLog: ((String) -> Void)?) async -> IceProbeReport {
#if canImport(WebRTC)
        guard !urls.isEmpty else {
            return IceProbeReport(stunPassed: false, turnPassed: false, logs: ["No ICE servers"])
        }
        let probe = IceGatheringProbe()
        var report = await probe.run(urls: urls, username: username, credential: credential, onCandidateLog: onCandidateLog)
        // Zero candidates (not even host) means the NetworkMonitor hadn't
        // enumerated interfaces yet — a transient race after the previous
        // PeerConnection was torn down.  Retry once; the monitor will be ready.
        if report.logs.isEmpty {
            onCandidateLog?("Zero candidates gathered — retrying (NetworkMonitor race)...")
            let retryProbe = IceGatheringProbe()
            report = await retryProbe.run(urls: urls, username: username, credential: credential, onCandidateLog: onCandidateLog)
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
        guard let url = apiClient.buildHTTPSURL(host: config.serverHost, path: "/api/room-id") else {
            return .unavailable(reason: "Invalid server host")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 5
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, (200...499).contains(http.statusCode) {
                return .available
            }
            return .unavailable(reason: "Server unreachable")
        } catch {
            return .unavailable(reason: error.localizedDescription)
        }
    }

    private func checkSignalingAsync() async -> SignalingCheckResult {
        do {
            try await apiClient.validateServerHost(config.serverHost)
            return .connected(transport: "https")
        } catch {
            return .failed(reason: error.localizedDescription)
        }
    }

    private func checkTurnAsync() async -> TurnCheckResult {
        guard let url = apiClient.buildHTTPSURL(host: config.serverHost, path: "/api/turn-credentials") else {
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

    func run(urls: [String], username: String, credential: String, onCandidateLog: ((String) -> Void)?) async -> IceProbeReport {
        self.onCandidateLog = onCandidateLog
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            self.start(urls: urls, username: username, credential: credential)
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                await self?.finish()
            }
        }
    }

    private func start(urls: [String], username: String, credential: String) {
        Self.warmUpFactory()
        let factory = Self.sharedFactory!

        let config = RTCConfiguration()
        config.iceServers = [RTCIceServer(urlStrings: urls, username: username, credential: credential)]
        config.sdpSemantics = .unifiedPlan

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = factory.peerConnection(with: config, constraints: constraints, delegate: self) else {
            logs.append("peerConnection creation failed")
            finish()
            return
        }

        peerConnection = connection
        _ = connection.dataChannel(forLabel: "diag", configuration: RTCDataChannelConfiguration())

        connection.offer(for: constraints) { [weak self] description, error in
            guard let self else { return }
            if let error {
                self.logs.append("offer failed: \(error.localizedDescription)")
                self.finish()
                return
            }
            guard let description else {
                self.logs.append("offer missing")
                self.finish()
                return
            }
            connection.setLocalDescription(description) { [weak self] setError in
                if let setError {
                    self?.logs.append("setLocalDescription failed: \(setError.localizedDescription)")
                    self?.finish()
                }
            }
        }
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        peerConnection?.close()
        continuation?.resume(returning: IceProbeReport(stunPassed: hasSrflx, turnPassed: hasRelay, logs: logs))
        continuation = nil
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        let sdp = candidate.sdp.lowercased()
        if sdp.contains(" typ srflx") { hasSrflx = true }
        if sdp.contains(" typ relay") { hasRelay = true }
        logs.append(candidate.sdp)
        onCandidateLog?(candidate.sdp)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete { finish() }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChangeLocalCandidate local: RTCIceCandidate, remoteCandidate remote: RTCIceCandidate, lastReceivedMs: Int32, changeReason reason: String) {}
}
#endif
