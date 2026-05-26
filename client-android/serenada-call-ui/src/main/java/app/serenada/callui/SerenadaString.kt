package app.serenada.callui

enum class SerenadaString {
    CallLocalCameraOff,
    CallCameraOff,
    CallVideoOff,
    CallWaitingShort,
    CallReconnecting,
    CallTakingLongerThanUsual,
    CallWaitingOverlay,
    CallShareLinkChooser,
    CallShareInvitation,
    CallInviteToRoom,
    CallQrCode,
    CallToggleFlashlight,
    CallToggleVideoFit,
    CallTakeSnapshot,
    FrontlineYou,
    FrontlineWaiting,
    FrontlineVideo,
    FrontlineVideoOn,
    FrontlineMute,
    FrontlineMore,
    FrontlineEnd,
    FrontlineFlipCamera,
    FrontlineStopScreenShare,
    FrontlineShareScreen,
    FrontlineClose,
    /** Header for Call audio route selection. */
    CallAudioRoute,
    /** Call audio route label for speakerphone output. */
    CallAudioSpeaker,
    /** Call audio route label for built-in phone/earpiece output. */
    CallAudioPhone,
    /** Call audio route label for wired headset output. */
    CallAudioHeadset,
    /** Call audio route label for Bluetooth output. */
    CallAudioBluetooth,
    /** Call audio route label for car audio output. */
    CallAudioCar,
    /** Call audio route label for USB audio output. */
    CallAudioUsb,
    /** Call audio route fallback label for unknown output routes. */
    CallAudioUnknown,
}

val serenadaDefaultStrings: Map<SerenadaString, String> = mapOf(
    SerenadaString.CallLocalCameraOff to "Your camera is off",
    SerenadaString.CallCameraOff to "Camera off",
    SerenadaString.CallVideoOff to "Video off",
    SerenadaString.CallWaitingShort to "Waiting...",
    SerenadaString.CallReconnecting to "Reconnecting...",
    SerenadaString.CallTakingLongerThanUsual to "Taking longer than usual...",
    SerenadaString.CallWaitingOverlay to "Waiting for someone to join...",
    SerenadaString.CallShareLinkChooser to "Share call link",
    SerenadaString.CallShareInvitation to "Share invitation",
    SerenadaString.CallInviteToRoom to "Invite to call",
    SerenadaString.CallQrCode to "QR code",
    SerenadaString.CallToggleFlashlight to "Toggle flashlight",
    SerenadaString.CallToggleVideoFit to "Toggle video fit",
    SerenadaString.CallTakeSnapshot to "Take photo",
    SerenadaString.FrontlineYou to "You",
    SerenadaString.FrontlineWaiting to "Waiting",
    SerenadaString.FrontlineVideo to "VIDEO",
    SerenadaString.FrontlineVideoOn to "VIDEO ON",
    SerenadaString.FrontlineMute to "MUTE",
    SerenadaString.FrontlineMore to "MORE",
    SerenadaString.FrontlineEnd to "END",
    SerenadaString.FrontlineFlipCamera to "Flip camera",
    SerenadaString.FrontlineStopScreenShare to "Stop screen share",
    SerenadaString.FrontlineShareScreen to "Share screen",
    SerenadaString.FrontlineClose to "Close",
    SerenadaString.CallAudioRoute to "Audio",
    SerenadaString.CallAudioSpeaker to "Speaker",
    SerenadaString.CallAudioPhone to "Phone",
    SerenadaString.CallAudioHeadset to "Headset",
    SerenadaString.CallAudioBluetooth to "Bluetooth",
    SerenadaString.CallAudioCar to "Car audio",
    SerenadaString.CallAudioUsb to "USB audio",
    SerenadaString.CallAudioUnknown to "Audio",
)

fun resolveString(key: SerenadaString, overrides: Map<SerenadaString, String>?): String {
    return overrides?.get(key) ?: serenadaDefaultStrings[key] ?: key.name
}
