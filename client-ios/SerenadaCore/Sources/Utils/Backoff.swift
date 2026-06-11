import Foundation

enum Backoff {
    static func reconnectDelayMs(attempt: Int) -> Int {
        let normalized = max(1, attempt)
        // Clamp the exponent (matching Android's shift clamp) so the
        // Double-to-Int conversion can never trap on overflow after many
        // consecutive reconnect failures; 2^13 x base is far beyond the cap.
        let exponent = min(normalized - 1, 13)
        let value = Int(Double(WebRtcResilience.reconnectBackoffBaseMs) * pow(2.0, Double(exponent)))
        return min(value, WebRtcResilience.reconnectBackoffCapMs)
    }
}
