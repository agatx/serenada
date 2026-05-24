import AVFoundation
import Foundation
import UIKit
#if canImport(WebRTC)
@preconcurrency import WebRTC
#endif

private let callAudioSessionOptions: AVAudioSession.CategoryOptions = [.allowBluetoothHFP, .mixWithOthers]
private let phoneAudioSessionOptions: AVAudioSession.CategoryOptions = [.mixWithOthers]
private let systemRoutePickerWillPresentNotification = Notification.Name("app.serenada.audio.systemRoutePickerWillPresent")

private final class ContinuationHolder<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<T>.Continuation] = [:]
    private var currentValue: T

    init(initialValue: T) {
        self.currentValue = initialValue
    }

    func update(_ value: T) {
        lock.lock()
        currentValue = value
        let values = Array(continuations.values)
        lock.unlock()
        for c in values {
            c.yield(value)
        }
    }

    func makeStream() -> AsyncStream<T> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            continuations[id] = continuation
            let initial = currentValue
            lock.unlock()

            continuation.yield(initial)

            continuation.onTermination = { [weak self] _ in
                guard let self = self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    var value: T {
        lock.lock()
        let value = currentValue
        lock.unlock()
        return value
    }
}

private final class EventHolder<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<T>.Continuation] = [:]

    func emit(_ value: T) {
        lock.lock()
        let values = Array(continuations.values)
        lock.unlock()
        for c in values {
            c.yield(value)
        }
    }

    func makeStream() -> AsyncStream<T> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            continuations[id] = continuation
            lock.unlock()

            continuation.onTermination = { [weak self] _ in
                guard let self = self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }
}

private extension AudioDeviceKind {
    var isBluetooth: Bool {
        switch self {
        case .bluetooth:
            return true
        default:
            return false
        }
    }
}

@MainActor
final class DefaultAudioCoordinator: NSObject, @preconcurrency SerenadaAudioCoordinator, SessionAudioController, @unchecked Sendable {
    private let availableDevicesHolder = ContinuationHolder<[AudioDevice]>(initialValue: [])
    private let effectiveInputDeviceHolder = ContinuationHolder<AudioDevice?>(initialValue: nil)
    private let effectiveOutputDeviceHolder = ContinuationHolder<AudioDevice?>(initialValue: nil)
    private let eventsHolder = EventHolder<AudioCoordinatorEvent>()
    private let routeConfigurationQueue = DispatchQueue(label: "app.serenada.audio.routeConfiguration")

    private var onProximityChanged: (Bool) -> Void
    private var onAudioEnvironmentChanged: () -> Void
    private let logger: SerenadaLogger?
    private let proximityMonitoringEnabled: Bool

    private let audioSession = AVAudioSession.sharedInstance()

    private var audioSessionActive = false
    private var proximityMonitoringActive = false
    private var isProximityNear = false
    private var pinnedOutputKind: AudioDeviceKind?
    private var systemRouteSelectionActive = false
    private var rememberedBluetoothOutputDevices: [String: AudioDevice] = [:]

    init(
        proximityMonitoringEnabled: Bool,
        onProximityChanged: @escaping (Bool) -> Void,
        onAudioEnvironmentChanged: @escaping () -> Void,
        logger: SerenadaLogger? = nil
    ) {
        self.proximityMonitoringEnabled = proximityMonitoringEnabled
        self.onProximityChanged = onProximityChanged
        self.onAudioEnvironmentChanged = onAudioEnvironmentChanged
        self.logger = logger
        super.init()
    }

    func setOnProximityChanged(_ handler: @escaping (Bool) -> Void) {
        onProximityChanged = handler
    }

    func setOnAudioEnvironmentChanged(_ handler: @escaping () -> Void) {
        onAudioEnvironmentChanged = handler
    }

