package app.serenada.core

import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.CallPhase
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.MediaActivationState

/**
 * Stable, registry-generated identity for a managed call (multi-call session,
 * contract §1). A `CallId` is created when the registry first joins a room and is
 * stable for the life of that managed call. It is NOT the room id and NOT a
 * host-supplied correlation key — those are display/dedup concerns, not identity.
 */
typealias CallId = String

/**
 * How a host names the room to join (contract §7 "Call identity policy"). The
 * registry canonicalizes BOTH forms the same way before dedup: it extracts the
 * room token from a `/call/<token>` URL path (host-agnostic, so `serenada.app`
 * and `serenada-app.ru` URLs for the same token collapse to one room), and uses a
 * bare room id verbatim. Reuses the existing room-token parsing; no new format.
 */
sealed interface RoomRef {
    /** A full room URL, e.g. `https://serenada.app/call/ABC123`. */
    data class Url(val url: String) : RoomRef

    /**
     * A bare room id with an optional server host override. When [serverHost] is
     * null the registry's [SerenadaCore] config server host is used.
     */
    data class Id(val roomId: String, val serverHost: String? = null) : RoomRef
}

/**
 * The canonical room id used by [SerenadaCallRegistry] for duplicate-join dedup
 * (contract §7 "Call identity policy"). Host-agnostic: a `/call/<token>` URL
 * collapses to `<token>` regardless of scheme/host/query/fragment/trailing slash,
 * so `serenada.app` and `serenada-app.ru` URLs for the same room are one key; a
 * bare id is taken verbatim. Reuses the same last-path-segment extraction the
 * single-call join already uses (`SerenadaCore.resolveRoomUrl`); no new format.
 */
internal fun canonicalRoomId(room: RoomRef): String = when (room) {
    is RoomRef.Id -> room.roomId.trim()
    is RoomRef.Url -> {
        val trimmed = room.url.trim()
        if (!trimmed.contains("/")) {
            trimmed
        } else {
            extractRoomToken(trimmed)
                ?: trimmed.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: trimmed
        }
    }
}

/**
 * Single source of truth for pulling the room token out of a `/call/<token>` URL
 * (contract §7 "Call identity policy"). Used by BOTH [canonicalRoomId] (registry
 * dedup key) and [SerenadaCore.resolveRoomUrl] (the join path), so the dedup key
 * can never disagree with the room the join connects to. Returns the segment that
 * FOLLOWS `call/` (web `roomIdentity.canonicalizeRoomId` and iOS `DeepLinkParser`
 * key on the `/call/` segment, not the last segment), falling back to the last
 * segment for non-`/call/` URLs; null when there is no usable segment.
 */
