import Foundation
import SerenadaCore

func currentCallAudioRoute(
    currentAudioDevice: AudioDevice?,
    availableAudioDevices: [AudioDevice]
) -> AudioDevice? {
    let currentKey = currentAudioDevice.flatMap { device in
        device.isCallAudioOutputRoute ? callAudioRouteKey(device) : nil
    }
    let options = callAudioRouteOptions(
        currentAudioDevice: currentAudioDevice,
        availableAudioDevices: availableAudioDevices
    )
    if let activeRoute = preferredActiveCallAudioRoute(options) {
        return activeRoute
    }
    if let currentKey, let currentRoute = options.first(where: { callAudioRouteKey($0) == currentKey }) {
        return currentRoute
    }
    return nil
}

func callAudioRouteOptions(
    currentAudioDevice: AudioDevice?,
    availableAudioDevices: [AudioDevice]
) -> [AudioDevice] {
    let candidates = (availableAudioDevices + [currentAudioDevice].compactMap { $0 })
        .filter(\.isCallAudioOutputRoute)
    let visibleCandidates = candidates.contains(where: { device in
        if case .bluetooth(_) = device.kind {
            return device.status == .active || device.status == .connecting || device == currentAudioDevice
        }
        return false
    })
        ? candidates.filter { $0.kind != .earpiece }
        : candidates
    let activeKeys = Set(
        visibleCandidates
            .filter { $0.status == .active || $0 == currentAudioDevice }
            .map(callAudioRouteKey)
    )
    var devicesByRoute: [String: AudioDevice] = [:]
    for device in visibleCandidates {
        let key = callAudioRouteKey(device)
        if let existing = devicesByRoute[key] {
            devicesByRoute[key] = preferredCallAudioRouteDisplay(existing, candidate: device)
        } else {
            devicesByRoute[key] = device
        }
    }
    return devicesByRoute.values
        .map { device in
            activeKeys.contains(callAudioRouteKey(device))
                ? callAudioRouteWithStatus(device, .active)
                : device
        }
        .sorted { lhs, rhs in
            let lhsRank = callAudioRouteSortRank(lhs.kind)
            let rhsRank = callAudioRouteSortRank(rhs.kind)
            if lhsRank != rhsRank { return lhsRank < rhsRank }
            return callAudioRouteSortLabel(lhs).localizedCaseInsensitiveCompare(
                callAudioRouteSortLabel(rhs)
            ) == .orderedAscending
        }
}

func callAudioRouteLabel(_ device: AudioDevice, strings: [SerenadaString: String]?) -> String {
    let routeName = device.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
    func routeNameOr(_ key: SerenadaString) -> String {
        routeName.isEmpty ? resolveString(key, overrides: strings) : routeName
    }

    switch device.kind {
    case .speakerphone:
        return resolveString(.callAudioSpeaker, overrides: strings)
    case .earpiece:
        return resolveString(.callAudioPhone, overrides: strings)
    case .wiredHeadset:
        return routeNameOr(.callAudioHeadset)
    case .bluetooth(_):
        return routeNameOr(.callAudioBluetooth)
    case .carAudio:
        return routeNameOr(.callAudioCar)
    case .usb:
        return routeNameOr(.callAudioUsb)
    case .other:
        return routeNameOr(.callAudioUnknown)
    }
}

func callAudioRouteSystemImage(_ kind: AudioDeviceKind?) -> String {
    switch kind {
    case .speakerphone:
        return "speaker.wave.2.fill"
    case .earpiece:
        return "phone.fill"
    case .wiredHeadset:
        return "headphones"
    case .bluetooth(_):
        return "dot.radiowaves.left.and.right"
    case .carAudio:
        return "car.fill"
    case .usb:
        return "cable.connector"
    case .other, nil:
        return "speaker.wave.2.fill"
    }
}

func callAudioRouteKey(_ device: AudioDevice) -> String {
    let routeName = device.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
    let fallback = device.id.isEmpty ? routeName : device.id

    switch device.kind {
    case .speakerphone:
        return "speakerphone"
    case .earpiece:
        return "earpiece"
    case .wiredHeadset:
        return "wired"
    case .bluetooth(_):
        return "bluetooth:\(fallback)"
    case .carAudio:
        return "car:\(fallback)"
    case .usb:
        return "usb:\(fallback)"
    case .other:
        return "other:\(fallback)"
    }
}

private func callAudioRouteSortRank(_ kind: AudioDeviceKind) -> Int {
    switch kind {
    case .speakerphone:
        return 0
    case .earpiece:
        return 1
    case .bluetooth(_):
        return 2
    case .wiredHeadset:
        return 3
    case .carAudio, .usb, .other:
        return 4
    }
}

private func callAudioRouteSortLabel(_ device: AudioDevice) -> String {
    let routeName = device.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
    return routeName.isEmpty ? device.id : routeName
}

private func preferredActiveCallAudioRoute(_ devices: [AudioDevice]) -> AudioDevice? {
    devices
        .filter { $0.status == .active }
        .min { lhs, rhs in
            let lhsRank = callAudioRouteActiveRank(lhs.kind)
            let rhsRank = callAudioRouteActiveRank(rhs.kind)
            if lhsRank != rhsRank { return lhsRank < rhsRank }
            return callAudioRouteSortLabel(lhs).localizedCaseInsensitiveCompare(
                callAudioRouteSortLabel(rhs)
            ) == .orderedAscending
        }
}

private func callAudioRouteActiveRank(_ kind: AudioDeviceKind) -> Int {
    switch kind {
    case .bluetooth(_):
        return 0
    case .wiredHeadset:
        return 1
    case .carAudio, .usb:
        return 2
    case .speakerphone:
        return 3
    case .earpiece:
        return 4
    case .other:
        return 5
    }
}

private func preferredCallAudioRouteDisplay(_ existing: AudioDevice, candidate: AudioDevice) -> AudioDevice {
    let existingRank = callAudioRouteDisplayRank(existing)
    let candidateRank = callAudioRouteDisplayRank(candidate)
    if candidateRank < existingRank { return candidate }
    if candidateRank > existingRank { return existing }
    if candidate.status == .active && existing.status != .active { return candidate }
    return existing
}

private func callAudioRouteDisplayRank(_ device: AudioDevice) -> Int {
    let hasName = !device.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    switch device.kind {
    case .bluetooth(let profile):
        switch profile {
        case .hfp, .ble:
            return hasName ? 0 : 3
        case .unknown:
            return hasName ? 1 : 4
        case .a2dp:
            return hasName ? 2 : 5
        }
    case .speakerphone, .earpiece:
        return 0
    case .wiredHeadset, .carAudio, .usb, .other:
        return hasName ? 0 : 1
    }
}

private func callAudioRouteWithStatus(_ device: AudioDevice, _ status: AudioDeviceStatus) -> AudioDevice {
    AudioDevice(
        id: device.id,
        displayName: device.displayName,
        kind: device.kind,
        direction: device.direction,
        status: status
    )
}

private extension AudioDevice {
    var isCallAudioOutputRoute: Bool {
        direction == .output || direction == .both
    }
}
