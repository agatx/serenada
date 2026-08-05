import { describe, expect, it } from 'vitest';
import { CallStatsCollector } from '../../src/media/callStats.js';

/** Minimal fake RTCPeerConnection that returns a fixed stats report. */
class FakeStatsPeerConnection {
    readonly localDescription: RTCSessionDescription | null;
    readonly remoteDescription: RTCSessionDescription | null;

    constructor(
        private readonly report: RTCStatsReport,
        localDescription: RTCSessionDescriptionInit | null = null,
        remoteDescription: RTCSessionDescriptionInit | null = null,
    ) {
        this.localDescription = localDescription as RTCSessionDescription | null;
        this.remoteDescription = remoteDescription as RTCSessionDescription | null;
    }
    async getStats(): Promise<RTCStatsReport> {
        return this.report;
    }
}

function makeReport(stats: Array<Record<string, unknown>>): RTCStatsReport {
    return new Map<string, RTCStats>(
        stats.map((s) => [s.id as string, s as unknown as RTCStats]),
    ) as RTCStatsReport;
}

/**
 * Drive the private `poll()` once with the given report and return the
 * collector's emitted snapshot. `poll` is private, so we reach it via the
 * collector instance — start() schedules a timer, but we invoke poll
 * directly through the bracket accessor to keep the test synchronous.
 */
async function collectOnce(
    report: RTCStatsReport,
    localDescription: RTCSessionDescriptionInit | null = null,
    remoteDescription: RTCSessionDescriptionInit | null = null,
): Promise<ReturnType<CallStatsCollector['stats']['valueOf']> | null> {
    const collector = new CallStatsCollector();
    const pc = new FakeStatsPeerConnection(report, localDescription, remoteDescription) as unknown as RTCPeerConnection;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    await (collector as any).poll([pc]);
    return collector.stats;
}

