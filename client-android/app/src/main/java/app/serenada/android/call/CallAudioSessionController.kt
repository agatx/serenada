package app.serenada.android.call

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
import android.util.Log

internal class CallAudioSessionController(
    context: Context,
    private val handler: Handler,
    private val onProximityChanged: (Boolean) -> Unit,
    private val onAudioEnvironmentChanged: () -> Unit
) {
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
    private var bluetoothScoActive = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d("CallManager", "Audio focus changed: $focusChange")
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
            onAudioEnvironmentChanged()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun activate() {
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
            startProximityMonitoring()
            applyCallAudioRouting()
            onAudioEnvironmentChanged()
        }.onSuccess {
            Log.d(
                "CallManager",
                "Audio session activated (prevMode=$previousAudioMode, focusGranted=$audioFocusGranted)"
            )
        }.onFailure { error ->
            Log.w("CallManager", "Failed to activate audio session", error)
        }
    }

    fun deactivate() {
        if (!audioSessionActive) {
            abandonAudioFocus()
            return
        }
        audioSessionActive = false
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
            Log.d("CallManager", "Audio session restored (mode=$previousAudioMode)")
        }.onFailure { error ->
            Log.w("CallManager", "Failed to restore audio session", error)
        }
        abandonAudioFocus()
    }

    fun shouldPauseVideoForProximity(isScreenSharing: Boolean): Boolean {
        return proximityMonitoringActive &&
            isProximityNear &&
            !isScreenSharing &&
            !isBluetoothHeadsetConnected()
    }

    private fun onAudioDevicesChanged() {
        if (!audioSessionActive) return
        applyCallAudioRouting()
        onAudioEnvironmentChanged()
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
            Log.w("CallManager", "Failed to register proximity listener", error)
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
            Log.w("CallManager", "Failed to unregister proximity listener", error)
        }
        proximityMonitoringActive = false
        isProximityNear = false
    }

    private fun startAudioDeviceMonitoring() {
        if (audioDeviceMonitoringActive) return
        runCatching {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
            audioDeviceMonitoringActive = true
        }.onFailure { error ->
            Log.w("CallManager", "Failed to register audio device callback", error)
        }
    }

    private fun stopAudioDeviceMonitoring() {
        if (!audioDeviceMonitoringActive) return
        runCatching {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        }.onFailure { error ->
            Log.w("CallManager", "Failed to unregister audio device callback", error)
        }
        audioDeviceMonitoringActive = false
    }

    private fun applyCallAudioRouting() {
        if (!audioSessionActive) return
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
                Log.w("CallManager", "Failed to route audio to Bluetooth headset")
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

    private fun setCommunicationDevice(type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == type }
            ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    private fun isBluetoothHeadsetConnected(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            findBluetoothCommunicationDevice() != null
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_ALL).any { device ->
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
                    Log.w("CallManager", "Failed to route audio to built-in speaker")
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
        Log.d("CallManager", "Audio focus request granted=$granted")
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
            Log.d("CallManager", "Audio focus abandoned")
        }.onFailure { error ->
            Log.w("CallManager", "Failed to abandon audio focus", error)
        }
    }
}
