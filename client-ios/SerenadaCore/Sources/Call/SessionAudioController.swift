import Foundation

@MainActor
protocol SessionAudioController {
    func activate()
    func deactivate()
    func shouldPauseVideoForProximity(isScreenSharing: Bool) -> Bool
    func setOnAudioEnvironmentChanged(_ handler: @escaping () -> Void)
}
