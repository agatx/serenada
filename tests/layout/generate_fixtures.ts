// Fixture generator for cross-platform layout conformance tests.
// Run: npx tsx tests/layout/generate_fixtures.ts > tests/layout/fixtures/layout_conformance_v1.json

import {
    computeLayout,
    type CallScene,
    type LayoutResult,
    type SceneParticipant,
    type ContentSource,
    type LayoutMode,
    type Rect,
} from '../../client/src/layout/computeLayout';

// ---------------------------------------------------------------------------
// Normalized frame helpers
// ---------------------------------------------------------------------------

interface NormalizedFrame {
    x: number;
    y: number;
    width: number;
    height: number;
}

function normalizeFrame(frame: Rect, viewportWidth: number, viewportHeight: number): NormalizedFrame {
    return {
        x: round4(frame.x / viewportWidth),
        y: round4(frame.y / viewportHeight),
        width: round4(frame.width / viewportWidth),
        height: round4(frame.height / viewportHeight),
    };
}

function round4(n: number): number {
    return Math.round(n * 10000) / 10000;
}

// ---------------------------------------------------------------------------
// Test matrix definitions
// ---------------------------------------------------------------------------

const VIEWPORTS = [
    { name: 'phone_portrait', width: 390, height: 844 },
    { name: 'phone_landscape', width: 844, height: 390 },
    { name: 'tablet_portrait', width: 768, height: 1024 },
    { name: 'tablet_landscape', width: 1024, height: 768 },
    { name: 'desktop_compact', width: 1280, height: 720 },
    { name: 'desktop_wide', width: 1600, height: 900 },
];

const ASPECT_RATIO_SETS = [
    { name: '16_9', ratios: [16 / 9, 16 / 9, 16 / 9, 16 / 9] },
    { name: '4_3', ratios: [4 / 3, 4 / 3, 4 / 3, 4 / 3] },
    { name: 'mixed', ratios: [16 / 9, 4 / 3, 9 / 16, 16 / 9] },
];

const SAFE_AREA_SETS = [
    { name: 'none', insets: { top: 0, bottom: 0, left: 0, right: 0 } },
    { name: 'notch', insets: { top: 47, bottom: 34, left: 0, right: 0 } },
];

function makeParticipants(count: number, aspectRatios: number[]): SceneParticipant[] {
    const participants: SceneParticipant[] = [];
    const ids = ['A', 'B', 'C', 'D'];
    for (let i = 0; i < count - 1; i++) {
        participants.push({
            id: ids[i],
            role: 'remote',
            videoEnabled: true,
            videoAspectRatio: aspectRatios[i] ?? 16 / 9,
        });
    }
    participants.push({
        id: 'ME',
        role: 'local',
        videoEnabled: true,
        videoAspectRatio: aspectRatios[count - 1] ?? 16 / 9,
    });
    return participants;
}

const DEFAULT_PREFS = { swappedLocalAndRemote: false, dominantFit: 'cover' as const };

// ---------------------------------------------------------------------------
// Case generation
// ---------------------------------------------------------------------------

interface TestCase {
    id: string;
    description: string;
    scene: CallScene;
    expected: {
        mode: LayoutMode;
        tileCount: number;
        tiles: Array<{
            id: string;
            type: 'participant' | 'contentSource';
            normalizedFrame: NormalizedFrame;
            fit: 'cover' | 'contain';
        }>;
        localPip: {
            participantId: string;
            anchor: string;
            normalizedFrame: NormalizedFrame;
        } | null;
    };
}

function buildCase(id: string, description: string, scene: CallScene): TestCase {
    const result: LayoutResult = computeLayout(scene);

    return {
        id,
        description,
        scene,
        expected: {
            mode: result.mode,
            tileCount: result.tiles.length,
            tiles: result.tiles.map((t) => ({
                id: t.id,
                type: t.type,
                normalizedFrame: normalizeFrame(t.frame, scene.viewportWidth, scene.viewportHeight),
                fit: t.fit,
            })),
            localPip: result.localPip
                ? {
                      participantId: result.localPip.participantId,
                      anchor: result.localPip.anchor,
                      normalizedFrame: normalizeFrame(
                          result.localPip.frame,
                          scene.viewportWidth,
                          scene.viewportHeight,
                      ),
                  }
                : null,
        },
    };
}

