import { describe, it, expect } from 'vitest';
import { computeLayout, type CallScene, type LayoutResult, type Rect } from './computeLayout';
import fixtureData from '../../../tests/layout/fixtures/layout_conformance_v1.json';

const STRICT_TOLERANCE = 0.005;

interface NormalizedFrame {
    x: number;
    y: number;
    width: number;
    height: number;
}

function normalizeFrame(frame: Rect, viewportWidth: number, viewportHeight: number): NormalizedFrame {
    return {
        x: frame.x / viewportWidth,
        y: frame.y / viewportHeight,
        width: frame.width / viewportWidth,
        height: frame.height / viewportHeight,
    };
}

function assertFrameClose(
    actual: NormalizedFrame,
    expected: NormalizedFrame,
    tolerance: number,
    _label: string,
) {
    expect(Math.abs(actual.x - expected.x)).toBeLessThanOrEqual(tolerance);
    expect(Math.abs(actual.y - expected.y)).toBeLessThanOrEqual(tolerance);
    expect(Math.abs(actual.width - expected.width)).toBeLessThanOrEqual(tolerance);
    expect(Math.abs(actual.height - expected.height)).toBeLessThanOrEqual(tolerance);
}

interface FixtureCase {
    id: string;
    description: string;
    scene: CallScene;
    expected: {
        mode: string;
        tileCount: number;
        tiles: Array<{
            id: string;
            type: string;
            normalizedFrame: NormalizedFrame;
            fit: string;
        }>;
        localPip: {
            participantId: string;
            anchor: string;
            normalizedFrame: NormalizedFrame;
        } | null;
    };
}

const fixtures = fixtureData as { cases: FixtureCase[] };

describe('layout conformance', () => {
    for (const testCase of fixtures.cases) {
        it(testCase.id, () => {
            const result: LayoutResult = computeLayout(testCase.scene);

            // Mode
            expect(result.mode).toBe(testCase.expected.mode);

            // Tile count
            expect(result.tiles.length).toBe(testCase.expected.tileCount);

            // Tile frames
            for (let i = 0; i < testCase.expected.tiles.length; i++) {
                const expectedTile = testCase.expected.tiles[i];
                const actualTile = result.tiles[i];
                expect(actualTile.id).toBe(expectedTile.id);
                expect(actualTile.type).toBe(expectedTile.type);
                expect(actualTile.fit).toBe(expectedTile.fit);

                const actualNorm = normalizeFrame(
                    actualTile.frame,
                    testCase.scene.viewportWidth,
                    testCase.scene.viewportHeight,
                );
                assertFrameClose(
                    actualNorm,
                    expectedTile.normalizedFrame,
                    STRICT_TOLERANCE,
                    `${testCase.id} tile[${i}]`,
                );
            }

            // Local PIP
            if (testCase.expected.localPip === null) {
                expect(result.localPip).toBeNull();
            } else {
                expect(result.localPip).not.toBeNull();
                expect(result.localPip!.participantId).toBe(
                    testCase.expected.localPip.participantId,
                );
                expect(result.localPip!.anchor).toBe(testCase.expected.localPip.anchor);

                const actualPipNorm = normalizeFrame(
                    result.localPip!.frame,
                    testCase.scene.viewportWidth,
                    testCase.scene.viewportHeight,
                );
                assertFrameClose(
                    actualPipNorm,
                    testCase.expected.localPip.normalizedFrame,
                    STRICT_TOLERANCE,
                    `${testCase.id} localPip`,
                );
            }
        });
    }
});
