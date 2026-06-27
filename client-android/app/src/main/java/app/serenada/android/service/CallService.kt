package app.serenada.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.serenada.android.MainActivity
import app.serenada.android.R
import app.serenada.android.SerenadaApp

/**
 * Single-instance foreground service for the multi-call session (design
 * "Foreground Service"). Android foreground-service state is process-level, so
 * ONE service backs every managed call. The service is driven by a snapshot of
 * the registry's call list ([CallService.update]); all start/stop and
 * notification decisions come from the pure [CallServicePlan]:
 *
 * - The service STOPS only when there is zero non-ended call. Holding or ending
 *   one call while another exists keeps it running ([CallServicePlan.shouldRun]).
 * - The active call ending while held calls remain keeps the service up; the
 *   registry does NOT auto-promote (Core Invariant 5) and the notification
 *   reflects "calls on hold" until the host foregrounds one or the last call ends.
 * - Notification primary actions (mute, end) target the ACTIVE call; held calls
 *   are summary text plus optional per-call "switch" actions.
 * - The `mediaProjection` FGS type applies only while some call holds a
 *   projection; switching away from a screen-sharing call (which stops its share
 *   first) drops the type on the next [startForeground]/[ServiceCompat] update
 *   WITHOUT tearing the service down.
 * - [onTaskRemoved] leaves ALL registry calls, not just the active one.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> (application as SerenadaApp).callManager.toggleAudio()
            ACTION_END -> (application as SerenadaApp).callManager.endCall()
            ACTION_SWITCH -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (!callId.isNullOrBlank()) {
                    (application as SerenadaApp).callManager.switchToCall(callId)
                }
            }
        }
        // Every start re-derives the plan from the latest registry snapshot, so a
        // mute/switch/refresh and the normal ACTION_UPDATE share one code path.
        applyPlan()
        return START_NOT_STICKY
    }

    /**
     * Apply the current [CallServicePlan]: stop when no non-ended call remains,
     * otherwise (re)enter the foreground with the right FGS types + notification.
     * Stopping ONE call (held or foreground) does not stop the service while
     * another call still exists ([CallServicePlan.shouldRun]).
     */
    private fun applyPlan() {
        val plan = CallServicePlan.from(latestCalls)
        if (!plan.shouldRun) {
            mediaProjectionForegroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val includeProjection = plan.includeMediaProjection || pendingMediaProjection
        val notification = buildNotification(plan)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceTypes =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (includeProjection) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceTypes)
            mediaProjectionForegroundActive = includeProjection
        } else {
            startForeground(NOTIFICATION_ID, notification)
            // Pre-Q has no FGS-type gating for MediaProjection, so a running
            // foreground service is sufficient. Mark ready when this update wants a
            // projection, else startScreenShareWhenForegroundReady never passes its
            // gate and the share silently does nothing on old Android.
            mediaProjectionForegroundActive = includeProjection
        }
    }

    override fun onDestroy() {
        mediaProjectionForegroundActive = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Leave ALL registry calls, not just the active one (design "Foreground
        // Service"): swiping the task away tears down every joined call.
        (application as SerenadaApp).callManager.leaveAllCalls()
        stopSelf()
    }

    private fun buildNotification(plan: CallServicePlan): Notification {
        createChannelIfNeeded()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val heldSummary = when {
            plan.heldCallCount == 0 -> null
            plan.heldCallCount == 1 -> getString(R.string.notification_one_on_hold)
            else -> getString(R.string.notification_n_on_hold, plan.heldCallCount)
        }
        val contentText = when {
            plan.activeLabel != null && heldSummary != null -> "${plan.activeLabel} · $heldSummary"
            plan.activeLabel != null -> plan.activeLabel
            heldSummary != null -> heldSummary
            else -> getString(R.string.notification_in_call)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_call_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        // Primary actions (mute, end) map to the ACTIVE call only. With no active
        // call (all held), the notification is summary-only — there is no
        // foreground call to mute/end (Core Invariant 5: no auto-promote).
        if (plan.activeCallId != null) {
            builder.addAction(
                0,
                getString(R.string.notification_action_mute),
                servicePendingIntent(ACTION_MUTE, requestCode = 1),
            )
            builder.addAction(
                0,
                getString(R.string.notification_action_end),
                servicePendingIntent(ACTION_END, requestCode = 2),
            )
        }
        // Optional per-held-call "switch" action (cap at one to keep the
        // notification minimal; targets a specific CallId).
        plan.heldCalls.firstOrNull()?.let { held ->
            builder.addAction(
                0,
                getString(R.string.notification_action_switch),
                servicePendingIntent(ACTION_SWITCH, requestCode = 3, callId = held.callId),
            )
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int, callId: String? = null): PendingIntent {
        val intent = Intent(this, CallService::class.java).apply {
            this.action = action
            if (callId != null) putExtra(EXTRA_CALL_ID, callId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_call_status_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "serenada_call"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_UPDATE = "app.serenada.android.action.UPDATE_CALL"
        private const val ACTION_MUTE = "app.serenada.android.action.MUTE_CALL"
        private const val ACTION_END = "app.serenada.android.action.END_CALL"
        private const val ACTION_SWITCH = "app.serenada.android.action.SWITCH_CALL"
        private const val EXTRA_CALL_ID = "call_id"

        // The latest registry snapshot the service derives its plan from. Set by
        // CallManager on every registry-state change and read by onStartCommand.
        @Volatile
        private var latestCalls: List<CallServiceCall> = emptyList()

        // Set true between requesting a screen share and the service actually
        // entering the foreground with the mediaProjection type, so an update that
        // races the registry's isScreenSharing flag still carries the type.
        @Volatile
        private var pendingMediaProjection: Boolean = false

        @Volatile
        private var mediaProjectionForegroundActive = false

        fun isMediaProjectionForegroundActive(): Boolean = mediaProjectionForegroundActive

        /**
         * Push a fresh registry snapshot and (re)apply the foreground-service plan.
         * Starts the service when a call exists, restarts the foreground with the
         * right FGS types + notification, or stops it when no non-ended call
         * remains. Idempotent — safe to call on every registry-state change.
         */
        fun update(context: Context, calls: List<CallServiceCall>) {
            latestCalls = calls
            val plan = CallServicePlan.from(calls)
            if (!plan.shouldRun) {
                // No call left: tell a running instance to stop (so it tears down its
                // own foreground state); never start one just to stop it.
                pendingMediaProjection = false
                context.stopService(Intent(context, CallService::class.java))
                return
            }
            startUpdate(context)
        }

        /**
         * Arm the `mediaProjection` FGS type before a screen share begins so the
         * NEXT foreground update carries it, then refresh the service. Cleared on
         * the next snapshot once the registry reports the projection (or it is
         * cancelled via [clearPendingMediaProjection]).
         */
        fun startScreenShareForeground(context: Context) {
            pendingMediaProjection = true
            startUpdate(context)
        }

        /**
         * Drop the armed `mediaProjection` type (screen share stopped or failed to
         * start) and refresh; the next update sheds the type without tearing down
         * the service (design "Foreground Service").
         */
        fun clearPendingMediaProjection(context: Context) {
            pendingMediaProjection = false
            // Only refresh a service that should still be running; otherwise let the
            // normal stop path handle it.
            if (CallServicePlan.from(latestCalls).shouldRun) {
                startUpdate(context)
            }
        }

        private fun startUpdate(context: Context) {
            val intent = Intent(context, CallService::class.java).apply { action = ACTION_UPDATE }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            latestCalls = emptyList()
            pendingMediaProjection = false
            mediaProjectionForegroundActive = false
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
