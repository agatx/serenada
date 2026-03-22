package app.serenada.core

/**
 * Delegate interface for receiving SDK lifecycle callbacks.
 * All methods have default no-op implementations so only relevant callbacks need overriding.
 */
interface SerenadaCoreDelegate {
    /**
     * Called when a session requires permissions before joining.
     * The host app or call-ui should request permissions and then call session.resumeJoin().
     */
    fun onPermissionsRequired(session: SerenadaSession, permissions: List<MediaCapability>) {}

    /**
     * Called when the session state changes.
     */
    fun onSessionStateChanged(session: SerenadaSession, state: CallState) {}

    /**
     * Called when a session ends.
     */
    fun onSessionEnded(session: SerenadaSession, reason: EndReason) {}
}

sealed class EndReason {
    object LocalLeft : EndReason()
    object RemoteEnded : EndReason()
    data class Error(val error: CallError) : EndReason()
}
