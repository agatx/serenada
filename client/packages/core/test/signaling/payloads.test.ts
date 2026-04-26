import { describe, expect, it } from 'vitest';
import {
    parseJoinedPayload,
    parseRoomStatePayload,
    parseErrorPayload,
    parseTurnRefreshedPayload,
    parseOfferPayload,
    parseAnswerPayload,
    parseIceCandidatePayload,
    parseRelayFailedPayload,
    parseNegotiationDirtyPayload,
} from '../../src/signaling/payloads';

describe('parseJoinedPayload', () => {
    it('returns typed object for valid payload', () => {
        const raw = {
            hostCid: 'abc',
            participants: [{ cid: 'abc', joinedAt: 100 }],
            turnToken: 'tok',
            turnTokenTTLMs: 60000,
            reconnectToken: 'rt',
            maxParticipants: 4,
        };
        expect(parseJoinedPayload(raw)).toEqual({
            hostCid: 'abc',
            participants: [{ cid: 'abc', joinedAt: 100 }],
            turnToken: 'tok',
            turnTokenTTLMs: 60000,
            reconnectToken: 'rt',
            maxParticipants: 4,
        });
    });

    it('returns null for undefined input', () => {
        expect(parseJoinedPayload(undefined)).toBeNull();
    });

    it('returns null when participants is missing', () => {
        expect(parseJoinedPayload({ hostCid: 'abc' })).toBeNull();
    });

    it('returns null when participants is not an array', () => {
        expect(parseJoinedPayload({ hostCid: 'abc', participants: 'bad' })).toBeNull();
    });

    it('defaults hostCid to null when not a string', () => {
        const result = parseJoinedPayload({ participants: [{ cid: 'x' }], hostCid: 42 });
        expect(result?.hostCid).toBeNull();
    });

    it('omits optional fields when they have wrong types', () => {
        const result = parseJoinedPayload({
            hostCid: 'h',
            participants: [{ cid: 'a' }],
            turnToken: 123,
            turnTokenTTLMs: 'bad',
            reconnectToken: false,
            maxParticipants: 'nope',
        });
        expect(result).toEqual({
            hostCid: 'h',
            participants: [{ cid: 'a', joinedAt: undefined }],
            turnToken: undefined,
            turnTokenTTLMs: undefined,
            reconnectToken: undefined,
            maxParticipants: undefined,
        });
    });

    it('ignores extra fields', () => {
        const result = parseJoinedPayload({
            hostCid: 'h',
            participants: [],
            extraStuff: 'ignored',
        });
        expect(result).toEqual({
            hostCid: 'h',
            participants: [],
            turnToken: undefined,
            turnTokenTTLMs: undefined,
            reconnectToken: undefined,
            maxParticipants: undefined,
        });
    });
});

describe('parseRoomStatePayload', () => {
    it('returns typed object for valid payload', () => {
        const raw = {
            hostCid: 'h',
            participants: [{ cid: 'a' }, { cid: 'b', joinedAt: 200 }],
            maxParticipants: 2,
        };
        expect(parseRoomStatePayload(raw)).toEqual({
            hostCid: 'h',
            participants: [
                { cid: 'a', joinedAt: undefined },
                { cid: 'b', joinedAt: 200 },
            ],
            maxParticipants: 2,
        });
    });

    it('returns null for undefined input', () => {
        expect(parseRoomStatePayload(undefined)).toBeNull();
    });

    it('returns null when participants is missing', () => {
        expect(parseRoomStatePayload({ hostCid: 'h' })).toBeNull();
    });

    it('returns null when participants is not an array', () => {
        expect(parseRoomStatePayload({ hostCid: 'h', participants: {} })).toBeNull();
    });

    it('ignores extra fields', () => {
        const result = parseRoomStatePayload({
            hostCid: null,
            participants: [],
            bonus: true,
        });
        expect(result).toEqual({
            hostCid: null,
            participants: [],
            maxParticipants: undefined,
        });
    });

    it('parses participant audioEnabled and videoEnabled', () => {
        const result = parseRoomStatePayload({
            hostCid: 'h',
            participants: [
                { cid: 'a', audioEnabled: true, videoEnabled: false },
                { cid: 'b', audioEnabled: false, videoEnabled: true },
                { cid: 'c' },
            ],
        });
        expect(result?.participants).toEqual([
            { cid: 'a', joinedAt: undefined, audioEnabled: true, videoEnabled: false },
            { cid: 'b', joinedAt: undefined, audioEnabled: false, videoEnabled: true },
            { cid: 'c', joinedAt: undefined },
        ]);
    });
});

