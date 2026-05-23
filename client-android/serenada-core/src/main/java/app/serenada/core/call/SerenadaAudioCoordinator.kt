package app.serenada.core.call

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow

/** Bluetooth route profile reported by an audio coordinator. */
enum class BluetoothProfile {
    /** Hands-free profile, usually suitable for two-way call audio. */
    HFP,
    /** Advanced audio distribution profile, usually playback-only. */
    A2DP,
    /** Bluetooth Low Energy audio route. */
    BLE,
    /** Bluetooth route with an unknown or platform-specific profile. */
    UNKNOWN
}

/** Direction in which an audio device can be used by the call. */
enum class AudioDeviceDirection {
    /** Device can capture audio. */
    INPUT,
    /** Device can play audio. */
    OUTPUT,
    /** Device can both capture and play audio. */
    BOTH
}

/** Availability state for a coordinator-published audio device. */
enum class AudioDeviceStatus {
    /** Device is available but not currently selected. */
    AVAILABLE,
    /** Device is in the process of becoming active. */
    CONNECTING,
    /** Device is the active input or output route. */
    ACTIVE
}

/** Logical category for an audio route shown to the host app. */
sealed class AudioDeviceKind {
    /** Wired headset or headphones route. */
    object WiredHeadset : AudioDeviceKind()
    /** Bluetooth audio route with the reported profile. */
    data class Bluetooth(val profile: BluetoothProfile) : AudioDeviceKind()
    /** Built-in loudspeaker route. */
    object Speakerphone : AudioDeviceKind()
    /** Built-in earpiece route. */
    object Earpiece : AudioDeviceKind()
    /** Car audio route. */
    object CarAudio : AudioDeviceKind()
    /** USB audio route. */
    object Usb : AudioDeviceKind()
    /** Device kind not covered by the known route categories. */
    object Other : AudioDeviceKind()
}

/**
 * Audio route exposed by [SerenadaAudioCoordinator].
 *
 * @property id Stable coordinator-defined identifier used for route selection.
 * @property displayName Human-readable route name for UI.
 * @property kind Logical route category.
 * @property direction Whether the route supports input, output, or both.
 * @property status Current availability or active state.
 */
data class AudioDevice(
    val id: String,
    val displayName: String,
    val kind: AudioDeviceKind,
    val direction: AudioDeviceDirection,
    val status: AudioDeviceStatus
)

/**
 * Host policy for how the SDK should activate and react to shared audio.
 *
 * @property supportsVideo Whether the session expects video call behavior.
 * @property requiresCapture Whether the call needs microphone capture.
 * @property requiresPlayback Whether the call needs remote audio playback.
 * @property preferredDevice Initial preferred audio route, if known.
 * @property enableProximityEarpiece Whether the SDK may use proximity-based earpiece routing.
 * @property muteOwnMicDuringExternalAudio Whether coordinator interruptions should mute the local WebRTC mic.
 * @property duckOwnPlaybackDuringExternalAudio Whether coordinator interruptions should lower remote playback volume.
 * @property exclusiveSession Whether the host expects this session to own audio exclusively.
 */
data class AudioIntent(
    val supportsVideo: Boolean = false,
    val requiresCapture: Boolean = true,
    val requiresPlayback: Boolean = true,
    val preferredDevice: AudioDevice? = null,
    val enableProximityEarpiece: Boolean = true,
    val muteOwnMicDuringExternalAudio: Boolean = true,
    val duckOwnPlaybackDuringExternalAudio: Boolean = true,
    val exclusiveSession: Boolean = false
)

/** SDK behavior to apply when push-to-talk audio overlaps with a Serenada call. */
enum class PttPolicy {
    /** Let push-to-talk and the Serenada call run concurrently. */
    COEXIST,
    /** End or leave the Serenada call when push-to-talk starts. */
    PREEMPT,
    /** Treat push-to-talk as unavailable while the Serenada call is active. */
    BLOCK
}

/** Component that owns the process audio session while a call is active. */
enum class SessionOwnership {
    /** The Serenada SDK owns audio session activation. */
    SDK_OWNED,
    /** The host app owns audio session activation through a custom coordinator. */
    HOST_OWNED,
    /** The operating system or another framework owns the active audio session. */
    SYSTEM_OWNED
}

