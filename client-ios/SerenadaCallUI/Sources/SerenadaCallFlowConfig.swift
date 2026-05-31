import Foundation

/// Visual presentation used by the prebuilt Serenada call UI.
public enum SerenadaCallUiVariant: String, CaseIterable {
    case standard
    case frontline
}

/// Configuration for SerenadaCallFlow feature toggles.
/// When a feature is disabled, the corresponding control is removed from the UI entirely.
public struct SerenadaCallFlowConfig {
    /// Show/hide screen share control. Default: `true`.
    public var screenSharingEnabled: Bool

    /// Show/hide QR code and invite/share buttons. Default: `true`.
    public var inviteControlsEnabled: Bool

    /// Show/hide the debug stats overlay toggle. Default: `false`.
    public var debugOverlayEnabled: Bool

    /// When `true` (default), the call controls bar fades out after a few
    /// seconds of idle time and a tap on the stage brings it back. When
    /// `false`, the controls are always visible and the idle timer never runs.
    public var autoHideControls: Bool

    /// When `true`, the call UI shows a circular shutter button anchored to
    /// the short edge of the current large preview (bottom in portrait,
    /// right in landscape). Tapping it captures the visible stream.
    /// Defaults to `false`.
    public var snapshotEnabled: Bool

    /// Optional resolver that returns an avatar for a remote participant's
    /// host-supplied `peerId` (passed to `SerenadaCore.join`). When unset or
    /// when `peerId` is absent on the participant, the call UI shows an
    /// initials placeholder derived from their display name.
    public var avatarProvider: AvatarProvider?

    /// When `true` (default), the call UI shows the video on/off and camera
    /// mode (flip) controls and the SDK requests camera permission on join.
    /// When `false`, both controls are hidden and — for URL-first call flows
    /// — the internally-created session is configured with no camera modes so
    /// the camera is never requested. For session-first usage, host apps that
    /// want a fully audio-only call should also pass `cameraModes: []` to the
    /// `SerenadaConfig` used to build the session.
    public var videoEnabled: Bool

    /// Selects the prebuilt visual presentation. Defaults to the existing
    /// standard call UI.
    public var uiVariant: SerenadaCallUiVariant

    /// Enables system Picture in Picture for active calls when the host app
    /// has the required iOS background modes/capabilities. iOS video-call PiP
    /// provides system return controls but does not allow custom in-window
    /// buttons such as End Call.
    public var systemPictureInPictureEnabled: Bool

    public init(
        screenSharingEnabled: Bool = true,
        inviteControlsEnabled: Bool = true,
        debugOverlayEnabled: Bool = false,
        autoHideControls: Bool = true,
        snapshotEnabled: Bool = false,
        avatarProvider: AvatarProvider? = nil,
        videoEnabled: Bool = true,
        uiVariant: SerenadaCallUiVariant = .standard,
        systemPictureInPictureEnabled: Bool = false
    ) {
        self.screenSharingEnabled = screenSharingEnabled
        self.inviteControlsEnabled = inviteControlsEnabled
        self.debugOverlayEnabled = debugOverlayEnabled
        self.autoHideControls = autoHideControls
        self.snapshotEnabled = snapshotEnabled
        self.avatarProvider = avatarProvider
        self.videoEnabled = videoEnabled
        self.uiVariant = uiVariant
        self.systemPictureInPictureEnabled = systemPictureInPictureEnabled
    }
}
