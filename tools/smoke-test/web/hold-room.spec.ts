import { test } from '@playwright/test';

const SERVER_URL = process.env.SMOKE_SERVER_URL!;
const ROOM_ID = process.env.SMOKE_ROOM_ID!;

/**
 * Joins a room and holds it open indefinitely.
 * Used as the web partner for iOS test pairs — the iOS XCUITest runs
 * autonomously, so the web client just needs to stay in the room.
 * The process is killed by the orchestrator when the iOS test completes.
 */
test('hold room open for iOS partner', async ({ page }) => {
  // Log console errors for debugging
  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      console.log(`[BROWSER ERROR] ${msg.text()}`);
    }
  });

  await page.goto(`${SERVER_URL}/call/${ROOM_ID}`);

  // Wait for "Join Call" text (ensures signaling is connected)
  const joinButton = page.getByRole('button', { name: /join call/i });
  await joinButton.waitFor({ state: 'visible', timeout: 15_000 });
  await joinButton.click();

  // Wait for pre-join card to disappear (transition to call screen)
  await page.waitForSelector('.prejoin-card', { state: 'detached', timeout: 30_000 });

  // Hold the room open — wait until the process is killed
  await page.waitForTimeout(600_000);
});
