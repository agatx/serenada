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

/// Identity of the foreground media lease under which a default audio coordinator
/// is driving `AVAudioSession`/`RTCAudioSession` (Phase 4, contract §6).
///
/// The coordinator's own async continuations and OS observers (route change,
/// interruption, media-services reset, silence hint) can fire AFTER a switch has
/// handed foreground to a NEW call. Each such path captures the lease it was
/// scheduled under and re-checks it against the coordinator's CURRENT lease before
/// re-driving the shared audio session; a stale lease is dropped so a superseded
/// session cannot deactivate/reconfigure the audio session the new foreground
/// owner just activated.
///
/// Two fences, matching the session-level fence in `completeForegroundActivation`:
/// - `ownerTokenId` — the live lease owner (an old session's token never matches
///   after the registry hands the lease to a new call).
/// - `generation` — the arbiter operation generation, bumped per activation
///   attempt (including rollback), so even a same-owner retry supersedes a stuck
///   callback from a prior attempt.
struct AudioSessionLease: Equatable, Sendable {
    /// The arbiter owner-token id this activation runs under. `nil` for the
    /// single-call/direct path that never had a registry lease (no fencing needed
    /// when there is only ever one owner).
    let ownerTokenId: Int?
    /// The arbiter operation generation captured at activation.
    let generation: Int
}

/// Process-global record of the foreground lease currently driving the shared
/// `AVAudioSession`/`RTCAudioSession` (Phase 4, contract §6).
///
/// Each `SerenadaSession` owns its OWN `DefaultAudioCoordinator` instance, but they
/// all drive the one process-global audio session. The central iOS risk — an OLD
/// session's deactivation/observer callback re-driving the audio session after a
/// NEW session activated it — is therefore a CROSS-INSTANCE race: the stale
/// callback runs on the old coordinator instance while a different instance holds
/// the live lease. A per-instance flag cannot catch it, so the "current" lease is
/// tracked here, process-wide. A coordinator records the lease it activated under
/// and re-checks it against this global value before touching the audio session.
@MainActor
final class AudioSessionLeaseRegistry {
    /// The single process-wide instance.
    static let shared = AudioSessionLeaseRegistry()

    /// The lease the live foreground owner activated under, or `nil` when no owner
    /// is driving the session. Updated on activate and cleared on the matching
    /// deactivate.
    private(set) var activeLease: AudioSessionLease?

    init() {}

    /// Install `lease` as the process-current audio lease. A later install (a new
    /// foreground owner, or a fresh-generation rollback) supersedes any in-flight
    /// callback captured under the previous lease.
    func install(_ lease: AudioSessionLease) {
        activeLease = lease
    }

    /// Clear `lease` only if it is still current — a deactivation for an
    /// already-superseded lease must NOT clear a newer owner's live lease.
    func clearIfCurrent(_ lease: AudioSessionLease) {
        if activeLease == lease {
            activeLease = nil
        }
    }

    /// Whether `lease` is still the process-current audio lease. `nil` (the
    /// single-call/direct path that never had a registry lease) is always current —
    /// there is only ever one owner.
    func isCurrent(_ lease: AudioSessionLease?) -> Bool {
        guard let lease else { return true }
        return activeLease == lease
    }

    /// **Test-only.** Reset between XCTest cases (the singleton would otherwise leak
    /// a lease across cases).
    func resetForTests() {
        activeLease = nil
    }
}

/// Internal seam (Phase 4): a `SerenadaAudioCoordinator` that can be told which
/// foreground lease it is now driving the shared audio session under, so its own
/// delayed callbacks can be fenced. Only the SDK's `DefaultAudioCoordinator`
/// conforms; the public protocol and any host `SerenadaAudioCoordinator` /
/// `CustomAudioCoordinatorAdapter` are unaffected — a host coordinator owns its own
/// `AVAudioSession` sequencing and is responsible for its own fencing.
@MainActor
protocol LeaseAwareAudioCoordinator: AnyObject {
    /// Install the lease this coordinator is now driving the audio session under.
    /// Called by the session from INSIDE the audio lifecycle task, AFTER the
    /// previous lifecycle op (a possibly-pending old deactivate) has settled and
    /// IMMEDIATELY before `activateCallSession` (Phase 4, contract §6). Installing
    /// it any earlier lets a still-pending old `deactivateCallSession` (running as
    /// the previous task drains) consume/clear the newly-installed lease before the
    /// matching activate runs. Records the lease both locally and in the
    /// process-global ``AudioSessionLeaseRegistry`` so a cross-instance stale
    /// callback can be fenced.
    func setForegroundLease(_ lease: AudioSessionLease)
    /// Snapshot the lease currently installed on this coordinator at REQUEST time,
    /// so the session can fence a deactivation against the lease as of when the
    /// deactivation was ENQUEUED — not whatever is installed when it finally runs
    /// (a later activation may have installed a fresher lease in between). `nil`
    /// when no lease is installed (the single-call/direct path).
    func installedLeaseSnapshot() -> AudioSessionLease?
    /// Tear down the audio session, fenced by the lease captured at request time.
    /// The teardown is dropped if `lease` is no longer the process-current lease (a
    /// newer foreground owner has since activated), so an old deactivate cannot
    /// deactivate the audio session a new owner just activated (contract §6).
    func deactivateCallSession(fencedBy lease: AudioSessionLease?) async
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
