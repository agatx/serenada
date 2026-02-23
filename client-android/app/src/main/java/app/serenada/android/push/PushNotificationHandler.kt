package app.serenada.android.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.Person
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.serenada.android.MainActivity
import app.serenada.android.R
import app.serenada.android.data.SavedRoomStore
import app.serenada.android.data.SettingsStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class PushNotificationHandler(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val savedRoomStore = SavedRoomStore(appContext)
    private val pushKeyStore = PushKeyStore()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    fun showFromPayload(data: Map<String, String>) {
        if (data.isEmpty()) return
        val host = resolveHost(data)
        val kind = data["kind"]?.trim()?.lowercase().orEmpty().ifBlank { PUSH_KIND_JOIN }
        val callPath = normalizeCallPath(data["url"])
        val roomId = extractRoomId(callPath)

        if (kind == PUSH_KIND_INVITE) {
            if (!settingsStore.areRoomInviteNotificationsEnabled) {
                return
            }
            if (roomId == null || !savedRoomStore.hasRoom(roomId)) {
                return
            }
            if (isInviteSuppressed(roomId)) {
                return
            }
        }

        val title = data["title"].orEmpty().ifBlank { appContext.getString(R.string.app_name) }
        val body = data["body"].orEmpty().ifBlank {
            if (kind == PUSH_KIND_INVITE) {
                appContext.getString(R.string.push_notification_invite_body)
            } else {
                appContext.getString(R.string.push_notification_default_body)
            }
        }
        val snapshot = if (kind == PUSH_KIND_INVITE) null else loadSnapshotBitmap(host, data)

        showNotification(
            host = host,
            callPath = callPath,
            roomId = roomId,
            kind = kind,
            title = title,
            body = body,
            snapshot = snapshot
        )
    }

    private fun resolveHost(data: Map<String, String>): String {
        val payloadHost = normalizePayloadHost(data["host"]) ?: extractHostFromAbsoluteUrl(data["url"])
        return payloadHost ?: settingsStore.host
    }

    private fun normalizePayloadHost(rawHost: String?): String? {
        val hostValue = rawHost?.trim().orEmpty()
        if (hostValue.isBlank()) return null
        extractHostFromAbsoluteUrl(hostValue)?.let { return it }
        val normalized = hostValue.substringBefore('/').trim()
        return normalized.ifBlank { null }
    }

    private fun extractHostFromAbsoluteUrl(rawUrl: String?): String? {
        val value = rawUrl?.trim().orEmpty()
        if (value.isBlank()) return null
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val uri = Uri.parse(value)
        val host = uri.host?.trim().orEmpty()
        if (host.isBlank()) return null
        return if (uri.port > 0 && uri.port != 443) "$host:${uri.port}" else host
    }

    private fun loadSnapshotBitmap(host: String, data: Map<String, String>): Bitmap? {
        val snapshotId = data["snapshotId"]?.trim().orEmpty()
        val snapshotSalt = data["snapshotSalt"].orEmpty()
        val snapshotEphemeralPub = data["snapshotEphemeralPubKey"].orEmpty()
        val snapshotKey = data["snapshotKey"].orEmpty()
        val snapshotKeyIv = data["snapshotKeyIv"].orEmpty()
        val snapshotIv = data["snapshotIv"].orEmpty()

        if (!isSafeSnapshotId(snapshotId) || snapshotSalt.isBlank() || snapshotEphemeralPub.isBlank() ||
            snapshotKey.isBlank() || snapshotKeyIv.isBlank() || snapshotIv.isBlank()
        ) {
            return null
        }

        val wrappedSnapshotKey = pushKeyStore.decryptWrappedSnapshotKey(
            snapshotSaltB64 = snapshotSalt,
            snapshotEphemeralPubB64 = snapshotEphemeralPub,
            wrappedKeyB64 = snapshotKey,
            wrappedKeyIvB64 = snapshotKeyIv
        ) ?: return null

        val encryptedSnapshot = fetchSnapshotCiphertext(host, snapshotId) ?: return null
        val decryptedSnapshot = pushKeyStore.decryptSnapshot(
            ciphertext = encryptedSnapshot,
            snapshotKey = wrappedSnapshotKey,
            snapshotIvB64 = snapshotIv
        ) ?: return null

        return BitmapFactory.decodeByteArray(decryptedSnapshot, 0, decryptedSnapshot.size)
    }

    private fun fetchSnapshotCiphertext(host: String, snapshotId: String): ByteArray? {
        val url = buildHttpsUrl(host, "/api/push/snapshot/$snapshotId") ?: return null
        val request = Request.Builder().url(url).get().build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()
            }
        }.onFailure {
            Log.w("Push", "Failed to load encrypted snapshot", it)
        }.getOrNull()
    }

    private fun showNotification(
        host: String,
        callPath: String,
        roomId: String?,
        kind: String,
        title: String,
        body: String,
        snapshot: Bitmap?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return
            }
        }

        createChannelsIfNeeded()

        val callUrl = buildCallUrl(host, callPath)
        val tapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(callUrl), appContext, MainActivity::class.java)
            .apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val isInvite = kind == PUSH_KIND_INVITE
        val notificationId = if (isInvite) {
            (roomId ?: callPath).hashCode()
        } else {
            (roomId ?: callPath).hashCode() xor System.currentTimeMillis().toInt()
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            callUrl.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val managerCompat = NotificationManagerCompat.from(appContext)
        val builder = if (isInvite) {
            val inviteRoomId = roomId.orEmpty()
            val incomingScreenPendingIntent = IncomingInviteActivity.createPendingIntent(
                context = appContext,
                notificationId = notificationId,
                roomId = inviteRoomId,
                callUrl = callUrl,
                title = title,
                body = body
            )
            val answerPendingIntent = InviteNotificationActionReceiver.answerPendingIntent(
                context = appContext,
                notificationId = notificationId,
                roomId = inviteRoomId,
                callUrl = callUrl
            )
            val declinePendingIntent = InviteNotificationActionReceiver.declinePendingIntent(
                context = appContext,
                notificationId = notificationId,
                roomId = inviteRoomId
            )
            val caller = Person.Builder()
                .setName(title)
                .setImportant(true)
                .build()
            NotificationCompat.Builder(appContext, INVITE_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setTimeoutAfter(INVITE_RING_TIMEOUT_MS)
                .setDeleteIntent(declinePendingIntent)
                .setContentIntent(incomingScreenPendingIntent)
                .setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(
                        caller,
                        declinePendingIntent,
                        answerPendingIntent
                    )
                )
                .setFullScreenIntent(incomingScreenPendingIntent, true)
        } else {
            NotificationCompat.Builder(appContext, JOIN_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        }

        if (!isInvite && snapshot != null) {
            builder.setLargeIcon(snapshot)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(snapshot)
                    .setSummaryText(body)
            )
        }

        managerCompat.notify(notificationId, builder.build())
    }

    private fun createChannelsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val joinChannel = NotificationChannel(
            JOIN_CHANNEL_ID,
            appContext.getString(R.string.notification_join_alerts_channel),
            NotificationManager.IMPORTANCE_HIGH
        )
        val inviteChannel = NotificationChannel(
            INVITE_CHANNEL_ID,
            appContext.getString(R.string.notification_invite_calls_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableLights(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 800L, 600L, 800L)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(joinChannel)
        manager.createNotificationChannel(inviteChannel)
    }

    private fun normalizeCallPath(rawUrl: String?): String {
        val normalized = rawUrl?.trim().orEmpty()
        if (normalized.isBlank()) return "/"
        if (normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true)
        ) {
            val uri = Uri.parse(normalized)
            val path = uri.path.orEmpty()
            return if (path.startsWith("/")) path else "/$path"
        }
        return if (normalized.startsWith('/')) normalized else "/$normalized"
    }

    private fun extractRoomId(path: String): String? {
        val segments = path.trim().trim('/').split('/')
        if (segments.size < 2 || segments.first() != "call") return null
        return segments[1].ifBlank { null }
    }

    private fun buildCallUrl(hostInput: String, callPath: String): String {
        val path = if (callPath.startsWith('/')) callPath else "/$callPath"
        return buildHttpsUrl(hostInput, path) ?: "https://serenada.app$path"
    }

    private fun buildHttpsUrl(hostInput: String, path: String): String? {
        val raw = hostInput.trim()
        if (raw.isBlank()) return null
        val withScheme =
            if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val base = withScheme.toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .scheme("https")
            .encodedPath(path)
            .build()
            .toString()
    }

    private fun isSafeSnapshotId(value: String): Boolean {
        if (value.isBlank()) return false
        return value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private fun isInviteSuppressed(roomId: String): Boolean {
        val untilMs = settingsStore.inviteSuppressedUntilMs
        val suppressedRoomId = settingsStore.inviteSuppressedRoomId
        if (suppressedRoomId == roomId && untilMs > System.currentTimeMillis()) {
            return true
        }
        if (suppressedRoomId == roomId && untilMs > 0L) {
            settingsStore.inviteSuppressedRoomId = null
            settingsStore.inviteSuppressedUntilMs = 0L
        }
        if (suppressedRoomId.isNullOrBlank()) {
            return false
        }
        // Cleanup stale suppression if it belongs to a different room and expired.
        if (untilMs <= System.currentTimeMillis()) {
            settingsStore.inviteSuppressedRoomId = null
            settingsStore.inviteSuppressedUntilMs = 0L
        }
        if (System.currentTimeMillis() < untilMs) {
            return suppressedRoomId == roomId
        }
        return false
    }

    companion object {
        const val JOIN_CHANNEL_ID = "serenada_join_alerts"
        const val INVITE_CHANNEL_ID = "serenada_invite_calls"
        const val INVITE_RING_TIMEOUT_MS = 45_000L
        const val INVITE_SUPPRESSION_AFTER_ACTION_MS = 8_000L
        const val PUSH_KIND_JOIN = "join"
        const val PUSH_KIND_INVITE = "invite"

        fun suppressInviteForRoom(context: Context, roomId: String) {
            if (roomId.isBlank()) return
            val settingsStore = SettingsStore(context.applicationContext)
            settingsStore.inviteSuppressedRoomId = roomId
            settingsStore.inviteSuppressedUntilMs =
                System.currentTimeMillis() + INVITE_SUPPRESSION_AFTER_ACTION_MS
        }

        fun stopInviteAlertFeedback(context: Context) {
            val appContext = context.applicationContext
            val cancelVibration = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = appContext.getSystemService(VibratorManager::class.java)
                    manager?.defaultVibrator?.cancel()
                    manager?.vibratorIds?.forEach { id ->
                        manager.getVibrator(id).cancel()
                    }
                }
                @Suppress("DEPRECATION")
                (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
            }
            runCatching { cancelVibration() }
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.postDelayed({ runCatching { cancelVibration() } }, 200L)
            mainHandler.postDelayed({ runCatching { cancelVibration() } }, 700L)
        }
    }
}
