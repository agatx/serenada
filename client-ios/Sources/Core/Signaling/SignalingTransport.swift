import Foundation

enum TransportKind: String, CaseIterable {
    case ws
    case sse

    var wireName: String { rawValue }
}

protocol SignalingTransport: AnyObject {
    var kind: TransportKind { get }

    func connect(
        host: String,
        onOpen: @escaping () -> Void,
        onMessage: @escaping (SignalingMessage) -> Void,
        onClosed: @escaping (String) -> Void
    )

    func send(_ message: SignalingMessage)
    func close()
    func resetSession()
}

extension SignalingTransport {
    func resetSession() {}
}
