import AVFoundation
import Foundation

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

@MainActor
public final class SerenadaDiagnostics {
    private let config: SerenadaConfig
    private let apiClient: CoreAPIClient

    public init(config: SerenadaConfig) {
        self.config = config
        self.apiClient = CoreAPIClient()
    }

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

    // MARK: - Private

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
        // Probe the TURN credentials endpoint with a dummy token to verify reachability.
        // A 401/403 response still confirms the endpoint is reachable.
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
