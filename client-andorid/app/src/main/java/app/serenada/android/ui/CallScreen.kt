package app.serenada.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(
    roomId: String,
    localAudioEnabled: Boolean,
    localVideoEnabled: Boolean,
    onToggleAudio: () -> Unit,
    onToggleVideo: () -> Unit,
    onFlipCamera: () -> Unit,
    onEndCall: () -> Unit,
    attachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    detachLocalRenderer: (SurfaceViewRenderer) -> Unit,
    attachRemoteRenderer: (SurfaceViewRenderer) -> Unit,
    detachRemoteRenderer: (SurfaceViewRenderer) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(
            modifier = Modifier.fillMaxSize(),
            onAttach = attachRemoteRenderer,
            onDetach = detachRemoteRenderer
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(120.dp, 180.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            VideoSurface(
                modifier = Modifier.fillMaxSize(),
                onAttach = attachLocalRenderer,
                onDetach = detachLocalRenderer
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Room $roomId", color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onToggleAudio, modifier = Modifier.weight(1f)) {
                    Text(if (localAudioEnabled) "Mute" else "Unmute")
                }
                Button(onClick = onToggleVideo, modifier = Modifier.weight(1f)) {
                    Text(if (localVideoEnabled) "Camera off" else "Camera on")
                }
                Button(onClick = onFlipCamera, modifier = Modifier.weight(1f)) {
                    Text("Flip")
                }
                Button(onClick = onEndCall, modifier = Modifier.weight(1f)) {
                    Text("Hang up")
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(
    modifier: Modifier,
    onAttach: (SurfaceViewRenderer) -> Unit,
    onDetach: (SurfaceViewRenderer) -> Unit
) {
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context) }

    DisposableEffect(renderer) {
        onAttach(renderer)
        onDispose {
            onDetach(renderer)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { renderer }
    )
}
