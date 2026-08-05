import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import type { CallStats } from '@agatx/serenada-core';
import { DebugPanel } from '../../src/components/DebugPanel.js';

function makeStats(): CallStats {
    return {
        transportPath: null, rttMs: null, availableOutgoingKbps: null,
        audioRxCodec: 'audio/red', audioTxCodec: 'audio/opus',
        audioRxPacketLossPct: null, audioTxPacketLossPct: null, audioJitterMs: null,
        audioPlayoutDelayMs: null, audioConcealedPct: null, audioRxKbps: null, audioTxKbps: null,
        videoRxCodec: 'video/VP8', videoTxCodec: 'video/H264',
        videoRxPacketLossPct: null, videoTxPacketLossPct: null, videoRxKbps: null, videoTxKbps: null,
        videoFps: null, videoResolution: null, videoFreezeCount60s: null, videoFreezeDuration60s: null,
        videoRetransmitPct: null, videoFramesDecoded: null, videoFramesDropped: null,
        audioPacketsLost: null, audioPacketsReceived: null, updatedAtMs: 1,
    };
}

describe('DebugPanel codec metrics', () => {
    it('shows inbound and outbound codec MIME types for audio and video', () => {
        const markup = renderToStaticMarkup(createElement(DebugPanel, { stats: makeStats() }));

        expect(markup).toContain('audio/red / audio/opus');
        expect(markup).toContain('video/VP8 / video/H264');
    });
});