describe('CallStatsCollector counter surface', () => {
    it('resolves active inbound and outbound codec MIME types from RTP codec references', async () => {
        const report = makeReport([
            { id: 'audio-red', type: 'codec', mimeType: 'audio/red' },
            { id: 'audio-opus', type: 'codec', mimeType: 'audio/opus' },
            { id: 'video-vp8', type: 'codec', mimeType: 'video/VP8' },
            { id: 'audio-in', type: 'inbound-rtp', kind: 'audio', codecId: 'audio-red', bytesReceived: 1000 },
            { id: 'audio-out', type: 'outbound-rtp', kind: 'audio', codecId: 'audio-opus', bytesSent: 1000 },
            { id: 'video-in', type: 'inbound-rtp', kind: 'video', codecId: 'video-vp8', bytesReceived: 2000 },
            { id: 'video-out', type: 'outbound-rtp', kind: 'video', codecId: 'video-vp8', bytesSent: 2000 },
        ]);

        const stats = await collectOnce(report);

        expect(stats!.audioRxCodec).toBe('audio/red');
        expect(stats!.audioTxCodec).toBe('audio/opus');
        expect(stats!.videoRxCodec).toBe('video/VP8');
        expect(stats!.videoTxCodec).toBe('video/VP8');
    });

    it('reports negotiated RED when RTP stats expose its inner Opus codec', async () => {
        const report = makeReport([
            { id: 'audio-opus', type: 'codec', mimeType: 'audio/opus' },
            { id: 'audio-in', type: 'inbound-rtp', kind: 'audio', codecId: 'audio-opus', bytesReceived: 1000 },
            { id: 'audio-out', type: 'outbound-rtp', kind: 'audio', codecId: 'audio-opus', bytesSent: 1000 },
        ]);
        const answer = {
            type: 'answer' as const,
            sdp: [
                'v=0',
                'm=audio 9 UDP/TLS/RTP/SAVPF 63 111',
                'a=rtpmap:63 red/48000/2',
                'a=rtpmap:111 opus/48000/2',
                'm=video 9 UDP/TLS/RTP/SAVPF 96',
            ].join('\r\n'),
        };

        const stats = await collectOnce(report, null, answer);

        expect(stats!.audioRxCodec).toBe('audio/red');
        expect(stats!.audioTxCodec).toBe('audio/red');
    });

    it('keeps Opus when the negotiated answer does not prefer RED', async () => {
        const report = makeReport([
            { id: 'audio-opus', type: 'codec', mimeType: 'audio/opus' },
            { id: 'audio-in', type: 'inbound-rtp', kind: 'audio', codecId: 'audio-opus', bytesReceived: 1000 },
        ]);
        const answer = {
            type: 'answer' as const,
            sdp: [
                'v=0',
                'm=audio 9 UDP/TLS/RTP/SAVPF 111 63',
                'a=rtpmap:111 opus/48000/2',
                'a=rtpmap:63 red/48000/2',
            ].join('\r\n'),
        };

        const stats = await collectOnce(report, answer);

        expect(stats!.audioRxCodec).toBe('audio/opus');
    });

    it('surfaces framesDecoded/framesDropped and audio packet counters, summed across slots', async () => {
        const report = makeReport([
            {
                id: 'video-in', type: 'inbound-rtp', kind: 'video',
                packetsReceived: 1000, packetsLost: 5, bytesReceived: 50000,
                framesDecoded: 600, framesDropped: 12,
            },
            {
                id: 'audio-in', type: 'inbound-rtp', kind: 'audio',
                packetsReceived: 2000, packetsLost: 30, bytesReceived: 8000,
            },
        ]);
        const stats = await collectOnce(report);
        expect(stats).not.toBeNull();
        expect(stats!.videoFramesDecoded).toBe(600);
        expect(stats!.videoFramesDropped).toBe(12);
        expect(stats!.audioPacketsLost).toBe(30);
        expect(stats!.audioPacketsReceived).toBe(2000);
    });

    it('surfaces null (unknown) for a kind with no inbound-rtp stat, never a fake 0', async () => {
        // Audio inbound present, but no video inbound-rtp at all.
        const report = makeReport([
            { id: 'audio-in', type: 'inbound-rtp', kind: 'audio', packetsReceived: 100, packetsLost: 0, bytesReceived: 1000 },
        ]);
        const stats = await collectOnce(report);
        // Audio present → real values (including a genuine 0 loss).
        expect(stats!.audioPacketsLost).toBe(0);
        expect(stats!.audioPacketsReceived).toBe(100);
        // No video inbound-rtp → null, not 0.
        expect(stats!.videoFramesDecoded).toBeNull();
        expect(stats!.videoFramesDropped).toBeNull();
    });

    it('surfaces null audio counters when there is no inbound-rtp audio stat', async () => {
        const report = makeReport([
            { id: 'video-in', type: 'inbound-rtp', kind: 'video', framesDecoded: 10, framesDropped: 1, bytesReceived: 2000 },
        ]);
        const stats = await collectOnce(report);
        expect(stats!.audioPacketsLost).toBeNull();
        expect(stats!.audioPacketsReceived).toBeNull();
        expect(stats!.videoFramesDecoded).toBe(10);
        expect(stats!.videoFramesDropped).toBe(1);
    });

    // #4 — per-FIELD presence: a row exists but omits one counter member.
    it('surfaces null for a counter member the inbound-rtp row omits, not a fake 0', async () => {
        const report = makeReport([
            // Video row present with framesDecoded but NO framesDropped (older impl).
            { id: 'video-in', type: 'inbound-rtp', kind: 'video', framesDecoded: 42, bytesReceived: 2000 },
            // Audio row present with packetsReceived but NO packetsLost.
            { id: 'audio-in', type: 'inbound-rtp', kind: 'audio', packetsReceived: 500, bytesReceived: 1000 },
        ]);
        const stats = await collectOnce(report);
        expect(stats!.videoFramesDecoded).toBe(42);
        expect(stats!.videoFramesDropped).toBeNull(); // member absent → null, not 0
        expect(stats!.audioPacketsReceived).toBe(500);
        expect(stats!.audioPacketsLost).toBeNull(); // member absent → null, not 0
    });
});
