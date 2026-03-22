import Foundation

@MainActor
final class ConnectionStatusTracker {
    private let clock: SessionClock
    private let getInternalPhase: () -> CallPhase
    private let getDiagnostics: () -> CallDiagnostics
    private let getCurrentStatus: () -> SerenadaConnectionStatus
    private let setConnectionStatus: (SerenadaConnectionStatus) -> Void

    private var connectionStatusRetryingTask: Task<Void, Never>?
    private let connectionStatusRetryingDelayNs: UInt64 = 10_000_000_000

    init(
        clock: SessionClock,
        getInternalPhase: @escaping () -> CallPhase,
        getDiagnostics: @escaping () -> CallDiagnostics,
        getCurrentStatus: @escaping () -> SerenadaConnectionStatus,
        setConnectionStatus: @escaping (SerenadaConnectionStatus) -> Void
    ) {
        self.clock = clock
        self.getInternalPhase = getInternalPhase
        self.getDiagnostics = getDiagnostics
        self.getCurrentStatus = getCurrentStatus
        self.setConnectionStatus = setConnectionStatus
    }

    func update() {
        guard getInternalPhase() == .inCall else {
            reset()
            return
        }

        if isConnectionDegraded() {
            markConnectionDegraded()
            return
        }

        reset()
    }

    func reset() {
        cancelTimer()
        let current = getCurrentStatus()
        if current != .connected {
            setConnectionStatus(.connected)
        }
    }

    func cancelTimer() {
        connectionStatusRetryingTask?.cancel()
        connectionStatusRetryingTask = nil
    }

    func isConnectionDegraded() -> Bool {
        let diag = getDiagnostics()
        return !diag.isSignalingConnected ||
            diag.iceConnectionState == .disconnected ||
            diag.iceConnectionState == .failed ||
            diag.peerConnectionState == .disconnected ||
            diag.peerConnectionState == .failed
    }

    private func markConnectionDegraded() {
        guard getInternalPhase() == .inCall else {
            reset()
            return
        }

        switch getCurrentStatus() {
        case .connected:
            setConnectionStatus(.recovering)
            scheduleRetryingTimer()
        case .recovering:
            scheduleRetryingTimer()
        case .retrying:
            break
        }
    }

    private func scheduleRetryingTimer() {
        guard connectionStatusRetryingTask == nil else { return }

        connectionStatusRetryingTask = Task { [weak self] in
            guard let self else { return }
            try? await self.clock.sleep(nanoseconds: self.connectionStatusRetryingDelayNs)
            guard !Task.isCancelled else { return }
            guard self.getInternalPhase() == .inCall else {
                self.reset()
                return
            }
            guard self.getCurrentStatus() == .recovering else { return }
            self.connectionStatusRetryingTask = nil
            self.setConnectionStatus(.retrying)
        }
    }
}
