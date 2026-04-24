package app.serenada.callui

data class SerenadaCallFlowConfig(
    val screenSharingEnabled: Boolean = true,
    val inviteControlsEnabled: Boolean = true,
    val debugOverlayEnabled: Boolean = false,
    /**
     * When `true` (default), the call controls bar fades out after a few
     * seconds of idle time and a tap on the stage brings it back. When
     * `false`, the controls are always visible and the idle timer never runs.
     */
    val autoHideControls: Boolean = true,
)
