#!/usr/bin/env node

import { constants } from 'node:fs';
import { access, mkdtemp, readFile, rm } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { delimiter, dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const fixturePath = resolve(scriptDirectory, '../client/packages/core/test/e2e/opus-red-mixed-fleet.html');

async function executable(path) {
  if (!path) return false;
  try {
    await access(path, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

async function findChrome() {
  const candidates = [
    process.env.CHROME_BIN,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ];
  for (const directory of (process.env.PATH ?? '').split(delimiter)) {
    candidates.push(join(directory, 'google-chrome'), join(directory, 'chromium'));
  }
  for (const candidate of candidates) {
    if (await executable(candidate)) return candidate;
  }
  throw new Error('Chrome or Chromium was not found. Set CHROME_BIN to its executable path.');
}

async function waitForDevToolsPort(profilePath, chrome) {
  const activePortPath = join(profilePath, 'DevToolsActivePort');
  for (let attempt = 0; attempt < 150; attempt += 1) {
    if (chrome.exitCode !== null) throw new Error(`Chrome exited early with code ${chrome.exitCode}`);
    try {
      const [port] = (await readFile(activePortPath, 'utf8')).trim().split('\n');
      if (port) return Number(port);
    } catch {
      // Chrome creates the file after its debugging server is ready.
    }
    await new Promise(resolvePromise => setTimeout(resolvePromise, 100));
  }
  throw new Error('Chrome DevTools port did not become ready');
}

async function waitForTestPage(port) {
  for (let attempt = 0; attempt < 150; attempt += 1) {
    try {
      const pages = await fetch(`http://127.0.0.1:${port}/json/list`).then(response => response.json());
      const page = pages.find(candidate => candidate.url.endsWith('/opus-red-mixed-fleet.html'));
      if (page) return page;
    } catch {
      // DevTools may accept connections just before the test page is registered.
    }
    await new Promise(resolvePromise => setTimeout(resolvePromise, 100));
  }
  throw new Error('Opus RED test page did not open');
}

async function readResult(webSocketUrl) {
  const socket = new WebSocket(webSocketUrl);
  await new Promise((resolvePromise, reject) => {
    socket.addEventListener('open', resolvePromise, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });

  let commandId = 0;
  const pending = new Map();
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    const command = pending.get(message.id);
    if (!command) return;
    pending.delete(message.id);
    if (message.error) command.reject(new Error(message.error.message));
    else command.resolve(message);
  });

  const command = (method, params = {}) => {
    const id = ++commandId;
    socket.send(JSON.stringify({ id, method, params }));
    return new Promise((resolvePromise, reject) => pending.set(id, { resolve: resolvePromise, reject }));
  };

  try {
    for (let attempt = 0; attempt < 450; attempt += 1) {
      const response = await command('Runtime.evaluate', {
        expression: "document.querySelector('#result')?.textContent",
        returnByValue: true,
      });
      const value = response.result?.result?.value;
      if (typeof value === 'string' && value !== 'RUNNING') return value;
      await new Promise(resolvePromise => setTimeout(resolvePromise, 100));
    }
    throw new Error('Opus RED browser test timed out');
  } finally {
    socket.close();
  }
}

async function stopChrome(chrome) {
  if (chrome.exitCode !== null) return;
  chrome.kill('SIGTERM');
  await Promise.race([
    new Promise(resolvePromise => chrome.once('exit', resolvePromise)),
    new Promise(resolvePromise => setTimeout(resolvePromise, 2000)),
  ]);
  if (chrome.exitCode === null) chrome.kill('SIGKILL');
}

async function main() {
  const chromePath = await findChrome();
  const profilePath = await mkdtemp(join(tmpdir(), 'serenada-opus-red-interop-'));
  let chrome;
  let chromeErrors = '';

  try {
    chrome = spawn(chromePath, [
      '--headless=new',
      '--disable-dev-shm-usage',
      '--disable-extensions',
      '--disable-gpu',
      '--no-first-run',
      '--no-sandbox',
      '--autoplay-policy=no-user-gesture-required',
      '--remote-debugging-port=0',
      `--user-data-dir=${profilePath}`,
      pathToFileURL(fixturePath).href,
    ], { stdio: ['ignore', 'ignore', 'pipe'] });
    chrome.stderr.on('data', chunk => {
      chromeErrors = `${chromeErrors}${chunk}`.slice(-8000);
    });

    const port = await waitForDevToolsPort(profilePath, chrome);
    const page = await waitForTestPage(port);
    const result = await readResult(page.webSocketDebuggerUrl);
    console.log(result);
    if (!result.startsWith('PASS')) throw new Error('Opus RED mixed-fleet interop failed');
  } catch (error) {
    if (chromeErrors) console.error(chromeErrors.trim());
    throw error;
  } finally {
    if (chrome) await stopChrome(chrome);
    await rm(profilePath, { recursive: true, force: true });
  }
}

main().catch(error => {
  console.error(error.stack ?? error);
  process.exitCode = 1;
});
