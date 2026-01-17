export type RoomState = {
    hostCid: string | null;
    participants: { cid: string; joinedAt?: number }[];
};

export type SignalingMessage = {
    v: number;
    type: string;
    rid?: string;
    sid?: string;
    cid?: string;
    to?: string;
    payload?: any;
};
