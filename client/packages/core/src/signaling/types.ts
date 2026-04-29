export type ParticipantConnectionStatus = 'active' | 'suspended';

/**
 * Latest ephemeral content metadata for a participant (screen share, content
 * camera mode, etc.). Persisted on the server's participant record so a peer
 * reconnecting after a suspension reconstructs its UI without waiting for the
 * sender to toggle again.
 */
export type ParticipantContentState = {
    active: boolean;
    contentType?: string;
    updatedAtMs?: number;
    epoch?: number;
};

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
    contentState?: ParticipantContentState;
};

export type RoomState = {
    hostCid: string | null;
    participants: RoomParticipant[];
    maxParticipants?: number;
    /**
     * Monotonic counter advanced by the server on every membership-mutating
     * operation. SDKs gate ICE restart on receiving an authoritative
     * post-reconnect room_state with epoch >= the last seen value, instead
     * of acting on a stale in-memory peer map.
     */
    epoch?: number;
};

/**
 * Outcome reported by the server in `joined.reconnect`. Drives whether the
 * SDK preserves media-active peer connections, schedules dirty-pair
 * renegotiation, or starts ground-up.
 */
export type ReconnectOutcome = 'fresh' | 'reattached' | 'recovered';

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
