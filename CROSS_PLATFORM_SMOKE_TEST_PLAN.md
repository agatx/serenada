# Cross-Platform Smoke Test Plan

## Context

There is currently no automated way to validate the full Serenada call flow across all three client platforms (Web, Android, iOS) in a single run. The iOS client has a UI test (`DeepLinkRejoinFlowUITests`) but it runs in isolation. The Android client has no UI tests at all. There are no cross-platform integration tests.

This plan creates a single `tools/smoke-test/smoke-test.sh` script that a developer runs with an Android phone and iPhone plugged in. It orchestrates sequential test pairs (Web+Android, Web+iOS) where each pair joins the same room, verifies peer connection, leaves, and rejoins — proving the entire stack works end-to-end across platforms.

---

## File Structure

New files under `tools/smoke-test/`:

```
tools/smoke-test/
  smoke-test.sh              # Main orchestrator
  lib/
    common.sh                # Logging, barrier helpers, LAN IP detection
    server.sh                # Docker start/stop/health-check
    room.sh                  # Room creation via /api/room-id
    device-detect.sh         # Android (adb) and iOS (xcrun devicectl) detection
    report.sh                # Per-pair results and summary output
  web/
    package.json             # Playwright dependency
    playwright.config.ts     # Chrome + fake media flags
    smoke.spec.ts            # Join, verify peer, leave, rejoin
    hold-room.spec.ts        # Join and hold room open (for iOS pairs)
  android/
    smoke-android.sh         # ADB deep link + uiautomator state polling
  ios/
    smoke-ios.sh             # xcodebuild wrapper targeting existing UITest
```

Modified existing files (minimal — just adding testTags):

```
client-android/.../ui/CallScreen.kt     # 3 small changes
client-android/.../ui/JoinScreen.kt     # 4 small changes
```

---

## 1. Android testTag Additions

Android has no Compose `testTag` modifiers. Add them to match the iOS accessibility identifiers exactly — useful beyond just the smoke test.

### `client-android/.../ui/CallScreen.kt`

**a)** Add import: `import androidx.compose.ui.platform.testTag`

**b)** Root container (line 283) — add `.testTag("call.screen")`:
```kotlin
BoxWithConstraints(
    modifier = Modifier.fillMaxSize().background(Color.Black)
        .testTag("call.screen")   // <-- ADD
        .clickable(...) { areControlsVisible = !areControlsVisible }
)
```

**c)** Add `modifier` parameter to `ControlButton` (line 1098):
```kotlin
private fun ControlButton(
    onClick: () -> Unit,
    icon: ImageVector,
    backgroundColor: Color,
    modifier: Modifier = Modifier,   // <-- ADD
    buttonSize: Dp = 56.dp,
    iconSize: Dp = 28.dp
) {
    Surface(
        modifier = modifier.size(buttonSize).clip(CircleShape).clickable { onClick() },
        // ...
```

**d)** End call button invocation (line 691) — pass testTag:
```kotlin
ControlButton(
    onClick = onEndCall,
    icon = Icons.Default.CallEnd,
    backgroundColor = Color.Red,
    modifier = Modifier.testTag("call.endCall")   // <-- ADD
)
```

### `client-android/.../ui/JoinScreen.kt`

**a)** Add import: `import androidx.compose.ui.platform.testTag`

**b)** Root Scaffold (line 134) — add testTag:
```kotlin
Scaffold(
    modifier = Modifier.testTag("join.screen"),   // <-- ADD
    topBar = { ... }
)
```

**c)** Busy overlay Box (line 315) — add testTag:
```kotlin
if (showBusyOverlay) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("join.busyOverlay")   // <-- ADD
            .background(...)
```

**d)** Recent call row — add testTag to the `Column` wrapper at the call site (line 420):
```kotlin
Column(modifier = Modifier.fillMaxWidth()
    .testTag("join.recentCall.${call.roomId}")   // <-- ADD
) {
    RecentCallRow(...)
```

These tags mirror iOS identifiers: `call.screen`, `call.endCall`, `join.screen`, `join.busyOverlay`, `join.recentCall.{roomId}`.

---

## 2. Orchestrator Script (`smoke-test.sh`)

### Configuration (env vars)

| Variable | Default | Description |
|---|---|---|
| `SMOKE_SERVER` | _(empty = local Docker)_ | Server URL override |
| `SMOKE_PAIRS` | `web+android,web+ios` | Comma-separated test pairs |
| `SMOKE_TIMEOUT` | `120` | Max seconds per pair |
| `SMOKE_ARTIFACTS_DIR` | `tools/smoke-test/artifacts` | Screenshots and logs |
| `SMOKE_SKIP_BUILD` | `0` | Skip platform builds |
| `SMOKE_KEEP_SERVER` | `0` | Don't stop Docker on exit |

