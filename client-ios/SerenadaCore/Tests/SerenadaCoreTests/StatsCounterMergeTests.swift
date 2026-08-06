@testable import SerenadaCore
import XCTest

/// Verifies the new telemetry counters (videoFramesDecoded/Dropped,
/// audioPacketsLost/Received) are summed across peer slots in the existing
/// merge step.
@MainActor
final class StatsCounterMergeTests: XCTestCase {
    func testSumsNewCountersAcrossSlots() {
        let a = RealtimeCallStats(
            audioRxCodec: "audio/red", audioTxCodec: "audio/opus",
            videoRxCodec: "video/VP8", videoTxCodec: "video/VP8",
            videoFramesDecoded: 600, videoFramesDropped: 12,
            audioPacketsLost: 30, audioPacketsReceived: 2000,
            updatedAtMs: 100
        )
        let b = RealtimeCallStats(
            audioRxCodec: "audio/opus", audioTxCodec: "audio/opus",
            videoRxCodec: "video/H264", videoTxCodec: "video/VP8",
            videoFramesDecoded: 400, videoFramesDropped: 8,
            audioPacketsLost: 10, audioPacketsReceived: 1000,
            updatedAtMs: 200
        )
        let merged = StatsPoller.mergeRealtimeStats([a, b])
        XCTAssertEqual(merged.audioRxCodec, "audio/opus | audio/red")
        XCTAssertEqual(merged.audioTxCodec, "audio/opus")
        XCTAssertEqual(merged.videoRxCodec, "video/H264 | video/VP8")
        XCTAssertEqual(merged.videoTxCodec, "video/VP8")
        XCTAssertEqual(merged.videoFramesDecoded, 1000)
        XCTAssertEqual(merged.videoFramesDropped, 20)
        XCTAssertEqual(merged.audioPacketsLost, 40)
        XCTAssertEqual(merged.audioPacketsReceived, 3000)
    }

    func testNilWhenNoSlotReportsACounter() {
        var a = RealtimeCallStats.empty; a.updatedAtMs = 1
        var b = RealtimeCallStats.empty; b.updatedAtMs = 2
        let merged = StatsPoller.mergeRealtimeStats([a, b])
        XCTAssertNil(merged.videoFramesDecoded)
        XCTAssertNil(merged.videoFramesDropped)
        XCTAssertNil(merged.audioPacketsLost)
        XCTAssertNil(merged.audioPacketsReceived)
    }
}
