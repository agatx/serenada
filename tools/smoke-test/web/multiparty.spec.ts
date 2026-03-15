import { test } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const SERVER_URL = process.env.SMOKE_SERVER_URL!;
const ROOM_ID = process.env.SMOKE_ROOM_ID!;
const BARRIER_DIR = process.env.SMOKE_BARRIER_DIR!;
const ROLE = process.env.SMOKE_ROLE || 'web';
const EXPECTED_PARTICIPANTS = Number.parseInt(process.env.SMOKE_EXPECTED_PARTICIPANTS || '3', 10);

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
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  return fs.readFileSync(filePath, 'utf-8').trim();
}

async function clickJoinAndWaitForCall(page: import('@playwright/test').Page) {
  const joinButton = page.getByRole('button', { name: /join call/i });
  await joinButton.waitFor({ state: 'visible', timeout: 15_000 });
  await joinButton.click();
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

test('smoke: join and validate multiparty participant count', async ({ page }) => {
  test.setTimeout(10 * 60_000);

  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      console.log(`[BROWSER ERROR] ${msg.text()}`);
    }
  });
  page.on('pageerror', (err) => {
    console.log(`[PAGE ERROR] ${err.message}`);
  });

  await page.goto(`${SERVER_URL}/call/${ROOM_ID}`);
  await clickJoinAndWaitForCall(page);
  barrierWrite(`${ROLE}.joined`);

  await page.waitForFunction(
    (expectedParticipants) => {
      const probe = document.querySelector<HTMLElement>('[data-testid="call-participant-count"]');
      if (!probe) {
        const multiPartyRoot = document.querySelector<HTMLElement>('.multi-party-call');
        if (!multiPartyRoot) {
          return false;
        }
        const visibleVideos = Array.from(multiPartyRoot.querySelectorAll('video')).filter((video) => {
          const rect = video.getBoundingClientRect();
          return rect.width >= 8 && rect.height >= 8;
        });
        return visibleVideos.length >= expectedParticipants;
      }
      const rawValue = probe.dataset.count || probe.textContent || '0';
      const count = Number.parseInt(rawValue, 10);
      return Number.isFinite(count) && count >= expectedParticipants;
    },
    EXPECTED_PARTICIPANTS,
    { timeout: 75_000 }
  );

  barrierWrite(`${ROLE}.participant-count-ok`, String(EXPECTED_PARTICIPANTS));

  await barrierWait('end', 45_000);
  await revealControlsAndClickLeave(page);
  await page.waitForURL('**/', { timeout: 15_000 });
  barrierWrite(`${ROLE}.done`);
});
