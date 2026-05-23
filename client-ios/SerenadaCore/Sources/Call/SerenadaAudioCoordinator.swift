import Foundation

/// Bluetooth route profile reported by an audio coordinator.
public enum BluetoothProfile: Hashable, Sendable {
    /// Hands-free profile, usually suitable for two-way call audio.
    case hfp
    /// Advanced audio distribution profile, usually playback-only.
    case a2dp
    /// Bluetooth Low Energy audio route.
    case ble
    /// Bluetooth route with an unknown or platform-specific profile.
    case unknown
}

/// Direction in which an audio device can be used by the call.
public enum AudioDeviceDirection: Hashable, Sendable {
    /// Device can capture audio.
    case input
    /// Device can play audio.
    case output
    /// Device can both capture and play audio.
    case both
}

/// Availability state for a coordinator-published audio device.
public enum AudioDeviceStatus: Hashable, Sendable {
    /// Device is available but not currently selected.
    case available
    /// Device is in the process of becoming active.
    case connecting
    /// Device is the active input or output route.
    case active
}

/// Logical category for an audio route shown to the host app.
public enum AudioDeviceKind: Hashable, Sendable {
    /// Wired headset or headphones route.
    case wiredHeadset
    /// Bluetooth audio route with the reported profile.
    case bluetooth(profile: BluetoothProfile)
    /// Built-in loudspeaker route.
    case speakerphone
    /// Built-in earpiece route.
    case earpiece
    /// Car audio route.
    case carAudio
    /// USB audio route.
    case usb
    /// Device kind not covered by the known route categories.
    case other
}

/// Audio route exposed by ``SerenadaAudioCoordinator``.
public struct AudioDevice: Hashable, Sendable {
    /// Stable coordinator-defined identifier used for route selection.
    public let id: String
    /// Human-readable route name for UI.
    public let displayName: String
    /// Logical route category.
    public let kind: AudioDeviceKind
    /// Whether the route supports input, output, or both.
    public let direction: AudioDeviceDirection
    /// Current availability or active state.
    public let status: AudioDeviceStatus

    /// Creates an audio route descriptor.
    ///
    /// - Parameters:
    ///   - id: Stable coordinator-defined identifier used for route selection.
    ///   - displayName: Human-readable route name for UI.
    ///   - kind: Logical route category.
    ///   - direction: Whether the route supports input, output, or both.
    ///   - status: Current availability or active state.
    public init(id: String, displayName: String, kind: AudioDeviceKind, direction: AudioDeviceDirection, status: AudioDeviceStatus) {
        self.id = id
        self.displayName = displayName
        self.kind = kind
        self.direction = direction
        self.status = status
    }
}

/// Host policy for how the SDK should activate and react to shared audio.
public struct AudioIntent: Equatable, Sendable {
    /// Whether the session expects video call behavior.
    public var supportsVideo: Bool
    /// Whether the call needs microphone capture.
    public var requiresCapture: Bool = true
    /// Whether the call needs remote audio playback.
    public var requiresPlayback: Bool = true
    /// Initial preferred audio route, if known.
    public var preferredDevice: AudioDevice?
    /// Whether the SDK may use proximity-based earpiece routing.
    public var enableProximityEarpiece: Bool = true
    /// Whether coordinator interruptions should mute the local WebRTC mic.
    public var muteOwnMicDuringExternalAudio: Bool = true
    /// Whether coordinator interruptions should lower remote playback volume.
    public var duckOwnPlaybackDuringExternalAudio: Bool = true
    /// Whether the host expects this session to own audio exclusively.
    public var exclusiveSession: Bool = false

    /// Creates an audio intent for call-session activation.
    ///
    /// - Parameters:
    ///   - supportsVideo: Whether the session expects video call behavior.
    ///   - requiresCapture: Whether the call needs microphone capture.
    ///   - requiresPlayback: Whether the call needs remote audio playback.
    ///   - preferredDevice: Initial preferred audio route, if known.
    ///   - enableProximityEarpiece: Whether the SDK may use proximity-based earpiece routing.
    ///   - muteOwnMicDuringExternalAudio: Whether coordinator interruptions should mute the local WebRTC mic.
    ///   - duckOwnPlaybackDuringExternalAudio: Whether coordinator interruptions should lower remote playback volume.
    ///   - exclusiveSession: Whether the host expects this session to own audio exclusively.
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

/// SDK behavior to apply when push-to-talk audio overlaps with a Serenada call.
public enum PttPolicy: Sendable {
    /// Let push-to-talk and the Serenada call run concurrently.
    case coexist
    /// End or leave the Serenada call when push-to-talk starts.
    case preempt
    /// Treat push-to-talk as unavailable while the Serenada call is active.
    case block
}

/// Component that owns the process audio session while a call is active.
public enum SessionOwnership: Sendable {
    /// The Serenada SDK owns audio session activation.
    case sdkOwned
    /// The host app owns audio session activation through a custom coordinator.
    case hostOwned
    /// The operating system or another framework owns the active audio session.
    case systemOwned
}

/// Capabilities returned by a coordinator when a call audio session is activated.
public struct AudioCoordinatorCapabilities: Sendable {
    /// Policy for push-to-talk overlap.
    public let pttPolicy: PttPolicy
    /// Whether the coordinator can share microphone capture with the SDK.
    public let canShareInput: Bool
    /// Current owner of the process audio session.
    public let sessionOwnership: SessionOwnership
    /// Route categories the coordinator can expose or select.
    public let supportedDeviceKinds: [AudioDeviceKind]

