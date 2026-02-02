package app.serenada.android.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import app.serenada.android.call.CallPhase
import app.serenada.android.call.CallUiState
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.abs
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(
        roomId: String,
        uiState: CallUiState,
        serverHost: String,
        onToggleAudio: () -> Unit,
        onToggleVideo: () -> Unit,
        onFlipCamera: () -> Unit,
        onEndCall: () -> Unit,
        attachLocalRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
        detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
        attachRemoteRenderer: (SurfaceViewRenderer, RendererCommon.RendererEvents?) -> Unit,
        detachRemoteRenderer: (SurfaceViewRenderer) -> Unit
) {
    var areControlsVisible by remember { mutableStateOf(true) }
    var isLocalLarge by remember { mutableStateOf(false) }
    var remoteVideoFitCover by remember { mutableStateOf(true) }
    var lastFrontCameraState by remember { mutableStateOf(uiState.isFrontCamera) }
    var remoteAspectRatio by remember { mutableStateOf<Float?>(null) }
    val context = LocalContext.current
    val localRenderer = remember { SurfaceViewRenderer(context) }
    val remoteRenderer = remember { SurfaceViewRenderer(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val remoteRendererEvents = remember {
        object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() = Unit

            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                val rotatedWidth = if (rotation % 180 == 0) width else height
                val rotatedHeight = if (rotation % 180 == 0) height else width
                if (rotatedWidth == 0 || rotatedHeight == 0) return
                val ratio = rotatedWidth.toFloat() / rotatedHeight.toFloat()
                mainHandler.post {
                    val current = remoteAspectRatio
                    if (current == null || abs(current - ratio) > 0.01f) {
                        remoteAspectRatio = ratio
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            localRenderer.release()
            remoteRenderer.release()
        }
    }

    val isReconnecting =
            remember(
                    uiState.iceConnectionState,
                    uiState.connectionState,
                    uiState.isSignalingConnected
            ) {
                val iceState = uiState.iceConnectionState
                val connState = uiState.connectionState
                !uiState.isSignalingConnected ||
                        iceState == "DISCONNECTED" ||
                        iceState == "FAILED" ||
                        connState == "DISCONNECTED" ||
                        connState == "FAILED"
            }

    // Auto-hide controls
    LaunchedEffect(areControlsVisible, uiState.phase) {
        if (areControlsVisible && uiState.phase == CallPhase.InCall) {
            delay(8000)
            areControlsVisible = false
        }
    }

    // Auto-swap based on camera facing
    LaunchedEffect(uiState.isFrontCamera) {
        if (uiState.isFrontCamera != lastFrontCameraState) {
            // Front -> Back: Swapping to main view for better preview of what we capture
            // Back -> Front: Swapping to PIP to see remote person clearly
            isLocalLarge = !uiState.isFrontCamera
            lastFrontCameraState = uiState.isFrontCamera
        }
    }

    BoxWithConstraints(
            modifier =
                    Modifier.fillMaxSize().background(Color.Black).clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                            ) { areControlsVisible = !areControlsVisible }
    ) {
        val showPip = uiState.phase == CallPhase.InCall || uiState.phase == CallPhase.Waiting
        val pipBackgroundColor = Color(0xFF222222)
        val pipCornerRadius = 12.dp
        val mainModifier =
                Modifier.fillMaxSize().clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                        ) { areControlsVisible = !areControlsVisible }

        val pipBaseModifier =
                if (showPip) {
                    Modifier.padding(
                                    bottom = if (areControlsVisible) 160.dp else 48.dp,
                                    end = 16.dp
                            )
                            .align(Alignment.BottomEnd)
                            .size(100.dp, 150.dp)
                            .zIndex(1f)
                } else {
                    Modifier.size(0.dp)
                }

        val pipBackgroundModifier =
                pipBaseModifier.clip(RoundedCornerShape(pipCornerRadius)).background(pipBackgroundColor)
                        .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                        ) { isLocalLarge = !isLocalLarge }

        val localModifier = if (isLocalLarge) mainModifier else pipBaseModifier
        val remoteModifier = if (isLocalLarge) pipBaseModifier else mainModifier

        if (showPip) {
            Box(modifier = pipBackgroundModifier)
        }

        if (isLocalLarge) {
            VideoSurface(
                    modifier = localModifier,
                    renderer = localRenderer,
                    onAttach = { renderer -> attachLocalRenderer(renderer, null) },
                    onDetach = detachLocalRenderer,
                    mirror = uiState.isFrontCamera,
                    contentScale = ContentScale.Crop,
                    maskCornerRadius = null
            )
            VideoSurface(
                    modifier = remoteModifier,
                    renderer = remoteRenderer,
                    onAttach = { renderer -> attachRemoteRenderer(renderer, remoteRendererEvents) },
                    onDetach = detachRemoteRenderer,
                    contentScale = ContentScale.Crop,
                    maskCornerRadius = pipCornerRadius,
                    maskColor = pipBackgroundColor
            )
        } else {
            val ratio = remoteAspectRatio ?: 0f
            val containerRatio = if (maxHeight == 0.dp) 1f else maxWidth / maxHeight
            val targetWidth: androidx.compose.ui.unit.Dp
            val targetHeight: androidx.compose.ui.unit.Dp
            if (!remoteVideoFitCover && ratio > 0f) {
                if (containerRatio > ratio) {
                    targetHeight = maxHeight
                    targetWidth = maxHeight * ratio
                } else {
                    targetWidth = maxWidth
                    targetHeight = maxWidth / ratio
                }
            } else {
                targetWidth = maxWidth
                targetHeight = maxHeight
            }
            Box(modifier = remoteModifier) {
                VideoSurface(
                        modifier =
                                Modifier.size(targetWidth, targetHeight)
                                        .align(Alignment.Center),
                        renderer = remoteRenderer,
                        onAttach = { renderer ->
                            attachRemoteRenderer(renderer, remoteRendererEvents)
                        },
                        onDetach = detachRemoteRenderer,
                        contentScale = ContentScale.Crop,
                        maskCornerRadius = null
                )
            }
            VideoSurface(
                    modifier = localModifier,
                    renderer = localRenderer,
                    onAttach = { renderer -> attachLocalRenderer(renderer, null) },
                    onDetach = detachLocalRenderer,
                    mirror = uiState.isFrontCamera,
                    contentScale = ContentScale.Crop,
                    maskCornerRadius = pipCornerRadius,
                    maskColor = pipBackgroundColor
            )
        }

        if (!uiState.localVideoEnabled) {
            Box(modifier = localModifier) {
                VideoPlaceholder(
                        text = if (isLocalLarge) "Your camera is off" else "Camera off",
                        fontSize = if (isLocalLarge) 16.sp else 10.sp
                )
            }
        }

        val showRemotePlaceholder =
                !uiState.remoteVideoEnabled &&
                        (uiState.phase == CallPhase.InCall ||
                                (uiState.phase == CallPhase.Waiting && isLocalLarge))
        if (showRemotePlaceholder) {
            val text = if (uiState.phase == CallPhase.Waiting) "Waiting..." else "Video off"
            Box(modifier = remoteModifier) {
                VideoPlaceholder(text = text, fontSize = if (isLocalLarge) 10.sp else 16.sp)
            }
        }

        // PIP overlay handled by VideoSurface mask when needed.

        // Waiting State Overlay
        if (uiState.phase == CallPhase.Waiting && !isLocalLarge) {
            WaitingOverlay(roomId = roomId, serverHost = serverHost)
        }

        // Reconnecting Indicator
        AnimatedVisibility(
                visible = isReconnecting && uiState.phase == CallPhase.InCall,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
        ) {
            Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(20.dp)) {
                Text(
                        text = "Reconnecting...",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 14.sp
                )
            }
        }

        // Zoom/Fit Button (Top Right)
        if (uiState.remoteVideoEnabled && uiState.phase == CallPhase.InCall && !isLocalLarge) {
            IconButton(
                    onClick = { remoteVideoFitCover = !remoteVideoFitCover },
                    modifier =
                            Modifier.align(Alignment.TopEnd)
                                    .statusBarsPadding()
                                    .padding(top = 16.dp, end = 16.dp)
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    .zIndex(2f)
            ) {
                Icon(
                        imageVector =
                                if (remoteVideoFitCover) Icons.Default.FullscreenExit
                                else Icons.Default.Fullscreen,
                        contentDescription = "Toggle Video Fit",
                        tint = Color.White
                )
            }
        }

        // Controls Bar
        AnimatedVisibility(
                visible = areControlsVisible,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .background(
                                            brush =
                                                    androidx.compose.ui.graphics.Brush
                                                            .verticalGradient(
                                                                    colors =
                                                                            listOf(
                                                                                    Color.Transparent,
                                                                                    Color.Black
                                                                                            .copy(
                                                                                                    alpha =
                                                                                                            0.7f
                                                                                            )
                                                                            )
                                                            )
                                    )
                                    .padding(bottom = 48.dp, top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    // Order: Flip, Mute, Camera, End call

                    // Flip Camera
                    ControlButton(
                            onClick = onFlipCamera,
                            icon = Icons.Default.FlipCameraIos,
                            backgroundColor = Color.White.copy(alpha = 0.2f)
                    )

                    // Mute Button
                    ControlButton(
                            onClick = onToggleAudio,
                            icon =
                                    if (uiState.localAudioEnabled) Icons.Default.Mic
                                    else Icons.Default.MicOff,
                            backgroundColor =
                                    if (uiState.localAudioEnabled) Color.White.copy(alpha = 0.2f)
                                    else Color.Red
                    )

                    // Video Toggle Button
                    ControlButton(
                            onClick = onToggleVideo,
                            icon =
                                    if (uiState.localVideoEnabled) Icons.Default.Videocam
                                    else Icons.Default.VideocamOff,
                            backgroundColor =
                                    if (uiState.localVideoEnabled) Color.White.copy(alpha = 0.2f)
                                    else Color.Red
                    )

                    // End Call Button
                    ControlButton(
                            onClick = onEndCall,
                            icon = Icons.Default.CallEnd,
                            backgroundColor = Color.Red
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
        onClick: () -> Unit,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        backgroundColor: Color,
        buttonSize: androidx.compose.ui.unit.Dp = 56.dp,
        iconSize: androidx.compose.ui.unit.Dp = 28.dp
) {
    Surface(
            modifier = Modifier.size(buttonSize).clip(CircleShape).clickable { onClick() },
            color = backgroundColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = Color.White
            )
        }
    }
}

@Composable
private fun WaitingOverlay(roomId: String, serverHost: String) {
    val link = "https://$serverHost/call/$roomId"
    val qrBitmap = remember(link) { generateQrCode(link) }
    val context = LocalContext.current

    Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
    ) {
        Text(
                text = "Waiting for someone to join...",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Surface(
                modifier = Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)),
                color = Color.White
        ) {
            qrBitmap?.let {
                Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.fillMaxSize().padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
                onClick = { shareLink(context, link) },
                colors =
                        ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.2f)
                        ),
                shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share Invitation")
        }
    }
}

