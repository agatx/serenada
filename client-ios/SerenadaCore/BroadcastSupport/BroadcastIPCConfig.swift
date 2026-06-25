import Foundation

/// Cross-process IPC identifiers for a screen-share broadcast, derived in ONE
/// place from the host's two inputs: the shared App Group container and the
/// broadcast upload extension's bundle ID.
///
/// The same derivation is used by the main app (the frame *reader*) and by the
/// broadcast upload extension (the frame *writer*), so both processes agree on
/// the shared file names and the device-global Darwin notification names without
/// duplicating string-building across the process boundary. Darwin notification
/// names are device-global, so they are namespaced by the extension bundle ID
/// (globally unique) to avoid collisions across apps; the frame and sidecar
/// files are namespaced the same way so multiple first-party extensions can
/// safely share a single App Group container.
public struct BroadcastIPCConfig: Equatable, Sendable {
    /// App Group whose shared container both processes open.
    public let appGroupIdentifier: String
    /// Bundle ID of the broadcast upload extension. Drives the system broadcast
    /// picker (`RPSystemBroadcastPickerView.preferredExtension`) and namespaces
    /// every derived identifier below.
    public let extensionBundleId: String

    public init(appGroupIdentifier: String, extensionBundleId: String) {
        self.appGroupIdentifier = appGroupIdentifier
        self.extensionBundleId = extensionBundleId
    }

    /// Memory-mapped frame file in the shared container.
    public var sharedFileName: String { "\(extensionBundleId).frame.dat" }

    /// Session lifecycle sidecar file in the shared container.
    public var sidecarFileName: String { "\(extensionBundleId).session.json" }

    /// Extension → app: a broadcast began and is writing frames.
    public var darwinNotifyStarted: String { "\(extensionBundleId).started" }
    /// Extension → app: the broadcast finished.
    public var darwinNotifyFinished: String { "\(extensionBundleId).finished" }
    /// App → extension: stop the broadcast.
    public var darwinNotifyRequestStop: String { "\(extensionBundleId).requestStop" }
}
