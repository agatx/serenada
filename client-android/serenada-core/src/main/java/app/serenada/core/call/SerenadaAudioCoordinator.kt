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
 * @property requiresCapture Whether the call needs microphone capture. This is a policy hint for
 * custom coordinators; it does not disable SDK WebRTC track creation.
 * @property requiresPlayback Whether the call needs remote audio playback. This is a policy hint for
 * custom coordinators; it does not disable SDK WebRTC track creation.
 * @property preferredDevice Initial preferred audio route, if known.
 * @property enableProximityEarpiece Whether the SDK may use proximity-based earpiece routing.
 * @property muteDuringExternalAudio Whether external audio should mute the local WebRTC mic.
 * @property duckDuringExternalAudio Whether external audio should lower remote playback volume.
 */
data class AudioIntent(
    val requiresCapture: Boolean = true,
    val requiresPlayback: Boolean = true,
    val preferredDevice: AudioDevice? = null,
    val enableProximityEarpiece: Boolean = true,
    val muteDuringExternalAudio: Boolean = true,
    val duckDuringExternalAudio: Boolean = true,
)

/** Events emitted by [SerenadaAudioCoordinator] and consumed by [app.serenada.core.SerenadaSession]. */
sealed class AudioCoordinatorEvent {
    /** Available route list changed. */
    data class AvailableDevicesChanged(val devices: List<AudioDevice>) : AudioCoordinatorEvent()
    /** Effective input or output route changed. */
    data class EffectiveRouteChanged(val input: AudioDevice?, val output: AudioDevice?) : AudioCoordinatorEvent()
    /** Host-owned audio is temporarily active and the SDK should apply its external-audio policy. */
    object ExternalAudioStarted : AudioCoordinatorEvent()
    /** Host-owned external audio ended and normal call audio may resume. */
    object ExternalAudioEnded : AudioCoordinatorEvent()
    /** Another audio owner requested playback ducking without interrupting local capture. */
    object PlaybackDuckingStarted : AudioCoordinatorEvent()
    /** Playback ducking is no longer needed. */
    object PlaybackDuckingEnded : AudioCoordinatorEvent()
}

/**
 * Host-provided audio coordination contract for Serenada call sessions.
 *
 * Implement this interface when the host app needs to own process-global audio state, custom
 * Bluetooth routing, or other audio-session policy. Leave
 * [app.serenada.core.SerenadaConfig.audioCoordinator] null to use the SDK's internal default.
 */
interface SerenadaAudioCoordinator {
    /**
     * Activate audio for a Serenada call.
     */
    suspend fun activateCallSession(intent: AudioIntent)

    /** Deactivate call audio and release coordinator-owned route or focus state. */
    suspend fun deactivateCallSession()

    /** Apply a user-selected audio route. */
    suspend fun applyRouting(device: AudioDevice)

    /** Notify the coordinator that the user-facing microphone mute state changed. */
    suspend fun setMicMuted(muted: Boolean)

    /** Current coordinator-published audio routes. */
    val availableDevices: StateFlow<List<AudioDevice>>

    /** Current effective input route, or null when input is unavailable. */
    val effectiveInputDevice: StateFlow<AudioDevice?>

    /** Current effective output route, or null when output is unavailable. */
    val effectiveOutputDevice: StateFlow<AudioDevice?>

    /** Audio route and external-audio events. */
    val events: SharedFlow<AudioCoordinatorEvent>
}