### Execution flow

```
1. Source .env from repo root
2. Detect devices (adb devices, xcrun devicectl list devices)
   - Skip pairs whose device is missing (warn, don't fail)
3. Server setup:
   - If SMOKE_SERVER set: health-check via POST /api/room-id
   - If not: build web client, docker compose up -d --build, wait for health
4. Resolve platform URLs (web → localhost, mobile → Mac LAN IP via ipconfig getifaddr)
5. Install Playwright: cd tools/smoke-test/web && npm install
6. For each pair in SMOKE_PAIRS:
   a. Create room: POST $SERVER/api/room-id → ROOM_ID
   b. Create barrier dir: mktemp -d
   c. If pair involves iOS → run iOS-specific flow (section 5)
   d. Else → run standard barrier-synchronized flow (section 4)
   e. Record result + timing
7. Print summary report
8. Cleanup: kill processes, remove barriers, optionally stop Docker
```

### LAN IP resolution for mobile devices

When server is `http://localhost`, mobile devices can't reach it. The orchestrator detects the Mac's LAN IP (`ipconfig getifaddr en0`) and uses `http://<LAN_IP>` for Android/iOS deep links. The `.env` `ALLOWED_ORIGINS` gets the LAN origin appended before starting Docker.

---

## 3. Web Test Leg (Playwright)

### `tools/smoke-test/web/playwright.config.ts`

- Browser: Chromium
- Args: `--use-fake-device-for-media-stream`, `--use-fake-ui-for-media-stream`
- Permissions: `['camera', 'microphone']`
- Timeout: 120s
- Screenshots on failure, video retained on failure

### `tools/smoke-test/web/smoke.spec.ts`

Env vars received: `SMOKE_SERVER_URL`, `SMOKE_ROOM_ID`, `SMOKE_BARRIER_DIR`, `SMOKE_ROLE`

Flow:
1. Navigate to `$SERVER/call/$ROOM_ID`
2. Click `button.btn-primary` (Join Call)
3. Write barrier `web.joined`
4. Wait for barrier `peer.ready` (other platform joined)
5. Wait for `.waiting-message` to become hidden (remote stream arrived)
6. Write barrier `web.in-call`
7. Wait for barrier `leave`
8. Click `button.btn-leave` (End Call)
9. Expect URL to be `/` (navigated home)
10. Write barrier `web.left`
11. Wait for barrier `rejoin`
12. Navigate to `$SERVER/call/$REJOIN_ROOM_ID`
13. Click Join, write `web.rejoined`
14. Wait for `peer.ready.2`, wait for `.waiting-message` hidden
15. Write `web.rejoin-in-call`
16. Wait for `end`, click leave, write `web.done`

Key selectors (verified from `CallRoom.tsx`):
- `button.btn-primary` — Join button (line 785)
- `.waiting-message` — waiting overlay (line 878)
- `button.btn-leave` — end call (line 983)
- `.call-container` — call screen root (line 818)

---

## 4. Android Test Leg (`smoke-android.sh`)

ADB-based script. No instrumented test APK needed.

### Helper functions

- `wait_for_element(tag, timeout)`: Loops `adb shell uiautomator dump`, greps XML for the testTag string
- `tap_element(tag)`: Dumps UI, parses `bounds="[x1,y1][x2,y2]"` for the node containing the tag, computes center, runs `adb shell input tap X Y`
- `take_screenshot(name)`: `adb shell screencap` + `adb pull`

### Flow

1. Pre-grant permissions: `adb shell pm grant app.serenada.android android.permission.CAMERA` and `RECORD_AUDIO`
2. Launch via deep link: `adb shell am start -a android.intent.action.VIEW -d "$URL/call/$ROOM_ID"`
3. `wait_for_element "call.screen" 30` → write barrier `android.joined`
4. Wait for barrier `peer.ready`
5. Brief stabilization (3s) → write `android.in-call`
6. Wait for barrier `leave`
7. `tap_element "call.endCall"`
8. `wait_for_element "join.screen" 20` → write `android.left`
9. Wait for barrier `rejoin`
10. Launch deep link with `$REJOIN_ROOM_ID`
11. `wait_for_element "call.screen" 30` → write `android.rejoined`
12. Wait for `peer.ready.2`, stabilize → write `android.rejoin-in-call`
13. Wait for `end`, tap end call → write `android.done`

---

## 5. iOS Test Leg (`smoke-ios.sh`)

### Strategy

The iOS test reuses the existing `DeepLinkRejoinFlowUITests.swift` which already implements the full join/leave/rejoin-from-recents cycle. The orchestrator passes the room ID via `SERENADA_UI_TEST_REJOIN_DEEPLINK` env var.