    func activate() {
        guard !audioSessionActive else { return }
        audioSessionActive = true
        systemRouteSelectionActive = false

        do {
            try audioSession.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: callAudioSessionOptions
            )
            try audioSession.setActive(true)
        } catch {
            logger?.log(.error, tag: "Audio", "failed to activate audio session: \(error)")
        }

        startAudioRouteMonitoring()
        if proximityMonitoringEnabled {
            startProximityMonitoring()
        }
        updateDevicesAndRoute()
        applyCallAudioRouting()
        onAudioEnvironmentChanged()
    }

    func deactivate() {
        guard audioSessionActive else {
            stopProximityMonitoring()
            return
        }

        audioSessionActive = false
        pinnedOutputKind = nil
        systemRouteSelectionActive = false
        stopAudioRouteMonitoring()
        stopProximityMonitoring()

        do {
            try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            logger?.log(.error, tag: "Audio", "failed to deactivate audio session: \(error)")
        }
    }

    func shouldPauseVideoForProximity(isScreenSharing: Bool) -> Bool {
        proximityMonitoringActive && isProximityNear && !isScreenSharing && !isBluetoothHeadsetConnected()
    }

    // MARK: - SerenadaAudioCoordinator Conformance

    func activateCallSession(intent: AudioIntent) async throws -> AudioCoordinatorCapabilities {
        activate()
        return AudioCoordinatorCapabilities(
            pttPolicy: .block,
            canShareInput: true,
            sessionOwnership: .sdkOwned,
            supportedDeviceKinds: [.wiredHeadset, .bluetooth(profile: .unknown), .speakerphone, .earpiece]
        )
    }

    func deactivateCallSession() async {
        deactivate()
    }

    func applyRouting(_ device: AudioDevice) async throws {
        if device.direction == .output || device.direction == .both {
            let previousPinnedOutputKind = pinnedOutputKind
            let previousSystemRouteSelectionActive = systemRouteSelectionActive
            pinnedOutputKind = device.kind
            systemRouteSelectionActive = false
            do {
                try await applyUserSelectedOutputRoute(for: device.kind)
            } catch {
                pinnedOutputKind = previousPinnedOutputKind
                systemRouteSelectionActive = previousSystemRouteSelectionActive
                removeRememberedOutputDevice(for: device.kind)
                throw error
            }
        }
        updateDevicesAndRoute()
        onAudioEnvironmentChanged()
    }

    func setMicMuted(_ muted: Bool) async throws {
        // No-op for default coordinator
    }

    func suspendCapture() async throws {
        // No-op for default coordinator
    }

    func resumeCapture() async throws {
        // No-op for default coordinator
    }

    var availableDevices: AsyncStream<[AudioDevice]> {
        availableDevicesHolder.makeStream()
    }

    var effectiveInputDevice: AsyncStream<AudioDevice?> {
        effectiveInputDeviceHolder.makeStream()
    }

    var effectiveOutputDevice: AsyncStream<AudioDevice?> {
        effectiveOutputDeviceHolder.makeStream()
    }

    var events: AsyncStream<AudioCoordinatorEvent> {
        eventsHolder.makeStream()
    }

    // MARK: - Private Route/Proximity Helpers

    private func startAudioRouteMonitoring() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioSessionInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleMediaServicesReset(_:)),
            name: AVAudioSession.mediaServicesWereResetNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemRoutePickerWillPresent(_:)),
            name: systemRoutePickerWillPresentNotification,
            object: nil
        )
    }

    private func stopAudioRouteMonitoring() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.mediaServicesWereResetNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: systemRoutePickerWillPresentNotification, object: nil)
    }

    private func startProximityMonitoring() {
        guard !proximityMonitoringActive else { return }

        UIDevice.current.isProximityMonitoringEnabled = true
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleProximityStateChange(_:)),
            name: UIDevice.proximityStateDidChangeNotification,
            object: nil
        )

        proximityMonitoringActive = true
        isProximityNear = UIDevice.current.proximityState
    }

    private func stopProximityMonitoring() {
        guard proximityMonitoringActive else {
            isProximityNear = false
            return
        }

        NotificationCenter.default.removeObserver(self, name: UIDevice.proximityStateDidChangeNotification, object: nil)

        UIDevice.current.isProximityMonitoringEnabled = false
        proximityMonitoringActive = false
        isProximityNear = false
    }

    @objc private func handleAudioRouteChange(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self = self, self.audioSessionActive else { return }
            self.updateDevicesAndRoute()
            if self.shouldApplyManagedAudioRouting {
                self.applyCallAudioRouting()
                self.updateDevicesAndRoute()
            }
            self.onAudioEnvironmentChanged()

            let inputs = self.audioSession.currentRoute.inputs
            let outputs = self.audioSession.currentRoute.outputs
            let activeInput = inputs.first.map { self.mapPortToAudioDevice($0, direction: .input, status: .active) }
            let activeOutput = outputs.first.map { self.mapPortToAudioDevice($0, direction: .output, status: .active) }
            self.emitEvent(.effectiveRouteChanged(input: activeInput, output: activeOutput))
        }
    }

    @objc private func handleSystemRoutePickerWillPresent(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self = self, self.audioSessionActive else { return }
            self.pinnedOutputKind = nil
            self.systemRouteSelectionActive = true
            do {
                try self.audioSession.overrideOutputAudioPort(.none)
            } catch {
                self.logger?.log(.error, tag: "Audio", "failed to clear speaker override before system route picker: \(error)")
            }
            self.updateDevicesAndRoute()
            self.onAudioEnvironmentChanged()
        }
    }

    @objc private func handleProximityStateChange(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self = self, self.proximityMonitoringActive else { return }
            let near = UIDevice.current.proximityState
            guard near != self.isProximityNear else { return }

            self.isProximityNear = near
            self.onProximityChanged(near)
            if self.shouldApplyManagedAudioRouting {
                self.applyCallAudioRouting()
                self.updateDevicesAndRoute()
            }
            self.onAudioEnvironmentChanged()
        }
    }

    @objc private func handleAudioSessionInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        Task { @MainActor [weak self] in
            guard let self = self, self.audioSessionActive else { return }
            switch type {
            case .began:
                self.emitEvent(.audioSessionInterrupted(reason: .systemAudio))
            case .ended:
                do {
                    // Try to restore call audio even when iOS omits shouldResume.
                    // If another owner still holds audio, activation fails and the
                    // session remains externally muted while we log the failure.
                    try self.audioSession.setActive(true)
                    self.emitEvent(.audioSessionResumed)
                } catch {
                    self.logger?.log(.error, tag: "Audio", "failed to reactivate audio session after interruption: \(error)")
                }
            @unknown default:
                break
            }
        }
    }

    @objc private func handleMediaServicesReset(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self = self, self.audioSessionActive else { return }
            do {
                try self.audioSession.setCategory(
                    .playAndRecord,
                    mode: .voiceChat,
                    options: callAudioSessionOptions
                )
                try self.audioSession.setActive(true)
#if canImport(WebRTC)
                let rtcAudioSession = RTCAudioSession.sharedInstance()
                rtcAudioSession.isAudioEnabled = false
                rtcAudioSession.isAudioEnabled = true
#endif
                self.updateDevicesAndRoute()
                self.systemRouteSelectionActive = false
                self.applyCallAudioRouting()
                self.onAudioEnvironmentChanged()
                self.emitEvent(.audioSessionResumed)
            } catch {
                self.logger?.log(.error, tag: "Audio", "failed to reset media services: \(error)")
            }
        }
    }

    private var shouldApplyManagedAudioRouting: Bool {
        pinnedOutputKind != nil || !systemRouteSelectionActive
    }

    private func applyCallAudioRouting() {
        guard audioSessionActive else { return }

        if let pinnedOutputKind {
            if isPinnedOutputKindAvailable(pinnedOutputKind) {
                do {
                    try applyOutputRoute(for: pinnedOutputKind)
                } catch {
                    logger?.log(.error, tag: "Audio", "pinned route apply failed: \(error)")
                }
                return
            }
            self.pinnedOutputKind = nil
        }

        if isBluetoothHeadsetConnected() || isBluetoothHeadsetAvailable() {
            do {
                try applyBluetoothRoutePreference()
            } catch {
                logger?.log(.error, tag: "Audio", "bluetooth route apply failed: \(error)")
            }
            return
        }

        if proximityMonitoringActive && isProximityNear {
            do {
                try audioSession.overrideOutputAudioPort(.none)
            } catch {
                logger?.log(.error, tag: "Audio", "earpiece route apply failed: \(error)")
            }
            return
        }

        do {
            try audioSession.overrideOutputAudioPort(.speaker)
        } catch {
            logger?.log(.error, tag: "Audio", "speaker route apply failed: \(error)")
        }
    }

    private func applyOutputRoute(for kind: AudioDeviceKind) throws {
        if kind == .speakerphone {
            try audioSession.overrideOutputAudioPort(.speaker)
        } else {
            try audioSession.overrideOutputAudioPort(.none)
        }
    }

    private func applyBluetoothRoutePreference() throws {
#if canImport(WebRTC)
        let rtcAudioSession = RTCAudioSession.sharedInstance()
        rtcAudioSession.lockForConfiguration()
        defer { rtcAudioSession.unlockForConfiguration() }
        try Self.applyUserSelectedOutputRoute(
            for: .bluetooth(profile: .hfp),
            audioSession: audioSession,
            rtcAudioSession: rtcAudioSession
        )
#else
        if let input = audioSession.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) {
            try audioSession.setPreferredInput(input)
        }
        try audioSession.overrideOutputAudioPort(.none)
