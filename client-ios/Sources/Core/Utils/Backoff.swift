import Foundation

enum Backoff {
    static func reconnectDelayMs(attempt: Int) -> Int {
        let normalized = max(1, attempt)
        let value = Int(Double(500) * pow(2.0, Double(normalized - 1)))
        return min(value, 5000)
    }
}
