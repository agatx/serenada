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
public final class DefaultAudioCoordinator: NSObject, SerenadaAudioCoordinator, SessionAudioController, @unchecked Sendable {
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
        if device.kind == .speakerphone {
            try audioSession.overrideOutputAudioPort(.speaker)
        } else {
            try audioSession.overrideOutputAudioPort(.none)
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
    }

    private func stopAudioRouteMonitoring() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
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
        guard audioSessionActive else { return }
        updateDevicesAndRoute()
        applyCallAudioRouting()
        onAudioEnvironmentChanged()

        let inputs = audioSession.currentRoute.inputs
        let outputs = audioSession.currentRoute.outputs
        let activeInput = inputs.first.map { mapPortToAudioDevice($0, status: .active) }
        let activeOutput = outputs.first.map { mapPortToAudioDevice($0, status: .active) }
        emitEvent(.effectiveRouteChanged(input: activeInput, output: activeOutput))
    }

    @objc private func handleProximityStateChange(_ notification: Notification) {
        guard proximityMonitoringActive else { return }
        let near = UIDevice.current.proximityState
        guard near != isProximityNear else { return }

        isProximityNear = near
        onProximityChanged(near)
        applyCallAudioRouting()
        onAudioEnvironmentChanged()
    }

    private func applyCallAudioRouting() {
        guard audioSessionActive else { return }

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

    private func mapPortToAudioDevice(_ port: AVAudioSessionPortDescription, status: AudioDeviceStatus = .available) -> AudioDevice {
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

        let direction: AudioDeviceDirection = port.portType == .builtInMic || port.portType == .headsetMic ? .input : .output

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
            let isActive = route.inputs.contains { $0.uid == port.uid }
            devices.append(mapPortToAudioDevice(port, status: isActive ? .active : .available))
        }

        for port in route.outputs {
            devices.append(mapPortToAudioDevice(port, status: .active))
        }

        let activeInput = route.inputs.first.map { mapPortToAudioDevice($0, status: .active) }
        let activeOutput = route.outputs.first.map { mapPortToAudioDevice($0, status: .active) }

        availableDevicesHolder.update(devices)
        effectiveInputDeviceHolder.update(activeInput)
        effectiveOutputDeviceHolder.update(activeOutput)
    }

    private func emitEvent(_ event: AudioCoordinatorEvent) {
        eventsHolder.emit(event)
    }
}
