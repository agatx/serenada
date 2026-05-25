package app.serenada.callui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Usb
import androidx.compose.ui.graphics.vector.ImageVector
import app.serenada.core.call.AudioDevice
import app.serenada.core.call.AudioDeviceDirection
import app.serenada.core.call.AudioDeviceKind
import app.serenada.core.call.AudioDeviceStatus
import app.serenada.core.call.BluetoothProfile
import java.util.Locale

internal fun currentCallAudioRoute(
    currentAudioDevice: AudioDevice?,
    availableAudioDevices: List<AudioDevice>,
): AudioDevice? {
    val currentKey = currentAudioDevice?.takeIf { it.isCallAudioOutputRoute() }?.callAudioRouteKey()
    val options = callAudioRouteOptions(currentAudioDevice, availableAudioDevices)
    return options.preferredActiveCallAudioRoute()
        ?: currentKey?.let { key ->
            options.firstOrNull { it.callAudioRouteKey() == key }
        }
}

internal fun callAudioRouteOptions(
    currentAudioDevice: AudioDevice?,
    availableAudioDevices: List<AudioDevice>,
): List<AudioDevice> {
    val candidates = buildList {
        addAll(availableAudioDevices)
        currentAudioDevice?.let(::add)
    }
        .filter { it.isCallAudioOutputRoute() }
    val activeKeys = candidates
        .filter { it.status == AudioDeviceStatus.ACTIVE || it == currentAudioDevice }
        .map { it.callAudioRouteKey() }
        .toSet()
    val devicesByRoute = linkedMapOf<String, AudioDevice>()
    candidates.forEach { device ->
        val key = device.callAudioRouteKey()
        val existing = devicesByRoute[key]
        devicesByRoute[key] = if (existing == null) {
            device
        } else {
            existing.preferredCallAudioRouteDisplay(device)
        }
    }
    return devicesByRoute.values
        .map { device ->
            if (device.callAudioRouteKey() in activeKeys) {
                device.copy(status = AudioDeviceStatus.ACTIVE)
            } else {
                device
            }
        }
        .sortedWith(
            compareBy<AudioDevice> { it.callAudioRouteSortRank() }
                .thenBy { it.callAudioRouteSortLabel().lowercase(Locale.ROOT) }
        )
}

internal fun AudioDevice.callAudioRouteKey(): String {
    val routeName = displayName.trim()
    val fallback = id.ifBlank { routeName }
    return when (kind) {
        is AudioDeviceKind.Speakerphone -> "speakerphone"
        is AudioDeviceKind.Earpiece -> "earpiece"
        is AudioDeviceKind.Bluetooth -> "bluetooth:$fallback"
        is AudioDeviceKind.WiredHeadset -> "wired"
        is AudioDeviceKind.CarAudio -> "car:$fallback"
        is AudioDeviceKind.Usb -> "usb:$fallback"
        is AudioDeviceKind.Other -> "other:$fallback"
    }
}

internal fun AudioDevice.callAudioRouteLabel(strings: Map<SerenadaString, String>?): String {
    val routeName = displayName.trim().takeIf { it.isNotEmpty() }
    return when (kind) {
        is AudioDeviceKind.Speakerphone -> resolveString(SerenadaString.CallAudioSpeaker, strings)
        is AudioDeviceKind.Earpiece -> resolveString(SerenadaString.CallAudioPhone, strings)
        is AudioDeviceKind.WiredHeadset -> routeName ?: resolveString(SerenadaString.CallAudioHeadset, strings)
        is AudioDeviceKind.Bluetooth -> routeName ?: resolveString(SerenadaString.CallAudioBluetooth, strings)
        is AudioDeviceKind.CarAudio -> routeName ?: resolveString(SerenadaString.CallAudioCar, strings)
        is AudioDeviceKind.Usb -> routeName ?: resolveString(SerenadaString.CallAudioUsb, strings)
        is AudioDeviceKind.Other -> routeName
            ?: resolveString(SerenadaString.CallAudioUnknown, strings)
    }
}

internal fun callAudioRouteIcon(kind: AudioDeviceKind?): ImageVector {
    return when (kind) {
        is AudioDeviceKind.Speakerphone -> Icons.AutoMirrored.Filled.VolumeUp
        is AudioDeviceKind.Earpiece -> Icons.Default.PhoneInTalk
        is AudioDeviceKind.WiredHeadset -> Icons.Default.Headset
        is AudioDeviceKind.Bluetooth -> Icons.Default.Bluetooth
        is AudioDeviceKind.CarAudio -> Icons.Default.DirectionsCar
        is AudioDeviceKind.Usb -> Icons.Default.Usb
        is AudioDeviceKind.Other, null -> Icons.AutoMirrored.Filled.VolumeUp
    }
}

private fun AudioDevice.isCallAudioOutputRoute(): Boolean {
    return direction == AudioDeviceDirection.OUTPUT || direction == AudioDeviceDirection.BOTH
}

private fun List<AudioDevice>.preferredActiveCallAudioRoute(): AudioDevice? {
    return filter { it.status == AudioDeviceStatus.ACTIVE }
        .minWithOrNull(
            compareBy<AudioDevice> { it.callAudioRouteActiveRank() }
                .thenBy { it.callAudioRouteSortLabel().lowercase(Locale.ROOT) }
        )
}

private fun AudioDevice.callAudioRouteActiveRank(): Int {
    return when (kind) {
        is AudioDeviceKind.Bluetooth -> 0
        is AudioDeviceKind.WiredHeadset -> 1
        is AudioDeviceKind.CarAudio,
        is AudioDeviceKind.Usb -> 2
        is AudioDeviceKind.Speakerphone -> 3
        is AudioDeviceKind.Earpiece -> 4
        is AudioDeviceKind.Other -> 5
    }
}

private fun AudioDevice.callAudioRouteSortRank(): Int {
    return when (kind) {
        is AudioDeviceKind.Speakerphone -> 0
        is AudioDeviceKind.Earpiece -> 1
        is AudioDeviceKind.Bluetooth -> 2
        is AudioDeviceKind.WiredHeadset -> 3
        is AudioDeviceKind.CarAudio,
        is AudioDeviceKind.Usb,
        is AudioDeviceKind.Other -> 4
    }
}

private fun AudioDevice.callAudioRouteSortLabel(): String {
    return displayName.trim().ifBlank { id }
}

private fun AudioDevice.preferredCallAudioRouteDisplay(candidate: AudioDevice): AudioDevice {
    val existingRank = callAudioRouteDisplayRank()
    val candidateRank = candidate.callAudioRouteDisplayRank()
    return when {
        candidateRank < existingRank -> candidate
        candidateRank > existingRank -> this
        candidate.status == AudioDeviceStatus.ACTIVE && status != AudioDeviceStatus.ACTIVE -> candidate
        else -> this
    }
}

private fun AudioDevice.callAudioRouteDisplayRank(): Int {
    val hasName = displayName.trim().isNotEmpty()
    return when (val kind = kind) {
        is AudioDeviceKind.Bluetooth -> when (kind.profile) {
            BluetoothProfile.HFP,
            BluetoothProfile.BLE -> if (hasName) 0 else 3
            BluetoothProfile.UNKNOWN -> if (hasName) 1 else 4
            BluetoothProfile.A2DP -> if (hasName) 2 else 5
        }
        is AudioDeviceKind.Speakerphone,
        is AudioDeviceKind.Earpiece -> 0
        is AudioDeviceKind.WiredHeadset,
        is AudioDeviceKind.CarAudio,
        is AudioDeviceKind.Usb,
        is AudioDeviceKind.Other -> if (hasName) 0 else 1
    }
}
