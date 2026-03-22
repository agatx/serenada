# AGENTS.md

This repository is production-critical.

## Rules
- Make minimal, targeted changes
- Do not introduce new dependencies, unless explicitly requested
- Preserve existing behavior unless instructed otherwise

## Architecture

All client platforms use a **headless SDK + optional UI** pattern:
- **Web**: `client/packages/core/` (vanilla TS SDK) + `client/packages/react-ui/` (React UI) + `client/src/` (thin app shell)
- **Android**: `client-android/serenada-core/` (Kotlin SDK) + `client-android/serenada-call-ui/` (Compose UI) + `client-android/app/` (host app)
- **iOS**: `client-ios/SerenadaCore/` (SPM package) + `client-ios/SerenadaCallUI/` (SPM/SwiftUI) + `client-ios/Sources/` (host app)
- **Server**: `server/` (Go signaling server)
- **Samples**: `samples/ios/`, `samples/android/`, `samples/web/` (minimal integration examples)

SDK packages must not depend on UI frameworks (no SwiftUI in SerenadaCore, no React in @serenada/core, no Compose in serenada-core).

## Code
- Follow existing style and patterns
- Prioritize clarity over cleverness
- In `client-android/`, camera source switching is mode-based (`selfie -> world -> composite`) rather than binary front/back flip
- In `client-ios/`, keep camera switching semantics mode-based (`selfie -> world -> composite`) with automatic composite skip when unsupported
- In `client-ios/`, preserve deep-link and universal-link parity (`/call/{roomId}`) for both `serenada.app` and `serenada-app.ru`; if changing iOS app links, update `client/public/.well-known/apple-app-site-association` and related docs

## Key Paths

### Web SDK
- Entry point: `client/packages/core/src/SerenadaCore.ts`
- Session: `client/packages/core/src/SerenadaSession.ts`
- Signaling: `client/packages/core/src/signaling/SignalingEngine.ts`
- Media: `client/packages/core/src/media/MediaEngine.ts`
- Constants: `client/packages/core/src/constants.ts`
- React UI: `client/packages/react-ui/src/SerenadaCallFlow.tsx`

### Android SDK
- Entry point: `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaCore.kt`
- Session: `.../core/SerenadaSession.kt`
- WebRTC: `.../core/call/WebRtcEngine.kt`
- Signaling: `.../core/call/SignalingClient.kt`
- Constants: `.../core/call/WebRtcResilienceConstants.kt`
- Host app integration: `client-android/app/src/main/java/app/serenada/android/call/CallManager.kt`

### iOS SDK
- Entry point: `client-ios/SerenadaCore/Sources/SerenadaCore.swift`
- Session: `client-ios/SerenadaCore/Sources/SerenadaSession.swift`
- WebRTC: `client-ios/SerenadaCore/Sources/Call/WebRtcEngine.swift`
- Signaling: `client-ios/SerenadaCore/Sources/Signaling/SignalingClient.swift`
- Constants: `client-ios/SerenadaCore/Sources/Call/WebRtcResilienceConstants.swift`
- Host app integration: `client-ios/Sources/Core/Call/CallManager.swift`
- Shared push key store: `client-ios/Shared/PushKeyStore.swift`

### Server
- Entry point: `server/main.go`
- Signaling: `server/signaling.go`
- Push notifications: `server/push.go`, `server/push_fcm.go`

## Cross-Platform Parity
- Resilience constants are shared across all clients. Run `node scripts/check-resilience-constants.mjs` to verify.
- Signaling protocol v1 is identical across all platforms (see `serenada_protocol_v1.md`).
- When changing resilience timing, update all three constant files and run the verification script.

## Documentation
- Update all relevant documentation when making changes. Only update documentation that is directly relevant to the change:
    - README.md - high-level overview for end users, including quick start instructions, description of features, and links to documentation
    - AGENTS.md - instructions for coding agents
    - DEPLOY.md - deployment instructions
    - serenada_protocol_v1.md - protocol specification
    - push-notifications.md - push notifications documentation

## Testing
### Testing the web client
- If you need to test locally, you can:
1. Run `npm run build` in the client directory
2. Run `docker-compose up -d --build` in the server directory
3. Access the app at `http://localhost`

### Testing the Android client
- If you need to test locally, you can:
1. Run `./gradlew assembleDebug` in the client-android directory
2. Run `./gradlew installDebug` in the client-android directory
3. Use UI automation tools to test the app
4. To join a live call use the following deep-link: `https://serenada.app/call/YovflsGamCygX912gb26Jeaq8Es`

### Testing the iOS client
- Build and deploy to a physical device:
```bash
cd client-ios
xcodegen generate
./scripts/deploy_to_device.sh
```
- Run unit tests:
```bash
cd client-ios
xcodegen generate
xcodebuild -project SerenadaiOS.xcodeproj -scheme SerenadaiOS \
  -destination 'platform=iOS Simulator,name=iPhone 16' test
```
- Use the following deep-link to join a live call: `https://serenada.app/call/YovflsGamCygX912gb26Jeaq8Es`

## Worktree Setup
When working in a git worktree, run the bootstrap script to install all dependencies:
```bash
tools/worktree-bootstrap.sh .
```
Skip platforms you don't need with `SKIP_WEB=1`, `SKIP_SERVER=1`, `SKIP_ANDROID=1`, `SKIP_IOS=1`.

Validate the worktree is build-ready:
```bash
tools/worktree-validate.sh .
```

## When unsure
- Ask for clarification instead of guessing
