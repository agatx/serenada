import { resolve as pathResolve } from 'node:path'
import { defineConfig } from 'vite'
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
      '@serenada/core': pathResolve(__dirname, 'packages/core/src/index.ts'),
      '@serenada/react-ui': pathResolve(__dirname, 'packages/react-ui/src/index.ts'),
    },
  }
})
