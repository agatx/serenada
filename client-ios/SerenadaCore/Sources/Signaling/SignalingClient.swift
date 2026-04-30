import Foundation

@MainActor
protocol SignalingClientListener: AnyObject {
    func onOpen(activeTransport: String)
    func onMessage(_ message: SignalingMessage)
    func onClosed(reason: String)
}

@MainActor
final class SignalingClient: SessionSignaling {
    weak var listener: SignalingClientListener?

    private let transportOrder: [TransportKind]
    private var transportConnectedOnce: [TransportKind: Bool] = [.ws: false, .sse: false]

    private let wsTransport: SignalingTransport
    private let sseTransport: SignalingTransport
    private var transports: [SignalingTransport] { [wsTransport, sseTransport] }

    private let clock: SessionClock

    private var connected = false
    private var connecting = false
    private var pingTask: Task<Void, Never>?
    private var connectTimeoutTask: Task<Void, Never>?
    private var forcePingTask: Task<Void, Never>?
    private var pongSeq: Int64 = 0

    private var connectionAttemptId = 0
    private var activeAttemptId = 0
    private var transportIndex = 0
    private var activeTransport: TransportKind?
    private var activeTransportImpl: SignalingTransport?
    private var normalizedHost: String?
    private var closedByClient = false
    private var wsConsecutiveFailures = 0
    private var lastPongAtMs: Int64 = 0
    private var missedPongs = 0

    init(
        forceSseSignaling: Bool = false,
        clock: SessionClock? = nil,
        transportFactory: ((TransportKind) -> SignalingTransport)? = nil
    ) {
        self.clock = clock ?? LiveSessionClock()
        self.transportOrder = forceSseSignaling ? [.sse] : [.ws, .sse]
        self.lastPongAtMs = self.clock.nowMs()
        if let factory = transportFactory {
            self.wsTransport = factory(.ws)
            self.sseTransport = factory(.sse)
        } else {
            self.wsTransport = WebSocketSignalingTransport()
            self.sseTransport = SseSignalingTransport()
        }
    }

    func connect(host: String) {
        if connected || connecting { return }

        guard let normalized = normalizeHost(host) else {
            listener?.onClosed(reason: "invalid_host")
            return
        }

        resetTransportState()
        if normalized != normalizedHost {
            resetTransportSessions()
        }

        normalizedHost = normalized
        closedByClient = false
        connectWithTransport(index: transportIndex)
    }

    func isConnected() -> Bool {
        connected
    }

    func send(_ message: SignalingMessage) {
        guard connected else { return }
        activeTransportImpl?.send(message)
    }

    func close() {
        closedByClient = true
        stopPing()
        clearConnectTimeout()
        clearForcePing()

        connecting = false
        connected = false
        activeAttemptId = -abs(activeAttemptId)
        activeTransport = nil
        activeTransportImpl = nil

        closeTransports()
        normalizedHost = nil
        resetTransportState()
        resetTransportSessions()
    }

    func recordPong() {
        lastPongAtMs = clock.nowMs()
        pongSeq += 1
        missedPongs = 0
    }

    func forcePingWithDeadline(timeoutMs: Int) {
        guard connected, let kind = activeTransport else { return }
        clearForcePing()

        let attemptId = activeAttemptId
        let pongSeqAtPing = pongSeq
        send(SignalingMessage(type: "ping"))

        forcePingTask = Task { [weak self] in
            guard let self else { return }
            try? await self.clock.sleep(nanoseconds: UInt64(max(timeoutMs, 0)) * 1_000_000)
            if Task.isCancelled { return }
            guard self.isAttemptActive(attemptId: attemptId, kind: kind) else { return }
            guard self.connected else { return }
            if self.pongSeq > pongSeqAtPing { return }
            self.handleTransportClosed(attemptId: attemptId, kind: kind, reason: "foreground_force_ping_timeout")
        }
    }

    private func clearForcePing() {
        forcePingTask?.cancel()
        forcePingTask = nil
    }

