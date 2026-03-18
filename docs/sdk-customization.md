# Serenada SDK — Feature Toggles, String Overrides & Theming

## Feature Toggles

`SerenadaCallFlowConfig` controls which optional UI elements appear in the call flow. When a feature is disabled, the corresponding control is removed from the UI entirely (not greyed out). The underlying core functionality remains available for core-only integrators.

### Fields

| Field | Type | Default | Effect |
|---|---|---|---|
| `screenSharingEnabled` | Bool | `true` | Show/hide screen share button in the control bar |
| `inviteControlsEnabled` | Bool | `true` | Show/hide QR code and invite/share buttons |
| `debugOverlayEnabled` | Bool | `false` | Show/hide the debug stats overlay toggle |

### iOS

```swift
SerenadaCallFlow(
    url: url,
    config: .init(
        screenSharingEnabled: false,
        inviteControlsEnabled: false,
        debugOverlayEnabled: true
    ),
    onDismiss: { dismiss() }
)
```

### Android

```kotlin
SerenadaCallFlow(
    url = url,
    config = SerenadaCallFlowConfig(
        screenSharingEnabled = false,
        inviteControlsEnabled = false,
        debugOverlayEnabled = true
    ),
    onDismiss = { navController.popBackStack() }
)
```

### Web

```tsx
<SerenadaCallFlow
    url={url}
    config={{
        screenSharingEnabled: false,
        inviteControlsEnabled: false,
        debugOverlayEnabled: true,
    }}
    onDismiss={() => navigate('/')}
/>
```

---

## String Overrides

Call-UI bundles English strings as the default. Host apps can override any string to provide localization or custom copy. Any string not overridden falls back to the bundled English default.

### iOS

String keys are defined by the `SerenadaString` enum:

```swift
SerenadaCallFlow(
    url: url,
    strings: [
        .callWaitingOverlay: "Ожидание другого участника...",
        .callReconnecting: "Переподключение...",
        .callA11yEndCall: "Завершить звонок",
        .callEnded: "Звонок завершён"
    ],
    onDismiss: { dismiss() }
)
```

Available string keys (see `SerenadaString` enum for full list):
- `callLocalCameraOff`, `callCameraOff`, `callVideoOff`
- `callReconnecting`, `callTakingLongerThanUsual`
- `callWaitingOverlay`
- `callInviteToRoom`, `callInviteSent`, `callInviteFailed`
- `callShareInvitation`, `callQrCode`
- `callA11yMuteOn`, `callA11yMuteOff`, `callA11yVideoOn`, `callA11yVideoOff`
- `callA11yFlipCamera`, `callA11yScreenShareOn`, `callA11yScreenShareOff`
- `callA11yEndCall`, `callA11yFlashlightOn`, `callA11yFlashlightOff`
- `callA11yShareInvite`, `callA11yVideoFit`, `callA11yVideoFill`
- `callErrorGeneric`, `callJoining`, `callEnded`
- `callPermissionsRequired`, `callPermissionsCamera`, `callPermissionsMicrophone`

### Android

String keys are defined by the `SerenadaString` enum:

```kotlin
SerenadaCallFlow(
    url = url,
    strings = mapOf(
        SerenadaString.CallWaitingOverlay to "Ожидание другого участника...",
        SerenadaString.CallReconnecting to "Переподключение...",
    ),
    onDismiss = { navController.popBackStack() }
)
```

Available string keys:
- `CallLocalCameraOff`, `CallCameraOff`, `CallVideoOff`
- `CallWaitingShort`, `CallReconnecting`, `CallTakingLongerThanUsual`
- `CallWaitingOverlay`
- `CallShareLinkChooser`, `CallShareInvitation`, `CallInviteToRoom`
- `CallQrCode`, `CallToggleFlashlight`, `CallToggleVideoFit`

### Web

String keys are TypeScript string literals:

```tsx
<SerenadaCallFlow
    url={url}
    strings={{
        waitingForOther: 'En attente de l\'autre participant...',
        reconnecting: 'Reconnexion...',
        endCall: 'Raccrocher',
        callEnded: 'Appel terminé',
    }}
    onDismiss={() => navigate('/')}
/>
```

Available string keys:
- `joiningCall`, `waitingForOther`, `shareLink`, `copied`
- `endCall`, `muteAudio`, `unmuteAudio`
- `enableVideo`, `disableVideo`, `flipCamera`
- `startScreenShare`, `stopScreenShare`
- `reconnecting`, `callEnded`, `errorOccurred`
- `permissionRequired`, `permissionCamera`, `permissionMicrophone`
- `permissionPrompt`, `grantPermissions`, `cancel`
- `debugPanel`, `you`, `remote`

---

## Theming

Each platform provides a theme object to customize the call UI's visual appearance.

### iOS

Use the `.serenadaTheme()` view modifier:

```swift
SerenadaCallFlow(url: url, onDismiss: { dismiss() })
    .serenadaTheme(.init(
        accentColor: .purple,
        backgroundColor: Color(hex: "#1a1a2e"),
        controlBarBackground: .thinMaterial
    ))
```

`SerenadaCallFlowTheme` fields:

| Field | Type | Default |
|---|---|---|
| `accentColor` | `Color` | `.blue` |
| `backgroundColor` | `Color` | `.black` |
| `controlBarBackground` | `Material` | `.ultraThinMaterial` |

The theme propagates via SwiftUI's environment system. Custom views inside the hierarchy can access it with `@Environment(\.serenadaTheme)`.

### Android

Pass a `SerenadaCallFlowTheme` to the composable:

```kotlin
SerenadaCallFlow(
    url = url,
    theme = SerenadaCallFlowTheme(
        accentColor = Color(0xFF9C27B0),
        backgroundColor = Color(0xFF1A1A2E)
    ),
    onDismiss = { navController.popBackStack() }
)
```

`SerenadaCallFlowTheme` fields:

| Field | Type | Default |
|---|---|---|
| `accentColor` | `Color` | `Color(0xFF2F81F7)` |
| `backgroundColor` | `Color` | `Color(0xFF0D1117)` |

The call UI wraps content in `SerenadaTheme` which provides a `MaterialTheme` with a dark color scheme derived from these values.

### Web

Pass a `theme` prop:

```tsx
<SerenadaCallFlow
    url={url}
    theme={{
        accentColor: '#9c27b0',
        backgroundColor: '#1a1a2e',
    }}
    onDismiss={() => navigate('/')}
/>
```

`SerenadaCallFlowTheme` fields:

| Field | Type | Default |
|---|---|---|
| `accentColor` | `string` (CSS color) | platform default |
| `backgroundColor` | `string` (CSS color) | platform default |

Theme values are applied as CSS custom properties throughout the call flow components.