/**
 * Capabilities returned by a coordinator when a call audio session is activated.
 *
 * @property pttPolicy Policy for push-to-talk overlap.
 * @property canShareInput Whether the coordinator can share microphone capture with the SDK.
 * @property sessionOwnership Current owner of the process audio session.
 * @property supportedDeviceKinds Route categories the coordinator can expose or select.
 */
data class AudioCoordinatorCapabilities(
    val pttPolicy: PttPolicy = PttPolicy.BLOCK,
    val canShareInput: Boolean = true,
    val sessionOwnership: SessionOwnership = SessionOwnership.SDK_OWNED,
    val supportedDeviceKinds: List<AudioDeviceKind> = emptyList()
)

/** Reason an audio coordinator reports a call-audio interruption. */
enum class InterruptionReason {
    /** A phone call interrupted the session. */
    PHONE_CALL,
    /** Another app or audio engine interrupted the session. */
    OTHER_AUDIO,
    /** Push-to-talk transmission interrupted or overlapped the session. */
    PTT_TRANSMISSION,
    /** Android system audio focus or routing interrupted the session. */
    SYSTEM_AUDIO,
    /** The coordinator could not classify the interruption. */
    UNKNOWN
}

/** Events emitted by [SerenadaAudioCoordinator] and consumed by [app.serenada.core.SerenadaSession]. */
sealed class AudioCoordinatorEvent {
    /** Available route list changed. */
    data class AvailableDevicesChanged(val devices: List<AudioDevice>) : AudioCoordinatorEvent()
    /** Effective input or output route changed. */
    data class EffectiveRouteChanged(val input: AudioDevice?, val output: AudioDevice?) : AudioCoordinatorEvent()
    /** The audio session was interrupted by external audio or system focus. */
    data class AudioSessionInterrupted(val reason: InterruptionReason) : AudioCoordinatorEvent()
    /** The audio session interruption ended and normal call audio may resume. */
    object AudioSessionResumed : AudioCoordinatorEvent()
    /** Microphone input is temporarily unavailable. */
    object InputUnavailable : AudioCoordinatorEvent()
    /** Microphone input became available again. */
    object InputAvailable : AudioCoordinatorEvent()
    /** Android audio focus was lost. */
    data class FocusLost(val transient: Boolean) : AudioCoordinatorEvent()
    /** Android audio focus was regained. */
    object FocusRegained : AudioCoordinatorEvent()
}

/**
 * Host-provided audio coordination contract for Serenada call sessions.
 *
 * Implement this interface when the host app needs to own process-global audio state, custom
 * Bluetooth routing, push-to-talk coexistence, or other audio-session policy. Leave
 * [app.serenada.core.SerenadaConfig.audioCoordinator] null to use the SDK's internal default.
 */
interface SerenadaAudioCoordinator {
    /**
     * Activate audio for a Serenada call and return the policy/capabilities available for this session.
     */
    suspend fun activateCallSession(intent: AudioIntent): AudioCoordinatorCapabilities

    /** Deactivate call audio and release coordinator-owned route or focus state. */
    suspend fun deactivateCallSession()

    /** Apply a user-selected audio route. */
    suspend fun applyRouting(device: AudioDevice)

    /** Notify the coordinator that the user-facing microphone mute state changed. */
    suspend fun setMicMuted(muted: Boolean)

    /** Suspend local capture when the coordinator cannot share input with the SDK. */
    suspend fun suspendCapture()

    /** Resume local capture after [suspendCapture]. */
    suspend fun resumeCapture()

    /** Current coordinator-published audio routes. */
    val availableDevices: StateFlow<List<AudioDevice>>

    /** Current effective input route, or null when input is unavailable. */
    val effectiveInputDevice: StateFlow<AudioDevice?>

    /** Current effective output route, or null when output is unavailable. */
    val effectiveOutputDevice: StateFlow<AudioDevice?>

    /** Audio route, focus, interruption, and capture availability events. */
    val events: SharedFlow<AudioCoordinatorEvent>
}
