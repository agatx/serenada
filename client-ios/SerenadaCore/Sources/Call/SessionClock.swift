import Combine
import Foundation

@MainActor
protocol SessionClock: AnyObject {
    func nowMs() -> Int64
    func sleep(nanoseconds: UInt64) async throws
    func scheduleRepeating(intervalSeconds: TimeInterval, action: @escaping @MainActor () -> Void) -> AnyCancellable
}