internal fun extractRoomToken(input: String): String? {
    val trimmed = input.trim()
    return try {
        val segments = android.net.Uri.parse(trimmed).pathSegments
        val callIndex = segments.indexOf("call")
        // Key on the segment after "call" so a non-canonical `/call/<token>/extra`
        // resolves to <token> (parity with web/iOS) instead of the trailing extra
        // that lastPathSegment would return. Fall back to the last segment when the
        // URL has no `call/` segment.
        val token = if (callIndex != -1 && callIndex + 1 < segments.size) {
            segments[callIndex + 1]
        } else {
            segments.lastOrNull()
        }
        token?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

/**
 * Result of [SerenadaCallRegistry.joinHeld] — joining a room WITHOUT taking the
 * foreground lease (the new call sits HELD; contract §7).
 */
sealed interface JoinResult {
    /** The room was joined held; [callId] identifies the managed call. */
    data class Joined(val callId: CallId) : JoinResult

    /**
     * The join failed. [callId] is present iff the managed call was created
     * before the failure (so the host can inspect/dismiss it); [error] carries
     * the cause.
     */
    data class Failed(val callId: CallId?, val error: CallRegistryError) : JoinResult
}

/**
 * Result of [SerenadaCallRegistry.switchToCall] — moving the foreground lease to
 * an existing managed call (contract §7).
 */
sealed interface SwitchResult {
    /** The target is now the active (foreground) call. */
    object Active : SwitchResult

    /**
     * The target needs a mic/camera grant for its desired media before it can
     * foreground; the previous foreground call is left untouched (Core Invariant
     * 4). The host prompts, then retries [SerenadaCallRegistry.switchToCall].
     */
    object NeedsPermission : SwitchResult

    /** The switch failed; see [error]. The old call's foreground state is per §7. */
    data class Failed(val error: CallRegistryError) : SwitchResult
}

/**
 * Result of [SerenadaCallRegistry.joinAndSwitch] — joining a room held then
 * switching to it (the common new-call flow; contract §7).
 */
sealed interface JoinAndSwitchResult {
    /** The new call ([callId]) was joined and is now the active foreground call. */
    data class Active(val callId: CallId) : JoinAndSwitchResult

    /**
     * The new call was joined held ([callId] exists) but needs a permission grant
     * before it can foreground; the prior active call is untouched. The host
     * prompts, then [SerenadaCallRegistry.switchToCall]([callId]). [callId] MUST
     * be carried so the host knows which call to retry.
     */
    data class NeedsPermission(val callId: CallId) : JoinAndSwitchResult

    /**
     * The flow failed. [callId] is present iff the held call was created before
     * the failure (e.g. an activation failure after a successful held join);
     * absent iff the held room join itself failed before registration completed.
     */
    data class Failed(val callId: CallId?, val error: CallRegistryError) : JoinAndSwitchResult
}

/**
 * A registry-level or per-call error surfaced through [CallRegistryState.lastError]
 * and [ManagedCallState.activationError] (contract §11). Registry-level
 * [lastError] is not enough once multiple calls exist, so each managed call also
 * carries its own [ManagedCallState.activationError].
 */
sealed interface CallRegistryError {
    /** A human-readable message for diagnostics/logging. */
    val message: String

    /** The held room join failed or timed out. */
    data class JoinFailed(override val message: String) : CallRegistryError

    /**
     * The target needs a device permission for its desired media (mirrors
     * [SwitchResult.NeedsPermission]); surfaced on the call so UI can prompt.
     */
    data class NeedsPermission(override val message: String) : CallRegistryError

    /** Draining the old foreground call did not confirm fully-held in time. */
    data class ReleaseFailed(override val message: String) : CallRegistryError

    /** Activating the target's foreground media failed or timed out. */
    data class ActivationFailed(override val message: String) : CallRegistryError

    /**
     * Both the new activation AND the rollback to the previous call failed; the
     * process is left with no foreground owner (`activeCallId == null`).
     */
    data class ActivationAndRollbackFailed(override val message: String) : CallRegistryError

    /** The requested call id is not (or no longer) a managed call. */
    data class CallNotFound(override val message: String) : CallRegistryError

    /** A cross-mode / lease-arbitration conflict (e.g. a direct session owns the process). */
    data class LeaseUnavailable(override val message: String) : CallRegistryError
}

/**
 * Published, value-type snapshot of one managed call (contract §11 / design
 * "Managed Call"). The three media axes are orthogonal: [membershipPhase] (room
 * membership), [mediaRole] (lease ownership), [mediaActivationState] (foreground
 * activation progress). Do not hide the underlying [SerenadaSession] —
 * [SerenadaCallRegistry] exposes the active call's session for diagnostics and
 * custom rendering.
 *
 * Desired vs actual: [desiredAudioEnabled]/[desiredVideoMode] are user intent and
 * survive hold; [actualAudioPublished]/[actualVideoPublished] are what peers
 * observe now (always false while held).
 */
data class ManagedCallState(
    /** Registry-generated stable identity. */
    val callId: CallId,
    /** Canonical room id (the dedup key). */
    val roomId: String,
    /** Full room URL when known. */
    val roomUrl: String?,
    /** Room membership phase (orthogonal to role/activation). */
    val membershipPhase: CallPhase,
    /** Which call holds the foreground lease. */
    val mediaRole: CallMediaRole,
    /** Foreground-activation progress (incl. needsPermission). */
    val mediaActivationState: MediaActivationState,
    /** Desired audio intent (survives hold). */
    val desiredAudioEnabled: Boolean,
    /** Desired video mode (null = off). Survives hold. */
    val desiredVideoMode: LocalCameraMode?,
    /** Audio published to peers right now (false while held). */
    val actualAudioPublished: Boolean,
    /** Video published to peers right now (false while held). */
    val actualVideoPublished: Boolean,
    /** Participants currently in the call. */
    val participantCount: Int,
    /** Local client id assigned by the server, when known. */
    val localCid: String?,
    /** Convenience flag: `mediaRole == HELD`. */
    val held: Boolean,
    /** Local participant display name, if set. */
    val displayName: String?,
    /**
     * Per-call error: a failed activation, failed/timed-out release, failed/
     * timed-out join, or the permission that is needed. Registry-level
     * [CallRegistryState.lastError] is not enough once multiple calls exist.
     */
    val activationError: CallRegistryError?,
    /** Aggregate call-quality summary, populated after the call ends. */
    val qualitySummary: CallQualitySummary?,
)

/**
 * Aggregate, observable registry state (contract §11). Published as a
 * [kotlinx.coroutines.flow.StateFlow] by [SerenadaCallRegistry].
 */
data class CallRegistryState(
    /** All managed calls (ended calls are removed after retention/dismiss). */
    val calls: List<ManagedCallState> = emptyList(),
    /**
     * The id of the single call whose [ManagedCallState.mediaRole] is FOREGROUND,
     * or null when none is foreground. Derived; at most one.
     */
    val activeCallId: CallId? = null,
    /** True while any queued registry op (join/switch/hold/leave/end) is running. */
    val registryOperationInProgress: Boolean = false,
    /** The most recent registry-level error (e.g. a mode conflict), or null. */
    val lastError: CallRegistryError? = null,
)
