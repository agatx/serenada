import AVFoundation
import Foundation
import UIKit

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
public final class DefaultAudioCoordinator: NSObject, @preconcurrency SerenadaAudioCoordinator, SessionAudioController, @unchecked Sendable {
    private let availableDevicesHolder = ContinuationHolder<[AudioDevice]>(initialValue: [])
    private let effectiveInputDeviceHolder = ContinuationHolder<AudioDevice?>(initialValue: nil)
    private let effectiveOutputDeviceHolder = ContinuationHolder<AudioDevice?>(initialValue: nil)
    private let eventsHolder = EventHolder<AudioCoordinatorEvent>()

    private var onProximityChanged: (Bool) -> Void
    private var onAudioEnvironmentChanged: () -> Void
    private let logger: SerenadaLogger?
    private let proximityMonitoringEnabled: Bool

    private let audioSession = AVAudioSession.sharedInstance()

    private var audioSessionActive = false
    private var proximityMonitoringActive = false
    private var isProximityNear = false
    private var pinnedOutputKind: AudioDeviceKind?

    public init(
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

    public func setOnProximityChanged(_ handler: @escaping (Bool) -> Void) {
        onProximityChanged = handler
    }

    public func setOnAudioEnvironmentChanged(_ handler: @escaping () -> Void) {
        onAudioEnvironmentChanged = handler
    }

    public func activate() {
        guard !audioSessionActive else { return }
        audioSessionActive = true

        do {
            try audioSession.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.allowBluetooth, .allowBluetoothA2DP, .mixWithOthers]
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

    public func deactivate() {
        guard audioSessionActive else {
            stopProximityMonitoring()
            return
        }

        audioSessionActive = false
        pinnedOutputKind = nil
        stopAudioRouteMonitoring()
        stopProximityMonitoring()

        do {
            try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            logger?.log(.error, tag: "Audio", "failed to deactivate audio session: \(error)")
        }
    }

    public func shouldPauseVideoForProximity(isScreenSharing: Bool) -> Bool {
        proximityMonitoringActive && isProximityNear && !isScreenSharing && !isBluetoothHeadsetConnected()
    }

    // MARK: - SerenadaAudioCoordinator Conformance

    public func activateCallSession(intent: AudioIntent) async throws -> AudioCoordinatorCapabilities {
        activate()
        return AudioCoordinatorCapabilities(
            pttPolicy: .block,
            canShareInput: true,
            sessionOwnership: .sdkOwned,
            supportedDeviceKinds: [.wiredHeadset, .bluetooth(profile: .unknown), .speakerphone, .earpiece]
        )
    }

    public func deactivateCallSession() async {
        deactivate()
    }

    public func applyRouting(_ device: AudioDevice) async throws {
        if device.direction == .output || device.direction == .both {
            pinnedOutputKind = device.kind
            try applyOutputRoute(for: device.kind)
        }
        updateDevicesAndRoute()
        onAudioEnvironmentChanged()
    }

    public func setMicMuted(_ muted: Bool) async throws {
        // No-op for default coordinator
    }

    public func suspendCapture() async throws {
        // No-op for default coordinator
    }

    public func resumeCapture() async throws {
        // No-op for default coordinator
    }

    public var availableDevices: AsyncStream<[AudioDevice]> {
        availableDevicesHolder.makeStream()
    }

    public var effectiveInputDevice: AsyncStream<AudioDevice?> {
        effectiveInputDeviceHolder.makeStream()
    }

    public var effectiveOutputDevice: AsyncStream<AudioDevice?> {
        effectiveOutputDeviceHolder.makeStream()
    }

    public var events: AsyncStream<AudioCoordinatorEvent> {
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
    }

    private func stopAudioRouteMonitoring() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.mediaServicesWereResetNotification, object: nil)
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
            self.applyCallAudioRouting()
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
                if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                    let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                    if options.contains(.shouldResume) {
                        self.emitEvent(.audioSessionResumed)
                    }
                } else {
                    self.emitEvent(.audioSessionResumed)
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
                    options: [.allowBluetooth, .allowBluetoothA2DP, .mixWithOthers]
                )
                try self.audioSession.setActive(true)
                self.updateDevicesAndRoute()
                self.applyCallAudioRouting()
                self.onAudioEnvironmentChanged()
                self.emitEvent(.audioSessionResumed)
            } catch {
                self.logger?.log(.error, tag: "Audio", "failed to reset media services: \(error)")
            }
        }
    }

    private func applyCallAudioRouting() {
        guard audioSessionActive else { return }

        if let pinnedOutputKind {
            do {
                try applyOutputRoute(for: pinnedOutputKind)
            } catch {
                logger?.log(.error, tag: "Audio", "pinned route apply failed: \(error)")
            }
            return
        }

        if isBluetoothHeadsetConnected() {
            do {
                try audioSession.overrideOutputAudioPort(.none)
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
}