// ---------------------------------------------------------------------------
// Generate all cases
// ---------------------------------------------------------------------------

const cases: TestCase[] = [];

// Solo mode: 1 participant × 6 viewports
for (const vp of VIEWPORTS) {
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(1, [16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: null,
        contentSource: null,
        userPrefs: DEFAULT_PREFS,
    };
    cases.push(buildCase(`solo_${vp.name}`, `Solo mode on ${vp.name}`, scene));
}

// Pair mode: 2 participants × 6 viewports
for (const vp of VIEWPORTS) {
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(2, [16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: null,
        contentSource: null,
        userPrefs: DEFAULT_PREFS,
    };
    cases.push(buildCase(`pair_${vp.name}`, `Pair mode on ${vp.name}`, scene));
}

// Pair mode swapped: 2 participants × 2 viewports (representative)
for (const vp of [VIEWPORTS[0], VIEWPORTS[4]]) {
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(2, [16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: null,
        contentSource: null,
        userPrefs: { swappedLocalAndRemote: true, dominantFit: 'cover' },
    };
    cases.push(buildCase(`pair_swapped_${vp.name}`, `Pair mode swapped on ${vp.name}`, scene));
}

// Pair mode with contain fit: 2 participants × 2 viewports
for (const vp of [VIEWPORTS[0], VIEWPORTS[4]]) {
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(2, [16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: null,
        contentSource: null,
        userPrefs: { swappedLocalAndRemote: false, dominantFit: 'contain' },
    };
    cases.push(buildCase(`pair_contain_${vp.name}`, `Pair mode with contain on ${vp.name}`, scene));
}

// Grid mode: 3 participants × 6 viewports × 3 aspect ratio combos
for (const vp of VIEWPORTS) {
    for (const ar of ASPECT_RATIO_SETS) {
        const scene: CallScene = {
            viewportWidth: vp.width,
            viewportHeight: vp.height,
            safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
            participants: makeParticipants(3, ar.ratios),
            localParticipantId: 'ME',
            activeSpeakerId: null,
            pinnedParticipantId: null,
            contentSource: null,
            userPrefs: DEFAULT_PREFS,
        };
        cases.push(
            buildCase(`grid_3p_${ar.name}_${vp.name}`, `Grid 3p ${ar.name} on ${vp.name}`, scene),
        );
    }
}

// Grid mode: 4 participants × 6 viewports × 3 aspect ratio combos
for (const vp of VIEWPORTS) {
    for (const ar of ASPECT_RATIO_SETS) {
        const scene: CallScene = {
            viewportWidth: vp.width,
            viewportHeight: vp.height,
            safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
            participants: makeParticipants(4, ar.ratios),
            localParticipantId: 'ME',
            activeSpeakerId: null,
            pinnedParticipantId: null,
            contentSource: null,
            userPrefs: DEFAULT_PREFS,
        };
        cases.push(
            buildCase(`grid_4p_${ar.name}_${vp.name}`, `Grid 4p ${ar.name} on ${vp.name}`, scene),
        );
    }
}

// Grid with safe-area insets: phone portrait only, 3 participants, 16:9
for (const sa of SAFE_AREA_SETS) {
    if (sa.name === 'none') continue;
    const vp = VIEWPORTS[0]; // phone portrait
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: sa.insets,
        participants: makeParticipants(3, [16 / 9, 16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: null,
        contentSource: null,
        userPrefs: DEFAULT_PREFS,
    };
    cases.push(
        buildCase(
            `grid_3p_16_9_phone_portrait_${sa.name}`,
            `Grid 3p 16:9 phone portrait with ${sa.name} insets`,
            scene,
        ),
    );
}

// Focus mode: 3 participants × 6 viewports × 2 pin variants (remote, local)
for (const vp of VIEWPORTS) {
    // Pinned remote
    const scene3Remote: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(3, [16 / 9, 16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: 'A',
        contentSource: null,
        userPrefs: DEFAULT_PREFS,
    };
    cases.push(buildCase(`focus_3p_pinnedRemote_${vp.name}`, `Focus 3p pinned remote on ${vp.name}`, scene3Remote));

    // Pinned local
    const scene3Local: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(3, [16 / 9, 16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: 'ME',
        contentSource: null,
        userPrefs: DEFAULT_PREFS,
    };
    cases.push(buildCase(`focus_3p_pinnedLocal_${vp.name}`, `Focus 3p pinned local on ${vp.name}`, scene3Local));
}

// Focus mode: 4 participants × 6 viewports, pinned remote
for (const vp of VIEWPORTS) {
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(4, [16 / 9, 16 / 9, 16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: 'A',
        contentSource: null,
        userPrefs: DEFAULT_PREFS,
    };
    cases.push(buildCase(`focus_4p_pinnedRemote_${vp.name}`, `Focus 4p pinned remote on ${vp.name}`, scene));
}

// Focus mode with contain fit: representative case
{
    const vp = VIEWPORTS[0];
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(3, [16 / 9, 16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: 'A',
        contentSource: null,
        userPrefs: { swappedLocalAndRemote: false, dominantFit: 'contain' },
    };
    cases.push(buildCase(`focus_3p_contain_phone_portrait`, `Focus 3p contain on phone portrait`, scene));
}

// Content mode: 2 participants × 6 viewports, screenShare
for (const vp of VIEWPORTS) {
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(2, [16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: null,
        contentSource: { type: 'screenShare', ownerParticipantId: 'A', aspectRatio: 16 / 9 },
        userPrefs: DEFAULT_PREFS,
    };
    cases.push(buildCase(`content_2p_screenShare_${vp.name}`, `Content 2p screenShare on ${vp.name}`, scene));
}

// Content mode: 3 participants × 6 viewports, screenShare
for (const vp of VIEWPORTS) {
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(3, [16 / 9, 16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: null,
        contentSource: { type: 'screenShare', ownerParticipantId: 'A', aspectRatio: 16 / 9 },
        userPrefs: DEFAULT_PREFS,
    };
    cases.push(buildCase(`content_3p_screenShare_${vp.name}`, `Content 3p screenShare on ${vp.name}`, scene));
}

// Content mode: 4 participants × 6 viewports, screenShare
for (const vp of VIEWPORTS) {
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(4, [16 / 9, 16 / 9, 16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: null,
        contentSource: { type: 'screenShare', ownerParticipantId: 'A', aspectRatio: 16 / 9 },
        userPrefs: DEFAULT_PREFS,
    };
    cases.push(buildCase(`content_4p_screenShare_${vp.name}`, `Content 4p screenShare on ${vp.name}`, scene));
}

// Content mode: worldCamera and compositeCamera on one viewport (verify layout equivalence)
for (const contentType of ['worldCamera', 'compositeCamera'] as const) {
    const vp = VIEWPORTS[0];
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(3, [16 / 9, 16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: null,
        contentSource: { type: contentType, ownerParticipantId: 'A', aspectRatio: 16 / 9 },
        userPrefs: DEFAULT_PREFS,
    };
    cases.push(buildCase(`content_3p_${contentType}_phone_portrait`, `Content 3p ${contentType} on phone portrait`, scene));
}

// Content mode with contain fit: representative case
{
    const vp = VIEWPORTS[0];
    const scene: CallScene = {
        viewportWidth: vp.width,
        viewportHeight: vp.height,
        safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
        participants: makeParticipants(3, [16 / 9, 16 / 9, 16 / 9]),
        localParticipantId: 'ME',
        activeSpeakerId: null,
        pinnedParticipantId: null,
        contentSource: { type: 'screenShare', ownerParticipantId: 'A', aspectRatio: 16 / 9 },
        userPrefs: { swappedLocalAndRemote: false, dominantFit: 'contain' },
    };
    cases.push(buildCase(`content_3p_contain_phone_portrait`, `Content 3p contain on phone portrait`, scene));
}

// ---------------------------------------------------------------------------
// Output
// ---------------------------------------------------------------------------

const output = {
    version: 1,
    description: 'Canonical conformance cases for the shared layout algorithm.',
    caseCount: cases.length,
    cases,
};

process.stdout.write(JSON.stringify(output, null, 2) + '\n');
