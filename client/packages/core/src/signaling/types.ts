export type ParticipantConnectionStatus = 'active' | 'suspended';

export type RoomParticipant = {
    cid: string;
    joinedAt?: number;
    displayName?: string;
    /** Host-supplied stable identity — opaque to the SDK, surfaced for avatar lookup. */
    peerId?: string;
    audioEnabled?: boolean;
    videoEnabled?: boolean;
    // Absent = active. 'suspended' means the server is holding the slot
    // open across a signaling drop — peers MUST keep the existing peer
    // connection alive until the participant returns or is fully removed.
    connectionStatus?: ParticipantConnectionStatus;
};

export type RoomState = {
    hostCid: string | null;
    participants: RoomParticipant[];
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
