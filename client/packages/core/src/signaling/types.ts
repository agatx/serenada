export type RoomParticipant = {
    cid: string;
    joinedAt?: number;
    displayName?: string;
    audioEnabled?: boolean;
    videoEnabled?: boolean;
};

export type RoomState = {
    hostCid: string | null;
    participants: RoomParticipant[];
    maxParticipants?: number;
    mode?: 'video' | 'voice';
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
