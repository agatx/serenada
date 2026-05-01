import XCTest
@testable import SerenadaCore

@MainActor
final class AudioLevelPollerFallbackTests: XCTestCase {
    private var clock: FakeSessionClock!

    override func setUp() async throws {
        clock = FakeSessionClock()
    }

    override func tearDown() async throws {
        clock = nil
    }

    /// The poller drives `localMonitor` from `collectLocalLevel` (the primer
    /// PC's `media-source.audioLevel` stat) so the indicator animates with
    /// consistent sensitivity in Waiting and InCall.
    func testUsesPrimerLevelWhenNoSlots() async {
        var primerCalls = 0
        var lastLocalLevel: Float?

        let poller = AudioLevelPoller(
            clock: clock,
            isActivePhase: { true },
            getPeerSlots: { [] },
            collectLocalLevel: { onComplete in
                primerCalls += 1
                onComplete(0.5)  // mid-speech level
            },
            onLevelsUpdated: { local, _ in lastLocalLevel = local }
        )

        poller.start()
        await clock.advance(byMs: Int64(AudioLevelMonitor.updateIntervalSeconds * 1_000) + 10)

        XCTAssertGreaterThan(primerCalls, 0, "Expected the primer to be queried")
        XCTAssertNotNil(lastLocalLevel)
        XCTAssertGreaterThan(lastLocalLevel ?? 0, 0, "Expected a non-zero local level fed by the primer")
    }

    /// A nil primer level (stat not yet populated) decays the indicator to
    /// silence rather than freezing it on the prior value.
    func testNilPrimerLevelDecaysToSilence() async {
        var lastLocalLevel: Float = 1.0

        let poller = AudioLevelPoller(
            clock: clock,
            isActivePhase: { true },
            getPeerSlots: { [] },
            collectLocalLevel: { onComplete in onComplete(nil) },
            onLevelsUpdated: { local, _ in lastLocalLevel = local }
        )

        poller.start()
        for _ in 0..<10 {
            await clock.advance(byMs: Int64(AudioLevelMonitor.updateIntervalSeconds * 1_000) + 1)
        }

        XCTAssertEqual(lastLocalLevel, 0, accuracy: 0.001, "Expected silent indicator when no PCM samples are available")
    }
}
