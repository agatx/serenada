import Foundation

/// Typed peer connection state, replacing raw String.
/// Values match WebRTC RTCPeerConnectionState (UPPER_CASE for cross-platform wire parity with Android).
public enum SerenadaPeerConnectionState: String, Codable, Equatable, Sendable {
    case new = "NEW"
    case connecting = "CONNECTING"
    case connected = "CONNECTED"
    case disconnected = "DISCONNECTED"
    case failed = "FAILED"
    case closed = "CLOSED"
}
