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
    object PermissionDenied : CallError()
    data class ServerError(val message: String) : CallError()
    data class Unknown(val message: String) : CallError()

    /** Human-readable message for UI display. */
    val displayMessage: String get() = when (this) {
        is SignalingTimeout -> "Connection timed out"
        is ConnectionFailed -> "Connection failed"
        is RoomFull -> "Room is full"
        is RoomEnded -> "Call ended"
        is PermissionDenied -> "Permission denied"
        is ServerError -> message
        is Unknown -> message
    }
}
