import Combine
import Foundation
@testable import SerenadaCore

@MainActor
final class FakeSessionClock: SessionClock {
    private var currentTimeMs: Int64 = 0
    private var pendingSleeps: [(id: UUID, deadlineMs: Int64, continuation: CheckedContinuation<Void, any Error>)] = []
    private var repeatingEntries: [RepeatingEntry] = []

    func nowMs() -> Int64 {
        currentTimeMs
    }

    func sleep(nanoseconds: UInt64) async throws {
        let deadlineMs = currentTimeMs + Int64(nanoseconds / 1_000_000)
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                if Task.isCancelled {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                pendingSleeps.append((id: id, deadlineMs: deadlineMs, continuation: continuation))
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.cancelSleep(id: id)
            }
        }
    }

    func scheduleRepeating(intervalSeconds: TimeInterval, action: @escaping @MainActor () -> Void) -> AnyCancellable {
        let intervalMs = Int64(intervalSeconds * 1000)
        var nextFireMs = currentTimeMs + intervalMs
        var cancelled = false

        let entry = RepeatingEntry(
            getNextFireMs: { nextFireMs },
            fire: {
                guard !cancelled else { return }
                action()
                nextFireMs += intervalMs
            }
        )
        repeatingEntries.append(entry)

        return AnyCancellable {
            cancelled = true
            self.repeatingEntries.removeAll { $0 === entry }
        }
    }

    /// Advance the clock by the given number of milliseconds, resuming all sleeps
    /// whose deadlines have passed and firing repeating timers as needed.
    func advance(byMs ms: Int64) async {
        let targetMs = currentTimeMs + ms

        while currentTimeMs < targetMs {
            // Find the next event time
            var nextEventMs = targetMs

            for entry in pendingSleeps {
                if entry.deadlineMs < nextEventMs {
                    nextEventMs = entry.deadlineMs
                }
            }
            for entry in repeatingEntries {
                let fireMs = entry.getNextFireMs()
                if fireMs < nextEventMs {
                    nextEventMs = fireMs
                }
            }

            currentTimeMs = nextEventMs

            // Resume all sleeps at or before current time
            let ready = pendingSleeps.filter { $0.deadlineMs <= currentTimeMs }
            pendingSleeps.removeAll { $0.deadlineMs <= currentTimeMs }
            for entry in ready {
                entry.continuation.resume()
            }

            // Fire repeating timers at or before current time
            for entry in repeatingEntries {
                while entry.getNextFireMs() <= currentTimeMs {
                    entry.fire()
                }
            }

            // Yield to let resumed tasks run
            await Task.yield()
            await Task.yield()
        }
    }

    deinit {
        for entry in pendingSleeps {
            entry.continuation.resume(throwing: CancellationError())
        }
        pendingSleeps.removeAll()
    }

    private func cancelSleep(id: UUID) {
        if let index = pendingSleeps.firstIndex(where: { $0.id == id }) {
            let entry = pendingSleeps.remove(at: index)
            entry.continuation.resume(throwing: CancellationError())
        }
    }

    private final class RepeatingEntry {
        let getNextFireMs: () -> Int64
        let fire: () -> Void

        init(getNextFireMs: @escaping () -> Int64, fire: @escaping () -> Void) {
            self.getNextFireMs = getNextFireMs
            self.fire = fire
        }
    }
}
