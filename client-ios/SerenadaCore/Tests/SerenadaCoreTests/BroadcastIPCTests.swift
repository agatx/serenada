import SerenadaBroadcastExtensionSupport
import XCTest

/// Pure-logic coverage for the cross-process broadcast IPC contract (R-SDK2,
/// R-IPC1). The full reader/writer handshake is device-only (ReplayKit broadcast
/// cannot run in the simulator), but the identifier derivation and the sidecar
/// liveness rules are deterministic and locked here.
final class BroadcastIPCTests: XCTestCase {

    // MARK: - Identifier derivation (R-SDK2)

    func testConfigDerivesEveryIdentifierFromTheExtensionBundleId() {
        let config = BroadcastIPCConfig(
            appGroupIdentifier: "group.com.example.shared",
            extensionBundleId: "com.example.app.broadcast"
        )
        XCTAssertEqual(config.darwinNotifyStarted, "com.example.app.broadcast.started")
        XCTAssertEqual(config.darwinNotifyFinished, "com.example.app.broadcast.finished")
        XCTAssertEqual(config.darwinNotifyRequestStop, "com.example.app.broadcast.requestStop")
        XCTAssertEqual(config.sharedFileName, "com.example.app.broadcast.frame.dat")
        XCTAssertEqual(config.sidecarFileName, "com.example.app.broadcast.session.json")
    }

    /// The reference app's derived Darwin names must equal the values previously
    /// hardcoded in `BroadcastShared`, so a reader and writer agree across the
    /// move off the compile flag.
    func testReferenceConfigMatchesLegacyDarwinNames() {
        let config = BroadcastIPCConfig(
            appGroupIdentifier: "group.app.serenada.ios",
            extensionBundleId: "app.serenada.ios.broadcast"
        )
        XCTAssertEqual(config.darwinNotifyStarted, "app.serenada.ios.broadcast.started")
        XCTAssertEqual(config.darwinNotifyFinished, "app.serenada.ios.broadcast.finished")
        XCTAssertEqual(config.darwinNotifyRequestStop, "app.serenada.ios.broadcast.requestStop")
    }

    // MARK: - Sidecar (R-IPC1)

    func testSidecarRoundTripsThroughJSON() throws {
        let sidecar = BroadcastSessionSidecar(
            sessionId: "session-1", generation: 7, activeCall: true, heartbeatMs: 1_234
        )
        let data = try JSONEncoder().encode(sidecar)
        let decoded = try JSONDecoder().decode(BroadcastSessionSidecar.self, from: data)
        XCTAssertEqual(decoded, sidecar)
        XCTAssertEqual(decoded.schemaVersion, BroadcastSessionSidecar.currentSchemaVersion)
    }

    func testIsLiveRequiresActiveCallAndFreshHeartbeat() {
        let now: Int64 = 10_000
        let stale = 3_000

        // Active call, recent heartbeat → live.
        XCTAssertTrue(
            BroadcastSessionSidecar(sessionId: "a", generation: 1, activeCall: true, heartbeatMs: now - 1_000)
                .isLive(nowMs: now, staleThresholdMs: stale)
        )
        // Active call, heartbeat older than the threshold → not live (reader gone).
        XCTAssertFalse(
            BroadcastSessionSidecar(sessionId: "a", generation: 1, activeCall: true, heartbeatMs: now - 5_000)
                .isLive(nowMs: now, staleThresholdMs: stale)
        )
        // Marker cleared → not live regardless of heartbeat (R-IPC2: no live call).
        XCTAssertFalse(
            BroadcastSessionSidecar(sessionId: "a", generation: 1, activeCall: false, heartbeatMs: now)
                .isLive(nowMs: now, staleThresholdMs: stale)
        )
        // Exactly at the threshold counts as stale (strict <).
        XCTAssertFalse(
            BroadcastSessionSidecar(sessionId: "a", generation: 1, activeCall: true, heartbeatMs: now - 3_000)
                .isLive(nowMs: now, staleThresholdMs: stale)
        )
    }
}
