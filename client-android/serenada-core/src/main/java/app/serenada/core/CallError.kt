package app.serenada.core

/**
 * Typed error representation for SDK call errors.
 * Matches the iOS `CallError` enum for cross-platform parity.
 */
sealed class CallError {
    object SignalingTimeout : CallError()
    object ConnectionFailed : CallError()
    object RoomFull : CallError()
    object RoomEnded : CallError()
    /**
     * The persisted reconnect credential is no longer valid (expired or
     * rejected). The SDK must clear stored reconnect state and surface a
     * dedicated terminal error so the host app can route the user back to
     * a fresh start instead of looping reconnects.
     */
    object SessionExpired : CallError()
    object PermissionDenied : CallError()
    /**
     * The configured single-session (v1) [SignalingProvider] is already bound by
     * another live session, so this concurrent join cannot proceed. Multi-call hosts
     * must supply a [MultiSessionSignalingProvider] instead. See
     * [SINGLE_SESSION_PROVIDER_IN_USE_MESSAGE].
     */
    object ProviderUnavailable : CallError()
    data class ServerError(val message: String) : CallError()
    data class Unknown(val message: String) : CallError()

    /** Human-readable message for UI display. */
    val displayMessage: String get() = when (this) {
        is SignalingTimeout -> "Connection timed out"
        is ConnectionFailed -> "Connection failed"
        is RoomFull -> "Room is full"
        is RoomEnded -> "Call ended"
        is SessionExpired -> "Session expired"
        is PermissionDenied -> "Permission denied"
        is ProviderUnavailable -> "Signaling provider is unavailable"
        is ServerError -> message
        is Unknown -> message
    }
}

/**
 * Descriptive cause surfaced when a second concurrent session tries to bind a
 * single-session (v1) [SignalingProvider] (contract §"v1 liveness guard"). Carried
 * by [SingleSessionProviderInUseException] and, for registry joins, relayed into
 * [CallRegistryError.JoinFailed].
 */
internal const val SINGLE_SESSION_PROVIDER_IN_USE_MESSAGE: String =
    "Signaling provider is single-session (v1); a session is already using it. " +
        "Provide a version-2 MultiSessionSignalingProvider for multi-call."

/**
 * Thrown by [SerenadaCore] when a join would bind the configured single-session
 * (v1) [SignalingProvider] while another live session already holds it. Registry
 * joins catch this and surface it as a [JoinResult.Failed]/[JoinAndSwitchResult.Failed]
 * carrying [CallRegistryError.JoinFailed]; a direct [SerenadaCore.join] propagates it
 * (a catchable configuration failure, never a crash). Sequential reuse — join after
 * the prior session has fully closed — is unaffected.
 */
class SingleSessionProviderInUseException(
    message: String = SINGLE_SESSION_PROVIDER_IN_USE_MESSAGE,
) : IllegalStateException(message) {
    /** Typed cause for surfacing as a [CallState] error on direct joins. */
    val callError: CallError = CallError.ProviderUnavailable
}
