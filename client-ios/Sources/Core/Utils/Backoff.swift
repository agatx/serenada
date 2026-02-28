import Foundation

enum Backoff {
    static func reconnectDelayMs(attempt: Int) -> Int {
        let normalized = max(1, attempt)
        let value = Int(Double(WebRtcResilience.reconnectBackoffBaseMs) * pow(2.0, Double(normalized - 1)))
        return min(value, WebRtcResilience.reconnectBackoffCapMs)
    }
}
