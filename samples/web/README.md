# Serenada Web Sample App

Minimal web host app demonstrating Serenada SDK integration with React.

## What it does

- Accepts a call URL and renders `<SerenadaCallFlow>`
- Creates a new room via `createSerenadaCore().createRoom()`
- Total integration: ~30 lines of React

## Setup

```bash
npm install @serenada/core @serenada/react-ui react react-dom
```

## Integration pattern

```tsx
import { createSerenadaCore } from '@serenada/core'
import { SerenadaCallFlow } from '@serenada/react-ui'

// 1. Initialize core
const serenada = createSerenadaCore({ serverHost: 'serenada.app' })

// 2. Show call UI when you have a URL
<SerenadaCallFlow url={callUrl} onDismiss={() => navigate('/')} />
```
