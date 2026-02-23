package app.serenada.android

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import app.serenada.android.push.PushNotificationHandler
import app.serenada.android.ui.SerenadaAppRoot

class MainActivity : AppCompatActivity() {
    companion object {
        private const val STATE_PENDING_DEEP_LINK = "pending_deep_link"
        private const val STATE_DEEP_LINK_REQUEST_ID = "deep_link_request_id"
        const val EXTRA_DISMISS_NOTIFICATION_ID = "dismiss_notification_id"
        const val EXTRA_SHOW_CALL_OVER_LOCK_SCREEN = "show_call_over_lock_screen"
        private const val INVALID_NOTIFICATION_ID = Int.MIN_VALUE
    }

    private val callManager by lazy { (application as SerenadaApp).callManager }
    private var pendingDeepLinkUri by mutableStateOf<Uri?>(null)
    private var deepLinkRequestId by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLinkUri = restorePendingDeepLink(savedInstanceState)
        deepLinkRequestId = restoreDeepLinkRequestId(savedInstanceState)
        applyLockScreenOverrideFromIntent(intent)
        dismissNotificationFromIntent(intent)
        setContent {
            SerenadaAppRoot(
                callManager = callManager,
                deepLinkUri = pendingDeepLinkUri,
                deepLinkRequestId = deepLinkRequestId,
                onDeepLinkConsumed = { pendingDeepLinkUri = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLockScreenOverrideFromIntent(intent)
        dismissNotificationFromIntent(intent)
        pendingDeepLinkUri = intent.data
        deepLinkRequestId += 1
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_DEEP_LINK, pendingDeepLinkUri?.toString())
        outState.putInt(STATE_DEEP_LINK_REQUEST_ID, deepLinkRequestId)
    }

    private fun restorePendingDeepLink(savedInstanceState: Bundle?): Uri? {
        if (savedInstanceState?.containsKey(STATE_PENDING_DEEP_LINK) == true) {
            return savedInstanceState
                .getString(STATE_PENDING_DEEP_LINK)
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse)
        }
        return intent?.data
    }

    private fun restoreDeepLinkRequestId(savedInstanceState: Bundle?): Int {
        if (savedInstanceState?.containsKey(STATE_DEEP_LINK_REQUEST_ID) == true) {
            return savedInstanceState.getInt(STATE_DEEP_LINK_REQUEST_ID)
        }
        return if (intent?.data != null) 1 else 0
    }

    private fun dismissNotificationFromIntent(intent: Intent?) {
        val notificationId = intent?.getIntExtra(EXTRA_DISMISS_NOTIFICATION_ID, INVALID_NOTIFICATION_ID)
            ?: INVALID_NOTIFICATION_ID
        if (notificationId == INVALID_NOTIFICATION_ID) return
        PushNotificationHandler.stopInviteAlertFeedback(this)
        NotificationManagerCompat.from(this).cancel(notificationId)
        extractRoomIdFromCallDeepLink(intent?.data)?.let { roomId ->
            PushNotificationHandler.suppressInviteForRoom(this, roomId)
        }
        intent?.removeExtra(EXTRA_DISMISS_NOTIFICATION_ID)
    }

    private fun applyLockScreenOverrideFromIntent(intent: Intent?) {
        val shouldShowOverLockScreen = intent?.getBooleanExtra(EXTRA_SHOW_CALL_OVER_LOCK_SCREEN, false) == true
        if (!shouldShowOverLockScreen) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(KeyguardManager::class.java)
                    ?.requestDismissKeyguard(this, null)
            }
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        intent?.removeExtra(EXTRA_SHOW_CALL_OVER_LOCK_SCREEN)
    }

    private fun extractRoomIdFromCallDeepLink(uri: Uri?): String? {
        val segments = uri?.pathSegments ?: return null
        if (segments.size < 2 || segments.first() != "call") return null
        return segments[1].takeIf { it.isNotBlank() }
    }
}
