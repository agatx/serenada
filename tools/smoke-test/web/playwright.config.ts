import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  timeout: 120_000,
  retries: 0,
  use: {
    browserName: 'chromium',
    headless: true,
    ignoreHTTPSErrors: true,
    launchOptions: {
      args: [
        '--use-fake-device-for-media-stream',
        '--use-fake-ui-for-media-stream',
        '--allow-running-insecure-content',
        '--autoplay-policy=no-user-gesture-required',
      ],
    },
    permissions: ['camera', 'microphone'],
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  outputDir: '../artifacts/playwright',
});