@Composable
private fun VideoSurface(
        modifier: Modifier,
        renderer: SurfaceViewRenderer,
        onAttach: (SurfaceViewRenderer) -> Unit,
        onDetach: (SurfaceViewRenderer) -> Unit,
        mirror: Boolean = false,
        contentScale: ContentScale = ContentScale.Crop,
        maskCornerRadius: androidx.compose.ui.unit.Dp? = null,
        maskColor: Color = Color.Transparent
) {
    val density = LocalDensity.current
    val maskRadiusPx = remember(maskCornerRadius, density) {
        maskCornerRadius?.let { with(density) { it.toPx() } }
    }
    val maskColorInt = remember(maskColor) { maskColor.toArgb() }

    DisposableEffect(renderer) {
        onAttach(renderer)
        onDispose { onDetach(renderer) }
    }

    AndroidView(
            modifier = modifier,
            factory = {
                RendererContainer(it, renderer).apply {
                    updateMask(maskRadiusPx, maskColorInt)
                }
            },
            update = { container ->
                container.updateMask(maskRadiusPx, maskColorInt)
                renderer.apply {
                    setZOrderOnTop(false)
                    setZOrderMediaOverlay(false)
                    setMirror(mirror)
                    setScalingType(
                        if (contentScale == ContentScale.Crop)
                                RendererCommon.ScalingType.SCALE_ASPECT_FILL
                        else RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    )
                }
            }
    )
}

