import Foundation
@testable import SerenadaCore

@MainActor
final class FakeSignalingTransport: SignalingTransport {
    let kind: TransportKind

    private(set) var connectCalls = 0
    private(set) var closeCalls = 0
    private(set) var sentMessages: [SignalingMessage] = []

    private var onOpen: (() -> Void)?
    private var onMessage: ((SignalingMessage) -> Void)?
    private var onClosed: ((String) -> Void)?

    init(kind: TransportKind) {
        self.kind = kind
    }

    func connect(
        host: String,
        onOpen: @escaping () -> Void,
        onMessage: @escaping (SignalingMessage) -> Void,
        onClosed: @escaping (String) -> Void
    ) {
        connectCalls += 1
        self.onOpen = onOpen
        self.onMessage = onMessage
        self.onClosed = onClosed
    }

    func send(_ message: SignalingMessage) {
        sentMessages.append(message)
    }

    func close() {
        closeCalls += 1
    }

    // MARK: - Test Drivers

    func simulateOpen() {
        onOpen?()
    }

    func simulateClose(_ reason: String = "transport-closed") {
        onClosed?(reason)
    }

    func simulateMessage(_ message: SignalingMessage) {
        onMessage?(message)
    }

    func clearSentMessages() {
        sentMessages.removeAll()
    }
}
