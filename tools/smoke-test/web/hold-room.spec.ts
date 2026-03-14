import { test } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const SERVER_URL = process.env.SMOKE_SERVER_URL!;
const ROOM_ID = process.env.SMOKE_ROOM_ID!;
const BARRIER_DIR = process.env.SMOKE_BARRIER_DIR || '';

function barrierWrite(name: string, content?: string) {
  if (!BARRIER_DIR) {
    return;
  }
  const filePath = path.join(BARRIER_DIR, name);
  fs.writeFileSync(filePath, content || '');
}

/**
 * Joins a room and holds it open indefinitely.
 * Used as the web partner for iOS test pairs — the iOS XCUITest runs
 * autonomously, so the web client just needs to stay in the room.
 * The process is killed by the orchestrator when the iOS test completes.
 */
test('hold room open for iOS partner', async ({ page }) => {
  test.setTimeout(10 * 60_000);

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
  // Ensure we actually received signaling "joined" (clientId persisted by SignalingContext).
  await page.waitForFunction(
    () => Boolean(window.sessionStorage.getItem('serenada.reconnectCid')),
    { timeout: 20_000 }
  );
  barrierWrite('web.holder.joined');

  // Best-effort observation for peer presence (informational only).
  // Not a hard requirement because iOS may join while media is still warming up.
  const waitingMessage = page.locator('.waiting-message');
  const initialWaitingText = (await waitingMessage.first().textContent())?.trim() ?? '';
  try {
    await page.waitForFunction(
      ({ initialText }) => {
        const waiting = document.querySelector('.waiting-message');
        if (!waiting) {
          return true;
        }
        const text = (waiting.textContent || '').trim();
        return text !== initialText;
      },
      { initialText: initialWaitingText },
      { timeout: 120_000 }
    );
    barrierWrite('web.holder.peer-connected');
  } catch {
    barrierWrite('web.holder.peer-not-observed');
  }

  // Hold the room open — wait until the process is killed
  await page.waitForTimeout(600_000);
});
