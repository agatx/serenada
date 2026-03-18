import Foundation

/// All user-facing string keys used by SerenadaCallUI.
/// Host apps can override any string by passing a `[SerenadaString: String]` dictionary.
public enum SerenadaString: String, CaseIterable {
    case callLocalCameraOff
    case callCameraOff
    case callVideoOff
    case callReconnecting
    case callTakingLongerThanUsual
    case callWaitingOverlay
    case callInviteToRoom
    case callInviteSent
    case callInviteFailed
    case callShareInvitation
    case callQrCode
    case callA11yMuteOn
    case callA11yMuteOff
    case callA11yVideoOn
    case callA11yVideoOff
    case callA11yFlipCamera
    case callA11yScreenShareOn
    case callA11yScreenShareOff
    case callA11yEndCall
    case callA11yFlashlightOn
    case callA11yFlashlightOff
    case callA11yShareInvite
    case callA11yVideoFit
    case callA11yVideoFill
    case callErrorGeneric
    case callJoining
    case callEnded
    case callPermissionsRequired
    case callPermissionsCamera
    case callPermissionsMicrophone
}

/// Default English strings for SerenadaCallUI.
public let serenadaDefaultStrings: [SerenadaString: String] = [
    .callLocalCameraOff: "Camera off",
    .callCameraOff: "Camera off",
    .callVideoOff: "Video off",
    .callReconnecting: "Reconnecting",
    .callTakingLongerThanUsual: "Taking longer than usual",
    .callWaitingOverlay: "Waiting for someone to join...",
    .callInviteToRoom: "Invite to room",
    .callInviteSent: "Invite sent",
    .callInviteFailed: "Failed to send invite",
    .callShareInvitation: "Share invitation",
    .callQrCode: "QR Code",
    .callA11yMuteOn: "Microphone on",
    .callA11yMuteOff: "Microphone off",
    .callA11yVideoOn: "Video on",
    .callA11yVideoOff: "Video off",
    .callA11yFlipCamera: "Flip camera",
    .callA11yScreenShareOn: "Stop sharing screen",
    .callA11yScreenShareOff: "Share screen",
    .callA11yEndCall: "End call",
    .callA11yFlashlightOn: "Flashlight on",
    .callA11yFlashlightOff: "Flashlight off",
    .callA11yShareInvite: "Share invite",
    .callA11yVideoFit: "Fit video",
    .callA11yVideoFill: "Fill video",
    .callErrorGeneric: "Something went wrong",
    .callJoining: "Joining...",
    .callEnded: "Call ended",
    .callPermissionsRequired: "Camera and microphone access are required",
    .callPermissionsCamera: "Camera",
    .callPermissionsMicrophone: "Microphone",
]

/// Resolves a string key, checking overrides first, then falling back to English defaults.
func resolveString(_ key: SerenadaString, overrides: [SerenadaString: String]?) -> String {
    overrides?[key] ?? serenadaDefaultStrings[key] ?? key.rawValue
}
