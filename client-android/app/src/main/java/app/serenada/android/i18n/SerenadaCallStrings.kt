package app.serenada.android.i18n

import android.content.Context
import app.serenada.android.R
import app.serenada.callui.SerenadaString

fun buildSerenadaCallStrings(context: Context): Map<SerenadaString, String> = mapOf(
    SerenadaString.CallLocalCameraOff to context.getString(R.string.call_local_camera_off),
    SerenadaString.CallCameraOff to context.getString(R.string.call_camera_off),
    SerenadaString.CallVideoOff to context.getString(R.string.call_video_off),
    SerenadaString.CallWaitingShort to context.getString(R.string.call_waiting_short),
    SerenadaString.CallReconnecting to context.getString(R.string.call_reconnecting),
    SerenadaString.CallTakingLongerThanUsual to context.getString(R.string.call_taking_longer_than_usual),
    SerenadaString.CallWaitingOverlay to context.getString(R.string.call_waiting_overlay),
    SerenadaString.CallShareLinkChooser to context.getString(R.string.call_share_link_chooser),
    SerenadaString.CallShareInvitation to context.getString(R.string.call_share_invitation),
    SerenadaString.CallInviteToRoom to context.getString(R.string.call_invite_to_room),
    SerenadaString.CallQrCode to context.getString(R.string.call_qr_code),
    SerenadaString.CallToggleFlashlight to context.getString(R.string.call_toggle_flashlight),
    SerenadaString.CallToggleVideoFit to context.getString(R.string.call_toggle_video_fit),
)
