@testable import SerenadaCore
import XCTest

@MainActor
final class SerenadaCoreProviderModeTests: XCTestCase {
    func testUnsupportedSignalingProviderVersionIsRejected() {
        XCTAssertThrowsError(
            try resolveSerenadaConfig(SerenadaConfig(signalingProvider: FakeSignalingProvider(version: 2)))
        ) { error in
            XCTAssertEqual(error.localizedDescription, "Unsupported signalingProvider version: 2")
        }
    }

    func testCreateRoomIdRequiresServerHostInProviderMode() async {
        let core = SerenadaCore(config: SerenadaConfig(signalingProvider: FakeSignalingProvider()))

        do {
            _ = try await core.createRoomId()
            XCTFail("Expected createRoomId() to fail without serverHost")
        } catch {
            XCTAssertEqual(error.localizedDescription, "requires serverHost")
        }
    }

    func testBuiltInServerProviderOwnsReconnectHandling() {
        let provider = SerenadaServerProvider(
            serverHost: "serenada.app",
            apiClient: FakeAPIClient()
        )

        XCTAssertTrue(provider.capabilities.handlesReconnection)
        provider.disconnect()
    }
}
