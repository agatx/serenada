# Serenada Android Client

Native Android (Kotlin) client for Serenada 1:1 WebRTC calls. This app mirrors the core call flow of the web client and prefers WebSocket signaling with automatic SSE fallback.

## Features
- 1:1 WebRTC audio/video calls
- WebSocket signaling with automatic SSE fallback (protocol v1)
- In-call camera source cycle: `selfie` (default) -> `world` -> `composite` (world view with circular selfie overlay), with automatic composite skip on unsupported devices or composite start failure
- In-call flashlight toggle shown in the top-right corner only for `world`/`composite` camera modes when the device reports flash support; flashlight turns off automatically when leaving those modes or ending the call, while the user’s flashlight preference is remembered during the same call and reapplied after returning to `world`/`composite`
- In-call performance locks (partial CPU wake lock + Wi-Fi low-latency lock) to reduce call-time scheduling/network jitter while the call is active
- Call-scoped audio session management (`MODE_IN_COMMUNICATION` + audio focus request / restore on hangup), with route priority `Bluetooth headset -> proximity earpiece -> speaker` during active calls
- Proximity sensor integration for call ergonomics: when the phone is against the ear, audio switches to earpiece and local camera video is paused until the phone is moved away (Bluetooth headset route takes precedence)
- WebRTC audio path configured with `JavaAudioDeviceModule` (`VOICE_COMMUNICATION`, hardware AEC/NS, low-latency path)
- Recent calls on home (max 3, deduped) with live room occupancy status and long-press remove
- Deep links for `https://serenada.app/call/*`
- Foreground service to keep active calls running in the background
- Settings screen to change server host, with host validation on save
- Encrypted join snapshot upload (`snapshotId` on `join`) so server push notifications can include a thumbnail when Android is the joiner
- Native push receive via Firebase Cloud Messaging, including encrypted snapshot decryption and `BigPicture` notifications in background/terminated app states

## Not included (current build)
- Multi-party calls

## Requirements
- Android Studio (Giraffe+ recommended)
- JDK 17
- Android SDK 34
- minSdk 26

## Project layout
- `app/` — Android app module
- `keystore/` — Release keystore + properties (ignored by git)

## Run (debug)
1. Open `client-android/` in Android Studio.
2. Sync Gradle.
3. Run on a device or emulator.

## Build (CLI)
Debug APK:
```bash
cd client-android
./gradlew :app:assembleDebug
```

Force SSE-only signaling in debug build (test mode):
```bash
cd client-android
./gradlew :app:assembleDebug -PforceSseSignaling=true
```

WebRTC provider A/B build (for performance comparison):
```bash
cd client-android
./gradlew :app:assembleDebug -PwebrtcProvider=local7559 # default (app/libs/libwebrtc-7559_173-arm64.aar)
./gradlew :app:assembleDebug -PwebrtcProvider=stream    # alternative
./gradlew :app:assembleDebug -PwebrtcProvider=dafruits  # legacy default
./gradlew :app:assembleDebug -PwebrtcProvider=webrtcsdk # Chromium-closer branch build
```

Rebuild the local WebRTC AAR on a Linux VPS:
```bash
cd /path/to/connected
bash tools/build_libwebrtc_android_7559.sh
```
The script outputs:
`/opt/webrtc-build/artifacts/libwebrtc-7559_173-arm64-curlroots.aar`

Release APK (signed):
```bash
cd client-android
./gradlew :app:assembleRelease
```

Release output:
```
app/build/outputs/apk/release/app-release.apk
```

### Firebase push configuration
Native push receive requires these Gradle properties at build time:
- `firebaseAppId`
- `firebaseApiKey`
- `firebaseProjectId`
- `firebaseSenderId`

Example:
```bash
cd client-android
./gradlew :app:assembleDebug \
  -PfirebaseAppId=1:1234567890:android:abc123 \
  -PfirebaseApiKey=AIza... \
  -PfirebaseProjectId=your-project-id \
  -PfirebaseSenderId=1234567890
```

## Install on a physical device
Enable USB debugging on the device and connect it. Then run:

Debug:
```bash
adb install -r app/build/outputs/apk/debug/serenada-debug.apk
```

Release:
```bash
adb install -r app/build/outputs/apk/release/serenada.apk
```

## Release signing
Release signing reads `keystore/keystore.properties` if present. This file is ignored by git.

Expected properties:
```
storeFile=../keystore/serenada-release.keystore
storePassword=YOUR_PASSWORD
keyAlias=serenada-release
keyPassword=YOUR_PASSWORD
```

### Generate a release keystore
Create the keystore (choose your own password and alias):
```bash
cd client-android
keytool -genkeypair -v \
  -keystore keystore/serenada-release.keystore \
  -alias serenada-release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass YOUR_PASSWORD \
  -keypass YOUR_PASSWORD \
  -dname "CN=Serenada, OU=Serenada, O=Serenada, L=, ST=, C=US"
```

Then create `keystore/keystore.properties`:
```bash
cd client-android
cat > keystore/keystore.properties <<'EOF'
storeFile=../keystore/serenada-release.keystore
storePassword=YOUR_PASSWORD
keyAlias=serenada-release
keyPassword=YOUR_PASSWORD
EOF
```

Get the SHA-256 fingerprint (needed for App Links):
```bash
keytool -list -v -keystore keystore/serenada-release.keystore -storepass YOUR_PASSWORD | \
  rg -m1 "SHA-256|SHA256"
```

## Deep links (App Links)
The app handles `https://serenada.app/call/*`. For App Links verification, the web server must serve:
```
client/public/.well-known/assetlinks.json
```

This file must include the release SHA-256 fingerprint for the signing certificate.

### Update assetlinks.json
Edit `client/public/.well-known/assetlinks.json` and add your release SHA-256:
```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "app.serenada.android",
      "sha256_cert_fingerprints": [
        "RELEASE_SHA256_HERE"
      ]
    }
  }
]
```

Then deploy the web app so the file is available at:
```
https://serenada.app/.well-known/assetlinks.json
```

Quick checks:
```bash
adb shell pm get-app-links app.serenada.android
adb shell am start -a android.intent.action.VIEW -d "https://serenada.app/call/ROOM_ID"
```

## Settings
Server host is configurable in the in-app Settings screen (Join screen → Settings).
On Save, the app validates `https://<host>/api/room-id` and only persists hosts that respond with the expected Serenada room ID payload.
`Call defaults` also include `HD Video (experimental)`; when disabled (default), camera capture uses legacy `640x480`, and when enabled the app applies higher per-mode camera/composite targets.
`Device Check` in Settings opens a native diagnostics screen with:
- Runtime permission checks (`CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS` on Android 13+)
- Audio/video capability inspection (camera inventory, composite prerequisites, audio processing feature availability)
- Connectivity checks (`/api/room-id`, WebSocket `/ws`, SSE `/sse` GET+POST, `/api/diagnostic-token`, `/api/turn-credentials`)
- ICE tests for full STUN/TURN and TURNS-only modes
- Title-bar share action that copies the full diagnostic report to clipboard and opens Android share sheet

During active call flows, WebRTC runtime stats are emitted to logcat every ~2s as:
- `CallManager: [WebRTCStats] ...`
- Debug builds also enable native WebRTC verbose logging (tag `org.webrtc`/`libjingle`) for ICE/TURN investigation.

## Known limitations
- Composite mode depends on device support for concurrent front+back camera capture; unsupported devices fall back to non-composite camera sources
