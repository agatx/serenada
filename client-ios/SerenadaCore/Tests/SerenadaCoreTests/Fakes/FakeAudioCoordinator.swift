import Foundation
@testable import SerenadaCore

final class FakeAudioCoordinator: @unchecked Sendable, SerenadaAudioCoordinator {
    private(set) var activateCalls = 0
    private(set) var deactivateCalls = 0
    private(set) var appliedRoutes: [AudioDevice] = []
    private(set) var micMutedValues: [Bool] = []

    func activateCallSession(intent: AudioIntent) async throws {
        activateCalls += 1
    }

    func deactivateCallSession() async {
        deactivateCalls += 1
    }

    func applyRouting(_ device: AudioDevice) async throws {
        appliedRoutes.append(device)
    }

    func setMicMuted(_ muted: Bool) async throws {
        micMutedValues.append(muted)
    }

    var availableDevices: AsyncStream<[AudioDevice]> { AsyncStream { _ in } }
    var effectiveInputDevice: AsyncStream<AudioDevice?> { AsyncStream { _ in } }
    var effectiveOutputDevice: AsyncStream<AudioDevice?> { AsyncStream { _ in } }
    var events: AsyncStream<AudioCoordinatorEvent> { AsyncStream { _ in } }
}
