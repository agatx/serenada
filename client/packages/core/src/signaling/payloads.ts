import type { RoomState } from './types.js';

export interface JoinedPayload {
    hostCid: string | null;
    participants: Array<{ cid: string; joinedAt?: number }>;
    turnToken?: string;
    turnTokenTTLMs?: number;
    reconnectToken?: string;
    maxParticipants?: number;
}

export interface ErrorPayload {
    code: string;
    message: string;
}

export interface TurnRefreshedPayload {
    turnToken: string;
    turnTokenTTLMs?: number;
}

export interface OfferPayload {
    from: string;
    sdp: string;
    timestamp?: number;
}

export interface AnswerPayload {
    from: string;
    sdp: string;
}

export interface IceCandidatePayload {
    from: string;
    candidate: RTCIceCandidateInit;
}

function parseParticipants(raw: unknown): Array<{ cid: string; joinedAt?: number }> | null {
    if (!Array.isArray(raw)) return null;
    const result: Array<{ cid: string; joinedAt?: number }> = [];
    for (const p of raw) {
        if (!p || typeof p !== 'object' || Array.isArray(p)) continue;
        const rec = p as Record<string, unknown>;
        if (typeof rec.cid !== 'string' || rec.cid.trim() === '') continue;
        result.push({
            cid: rec.cid,
            joinedAt: typeof rec.joinedAt === 'number' ? rec.joinedAt : undefined,
        });
    }
    return result;
}

export function parseJoinedPayload(raw: Record<string, unknown> | undefined): JoinedPayload | null {
    if (!raw) return null;
    const participants = parseParticipants(raw.participants);
    if (!participants) return null;
    return {
        hostCid: typeof raw.hostCid === 'string' ? raw.hostCid : null,
        participants,
        turnToken: typeof raw.turnToken === 'string' ? raw.turnToken : undefined,
        turnTokenTTLMs: typeof raw.turnTokenTTLMs === 'number' ? raw.turnTokenTTLMs : undefined,
        reconnectToken: typeof raw.reconnectToken === 'string' ? raw.reconnectToken : undefined,
        maxParticipants: typeof raw.maxParticipants === 'number' ? raw.maxParticipants : undefined,
    };
}

export function parseRoomStatePayload(raw: Record<string, unknown> | undefined): RoomState | null {
    if (!raw) return null;
    const participants = parseParticipants(raw.participants);
    if (!participants) return null;
    return {
        hostCid: typeof raw.hostCid === 'string' ? raw.hostCid : null,
        participants,
        maxParticipants: typeof raw.maxParticipants === 'number' ? raw.maxParticipants : undefined,
    };
}

export function parseErrorPayload(raw: Record<string, unknown> | undefined): ErrorPayload | null {
    if (!raw) return null;
    if (typeof raw.message !== 'string') return null;
    return {
        code: typeof raw.code === 'string' ? raw.code : 'UNKNOWN',
        message: raw.message,
    };
}

export function parseTurnRefreshedPayload(raw: Record<string, unknown> | undefined): TurnRefreshedPayload | null {
    if (!raw) return null;
    if (typeof raw.turnToken !== 'string') return null;
    return {
        turnToken: raw.turnToken,
        turnTokenTTLMs: typeof raw.turnTokenTTLMs === 'number' ? raw.turnTokenTTLMs : undefined,
    };
}

export function parseOfferPayload(raw: Record<string, unknown> | undefined): OfferPayload | null {
    if (!raw) return null;
    if (typeof raw.from !== 'string' || raw.from === '') return null;
    if (typeof raw.sdp !== 'string' || raw.sdp === '') return null;
    return {
        from: raw.from,
        sdp: raw.sdp,
        timestamp: typeof raw.timestamp === 'number' ? raw.timestamp : undefined,
    };
}

export function parseAnswerPayload(raw: Record<string, unknown> | undefined): AnswerPayload | null {
    if (!raw) return null;
    if (typeof raw.from !== 'string' || raw.from === '') return null;
    if (typeof raw.sdp !== 'string' || raw.sdp === '') return null;
    return {
        from: raw.from,
        sdp: raw.sdp,
    };
}

export function parseIceCandidatePayload(raw: Record<string, unknown> | undefined): IceCandidatePayload | null {
    if (!raw) return null;
    if (typeof raw.from !== 'string' || raw.from === '') return null;
    const candidate = raw.candidate;
    if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) return null;
    const candObj = candidate as Record<string, unknown>;
    if (typeof candObj.candidate !== 'string') return null;
    return {
        from: raw.from,
        candidate: candidate as RTCIceCandidateInit,
    };
}
