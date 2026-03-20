import { afterEach, describe, expect, it } from 'vitest';
import { buildApiUrl, buildRoomUrl, resolveServerBaseUrl, resolveServerUrls } from '../src/serverUrls';

const originalWindow = globalThis.window;

describe('serverUrls', () => {
    afterEach(() => {
        if (originalWindow === undefined) {
            Reflect.deleteProperty(globalThis, 'window');
            return;
        }
        Object.defineProperty(globalThis, 'window', {
            value: originalWindow,
            configurable: true,
        });
    });

    it('defaults remote hosts to secure https and wss endpoints', () => {
        expect(resolveServerUrls('serenada.app')).toEqual({
            httpBaseUrl: 'https://serenada.app',
            wsUrl: 'wss://serenada.app/ws',
        });
    });

    it('keeps loopback hosts on http and ws', () => {
        expect(resolveServerUrls('localhost:8080')).toEqual({
            httpBaseUrl: 'http://localhost:8080',
            wsUrl: 'ws://localhost:8080/ws',
        });
    });

    it('preserves explicit insecure origins', () => {
        expect(resolveServerBaseUrl('http://qa-box:8080')).toBe('http://qa-box:8080');
        expect(buildRoomUrl('http://qa-box:8080', 'room123')).toBe('http://qa-box:8080/call/room123');
        expect(buildApiUrl('http://qa-box:8080', '/api/room-id')).toBe('http://qa-box:8080/api/room-id');
    });

    it('maps websocket overrides back to the matching http origin', () => {
        expect(resolveServerBaseUrl('ws://qa-box:8080/ws')).toBe('http://qa-box:8080');
    });

    it('reuses the current page protocol for matching bare hosts', () => {
        Object.defineProperty(globalThis, 'window', {
            value: {
                location: {
                    protocol: 'http:',
                    host: 'qa-box:8080',
                },
            },
            configurable: true,
        });

        expect(resolveServerBaseUrl('qa-box:8080')).toBe('http://qa-box:8080');
    });
});
