# Serenada Web Sample App

Minimal web host app demonstrating Serenada SDK integration with React.

## What it does

- Accepts a call URL and renders `<SerenadaCallFlow>`
- Creates a new room via `createSerenadaCore().createRoom()`
- Runs as a standalone Vite app inside this repository
- Resolves `@serenada/core` and `@serenada/react-ui` directly from local source in `client/packages/`

## Run in this repo

```bash
cd samples/web
npm install
npm run dev
```

Then open the local Vite URL, usually `http://localhost:5173`.

To verify a production build:

```bash
cd samples/web
npm run build
```

## Standalone setup outside this repo

If you want to copy the sample into another project instead of using the repo-local package:

```bash
npm install @serenada/core @serenada/react-ui lucide-react react react-dom react-qr-code
```

## Integration pattern

```tsx
import { createSerenadaCore } from '@serenada/core'
import { SerenadaCallFlow } from '@serenada/react-ui'

// 1. Initialize core
const serenada = createSerenadaCore({ serverHost: 'serenada.app' })

// Prefer SerenadaSessionHandle in app-facing code and component props.

// 2a. Join an existing invite link by URL
<SerenadaCallFlow url={callUrl} onDismiss={() => navigate('/')} />

// 2b. When you create a room, reuse the returned session.
// createRoom() already joins once, so passing only room.url would join twice.
const room = await serenada.createRoom()
<SerenadaCallFlow url={room.url} session={room.session} onDismiss={() => navigate('/')} />
```
