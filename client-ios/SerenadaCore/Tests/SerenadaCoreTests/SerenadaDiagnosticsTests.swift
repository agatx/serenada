@testable import SerenadaCore
import XCTest

@MainActor
final class SerenadaDiagnosticsTests: XCTestCase {
    func testRunAllSkipsSignalingInProviderMode() async {
        let provider = FakeSignalingProvider()
        let diagnostics = SerenadaDiagnostics(config: SerenadaConfig(signalingProvider: provider))

        let report = await withCheckedContinuation { continuation in
            diagnostics.runAll { continuation.resume(returning: $0) }
        }

        XCTAssertEqual(report.signaling, .skipped(reason: "requires serverHost"))
    }

    func testRunConnectivityChecksRequiresServerHost() async {
        let diagnostics = SerenadaDiagnostics(config: SerenadaConfig(signalingProvider: FakeSignalingProvider()))

        do {
            _ = try await diagnostics.runConnectivityChecks()
            XCTFail("Expected runConnectivityChecks() to fail without serverHost")
        } catch {
            XCTAssertEqual(error.localizedDescription, "requires serverHost")
        }
    }

    func testCheckTurnUsesProviderIceServers() async {
        let provider = FakeSignalingProvider()
        provider.iceServerResults = [.success([IceServerConfig(urls: ["turn:turn.example.com:3478"], username: "user", credential: "pass")])]
        let diagnostics = SerenadaDiagnostics(config: SerenadaConfig(signalingProvider: provider))

        let result = await withCheckedContinuation { continuation in
            diagnostics.checkTurn { continuation.resume(returning: $0) }
        }

        XCTAssertEqual(provider.getIceServersCallCount, 1)
        XCTAssertEqual(result, .reachable(latencyMs: 0))
    }

    func testRunTurnProbeUsesProviderIceServers() async {
        let provider = FakeSignalingProvider()
        provider.iceServerResults = [.success([IceServerConfig(urls: ["turn:turn.example.com:3478"], username: "user", credential: "pass")])]
        let diagnostics = SerenadaDiagnostics(config: SerenadaConfig(signalingProvider: provider))

        _ = await diagnostics.runTurnProbe(turnsOnly: true)

        XCTAssertEqual(provider.getIceServersCallCount, 1)
    }
}
