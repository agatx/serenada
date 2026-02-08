# Serenada Android Client

Native Android (Kotlin) client for Serenada 1:1 WebRTC calls. This app mirrors the core call flow of the web client and uses WebSocket signaling only (no SSE or push in the current build).

## Features
- 1:1 WebRTC audio/video calls
- WebSocket signaling (protocol v1)
- In-call camera source cycle: `selfie` (default) -> `world` -> `composite` (world view with circular selfie overlay)
- Recent calls on home (max 3, deduped) with live room occupancy status and long-press remove
- Deep links for `https://serenada.app/call/*`
- Foreground service to keep active calls running in the background
- Settings screen to change server host

## Not included (current build)
- SSE signaling fallback
- Push notifications
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

Release APK (signed):
```bash
cd client-android
./gradlew :app:assembleRelease
```

Release output:
```
app/build/outputs/apk/release/app-release.apk
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

## Known limitations
- WebSocket signaling only
- No push notifications
- No SSE fallback
- Composite mode depends on device support for concurrent front+back camera capture; unsupported devices fall back to non-composite camera sources