#endif
    }

    private func applyUserSelectedOutputRoute(for kind: AudioDeviceKind) async throws {
#if canImport(WebRTC)
        let queue = routeConfigurationQueue
        let audioSession = audioSession
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                let rtcAudioSession = RTCAudioSession.sharedInstance()
                rtcAudioSession.lockForConfiguration()
                defer { rtcAudioSession.unlockForConfiguration() }

                do {
                    try Self.applyUserSelectedOutputRoute(for: kind, audioSession: audioSession, rtcAudioSession: rtcAudioSession)
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
#else
        try applyOutputRoute(for: kind)
#endif
    }

#if canImport(WebRTC)
    private nonisolated static func applyUserSelectedOutputRoute(
        for kind: AudioDeviceKind,
        audioSession: AVAudioSession,
        rtcAudioSession: RTCAudioSession
    ) throws {
        let options = kind == .earpiece ? phoneAudioSessionOptions : callAudioSessionOptions
        try rtcAudioSessionSetCallCategory(options: options, rtcAudioSession: rtcAudioSession)

        switch kind {
        case .speakerphone:
            try rtcAudioSessionOverrideOutput(.speaker, rtcAudioSession: rtcAudioSession)
        case .earpiece:
            try rtcAudioSessionSetPreferredInput(.builtInMic, audioSession: audioSession, rtcAudioSession: rtcAudioSession)
            try rtcAudioSessionOverrideOutput(.none, rtcAudioSession: rtcAudioSession)
        case .bluetooth(_):
            try rtcAudioSessionSetPreferredInput(.bluetoothHFP, audioSession: audioSession, rtcAudioSession: rtcAudioSession)
            try rtcAudioSessionOverrideOutput(.none, rtcAudioSession: rtcAudioSession)
        case .wiredHeadset:
            try rtcAudioSessionSetPreferredInput(.headsetMic, audioSession: audioSession, rtcAudioSession: rtcAudioSession)
            try rtcAudioSessionOverrideOutput(.none, rtcAudioSession: rtcAudioSession)
        default:
            try rtcAudioSessionOverrideOutput(.none, rtcAudioSession: rtcAudioSession)
        }
    }

    private nonisolated static func rtcAudioSessionSetCallCategory(
        options: AVAudioSession.CategoryOptions,
        rtcAudioSession: RTCAudioSession
    ) throws {
        try rtcAudioSession.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: options
        )
    }

    private nonisolated static func rtcAudioSessionSetPreferredInput(
        _ portType: AVAudioSession.Port,
        audioSession: AVAudioSession,
        rtcAudioSession: RTCAudioSession
    ) throws {
        let input = audioSession.availableInputs?.first { $0.portType == portType }
        try rtcAudioSessionSetPreferredInput(input, portType: portType, rtcAudioSession: rtcAudioSession)
    }

    private nonisolated static func rtcAudioSessionSetPreferredInput(
        _ input: AVAudioSessionPortDescription?,
        portType: AVAudioSession.Port,
        rtcAudioSession: RTCAudioSession
    ) throws {
        guard let input else {
            throw audioRouteError("missing preferred input \(portType.rawValue)")
        }
        try rtcAudioSession.setPreferredInput(input)
    }

    private nonisolated static func rtcAudioSessionOverrideOutput(
        _ portOverride: AVAudioSession.PortOverride,
        rtcAudioSession: RTCAudioSession
    ) throws {
        try rtcAudioSession.overrideOutputAudioPort(portOverride)
    }

    private nonisolated static func audioRouteError(_ message: String) -> NSError {
        NSError(domain: "app.serenada.audioRoute", code: -1, userInfo: [NSLocalizedDescriptionKey: message])
    }
