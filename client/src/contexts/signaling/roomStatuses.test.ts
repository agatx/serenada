import { describe, expect, it } from 'vitest';
import { mergeRoomStatusesPayload, mergeRoomStatusUpdatePayload } from './roomStatuses';

describe('mergeRoomStatusesPayload', () => {
    it('merges valid room counts into existing state', () => {
        const previous = { alpha: 1 };
        const payload = { alpha: 2, beta: 1 };

        expect(mergeRoomStatusesPayload(previous, payload)).toEqual({
            alpha: 2,
            beta: 1
        });
    });

    it('ignores malformed payload values', () => {
        const previous = { alpha: 1 };
        const payload = { alpha: 'bad', beta: 3, gamma: Infinity };

        expect(mergeRoomStatusesPayload(previous, payload)).toEqual({
            alpha: 1,
            beta: 3
        });
    });
});

describe('mergeRoomStatusUpdatePayload', () => {
    it('applies single room status update', () => {
        const previous = { alpha: 1, beta: 2 };
        const payload = { rid: 'beta', count: 0 };

        expect(mergeRoomStatusUpdatePayload(previous, payload)).toEqual({
            alpha: 1,
            beta: 0
        });
    });

    it('ignores malformed room status update payloads', () => {
        const previous = { alpha: 1 };

        expect(mergeRoomStatusUpdatePayload(previous, { rid: 123, count: 2 })).toEqual(previous);
        expect(mergeRoomStatusUpdatePayload(previous, { rid: 'alpha', count: 'bad' })).toEqual(previous);
        expect(mergeRoomStatusUpdatePayload(previous, null)).toEqual(previous);
    });
});