private class RendererContainer(
        context: Context,
        renderer: SurfaceViewRenderer
) : FrameLayout(context) {
    private val maskView = CornerMaskView(context)

    init {
        if (renderer.parent is ViewGroup) {
            (renderer.parent as ViewGroup).removeView(renderer)
        }
        addView(
                renderer,
                LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        )
        addView(
                maskView,
                LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        )
        isClickable = false
        isFocusable = false
        maskView.isClickable = false
        maskView.isFocusable = false
    }

    fun updateMask(cornerRadiusPx: Float?, color: Int) {
        if (cornerRadiusPx == null || cornerRadiusPx <= 0f) {
            maskView.visibility = View.GONE
            return
        }
        maskView.visibility = View.VISIBLE
        maskView.updateMask(cornerRadiusPx, color)
    }
}

private class CornerMaskView(context: Context) : View(context) {
    private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
    private val path = Path()
    private val rect = RectF()
    private var cornerRadiusPx: Float = 0f
    private var maskColor: Int = AndroidColor.TRANSPARENT

    fun updateMask(cornerRadiusPx: Float, color: Int) {
        this.cornerRadiusPx = cornerRadiusPx
        this.maskColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (cornerRadiusPx <= 0f) return
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(rect, Path.Direction.CW)
        path.addRoundRect(rect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        paint.color = maskColor
        canvas.drawPath(path, paint)
    }
}

@Composable
private fun VideoPlaceholder(text: String, fontSize: androidx.compose.ui.unit.TextUnit = 16.sp) {
    Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF111111)),
            contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                    imageVector = Icons.Default.VideocamOff,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(if (fontSize < 12.sp) 32.dp else 48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                    text = text,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = fontSize,
                    textAlign = TextAlign.Center
            )
        }
    }
}

private fun generateQrCode(text: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                        x,
                        y,
                        if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

private fun shareLink(context: Context, text: String) {
    val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    context.startActivity(Intent.createChooser(intent, "Share call link"))
}
