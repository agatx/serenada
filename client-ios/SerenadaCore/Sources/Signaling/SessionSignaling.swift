import Foundation

@MainActor
protocol SessionSignaling: AnyObject {
    var listener: SignalingClientListener? { get set }
    func connect(host: String)
    func isConnected() -> Bool
    func send(_ message: SignalingMessage)
    func close()
    func recordPong()

    /// Send a synthetic ping and arm a short deadline; if no pong arrives,
    /// force-close the transport so the normal reconnect path kicks in.
    /// Used by the session's foreground lifecycle hook so a stalled WS that
    /// the OS killed during background gets detected immediately instead of
    /// waiting for the regular `pingIntervalMs` cycle.
    func forcePingWithDeadline(timeoutMs: Int)
}

extension SessionSignaling {
    func forcePingWithDeadline(timeoutMs: Int) {}
}
