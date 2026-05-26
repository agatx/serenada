import AVFoundation
import Foundation
import SerenadaCore

public final class SampleAudioCoordinator: SerenadaAudioCoordinator, @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<[AudioDevice]>.Continuation] = [:]
    private var inputContinuations: [UUID: AsyncStream<AudioDevice?>.Continuation] = [:]
    private var outputContinuations: [UUID: AsyncStream<AudioDevice?>.Continuation] = [:]
    private var eventContinuations: [UUID: AsyncStream<AudioCoordinatorEvent>.Continuation] = [:]

    private var devices: [AudioDevice] = []
    private var activeInput: AudioDevice?
    private var activeOutput: AudioDevice?

    public init() {
        let speaker = AudioDevice(id: "speaker", displayName: "Mock Speaker", kind: .speakerphone, direction: .output, status: .active)
        let mic = AudioDevice(id: "mic", displayName: "Mock Mic", kind: .earpiece, direction: .input, status: .active)
        self.devices = [speaker, mic]
        self.activeInput = mic
        self.activeOutput = speaker
    }

    public func activateCallSession(intent: AudioIntent) async throws {
        print("[SampleAudioCoordinator] activateCallSession called with intent: \(intent)")
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetoothHFP, .allowBluetoothA2DP, .mixWithOthers])
        try session.setActive(true)
    }

    public func deactivateCallSession() async {
        print("[SampleAudioCoordinator] deactivateCallSession called")
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    public func applyRouting(_ device: AudioDevice) async throws {
        print("[SampleAudioCoordinator] applyRouting called for device: \(device.displayName)")
        lock.lock()
        activeOutput = device
        let currentOutputContinuations = Array(outputContinuations.values)
        let currentInput = activeInput
        lock.unlock()
        
        for c in currentOutputContinuations {
            c.yield(device)
        }
        
        emit(.effectiveRouteChanged(input: currentInput, output: device))
    }

    public func setMicMuted(_ muted: Bool) async throws {
        print("[SampleAudioCoordinator] setMicMuted: \(muted)")
    }

    public func simulateExternalAudio(_ active: Bool) {
        if active {
            print("[SampleAudioCoordinator] Simulating external audio start")
            emit(.externalAudioStarted)
        } else {
            print("[SampleAudioCoordinator] Simulating external audio end")
            emit(.externalAudioEnded)
        }
    }

    private func emit(_ event: AudioCoordinatorEvent) {
        lock.lock()
        let currentEventContinuations = Array(eventContinuations.values)
        lock.unlock()
        
        for c in currentEventContinuations {
            c.yield(event)
        }
    }

    public var availableDevices: AsyncStream<[AudioDevice]> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            continuations[id] = continuation
            let initial = devices
            lock.unlock()
            
            continuation.yield(initial)
            
            continuation.onTermination = { [weak self] _ in
                self?.lock.lock()
                self?.continuations.removeValue(forKey: id)
                self?.lock.unlock()
            }
        }
    }

    public var effectiveInputDevice: AsyncStream<AudioDevice?> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            inputContinuations[id] = continuation
            let initial = activeInput
            lock.unlock()
            
            continuation.yield(initial)
            
            continuation.onTermination = { [weak self] _ in
                self?.lock.lock()
                self?.inputContinuations.removeValue(forKey: id)
                self?.lock.unlock()
            }
        }
    }

    public var effectiveOutputDevice: AsyncStream<AudioDevice?> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            outputContinuations[id] = continuation
            let initial = activeOutput
            lock.unlock()
            
            continuation.yield(initial)
            
            continuation.onTermination = { [weak self] _ in
                self?.lock.lock()
                self?.outputContinuations.removeValue(forKey: id)
                self?.lock.unlock()
            }
        }
    }

    public var events: AsyncStream<AudioCoordinatorEvent> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            eventContinuations[id] = continuation
            lock.unlock()
            
            continuation.onTermination = { [weak self] _ in
                self?.lock.lock()
                self?.eventContinuations.removeValue(forKey: id)
                self?.lock.unlock()
            }
        }
    }
}
