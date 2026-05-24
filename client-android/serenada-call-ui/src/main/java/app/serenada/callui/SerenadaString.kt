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
    /** Header for Frontline audio route selection. */
    FrontlineAudioRoute,
    /** Frontline audio route label for speakerphone output. */
    FrontlineAudioSpeaker,
    /** Frontline audio route label for built-in phone/earpiece output. */
    FrontlineAudioEarpiece,
    /** Frontline audio route label for wired headset output. */
    FrontlineAudioHeadset,
    /** Frontline audio route label for Bluetooth output. */
    FrontlineAudioBluetooth,
    /** Frontline audio route label for car audio output. */
    FrontlineAudioCar,
    /** Frontline audio route label for USB audio output. */
    FrontlineAudioUsb,
    /** Frontline audio route fallback label for unknown output routes. */
    FrontlineAudioUnknown,
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
    SerenadaString.FrontlineAudioRoute to "Audio",
    SerenadaString.FrontlineAudioSpeaker to "Speaker",
    SerenadaString.FrontlineAudioEarpiece to "Phone",
    SerenadaString.FrontlineAudioHeadset to "Headset",
    SerenadaString.FrontlineAudioBluetooth to "Bluetooth",
    SerenadaString.FrontlineAudioCar to "Car audio",
    SerenadaString.FrontlineAudioUsb to "USB audio",
    SerenadaString.FrontlineAudioUnknown to "Audio",
)

fun resolveString(key: SerenadaString, overrides: Map<SerenadaString, String>?): String {
    return overrides?.get(key) ?: serenadaDefaultStrings[key] ?: key.name
}
