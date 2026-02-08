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

## Documentation
- Update all relevant documentation when making changes:
    - README.md - high-level overview for end users, including quick start instructions, description of features, and links to documentation
    - AGENTS.md - instructions for coding agents
    - DEPLOY.md - deployment instructions
    - serenada_protocol_v1.md - protocol specification
    - push-notifications.md - push notifications documentation

## Testing
- If you need to test locally, you can:
1. Run `npm run build` in the client directory
2. Run `docker-compose up -d --build` in the server directory
3. Access the app at `http://localhost`

## When unsure
- Ask for clarification instead of guessing
