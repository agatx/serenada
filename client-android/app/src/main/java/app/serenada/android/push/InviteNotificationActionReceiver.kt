package app.serenada.android.push

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import app.serenada.android.MainActivity

class InviteNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, INVALID_NOTIFICATION_ID)
        if (notificationId == INVALID_NOTIFICATION_ID) return
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        val callUrl = intent.getStringExtra(EXTRA_CALL_URL).orEmpty()
        PushNotificationHandler.stopInviteAlertFeedback(context)
        NotificationManagerCompat.from(context).cancel(notificationId)

        when (intent.action) {
            ACTION_DECLINE -> {
                PushNotificationHandler.suppressInviteForRoom(context, roomId)
            }
            ACTION_ANSWER -> {
                if (callUrl.isBlank()) return
                PushNotificationHandler.suppressInviteForRoom(context, roomId)
                val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(callUrl), context, MainActivity::class.java)
                    .apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(MainActivity.EXTRA_DISMISS_NOTIFICATION_ID, notificationId)
                        putExtra(MainActivity.EXTRA_SHOW_CALL_OVER_LOCK_SCREEN, true)
                    }
                runCatching { context.startActivity(openIntent) }
            }
        }
    }

    companion object {
        private const val ACTION_DECLINE = "app.serenada.android.push.ACTION_DECLINE_INVITE"
        private const val ACTION_ANSWER = "app.serenada.android.push.ACTION_ANSWER_INVITE"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_ROOM_ID = "room_id"
        private const val EXTRA_CALL_URL = "call_url"
        private const val INVALID_NOTIFICATION_ID = Int.MIN_VALUE
        private const val DECLINE_REQUEST_MASK = 0x20000
        private const val ANSWER_REQUEST_MASK = 0x30000

        fun declinePendingIntent(context: Context, notificationId: Int, roomId: String): PendingIntent {
            val intent = Intent(context, InviteNotificationActionReceiver::class.java).apply {
                action = ACTION_DECLINE
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_ROOM_ID, roomId)
            }
            return PendingIntent.getBroadcast(
                context,
                notificationId xor DECLINE_REQUEST_MASK,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun answerPendingIntent(
            context: Context,
            notificationId: Int,
            roomId: String,
            callUrl: String
        ): PendingIntent {
            val intent = Intent(context, InviteNotificationActionReceiver::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_CALL_URL, callUrl)
            }
            return PendingIntent.getBroadcast(
                context,
                notificationId xor ANSWER_REQUEST_MASK,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
