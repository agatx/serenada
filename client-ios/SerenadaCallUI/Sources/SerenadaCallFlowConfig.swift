import Foundation

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

    /// Optional resolver that returns an avatar for a remote participant's
    /// host-supplied `peerId` (passed to `SerenadaCore.join`). When unset or
    /// when `peerId` is absent on the participant, the call UI shows an
    /// initials placeholder derived from their display name.
    public var avatarProvider: AvatarProvider?

    public init(
        screenSharingEnabled: Bool = true,
        inviteControlsEnabled: Bool = true,
        debugOverlayEnabled: Bool = false,
        autoHideControls: Bool = true,
        avatarProvider: AvatarProvider? = nil
    ) {
        self.screenSharingEnabled = screenSharingEnabled
        self.inviteControlsEnabled = inviteControlsEnabled
        self.debugOverlayEnabled = debugOverlayEnabled
        self.autoHideControls = autoHideControls
        self.avatarProvider = avatarProvider
    }
}
