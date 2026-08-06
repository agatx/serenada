import Foundation
import WebRTC

func mediaKind(for stat: RTCStatistics) -> String? {
    let kind = memberString(stat, key: "kind") ?? memberString(stat, key: "mediaType")
    if kind == "audio" || kind == "video" {
        return kind
    }
    return nil
}

func resolveCodecMimeType(
    for rtpStat: RTCStatistics,
    statsById: [String: RTCStatistics]
) -> String? {
    guard let codecId = memberString(rtpStat, key: "codecId"),
          let codecStat = statsById[codecId]
    else {
        return nil
    }
    return memberString(codecStat, key: "mimeType")
}

func joinCodecMimeTypes(_ values: [String]) -> String? {
    let codecs = Set(
        values
            .flatMap { $0.components(separatedBy: " | ") }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    )
    guard !codecs.isEmpty else { return nil }
    return codecs.sorted().joined(separator: " | ")
}

func firstAudioCodecMimeType(in sdp: String?) -> String? {
    guard let sdp, !sdp.isEmpty else { return nil }
    let lines = sdp.components(separatedBy: .newlines).map {
        $0.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    guard let audioLineIndex = lines.firstIndex(where: { $0.hasPrefix("m=audio ") }) else { return nil }
    let audioLineParts = lines[audioLineIndex].split(whereSeparator: \Character.isWhitespace)
    guard audioLineParts.count > 3 else { return nil }
    let firstPayloadType = String(audioLineParts[3])

    for line in lines.dropFirst(audioLineIndex + 1) {
        if line.hasPrefix("m=") { break }
        guard line.lowercased().hasPrefix("a=rtpmap:") else { continue }
        let mapping = line.dropFirst("a=rtpmap:".count).split(maxSplits: 1, whereSeparator: \Character.isWhitespace)
        guard mapping.count == 2, mapping[0] == firstPayloadType else { continue }
        guard let codec = mapping[1].split(separator: "/").first else { return nil }
        return "audio/\(codec.lowercased())"
    }
    return nil
}

// Native WebRTC stats report the inner Opus codec for an RTP stream wrapped
// in RED. The negotiated answer carries the outer codec that is actually sent.
func effectiveAudioCodecMimeType(
    statsCodecMimeType: String?,
    negotiatedAnswerCodecMimeType: String?
) -> String? {
    if statsCodecMimeType?.lowercased() == "audio/opus",
       negotiatedAnswerCodecMimeType?.lowercased() == "audio/red" {
        return negotiatedAnswerCodecMimeType
    }
    return statsCodecMimeType
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

/// Clamps a raw `audioLevel` stat value to `[0, 1]`, returning `nil` for
/// missing or non-finite inputs. `max`/`min` alone don't reject NaN (they use
/// `<` comparisons that propagate NaN), so a stray non-finite value would
/// otherwise pin the indicator to a garbage reading.
func clampedAudioLevel(_ raw: Double?) -> Float? {
    guard let raw, raw.isFinite else { return nil }
    return Float(max(0, min(1, raw)))
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
