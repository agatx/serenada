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

/**
 * The effective `mediaProjection` FGS-type decision for one [CallService] update
 * (multi-call session, P5-1 fix). The type is driven by the live call list's
 * screen-share state ([CallServicePlan.includeMediaProjection]) so it is dropped
 * once no live call is sharing — even when the share stopped IMPLICITLY (switch,
 * hold, end) without going through the explicit stop path.
 *
 * The transient [pending] flag only ARMS the type for the gap between requesting a
 * share and the registry reporting `isScreenSharing` (the OS needs the FGS type
 * present before [android.media.projection.MediaProjection] capture can start).
 * Once the plan confirms a live share, [nextPending] retires the flag so all
 * subsequent updates derive the type purely from the live call list — the flag can
 * never go sticky and keep the type after the share is gone.
 */
data class MediaProjectionDecision(
    /** Whether THIS foreground update should carry the `mediaProjection` type. */
    val include: Boolean,
    /** The [pending]-flag value to keep after this update (retired once confirmed live). */
    val nextPending: Boolean,
) {
    companion object {
        fun resolve(plan: CallServicePlan, pending: Boolean): MediaProjectionDecision =
            MediaProjectionDecision(
                include = plan.includeMediaProjection || pending,
                // Once a live call confirms the projection, the plan alone drives the
                // type; drop the transient arm so it can never linger past the share.
                nextPending = pending && !plan.includeMediaProjection,
            )
    }
}
