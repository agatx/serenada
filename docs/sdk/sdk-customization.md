# Serenada SDK — Custom Signaling, Feature Toggles, String Overrides & Theming

## Custom Signaling Providers

Serenada can run against the built-in Serenada signaling server or an injected `SignalingProvider`. Choose exactly one signaling mode per `SerenadaConfig`:

- Built-in mode: `serverHost`
- Provider mode: `signalingProvider`

Provider mode is best for integrators who already have their own peer-message delivery and room membership system.

### Provider contract expectations

- `joined` is the required initial membership event.
- `roomStateUpdated` is optional. Incremental `peerJoined` / `peerLeft` is sufficient.
- `hostPeerId` is optional. UI and session layers tolerate it being absent.
- `peerId` is the provider-facing identifier. Built-in `cid` mapping stays internal.
- `getIceServers()` supplies the initial ICE server list.
- `iceServersChanged` rotates ICE servers for both current and future peer connections. Time-limited TURN credentials must be refreshed through this before they expire — see ICE-server sourcing and refresh.

### Reconnection ownership

`ProviderCapabilities.handlesReconnection` controls reconnect behavior:

- `false` (default): the session owns reconnect and re-calls `joinRoom()` with `reconnectPeerId`.
- `true`: the provider owns reconnect and should emit `disconnected` / `connected` around the interruption.

This flag does not transfer initial join-timeout ownership to the provider. The SDK still enforces the initial join hard-timeout on every platform; `handlesReconnection` only affects reconnect behavior after the join attempt is already in progress.

Only set `handlesReconnection = true` when your adapter already preserves identity and transport recovery semantics. The built-in `SerenadaServerProvider` does this on all platforms.

### ICE-server sourcing and refresh

Provider mode does not use Serenada's TURN token API. Your adapter owns the ICE credential lifecycle, including refresh:

- Return STUN/TURN configs from `getIceServers()` — the one-time fetch at join. Reject/throw on failure so the session retries.
- **Refresh time-limited credentials before they expire.** If your TURN credentials have a TTL, run your own timer and push fresh servers through the provider listener's `iceServersChanged` *before* the current ones lapse. A good default is to refresh at ~0.8 × TTL (the SDK uses the same ratio internally). The session applies the new servers to every current and future peer connection, so the next relay (re)allocation and any post-reconnect ICE restart use valid credentials.
- The built-in `SerenadaServerProvider` works exactly this way (it drives a `turn-refresh` loop and pushes `iceServersChanged`). The SDK does not refresh provider credentials for you: `getIceServers()` is called only once, at join, and only your adapter knows the credentials' real lifetime.

Why this matters: on relay-only networks (symmetric NAT, cross-LAN) a call that outlives its TURN credential TTL can no longer allocate a relay, so a mid-call ICE restart — for example after a network blip — fails and the call drops. Proactive `iceServersChanged` refresh keeps the relay path valid for the life of the call. See "Threading and actor guarantees" below for when and from which thread the listener may be called.

`runTurnProbe()` also uses provider ICE servers in provider mode, so the same source feeds both diagnostics and live calls.

### Threading and actor guarantees

- Web: provider callbacks run on the normal single-threaded JS event loop.
- Android: providers may invoke `listener` from any thread. The session marshals callbacks onto the main looper before mutating SDK state.
- iOS: providers may invoke `delegate` off-actor. The session re-enters `MainActor` before mutating SDK state.

Host apps must still call public Android/iOS SDK entrypoints on the main thread / `MainActor`.

### Provider-mode restrictions

Provider mode does not expose Serenada server helpers. These APIs require `serverHost`:

- `createRoom()` — returns `{ url, roomId }` only; call `join()` afterward to start the call
- Native `createRoomId()`
- `RoomWatcher`
- `validateServerHost()`
- `runConnectivityChecks()`

## Feature Toggles

`SerenadaCallFlowConfig` controls which optional UI elements appear in the call flow. When a feature is disabled, the corresponding control is removed from the UI entirely (not greyed out). The underlying core functionality remains available for core-only integrators.

### Fields

