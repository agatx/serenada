import Foundation
@testable import SerenadaCore

@MainActor
final class FakeSessionSignaling: SessionSignaling {
    weak var listener: SignalingClientListener?

    private(set) var connectHosts: [String] = []
    private(set) var sentMessages: [SignalingMessage] = []
    private(set) var closeCalls = 0
    private(set) var recordPongCalls = 0
    var connected = false

    func connect(host: String) {
        connectHosts.append(host)
    }

    func isConnected() -> Bool {
        connected
    }

    func send(_ message: SignalingMessage) {
        sentMessages.append(message)
    }

    func close() {
        closeCalls += 1
        connected = false
    }

    func recordPong() {
        recordPongCalls += 1
    }

    func clearSentMessages() {
        sentMessages.removeAll()
    }

    func simulateOpen(activeTransport: String = "ws") {
        connected = true
        listener?.onOpen(activeTransport: activeTransport)
    }

    func simulateMessage(_ message: SignalingMessage) {
        listener?.onMessage(message)
    }

    func simulateClosed(reason: String = "test") {
        connected = false
        listener?.onClosed(reason: reason)
    }
}
