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
    /// Whether the call needs microphone capture.
    ///
    /// This is a policy hint for custom coordinators; it does not disable SDK WebRTC track creation.
    public var requiresCapture: Bool = true
    /// Whether the call needs remote audio playback.
    ///
    /// This is a policy hint for custom coordinators; it does not disable SDK WebRTC track creation.
    public var requiresPlayback: Bool = true
    /// Initial preferred audio route, if known.
    public var preferredDevice: AudioDevice?
    /// Whether the SDK may use proximity-based earpiece routing.
    public var enableProximityEarpiece: Bool = true
    /// Whether external audio should mute the local WebRTC mic.
    public var muteDuringExternalAudio: Bool = true
    /// Whether external audio should lower remote playback volume.
    public var duckDuringExternalAudio: Bool = true

    /// Creates an audio intent for call-session activation.
    ///
    /// - Parameters:
    ///   - requiresCapture: Whether the call needs microphone capture. This is a policy hint for custom coordinators; it does not disable SDK WebRTC track creation.
    ///   - requiresPlayback: Whether the call needs remote audio playback. This is a policy hint for custom coordinators; it does not disable SDK WebRTC track creation.
    ///   - preferredDevice: Initial preferred audio route, if known.
    ///   - enableProximityEarpiece: Whether the SDK may use proximity-based earpiece routing.
    ///   - muteDuringExternalAudio: Whether external audio should mute the local WebRTC mic.
    ///   - duckDuringExternalAudio: Whether external audio should lower remote playback volume.
    public init(
        requiresCapture: Bool = true,
        requiresPlayback: Bool = true,
        preferredDevice: AudioDevice? = nil,
        enableProximityEarpiece: Bool = true,
        muteDuringExternalAudio: Bool = true,
        duckDuringExternalAudio: Bool = true
    ) {
        self.requiresCapture = requiresCapture
        self.requiresPlayback = requiresPlayback
        self.preferredDevice = preferredDevice
        self.enableProximityEarpiece = enableProximityEarpiece
        self.muteDuringExternalAudio = muteDuringExternalAudio
        self.duckDuringExternalAudio = duckDuringExternalAudio
    }
}

/// Events emitted by ``SerenadaAudioCoordinator`` and consumed by ``SerenadaSession``.
public enum AudioCoordinatorEvent: Sendable {
    /// Available route list changed.
    case availableDevicesChanged([AudioDevice])
    /// Effective input or output route changed.
    case effectiveRouteChanged(input: AudioDevice?, output: AudioDevice?)
    /// Host-owned audio is temporarily active and the SDK should apply its external-audio policy.
    case externalAudioStarted
    /// Host-owned external audio ended and normal call audio may resume.
    case externalAudioEnded
    /// The host re-activated the call audio session after a same-app audio owner (for example a
    /// PTT framework transmit) held and released it. Same-app session takeovers post no
    /// `AVAudioSession.interruptionNotification`, so WebRTC's internal interruption recovery never
    /// runs; on this event the SDK restarts its audio unit so capture and playback resume, in
    /// addition to the ``externalAudioEnded`` policy reset.
    case audioSessionRestarted
    /// Another audio owner requested playback ducking without interrupting local capture.
    case playbackDuckingStarted
    /// Playback ducking is no longer needed.
    case playbackDuckingEnded
}

/// Host-provided audio coordination contract for Serenada call sessions.
///
/// Implement this protocol when the host app needs to own process-global audio state, custom
/// Bluetooth routing, or other audio-session policy. Leave
/// ``SerenadaConfig/audioCoordinator`` nil to use the SDK's internal default.
public protocol SerenadaAudioCoordinator: AnyObject, Sendable {
    /// Activate audio for a Serenada call.
    ///
    /// Custom iOS coordinators own `AVAudioSession` activation while this call is active. Configure
    /// the category/mode/route policy and call `setActive(true)` before returning so WebRTC audio
    /// can flow while the SDK has manual WebRTC audio enabled.
    func activateCallSession(intent: AudioIntent) async throws

    /// Deactivate call audio and release coordinator-owned route or focus state.
    ///
    /// Custom iOS coordinators should restore or deactivate their `AVAudioSession` state here.
    func deactivateCallSession() async

    /// Apply a user-selected audio route.
    func applyRouting(_ device: AudioDevice) async throws

    /// Notify the coordinator that the user-facing microphone mute state changed.
    func setMicMuted(_ muted: Bool) async throws

    /// Current coordinator-published audio routes.
    var availableDevices: AsyncStream<[AudioDevice]> { get }

    /// Current effective input route, or nil when input is unavailable.
    var effectiveInputDevice: AsyncStream<AudioDevice?> { get }

    /// Current effective output route, or nil when output is unavailable.
    var effectiveOutputDevice: AsyncStream<AudioDevice?> { get }

    /// Audio route and external-audio events.
    var events: AsyncStream<AudioCoordinatorEvent> { get }
}