| Field | Type | Default | Effect |
|---|---|---|---|
| `uiVariant` | `SerenadaCallUiVariant` | `Standard` | Android and iOS. Selects the visual presentation for the prebuilt call UI. `Frontline` uses an audio-first layout optimized for large touch targets and field use, and keeps Frontline styling across lifecycle, 1:1, and multi-party states. |
| `screenSharingEnabled` | Bool | `true` | Show/hide the screen-share control when the current browser/device supports screen capture. On iOS the control is also hidden when the active session reports `isScreenShareAvailable == false`, such as `SerenadaConfig.screenShareMode == .disabled`. |
| `videoEnabled` | Bool | `true` | When `true`, the video on/off and camera-mode (flip) controls appear and the SDK requests camera permission on join. When `false`, both controls are hidden and URL-first call flows configure the internally-created session with no camera modes (camera is never requested). Session-first hosts that need strict audio-only media should pass `videoMediaEnabled: false` / `videoMediaEnabled = false` to `SerenadaConfig`. |
| `inviteControlsEnabled` | Bool | `true` | Show/hide the built-in QR code and share-link UI in the waiting screen |
| `debugOverlayEnabled` | Bool | `false` | Show/hide the in-call debug toggle and diagnostics panel |
| `autoHideControls` | Bool | `true` | When `true`, the call controls bar fades out after a few seconds of idle time and a tap on the stage brings it back. When `false`, the controls stay visible for the entire call and the idle timer never runs. |
| `systemPictureInPictureEnabled` | Bool | `false` | Android and iOS. Enables system Picture-in-Picture for waiting and active calls on supported mobile platforms. Android host apps must also set activity PiP manifest flags; iOS host apps must include the background audio mode. The SDK opts iOS capture sessions into multitasking camera access when supported, stops iOS system PiP as the app returns to the foreground, and closes Android PiP by finishing the activity if the call ends while still in PiP. Custom PiP actions are not used, so call controls remain available after returning to the app. |

### iOS

```swift
SerenadaCallFlow(
    url: url,
    config: .init(
        screenSharingEnabled: false,
        inviteControlsEnabled: false,
        debugOverlayEnabled: true,
        autoHideControls: false,
        videoEnabled: false,
        uiVariant: .frontline,
        systemPictureInPictureEnabled: true
    ),
    onDismiss: { dismiss() }
)
```

### Android

```kotlin
SerenadaCallFlow(
    url = url,
    config = SerenadaCallFlowConfig(
        uiVariant = SerenadaCallUiVariant.Frontline,
        screenSharingEnabled = false,
        inviteControlsEnabled = false,
        debugOverlayEnabled = true,
        autoHideControls = false,
        videoEnabled = false,
        systemPictureInPictureEnabled = true,
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
        autoHideControls: false,
        videoEnabled: false,
    }}
    onDismiss={() => navigate('/')}
/>
```

`inviteControlsEnabled` only hides the built-in invite UI. Any custom `waitingActions` still render.

### Android and iOS Frontline Variant

`SerenadaCallUiVariant.Frontline` / `.frontline` is an opt-in native call UI for frontline workflows. It keeps the same SDK/session contract as the standard UI, but changes the presentation to an audio-first screen with larger controls, local-camera preview actions, multi-party Frontline tiles, Frontline lifecycle states, and a More sheet for supported secondary actions.

For URL-first calls, the call flow configures the internally-created session as audio-first and world-camera-first:

```kotlin
SerenadaCallFlow(
    url = url,
    config = SerenadaCallFlowConfig(
        uiVariant = SerenadaCallUiVariant.Frontline,
        snapshotEnabled = true,
    ),
)
```

```swift
SerenadaCallFlow(
    url: url,
    config: SerenadaCallFlowConfig(
        snapshotEnabled: true,
        uiVariant: .frontline
    )
)
```

For session-first calls, configure the session the same way before passing it to the call UI:

```kotlin
val serenada = SerenadaCore(
    config = SerenadaConfig(
        serverHost = "serenada.app",
        defaultVideoEnabled = false,
        cameraModes = listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE, LocalCameraMode.COMPOSITE),
    ),
    context = context,
)

SerenadaCallFlow(
    session = serenada.join(url),
    config = SerenadaCallFlowConfig(uiVariant = SerenadaCallUiVariant.Frontline),
)
```

```swift
let serenada = SerenadaCore(
    config: SerenadaConfig(
        serverHost: "serenada.app",
        defaultVideoEnabled: false,
        cameraModes: [.world, .selfie, .composite]
    )
)

SerenadaCallFlow(
    session: serenada.join(url: url),
    config: SerenadaCallFlowConfig(uiVariant: .frontline)
)
```

Frontline v1 shows the current audio route as the first More sheet item. Android and iOS open a simple checkmarked route picker backed by `availableAudioDevices`, `currentAudioDevice`, and `selectAudioDevice(...)`. On iOS, the built-in Phone route is hidden while Bluetooth audio is present because iOS cannot reliably expose Phone as an app-selectable communication route in that state. Built-in earpiece routes are labeled as "Phone"; named external routes such as Bluetooth devices use the device name when the coordinator provides one. Controls that are not backed by the SDK call UI contract today, including report-quality and placeholder add-person flows, remain hidden. Screen sharing, invite/share actions, mute, video, camera mode, flashlight, snapshot, PiP swap, end call, debug overlay, reconnect badge, and local pinch zoom use existing SDK callbacks. The standard waiting-screen QR code is not shown in the Frontline variant.

