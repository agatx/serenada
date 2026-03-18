import Foundation

public enum TransportKind: String, CaseIterable {
    case ws
    case sse

    public var wireName: String { rawValue }
}

public protocol SignalingTransport: AnyObject {
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
    public func resetSession() {}
}
