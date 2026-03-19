import AVFoundation
import SerenadaCallUI
import SerenadaCore
import SwiftUI
import UserNotifications

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

struct DiagnosticsScreen: View {
    let host: String

    @State private var permissions = DiagnosticsPermissionReport()
    @State private var media = DiagnosticsMediaReport()
    @State private var connectivityChecks: [DiagnosticsCheckResult] = []
    @State private var isRunningConnectivity = false

    @State private var iceReport = IceProbeReport(stunPassed: false, turnPassed: false, logs: [])
    @State private var isRunningIce = false

    @State private var logLines: [String] = []
    @State private var shareText: String?

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

    // MARK: - Helpers

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

    // MARK: - Permissions & media (local iOS APIs only)

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

    // MARK: - Connectivity (delegates to SerenadaDiagnostics)

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

        let diag = SerenadaDiagnostics(config: SerenadaConfig(serverHost: normalizedHost))
        let report = await diag.runConnectivityChecks()

        let titles = [
            L10n.diagnosticsConnectivityRoomApi,
            L10n.diagnosticsConnectivityWebSocket,
            L10n.diagnosticsConnectivitySse,
            L10n.diagnosticsConnectivityDiagnosticToken,
            L10n.diagnosticsConnectivityTurnCredentials
        ]
        let outcomes = [report.roomApi, report.webSocket, report.sse, report.diagnosticToken, report.turnCredentials]

        for (title, outcome) in zip(titles, outcomes) {
            let result: DiagnosticsCheckResult
            switch outcome {
            case .notRun:
                result = DiagnosticsCheckResult(title: title, status: .idle, detail: "-", latencyMs: nil)
            case .passed(let latencyMs):
                appendLog("\(title): OK (\(latencyMs) ms)")
                result = DiagnosticsCheckResult(title: title, status: .pass, detail: L10n.diagnosticsCheckPassed, latencyMs: latencyMs)
            case .failed(let error):
                appendLog("\(title): \(error)")
                result = DiagnosticsCheckResult(title: title, status: .fail, detail: error, latencyMs: nil)
            }
            connectivityChecks.append(result)
        }
    }

    // MARK: - ICE (delegates to SerenadaDiagnostics)

    private func runIceCheck(turnsOnly: Bool) async {
        guard !isRunningIce else { return }
        isRunningIce = true
        defer { isRunningIce = false }

        let normalizedHost = DeepLinkParser.normalizeHostValue(host) ?? host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedHost.isEmpty else {
            iceReport = IceProbeReport(stunPassed: false, turnPassed: false, logs: [])
            appendLog(L10n.settingsErrorInvalidServerHost)
            return
        }

        appendLog(turnsOnly ? L10n.diagnosticsRunIceTurnsOnly : L10n.diagnosticsRunIceFull)
        let diag = SerenadaDiagnostics(config: SerenadaConfig(serverHost: normalizedHost))
        let report = await diag.runIceProbe(turnsOnly: turnsOnly) { candidate in
            appendLog("ICE: \(candidate)")
        }
        iceReport = report
        appendLog("ICE done: STUN=\(report.stunPassed) TURN=\(report.turnPassed)")
    }

    // MARK: - Logging

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

private extension View {
    func cardStyle() -> some View {
        self
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground).opacity(0.8))
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
