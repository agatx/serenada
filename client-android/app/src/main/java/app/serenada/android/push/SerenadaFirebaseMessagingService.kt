package app.serenada.android.push

import android.util.Log
import app.serenada.android.data.SettingsStore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SerenadaFirebaseMessagingService : FirebaseMessagingService() {
    private val notificationHandler by lazy { PushNotificationHandler(applicationContext) }

    override fun onNewToken(token: String) {
        val normalized = token.trim()
        if (normalized.isBlank()) return
        SettingsStore(applicationContext).pushEndpoint = normalized
        Log.d("Push", "FCM token updated")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val payload = remoteMessage.data
        if (payload.isNullOrEmpty()) {
            return
        }
        notificationHandler.showFromPayload(payload)
    }
}