    /// Creates a capability snapshot returned from coordinator activation.
    ///
    /// - Parameters:
    ///   - pttPolicy: Policy for push-to-talk overlap.
    ///   - canShareInput: Whether the coordinator can share microphone capture with the SDK.
    ///   - sessionOwnership: Current owner of the process audio session.
    ///   - supportedDeviceKinds: Route categories the coordinator can expose or select.
    public init(pttPolicy: PttPolicy, canShareInput: Bool, sessionOwnership: SessionOwnership, supportedDeviceKinds: [AudioDeviceKind]) {
        self.pttPolicy = pttPolicy
        self.canShareInput = canShareInput
        self.sessionOwnership = sessionOwnership
        self.supportedDeviceKinds = supportedDeviceKinds
    }
}

/// Reason an audio coordinator reports a call-audio interruption.
public enum InterruptionReason: Sendable {
    /// A phone call interrupted the session.
    case phoneCall
    /// Another app or audio engine interrupted the session.
    case otherAudio
    /// Push-to-talk transmission interrupted or overlapped the session.
    case pttTransmission
    /// System audio focus or routing interrupted the session.
    case systemAudio
    /// The coordinator could not classify the interruption.
    case unknown
}

/// Events emitted by ``SerenadaAudioCoordinator`` and consumed by ``SerenadaSession``.
public enum AudioCoordinatorEvent: Sendable {
    /// Available route list changed.
    case availableDevicesChanged([AudioDevice])
    /// Effective input or output route changed.
    case effectiveRouteChanged(input: AudioDevice?, output: AudioDevice?)
    /// The audio session was interrupted by external audio or system focus.
    case audioSessionInterrupted(reason: InterruptionReason)
    /// The audio session interruption ended and normal call audio may resume.
    case audioSessionResumed
    /// Microphone input is temporarily unavailable.
    case inputUnavailable
    /// Microphone input became available again.
    case inputAvailable
    /// Audio focus was lost.
    case focusLost(transient: Bool)
    /// Audio focus was regained.
    case focusRegained
}

/// Host-provided audio coordination contract for Serenada call sessions.
///
/// Implement this protocol when the host app needs to own process-global audio state, custom
/// Bluetooth routing, push-to-talk coexistence, or other audio-session policy. Leave
/// ``SerenadaConfig/audioCoordinator`` nil to use the SDK's internal default.
public protocol SerenadaAudioCoordinator: AnyObject, Sendable {
    /// Activate audio for a Serenada call and return the policy/capabilities available for this session.
    func activateCallSession(intent: AudioIntent) async throws -> AudioCoordinatorCapabilities

    /// Deactivate call audio and release coordinator-owned route or focus state.
    func deactivateCallSession() async

    /// Apply a user-selected audio route.
    func applyRouting(_ device: AudioDevice) async throws

    /// Notify the coordinator that the user-facing microphone mute state changed.
    func setMicMuted(_ muted: Bool) async throws

    /// Suspend local capture when the coordinator cannot share input with the SDK.
    func suspendCapture() async throws

    /// Resume local capture after ``suspendCapture()``.
    func resumeCapture() async throws

    /// Current coordinator-published audio routes.
    var availableDevices: AsyncStream<[AudioDevice]> { get }

    /// Current effective input route, or nil when input is unavailable.
    var effectiveInputDevice: AsyncStream<AudioDevice?> { get }

    /// Current effective output route, or nil when output is unavailable.
    var effectiveOutputDevice: AsyncStream<AudioDevice?> { get }

    /// Audio route, focus, interruption, and capture availability events.
    var events: AsyncStream<AudioCoordinatorEvent> { get }
}
