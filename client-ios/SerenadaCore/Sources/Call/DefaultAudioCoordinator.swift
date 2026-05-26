import AVFoundation
import Foundation
import UIKit
#if canImport(WebRTC)
@preconcurrency import WebRTC
#endif

private let callAudioSessionOptions: AVAudioSession.CategoryOptions = [.allowBluetoothHFP, .allowBluetoothA2DP, .mixWithOthers]
private let phoneAudioSessionOptions: AVAudioSession.CategoryOptions = [.allowBluetoothHFP, .allowBluetoothA2DP, .mixWithOthers]

private struct OutputRouteRequest: Equatable {
    let id: String
    let kind: AudioDeviceKind
}

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
            continuation.yield(initial)
            lock.unlock()

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
    private var proximityEarpieceEnabled = true
    private var pinnedOutputDevice: AudioDevice?
    private var pinnedOutputRouteInventory: Set<String>?
    private var builtInReceiverRouteObserved = false
    private var pendingManagedOutputRequest: OutputRouteRequest?

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
        if proximityMonitoringEnabled && proximityEarpieceEnabled {
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
        proximityEarpieceEnabled = true
        pinnedOutputDevice = nil
        pinnedOutputRouteInventory = nil
        pendingManagedOutputRequest = nil
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

    func activateCallSession(intent: AudioIntent) async throws {
        proximityEarpieceEnabled = intent.enableProximityEarpiece
        if audioSessionActive {
            updateProximityMonitoringForIntent()
            updateDevicesAndRoute()
            applyCallAudioRouting()
            onAudioEnvironmentChanged()
        } else {
            activate()
        }
        if let preferredDevice = intent.preferredDevice {
            try await applyRouting(preferredDevice)
        }
    }

    func deactivateCallSession() async {
        deactivate()
    }

    func applyRouting(_ device: AudioDevice) async throws {
        if device.direction == .output || device.direction == .both {
            let previousPinnedOutputDevice = pinnedOutputDevice
            let previousPinnedOutputRouteInventory = pinnedOutputRouteInventory
            pinnedOutputDevice = device
            pinnedOutputRouteInventory = currentOutputRouteInventory()
            do {
                try await applyUserSelectedOutputRoute(for: device)
            } catch {
                pinnedOutputDevice = previousPinnedOutputDevice
                pinnedOutputRouteInventory = previousPinnedOutputRouteInventory
                throw error
            }
        }
        updateDevicesAndRoute()
        onAudioEnvironmentChanged()
    }

    func setMicMuted(_ muted: Bool) async throws {
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
            selector: #selector(handleSilenceSecondaryAudioHint(_:)),
            name: AVAudioSession.silenceSecondaryAudioHintNotification,
            object: nil
        )
    }

    private func stopAudioRouteMonitoring() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.mediaServicesWereResetNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.silenceSecondaryAudioHintNotification, object: nil)
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

    private func updateProximityMonitoringForIntent() {
        if proximityMonitoringEnabled && proximityEarpieceEnabled {
            startProximityMonitoring()
        } else {
            stopProximityMonitoring()
        }
    }

    @objc private func handleAudioRouteChange(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self = self, self.audioSessionActive else { return }
            self.updateDevicesAndRoute()
            self.applyCallAudioRouting()
            self.updateDevicesAndRoute()
            self.onAudioEnvironmentChanged()

            let inputs = self.audioSession.currentRoute.inputs
            let outputs = self.audioSession.currentRoute.outputs
            let activeInput = inputs.first.map { self.mapPortToAudioDevice($0, direction: .input, status: .active) }
            let activeOutput = outputs.first.map { self.mapPortToAudioDevice($0, direction: .output, status: .active) }
            self.emitEvent(.effectiveRouteChanged(input: activeInput, output: activeOutput))
        }
    }

    @objc private func handleProximityStateChange(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self = self, self.proximityMonitoringActive else { return }
            let near = UIDevice.current.proximityState
            guard near != self.isProximityNear else { return }

            self.isProximityNear = near
            self.onProximityChanged(near)
            self.applyCallAudioRouting()
            self.updateDevicesAndRoute()
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
                self.emitEvent(.externalAudioStarted)
            case .ended:
                do {
                    // Try to restore call audio even when iOS omits shouldResume.
                    // If another owner still holds audio, activation fails and the
                    // session remains externally muted while we log the failure.
                    try self.audioSession.setActive(true)
                    self.emitEvent(.externalAudioEnded)
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
                do {
                    rtcAudioSession.lockForConfiguration()
                    defer { rtcAudioSession.unlockForConfiguration() }
                    rtcAudioSession.isAudioEnabled = false
                    rtcAudioSession.isAudioEnabled = true
                }
#endif
                self.updateDevicesAndRoute()
                self.applyCallAudioRouting()
                self.onAudioEnvironmentChanged()
                self.emitEvent(.externalAudioEnded)
            } catch {
                self.logger?.log(.error, tag: "Audio", "failed to reset media services: \(error)")
            }
        }
    }

    @objc private func handleSilenceSecondaryAudioHint(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionSilenceSecondaryAudioHintTypeKey] as? UInt,
              let type = AVAudioSession.SilenceSecondaryAudioHintType(rawValue: typeValue) else {
            return
        }

        Task { @MainActor [weak self] in
            guard let self = self, self.audioSessionActive else { return }
            switch type {
            case .begin:
                self.emitEvent(.playbackDuckingStarted)
            case .end:
                self.emitEvent(.playbackDuckingEnded)
            @unknown default:
                break
            }
        }
    }

    private func applyCallAudioRouting() {
        guard audioSessionActive else { return }

        clearPinnedOutputIfRouteInventoryChanged()

        if let pinnedOutputDevice {
            if isPinnedOutputDeviceAvailable(pinnedOutputDevice) {
                requestManagedOutputRoute(for: pinnedOutputDevice, failureContext: "pinned")
                return
            }
            self.pinnedOutputDevice = nil
            pinnedOutputRouteInventory = nil
        }

        let preferredDevice = preferredAutomaticOutputDevice()
        requestManagedOutputRoute(for: preferredDevice, failureContext: "automatic")
    }

    private func preferredAutomaticOutputDevice() -> AudioDevice {
        if let bluetoothDevice = preferredBluetoothOutputDevice() {
            return bluetoothDevice
        }
        if let externalDevice = preferredExternalOutputDevice() {
            return externalDevice
        }
        if hasBuiltInReceiverRoute && proximityMonitoringActive && isProximityNear {
            return availableOutputDevice(kind: .earpiece)
        }
        return availableOutputDevice(kind: .speakerphone)
    }

    private func preferredBluetoothOutputDevice() -> AudioDevice? {
        let bluetoothDevices = availableDevicesHolder.value.filter { device in
            (device.direction == .output || device.direction == .both) && device.kind.isBluetooth
        }
        return bluetoothDevices.first { $0.status == .active }
            ?? bluetoothDevices.first { $0.kind.isBluetoothHandsFree }
            ?? bluetoothDevices.first { $0.kind.isBluetoothLowEnergy }
            ?? bluetoothDevices.first
    }

    private func preferredExternalOutputDevice() -> AudioDevice? {
        availableDevicesHolder.value
            .filter { device in
                (device.direction == .output || device.direction == .both) && device.kind.isExternalOutputRoute
            }
            .min { lhs, rhs in
                let lhsRank = automaticRouteRank(lhs.kind)
                let rhsRank = automaticRouteRank(rhs.kind)
                if lhsRank != rhsRank { return lhsRank < rhsRank }
                return outputRouteInventoryKey(lhs) < outputRouteInventoryKey(rhs)
            }
    }

    private func clearPinnedOutputIfRouteInventoryChanged() {
        guard let pinnedOutputRouteInventory else { return }
        if pinnedOutputRouteInventory != currentOutputRouteInventory() {
            pinnedOutputDevice = nil
            self.pinnedOutputRouteInventory = nil
        }
    }

    private func applyOutputRoute(for kind: AudioDeviceKind) throws {
        if kind == .speakerphone {
            try audioSession.overrideOutputAudioPort(.speaker)
        } else {
            try audioSession.overrideOutputAudioPort(.none)
        }
    }

    private func requestManagedOutputRoute(for device: AudioDevice, failureContext: String) {
        let request = OutputRouteRequest(id: device.id, kind: device.kind)
        guard pendingManagedOutputRequest != request else { return }
        guard !isOutputRouteActive(for: device) else { return }
        pendingManagedOutputRequest = request

        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await self.applyUserSelectedOutputRoute(for: device)
            } catch {
                self.logger?.log(.error, tag: "Audio", "\(failureContext) route apply failed: \(error)")
            }
            if self.pendingManagedOutputRequest == request {
                self.pendingManagedOutputRequest = nil
            }
        }
    }

    private func isOutputRouteActive(for device: AudioDevice) -> Bool {
        let outputs = audioSession.currentRoute.outputs
        switch device.kind {
        case .speakerphone:
            return outputs.contains { $0.portType == .builtInSpeaker }
        case .earpiece:
            return outputs.contains { $0.portType == .builtInReceiver }
        case .bluetooth:
            return outputs.contains { output in
                output.uid == device.id || (device.id.isEmpty && output.portType.isBluetoothRoute)
            }
        case .wiredHeadset:
            return outputs.contains { output in
                output.uid == device.id || (device.id.isEmpty && (output.portType == .headphones || output.portType == .headsetMic))
            }
        case .carAudio:
            return outputs.contains { output in output.uid == device.id || (device.id.isEmpty && output.portType == .carAudio) }
        case .usb:
            return outputs.contains { output in output.uid == device.id || (device.id.isEmpty && output.portType == .usbAudio) }
        case .other:
            return false
        }
    }

    private func currentOutputRouteInventory() -> Set<String> {
        Set(
            availableDevicesHolder.value
                .filter { $0.direction == .output || $0.direction == .both }
                .map(outputRouteInventoryKey)
        )
    }

    private func outputRouteInventoryKey(_ device: AudioDevice) -> String {
        let routeName = device.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = device.id.isEmpty ? routeName : device.id
        switch device.kind {
        case .speakerphone:
            return "speakerphone"
        case .earpiece:
            return "earpiece"
        case .bluetooth(_):
            return "bluetooth:\(fallback)"
        case .wiredHeadset:
            return "wired"
        case .carAudio:
            return "car:\(fallback)"
        case .usb:
            return "usb:\(fallback)"
        case .other:
            return "other:\(fallback)"
        }
    }

    private func applyUserSelectedOutputRoute(for device: AudioDevice) async throws {
#if canImport(WebRTC)
        let queue = routeConfigurationQueue
        let audioSession = audioSession
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                let rtcAudioSession = RTCAudioSession.sharedInstance()
                rtcAudioSession.lockForConfiguration()
                defer { rtcAudioSession.unlockForConfiguration() }

                do {
                    try Self.applyUserSelectedOutputRoute(for: device, audioSession: audioSession, rtcAudioSession: rtcAudioSession)
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
#else
        try applyOutputRoute(for: device.kind)
#endif
    }

#if canImport(WebRTC)
    private nonisolated static func applyUserSelectedOutputRoute(
        for device: AudioDevice,
        audioSession: AVAudioSession,
        rtcAudioSession: RTCAudioSession
    ) throws {
        let kind = device.kind
        let options = kind == .earpiece ? phoneAudioSessionOptions : callAudioSessionOptions
        try rtcAudioSessionSetCallCategory(options: options, rtcAudioSession: rtcAudioSession)

        switch kind {
        case .speakerphone:
            try rtcAudioSessionOverrideOutput(.speaker, rtcAudioSession: rtcAudioSession)
        case .earpiece:
            try rtcAudioSessionSetPreferredInput(.builtInMic, audioSession: audioSession, rtcAudioSession: rtcAudioSession)
            try rtcAudioSessionOverrideOutput(.none, rtcAudioSession: rtcAudioSession)
        case .bluetooth(let profile):
            let portTypes = bluetoothPortTypes(for: profile)
            let input = audioSession.availableInputs?.first { $0.uid == device.id && portTypes.contains($0.portType) }
                ?? audioSession.availableInputs?.first { portTypes.contains($0.portType) }
            if let input {
                try rtcAudioSession.setPreferredInput(input)
            } else if profile != .a2dp {
                throw audioRouteError("missing preferred bluetooth input")
            }
            try rtcAudioSessionOverrideOutput(.none, rtcAudioSession: rtcAudioSession)
        case .wiredHeadset:
            let input = audioSession.availableInputs?.first { $0.uid == device.id && $0.portType == .headsetMic }
            try rtcAudioSessionSetPreferredInput(input, fallbackPortType: .headsetMic, audioSession: audioSession, rtcAudioSession: rtcAudioSession)
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
        try rtcAudioSessionSetPreferredInput(input, fallbackPortType: portType, audioSession: audioSession, rtcAudioSession: rtcAudioSession)
    }

    private nonisolated static func rtcAudioSessionSetPreferredInput(
        _ input: AVAudioSessionPortDescription?,
        fallbackPortType: AVAudioSession.Port,
        audioSession: AVAudioSession,
        rtcAudioSession: RTCAudioSession
    ) throws {
        let input = input ?? audioSession.availableInputs?.first { $0.portType == fallbackPortType }
        guard let input else { throw audioRouteError("missing preferred input \(fallbackPortType.rawValue)") }
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

    private nonisolated static func bluetoothPortTypes(for profile: BluetoothProfile) -> Set<AVAudioSession.Port> {
        switch profile {
        case .hfp:
            return [.bluetoothHFP]
        case .a2dp:
            return [.bluetoothA2DP]
        case .ble:
            return [.bluetoothLE]
        case .unknown:
            return [.bluetoothHFP, .bluetoothA2DP, .bluetoothLE]
        }
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

    private func isPinnedOutputDeviceAvailable(_ pinnedDevice: AudioDevice) -> Bool {
        switch pinnedDevice.kind {
        case .speakerphone:
            return true
        case .earpiece:
            return hasBuiltInReceiverRoute
        default:
            return availableDevicesHolder.value.contains { device in
                (device.direction == .output || device.direction == .both) && outputRouteInventoryKey(device) == outputRouteInventoryKey(pinnedDevice)
            }
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

    private var hasBuiltInReceiverRoute: Bool {
        builtInReceiverRouteObserved || audioSession.currentRoute.outputs.contains { $0.portType == .builtInReceiver }
    }

    private func updateDevicesAndRoute() {
        let route = audioSession.currentRoute
        if route.outputs.contains(where: { $0.portType == .builtInReceiver }) {
            builtInReceiverRouteObserved = true
        }

        var devices = [AudioDevice]()

        for port in audioSession.availableInputs ?? [] {
            let isActiveInput = route.inputs.contains { $0.uid == port.uid }
            let inputDevice = mapPortToAudioDevice(port, direction: .input, status: isActiveInput ? .active : .available)
            devices.append(inputDevice)

            if port.portType == .bluetoothHFP || port.portType == .bluetoothA2DP || port.portType == .bluetoothLE || port.portType == .headsetMic || port.portType == .usbAudio {
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

        if hasBuiltInReceiverRoute {
            let isEarpieceActive = route.outputs.contains { $0.portType == .builtInReceiver }
            let earpieceDevice = AudioDevice(
                id: "earpiece",
                displayName: "Earpiece",
                kind: .earpiece,
                direction: .output,
                status: isEarpieceActive ? .active : .available
            )
            devices.append(earpieceDevice)
        }

        for port in route.outputs {
            if port.portType != .builtInSpeaker && port.portType != .builtInReceiver && port.portType != .bluetoothHFP && port.portType != .bluetoothA2DP && port.portType != .bluetoothLE && port.portType != .usbAudio {
                devices.append(mapPortToAudioDevice(port, direction: .output, status: .active))
            }
        }

        let activeInput = route.inputs.first.map { mapPortToAudioDevice($0, direction: .input, status: .active) }
        let activeOutput = route.outputs.first.map { mapPortToAudioDevice($0, direction: .output, status: .active) }

        availableDevicesHolder.update(devices)
        effectiveInputDeviceHolder.update(activeInput)
        effectiveOutputDeviceHolder.update(activeOutput)
        emitEvent(.availableDevicesChanged(devices))
    }

    private func emitEvent(_ event: AudioCoordinatorEvent) {
        eventsHolder.emit(event)
    }

    private func availableOutputDevice(kind: AudioDeviceKind) -> AudioDevice {
        availableDevicesHolder.value.first { device in
            (device.direction == .output || device.direction == .both) && device.kind == kind
        } ?? AudioDevice(
            id: kind.defaultOutputId,
            displayName: kind.defaultOutputDisplayName,
            kind: kind,
            direction: .output,
            status: .available
        )
    }
}

private extension AudioDeviceKind {
    var isExternalOutputRoute: Bool {
        switch self {
        case .wiredHeadset, .carAudio, .usb, .other:
            return true
        case .bluetooth, .speakerphone, .earpiece:
            return false
        }
    }

    var isBluetooth: Bool {
        if case .bluetooth = self { return true }
        return false
    }

    var isBluetoothHandsFree: Bool {
        if case .bluetooth(profile: .hfp) = self { return true }
        return false
    }

    var isBluetoothLowEnergy: Bool {
        if case .bluetooth(profile: .ble) = self { return true }
        return false
    }

    var defaultOutputId: String {
        switch self {
        case .speakerphone:
            return "speaker"
        case .earpiece:
            return "earpiece"
        case .bluetooth:
            return "bluetooth"
        case .wiredHeadset:
            return "wired"
        case .carAudio:
            return "car"
        case .usb:
            return "usb"
        case .other:
            return "other"
        }
    }

    var defaultOutputDisplayName: String {
        switch self {
        case .speakerphone:
            return "Speaker"
        case .earpiece:
            return "Earpiece"
        case .bluetooth:
            return "Bluetooth"
        case .wiredHeadset:
            return "Headset"
        case .carAudio:
            return "Car audio"
        case .usb:
            return "USB audio"
        case .other:
            return "Audio"
        }
    }
}

private extension AVAudioSession.Port {
    var isBluetoothRoute: Bool {
        self == .bluetoothHFP || self == .bluetoothA2DP || self == .bluetoothLE
    }
}

private func automaticRouteRank(_ kind: AudioDeviceKind) -> Int {
    switch kind {
    case .wiredHeadset:
        return 0
    case .carAudio, .usb:
        return 1
    case .other:
        return 2
    case .bluetooth, .speakerphone, .earpiece:
        return 3
    }
}
