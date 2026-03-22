import AVFoundation
import Foundation
import UIKit

@MainActor
public final class CallAudioSessionController: SessionAudioController {
    private var onProximityChanged: (Bool) -> Void
    private var onAudioEnvironmentChanged: () -> Void
    private let logger: SerenadaLogger?

    private let audioSession = AVAudioSession.sharedInstance()

    private var audioSessionActive = false
    private var proximityMonitoringActive = false
    private var isProximityNear = false

    public init(
        onProximityChanged: @escaping (Bool) -> Void,
        onAudioEnvironmentChanged: @escaping () -> Void,
        logger: SerenadaLogger? = nil
    ) {
        self.onProximityChanged = onProximityChanged
        self.onAudioEnvironmentChanged = onAudioEnvironmentChanged
        self.logger = logger
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

        startMonitoring()
        applyCallAudioRouting()
        onAudioEnvironmentChanged()
    }

    public func deactivate() {
        guard audioSessionActive else {
            stopMonitoring()
            return
        }

        audioSessionActive = false
        stopMonitoring()

        do {
            try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            logger?.log(.error, tag: "Audio", "failed to deactivate audio session: \(error)")
        }
    }

    public func shouldPauseVideoForProximity(isScreenSharing: Bool) -> Bool {
        proximityMonitoringActive && isProximityNear && !isScreenSharing && !isBluetoothHeadsetConnected()
    }

    private func startMonitoring() {
        guard !proximityMonitoringActive else { return }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )

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

    private func stopMonitoring() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: UIDevice.proximityStateDidChangeNotification, object: nil)

        UIDevice.current.isProximityMonitoringEnabled = false
        proximityMonitoringActive = false
        isProximityNear = false
    }

    @objc private func handleAudioRouteChange(_ notification: Notification) {
        guard audioSessionActive else { return }
        applyCallAudioRouting()
        onAudioEnvironmentChanged()
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
}
