import type { ParticipantContentState, ReconnectOutcome, RoomParticipant, RoomState } from './types.js';

export interface JoinedPayload {
    hostCid: string | null;
    participants: RoomParticipant[];
    turnToken?: string;
    turnTokenTTLMs?: number;
    reconnectToken?: string;
    /**
     * How long (ms) the server is willing to honor `reconnectToken`. SDKs
     * that persist the token across launches should clear it once this
     * window has elapsed.
     */
    reconnectTokenTTLMs?: number;
    maxParticipants?: number;
    /** Server-reported room state epoch. Monotonic. */
    epoch?: number;
    /**
     * Disposition of this join. SDKs use this to decide whether to keep
     * media-active peer connections (`reattached`/`recovered`) or treat the
     * call as ground-up new (`fresh`).
     */
    reconnect?: ReconnectOutcome;
}

export interface ErrorPayload {
    code: string;
    message: string;
    /** Optional reason for terminal codes (e.g. ROOM_ENDED → "ended_by_host"). */
    reason?: string;
}

/**
 * Server tells the sender that an offer/answer/ice could not be delivered
 * because the target was suspended. The SDK should suppress further
 * negotiation toward those CIDs and wait for a `negotiation_dirty` message
 * after the peer reattaches.
 */
export interface RelayFailedPayload {
    reason: 'target_suspended' | (string & {});
    targets: string[];
    of?: string;
}

/**
 * Server tells the sender that a previously-suspended peer has reattached
 * AND that the sender had pending negotiation traffic to it during the
 * suspension. The SDK should perform glare-safe fresh negotiation /
 * ICE restart for the named CID, NOT replay the original SDP.
 */
export interface NegotiationDirtyPayload {
    with: string;
}

export interface TurnRefreshedPayload {
    turnToken: string;
    turnTokenTTLMs?: number;
}

export interface ReconnectTokenRefreshedPayload {
    reconnectToken: string;
    reconnectTokenTTLMs?: number;
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

function parseContentState(raw: unknown): ParticipantContentState | undefined {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
    const rec = raw as Record<string, unknown>;
    if (typeof rec.active !== 'boolean') return undefined;
    return {
        active: rec.active,
        contentType: typeof rec.contentType === 'string' && rec.contentType !== '' ? rec.contentType : undefined,
        updatedAtMs: typeof rec.updatedAtMs === 'number' ? rec.updatedAtMs : undefined,
        epoch: typeof rec.epoch === 'number' ? rec.epoch : undefined,
    };
}

function parseParticipants(raw: unknown): RoomParticipant[] | null {
    if (!Array.isArray(raw)) return null;
    const result: RoomParticipant[] = [];
    for (const p of raw) {
        if (!p || typeof p !== 'object' || Array.isArray(p)) continue;
        const rec = p as Record<string, unknown>;
        if (typeof rec.cid !== 'string' || rec.cid.trim() === '') continue;
        result.push({
            cid: rec.cid,
            joinedAt: typeof rec.joinedAt === 'number' ? rec.joinedAt : undefined,
            displayName: typeof rec.displayName === 'string' && rec.displayName.trim() !== '' ? rec.displayName : undefined,
            peerId: typeof rec.peerId === 'string' && rec.peerId.trim() !== '' ? rec.peerId : undefined,
            audioEnabled: typeof rec.audioEnabled === 'boolean' ? rec.audioEnabled : undefined,
            videoEnabled: typeof rec.videoEnabled === 'boolean' ? rec.videoEnabled : undefined,
            // Only the recognized status value is forwarded; absent/unknown
            // is left undefined and treated as active downstream.
            connectionStatus: rec.connectionStatus === 'suspended' ? 'suspended' : undefined,
            contentState: parseContentState(rec.contentState),
        });
    }
    return result;
}

function parseReconnectOutcome(raw: unknown): ReconnectOutcome | undefined {
    if (raw === 'fresh' || raw === 'reattached' || raw === 'recovered') return raw;
    return undefined;
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
        reconnectTokenTTLMs: typeof raw.reconnectTokenTTLMs === 'number' ? raw.reconnectTokenTTLMs : undefined,
        maxParticipants: typeof raw.maxParticipants === 'number' ? raw.maxParticipants : undefined,
        epoch: typeof raw.epoch === 'number' ? raw.epoch : undefined,
        reconnect: parseReconnectOutcome(raw.reconnect),
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
        epoch: typeof raw.epoch === 'number' ? raw.epoch : undefined,
    };
}

export function parseErrorPayload(raw: Record<string, unknown> | undefined): ErrorPayload | null {
    if (!raw) return null;
    if (typeof raw.message !== 'string') return null;
    return {
        code: typeof raw.code === 'string' ? raw.code : 'UNKNOWN',
        message: raw.message,
        reason: typeof raw.reason === 'string' && raw.reason !== '' ? raw.reason : undefined,
    };
}

export function parseRelayFailedPayload(raw: Record<string, unknown> | undefined): RelayFailedPayload | null {
    if (!raw) return null;
    if (typeof raw.reason !== 'string') return null;
    if (!Array.isArray(raw.targets)) return null;
    const targets = raw.targets.filter((t): t is string => typeof t === 'string' && t !== '');
    if (targets.length === 0) return null;
    return {
        reason: raw.reason as RelayFailedPayload['reason'],
        targets,
        of: typeof raw.of === 'string' && raw.of !== '' ? raw.of : undefined,
    };
}

export function parseNegotiationDirtyPayload(raw: Record<string, unknown> | undefined): NegotiationDirtyPayload | null {
    if (!raw) return null;
    if (typeof raw.with !== 'string' || raw.with === '') return null;
    return { with: raw.with };
}

export function parseTurnRefreshedPayload(raw: Record<string, unknown> | undefined): TurnRefreshedPayload | null {
    if (!raw) return null;
    if (typeof raw.turnToken !== 'string') return null;
    return {
        turnToken: raw.turnToken,
        turnTokenTTLMs: typeof raw.turnTokenTTLMs === 'number' ? raw.turnTokenTTLMs : undefined,
    };
}

export function parseReconnectTokenRefreshedPayload(raw: Record<string, unknown> | undefined): ReconnectTokenRefreshedPayload | null {
    if (!raw) return null;
    if (typeof raw.reconnectToken !== 'string') return null;
    return {
        reconnectToken: raw.reconnectToken,
        reconnectTokenTTLMs: typeof raw.reconnectTokenTTLMs === 'number' ? raw.reconnectTokenTTLMs : undefined,
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
