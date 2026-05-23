package app.serenada.core.call

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow

enum class BluetoothProfile {
    HFP, A2DP, BLE, UNKNOWN
}

enum class AudioDeviceDirection {
    INPUT, OUTPUT, BOTH
}

enum class AudioDeviceStatus {
    AVAILABLE, CONNECTING, ACTIVE
}

sealed class AudioDeviceKind {
    object WiredHeadset : AudioDeviceKind()
    data class Bluetooth(val profile: BluetoothProfile) : AudioDeviceKind()
    object Speakerphone : AudioDeviceKind()
    object Earpiece : AudioDeviceKind()
    object CarAudio : AudioDeviceKind()
    object Usb : AudioDeviceKind()
    object Other : AudioDeviceKind()
}

data class AudioDevice(
    val id: String,
    val displayName: String,
    val kind: AudioDeviceKind,
    val direction: AudioDeviceDirection,
    val status: AudioDeviceStatus
)

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

enum class PttPolicy {
    COEXIST, PREEMPT, BLOCK
}

enum class SessionOwnership {
    SDK_OWNED, HOST_OWNED, SYSTEM_OWNED
}

data class AudioCoordinatorCapabilities(
    val pttPolicy: PttPolicy = PttPolicy.BLOCK,
    val canShareInput: Boolean = true,
    val sessionOwnership: SessionOwnership = SessionOwnership.SDK_OWNED,
    val supportedDeviceKinds: List<AudioDeviceKind> = emptyList()
)

enum class InterruptionReason {
    PHONE_CALL, OTHER_AUDIO, PTT_TRANSMISSION, SYSTEM_AUDIO, UNKNOWN
}

sealed class AudioCoordinatorEvent {
    data class AvailableDevicesChanged(val devices: List<AudioDevice>) : AudioCoordinatorEvent()
    data class EffectiveRouteChanged(val input: AudioDevice?, val output: AudioDevice?) : AudioCoordinatorEvent()
    data class AudioSessionInterrupted(val reason: InterruptionReason) : AudioCoordinatorEvent()
    object AudioSessionResumed : AudioCoordinatorEvent()
    object InputUnavailable : AudioCoordinatorEvent()
    object InputAvailable : AudioCoordinatorEvent()
    data class FocusLost(val transient: Boolean) : AudioCoordinatorEvent()
    object FocusRegained : AudioCoordinatorEvent()
}

interface SerenadaAudioCoordinator {
    suspend fun activateCallSession(intent: AudioIntent): AudioCoordinatorCapabilities
    suspend fun deactivateCallSession()
    suspend fun applyRouting(device: AudioDevice)
    suspend fun setMicMuted(muted: Boolean)
    suspend fun suspendCapture()
    suspend fun resumeCapture()

    val availableDevices: StateFlow<List<AudioDevice>>
    val effectiveInputDevice: StateFlow<AudioDevice?>
    val effectiveOutputDevice: StateFlow<AudioDevice?>
    val events: SharedFlow<AudioCoordinatorEvent>
}