    private func startPing() {
        stopPing()
        lastPongAtMs = clock.nowMs()
        missedPongs = 0
        pingTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                try? await self.clock.sleep(nanoseconds: WebRtcResilience.pingIntervalNs)
                if Task.isCancelled { return }
                if !self.connected { continue }

                // Check for missed pongs
                let elapsed = self.clock.nowMs() - self.lastPongAtMs
                if elapsed > Int64(WebRtcResilience.pingIntervalMs) {
                    self.missedPongs += 1
                    if self.missedPongs >= WebRtcResilience.pongMissThreshold {
                        self.missedPongs = 0
                        guard let kind = self.activeTransport else { continue }
                        let attemptId = self.activeAttemptId
                        self.handleTransportClosed(attemptId: attemptId, kind: kind, reason: "pong_timeout")
                        return
                    }
                }

                self.send(SignalingMessage(type: "ping"))
            }
        }
    }

    private func stopPing() {
        pingTask?.cancel()
        pingTask = nil
    }

    private func scheduleConnectTimeout(attemptId: Int) {
        clearConnectTimeout()

        connectTimeoutTask = Task { [weak self] in
            guard let self else { return }
            try? await self.clock.sleep(nanoseconds: WebRtcResilience.connectTimeoutNs)
            guard !Task.isCancelled else { return }
            guard let kind = self.activeTransport else { return }
            guard self.isAttemptActive(attemptId: attemptId, kind: kind) else { return }
            guard !self.connected else { return }

            self.handleTransportClosed(attemptId: attemptId, kind: kind, reason: "timeout")
        }
    }

    private func clearConnectTimeout() {
        connectTimeoutTask?.cancel()
        connectTimeoutTask = nil
    }

    private func connectWithTransport(index: Int) {
        if connected || connecting { return }
        guard let host = normalizedHost else { return }
        guard let kind = transportOrder[safe: index] else { return }

        transportIndex = index
        activeTransport = kind
        connecting = true

        connectionAttemptId += 1
        let attemptId = connectionAttemptId
        activeAttemptId = attemptId

        closeTransports()

        let transport = transportForKind(kind)
        activeTransportImpl = transport

        transport.connect(
            host: host,
            onOpen: { [weak self] in
                Task { @MainActor in
                    self?.handleTransportOpen(attemptId: attemptId, kind: kind)
                }
            },
            onMessage: { [weak self] message in
                Task { @MainActor in
                    guard let self else { return }
                    guard self.isAttemptActive(attemptId: attemptId, kind: kind) else { return }
                    self.listener?.onMessage(message)
                }
            },
            onClosed: { [weak self] reason in
                Task { @MainActor in
                    self?.handleTransportClosed(attemptId: attemptId, kind: kind, reason: reason)
                }
            }
        )

        scheduleConnectTimeout(attemptId: attemptId)
    }

    private func handleTransportOpen(attemptId: Int, kind: TransportKind) {
        guard isAttemptActive(attemptId: attemptId, kind: kind) else { return }

        clearConnectTimeout()
        connecting = false
        connected = true
        transportConnectedOnce[kind] = true
        if kind == .ws { wsConsecutiveFailures = 0 }
        lastPongAtMs = clock.nowMs()
        missedPongs = 0

        listener?.onOpen(activeTransport: kind.wireName)
        startPing()
    }

    private func handleTransportClosed(attemptId: Int, kind: TransportKind, reason: String) {
        guard isAttemptActive(attemptId: attemptId, kind: kind) else { return }

        clearConnectTimeout()
        stopPing()

        connecting = false
        connected = false
        activeAttemptId = -abs(attemptId)
        activeTransport = nil
        activeTransportImpl = nil
        if kind == .ws { wsConsecutiveFailures += 1 }
        closeTransports()

        if closedByClient {
            return
        }

        if shouldFallback(kind: kind, reason: reason), tryNextTransport(reason: reason) {
            return
        }

        listener?.onClosed(reason: reason)
    }

    private func shouldFallback(kind: TransportKind, reason: String) -> Bool {
        if transportOrder.count <= 1 { return false }
        if transportIndex >= transportOrder.count - 1 { return false }
        if reason == "unsupported" || reason == "timeout" { return true }
        if transportConnectedOnce[kind] != true { return true }
        // Allow SSE fallback after consecutive WS failures even if WS connected before
        if kind == .ws && wsConsecutiveFailures >= WebRtcResilience.wsFallbackConsecutiveFailures { return true }
        return false
    }

    @discardableResult
    private func tryNextTransport(reason: String) -> Bool {
        let nextIndex = transportIndex + 1
        guard transportOrder[safe: nextIndex] != nil else { return false }
        transportIndex = nextIndex
        connectWithTransport(index: nextIndex)
        return true
    }

    private func isAttemptActive(attemptId: Int, kind: TransportKind) -> Bool {
        activeAttemptId == attemptId && activeTransport == kind
    }

    private func closeTransports() {
        transports.forEach { $0.close() }
    }

    private func resetTransportSessions() {
        transports.forEach { $0.resetSession() }
    }

    private func resetTransportState() {
        transportIndex = 0
        transportConnectedOnce = [.ws: false, .sse: false]
    }

    private func normalizeHost(_ hostInput: String) -> String? {
        let host = hostInput
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        return host.isEmpty ? nil : host
    }

    private func transportForKind(_ kind: TransportKind) -> SignalingTransport {
        switch kind {
        case .ws:
            return wsTransport
        case .sse:
            return sseTransport
        }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        guard indices.contains(index) else { return nil }
        return self[index]
    }
}
