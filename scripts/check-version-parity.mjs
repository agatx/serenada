#!/usr/bin/env node

/**
 * Verifies that all Serenada SDK packages declare the same version.
 *
 * Usage:  node scripts/check-version-parity.mjs
 * Exit 0 on match, 1 on mismatch.
 */

import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');

// ── Version sources ─────────────────────────────────────────────────

const sources = [
    {
        name: '@serenada/core (TS constant)',
        file: 'client/packages/core/src/index.ts',
        regex: /SERENADA_CORE_VERSION\s*=\s*'([^']+)'/,
    },
    {
        name: '@serenada/core (package.json)',
        file: 'client/packages/core/package.json',
        regex: /"version"\s*:\s*"([^"]+)"/,
    },
    {
        name: '@serenada/react-ui (package.json)',
        file: 'client/packages/react-ui/package.json',
        regex: /"version"\s*:\s*"([^"]+)"/,
    },
    {
        name: 'serenada-core (build.gradle.kts)',
        file: 'client-android/serenada-core/build.gradle.kts',
        regex: /version\s*=\s*"([^"]+)"/,
    },
    {
        name: 'serenada-call-ui (build.gradle.kts)',
        file: 'client-android/serenada-call-ui/build.gradle.kts',
        regex: /version\s*=\s*"([^"]+)"/,
    },
    {
        name: 'SerenadaCore (Swift)',
        file: 'client-ios/SerenadaCore/Sources/SerenadaCore.swift',
        regex: /static\s+let\s+version\s*=\s*"([^"]+)"/,
    },
    {
        name: '@serenada/react-ui pinned @serenada/core dep',
        file: 'client/packages/react-ui/package.json',
        regex: /"@serenada\/core"\s*:\s*"\^?([^"]+)"/,
    },
];

// ── Parse and compare ───────────────────────────────────────────────

let allMatch = true;
const versions = [];

for (const src of sources) {
    const filePath = resolve(root, src.file);
    try {
        const content = readFileSync(filePath, 'utf-8');
        const match = content.match(src.regex);
        if (match) {
            versions.push({ name: src.name, version: match[1] });
        } else {
            console.error(`ERROR: Could not find version in ${src.file}`);
            allMatch = false;
        }
    } catch (e) {
        console.error(`ERROR: Could not read ${src.file}: ${e.message}`);
        allMatch = false;
    }
}

const uniqueVersions = [...new Set(versions.map(v => v.version))];

if (uniqueVersions.length > 1) {
    console.error('VERSION MISMATCH:');
    for (const v of versions) {
        console.error(`  ${v.name}: ${v.version}`);
    }
    allMatch = false;
} else if (uniqueVersions.length === 1 && allMatch && versions.length === sources.length) {
    console.log(`OK: All ${versions.length} version sources match at ${uniqueVersions[0]}.`);
}

process.exit(allMatch ? 0 : 1);
