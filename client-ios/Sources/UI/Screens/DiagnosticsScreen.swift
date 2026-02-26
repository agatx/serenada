import AVFoundation
import SwiftUI
import UserNotifications
#if canImport(WebRTC)
import WebRTC
#endif

private enum DiagnosticsCheckStatus {
    case idle
    case running
    case pass
    case warn
    case fail
}

private struct DiagnosticsCheckResult: Identifiable {
    let id = UUID()
    let title: String
    let status: DiagnosticsCheckStatus
    let detail: String
    let latencyMs: Int?
}

private struct DiagnosticsPermissionReport {
    var cameraGranted = false
    var microphoneGranted = false
    var notificationsGranted = false
}

private struct DiagnosticsMediaReport {
    var hasCameraHardware = false
    var hasFrontCamera = false
    var hasBackCamera = false
    var isCompositeSupported = false
    var hasMicrophoneHardware = false
    var sampleRateHz: Double?
    var ioBufferMs: Double?
}

private struct DiagnosticsIceReport {
    var stunPassed = false
    var turnPassed = false
}

struct DiagnosticsScreen: View {
    let host: String

    @State private var permissions = DiagnosticsPermissionReport()
    @State private var media = DiagnosticsMediaReport()
    @State private var connectivityChecks: [DiagnosticsCheckResult] = []
    @State private var isRunningConnectivity = false

    @State private var iceReport = DiagnosticsIceReport()
    @State private var isRunningIce = false

    @State private var logLines: [String] = []
    @State private var shareText: String?

