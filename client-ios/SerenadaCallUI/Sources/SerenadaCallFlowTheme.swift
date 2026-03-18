import SwiftUI

/// Theming configuration for SerenadaCallFlow.
public struct SerenadaCallFlowTheme {
    public var accentColor: Color
    public var backgroundColor: Color
    public var controlBarBackground: Material

    public init(
        accentColor: Color = .blue,
        backgroundColor: Color = .black,
        controlBarBackground: Material = .ultraThinMaterial
    ) {
        self.accentColor = accentColor
        self.backgroundColor = backgroundColor
        self.controlBarBackground = controlBarBackground
    }
}

// MARK: - View Modifier

private struct SerenadaThemeModifier: ViewModifier {
    let theme: SerenadaCallFlowTheme

    func body(content: Content) -> some View {
        content.environment(\.serenadaTheme, theme)
    }
}

extension View {
    /// Applies a custom theme to SerenadaCallFlow.
    public func serenadaTheme(_ theme: SerenadaCallFlowTheme) -> some View {
        modifier(SerenadaThemeModifier(theme: theme))
    }
}

// MARK: - Environment Key

private struct SerenadaThemeKey: EnvironmentKey {
    static let defaultValue = SerenadaCallFlowTheme()
}

extension EnvironmentValues {
    public var serenadaTheme: SerenadaCallFlowTheme {
        get { self[SerenadaThemeKey.self] }
        set { self[SerenadaThemeKey.self] = newValue }
    }
}
