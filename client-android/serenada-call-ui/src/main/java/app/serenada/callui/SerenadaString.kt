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
)

fun resolveString(key: SerenadaString, overrides: Map<SerenadaString, String>?): String {
    return overrides?.get(key) ?: serenadaDefaultStrings[key] ?: key.name
}