describe('parseErrorPayload', () => {
    it('returns typed object for valid payload', () => {
        expect(parseErrorPayload({ code: 'FULL', message: 'Room full' })).toEqual({
            code: 'FULL',
            message: 'Room full',
        });
    });

    it('returns null for undefined input', () => {
        expect(parseErrorPayload(undefined)).toBeNull();
    });

    it('returns null when message is missing', () => {
        expect(parseErrorPayload({ code: 'FULL' })).toBeNull();
    });

    it('returns null when message has wrong type', () => {
        expect(parseErrorPayload({ code: 'FULL', message: 42 })).toBeNull();
    });

    it('defaults code to UNKNOWN when not a string', () => {
        const result = parseErrorPayload({ code: 123, message: 'oops' });
        expect(result).toEqual({ code: 'UNKNOWN', message: 'oops' });
    });

    it('ignores extra fields', () => {
        const result = parseErrorPayload({ code: 'X', message: 'Y', extra: true });
        expect(result).toEqual({ code: 'X', message: 'Y' });
    });
});

describe('parseTurnRefreshedPayload', () => {
    it('returns typed object for valid payload', () => {
        expect(parseTurnRefreshedPayload({ turnToken: 'tok', turnTokenTTLMs: 30000 })).toEqual({
            turnToken: 'tok',
            turnTokenTTLMs: 30000,
        });
    });

    it('returns null for undefined input', () => {
        expect(parseTurnRefreshedPayload(undefined)).toBeNull();
    });

    it('returns null when turnToken is missing', () => {
        expect(parseTurnRefreshedPayload({ turnTokenTTLMs: 30000 })).toBeNull();
    });

    it('returns null when turnToken has wrong type', () => {
        expect(parseTurnRefreshedPayload({ turnToken: 42 })).toBeNull();
    });

    it('omits turnTokenTTLMs when it has wrong type', () => {
        expect(parseTurnRefreshedPayload({ turnToken: 'tok', turnTokenTTLMs: 'bad' })).toEqual({
            turnToken: 'tok',
            turnTokenTTLMs: undefined,
        });
    });

    it('ignores extra fields', () => {
        expect(parseTurnRefreshedPayload({ turnToken: 't', extra: 1 })).toEqual({
            turnToken: 't',
            turnTokenTTLMs: undefined,
        });
    });
});

describe('parseOfferPayload', () => {
    it('returns typed object for valid payload', () => {
        expect(parseOfferPayload({ from: 'a', sdp: 'v=0\r\n', timestamp: 1234 })).toEqual({
            from: 'a',
            sdp: 'v=0\r\n',
            timestamp: 1234,
        });
    });

    it('returns null for undefined input', () => {
        expect(parseOfferPayload(undefined)).toBeNull();
    });

    it('returns null when from is missing', () => {
        expect(parseOfferPayload({ sdp: 'v=0\r\n' })).toBeNull();
    });

    it('returns null when sdp is missing', () => {
        expect(parseOfferPayload({ from: 'a' })).toBeNull();
    });

    it('returns null when from has wrong type', () => {
        expect(parseOfferPayload({ from: 42, sdp: 'v=0\r\n' })).toBeNull();
    });

    it('returns null when sdp has wrong type', () => {
        expect(parseOfferPayload({ from: 'a', sdp: 123 })).toBeNull();
    });

    it('omits timestamp when it has wrong type', () => {
        expect(parseOfferPayload({ from: 'a', sdp: 's', timestamp: 'bad' })).toEqual({
            from: 'a',
            sdp: 's',
            timestamp: undefined,
        });
    });

    it('ignores extra fields', () => {
        expect(parseOfferPayload({ from: 'a', sdp: 's', extra: true })).toEqual({
            from: 'a',
            sdp: 's',
            timestamp: undefined,
        });
    });
});

describe('parseAnswerPayload', () => {
    it('returns typed object for valid payload', () => {
        expect(parseAnswerPayload({ from: 'b', sdp: 'v=0\r\n' })).toEqual({
            from: 'b',
            sdp: 'v=0\r\n',
        });
    });

    it('returns null for undefined input', () => {
        expect(parseAnswerPayload(undefined)).toBeNull();
    });

    it('returns null when from is missing', () => {
        expect(parseAnswerPayload({ sdp: 'v=0\r\n' })).toBeNull();
    });

    it('returns null when sdp is missing', () => {
        expect(parseAnswerPayload({ from: 'b' })).toBeNull();
    });

    it('returns null when from has wrong type', () => {
        expect(parseAnswerPayload({ from: 42, sdp: 'v=0\r\n' })).toBeNull();
    });

    it('returns null when sdp has wrong type', () => {
        expect(parseAnswerPayload({ from: 'b', sdp: null })).toBeNull();
    });

    it('ignores extra fields', () => {
        expect(parseAnswerPayload({ from: 'b', sdp: 's', bonus: 1 })).toEqual({
            from: 'b',
            sdp: 's',
        });
    });
});

