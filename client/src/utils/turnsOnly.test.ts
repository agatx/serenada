import { describe, expect, it } from 'vitest';
import { parseTurnsOnly } from './turnsOnly';

describe('parseTurnsOnly', () => {
    it('defaults to false when the query parameter is absent', () => {
        expect(parseTurnsOnly('')).toBe(false);
        expect(parseTurnsOnly('?name=test')).toBe(false);
    });

    it('accepts common truthy values', () => {
        expect(parseTurnsOnly('?turnsOnly=1')).toBe(true);
        expect(parseTurnsOnly('?turnsOnly=true')).toBe(true);
        expect(parseTurnsOnly('?turnsOnly=yes')).toBe(true);
        expect(parseTurnsOnly('?turnsOnly=on')).toBe(true);
    });

    it('rejects common falsey values', () => {
        expect(parseTurnsOnly('?turnsOnly=0')).toBe(false);
        expect(parseTurnsOnly('?turnsOnly=false')).toBe(false);
        expect(parseTurnsOnly('?turnsOnly=no')).toBe(false);
        expect(parseTurnsOnly('?turnsOnly=off')).toBe(false);
        expect(parseTurnsOnly('?turnsOnly=')).toBe(false);
    });
});
