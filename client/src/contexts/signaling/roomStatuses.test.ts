import { describe, expect, it } from 'vitest';
import { getRoomStatusState, mergeRoomStatusesPayload, mergeRoomStatusUpdatePayload } from './roomStatuses';

describe('mergeRoomStatusesPayload', () => {
    it('merges valid room status payloads into existing state', () => {
        const previous = {
            alpha: { count: 1, maxParticipants: 2 }
        };
        const payload = {
            alpha: { count: 2, maxParticipants: 4 },
            beta: { count: 1, maxParticipants: 4 }
        };

        expect(mergeRoomStatusesPayload(previous, payload)).toEqual({
            alpha: { count: 2, maxParticipants: 4 },
            beta: { count: 1, maxParticipants: 4 }
        });
    });

    it('accepts legacy numeric room counts and ignores malformed values', () => {
        const previous = {
            alpha: { count: 1, maxParticipants: 4 }
        };
        const payload = {
            alpha: 'bad',
            beta: 3,
            gamma: Infinity
        };

        expect(mergeRoomStatusesPayload(previous, payload)).toEqual({
            alpha: { count: 1, maxParticipants: 4 },
            beta: { count: 3, maxParticipants: 2 }
        });
    });
});

describe('mergeRoomStatusUpdatePayload', () => {
    it('applies single room status update', () => {
        const previous = {
            alpha: { count: 1, maxParticipants: 2 },
            beta: { count: 2, maxParticipants: 4 }
        };
        const payload = { rid: 'beta', count: 0 };

        expect(mergeRoomStatusUpdatePayload(previous, payload)).toEqual({
            alpha: { count: 1, maxParticipants: 2 },
            beta: { count: 0, maxParticipants: 4 }
        });
    });

    it('ignores malformed room status update payloads', () => {
        const previous = {
            alpha: { count: 1, maxParticipants: 2 }
        };

        expect(mergeRoomStatusUpdatePayload(previous, { rid: 123, count: 2 })).toEqual(previous);
        expect(mergeRoomStatusUpdatePayload(previous, { rid: 'alpha', count: 'bad' })).toEqual(previous);
        expect(mergeRoomStatusUpdatePayload(previous, null)).toEqual(previous);
    });
});

describe('getRoomStatusState', () => {
    it('marks a room as full only when count reaches capacity', () => {
        expect(getRoomStatusState({ count: 2, maxParticipants: 4 })).toBe('waiting');
        expect(getRoomStatusState({ count: 4, maxParticipants: 4 })).toBe('full');
    });
});
