package app.serenada.core.call

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

internal class DefaultAudioCoordinator(
    context: Context,
    private val handler: Handler,
    private val proximityMonitoringEnabled: Boolean,
    private val onProximityChanged: (Boolean) -> Unit,
    private val onAudioEnvironmentChanged: () -> Unit,
    private val logger: SerenadaLogger? = null,
) : SessionAudioController, SerenadaAudioCoordinator {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var audioSessionActive = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn = false
    private var previousMicrophoneMute = false
    private var proximityMonitoringActive = false
    private var isProximityNear = false
    private var audioDeviceMonitoringActive = false
    private var communicationDeviceChangedListener: Any? = null
    private var bluetoothScoActive = false
    private var pinnedOutputKind: AudioDeviceKind? = null
    private val communicationDeviceExecutor = Executor { command ->
        handler.post(command)
    }

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    private val _effectiveInputDevice = MutableStateFlow<AudioDevice?>(null)
    override val effectiveInputDevice: StateFlow<AudioDevice?> = _effectiveInputDevice.asStateFlow()

    private val _effectiveOutputDevice = MutableStateFlow<AudioDevice?>(null)
    override val effectiveOutputDevice: StateFlow<AudioDevice?> = _effectiveOutputDevice.asStateFlow()

    private val _events = MutableSharedFlow<AudioCoordinatorEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AudioCoordinatorEvent> = _events.asSharedFlow()

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        logger?.log(SerenadaLogLevel.DEBUG, "Audio", "Audio focus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                _events.tryEmit(AudioCoordinatorEvent.FocusLost(true))
                _events.tryEmit(AudioCoordinatorEvent.AudioSessionInterrupted(InterruptionReason.SYSTEM_AUDIO))
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                _events.tryEmit(AudioCoordinatorEvent.FocusLost(true))
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusGranted = false
                _events.tryEmit(AudioCoordinatorEvent.FocusLost(false))
                _events.tryEmit(AudioCoordinatorEvent.AudioSessionInterrupted(InterruptionReason.SYSTEM_AUDIO))
                handler.post {
                    if (!audioSessionActive) return@post
                    requestAudioFocus()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusGranted = true
                _events.tryEmit(AudioCoordinatorEvent.FocusRegained)
                _events.tryEmit(AudioCoordinatorEvent.AudioSessionResumed)
            }
            else -> Unit
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            onAudioDevicesChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            onAudioDevicesChanged()
        }
    }

    private val proximitySensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val maxRange = proximitySensor?.maximumRange ?: return
            val distance = event.values.firstOrNull() ?: return
            val near = distance < maxRange
            if (near == isProximityNear) return
            isProximityNear = near
            onProximityChanged(near)
            applyCallAudioRouting()
            updateDevicesAndRoute()
            onAudioEnvironmentChanged()
            _events.tryEmit(AudioCoordinatorEvent.EffectiveRouteChanged(_effectiveInputDevice.value, _effectiveOutputDevice.value))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun activate() {
        if (audioSessionActive) return
        audioSessionActive = true
        previousAudioMode = audioManager.mode
        previousSpeakerphoneOn = isSpeakerphoneEnabled()
        previousMicrophoneMute = audioManager.isMicrophoneMute
        requestAudioFocus()
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isMicrophoneMute = false
            startAudioDeviceMonitoring()
            if (proximityMonitoringEnabled) {
                startProximityMonitoring()
            }
            updateDevicesAndRoute()
            applyCallAudioRouting()
            updateDevicesAndRoute()
            onAudioEnvironmentChanged()
        }.onSuccess {
            logger?.log(
                SerenadaLogLevel.DEBUG,
                "Audio",
                "Audio session activated (prevMode=$previousAudioMode, focusGranted=$audioFocusGranted)"
            )
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to activate audio session: ${error.message}")
        }
    }

    override fun deactivate() {
        if (!audioSessionActive) {
            abandonAudioFocus()
            return
        }
        audioSessionActive = false
        pinnedOutputKind = null
        stopProximityMonitoring()
        stopAudioDeviceMonitoring()
        runCatching {
            setLegacyBluetoothScoRouting(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isMicrophoneMute = previousMicrophoneMute
            setSpeakerphoneEnabled(previousSpeakerphoneOn)
            audioManager.mode = previousAudioMode
        }.onSuccess {
            logger?.log(SerenadaLogLevel.DEBUG, "Audio", "Audio session restored (mode=$previousAudioMode)")
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to restore audio session: ${error.message}")
        }
        abandonAudioFocus()
    }

    override fun shouldPauseVideoForProximity(isScreenSharing: Boolean): Boolean {
        return proximityMonitoringActive &&
            isProximityNear &&
            !isScreenSharing &&
            !isBluetoothHeadsetConnected()
    }

    // MARK: - SerenadaAudioCoordinator Conformance

    override suspend fun activateCallSession(intent: AudioIntent): AudioCoordinatorCapabilities {
        activate()
        return AudioCoordinatorCapabilities(
            pttPolicy = PttPolicy.BLOCK,
            canShareInput = true,
            sessionOwnership = SessionOwnership.SDK_OWNED,
            supportedDeviceKinds = listOf(
                AudioDeviceKind.WiredHeadset,
                AudioDeviceKind.Bluetooth(BluetoothProfile.UNKNOWN),
                AudioDeviceKind.Speakerphone,
                AudioDeviceKind.Earpiece
            )
        )
    }

    override suspend fun deactivateCallSession() {
        deactivate()
    }

    override suspend fun applyRouting(device: AudioDevice) {
        if (!device.isOutputRoute()) return
        pinnedOutputKind = device.kind
        applyOutputRoute(device.kind)
        refreshDevicesAndRouteFromSystem()
    }

    private fun applyOutputRoute(kind: AudioDeviceKind) {
        when (kind) {
            is AudioDeviceKind.Speakerphone -> routeAudioToSpeaker()
            is AudioDeviceKind.Earpiece -> routeAudioToEarpiece()
            is AudioDeviceKind.Bluetooth -> routeAudioToBluetooth()
            else -> routeAudioToExternal(kind)
        }
        scheduleRouteRefreshFromSystem()
    }

    private fun scheduleRouteRefreshFromSystem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        handler.postDelayed(
            { refreshDevicesAndRouteFromSystem() },
            COMMUNICATION_ROUTE_REFRESH_DELAY_MS
        )
    }

    private fun refreshDevicesAndRouteFromSystem() {
        if (!audioSessionActive) return
        updateDevicesAndRoute()
        onAudioEnvironmentChanged()
        _events.tryEmit(AudioCoordinatorEvent.EffectiveRouteChanged(_effectiveInputDevice.value, _effectiveOutputDevice.value))
    }

    override suspend fun setMicMuted(muted: Boolean) {
        // No-op to avoid mutating process-global AudioManager.isMicrophoneMute
        if (!muted) {
            ensureAudioFocus()
        }
    }

    private fun ensureAudioFocus() {
        if (!audioFocusGranted) {
            requestAudioFocus()
        }
    }

    override suspend fun suspendCapture() {
        // No-op for default coordinator
    }

    override suspend fun resumeCapture() {
        // No-op for default coordinator
    }

    private fun onAudioDevicesChanged() {
        if (!audioSessionActive) return
        updateDevicesAndRoute()
        applyCallAudioRouting()
        refreshDevicesAndRouteFromSystem()
    }

    private fun onCommunicationDeviceChanged() {
        refreshDevicesAndRouteFromSystem()
    }

    private fun startProximityMonitoring() {
        if (proximityMonitoringActive) return
        val manager = sensorManager ?: return
        val sensor = proximitySensor ?: return
        val registered = runCatching {
            manager.registerListener(
                proximitySensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                handler
            )
        }.getOrElse { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to register proximity listener: ${error.message}")
            false
        }
        if (registered) {
            proximityMonitoringActive = true
            isProximityNear = false
        }
    }

    private fun stopProximityMonitoring() {
        if (!proximityMonitoringActive) {
            isProximityNear = false
            return
        }
        runCatching {
            sensorManager?.unregisterListener(proximitySensorListener)
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to unregister proximity listener: ${error.message}")
        }
        proximityMonitoringActive = false
        isProximityNear = false
    }

    private fun startAudioDeviceMonitoring() {
        if (!audioDeviceMonitoringActive) {
            runCatching {
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
                audioDeviceMonitoringActive = true
            }.onFailure { error ->
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to register audio device callback: ${error.message}")
            }
        }
        startCommunicationDeviceMonitoring()
    }

    private fun stopAudioDeviceMonitoring() {
        stopCommunicationDeviceMonitoring()
        if (audioDeviceMonitoringActive) {
            runCatching {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            }.onFailure { error ->
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to unregister audio device callback: ${error.message}")
            }
            audioDeviceMonitoringActive = false
        }
    }

    private fun startCommunicationDeviceMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || communicationDeviceChangedListener != null) return
        val listener = AudioManager.OnCommunicationDeviceChangedListener {
            onCommunicationDeviceChanged()
        }
        runCatching {
            audioManager.addOnCommunicationDeviceChangedListener(communicationDeviceExecutor, listener)
            communicationDeviceChangedListener = listener
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to register communication device callback: ${error.message}")
        }
    }

    private fun stopCommunicationDeviceMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            communicationDeviceChangedListener = null
            return
        }
        val listener = communicationDeviceChangedListener as? AudioManager.OnCommunicationDeviceChangedListener ?: return
        runCatching {
            audioManager.removeOnCommunicationDeviceChangedListener(listener)
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to unregister communication device callback: ${error.message}")
        }
        communicationDeviceChangedListener = null
    }

    private fun applyCallAudioRouting() {
        if (!audioSessionActive) return
        pinnedOutputKind?.let { kind ->
            if (isPinnedOutputKindAvailable(kind)) {
                applyOutputRoute(kind)
                return
            }
            pinnedOutputKind = null
        }
        if (isBluetoothHeadsetConnected()) {
            routeAudioToBluetooth()
            return
        }
        if (proximityMonitoringActive && isProximityNear) {
            routeAudioToEarpiece()
            return
        }
        routeAudioToSpeaker()
    }

    private fun routeAudioToBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothDevice = findBluetoothCommunicationDevice()
            if (bluetoothDevice == null || !audioManager.setCommunicationDevice(bluetoothDevice)) {
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to route audio to Bluetooth headset")
                routeAudioToSpeaker()
            }
            return
        }
        setSpeakerphoneEnabled(false)
        setLegacyBluetoothScoRouting(true)
    }

    private fun routeAudioToEarpiece() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setLegacyBluetoothScoRouting(false)
            if (!setCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) {
                routeAudioToSpeaker()
            }
            return
        }
        setLegacyBluetoothScoRouting(false)
        setSpeakerphoneEnabled(false)
    }

    private fun routeAudioToSpeaker() {
        setLegacyBluetoothScoRouting(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!setCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)) {
                audioManager.clearCommunicationDevice()
            }
            return
        }
        setSpeakerphoneEnabled(true)
    }

    private fun routeAudioToExternal(kind: AudioDeviceKind) {
        setLegacyBluetoothScoRouting(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!setCommunicationDevice(kind)) {
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to route audio to external device kind=$kind")
            }
            return
        }
        setSpeakerphoneEnabled(false)
    }

    private fun setCommunicationDevice(type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == type }
            ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    private fun setCommunicationDevice(kind: AudioDeviceKind): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull { info ->
            deviceKindMatches(mapDeviceKind(info.type), kind)
        } ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    private fun isBluetoothHeadsetConnected(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            findBluetoothCommunicationDevice() != null
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                isBluetoothHeadsetType(device.type)
            }
        }
    }

    private fun findBluetoothCommunicationDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return audioManager.availableCommunicationDevices.firstOrNull { device ->
            isBluetoothHeadsetType(device.type)
        }
    }

    private fun isBluetoothHeadsetType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    @Suppress("DEPRECATION")
    private fun setLegacyBluetoothScoRouting(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothScoActive = false
            return
        }
        if (enabled) {
            if (!bluetoothScoActive) {
                audioManager.startBluetoothSco()
                bluetoothScoActive = true
            }
            audioManager.isBluetoothScoOn = true
            return
        }
        if (bluetoothScoActive) {
            audioManager.stopBluetoothSco()
            bluetoothScoActive = false
        }
        audioManager.isBluetoothScoOn = false
    }

    private fun isSpeakerphoneEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }
    }

    private fun setSpeakerphoneEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker == null || !audioManager.setCommunicationDevice(speaker)) {
                    logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to route audio to built-in speaker")
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
            return
        }

        @Suppress("DEPRECATION")
        run {
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    private fun isPinnedOutputKindAvailable(kind: AudioDeviceKind): Boolean {
        return _availableDevices.value.any { device ->
            device.isOutputRoute() && deviceKindMatches(device.kind, kind)
        }
    }

    private fun requestAudioFocus() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request =
                audioFocusRequest
                    ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(audioFocusChangeListener)
                        .build()
                        .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        audioFocusGranted = granted
        logger?.log(SerenadaLogLevel.DEBUG, "Audio", "Audio focus request granted=$granted")
    }

    private fun abandonAudioFocus() {
        if (!audioFocusGranted) return
        audioFocusGranted = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = audioFocusRequest
                if (request != null) {
                    audioManager.abandonAudioFocusRequest(request)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
            Unit
        }.onSuccess {
            logger?.log(SerenadaLogLevel.DEBUG, "Audio", "Audio focus abandoned")
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to abandon audio focus: ${error.message}")
        }
    }

    private fun mapDeviceKind(type: Int): AudioDeviceKind {
        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioDeviceKind.WiredHeadset
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceKind.Bluetooth(BluetoothProfile.HFP)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioDeviceKind.Bluetooth(BluetoothProfile.A2DP)
            AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioDeviceKind.Bluetooth(BluetoothProfile.BLE)
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioDeviceKind.Speakerphone
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioDeviceKind.Earpiece
            AudioDeviceInfo.TYPE_AUX_LINE -> AudioDeviceKind.CarAudio
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceKind.Usb
            else -> AudioDeviceKind.Other
        }
    }

    private fun mapDeviceDirection(info: AudioDeviceInfo): AudioDeviceDirection {
        return if (info.isSource && info.isSink) {
            AudioDeviceDirection.BOTH
        } else if (info.isSource) {
            AudioDeviceDirection.INPUT
        } else {
            AudioDeviceDirection.OUTPUT
        }
    }

    private fun mapDeviceInfo(info: AudioDeviceInfo, status: AudioDeviceStatus = AudioDeviceStatus.AVAILABLE): AudioDevice {
        val kind = mapDeviceKind(info.type)
        return AudioDevice(
            id = info.id.toString(),
            displayName = info.productName.toString(),
            kind = kind,
            direction = mapDeviceDirection(info),
            status = status
        )
    }

    private fun AudioDevice.isOutputRoute(): Boolean {
        return direction == AudioDeviceDirection.OUTPUT || direction == AudioDeviceDirection.BOTH
    }

    private fun deviceKindMatches(actual: AudioDeviceKind, expected: AudioDeviceKind): Boolean {
        return when {
            actual is AudioDeviceKind.Bluetooth && expected is AudioDeviceKind.Bluetooth -> true
            else -> actual == expected
        }
    }

    private fun AudioDeviceInfo.isPublishableRoute(communicationDevices: List<AudioDeviceInfo>): Boolean {
        val direction = mapDeviceDirection(this)
        if (direction == AudioDeviceDirection.INPUT) return true
        if (mapDeviceKind(type) !is AudioDeviceKind.Other) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return communicationDevices.any { it.id == id }
    }

    private fun updateDevicesAndRoute() {
        val allDevices = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
            .toList()
        val communicationDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            emptyList()
        }
        val list = (allDevices + communicationDevices)
            .distinctBy { it.id }
            .filter { it.isPublishableRoute(communicationDevices) }
            .map { mapDeviceInfo(it, AudioDeviceStatus.AVAILABLE) }

        val activeOutput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { mapDeviceInfo(it, AudioDeviceStatus.ACTIVE) }
        } else {
            if (isSpeakerphoneEnabled()) {
                list.firstOrNull { it.kind is AudioDeviceKind.Speakerphone }?.copy(status = AudioDeviceStatus.ACTIVE)
            } else if (audioManager.isBluetoothScoOn) {
                list.firstOrNull { it.kind is AudioDeviceKind.Bluetooth }?.copy(status = AudioDeviceStatus.ACTIVE)
            } else if (allDevices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }) {
                list.firstOrNull { it.kind is AudioDeviceKind.WiredHeadset }?.copy(status = AudioDeviceStatus.ACTIVE)
            } else if (allDevices.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }) {
                list.firstOrNull { it.kind is AudioDeviceKind.Usb }?.copy(status = AudioDeviceStatus.ACTIVE)
            } else {
                list.firstOrNull { it.kind is AudioDeviceKind.Earpiece }?.copy(status = AudioDeviceStatus.ACTIVE)
            }
        }

        val activeInput = if (activeOutput != null && (activeOutput.kind is AudioDeviceKind.Bluetooth || activeOutput.kind is AudioDeviceKind.WiredHeadset || activeOutput.kind is AudioDeviceKind.Usb)) {
            list.firstOrNull { it.direction == AudioDeviceDirection.INPUT && it.kind == activeOutput.kind }?.copy(status = AudioDeviceStatus.ACTIVE)
                ?: list.firstOrNull { it.direction == AudioDeviceDirection.INPUT }?.copy(status = AudioDeviceStatus.ACTIVE)
        } else {
            list.firstOrNull { it.direction == AudioDeviceDirection.INPUT && it.kind is AudioDeviceKind.Earpiece }?.copy(status = AudioDeviceStatus.ACTIVE)
                ?: list.firstOrNull { it.direction == AudioDeviceDirection.INPUT }?.copy(status = AudioDeviceStatus.ACTIVE)
        }

        val updatedList = list.map { device ->
            if (device.id == activeOutput?.id || device.id == activeInput?.id) {
                device.copy(status = AudioDeviceStatus.ACTIVE)
            } else {
                device
            }
        }

        _availableDevices.value = updatedList
        _effectiveInputDevice.value = activeInput
        _effectiveOutputDevice.value = activeOutput
        _events.tryEmit(AudioCoordinatorEvent.AvailableDevicesChanged(updatedList))
    }

    private companion object {
        private const val COMMUNICATION_ROUTE_REFRESH_DELAY_MS = 300L
    }
}
