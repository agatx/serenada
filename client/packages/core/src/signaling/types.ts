export type RoomState = {
    hostCid: string | null;
    participants: { cid: string; joinedAt?: number }[];
    maxParticipants?: number;
};

export type SignalingMessage = {
    v: number;
    type: string;
    rid?: string;
    sid?: string;
    cid?: string;
    to?: string;
    payload?: Record<string, unknown>;
};

export type {
    JoinedPayload,
    ErrorPayload,
    TurnRefreshedPayload,
    OfferPayload,
    AnswerPayload,
    IceCandidatePayload,
} from './payloads.js';
