import Foundation
import Photos
import SerenadaCore
import UIKit

/// Saves a captured snapshot JPEG to the user's Photos library. The Info.plist
/// declares `NSPhotoLibraryAddUsageDescription`, so the first call triggers
/// the add-only permission prompt; subsequent calls are silent.
enum SnapshotSaver {
    enum Failure: Error {
        case decodeFailed
        case authorizationDenied
        case saveFailed(Error)
    }

    static func save(jpegData: Data, completion: @escaping @MainActor (Result<Void, Failure>) -> Void) {
        guard let image = UIImage(data: jpegData) else {
            Task { @MainActor in completion(.failure(.decodeFailed)) }
            return
        }

        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                Task { @MainActor in completion(.failure(.authorizationDenied)) }
                return
            }
            PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.creationRequestForAsset(from: image)
            } completionHandler: { success, error in
                Task { @MainActor in
                    if success {
                        completion(.success(()))
                    } else {
                        completion(.failure(.saveFailed(error ?? NSError(
                            domain: "SnapshotSaver",
                            code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "Unknown PHPhotoLibrary failure"]
                        ))))
                    }
                }
            }
        }
    }
}

extension SnapshotSaver.Failure {
    var toastDescription: String {
        switch self {
        case .decodeFailed:
            return L10n.snapshotReasonDecodeFailed
        case .authorizationDenied:
            return L10n.snapshotReasonPermissionDenied
        case .saveFailed(let error):
            return error.localizedDescription
        }
    }
}

extension SnapshotError {
    var toastDescription: String {
        switch self {
        case .streamNotActive:
            return L10n.snapshotReasonNoVideo
        case .noVideoTrack:
            return L10n.snapshotReasonNoTrack
        case .captureTimeout:
            return L10n.snapshotReasonTimeout
        case .captureFailed(let reason):
            return reason
        case .unsupportedSource:
            return L10n.snapshotReasonUnsupported
        }
    }
}
