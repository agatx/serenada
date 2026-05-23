import Foundation

public enum BluetoothProfile: Hashable, Sendable {
    case hfp
    case a2dp
    case ble
    case unknown
}

public enum AudioDeviceDirection: Hashable, Sendable {
    case input
    case output
    case both
}

public enum AudioDeviceStatus: Hashable, Sendable {
    case available
    case connecting
    case active
}

public enum AudioDeviceKind: Hashable, Sendable {
    case wiredHeadset
    case bluetooth(profile: BluetoothProfile)
    case speakerphone
    case earpiece
    case carAudio
    case usb
    case other
}

public struct AudioDevice: Hashable, Sendable {
    public let id: String
    public let displayName: String
    public let kind: AudioDeviceKind
    public let direction: AudioDeviceDirection
    public let status: AudioDeviceStatus

    public init(id: String, displayName: String, kind: AudioDeviceKind, direction: AudioDeviceDirection, status: AudioDeviceStatus) {
        self.id = id
        self.displayName = displayName
        self.kind = kind
        self.direction = direction
        self.status = status
    }
}

public struct AudioIntent: Equatable, Sendable {
    public var supportsVideo: Bool
    public var requiresCapture: Bool = true
    public var requiresPlayback: Bool = true
    public var preferredDevice: AudioDevice?
    public var enableProximityEarpiece: Bool = true
    public var muteOwnMicDuringExternalAudio: Bool = true
    public var duckOwnPlaybackDuringExternalAudio: Bool = true
    public var exclusiveSession: Bool = false

    public init(
        supportsVideo: Bool = false,
        requiresCapture: Bool = true,
        requiresPlayback: Bool = true,
        preferredDevice: AudioDevice? = nil,
        enableProximityEarpiece: Bool = true,
        muteOwnMicDuringExternalAudio: Bool = true,
        duckOwnPlaybackDuringExternalAudio: Bool = true,
        exclusiveSession: Bool = false
    ) {
        self.supportsVideo = supportsVideo
        self.requiresCapture = requiresCapture
        self.requiresPlayback = requiresPlayback
        self.preferredDevice = preferredDevice
        self.enableProximityEarpiece = enableProximityEarpiece
        self.muteOwnMicDuringExternalAudio = muteOwnMicDuringExternalAudio
        self.duckOwnPlaybackDuringExternalAudio = duckOwnPlaybackDuringExternalAudio
        self.exclusiveSession = exclusiveSession
    }
}

public enum PttPolicy: Sendable {
    case coexist
    case preempt
    case block
}

public enum SessionOwnership: Sendable {
    case sdkOwned
    case hostOwned
    case systemOwned
}

public struct AudioCoordinatorCapabilities: Sendable {
    public let pttPolicy: PttPolicy
    public let canShareInput: Bool
    public let sessionOwnership: SessionOwnership
    public let supportedDeviceKinds: [AudioDeviceKind]

    public init(pttPolicy: PttPolicy, canShareInput: Bool, sessionOwnership: SessionOwnership, supportedDeviceKinds: [AudioDeviceKind]) {
        self.pttPolicy = pttPolicy
        self.canShareInput = canShareInput
        self.sessionOwnership = sessionOwnership
        self.supportedDeviceKinds = supportedDeviceKinds
    }
}

public enum InterruptionReason: Sendable {
    case phoneCall
    case otherAudio
    case pttTransmission
    case systemAudio
    case unknown
}

public enum AudioCoordinatorEvent: Sendable {
    case availableDevicesChanged([AudioDevice])
    case effectiveRouteChanged(input: AudioDevice?, output: AudioDevice?)
    case audioSessionInterrupted(reason: InterruptionReason)
    case audioSessionResumed
    case inputUnavailable
    case inputAvailable
    case focusLost(transient: Bool)
    case focusRegained
}

public protocol SerenadaAudioCoordinator: AnyObject, Sendable {
    func activateCallSession(intent: AudioIntent) async throws -> AudioCoordinatorCapabilities
    func deactivateCallSession() async
    func applyRouting(_ device: AudioDevice) async throws
    func setMicMuted(_ muted: Bool) async throws
    func suspendCapture() async throws
    func resumeCapture() async throws

    var availableDevices: AsyncStream<[AudioDevice]> { get }
    var effectiveInputDevice: AsyncStream<AudioDevice?> { get }
    var effectiveOutputDevice: AsyncStream<AudioDevice?> { get }
    var events: AsyncStream<AudioCoordinatorEvent> { get }
}
