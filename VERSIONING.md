# Versioning Policy

## Semantic Versioning
Serenada SDKs follow [Semantic Versioning 2.0.0](https://semver.org/).

### Pre-1.0 (current)
- **Minor** bumps (0.x.0) may include breaking changes
- **Patch** bumps (0.1.x) are backward-compatible bug fixes and improvements

### Post-1.0
- **Major** bumps for breaking changes
- **Minor** bumps for new features (backward-compatible)
- **Patch** bumps for bug fixes

## Version Synchronization
All SDK packages share the same version number and are released together:
- `@serenada/core` (npm)
- `@serenada/react-ui` (npm)
- `serenada-core` (Maven/AAR)
- `serenada-call-ui` (Maven/AAR)
- `SerenadaCore` (SPM)

## What Constitutes a Breaking Change
- Removal or rename of public types, methods, or properties
- Changes to state machine transitions (CallPhase flow)
- Signaling protocol version bump
- Changes to constructor signatures or required configuration
- Behavioral changes that existing consumers depend on

## Verifying Version Parity
```bash
node scripts/check-version-parity.mjs
```

This script checks that all SDK packages declare the same version.
