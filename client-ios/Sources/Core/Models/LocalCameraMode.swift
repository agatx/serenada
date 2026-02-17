import Foundation

enum LocalCameraMode: String, Codable, Equatable {
    case selfie = "SELFIE"
    case world = "WORLD"
    case composite = "COMPOSITE"
    case screenShare = "SCREEN_SHARE"
}

func nextFlipCameraMode(current: LocalCameraMode, compositeAvailable: Bool) -> LocalCameraMode {
    switch current {
    case .selfie:
        return .world
    case .world:
        return compositeAvailable ? .composite : .selfie
    case .composite:
        return .selfie
    case .screenShare:
        return .selfie
    }
}
