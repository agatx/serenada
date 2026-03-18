# Serenada SDK — API Reference Generation

## iOS (Swift DocC)

Swift Package Manager has built-in DocC support. Generate API docs for the SDK packages:

```bash
cd client-ios

# Generate SerenadaCore docs
swift package --package-path SerenadaCore generate-documentation --target SerenadaCore

# Generate SerenadaCallUI docs
swift package --package-path SerenadaCallUI generate-documentation --target SerenadaCallUI
```

Or from Xcode: Product → Build Documentation.

DocC output can be exported as a static site for hosting.

## Android (Dokka)

Dokka is configured in both `:serenada-core` and `:serenada-call-ui` modules.

```bash
cd client-android

# Generate docs for individual modules
./gradlew :serenada-core:dokkaHtml
./gradlew :serenada-call-ui:dokkaHtml
```

Output: `serenada-core/build/dokka/html/` and `serenada-call-ui/build/dokka/html/`.

## Web (TypeDoc)

TypeDoc configs are in each package directory.

```bash
cd client

# Install TypeDoc (dev dependency)
npm install -D typedoc

# Generate docs for each package
npx typedoc --options packages/core/typedoc.json
npx typedoc --options packages/react-ui/typedoc.json
```

Output: `packages/core/docs/` and `packages/react-ui/docs/`.
