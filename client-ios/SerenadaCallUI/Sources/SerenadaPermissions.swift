import AVFoundation
import Foundation
import SerenadaCore

/// Helper for requesting camera and microphone permissions.
/// Used by SerenadaCallFlow in URL-first mode, or available for host apps
/// in session-first mode via the `sessionRequiresPermissions` delegate callback.
public enum SerenadaPermissions {
    /// Request the specified media permissions.
    /// Returns `true` if all requested permissions are granted.
    public static func request(_ permissions: [MediaCapability], completion: @escaping (Bool) -> Void) {
        let group = DispatchGroup()
        let lock = NSLock()
        var results = [Bool]()

        for permission in permissions {
            group.enter()
            switch permission {
            case .camera:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    lock.lock()
                    results.append(granted)
                    lock.unlock()
                    group.leave()
                }
            case .microphone:
                AVCaptureDevice.requestAccess(for: .audio) { granted in
                    lock.lock()
                    results.append(granted)
                    lock.unlock()
                    group.leave()
                }
            }
        }

        group.notify(queue: .main) {
            completion(results.allSatisfy { $0 })
        }
    }

    /// Async version of `request(_:completion:)`.
    public static func request(_ permissions: [MediaCapability]) async -> Bool {
        await withCheckedContinuation { continuation in
            request(permissions) { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    /// Check current authorization status for a media capability without prompting.
    public static func authorizationStatus(for capability: MediaCapability) -> AVAuthorizationStatus {
        switch capability {
        case .camera:
            return AVCaptureDevice.authorizationStatus(for: .video)
        case .microphone:
            return AVCaptureDevice.authorizationStatus(for: .audio)
        }
    }
}
