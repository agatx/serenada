import Foundation
@testable import SerenadaCore

@MainActor
final class FakeSignaling: SessionSignaling {
    weak var listener: SignalingClientListener?

    private(set) var connectCalls: [String] = []
    private(set) var sentMessages: [SignalingMessage] = []
    private(set) var closeCalls = 0
    private(set) var connected = false

    func connect(host: String) {
        connectCalls.append(host)
    }

    func isConnected() -> Bool { connected }

    func send(_ message: SignalingMessage) {
        sentMessages.append(message)
    }

    func close() {
        closeCalls += 1
        connected = false
    }

    func recordPong() {}

    // MARK: - Test Drivers

    func simulateOpen(transport: String = "ws") {
        connected = true
        listener?.onOpen(activeTransport: transport)
    }

    func simulateMessage(_ message: SignalingMessage) {
        listener?.onMessage(message)
    }

    func simulateClosed(reason: String = "test") {
        connected = false
        listener?.onClosed(reason: reason)
    }

    func sentMessages(ofType type: String) -> [SignalingMessage] {
        sentMessages.filter { $0.type == type }
    }
}