    private let apiClient = APIClient()

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                permissionsCard
                mediaCard
                connectivityCard
                iceCard
                logsCard
            }
            .padding(16)
        }
        .navigationTitle(L10n.diagnosticsTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    shareText = buildReportText()
                } label: {
                    Image(systemName: "square.and.arrow.up")
                }
            }
        }
        .sheet(isPresented: Binding(
            get: { shareText != nil },
            set: { if !$0 { shareText = nil } }
        )) {
            if let shareText {
                ActivityView(items: [shareText])
            }
        }
        .task {
            await refreshPermissions()
            refreshMedia()
        }
    }

    private var permissionsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.diagnosticsPermissionsTitle)
                .font(.headline)
                .padding(.horizontal, 4)

            VStack(alignment: .leading, spacing: 10) {
                checkRow(title: L10n.diagnosticsPermissionCamera, passed: permissions.cameraGranted)
                checkRow(title: L10n.diagnosticsPermissionMicrophone, passed: permissions.microphoneGranted)
                checkRow(title: L10n.diagnosticsPermissionNotifications, passed: permissions.notificationsGranted)

                HStack(spacing: 10) {
                    Button(L10n.diagnosticsPermissionsRequest) {
                        Task {
                            await requestMissingPermissions()
                            await refreshPermissions()
                        }
                    }
                    .buttonStyle(.borderedProminent)

                    Button(L10n.diagnosticsRefresh) {
                        Task {
                            await refreshPermissions()
                        }
                    }
                    .buttonStyle(.bordered)
                }
            }
            .cardStyle()
        }
    }

    private var mediaCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.diagnosticsMediaTitle)
                .font(.headline)
                .padding(.horizontal, 4)

            VStack(alignment: .leading, spacing: 10) {
                checkRow(title: L10n.diagnosticsMediaAnyCamera, passed: media.hasCameraHardware)
                checkRow(title: L10n.diagnosticsMediaFrontCamera, passed: media.hasFrontCamera)
                checkRow(title: L10n.diagnosticsMediaBackCamera, passed: media.hasBackCamera)
                checkRow(title: L10n.diagnosticsMediaComposite, passed: media.isCompositeSupported)
                checkRow(title: L10n.diagnosticsMediaMicHardware, passed: media.hasMicrophoneHardware)

                if let sampleRateHz = media.sampleRateHz {
                    infoLine(title: L10n.diagnosticsMediaSampleRate, value: "\(Int(sampleRateHz)) Hz")
                }
                if let ioBufferMs = media.ioBufferMs {
                    infoLine(title: L10n.diagnosticsMediaBuffer, value: String(format: "%.1f ms", ioBufferMs))
                }

                Button(L10n.diagnosticsRefreshMedia) {
                    refreshMedia()
                }
                .buttonStyle(.bordered)
            }
            .cardStyle()
        }
    }

    private var connectivityCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.diagnosticsConnectivityTitle)
                .font(.headline)
                .padding(.horizontal, 4)

            VStack(alignment: .leading, spacing: 10) {
                ForEach(connectivityChecks) { check in
                    HStack(spacing: 10) {
                        statusDot(check.status)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text(check.title)
                                    .font(.subheadline.weight(.semibold))
                                if let latencyMs = check.latencyMs {
                                    Text("(\(latencyMs) ms)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Text(check.detail)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Button(isRunningConnectivity ? L10n.diagnosticsRunning : L10n.diagnosticsRunConnectivity) {
                    Task { await runConnectivityChecks() }
                }
                .buttonStyle(.borderedProminent)
                .disabled(isRunningConnectivity || isRunningIce)
            }
            .cardStyle()
        }
    }

    private var iceCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.diagnosticsIceTitle)
                .font(.headline)
                .padding(.horizontal, 4)

            VStack(alignment: .leading, spacing: 10) {
                checkRow(title: L10n.diagnosticsIceStun, passed: iceReport.stunPassed)
                checkRow(title: L10n.diagnosticsIceTurn, passed: iceReport.turnPassed)

                HStack(spacing: 10) {
                    Button(isRunningIce ? L10n.diagnosticsRunning : L10n.diagnosticsRunIceFull) {
                        Task { await runIceCheck(turnsOnly: false) }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isRunningIce || isRunningConnectivity)

                    Button(L10n.diagnosticsRunIceTurnsOnly) {
                        Task { await runIceCheck(turnsOnly: true) }
                    }
                    .buttonStyle(.bordered)
                    .disabled(isRunningIce || isRunningConnectivity)
                }
            }
            .cardStyle()
        }
    }

    private var logsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.diagnosticsLogsTitle)
                .font(.headline)
                .padding(.horizontal, 4)

            VStack(alignment: .leading, spacing: 10) {
                if logLines.isEmpty {
                    Text(L10n.diagnosticsLogsEmpty)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(logLines.indices, id: \.self) { index in
                        Text(logLines[index])
                            .font(.caption2.monospaced())
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .cardStyle()
        }
    }

    private func checkRow(title: String, passed: Bool) -> some View {
        HStack(spacing: 10) {
            Circle()
                .fill(passed ? Color(UIColor.systemGreen) : Color(UIColor.systemRed))
                .frame(width: 9, height: 9)
            Text(title)
                .font(.subheadline)
            Spacer()
            Text(passed ? L10n.diagnosticsStatusAvailable : L10n.diagnosticsStatusMissing)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func infoLine(title: String, value: String) -> some View {
        HStack(spacing: 10) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.caption.weight(.semibold))
        }
    }

    private func statusDot(_ status: DiagnosticsCheckStatus) -> some View {
        Circle()
            .fill(color(for: status))
            .frame(width: 9, height: 9)
    }

    private func color(for status: DiagnosticsCheckStatus) -> Color {
        switch status {
        case .idle:
            return Color(UIColor.systemGray)
        case .running:
            return Color(UIColor.systemBlue)
        case .pass:
            return Color(UIColor.systemGreen)
        case .warn:
            return Color(UIColor.systemYellow)
        case .fail:
            return Color(UIColor.systemRed)
        }
    }

    private func refreshMedia() {
        let devices = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInDualCamera, .builtInDualWideCamera, .builtInUltraWideCamera],
            mediaType: .video,
            position: .unspecified
        ).devices
        let hasFront = devices.contains { $0.position == .front }
        let hasBack = devices.contains { $0.position == .back }

        let session = AVAudioSession.sharedInstance()
        let sampleRate = session.sampleRate
        let ioBufferMs = session.ioBufferDuration * 1000.0

        media = DiagnosticsMediaReport(
            hasCameraHardware: !devices.isEmpty,
            hasFrontCamera: hasFront,
            hasBackCamera: hasBack,
            isCompositeSupported: AVCaptureMultiCamSession.isMultiCamSupported,
            hasMicrophoneHardware: session.availableInputs?.isEmpty == false,
            sampleRateHz: sampleRate > 0 ? sampleRate : nil,
            ioBufferMs: ioBufferMs > 0 ? ioBufferMs : nil
        )
    }

    private func refreshPermissions() async {
        let cameraGranted = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
        let micGranted = AVAudioSession.sharedInstance().recordPermission == .granted
        let notificationsGranted = await isNotificationPermissionGranted()

        permissions = DiagnosticsPermissionReport(
            cameraGranted: cameraGranted,
            microphoneGranted: micGranted,
            notificationsGranted: notificationsGranted
        )
    }

    private func isNotificationPermissionGranted() async -> Bool {
        await withCheckedContinuation { continuation in
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                continuation.resume(returning: settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional)
            }
        }
    }

    private func requestMissingPermissions() async {
        if !permissions.cameraGranted {
            _ = await requestCameraAccess()
        }
        if !permissions.microphoneGranted {
            _ = await requestMicAccess()
        }
        if !permissions.notificationsGranted {
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        }
    }

    private func requestCameraAccess() async -> Bool {
        await withCheckedContinuation { continuation in
            AVCaptureDevice.requestAccess(for: .video) { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    private func requestMicAccess() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    private func runConnectivityChecks() async {
        guard !isRunningConnectivity else { return }
        isRunningConnectivity = true
        defer { isRunningConnectivity = false }

        connectivityChecks = []
        appendLog("\(L10n.diagnosticsConnectivityTitle): \(host)")

        let normalizedHost = DeepLinkParser.normalizeHostValue(host) ?? host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedHost.isEmpty else {
            connectivityChecks = [
                DiagnosticsCheckResult(title: L10n.diagnosticsConnectivityHost, status: .fail, detail: L10n.settingsErrorInvalidServerHost, latencyMs: nil)
            ]
            appendLog(L10n.settingsErrorInvalidServerHost)
            return
        }

        connectivityChecks.append(await runConnectivityCheck(title: L10n.diagnosticsConnectivityRoomApi) {
            _ = try await apiClient.createRoomId(host: normalizedHost)
            return L10n.diagnosticsCheckPassed
        })

        connectivityChecks.append(await runConnectivityCheck(title: L10n.diagnosticsConnectivityWebSocket) {
            try await testWebSocket(host: normalizedHost)
            return L10n.diagnosticsCheckPassed
        })

        connectivityChecks.append(await runConnectivityCheck(title: L10n.diagnosticsConnectivitySse) {
            try await testSse(host: normalizedHost)
            return L10n.diagnosticsCheckPassed
        })

        connectivityChecks.append(await runConnectivityCheck(title: L10n.diagnosticsConnectivityDiagnosticToken) {
            _ = try await apiClient.fetchDiagnosticToken(host: normalizedHost)
            return L10n.diagnosticsCheckPassed
        })

        connectivityChecks.append(await runConnectivityCheck(title: L10n.diagnosticsConnectivityTurnCredentials) {
            let token = try await apiClient.fetchDiagnosticToken(host: normalizedHost)
            _ = try await apiClient.fetchTurnCredentials(host: normalizedHost, token: token)
            return L10n.diagnosticsCheckPassed
        })
    }

    private func runConnectivityCheck(
        title: String,
        run: @escaping () async throws -> String
    ) async -> DiagnosticsCheckResult {
        let start = Date()
        do {
            let detail = try await run()
            let latencyMs = Int(Date().timeIntervalSince(start) * 1000)
            appendLog("\(title): OK (\(latencyMs) ms)")
            return DiagnosticsCheckResult(title: title, status: .pass, detail: detail, latencyMs: latencyMs)
        } catch {
            appendLog("\(title): \(error.localizedDescription)")
            return DiagnosticsCheckResult(title: title, status: .fail, detail: error.localizedDescription, latencyMs: nil)
        }
    }

    private func testWebSocket(host: String) async throws {
        var components = URLComponents()
        components.scheme = "wss"
        components.host = host
        components.path = "/ws"
        guard let url = components.url else { throw APIError.invalidHost }

        let task = URLSession.shared.webSocketTask(with: url)
        task.resume()
        try await Task.sleep(nanoseconds: 600_000_000)
        task.cancel(with: .goingAway, reason: nil)
    }

    private func testSse(host: String) async throws {
        let sid = "diag-\(UUID().uuidString)"

        var getComponents = URLComponents()
        getComponents.scheme = "https"
        getComponents.host = host
        getComponents.path = "/sse"
        getComponents.queryItems = [URLQueryItem(name: "sid", value: sid)]
        guard let getURL = getComponents.url else { throw APIError.invalidHost }

        let (bytes, response) = try await URLSession.shared.bytes(from: getURL)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("SSE open failed")
        }
        _ = try await bytes.lines.first(where: { _ in true })

        var postComponents = URLComponents()
        postComponents.scheme = "https"
        postComponents.host = host
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

    private func runIceCheck(turnsOnly: Bool) async {
        guard !isRunningIce else { return }
        isRunningIce = true
        defer { isRunningIce = false }

        let normalizedHost = DeepLinkParser.normalizeHostValue(host) ?? host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedHost.isEmpty else {
            iceReport = DiagnosticsIceReport(stunPassed: false, turnPassed: false)
            appendLog(L10n.settingsErrorInvalidServerHost)
            return
        }

        do {
            appendLog(turnsOnly ? L10n.diagnosticsRunIceTurnsOnly : L10n.diagnosticsRunIceFull)
            let token = try await apiClient.fetchDiagnosticToken(host: normalizedHost)
            let credentials = try await apiClient.fetchTurnCredentials(host: normalizedHost, token: token)
            let urls = turnsOnly ? credentials.uris.filter { $0.lowercased().hasPrefix("turns:") } : credentials.uris
            let probe = await runIceProbe(
                urls: urls,
                username: credentials.username,
                credential: credentials.password,
                onCandidateLog: { candidate in
                    appendLog("ICE: \(candidate)")
                }
            )
            iceReport = DiagnosticsIceReport(stunPassed: probe.stunPassed, turnPassed: probe.turnPassed)
            appendLog("ICE done: STUN=\(probe.stunPassed) TURN=\(probe.turnPassed)")
        } catch {
            iceReport = DiagnosticsIceReport(stunPassed: false, turnPassed: false)
            appendLog("ICE: \(error.localizedDescription)")
        }
    }

    private func appendLog(_ line: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let prefix = formatter.string(from: Date())
        logLines.append("[\(prefix)] \(line)")
        if logLines.count > 150 {
            logLines.removeFirst(logLines.count - 150)
        }
    }

    private func buildReportText() -> String {
        let connectivityText = connectivityChecks.map { check in
            let latency = check.latencyMs.map { " (\($0)ms)" } ?? ""
            return "- \(check.title): \(check.detail)\(latency)"
        }.joined(separator: "\n")
        let allLogs = logLines.joined(separator: "\n")

        return """
        \(L10n.diagnosticsTitle)
        host: \(host)

        [Permissions]
        camera: \(permissions.cameraGranted)
        microphone: \(permissions.microphoneGranted)
        notifications: \(permissions.notificationsGranted)

        [Media]
        hasCameraHardware: \(media.hasCameraHardware)
        hasFrontCamera: \(media.hasFrontCamera)
        hasBackCamera: \(media.hasBackCamera)
        isCompositeSupported: \(media.isCompositeSupported)
        hasMicrophoneHardware: \(media.hasMicrophoneHardware)
        sampleRateHz: \(media.sampleRateHz?.description ?? "n/a")
        ioBufferMs: \(media.ioBufferMs?.description ?? "n/a")

        [Connectivity]
        \(connectivityText)

        [ICE]
        stunPassed: \(iceReport.stunPassed)
        turnPassed: \(iceReport.turnPassed)

        [Logs]
        \(allLogs)
        """
    }
}

private struct IceProbeResult {
    let stunPassed: Bool
    let turnPassed: Bool
    let logs: [String]
}

private extension DiagnosticsScreen {
    @MainActor
    func runIceProbe(urls: [String], username: String, credential: String, onCandidateLog: @escaping (String) -> Void) async -> IceProbeResult {
#if canImport(WebRTC)
        guard !urls.isEmpty else {
            return IceProbeResult(stunPassed: false, turnPassed: false, logs: [L10n.diagnosticsIceNoServers])
        }
        let probe = IceGatheringProbe()
        return await probe.run(urls: urls, username: username, credential: credential, onCandidateLog: onCandidateLog)
#else
        return IceProbeResult(stunPassed: false, turnPassed: false, logs: [L10n.errorSomethingWentWrong])
#endif
    }
}

#if canImport(WebRTC)
@MainActor
private final class IceGatheringProbe: NSObject, RTCPeerConnectionDelegate {
    private var continuation: CheckedContinuation<IceProbeResult, Never>?
    private var peerConnection: RTCPeerConnection?
    private var hasSrflx = false
    private var hasRelay = false
    private var logs: [String] = []
    private var finished = false
    private var onCandidateLog: ((String) -> Void)?

    func run(urls: [String], username: String, credential: String, onCandidateLog: @escaping (String) -> Void) async -> IceProbeResult {
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
        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        let factory = RTCPeerConnectionFactory(encoderFactory: encoderFactory, decoderFactory: decoderFactory)

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
        continuation?.resume(returning: IceProbeResult(stunPassed: hasSrflx, turnPassed: hasRelay, logs: logs))
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
        if newState == .complete {
            finish()
        }
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

private extension View {
    func cardStyle() -> some View {
        self
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground).opacity(0.8))
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
