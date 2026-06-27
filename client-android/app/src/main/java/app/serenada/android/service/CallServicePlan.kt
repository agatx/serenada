package app.serenada.android.service

/**
 * One managed call as the foreground service needs to see it (multi-call session,
 * design "Foreground Service"). This is a deliberately thin, framework-free
 * projection of the registry's call list so the start/stop and notification
 * decisions below are pure and unit-testable.
 */
data class CallServiceCall(
    /** Stable registry CallId — the target of a per-held-call "switch" action. */
    val callId: String,
    /** Display name for the notification (room name or room id). */
    val label: String,
    /** True while this call holds the single foreground lease (the active call). */
    val isForeground: Boolean,
    /** True once the call has ended/torn down; ended calls do not keep the service up. */
    val isEnded: Boolean,
    /** True while this call owns the MediaProjection screen share (foreground-only). */
    val isScreenSharing: Boolean,
)

/**
 * The foreground-service decision derived from the current registry call list
 * (design "Foreground Service"). Pure: [CallService] just applies it.
 *
 * Invariants encoded here (design "Foreground Service" + Core Invariant 5):
 * - The service STOPS only when there is ZERO non-ended call. Holding or ending
 *   one call while another exists keeps the service up ([shouldRun]).
 * - The `mediaProjection` FGS type applies only while SOME non-ended call still
 *   holds a projection ([includeMediaProjection]); switching away from a
 *   screen-sharing call (which stops its share first) drops the type on the next
 *   update WITHOUT tearing the service down.
 * - Notification primary actions (mute, end) map to the ACTIVE (foreground) call
 *   ([activeCallId]); held calls are summary text ([heldCallCount]) plus optional
 *   per-call switch targets ([heldCalls]). When the active call ends while held
 *   calls remain, [activeCallId] is null and the notification reflects "calls on
 *   hold" (no auto-promote, Core Invariant 5).
 */
data class CallServicePlan(
    /** Whether the foreground service should be running at all. */
    val shouldRun: Boolean,
    /** Whether the `mediaProjection` FGS type should be present on the next update. */
    val includeMediaProjection: Boolean,
    /** The foreground (active) call, or null when all non-ended calls are held. */
    val activeCallId: String?,
    /** The active call's label for the notification title/text, when active. */
    val activeLabel: String?,
    /** Number of non-ended HELD calls (drives "N on hold" summary text). */
    val heldCallCount: Int,
    /** Non-ended held calls, for optional per-call "switch" actions. */
    val heldCalls: List<CallServiceCall>,
) {
    companion object {
        /**
         * Compute the plan from the current registry call list. Ended calls are
         * ignored for keep-alive and FGS-type purposes; a non-ended call holding a
         * projection keeps the `mediaProjection` type.
         */
        fun from(calls: List<CallServiceCall>): CallServicePlan {
            val live = calls.filter { !it.isEnded }
            val active = live.firstOrNull { it.isForeground }
            val held = live.filter { !it.isForeground }
            return CallServicePlan(
                shouldRun = live.isNotEmpty(),
                includeMediaProjection = live.any { it.isScreenSharing },
                activeCallId = active?.callId,
                activeLabel = active?.label,
                heldCallCount = held.size,
                heldCalls = held,
            )
        }
    }
}
