import Foundation

@MainActor
protocol SessionSignaling: AnyObject {
    var listener: SignalingClientListener? { get set }
    func connect(host: String)
    func isConnected() -> Bool
    func send(_ message: SignalingMessage)
    func close()
    func recordPong()
}
