import type { SerenadaConfig, CreateRoomResult, SerenadaSessionHandle } from './types.js';
import { SerenadaSession } from './SerenadaSession.js';
import { createRoomId } from './api/roomApi.js';
import { buildRoomUrl } from './serverUrls.js';

export class SerenadaCore {
    private config: SerenadaConfig;

    constructor(config: SerenadaConfig) {
        this.config = config;
    }

    join(url: string): SerenadaSessionHandle;
    join(options: { roomId: string }): SerenadaSessionHandle;
    join(urlOrOptions: string | { roomId: string }): SerenadaSessionHandle {
        if (typeof urlOrOptions === 'string') {
            const roomId = this.parseRoomIdFromUrl(urlOrOptions);
            return new SerenadaSession(this.config, roomId, urlOrOptions);
        }
        const roomUrl = buildRoomUrl(this.config.serverHost, urlOrOptions.roomId);
        return new SerenadaSession(this.config, urlOrOptions.roomId, roomUrl);
    }

    async createRoom(): Promise<CreateRoomResult> {
        const roomId = await createRoomId(this.config.serverHost);
        const url = buildRoomUrl(this.config.serverHost, roomId);
        const session = new SerenadaSession(this.config, roomId, url);
        return { url, roomId, session };
    }

    private parseRoomIdFromUrl(url: string): string {
        try {
            const parsed = new URL(url);
            const parts = parsed.pathname.split('/');
            const callIndex = parts.indexOf('call');
            if (callIndex !== -1 && parts[callIndex + 1]) {
                return parts[callIndex + 1];
            }
            // Fallback: last path segment
            return parts[parts.length - 1] || url;
        } catch {
            return url;
        }
    }
}
