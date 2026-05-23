package app.serenada.sample

import app.serenada.core.call.AudioDevice
import app.serenada.core.call.AudioDeviceDirection
import app.serenada.core.call.AudioDeviceKind
import app.serenada.core.call.AudioDeviceStatus
import app.serenada.core.call.AudioIntent
import app.serenada.core.call.AudioCoordinatorCapabilities
import app.serenada.core.call.AudioCoordinatorEvent
import app.serenada.core.call.PttPolicy
import app.serenada.core.call.SessionOwnership
import app.serenada.core.call.SerenadaAudioCoordinator
import app.serenada.core.call.InterruptionReason
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log

class SampleAudioCoordinator : SerenadaAudioCoordinator {
    private val TAG = "SampleAudioCoordinator"

    private val speaker = AudioDevice(
        id = "speaker",
        displayName = "Mock Speaker",
        kind = AudioDeviceKind.Speakerphone,
        direction = AudioDeviceDirection.OUTPUT,
        status = AudioDeviceStatus.ACTIVE
    )
    
    private val mic = AudioDevice(
        id = "mic",
        displayName = "Mock Mic",
        kind = AudioDeviceKind.Earpiece,
        direction = AudioDeviceDirection.INPUT,
        status = AudioDeviceStatus.ACTIVE
    )

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(listOf(speaker, mic))
    override val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    private val _effectiveInputDevice = MutableStateFlow<AudioDevice?>(mic)
    override val effectiveInputDevice: StateFlow<AudioDevice?> = _effectiveInputDevice.asStateFlow()

    private val _effectiveOutputDevice = MutableStateFlow<AudioDevice?>(speaker)
    override val effectiveOutputDevice: StateFlow<AudioDevice?> = _effectiveOutputDevice.asStateFlow()

    private val _events = MutableSharedFlow<AudioCoordinatorEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AudioCoordinatorEvent> = _events.asSharedFlow()

    override suspend fun activateCallSession(intent: AudioIntent): AudioCoordinatorCapabilities {
        Log.d(TAG, "activateCallSession called with intent: $intent")
        return AudioCoordinatorCapabilities(
            pttPolicy = PttPolicy.COEXIST,
            canShareInput = true,
            sessionOwnership = SessionOwnership.HOST_OWNED,
            supportedDeviceKinds = listOf(
                AudioDeviceKind.Speakerphone,
                AudioDeviceKind.Earpiece
            )
        )
    }

    override suspend fun deactivateCallSession() {
        Log.d(TAG, "deactivateCallSession called")
    }

    override suspend fun applyRouting(device: AudioDevice) {
        Log.d(TAG, "applyRouting called for device: ${device.displayName}")
        _effectiveOutputDevice.value = device
        _events.tryEmit(AudioCoordinatorEvent.EffectiveRouteChanged(_effectiveInputDevice.value, device))
    }

    override suspend fun setMicMuted(muted: Boolean) {
        Log.d(TAG, "setMicMuted: $muted")
    }

    override suspend fun suspendCapture() {
        Log.d(TAG, "suspendCapture called")
    }

    override suspend fun resumeCapture() {
        Log.d(TAG, "resumeCapture called")
    }

    fun simulatePttPress(active: Boolean) {
        if (active) {
            Log.d(TAG, "Simulating PTT Press: Interrupting audio session")
            _events.tryEmit(AudioCoordinatorEvent.AudioSessionInterrupted(InterruptionReason.PTT_TRANSMISSION))
        } else {
            Log.d(TAG, "Simulating PTT Release: Resuming audio session")
            _events.tryEmit(AudioCoordinatorEvent.AudioSessionResumed)
        }
    }
}
