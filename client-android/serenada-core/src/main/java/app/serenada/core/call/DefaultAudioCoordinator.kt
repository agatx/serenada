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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private var proximityEarpieceEnabled = true
    private var audioDeviceMonitoringActive = false
    private var communicationDeviceChangedListener: Any? = null
    private var bluetoothScoActive = false
    private var pinnedOutputDevice: AudioDevice? = null
    private var pinnedOutputRouteInventory: Set<String>? = null

    private data class Deactivation(
        val restoreAudioSession: Boolean,
        val previousAudioMode: Int,
        val previousSpeakerphoneOn: Boolean,
        val previousMicrophoneMute: Boolean,
        val bluetoothScoWasActive: Boolean,
        val focusWasGranted: Boolean,
        val focusRequest: AudioFocusRequest?,
    )
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
    private val playbackDuckingFallbackRunnable = Runnable {
        _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingEnded)
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (!audioSessionActive) return@OnAudioFocusChangeListener
        logger?.log(SerenadaLogLevel.DEBUG, "Audio", "Audio focus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                clearPlaybackDuckingFallback()
                _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingEnded)
                _events.tryEmit(AudioCoordinatorEvent.ExternalAudioStarted)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingStarted)
                schedulePlaybackDuckingFallback()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                clearPlaybackDuckingFallback()
                _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingEnded)
                audioFocusGranted = false
                _events.tryEmit(AudioCoordinatorEvent.ExternalAudioStarted)
                handler.post {
                    if (!audioSessionActive) return@post
                    requestAudioFocus(emitRecoveryEventOnGain = true)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                clearPlaybackDuckingFallback()
                audioFocusGranted = true
                _events.tryEmit(AudioCoordinatorEvent.ExternalAudioEnded)
                _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingEnded)
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
            if (!audioSessionActive) return
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
            if (proximityMonitoringEnabled && proximityEarpieceEnabled) {
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
        val deactivation = prepareDeactivation() ?: return
        finishDeactivation(deactivation)
    }

    private fun prepareDeactivation(): Deactivation? {
        val restoreAudioSession = audioSessionActive
        if (!restoreAudioSession && !audioFocusGranted) return null
        val deactivation = Deactivation(
            restoreAudioSession = restoreAudioSession,
            previousAudioMode = previousAudioMode,
            previousSpeakerphoneOn = previousSpeakerphoneOn,
            previousMicrophoneMute = previousMicrophoneMute,
            bluetoothScoWasActive = bluetoothScoActive,
            focusWasGranted = audioFocusGranted,
            focusRequest = audioFocusRequest,
        )
        audioSessionActive = false
        proximityEarpieceEnabled = true
        pinnedOutputDevice = null
        pinnedOutputRouteInventory = null
        clearPlaybackDuckingFallback()
        stopProximityMonitoring()
        stopAudioDeviceMonitoring()
        bluetoothScoActive = false
        audioFocusGranted = false
        audioFocusRequest = null
        return deactivation
    }

    private fun finishDeactivation(deactivation: Deactivation) {
        if (deactivation.restoreAudioSession) {
            restoreAudioSession(deactivation)
        }
        abandonAudioFocus(deactivation.focusWasGranted, deactivation.focusRequest)
    }

    override fun shouldPauseVideoForProximity(isScreenSharing: Boolean): Boolean {
        return proximityMonitoringActive &&
            isProximityNear &&
            !isScreenSharing &&
            !isBluetoothHeadsetConnected()
    }

    // MARK: - SerenadaAudioCoordinator Conformance

    override suspend fun activateCallSession(intent: AudioIntent) {
        proximityEarpieceEnabled = intent.enableProximityEarpiece
        if (audioSessionActive) {
            updateProximityMonitoringForIntent()
            applyCallAudioRouting()
            updateDevicesAndRoute()
            onAudioEnvironmentChanged()
        } else {
            activate()
        }
        intent.preferredDevice?.let { applyRouting(it) }
    }

    override suspend fun deactivateCallSession() {
        val deactivation = prepareDeactivation() ?: return
        withContext(Dispatchers.Default) {
            finishDeactivation(deactivation)
        }
    }

    override suspend fun applyRouting(device: AudioDevice) {
        if (!device.isOutputRoute()) return
        pinnedOutputDevice = device
        pinnedOutputRouteInventory = currentOutputRouteInventory()
        applyOutputRoute(device)
        refreshDevicesAndRouteFromSystem()
    }

    private fun applyOutputRoute(device: AudioDevice) {
        when (device.kind) {
            is AudioDeviceKind.Speakerphone -> routeAudioToSpeaker()
            is AudioDeviceKind.Earpiece -> routeAudioToEarpiece()
            is AudioDeviceKind.Bluetooth -> routeAudioToBluetooth(device)
            else -> routeAudioToExternal(device)
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
        clearPinnedOutputIfRouteInventoryChanged()
        pinnedOutputDevice?.let { device ->
            if (isPinnedOutputDeviceAvailable(device)) {
                applyOutputRoute(device)
                return
            }
            pinnedOutputDevice = null
            pinnedOutputRouteInventory = null
        }
        applyOutputRoute(preferredAutomaticOutputDevice())
    }

    private fun preferredAutomaticOutputDevice(): AudioDevice {
        preferredBluetoothOutputDevice()?.let { return it }
        preferredExternalOutputDevice()?.let { return it }
        if (proximityMonitoringActive && isProximityNear) {
            return availableOutputDevice(AudioDeviceKind.Earpiece)
        }
        return availableOutputDevice(AudioDeviceKind.Speakerphone)
    }

    private fun preferredBluetoothOutputDevice(): AudioDevice? {
        val bluetoothDevices = _availableDevices.value
            .filter { it.isOutputRoute() && it.kind is AudioDeviceKind.Bluetooth }
        return bluetoothDevices.firstOrNull { it.status == AudioDeviceStatus.ACTIVE }
            ?: bluetoothDevices.firstOrNull { (it.kind as? AudioDeviceKind.Bluetooth)?.profile == BluetoothProfile.HFP }
            ?: bluetoothDevices.firstOrNull { (it.kind as? AudioDeviceKind.Bluetooth)?.profile == BluetoothProfile.BLE }
            ?: bluetoothDevices.firstOrNull()
    }

    private fun preferredExternalOutputDevice(): AudioDevice? {
        return _availableDevices.value
            .filter { it.isOutputRoute() && it.kind.isExternalOutputRoute() }
            .minWithOrNull(
                compareBy<AudioDevice> { it.kind.automaticRouteRank() }
                    .thenBy { outputRouteInventoryKey(it) }
            )
    }

    private fun clearPinnedOutputIfRouteInventoryChanged() {
        val pinnedInventory = pinnedOutputRouteInventory ?: return
        if (pinnedInventory != currentOutputRouteInventory()) {
            pinnedOutputDevice = null
            pinnedOutputRouteInventory = null
        }
    }

    private fun routeAudioToBluetooth(device: AudioDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothDevice = findCommunicationDevice(device)
                ?: findBluetoothCommunicationDevice()
            if (bluetoothDevice == null || !audioManager.setCommunicationDevice(bluetoothDevice)) {
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to route audio to Bluetooth headset")
                pinnedOutputDevice = null
                pinnedOutputRouteInventory = null
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

    private fun updateProximityMonitoringForIntent() {
        if (proximityMonitoringEnabled && proximityEarpieceEnabled) {
            startProximityMonitoring()
        } else {
            stopProximityMonitoring()
        }
    }

    private fun schedulePlaybackDuckingFallback() {
        clearPlaybackDuckingFallback()
        handler.postDelayed(playbackDuckingFallbackRunnable, PLAYBACK_DUCKING_FALLBACK_MS)
    }

    private fun clearPlaybackDuckingFallback() {
        handler.removeCallbacks(playbackDuckingFallbackRunnable)
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

    private fun routeAudioToExternal(device: AudioDevice) {
        setLegacyBluetoothScoRouting(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!setCommunicationDevice(device)) {
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to route audio to external device kind=${device.kind}")
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

    private fun setCommunicationDevice(device: AudioDevice): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val communicationDevice = findCommunicationDevice(device)
            ?: audioManager.availableCommunicationDevices.firstOrNull { info ->
                deviceKindMatches(mapDeviceKind(info.type), device.kind)
            }
            ?: return false
        return audioManager.setCommunicationDevice(communicationDevice)
    }

    private fun findCommunicationDevice(device: AudioDevice): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return audioManager.availableCommunicationDevices.firstOrNull { info ->
            info.id.toString() == device.id
        }
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

    private fun isPinnedOutputDeviceAvailable(pinnedDevice: AudioDevice): Boolean {
        return _availableDevices.value.any { device ->
            device.isOutputRoute() && outputRouteInventoryKey(device) == outputRouteInventoryKey(pinnedDevice)
        }
    }

    private fun currentOutputRouteInventory(): Set<String> {
        return _availableDevices.value
            .filter { it.isOutputRoute() }
            .map { outputRouteInventoryKey(it) }
            .toSet()
    }

    private fun outputRouteInventoryKey(device: AudioDevice): String {
        val routeName = device.displayName.trim()
        val fallback = device.id.ifEmpty { routeName }
        return when (device.kind) {
            is AudioDeviceKind.Speakerphone -> "speakerphone"
            is AudioDeviceKind.Earpiece -> "earpiece"
            is AudioDeviceKind.Bluetooth -> "bluetooth:$fallback"
            is AudioDeviceKind.WiredHeadset -> "wired"
            is AudioDeviceKind.CarAudio -> "car:$fallback"
            is AudioDeviceKind.Usb -> "usb:$fallback"
            is AudioDeviceKind.Other -> "other:$fallback"
        }
    }

    private fun requestAudioFocus(emitRecoveryEventOnGain: Boolean = false) {
        val wasGranted = audioFocusGranted
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
        if (emitRecoveryEventOnGain && granted && !wasGranted) {
            _events.tryEmit(AudioCoordinatorEvent.ExternalAudioEnded)
            _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingEnded)
        }
        logger?.log(SerenadaLogLevel.DEBUG, "Audio", "Audio focus request granted=$granted")
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioSession(deactivation: Deactivation) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                if (deactivation.bluetoothScoWasActive) {
                    audioManager.stopBluetoothSco()
                }
                audioManager.isBluetoothScoOn = false
            }
            audioManager.isMicrophoneMute = deactivation.previousMicrophoneMute
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (deactivation.previousSpeakerphoneOn) {
                    val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker == null || !audioManager.setCommunicationDevice(speaker)) {
                        logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to restore built-in speaker route")
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                audioManager.isSpeakerphoneOn = deactivation.previousSpeakerphoneOn
            }
            audioManager.mode = deactivation.previousAudioMode
        }.onSuccess {
            logger?.log(
                SerenadaLogLevel.DEBUG,
                "Audio",
                "Audio session restored (mode=${deactivation.previousAudioMode})",
            )
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to restore audio session: ${error.message}")
        }
    }

    private fun abandonAudioFocus(focusWasGranted: Boolean, focusRequest: AudioFocusRequest?) {
        if (!focusWasGranted) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
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

    private fun AudioDeviceKind.isExternalOutputRoute(): Boolean {
        return when (this) {
            is AudioDeviceKind.WiredHeadset,
            is AudioDeviceKind.CarAudio,
            is AudioDeviceKind.Usb,
            is AudioDeviceKind.Other -> true
            is AudioDeviceKind.Bluetooth,
            is AudioDeviceKind.Speakerphone,
            is AudioDeviceKind.Earpiece -> false
        }
    }

    private fun AudioDeviceKind.automaticRouteRank(): Int {
        return when (this) {
            is AudioDeviceKind.WiredHeadset -> 0
            is AudioDeviceKind.CarAudio,
            is AudioDeviceKind.Usb -> 1
            is AudioDeviceKind.Other -> 2
            is AudioDeviceKind.Bluetooth,
            is AudioDeviceKind.Speakerphone,
            is AudioDeviceKind.Earpiece -> 3
        }
    }

    private fun availableOutputDevice(kind: AudioDeviceKind): AudioDevice {
        return _availableDevices.value.firstOrNull { device ->
            device.isOutputRoute() && device.kind == kind
        } ?: AudioDevice(
            id = kind.defaultOutputId(),
            displayName = kind.defaultOutputDisplayName(),
            kind = kind,
            direction = AudioDeviceDirection.OUTPUT,
            status = AudioDeviceStatus.AVAILABLE
        )
    }

    private fun AudioDeviceKind.defaultOutputId(): String {
        return when (this) {
            is AudioDeviceKind.Speakerphone -> "speaker"
            is AudioDeviceKind.Earpiece -> "earpiece"
            is AudioDeviceKind.Bluetooth -> "bluetooth"
            is AudioDeviceKind.WiredHeadset -> "wired"
            is AudioDeviceKind.CarAudio -> "car"
            is AudioDeviceKind.Usb -> "usb"
            is AudioDeviceKind.Other -> "other"
        }
    }

    private fun AudioDeviceKind.defaultOutputDisplayName(): String {
        return when (this) {
            is AudioDeviceKind.Speakerphone -> "Speaker"
            is AudioDeviceKind.Earpiece -> "Earpiece"
            is AudioDeviceKind.Bluetooth -> "Bluetooth"
            is AudioDeviceKind.WiredHeadset -> "Headset"
            is AudioDeviceKind.CarAudio -> "Car audio"
            is AudioDeviceKind.Usb -> "USB audio"
            is AudioDeviceKind.Other -> "Audio"
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mapDeviceKind(type) is AudioDeviceKind.Bluetooth) {
            return communicationDevices.any { it.id == id }
        }
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
        val routeDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            communicationDevices + allDevices
        } else {
            allDevices
        }
        val list = routeDevices
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
        private const val PLAYBACK_DUCKING_FALLBACK_MS = 3_000L
    }
}