**Synchronization approach**: Since XCUITest runs autonomously (we can't inject barrier signals mid-test), the partner client (Web) joins first and stays in the room. When iOS joins, both connect. When iOS leaves, the Web partner remains in-room (the Web client stays on the call screen and shows `.waiting-message` again when the remote peer disconnects — verified in `CallRoom.tsx` lines 462-475). When iOS rejoins from recents, they reconnect. The Web partner just needs to stay alive for the duration.

### Flow

1. Auto-detect device UDID via `xcrun devicectl list devices`
2. `cd client-ios && xcodegen generate`
3. Run XCUITest on the physical device:
   ```bash
   SERENADA_UI_TEST_REJOIN_DEEPLINK="$URL/call/$ROOM_ID" \
   xcodebuild \
     -project SerenadaiOS.xcodeproj \
     -scheme SerenadaiOS \
     -destination "id=$UDID" \
     -only-testing:SerenadaiOSUITests/DeepLinkRejoinFlowUITests \
     -resultBundlePath "$ARTIFACTS_DIR/ios-smoke.xcresult" \
     -allowProvisioningUpdates \
     test
   ```
4. Pass/fail based on xcodebuild exit code

### iOS pair orchestration in `smoke-test.sh`

```
1. Create room
2. Launch hold-room.spec.ts (Playwright joins room, stays indefinitely)
3. Launch smoke-ios.sh (runs XCUITest: joins, leaves, rejoins × 2)
4. Wait for xcodebuild to complete
5. Kill web holder
6. Record pass/fail
```

This requires a second Playwright spec: `web/hold-room.spec.ts` — joins the room and waits until the process is killed.

---

## 6. Barrier Synchronization (standard pairs)

For Web+Android pairs, both legs communicate via the filesystem:

- Orchestrator creates `BARRIER_DIR=$(mktemp -d)`
- Test legs write markers: `touch $BARRIER_DIR/web.joined`
- Test legs poll for signals: `while [ ! -f $BARRIER_DIR/leave ]; do sleep 0.5; done`
- Playwright accesses barriers via Node.js `fs` module
- Android leg uses shell `[ -f ... ]` polling

Orchestrator coordination for a standard pair:
```
1. Start client A (background)
2. Wait for A.joined barrier (30s)
3. Start client B (background)
4. Wait for B.joined barrier (30s)
5. Write peer.ready barrier
6. Wait for A.in-call + B.in-call (45s)
7. Write leave barrier
8. Wait for A.left + B.left (20s)
9. Create new room for rejoin
10. Write rejoin barrier (with new room ID in file content)
11. Wait for A.rejoined + B.rejoined (30s)
12. Write peer.ready.2 barrier
13. Wait for A.rejoin-in-call + B.rejoin-in-call (45s)
14. Write end barrier
15. Wait for both processes to exit
```

---

## 7. Report Output

```
==========================================
 Smoke Test Results
==========================================
  web+android       PASS  (47s)
  web+ios           PASS  (83s)
==========================================
 Overall: PASS
```

On failure: screenshots saved to `$SMOKE_ARTIFACTS_DIR/`, failure reason logged.

---

## 8. Implementation Sequence

1. Add Android testTags (CallScreen.kt, JoinScreen.kt)
2. Create `tools/smoke-test/lib/` — common.sh, server.sh, room.sh, device-detect.sh, report.sh
3. Create `tools/smoke-test/web/` — package.json, playwright.config.ts, smoke.spec.ts, hold-room.spec.ts
4. Create `tools/smoke-test/android/smoke-android.sh`
5. Create `tools/smoke-test/ios/smoke-ios.sh`
6. Create `tools/smoke-test/smoke-test.sh` (main orchestrator)
7. Update CLAUDE.md with smoke test commands

---

## 9. Verification

1. **Android testTags**: Build debug APK, install, open app, run `adb shell uiautomator dump /sdcard/dump.xml && adb pull /sdcard/dump.xml`, grep for `call.screen` and `join.screen`
2. **Web standalone**: `cd tools/smoke-test/web && npm install && SMOKE_SERVER_URL=http://localhost SMOKE_ROOM_ID=test SMOKE_BARRIER_DIR=/tmp/test-barrier npx playwright test` — verify selectors work
3. **iOS standalone**: Run existing UITest against production: `cd client-ios && SERENADA_UI_TEST_REJOIN_DEEPLINK='https://serenada.app/call/TOKEN' xcodebuild -destination "id=$UDID" -only-testing:SerenadaiOSUITests/DeepLinkRejoinFlowUITests test`
4. **Full end-to-end**: `bash tools/smoke-test/smoke-test.sh` with both devices plugged in
5. **Remote mode**: `SMOKE_SERVER=https://serenada.app bash tools/smoke-test/smoke-test.sh`