describe('parseIceCandidatePayload', () => {
    const validCandidate = { candidate: 'candidate:abc', sdpMid: '0', sdpMLineIndex: 0 };

    it('returns typed object for valid payload', () => {
        expect(parseIceCandidatePayload({ from: 'c', candidate: validCandidate })).toEqual({
            from: 'c',
            candidate: validCandidate,
        });
    });

    it('returns null for undefined input', () => {
        expect(parseIceCandidatePayload(undefined)).toBeNull();
    });

    it('returns null when from is missing', () => {
        expect(parseIceCandidatePayload({ candidate: validCandidate })).toBeNull();
    });

    it('returns null when candidate is missing', () => {
        expect(parseIceCandidatePayload({ from: 'c' })).toBeNull();
    });

    it('returns null when from has wrong type', () => {
        expect(parseIceCandidatePayload({ from: 42, candidate: validCandidate })).toBeNull();
    });

    it('returns null when candidate is not an object', () => {
        expect(parseIceCandidatePayload({ from: 'c', candidate: 'bad' })).toBeNull();
    });

    it('returns null when candidate is null', () => {
        expect(parseIceCandidatePayload({ from: 'c', candidate: null })).toBeNull();
    });

    it('ignores extra fields', () => {
        expect(parseIceCandidatePayload({ from: 'c', candidate: validCandidate, extra: true })).toEqual({
            from: 'c',
            candidate: validCandidate,
        });
    });
});

describe('parseJoinedPayload — Phase 1 fields', () => {
    it('parses reconnect outcome and epoch', () => {
        const result = parseJoinedPayload({
            hostCid: 'h',
            participants: [{ cid: 'a' }],
            reconnect: 'recovered',
            epoch: 7,
            reconnectTokenTTLMs: 600_000,
        });
        expect(result?.reconnect).toBe('recovered');
        expect(result?.epoch).toBe(7);
        expect(result?.reconnectTokenTTLMs).toBe(600_000);
    });

    it('rejects unknown reconnect outcome values', () => {
        const result = parseJoinedPayload({
            hostCid: 'h',
            participants: [{ cid: 'a' }],
            reconnect: 'banana',
        });
        expect(result?.reconnect).toBeUndefined();
    });

    it('parses participant content state', () => {
        const result = parseJoinedPayload({
            hostCid: 'h',
            participants: [
                {
                    cid: 'a',
                    contentState: { active: true, contentType: 'screen', updatedAtMs: 1000, epoch: 3 },
                },
                {
                    cid: 'b',
                    contentState: { active: false },
                },
                {
                    cid: 'c',
                    contentState: { active: 'nope' },
                },
            ],
        });
        expect(result?.participants[0].contentState).toEqual({
            active: true,
            contentType: 'screen',
            updatedAtMs: 1000,
            epoch: 3,
        });
        expect(result?.participants[1].contentState).toEqual({
            active: false,
            contentType: undefined,
            updatedAtMs: undefined,
            epoch: undefined,
        });
        expect(result?.participants[2].contentState).toBeUndefined();
    });
});

describe('parseRoomStatePayload — Phase 1 fields', () => {
    it('parses epoch and content state', () => {
        const result = parseRoomStatePayload({
            hostCid: 'h',
            participants: [
                { cid: 'a', contentState: { active: true, contentType: 'screen' } },
            ],
            epoch: 12,
        });
        expect(result?.epoch).toBe(12);
        expect(result?.participants[0].contentState?.active).toBe(true);
        expect(result?.participants[0].contentState?.contentType).toBe('screen');
    });
});

describe('parseErrorPayload — Phase 1 fields', () => {
    it('parses reason field', () => {
        expect(parseErrorPayload({ code: 'ROOM_ENDED', message: 'gone', reason: 'ended_by_host' })).toEqual({
            code: 'ROOM_ENDED',
            message: 'gone',
            reason: 'ended_by_host',
        });
    });

    it('omits reason when not a string', () => {
        const result = parseErrorPayload({ code: 'X', message: 'y', reason: 42 });
        expect(result?.reason).toBeUndefined();
    });
});

describe('parseRelayFailedPayload', () => {
    it('parses target_suspended payload', () => {
        expect(parseRelayFailedPayload({ reason: 'target_suspended', targets: ['C-1'], of: 'offer' })).toEqual({
            reason: 'target_suspended',
            targets: ['C-1'],
            of: 'offer',
        });
    });

    it('returns null on empty targets list', () => {
        expect(parseRelayFailedPayload({ reason: 'target_suspended', targets: [] })).toBeNull();
    });

    it('returns null when reason missing', () => {
        expect(parseRelayFailedPayload({ targets: ['C-1'] })).toBeNull();
    });

    it('drops non-string entries from targets', () => {
        const result = parseRelayFailedPayload({ reason: 'target_suspended', targets: ['C-1', 7, ''] });
        expect(result?.targets).toEqual(['C-1']);
    });
});

describe('parseNegotiationDirtyPayload', () => {
    it('parses well-formed payload', () => {
        expect(parseNegotiationDirtyPayload({ with: 'C-2' })).toEqual({ with: 'C-2' });
    });

    it('returns null when `with` missing', () => {
        expect(parseNegotiationDirtyPayload({})).toBeNull();
    });

    it('returns null when `with` empty', () => {
        expect(parseNegotiationDirtyPayload({ with: '' })).toBeNull();
    });
});
