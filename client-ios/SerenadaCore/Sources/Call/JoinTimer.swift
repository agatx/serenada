import Foundation

@MainActor
final class JoinTimer {
    private let clock: SessionClock
    private let getRoomId: () -> String
    private let getJoinAttemptSerial: () -> Int64
    private let getInternalPhase: () -> CallPhase
    private let hasJoinSignalStarted: () -> Bool
    private let hasJoinAcknowledged: () -> Bool
    private let isSignalingConnected: () -> Bool
    private let onJoinTimeout: () -> Void
    private let ensureSignalingConnection: () -> Void
    private let onRecovery: (_ participantHint: Int?, _ preferInCall: Bool) -> Void

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
        ensureSignalingConnection: @escaping () -> Void,
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
        self.ensureSignalingConnection = ensureSignalingConnection
        self.onRecovery = onRecovery
    }

    func scheduleTimeout(roomId: String, joinAttempt: Int64) {
        clearTimeout()

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

    func clearTimeout() {
        joinTimeoutTask?.cancel()
        joinTimeoutTask = nil
    }

    func scheduleKickstart(roomId: String, joinAttempt: Int64) {
        clearKickstart()

        joinConnectKickstartTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: WebRtcResilience.joinConnectKickstartNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.getInternalPhase() == .joining else { return }
            guard self.getRoomId() == roomId else { return }
            guard self.getJoinAttemptSerial() == joinAttempt else { return }
            guard !self.hasJoinSignalStarted() else { return }
            self.ensureSignalingConnection()
        }
    }

    func clearKickstart() {
        joinConnectKickstartTask?.cancel()
        joinConnectKickstartTask = nil
    }

    func scheduleRecovery(for roomId: String) {
        clearRecovery()

        joinRecoveryTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: WebRtcResilience.joinRecoveryNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.getRoomId() == roomId else { return }
            guard self.isSignalingConnected() else { return }
            guard self.hasJoinAcknowledged() else {
                if self.getInternalPhase() == .joining {
                    self.ensureSignalingConnection()
                }
                return
            }
            self.onRecovery(nil, false)
        }
    }

    func clearRecovery() {
        joinRecoveryTask?.cancel()
        joinRecoveryTask = nil
    }

    func clearAll() {
        clearTimeout()
        clearKickstart()
        clearRecovery()
    }
}
