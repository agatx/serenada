import AVFoundation
import Foundation

@MainActor
final class JoinFlowCoordinator {
    private let clock: SessionClock

    // State readers
    private let getRoomId: () -> String
    private let getJoinAttemptSerial: () -> Int64
    private let getInternalPhase: () -> CallPhase
    private let hasJoinSignalStarted: () -> Bool
    private let hasJoinAcknowledged: () -> Bool
    private let isSignalingConnected: () -> Bool

    // Callbacks
    private let onJoinTimeout: () -> Void
    private let onEnsureSignalingConnection: () -> Void
    private let onRecovery: (_ participantHint: Int?, _ preferInCall: Bool) -> Void

    // Timer state
    private var joinTimeoutTask: Task<Void, Never>?
    private var joinConnectKickstartTask: Task<Void, Never>?
    private var joinRecoveryTask: Task<Void, Never>?

    init(
        clock: SessionClock,
        getRoomId: @escaping () -> String,
        getJoinAttemptSerial: @escaping () -> Int64,
        getInternalPhase: @escaping () -> CallPhase,
        hasJoinSignalStarted: @escaping () -> Bool,
        hasJoinAcknowledged: @escaping () -> Bool,
        isSignalingConnected: @escaping () -> Bool,
        onJoinTimeout: @escaping () -> Void,
        onEnsureSignalingConnection: @escaping () -> Void,
        onRecovery: @escaping (_ participantHint: Int?, _ preferInCall: Bool) -> Void
    ) {
        self.clock = clock
        self.getRoomId = getRoomId
        self.getJoinAttemptSerial = getJoinAttemptSerial
        self.getInternalPhase = getInternalPhase
        self.hasJoinSignalStarted = hasJoinSignalStarted
        self.hasJoinAcknowledged = hasJoinAcknowledged
        self.isSignalingConnected = isSignalingConnected
        self.onJoinTimeout = onJoinTimeout
        self.onEnsureSignalingConnection = onEnsureSignalingConnection
        self.onRecovery = onRecovery
    }

    // MARK: - Join Timeout

    func scheduleJoinTimeout(roomId: String, joinAttempt: Int64) {
        clearJoinTimeout()

        joinTimeoutTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: WebRtcResilience.joinHardTimeoutNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.getInternalPhase() == .joining else { return }
            guard self.getRoomId() == roomId else { return }
            guard self.getJoinAttemptSerial() == joinAttempt else { return }
            self.onJoinTimeout()
        }
    }

    func clearJoinTimeout() {
        joinTimeoutTask?.cancel()
        joinTimeoutTask = nil
    }

    // MARK: - Join Connect Kickstart

    func scheduleJoinConnectKickstart(roomId: String, joinAttempt: Int64) {
        clearJoinConnectKickstart()

        joinConnectKickstartTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: WebRtcResilience.joinConnectKickstartNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.getInternalPhase() == .joining else { return }
            guard self.getRoomId() == roomId else { return }
            guard self.getJoinAttemptSerial() == joinAttempt else { return }
            guard !self.hasJoinSignalStarted() else { return }
            self.onEnsureSignalingConnection()
        }
    }

    func clearJoinConnectKickstart() {
        joinConnectKickstartTask?.cancel()
        joinConnectKickstartTask = nil
    }

    // MARK: - Join Recovery

    func scheduleJoinRecovery(for roomId: String) {
        clearJoinRecovery()

        joinRecoveryTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: WebRtcResilience.joinRecoveryNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.getRoomId() == roomId else { return }
            guard self.isSignalingConnected() else { return }
            guard self.hasJoinAcknowledged() else {
                if self.getInternalPhase() == .joining {
                    self.onEnsureSignalingConnection()
                }
                return
            }
            self.onRecovery(nil, false)
        }
    }

    func clearJoinRecovery() {
        joinRecoveryTask?.cancel()
        joinRecoveryTask = nil
    }

    // MARK: - Clear All Timers

    func clearAllTimers() {
        clearJoinTimeout()
        clearJoinConnectKickstart()
        clearJoinRecovery()
    }

    // MARK: - Permissions

    static func missingPermissions() -> [MediaCapability] {
        var required: [MediaCapability] = []
        if AVCaptureDevice.authorizationStatus(for: .video) != .authorized {
            required.append(.camera)
        }
        if AVAudioSession.sharedInstance().recordPermission != .granted {
            required.append(.microphone)
        }
        return required
    }
}
