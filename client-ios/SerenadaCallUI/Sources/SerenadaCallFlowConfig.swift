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

    public init(
        screenSharingEnabled: Bool = true,
        inviteControlsEnabled: Bool = true,
        debugOverlayEnabled: Bool = false
    ) {
        self.screenSharingEnabled = screenSharingEnabled
        self.inviteControlsEnabled = inviteControlsEnabled
        self.debugOverlayEnabled = debugOverlayEnabled
    }
}
