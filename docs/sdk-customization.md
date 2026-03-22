# Serenada SDK — Feature Toggles, String Overrides & Theming

## Feature Toggles

`SerenadaCallFlowConfig` controls which optional UI elements appear in the call flow. When a feature is disabled, the corresponding control is removed from the UI entirely (not greyed out). The underlying core functionality remains available for core-only integrators.

### Fields

| Field | Type | Default | Effect |
|---|---|---|---|
| `screenSharingEnabled` | Bool | `true` | Show/hide the screen-share control when the current browser/device supports screen capture |
| `inviteControlsEnabled` | Bool | `true` | Show/hide the built-in QR code and share-link UI in the waiting screen |
| `debugOverlayEnabled` | Bool | `false` | Show/hide the in-call debug toggle and diagnostics panel |

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

`inviteControlsEnabled` only hides the built-in invite UI. Any custom `waitingActions` still render.

## Web Waiting Actions

Use `waitingActions` for host-app-specific actions that should appear under the default waiting UI:

```tsx
<SerenadaCallFlow
    url={url}
    waitingActions={
        <button type="button" onClick={notifyInvitees}>
            Notify invitees
        </button>
    }
    onDismiss={() => navigate('/')}
/>
```

---

## Logging

The SDK ships silent by default. Enable logging by providing a `SerenadaLogger` implementation. Built-in convenience loggers are provided for each platform:

| Platform | Built-in Logger | Output |
|----------|----------------|--------|
| iOS | `PrintSerenadaLogger` | `print()` to stdout |
| Android | `AndroidSerenadaLogger` | `android.util.Log` |
| Web | `ConsoleSerenadaLogger` | `console.debug/info/warn/error` |

### Custom Logger

Implement `SerenadaLogger` to route SDK logs to your own system (Crashlytics, os_log, Timber, Sentry, etc.):

| iOS | `SerenadaLogger` protocol — `func log(_ level: SerenadaLogLevel, tag: String, _ message: String)` |
|---|---|
| **Android** | `SerenadaLogger` interface — `fun log(level: SerenadaLogLevel, tag: String, message: String)` |
| **Web** | `SerenadaLogger` interface — `log(level: SerenadaLogLevel, tag: string, message: string): void` |

### Log Tags

Tags are consistent across all three platforms:

| Tag | Components |
|-----|-----------|
| `Session` | SerenadaSession |
| `Signaling` | SignalingClient / SignalingEngine |
| `Transport` | WS/SSE transports |
| `WebRTC` | WebRtcEngine / MediaEngine |
| `PeerConnection` | PeerConnectionSlot |
| `Negotiation` | PeerNegotiationEngine |
| `Audio` | CallAudioSessionController |
| `Camera` | CameraCaptureController, CompositeCameraCapturer |
| `ScreenShare` | ScreenShareController |
| `Stats` | CallStatsCollector |

### Log Levels

| Level | iOS | Android | Web |
|-------|-----|---------|-----|
| Debug | `.debug` | `DEBUG` | `'debug'` |
| Info | `.info` | `INFO` | `'info'` |
| Warning | `.warning` | `WARNING` | `'warning'` |
| Error | `.error` | `ERROR` | `'error'` |

See each platform's quick-start guide for setup examples.

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

Only the exported `SerenadaString` keys are overridable. Other small utility labels in the current web debug/zoom UI are not yet part of the string override surface.

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
        backgroundColor: '#1a1a2e',
    }}
    onDismiss={() => navigate('/')}
/>
```

`SerenadaCallFlowTheme` fields:

| Field | Type | Default |
|---|---|---|
| `accentColor` | `string` (CSS color) | `#3b82f6` |
| `backgroundColor` | `string` (CSS color) | `#000` |

On web, `backgroundColor` is applied to the root call-flow container. `accentColor` styles primary action accents such as loading spinners, primary buttons, and invite/zoom affordances while preserving the default Serenada in-call control chrome.
