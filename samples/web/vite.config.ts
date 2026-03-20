import { resolve as pathResolve } from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@serenada/core': pathResolve(__dirname, '../../client/packages/core/src/index.ts'),
      '@serenada/react-ui': pathResolve(__dirname, '../../client/packages/react-ui/src/index.ts'),
    },
  },
  server: {
    fs: {
      allow: [pathResolve(__dirname, '../..')],
    },
  },
})
