import Foundation
#if canImport(WebRTC)
import WebRTC
#endif

#if canImport(WebRTC)
func mediaKind(for stat: RTCStatistics) -> String? {
    let kind = memberString(stat, key: "kind") ?? memberString(stat, key: "mediaType")
    if kind == "audio" || kind == "video" {
        return kind
    }
    return nil
}

func memberString(_ stat: RTCStatistics?, key: String) -> String? {
    guard let value = stat?.values[key] else { return nil }
    if let str = value as? String {
        let trimmed = str.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
    let text = value.description.trimmingCharacters(in: .whitespacesAndNewlines)
    return text.isEmpty ? nil : text
}

func memberDouble(_ stat: RTCStatistics?, key: String) -> Double? {
    guard let value = stat?.values[key] else { return nil }
    if let number = value as? NSNumber {
        return number.doubleValue
    }
    if let text = value as? String {
        return Double(text)
    }
    return nil
}

func memberInt64(_ stat: RTCStatistics?, key: String) -> Int64? {
    guard let value = stat?.values[key] else { return nil }
    if let number = value as? NSNumber {
        return number.int64Value
    }
    if let text = value as? String {
        return Int64(text)
    }
    return nil
}

func memberBool(_ stat: RTCStatistics?, key: String) -> Bool? {
    guard let value = stat?.values[key] else { return nil }
    if let number = value as? NSNumber {
        return number.boolValue
    }
    if let text = value as? String {
        switch text.lowercased() {
        case "true":
            return true
        case "false":
            return false
        default:
            return nil
        }
    }
    return nil
}

func calculateBitrateKbps(previousBytes: Int64, currentBytes: Int64, elapsedSeconds: Double) -> Double? {
    guard elapsedSeconds > 0, currentBytes >= previousBytes else { return nil }
    let bits = Double(currentBytes - previousBytes) * 8
    return bits / elapsedSeconds / 1000.0
}

func ratioPercent(numerator: Int64, denominator: Int64) -> Double? {
    guard denominator > 0 else { return nil }
    return (Double(numerator) / Double(denominator)) * 100.0
}

func positiveRatePerMinute(currentValue: Int64, previousValue: Int64, elapsedSeconds: Double) -> Double? {
    guard elapsedSeconds > 0, currentValue >= previousValue else { return nil }
    return (Double(currentValue - previousValue) / elapsedSeconds) * 60.0
}

func connectionStateString(_ state: RTCPeerConnectionState) -> String {
    peerConnectionState(state).rawValue
}

func peerConnectionState(_ state: RTCPeerConnectionState) -> SerenadaPeerConnectionState {
    switch state {
    case .new: return .new
    case .connecting: return .connecting
    case .connected: return .connected
    case .disconnected: return .disconnected
    case .failed: return .failed
    case .closed: return .closed
    @unknown default:
        // Future RTCPeerConnectionState values are mapped to .new as a safe default.
        // This avoids crashing on SDK upgrades when the WebRTC framework adds new states.
        return .new
    }
}

func iceConnectionStateString(_ state: RTCIceConnectionState) -> String {
    switch state {
    case .new:
        return "NEW"
    case .checking:
        return "CHECKING"
    case .connected:
        return "CONNECTED"
    case .completed:
        return "COMPLETED"
    case .failed:
        return "FAILED"
    case .disconnected:
        return "DISCONNECTED"
    case .closed:
        return "CLOSED"
    case .count:
        return "COUNT"
    @unknown default:
        return "UNKNOWN"
    }
}

func signalingStateString(_ state: RTCSignalingState) -> String {
    switch state {
    case .stable:
        return "STABLE"
    case .haveLocalOffer:
        return "HAVE_LOCAL_OFFER"
    case .haveLocalPrAnswer:
        return "HAVE_LOCAL_PRANSWER"
    case .haveRemoteOffer:
        return "HAVE_REMOTE_OFFER"
    case .haveRemotePrAnswer:
        return "HAVE_REMOTE_PRANSWER"
    case .closed:
        return "CLOSED"
    @unknown default:
        return "UNKNOWN"
    }
}
#endif
