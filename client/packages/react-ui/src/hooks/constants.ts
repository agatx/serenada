import type { CallState } from '@serenada/core';

export const IDLE_STATE: CallState = {
    phase: 'idle',
    roomId: null,
    roomUrl: null,
    localParticipant: null,
    remoteParticipants: [],
    connectionStatus: 'connected',
    activeTransport: null,
    requiredPermissions: null,
    error: null,
};

export const EMPTY_STREAMS = new Map<string, MediaStream>();
