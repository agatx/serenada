package app.serenada.android.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import app.serenada.android.MainActivity
import app.serenada.android.R
import app.serenada.android.ui.SerenadaTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class IncomingInviteActivity : ComponentActivity() {
    private var payload by mutableStateOf<InvitePayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindowForIncomingInvite()
        payload = parsePayload(intent)
        if (payload == null) {
            finish()
            return
        }
        setContent {
            SerenadaTheme {
                payload?.let { current ->
                    IncomingInviteScreen(
                        title = current.title,
                        body = current.body,
                        onAccept = { acceptInvite(current) },
                        onDecline = { declineInvite(current) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        payload = parsePayload(intent)
    }

    private fun acceptInvite(payload: InvitePayload) {
        PushNotificationHandler.stopInviteAlertFeedback(this)
        NotificationManagerCompat.from(this).cancel(payload.notificationId)
        PushNotificationHandler.suppressInviteForRoom(this, payload.roomId)
        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(payload.callUrl), this, MainActivity::class.java)
            .apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_DISMISS_NOTIFICATION_ID, payload.notificationId)
                putExtra(MainActivity.EXTRA_SHOW_CALL_OVER_LOCK_SCREEN, true)
            }
        startActivity(openIntent)
        finish()
    }

    private fun declineInvite(payload: InvitePayload) {
        PushNotificationHandler.stopInviteAlertFeedback(this)
        NotificationManagerCompat.from(this).cancel(payload.notificationId)
        PushNotificationHandler.suppressInviteForRoom(this, payload.roomId)
        finish()
    }

    private fun configureWindowForIncomingInvite() {
        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun parsePayload(intent: Intent?): InvitePayload? {
        val callUrl = intent?.getStringExtra(EXTRA_CALL_URL).orEmpty()
        val roomId = intent?.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, INVALID_NOTIFICATION_ID)
            ?: INVALID_NOTIFICATION_ID
        if (callUrl.isBlank() || roomId.isBlank() || notificationId == INVALID_NOTIFICATION_ID) {
            return null
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank {
            getString(R.string.app_name)
        }
        val body = intent?.getStringExtra(EXTRA_BODY).orEmpty().ifBlank {
            getString(R.string.push_notification_invite_body)
        }
        return InvitePayload(
            roomId = roomId,
            callUrl = callUrl,
            title = title,
            body = body,
            notificationId = notificationId
        )
    }

    companion object {
        private const val EXTRA_CALL_URL = "call_url"
        private const val EXTRA_ROOM_ID = "room_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val INVALID_NOTIFICATION_ID = Int.MIN_VALUE
        private const val OPEN_REQUEST_MASK = 0x40000

        fun createPendingIntent(
            context: Context,
            notificationId: Int,
            roomId: String,
            callUrl: String,
            title: String,
            body: String
        ): PendingIntent {
            val intent = Intent(context, IncomingInviteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_CALL_URL, callUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            return PendingIntent.getActivity(
                context,
                notificationId xor OPEN_REQUEST_MASK,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

private data class InvitePayload(
    val roomId: String,
    val callUrl: String,
    val title: String,
    val body: String,
    val notificationId: Int
)

@Composable
private fun IncomingInviteScreen(
    title: String,
    body: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val background = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
            MaterialTheme.colorScheme.surface
        )
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 24.dp, vertical = 36.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            SwipeDecisionControl(
                modifier = Modifier.fillMaxWidth(),
                onAccept = onAccept,
                onDecline = onDecline
            )
        }
    }
}

@Composable
private fun SwipeDecisionControl(
    modifier: Modifier = Modifier,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var acted by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(84.dp)
            .clip(RoundedCornerShape(42.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(42.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CallEnd, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    text = stringResource(R.string.notification_decline),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.notification_answer),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
                Icon(Icons.Default.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }

        val dragState = rememberDraggableState { delta ->
            if (acted) return@rememberDraggableState
            scope.launch {
                offsetX.snapTo((offsetX.value + delta).coerceIn(-260f, 260f))
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    state = dragState,
                    onDragStopped = {
                        if (acted) return@draggable
                        when {
                            offsetX.value > 170f -> {
                                acted = true
                                onAccept()
                            }
                            offsetX.value < -170f -> {
                                acted = true
                                onDecline()
                            }
                            else -> {
                                scope.launch { offsetX.animateTo(0f, spring()) }
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⇆",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}
