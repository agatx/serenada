# AGENTS.md

This repository is production-critical.

## Rules
- Make minimal, targeted changes
- Do not introduce new dependencies, unless explicitly requested
- Preserve existing behavior unless instructed otherwise

## Code
- Follow existing style and patterns
- Prioritize clarity over cleverness
- Native Android client directory is `client-android/`
- Native iOS client directory is `client-ios/`
- In `client-android/`, camera source switching is mode-based (`selfie -> world -> composite`) rather than binary front/back flip
- In `client-ios/`, keep camera switching semantics mode-based (`selfie -> world -> composite`) with automatic composite skip when unsupported
- In `client-ios/`, preserve deep-link and universal-link parity (`/call/{roomId}`) for both `serenada.app` and `serenada-app.ru`; if changing iOS app links, update `client/public/.well-known/apple-app-site-association` and related docs

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
1. Run `./gradlew assembleDebug` in the client directory
2. Run `./gradlew installDebug` in the client directory
3. Use UI automation tools to test the app
4. To join a live call use the following deep-link: `https://serenada.app/call/YovflsGamCygX912gb26Jeaq8Es`

### Testing the iOS client
Follow the instructions in the `client-ios/README.md` file. Use the following deep-link: `https://serenada.app/call/YovflsGamCygX912gb26Jeaq8Es` to join a live call.

## When unsure
- Ask for clarification instead of guessing
