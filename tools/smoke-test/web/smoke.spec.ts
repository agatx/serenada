import { test } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const SERVER_URL = process.env.SMOKE_SERVER_URL!;
const ROOM_ID = process.env.SMOKE_ROOM_ID!;
const BARRIER_DIR = process.env.SMOKE_BARRIER_DIR!;
const ROLE = process.env.SMOKE_ROLE || 'web';

function barrierWrite(name: string, content?: string) {
  const filePath = path.join(BARRIER_DIR, name);
  fs.writeFileSync(filePath, content || '');
}

async function barrierWait(name: string, timeoutMs = 30_000): Promise<string> {
  const filePath = path.join(BARRIER_DIR, name);
  const start = Date.now();
  while (!fs.existsSync(filePath)) {
    if (Date.now() - start > timeoutMs) {
      throw new Error(`Barrier timeout: waited ${timeoutMs}ms for '${name}'`);
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  return fs.readFileSync(filePath, 'utf-8').trim();
}

async function clickJoinAndWaitForCall(page: import('@playwright/test').Page) {
  // Wait for the Join Call button with exact text (ensures signaling is connected)
  const joinButton = page.getByRole('button', { name: /join call/i });
  await joinButton.waitFor({ state: 'visible', timeout: 15_000 });

  // Click and wait for the call screen to appear
  await joinButton.click();

  // Wait for the pre-join card to disappear (hasJoined becomes true)
  await page.waitForSelector('.prejoin-card', { state: 'detached', timeout: 30_000 });
}

async function revealControlsAndClickLeave(page: import('@playwright/test').Page, timeoutMs = 12_000) {
  const leaveButton = page.locator('button.btn-leave');
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;

  while (Date.now() < deadline) {
    try {
      await leaveButton.click({ trial: true, timeout: 1_000 });
      await leaveButton.click({ timeout: 5_000 });
      return;
    } catch (error) {
      lastError = error;
    }

    const viewport = page.viewportSize();
    const tapX = Math.floor((viewport?.width ?? 1280) / 2);
    const tapY = Math.floor((viewport?.height ?? 720) / 2);
    await page.mouse.click(tapX, tapY);
    await page.waitForTimeout(250);
  }

  throw new Error(`Leave button did not become actionable within ${timeoutMs}ms: ${String(lastError)}`);
}

test('smoke: join, verify peer, leave, rejoin', async ({ page }) => {
  // Log console errors for debugging
  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      console.log(`[BROWSER ERROR] ${msg.text()}`);
    }
  });
  page.on('pageerror', (err) => {
    console.log(`[PAGE ERROR] ${err.message}`);
  });

  // Phase 1: Join room
  await page.goto(`${SERVER_URL}/call/${ROOM_ID}`);
  await clickJoinAndWaitForCall(page);

  // Signal that web has joined
  barrierWrite(`${ROLE}.joined`);

  // Wait for the other participant to join
  await barrierWait('peer.ready', 45_000);

  // Wait for remote stream — .waiting-message should become hidden
  await page.waitForSelector('.waiting-message', { state: 'hidden', timeout: 45_000 });

  // Signal in-call
  barrierWrite(`${ROLE}.in-call`);

  // Phase 2: Leave
  await barrierWait('leave', 30_000);
  await revealControlsAndClickLeave(page);

  // Should navigate home
  await page.waitForURL('**/', { timeout: 15_000 });
  barrierWrite(`${ROLE}.left`);

  // Phase 3: Rejoin
  const rejoinContent = await barrierWait('rejoin', 30_000);
  const rejoinRoomId = rejoinContent || ROOM_ID;

  await page.goto(`${SERVER_URL}/call/${rejoinRoomId}`);
  await clickJoinAndWaitForCall(page);
  barrierWrite(`${ROLE}.rejoined`);

  // Wait for peer again
  await barrierWait('peer.ready.2', 45_000);
  await page.waitForSelector('.waiting-message', { state: 'hidden', timeout: 45_000 });
  barrierWrite(`${ROLE}.rejoin-in-call`);

  // Phase 4: End
  await barrierWait('end', 30_000);
  await revealControlsAndClickLeave(page);
  await page.waitForURL('**/', { timeout: 15_000 });
  barrierWrite(`${ROLE}.done`);
});
