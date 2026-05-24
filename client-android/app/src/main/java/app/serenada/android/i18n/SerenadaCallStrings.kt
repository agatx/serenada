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
    SerenadaString.CallTakeSnapshot to context.getString(R.string.call_take_snapshot),
    SerenadaString.FrontlineYou to context.getString(R.string.frontline_you),
    SerenadaString.FrontlineWaiting to context.getString(R.string.frontline_waiting),
    SerenadaString.FrontlineVideo to context.getString(R.string.frontline_video),
    SerenadaString.FrontlineVideoOn to context.getString(R.string.frontline_video_on),
    SerenadaString.FrontlineMute to context.getString(R.string.frontline_mute),
    SerenadaString.FrontlineMore to context.getString(R.string.frontline_more),
    SerenadaString.FrontlineEnd to context.getString(R.string.frontline_end),
    SerenadaString.FrontlineFlipCamera to context.getString(R.string.frontline_flip_camera),
    SerenadaString.FrontlineStopScreenShare to context.getString(R.string.frontline_stop_screen_share),
    SerenadaString.FrontlineShareScreen to context.getString(R.string.frontline_share_screen),
    SerenadaString.FrontlineClose to context.getString(R.string.frontline_close),
    SerenadaString.FrontlineAudioRoute to context.getString(R.string.frontline_audio_route),
    SerenadaString.FrontlineAudioSpeaker to context.getString(R.string.frontline_audio_speaker),
    SerenadaString.FrontlineAudioEarpiece to context.getString(R.string.frontline_audio_earpiece),
    SerenadaString.FrontlineAudioHeadset to context.getString(R.string.frontline_audio_headset),
    SerenadaString.FrontlineAudioBluetooth to context.getString(R.string.frontline_audio_bluetooth),
    SerenadaString.FrontlineAudioCar to context.getString(R.string.frontline_audio_car),
    SerenadaString.FrontlineAudioUsb to context.getString(R.string.frontline_audio_usb),
    SerenadaString.FrontlineAudioUnknown to context.getString(R.string.frontline_audio_unknown),
)
