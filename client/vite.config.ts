import { resolve as pathResolve } from 'node:path'
// `vitest/config` re-exports Vite's defineConfig with the `test` field typed, so
// the dev/build pipeline and the test setup share one config (multi-call session).
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import legacy from '@vitejs/plugin-legacy'

// https://vite.dev/config/
export default defineConfig({
  envDir: '..',
  envPrefix: ['VITE_', 'TRANSPORTS'],
  plugins: [
    react(),
    legacy({
      targets: ['defaults', 'not IE 11', 'Android >= 7'],
    }),
  ],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
      },
      '/device-check': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    }
  },
  resolve: {
    alias: {
      '@agatx/serenada-core': pathResolve(__dirname, 'packages/core/src/index.ts'),
      '@agatx/serenada-react-ui': pathResolve(__dirname, 'packages/react-ui/src/index.ts'),
    },
  },
  test: {
    // Reset the process-singleton foreground media arbiter after every test so a
    // lease/mode held by one test cannot fail a later one with
    // ForegroundLeaseUnavailable (multi-call session, Phase 2).
    setupFiles: [pathResolve(__dirname, 'test/vitest.setup.ts')],
  },
})
