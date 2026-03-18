import Foundation

public enum LocalCameraMode: String, Codable, Equatable {
    case selfie = "SELFIE"
    case world = "WORLD"
    case composite = "COMPOSITE"
    case screenShare = "SCREEN_SHARE"

    public var isContentMode: Bool { self == .world || self == .composite }
}

public enum ContentTypeWire {
    public static let screenShare = "screenShare"
    public static let worldCamera = "worldCamera"
    public static let compositeCamera = "compositeCamera"
}

public func nextFlipCameraMode(current: LocalCameraMode, compositeAvailable: Bool) -> LocalCameraMode {
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
