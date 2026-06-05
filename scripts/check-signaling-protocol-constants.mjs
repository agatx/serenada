#!/usr/bin/env node

/**
 * Verifies signaling protocol wire constants that must stay identical across
 * all three Serenada clients (web, Android, iOS).
 *
 * Usage:  node scripts/check-signaling-protocol-constants.mjs
 * Exit 0 on match, 1 on mismatch.
 */

import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');

const TS_PATH = resolve(root, 'client/packages/core/src/signaling/protocolConstants.ts');
const KT_PATH = resolve(root, 'client-android/serenada-core/src/main/java/app/serenada/core/call/SignalingProtocolConstants.kt');
const SWIFT_PATH = resolve(root, 'client-ios/SerenadaCore/Sources/Call/SignalingProtocolConstants.swift');

const EXPECTED = new Map([
    ['MEDIA_RESTART_REASON_LOCAL_TRACK_NEGOTIATION', 'local track negotiation'],
]);

function parseTypeScript(src) {
    const constants = new Map();
    for (const m of src.matchAll(/export\s+const\s+([A-Z_]+)\s*=\s*['"]([^'"]*)['"]/g)) {
        constants.set(m[1], m[2]);
    }
    return constants;
}

function parseKotlin(src) {
    const constants = new Map();
    for (const m of src.matchAll(/const\s+val\s+([A-Z_]+)\s*=\s*"([^"]*)"/g)) {
        constants.set(m[1], m[2]);
    }
    return constants;
}

function swiftCamelToUpperSnake(name) {
    return name.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
}

function parseSwift(src) {
    const constants = new Map();
    for (const m of src.matchAll(/static\s+let\s+(\w+)\s*=\s*"([^"]*)"/g)) {
        constants.set(swiftCamelToUpperSnake(m[1]), m[2]);
    }
    return constants;
}

const platformMaps = [
    ['TypeScript', parseTypeScript(readFileSync(TS_PATH, 'utf-8'))],
    ['Kotlin', parseKotlin(readFileSync(KT_PATH, 'utf-8'))],
    ['Swift', parseSwift(readFileSync(SWIFT_PATH, 'utf-8'))],
];

let exitCode = 0;

function fail(msg) {
    console.error(`  FAIL: ${msg}`);
    exitCode = 1;
}

for (const [name, expectedValue] of EXPECTED) {
    for (const [platform, constants] of platformMaps) {
        const actualValue = constants.get(name);
        if (actualValue === undefined) {
            fail(`${name}: missing in ${platform}`);
        } else if (actualValue !== expectedValue) {
            fail(`${name}: ${platform}=${JSON.stringify(actualValue)} expected=${JSON.stringify(expectedValue)}`);
        }
    }
}

if (exitCode === 0) {
    console.log(`OK: ${EXPECTED.size} signaling protocol constants match across platforms.`);
}

process.exit(exitCode);