## Camera Modes

`SerenadaConfig.cameraModes` is a core-level setting that restricts which camera modes (`selfie`, `world`, `composite`) are available and in what order. It controls camera capture only; set `videoMediaEnabled` to `false` for strict audio-only calls where the SDK must not negotiate or receive video media. It affects the call UI in three ways:

- **Initial mode**: the first supported entry of the list is used when media starts.
- **Flip-camera control**: hidden when only one mode is configured (nothing to cycle to). Also hidden while the local video is turned off.
- **Video toggle & camera permission**: when the list is empty, the video toggle is hidden and the camera is never requested. Remote video and screen sharing can still work unless `videoMediaEnabled` is `false`. When camera modes are present but `defaultVideoEnabled` is `false`, the call joins with video off and requests camera access only if the user enables video.

Platform-unsupported modes are dropped silently (`composite` on web; `composite` on devices without multi-camera support on iOS / Android). If a native camera source still fails at runtime, startup retries the remaining configured modes before continuing audio-only. `screenShare` is rejected — screen sharing is controlled separately.

| Value | Effect |
|---|---|
| `[selfie, world, composite]` (default) | All supported camera modes available, start in selfie. |
| `[world, selfie]` | Start in world (rear) camera; flip toggles between world and selfie. |
| `[selfie]` | Selfie only — flip-camera control hidden. |
| `[]` | Camera capture disabled — video toggle and camera controls hidden; screen sharing and remote video remain available unless `videoMediaEnabled` is `false`. |

For strict audio-only calls, such as PSTN bridge flows, set `videoMediaEnabled` to `false`. That disables camera capture, screen sharing, video transceivers, and remote video across web, Android, and iOS. If the provider may delay the first answer while waiting for a remote action such as human pickup, set `deferInitialAnswer` to `true`; the host peer keeps the initial offer alive and suppresses offer-timeout/ICE-restart churn until the first answer is applied.

### iOS

```swift
let config = SerenadaConfig(
    serverHost: "serenada.app",
    cameraModes: [.world, .selfie]       // start in world, cycle → selfie → world
)
```

### Android

```kotlin
val config = SerenadaConfig(
    serverHost = "serenada.app",
    cameraModes = listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE),
)
```

### Web

```typescript
const serenada = createSerenadaCore({
    serverHost: 'serenada.app',
    cameraModes: ['world', 'selfie'],
})
```

The resolved (platform-filtered) list is echoed back on `CallState.localParticipant.availableCameraModes` (web) / `state.localParticipant.availableCameraModes` (iOS) / `CallState.availableCameraModes` (Android). Call UIs should consult that list — not the configured one — when deciding whether to render flip / video-toggle controls.

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
- `CallQrCode`, `CallToggleFlashlight`, `CallToggleVideoFit`, `CallTakeSnapshot`
- Native Frontline: `FrontlineYou` / `frontlineYou`, `FrontlineWaiting` / `frontlineWaiting`, `FrontlineVideo` / `frontlineVideo`, `FrontlineVideoOn` / `frontlineVideoOn`, `FrontlineMute` / `frontlineMute`, `FrontlineMore` / `frontlineMore`, `FrontlineEnd` / `frontlineEnd`, `FrontlineFlipCamera` / `frontlineFlipCamera`
- Native Frontline: `FrontlineStopScreenShare` / `frontlineStopScreenShare`, `FrontlineShareScreen` / `frontlineShareScreen`, `FrontlineClose` / `frontlineClose`
- Native Call audio routes: `CallAudioRoute` / `callAudioRoute`, `CallAudioSpeaker` / `callAudioSpeaker`, `CallAudioPhone` / `callAudioPhone`, `CallAudioHeadset` / `callAudioHeadset`, `CallAudioBluetooth` / `callAudioBluetooth`, `CallAudioCar` / `callAudioCar`, `CallAudioUsb` / `callAudioUsb`, `CallAudioUnknown` / `callAudioUnknown`

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

## End-call handling

`SerenadaCallFlow` leaves the active session directly when the user taps End Call
unless you provide `onEndCall`. Use `onEndCall` when the host app needs to route
the button through app-owned cleanup, foreground-service teardown, analytics, or
navigation. When provided, the callback owns calling `session.leave()` and
releasing any host-owned session resources.

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
