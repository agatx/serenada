#!/usr/bin/env node

/**
 * Verifies that WebRTC resilience constants are in sync across all three
 * Serenada clients (web, Android, iOS).
 *
 * Usage:  node scripts/check-resilience-constants.mjs
 * Exit 0 on match, 1 on mismatch.
 */

import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');

const TS_PATH = resolve(root, 'client/packages/core/src/constants.ts');
const KT_PATH = resolve(root, 'client-android/serenada-core/src/main/java/app/serenada/core/call/WebRtcResilienceConstants.kt');
const SWIFT_PATH = resolve(root, 'client-ios/SerenadaCore/Sources/Call/WebRtcResilienceConstants.swift');

// ── Parsers ──────────────────────────────────────────────────────────

function parseTypeScript(src) {
    const constants = new Map();
    for (const m of src.matchAll(/export\s+const\s+([A-Z_]+)\s*=\s*([0-9.]+)/g)) {
        constants.set(m[1], parseFloat(m[2]));
    }
    for (const m of src.matchAll(/export\s+const\s+([A-Z_]+)\s*=\s*\[([^\]]*)\]/g)) {
        constants.set(m[1], parseNumericArray(m[2]));
    }
    return constants;
}

function parseKotlin(src) {
    const constants = new Map();
    for (const m of src.matchAll(/const\s+val\s+([A-Z_]+)\s*=\s*([0-9._]+)L?/g)) {
        constants.set(m[1], parseFloat(m[2].replace(/_/g, '')));
    }
    for (const m of src.matchAll(/val\s+([A-Z_]+)\s*=\s*longArrayOf\(([^)]*)\)/g)) {
        constants.set(m[1], parseNumericArray(m[2]));
    }
    return constants;
}

function swiftCamelToUpperSnake(name) {
    // Strip trailing Ms suffix used for millisecond constants
    let base = name.replace(/Ms$/, '_MS');
    // Convert camelCase to UPPER_SNAKE_CASE
    base = base.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
    return base;
}

function parseSwift(src) {
    const constants = new Map();
    // Match "static let fooBarMs = 123" or "static let fooBar = 0.8"
    // Skip Ns accessors (computed properties with var)
    for (const m of src.matchAll(/static\s+let\s+(\w+)\s*=\s*([0-9._]+)/g)) {
        const name = m[1];
        // Skip nanosecond accessors
        if (name.endsWith('Ns')) continue;
        const upperName = swiftCamelToUpperSnake(name);
        constants.set(upperName, parseFloat(m[2].replace(/_/g, '')));
    }
    for (const m of src.matchAll(/static\s+let\s+(\w+)\s*=\s*\[([^\]]*)\]/g)) {
        const name = m[1];
        if (name.endsWith('Ns')) continue;
        const upperName = swiftCamelToUpperSnake(name);
        constants.set(upperName, parseNumericArray(m[2]));
    }
    return constants;
}

function parseNumericArray(raw) {
    return raw
        .split(',')
        .map((entry) => entry.trim().replace(/_/g, '').replace(/L$/g, ''))
        .filter((entry) => entry.length > 0)
        .map((entry) => parseFloat(entry));
}

function valuesEqual(left, right) {
    if (Array.isArray(left) || Array.isArray(right)) {
        return JSON.stringify(left) === JSON.stringify(right);
    }
    return left === right;
}

function formatValue(value) {
    return Array.isArray(value) ? JSON.stringify(value) : String(value);
}

// ── Main ─────────────────────────────────────────────────────────────

let exitCode = 0;

function fail(msg) {
    console.error(`  FAIL: ${msg}`);
    exitCode = 1;
}

const tsSrc = readFileSync(TS_PATH, 'utf-8');
const ktSrc = readFileSync(KT_PATH, 'utf-8');
const swSrc = readFileSync(SWIFT_PATH, 'utf-8');

const tsMap = parseTypeScript(tsSrc);
const ktMap = parseKotlin(ktSrc);
const swMap = parseSwift(swSrc);

const allNames = new Set([...tsMap.keys(), ...ktMap.keys(), ...swMap.keys()]);
let matchCount = 0;
let skippedCount = 0;

for (const name of [...allNames].sort()) {
    const tsVal = tsMap.get(name);
    const ktVal = ktMap.get(name);
    const swVal = swMap.get(name);

    // Only enforce parity for constants present in at least two platforms.
    // Platform-specific constants (e.g. web-only LOCAL_VIDEO_*) are allowed.
    const present = [tsVal, ktVal, swVal].filter(v => v !== undefined);
    if (present.length < 2) {
        skippedCount++;
        continue;
    }

    if (tsVal !== undefined && ktVal !== undefined && !valuesEqual(tsVal, ktVal)) {
        fail(`${name}: TypeScript=${formatValue(tsVal)} vs Kotlin=${formatValue(ktVal)}`);
    } else if (tsVal !== undefined && swVal !== undefined && !valuesEqual(tsVal, swVal)) {
        fail(`${name}: TypeScript=${formatValue(tsVal)} vs Swift=${formatValue(swVal)}`);
    } else if (ktVal !== undefined && swVal !== undefined && !valuesEqual(ktVal, swVal)) {
        fail(`${name}: Kotlin=${formatValue(ktVal)} vs Swift=${formatValue(swVal)}`);
    } else {
        matchCount++;
    }
}

if (exitCode === 0) {
    const msg = `OK: ${matchCount} resilience constants match across platforms.`;
    console.log(skippedCount > 0 ? `${msg} (${skippedCount} platform-specific skipped)` : msg);
} else {
    console.log(`\n${matchCount}/${allNames.size - skippedCount} shared constants match.`);
}

process.exit(exitCode);
