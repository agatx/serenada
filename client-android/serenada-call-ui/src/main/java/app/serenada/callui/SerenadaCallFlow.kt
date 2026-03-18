package app.serenada.callui

import android.content.Intent
import androidx.compose.runtime.Composable
import app.serenada.core.SerenadaSession
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink

@Composable
fun SerenadaCallFlow(
    url: String? = null,
    session: SerenadaSession? = null,
    config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
    strings: Map<SerenadaString, String>? = null,
    onDismiss: () -> Unit = {},
) {
    // URL-first or session-first mode placeholder
}

@Composable
fun SerenadaCallFlow(
    uiState: CallUiState,
    roomId: String,
    serverHost: String,
    eglContext: EglBase.Context,
    roomName: String? = null,
    rendererProvider: CallRendererProvider? = null,
    initialRemoteVideoFitCover: Boolean = true,
    config: SerenadaCallFlowConfig = SerenadaCallFlowConfig(),
    strings: Map<SerenadaString, String>? = null,
    onToggleAudio: () -> Unit,
    onToggleVideo: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleFlashlight: () -> Unit = {},
    onLocalPinchZoom: (Float) -> Unit = {},
    onEndCall: () -> Unit,
    onShareLink: (() -> Unit)? = null,
    onInviteToRoom: () -> Unit = {},
    onRemoteVideoFitChanged: ((Boolean) -> Unit)? = null,
    onStartScreenShare: (Intent) -> Unit = {},
    onStopScreenShare: () -> Unit = {},
    attachLocalRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    attachLocalSink: (VideoSink) -> Unit,
    detachLocalSink: (VideoSink) -> Unit,
    attachRemoteRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
    detachRemoteRenderer: (SurfaceViewRenderer) -> Unit,
    attachRemoteSinkForCid: (String, VideoSink) -> Unit,
    detachRemoteSinkForCid: (String, VideoSink) -> Unit,
    attachRemoteSink: (VideoSink) -> Unit,
    detachRemoteSink: (VideoSink) -> Unit,
    onDismiss: () -> Unit = {},
) {
    CallScreen(
        roomId = roomId,
        uiState = uiState,
        serverHost = serverHost,
        eglContext = eglContext,
        roomName = roomName,
        rendererProvider = rendererProvider,
        initialRemoteVideoFitCover = initialRemoteVideoFitCover,
        config = config,
        strings = strings,
        onToggleAudio = onToggleAudio,
        onToggleVideo = onToggleVideo,
        onFlipCamera = onFlipCamera,
        onToggleFlashlight = onToggleFlashlight,
        onLocalPinchZoom = onLocalPinchZoom,
        onEndCall = onEndCall,
        onShareLink = onShareLink,
        onInviteToRoom = onInviteToRoom,
        onRemoteVideoFitChanged = onRemoteVideoFitChanged,
        onStartScreenShare = onStartScreenShare,
        onStopScreenShare = onStopScreenShare,
        attachLocalRenderer = attachLocalRenderer,
        detachLocalRenderer = detachLocalRenderer,
        attachLocalSink = attachLocalSink,
        detachLocalSink = detachLocalSink,
        attachRemoteRenderer = attachRemoteRenderer,
        detachRemoteRenderer = detachRemoteRenderer,
        attachRemoteSinkForCid = attachRemoteSinkForCid,
        detachRemoteSinkForCid = detachRemoteSinkForCid,
        attachRemoteSink = attachRemoteSink,
        detachRemoteSink = detachRemoteSink,
    )
}
