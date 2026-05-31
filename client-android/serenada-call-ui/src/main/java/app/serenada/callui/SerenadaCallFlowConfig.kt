package app.serenada.callui

enum class SerenadaCallUiVariant {
    Standard,
    Frontline,
}

data class SerenadaCallFlowConfig(
    val uiVariant: SerenadaCallUiVariant = SerenadaCallUiVariant.Standard,
    val screenSharingEnabled: Boolean = true,
    val inviteControlsEnabled: Boolean = true,
    val debugOverlayEnabled: Boolean = false,
    /**
     * When `true` (default), the call controls bar fades out after a few
     * seconds of idle time and a tap on the stage brings it back. When
     * `false`, the controls are always visible and the idle timer never runs.
     */
    val autoHideControls: Boolean = true,
    /**
     * When `true`, the call UI shows a circular shutter button anchored to
     * the short edge of the current large preview (bottom in portrait,
     * right in landscape). Tapping it captures the visible stream via
     * `SerenadaSession.captureSnapshot`. Defaults to `false`.
     */
    val snapshotEnabled: Boolean = false,
    /**
     * Optional resolver that returns an avatar for a remote participant's
     * host-supplied `peerId` (passed to `SerenadaCore.join`). When unset or
     * when `peerId` is absent on the participant, the call UI shows an
     * initials placeholder derived from their display name.
     */
    val avatarProvider: AvatarProvider? = null,
    /**
     * When `true` (default), the call UI shows the video on/off and camera
     * mode (flip) controls and the SDK requests camera permission on join.
     * When `false`, both controls are hidden and — for URL-first call flows
     * — the internally-created session is configured with no camera modes so
     * the camera is never requested. For session-first usage, host apps that
     * want a fully audio-only call should also pass `cameraModes = emptyList()`
     * to the `SerenadaConfig` used to build the session.
     */
    val videoEnabled: Boolean = true,
    /**
     * Enables Android system Picture-in-Picture while a call is active.
     *
     * Host apps must also mark their call activity with
     * `android:supportsPictureInPicture="true"` and appropriate
     * `android:configChanges` entries. When enabled, the call UI configures
     * the host activity's PiP params, auto-enters PiP on Home/gesture leave
     * where supported, and shows only the active video/avatar surface.
     */
    val systemPictureInPictureEnabled: Boolean = false,
)