#endif

    private func isBluetoothHeadsetConnected() -> Bool {
        audioSession.currentRoute.outputs.contains { output in
            switch output.portType {
            case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE:
                return true
            default:
                return false
            }
        }
    }

    private func isBluetoothHeadsetAvailable() -> Bool {
        audioSession.availableInputs?.contains { input in
            input.portType == .bluetoothHFP
        } ?? false
    }

    private func isPinnedOutputKindAvailable(_ kind: AudioDeviceKind) -> Bool {
        switch kind {
        case .speakerphone, .earpiece:
            return true
        default:
            return availableDevicesHolder.value.contains { device in
                (device.direction == .output || device.direction == .both) && device.kind == kind
            }
        }
    }

    private func removeRememberedOutputDevice(for kind: AudioDeviceKind) {
        switch kind {
        case .bluetooth:
            rememberedBluetoothOutputDevices.removeAll()
        default:
            break
        }
    }

    private func mapPortToAudioDevice(
        _ port: AVAudioSessionPortDescription,
        direction: AudioDeviceDirection,
        status: AudioDeviceStatus = .available
    ) -> AudioDevice {
        let kind: AudioDeviceKind
        switch port.portType {
        case .bluetoothHFP:
            kind = .bluetooth(profile: .hfp)
        case .bluetoothA2DP:
            kind = .bluetooth(profile: .a2dp)
        case .bluetoothLE:
            kind = .bluetooth(profile: .ble)
        case .builtInMic, .builtInReceiver:
            kind = .earpiece
        case .builtInSpeaker:
            kind = .speakerphone
        case .headphones, .headsetMic:
            kind = .wiredHeadset
        case .carAudio:
            kind = .carAudio
        case .usbAudio:
            kind = .usb
        default:
            kind = .other
        }

        return AudioDevice(
            id: port.uid,
            displayName: port.portName,
            kind: kind,
            direction: direction,
            status: status
        )
    }

    private func updateDevicesAndRoute() {
        let route = audioSession.currentRoute

        var devices = [AudioDevice]()

        for port in audioSession.availableInputs ?? [] {
            let isActiveInput = route.inputs.contains { $0.uid == port.uid }
            let inputDevice = mapPortToAudioDevice(port, direction: .input, status: isActiveInput ? .active : .available)
            devices.append(inputDevice)

            if port.portType == .bluetoothHFP || port.portType == .headsetMic || port.portType == .usbAudio {
                let isActiveOutput = route.outputs.contains { $0.uid == port.uid }
                let outputDevice = mapPortToAudioDevice(port, direction: .output, status: isActiveOutput ? .active : .available)
                devices.append(outputDevice)
            }
        }

        let isSpeakerActive = route.outputs.contains { $0.portType == .builtInSpeaker }
        let speakerDevice = AudioDevice(
            id: "speaker",
            displayName: "Speaker",
            kind: .speakerphone,
            direction: .output,
            status: isSpeakerActive ? .active : .available
        )
        devices.append(speakerDevice)

        let isEarpieceActive = route.outputs.contains { $0.portType == .builtInReceiver }
        let earpieceDevice = AudioDevice(
            id: "earpiece",
            displayName: "Earpiece",
            kind: .earpiece,
            direction: .output,
            status: isEarpieceActive ? .active : .available
        )
        devices.append(earpieceDevice)

        for port in route.outputs {
            if port.portType != .builtInSpeaker && port.portType != .builtInReceiver && port.portType != .bluetoothHFP && port.portType != .usbAudio {
                devices.append(mapPortToAudioDevice(port, direction: .output, status: .active))
            }
        }

        rememberBluetoothOutputDevices(from: devices)
        appendRememberedBluetoothOutputDevices(to: &devices)

        let activeInput = route.inputs.first.map { mapPortToAudioDevice($0, direction: .input, status: .active) }
        let activeOutput = route.outputs.first.map { mapPortToAudioDevice($0, direction: .output, status: .active) }

        availableDevicesHolder.update(devices)
        effectiveInputDeviceHolder.update(activeInput)
        effectiveOutputDeviceHolder.update(activeOutput)
        emitEvent(.availableDevicesChanged(devices))
    }

    private func rememberBluetoothOutputDevices(from devices: [AudioDevice]) {
        let bluetoothDevices = devices.filter { device in
            (device.direction == .output || device.direction == .both) && device.kind.isBluetooth
        }
        if bluetoothDevices.isEmpty && pinnedOutputKind != .earpiece {
            rememberedBluetoothOutputDevices.removeAll()
            return
        }

        for device in bluetoothDevices {
            let remembered = audioDevice(device, withStatus: .available)
            rememberedBluetoothOutputDevices[outputDeviceKey(remembered)] = remembered
        }
    }

    private func appendRememberedBluetoothOutputDevices(to devices: inout [AudioDevice]) {
        guard pinnedOutputKind == .earpiece else { return }

        var existingKeys = Set(
            devices
                .filter { $0.direction == .output || $0.direction == .both }
                .map(outputDeviceKey)
        )
        for device in rememberedBluetoothOutputDevices.values {
            let key = outputDeviceKey(device)
            guard !existingKeys.contains(key) else { continue }
            devices.append(device)
            existingKeys.insert(key)
        }
    }

    private func outputDeviceKey(_ device: AudioDevice) -> String {
        switch device.kind {
        case .speakerphone:
            return "speakerphone"
        case .earpiece:
            return "earpiece"
        case .bluetooth:
            return "bluetooth"
        case .wiredHeadset:
            return "wired"
        default:
            return "\(device.kind):\(device.id)"
        }
    }

    private func audioDevice(_ device: AudioDevice, withStatus status: AudioDeviceStatus) -> AudioDevice {
        AudioDevice(
            id: device.id,
            displayName: device.displayName,
            kind: device.kind,
            direction: device.direction,
            status: status
        )
    }

    private func emitEvent(_ event: AudioCoordinatorEvent) {
        eventsHolder.emit(event)
    }
}
