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

/// Resolve the configured `SerenadaConfig.cameraModes` list into the set of
/// modes this session will allow, in the configured order. `.screenShare` is
/// always dropped — screen sharing is controlled separately. Duplicates are
/// removed, preserving the first occurrence. Returning an empty array is
/// valid and signals that video is disabled entirely.
///
/// `.composite` is dropped when `compositeAvailable` is false.
internal func resolveCameraModes(
    _ configured: [LocalCameraMode]?,
    compositeAvailable: Bool = true
) -> [LocalCameraMode] {
    let source = configured ?? [.selfie, .world, .composite]
    var seen: Set<LocalCameraMode> = []
    var result: [LocalCameraMode] = []
    for mode in source {
        if mode == .screenShare { continue }
        if mode == .composite && !compositeAvailable { continue }
        if seen.contains(mode) { continue }
        seen.insert(mode)
        result.append(mode)
    }
    return result
}

/// Cycle to the next mode in `modes` after `current`, preserving the
/// configured order and optionally skipping `.composite` when the device
/// can't support it. Returns `nil` when the list has one or zero entries.
internal func nextCameraMode(
    modes: [LocalCameraMode],
    current: LocalCameraMode,
    compositeAvailable: Bool
) -> LocalCameraMode? {
    let cyclable = modes.filter { $0 != .composite || compositeAvailable }
    if cyclable.count <= 1 { return nil }
    let startIndex = cyclable.firstIndex(of: current) ?? -1
    let nextIndex = (startIndex + 1) % cyclable.count
    let next = cyclable[nextIndex]
    return next == current ? nil : next
}
