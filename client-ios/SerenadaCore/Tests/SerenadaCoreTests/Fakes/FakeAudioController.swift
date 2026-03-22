import Foundation
@testable import SerenadaCore

@MainActor
final class FakeAudioController: SessionAudioController {
    private(set) var activateCalls = 0
    private(set) var deactivateCalls = 0
    var proximityPausesVideo = false
    private var audioEnvironmentChangedHandler: (() -> Void)?

    func activate() { activateCalls += 1 }
    func deactivate() { deactivateCalls += 1 }

    func shouldPauseVideoForProximity(isScreenSharing: Bool) -> Bool {
        proximityPausesVideo
    }

    func setOnAudioEnvironmentChanged(_ handler: @escaping () -> Void) {
        audioEnvironmentChangedHandler = handler
    }

    func triggerAudioEnvironmentChanged() {
        audioEnvironmentChangedHandler?()
    }
}
