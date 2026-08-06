import { describe, expect, it } from 'vitest';
import { enableOpusDtxInSdp } from '../../src/media/opusDtxSdp.js';

describe('enableOpusDtxInSdp', () => {
    it('adds DTX to the Opus fmtp line while preserving CRLF framing', () => {
        const sdp = [
            'v=0',
            'm=audio 9 UDP/TLS/RTP/SAVPF 63 111',
            'a=rtpmap:63 red/48000/2',
            'a=fmtp:63 111/111',
            'a=rtpmap:111 opus/48000/2',
            'a=fmtp:111 minptime=10;useinbandfec=1',
            'm=video 9 UDP/TLS/RTP/SAVPF 96',
            'a=rtpmap:96 VP8/90000',
            '',
        ].join('\r\n');

        expect(enableOpusDtxInSdp(sdp)).toBe(sdp.replace(
            'a=fmtp:111 minptime=10;useinbandfec=1',
            'a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1',
        ));
    });

    it('replaces an explicit disabled value and is idempotent', () => {
        const sdp = 'm=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=0;minptime=10\n';
        const enabled = enableOpusDtxInSdp(sdp);

        expect(enabled).toContain('a=fmtp:111 usedtx=1;minptime=10');
        expect(enableOpusDtxInSdp(enabled)).toBe(enabled);
    });

    it('creates an Opus fmtp line when one is absent', () => {
        const sdp = 'm=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\n';

        expect(enableOpusDtxInSdp(sdp)).toBe(
            'm=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=1\n',
        );
    });
});
