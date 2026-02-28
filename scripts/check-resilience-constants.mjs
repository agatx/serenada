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

const TS_PATH = resolve(root, 'client/src/constants/webrtcResilience.ts');
const KT_PATH = resolve(root, 'client-android/app/src/main/java/app/serenada/android/call/WebRtcResilienceConstants.kt');
const SWIFT_PATH = resolve(root, 'client-ios/Sources/Core/Call/WebRtcResilienceConstants.swift');

// ── Parsers ──────────────────────────────────────────────────────────

function parseTypeScript(src) {
    const constants = new Map();
    for (const m of src.matchAll(/export\s+const\s+([A-Z_]+)\s*=\s*([0-9.]+)/g)) {
        constants.set(m[1], parseFloat(m[2]));
    }
    return constants;
}

function parseKotlin(src) {
    const constants = new Map();
    for (const m of src.matchAll(/const\s+val\s+([A-Z_]+)\s*=\s*([0-9._]+)L?/g)) {
        constants.set(m[1], parseFloat(m[2].replace(/_/g, '')));
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
    return constants;
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

for (const name of [...allNames].sort()) {
    const tsVal = tsMap.get(name);
    const ktVal = ktMap.get(name);
    const swVal = swMap.get(name);

    if (tsVal === undefined) {
        fail(`${name}: missing in TypeScript`);
        continue;
    }
    if (ktVal === undefined) {
        fail(`${name}: missing in Kotlin`);
        continue;
    }
    if (swVal === undefined) {
        fail(`${name}: missing in Swift`);
        continue;
    }

    if (tsVal !== ktVal) {
        fail(`${name}: TypeScript=${tsVal} vs Kotlin=${ktVal}`);
    } else if (tsVal !== swVal) {
        fail(`${name}: TypeScript=${tsVal} vs Swift=${swVal}`);
    } else {
        matchCount++;
    }
}

if (exitCode === 0) {
    console.log(`OK: all ${matchCount} resilience constants match across web, Android, and iOS.`);
} else {
    console.log(`\n${matchCount}/${allNames.size} constants match.`);
}

process.exit(exitCode);
