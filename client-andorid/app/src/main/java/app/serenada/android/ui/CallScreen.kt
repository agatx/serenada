package app.serenada.android.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.serenada.android.call.CallPhase
import app.serenada.android.call.CallUiState
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
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
    attachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    attachRemoteRenderer: (SurfaceViewRenderer) -> Unit,
    detachRemoteRenderer: (SurfaceViewRenderer) -> Unit
) {
    var areControlsVisible by remember { mutableStateOf(true) }
    var isLocalLarge by remember { mutableStateOf(false) }
    var remoteVideoFitCover by remember { mutableStateOf(true) }
    var lastFrontCameraState by remember { mutableStateOf(uiState.isFrontCamera) }

    val isReconnecting = remember(uiState.iceConnectionState, uiState.connectionState, uiState.isSignalingConnected) {
        val iceState = uiState.iceConnectionState
        val connState = uiState.connectionState
        !uiState.isSignalingConnected ||
                iceState == "DISCONNECTED" || iceState == "FAILED" ||
                connState == "DISCONNECTED" || connState == "FAILED"
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                areControlsVisible = !areControlsVisible
            }
    ) {
        // Primary Video
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (isLocalLarge) {
                        isLocalLarge = false
                    } else {
                        areControlsVisible = !areControlsVisible
                    }
                }
        ) {
            if (isLocalLarge) {
                if (uiState.localVideoEnabled) {
                    VideoSurface(
                        modifier = Modifier.fillMaxSize(),
                        onAttach = attachLocalRenderer,
                        onDetach = detachLocalRenderer,
                        mirror = uiState.isFrontCamera,
                        contentScale = ContentScale.Crop,
                        id = "large-local"
                    )
                } else {
                    VideoPlaceholder("Your camera is off")
                }
            } else {
                if (uiState.remoteVideoEnabled) {
                    VideoSurface(
                        modifier = Modifier.fillMaxSize(),
                        onAttach = attachRemoteRenderer,
                        onDetach = detachRemoteRenderer,
                        contentScale = if (remoteVideoFitCover) ContentScale.Crop else ContentScale.Fit,
                        id = "large-remote"
                    )
                } else {
                    if (uiState.phase == CallPhase.InCall && !uiState.remoteVideoEnabled) {
                        VideoPlaceholder("Video off")
                    }
                }
            }
        }

        // PIP Video (Lower Right)
        if (uiState.phase == CallPhase.InCall || uiState.phase == CallPhase.Waiting) {
            Box(
                modifier = Modifier
                    .padding(bottom = if (areControlsVisible) 160.dp else 48.dp, end = 16.dp)
                    .align(Alignment.BottomEnd)
                    .size(100.dp, 150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF222222))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isLocalLarge = !isLocalLarge
                    }
                    .zIndex(1f)
            ) {
                if (isLocalLarge) {
                    if (uiState.remoteVideoEnabled) {
                        VideoSurface(
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                            onAttach = attachRemoteRenderer,
                            onDetach = detachRemoteRenderer,
                            contentScale = if (remoteVideoFitCover) ContentScale.Crop else ContentScale.Fit,
                            id = "pip-remote"
                        )
                    } else {
                        VideoPlaceholder("Video off", fontSize = 10.sp)
                    }
                } else {
                    if (uiState.localVideoEnabled) {
                        VideoSurface(
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                            onAttach = attachLocalRenderer,
                            onDetach = detachLocalRenderer,
                            mirror = uiState.isFrontCamera,
                            contentScale = ContentScale.Crop,
                            id = "pip-local"
                        )
                    } else {
                        VideoPlaceholder("Camera off", fontSize = 10.sp)
                    }
                }
            }
        }

        // Waiting State Overlay
        if (uiState.phase == CallPhase.Waiting && !isLocalLarge) {
            WaitingOverlay(
                roomId = roomId,
                serverHost = serverHost
            )
        }

        // Reconnecting Indicator
        AnimatedVisibility(
            visible = isReconnecting && uiState.phase == CallPhase.InCall,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp)
            ) {
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
                onClick = { 
                    remoteVideoFitCover = !remoteVideoFitCover
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 16.dp, end = 16.dp)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .zIndex(2f)
            ) {
                Icon(
                    imageVector = if (remoteVideoFitCover) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
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
                        icon = if (uiState.localAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                        backgroundColor = if (uiState.localAudioEnabled) Color.White.copy(alpha = 0.2f) else Color.Red
                    )

                    // Video Toggle Button
                    ControlButton(
                        onClick = onToggleVideo,
                        icon = if (uiState.localVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        backgroundColor = if (uiState.localVideoEnabled) Color.White.copy(alpha = 0.2f) else Color.Red
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
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .clickable { onClick() },
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
private fun WaitingOverlay(
    roomId: String,
    serverHost: String
) {
    val link = "https://$serverHost/call/$roomId"
    val qrBitmap = remember(link) { generateQrCode(link) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
            modifier = Modifier
                .size(200.dp)
                .clip(RoundedCornerShape(16.dp)),
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
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
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
    onAttach: (SurfaceViewRenderer) -> Unit,
    onDetach: (SurfaceViewRenderer) -> Unit,
    mirror: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
    id: String = ""
) {
    val context = LocalContext.current
    val renderer = remember(id) { SurfaceViewRenderer(context) }

    DisposableEffect(renderer) {
        onAttach(renderer)
        onDispose {
            onDetach(renderer)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { 
            renderer.apply {
                setMirror(mirror)
                setScalingType(if (contentScale == ContentScale.Crop) 
                    RendererCommon.ScalingType.SCALE_ASPECT_FILL 
                    else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        },
        update = { view ->
            view.setMirror(mirror)
            view.setScalingType(if (contentScale == ContentScale.Crop) 
                RendererCommon.ScalingType.SCALE_ASPECT_FILL 
                else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
    )
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
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

private fun shareLink(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Share call link"))
}
