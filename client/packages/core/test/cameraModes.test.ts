import { describe, expect, it } from 'vitest';
import { nextCameraMode, resolveCameraModes } from '../src/cameraModes.js';

describe('resolveCameraModes', () => {
    it('defaults to selfie + world when undefined (composite dropped on web)', () => {
        expect(resolveCameraModes(undefined)).toEqual(['selfie', 'world']);
    });

    it('preserves configured order', () => {
        expect(resolveCameraModes(['world', 'selfie'])).toEqual(['world', 'selfie']);
    });

    it('silently drops composite on web', () => {
        expect(resolveCameraModes(['selfie', 'composite', 'world'])).toEqual(['selfie', 'world']);
    });

    it('returns an empty list when given an empty list', () => {
        expect(resolveCameraModes([])).toEqual([]);
    });

    it('returns empty when all entries are unsupported', () => {
        expect(resolveCameraModes(['composite'])).toEqual([]);
    });

    it('deduplicates while preserving first occurrence', () => {
        expect(resolveCameraModes(['world', 'selfie', 'world'])).toEqual(['world', 'selfie']);
    });
});

describe('nextCameraMode', () => {
    it('returns null when only one mode is available', () => {
        expect(nextCameraMode(['selfie'], 'selfie')).toBeNull();
    });

    it('cycles in configured order', () => {
        expect(nextCameraMode(['world', 'selfie'], 'world')).toBe('selfie');
        expect(nextCameraMode(['world', 'selfie'], 'selfie')).toBe('world');
    });

    it('falls back to the first mode when current is not in the list', () => {
        expect(nextCameraMode(['world', 'selfie'], 'composite')).toBe('world');
    });
});
