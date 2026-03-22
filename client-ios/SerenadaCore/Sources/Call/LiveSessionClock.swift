import Combine
import Foundation

@MainActor
final class LiveSessionClock: SessionClock {
    func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    func sleep(nanoseconds: UInt64) async throws {
        try await Task.sleep(nanoseconds: nanoseconds)
    }

    func scheduleRepeating(intervalSeconds: TimeInterval, action: @escaping @MainActor () -> Void) -> AnyCancellable {
        let timer = Timer.scheduledTimer(withTimeInterval: intervalSeconds, repeats: true) { _ in
            Task { @MainActor in
                action()
            }
        }
        return AnyCancellable { timer.invalidate() }
    }
}
