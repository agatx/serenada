import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { TestSessionHarness } from './helpers/TestSessionHarness.js';
import type { FakeMediaStreamTrack } from './helpers/FakeMediaEngine.js';

// SerenadaSession uses `window.setTimeout` / `window.clearTimeout`. In Node (no
// jsdom) `window` is undefined; provide a shim that delegates dynamically so
// vi.useFakeTimers() patches are picked up (mirrors SerenadaSession.test.ts).
if (typeof globalThis.window === 'undefined') {
    const handler: ProxyHandler<Record<string, unknown>> = {
        get(_target, prop) {
            if (prop === 'setTimeout') return globalThis.setTimeout.bind(globalThis);
            if (prop === 'clearTimeout') return globalThis.clearTimeout.bind(globalThis);
            if (prop === 'setInterval') return globalThis.setInterval.bind(globalThis);
            if (prop === 'clearInterval') return globalThis.clearInterval.bind(globalThis);
            return undefined;
        },
    };
    (globalThis as Record<string, unknown>).window = new Proxy({}, handler);
}
if (typeof globalThis.navigator === 'undefined') {
    (globalThis as Record<string, unknown>).navigator = {};
}

/** Last `participant_media_state` payload broadcast by the session. */
function lastMediaStateBroadcast(harness: TestSessionHarness): Record<string, unknown> | undefined {
    const calls = harness.signaling.broadcastCalls.filter((c) => c.type === 'participant_media_state');
    return calls.at(-1)?.payload as Record<string, unknown> | undefined;
}

describe('SerenadaSession hold/resume primitives', () => {
    let harness: TestSessionHarness;

    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        harness?.destroy();
        vi.useRealTimers();
    });

    async function joinAndSettle(): Promise<void> {
        harness.simulateJoined({
            clientId: 'me',
            participants: [{ cid: 'me' }, { cid: 'peer-1' }],
        });
        await vi.advanceTimersByTimeAsync(0);
        await harness.session.resumeJoin();
    }

    it('suspendForHold stops mic + camera CAPTURE (released, not just enabled=false)', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        const stream = harness.media.installLocalStream({ audio: true, video: true });
        const audioTrack = stream.getAudioTracks()[0] as FakeMediaStreamTrack;
        const videoTrack = stream.getVideoTracks()[0] as FakeMediaStreamTrack;

        await harness.session.suspendForHold();

        // Capture is actually released: tracks were stopped AND removed from the
        // stream (the browser keeps capture live if we only flip `enabled`).
        expect(audioTrack.stopCalls).toBe(1);
        expect(videoTrack.stopCalls).toBe(1);
        expect(stream.getAudioTracks()).toHaveLength(0);
        expect(stream.getVideoTracks()).toHaveLength(0);
        expect(harness.media.suspendLocalMediaForHoldCalls).toBe(1);
        expect(harness.session.currentMediaRole).toBe('held');
    });

    it('held disables remote audio playout (setRemotePlaybackEnabled(false))', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: true });

        await harness.session.suspendForHold();

        expect(harness.media.setRemotePlaybackEnabledCalls).toContain(false);
        expect(harness.media.detachOrPauseRenderersForHoldCalls).toBe(1);
    });

    it('broadcasts held:true AFTER local capture is stopped', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        const stream = harness.media.installLocalStream({ audio: true, video: true });
        const audioTrack = stream.getAudioTracks()[0] as FakeMediaStreamTrack;

        await harness.session.suspendForHold();

        const payload = lastMediaStateBroadcast(harness);
        expect(payload).toEqual({ audioEnabled: false, videoEnabled: false, held: true });
        // Ordering: the held broadcast happens after capture stops, so by the
        // time the message is on the wire the mic track is already ended.
        expect(audioTrack.readyState).toBe('ended');
    });

    it('suspendForHold is idempotent and does not throw after partial release', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: true });

        await harness.session.suspendForHold();
        const broadcastsAfterFirst = harness.signaling.broadcastCalls.length;
        const suspendCallsAfterFirst = harness.media.suspendLocalMediaForHoldCalls;

        // Second call is a safe no-op (already held).
        await expect(harness.session.suspendForHold()).resolves.toBeUndefined();
        expect(harness.media.suspendLocalMediaForHoldCalls).toBe(suspendCallsAfterFirst);
        expect(harness.signaling.broadcastCalls.length).toBe(broadcastsAfterFirst);
        expect(harness.session.currentMediaRole).toBe('held');
    });

    it('resume restores desired audio/video intent and broadcasts held:false', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        const stream = harness.media.installLocalStream({ audio: true, video: true });

        await harness.session.suspendForHold();
        expect(stream.getAudioTracks()).toHaveLength(0);

        await harness.session.resumeForeground();

        // Resume reacquires per desired intent (default config: audio on,
        // selfie camera on) and re-enables remote playout.
        const resumeCall = harness.media.resumeLocalMediaFromHoldCalls.at(-1);
        expect(resumeCall).toEqual({ desiredAudio: true, desiredVideoMode: 'selfie' });
        expect(stream.getAudioTracks()).toHaveLength(1);
        expect(stream.getVideoTracks()).toHaveLength(1);
        expect(harness.media.setRemotePlaybackEnabledCalls.at(-1)).toBe(true);
        expect(harness.session.currentMediaRole).toBe('foreground');

        const payload = lastMediaStateBroadcast(harness);
        expect(payload).toEqual({ audioEnabled: true, videoEnabled: true, held: false });
    });

    it('hold preserves desired intent across audio-only resume', async () => {
        harness = new TestSessionHarness({ config: { defaultVideoEnabled: false } });
        await joinAndSettle();
        const stream = harness.media.installLocalStream({ audio: true, video: false });

        await harness.session.suspendForHold();
        await harness.session.resumeForeground();

        // Camera intent was off, so resume only reacquires audio.
        const resumeCall = harness.media.resumeLocalMediaFromHoldCalls.at(-1);
        expect(resumeCall).toEqual({ desiredAudio: true, desiredVideoMode: 'off' });
        expect(stream.getAudioTracks()).toHaveLength(1);
        expect(stream.getVideoTracks()).toHaveLength(0);
    });

    it('a muted (audio-off) call holds and resumes muted', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        const stream = harness.media.installLocalStream({ audio: true, video: true });
        // User mutes before holding -> desiredAudioEnabled becomes false.
        harness.session.setAudioEnabled(false);

        await harness.session.suspendForHold();
        await harness.session.resumeForeground();

        const resumeCall = harness.media.resumeLocalMediaFromHoldCalls.at(-1);
        expect(resumeCall?.desiredAudio).toBe(false);
        // No audio track reacquired because intent is muted.
        expect(stream.getAudioTracks()).toHaveLength(0);
    });

    // FIX W1: a muted held call must resume MUTED. The broadcast `audioEnabled`
    // and the mic reacquire are driven by `desiredAudioEnabled`, never by track
    // presence or `config.defaultAudioEnabled`. Before the fix, a missing audio
    // track (no reacquire) fell back to `defaultAudioEnabled` and wrongly
    // broadcast `audioEnabled:true` for a muted call (privacy bug).
    it('muted held call resumes muted: no mic reacquire AND broadcasts audioEnabled:false', async () => {
        // Default config (defaultAudioEnabled !== false) is the dangerous case:
        // the old fallback would have advertised audioEnabled:true.
        harness = new TestSessionHarness();
        await joinAndSettle();
        const stream = harness.media.installLocalStream({ audio: true, video: true });
        harness.session.setAudioEnabled(false);

        await harness.session.suspendForHold();
        await harness.session.resumeForeground();

        // Mic was NOT reacquired (desired audio is off).
        expect(harness.media.resumeLocalMediaFromHoldCalls.at(-1)?.desiredAudio).toBe(false);
        expect(stream.getAudioTracks()).toHaveLength(0);

        // Broadcast reflects the muted intent, not defaultAudioEnabled.
        const payload = lastMediaStateBroadcast(harness);
        expect(payload?.audioEnabled).toBe(false);
        expect(payload?.held).toBe(false);
        // Video intent (default on) survived the hold and resumed.
        expect(payload?.videoEnabled).toBe(true);

        // Local participant mirror also reflects muted (matches what peers see).
        const me = harness.state.localParticipant;
        expect(me?.audioEnabled).toBe(false);
    });

    // FIX W1 mirror: a camera-off-but-mic-on held call resumes audio-only — the
    // broadcast must advertise audioEnabled:true, videoEnabled:false.
    it('camera-off held call resumes audio-only: broadcasts audio on, video off', async () => {
        harness = new TestSessionHarness({ config: { defaultVideoEnabled: false } });
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: false });

        await harness.session.suspendForHold();
        await harness.session.resumeForeground();

        const payload = lastMediaStateBroadcast(harness);
        expect(payload?.audioEnabled).toBe(true);
        expect(payload?.videoEnabled).toBe(false);
        expect(payload?.held).toBe(false);
    });

    // §8 / W3 follow-up: the post-reconnect resync re-broadcast must carry the
    // current `held` flag so peers that missed the original hold message during
    // the outage converge to "on hold", not a stale "live" state.
    it('post-reconnect resync re-broadcast includes held:true while held', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: true });

        await harness.session.suspendForHold();

        // Outage, then transport reconnect (arms the post-reconnect resync
        // because roomState was present), then the authoritative snapshot
        // (flushes the resync re-broadcast).
        harness.simulateDisconnect();
        harness.signaling.emitConnected('ws');
        harness.signaling.broadcastCalls.length = 0;
        harness.signaling.emitRoomStateUpdated({
            hostPeerId: 'me',
            participants: [{ peerId: 'me' }, { peerId: 'peer-1' }],
        });

        const payload = lastMediaStateBroadcast(harness);
        expect(payload).toEqual({ audioEnabled: false, videoEnabled: false, held: true });
    });

    it('held is re-broadcast to a peer that joins while held', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: true });

        await harness.session.suspendForHold();
        harness.signaling.broadcastCalls.length = 0;

        harness.signaling.emitPeerJoined({ peerId: 'peer-2', joinedAt: 99 });

        const payload = lastMediaStateBroadcast(harness);
        expect(payload).toEqual({ audioEnabled: false, videoEnabled: false, held: true });
    });
});

describe('participant_media_state held decode (unknown-field tolerance)', () => {
    let harness: TestSessionHarness;

    beforeEach(() => { vi.useFakeTimers(); });
    afterEach(() => { harness?.destroy(); vi.useRealTimers(); });

    async function joinWithPeer(): Promise<void> {
        harness = new TestSessionHarness();
        harness.simulateJoined({
            clientId: 'me',
            participants: [{ cid: 'me' }, { cid: 'peer-1' }],
        });
        await vi.advanceTimersByTimeAsync(0);
        await harness.session.resumeJoin();
    }

    function remotePeer(harness: TestSessionHarness, cid: string) {
        return harness.state.remoteParticipants.find((p) => p.cid === cid);
    }

    it('parses held:true and surfaces it on the remote participant', async () => {
        await joinWithPeer();

        harness.signaling.emitMessage({
            from: 'peer-1',
            type: 'participant_media_state',
            payload: { audioEnabled: false, videoEnabled: false, held: true },
        });

        const peer = remotePeer(harness, 'peer-1');
        expect(peer?.held).toBe(true);
        expect(peer?.audioEnabled).toBe(false);
        expect(peer?.videoEnabled).toBe(false);
    });

    it('parses a pre-held payload (no held field) without error and leaves held undefined', async () => {
        await joinWithPeer();

        harness.signaling.emitMessage({
            from: 'peer-1',
            type: 'participant_media_state',
            payload: { audioEnabled: true, videoEnabled: true },
        });

        const peer = remotePeer(harness, 'peer-1');
        expect(peer?.held).toBeUndefined();
        expect(peer?.audioEnabled).toBe(true);
        expect(peer?.videoEnabled).toBe(true);
    });

    it('ignores a non-boolean held value and keeps the prior cached held state', async () => {
        await joinWithPeer();

        harness.signaling.emitMessage({
            from: 'peer-1',
            type: 'participant_media_state',
            payload: { audioEnabled: false, videoEnabled: false, held: true },
        });
        expect(remotePeer(harness, 'peer-1')?.held).toBe(true);

        // A later message with a garbage `held` (and an extra unknown field)
        // must not throw and must leave the cached held value intact.
        harness.signaling.emitMessage({
            from: 'peer-1',
            type: 'participant_media_state',
            payload: { audioEnabled: false, videoEnabled: false, held: 'yes', futureField: 42 },
        });

        expect(remotePeer(harness, 'peer-1')?.held).toBe(true);
    });

    it('clears held when the peer resumes (held:false)', async () => {
        await joinWithPeer();

        harness.signaling.emitMessage({
            from: 'peer-1',
            type: 'participant_media_state',
            payload: { audioEnabled: false, videoEnabled: false, held: true },
        });
        expect(remotePeer(harness, 'peer-1')?.held).toBe(true);

        harness.signaling.emitMessage({
            from: 'peer-1',
            type: 'participant_media_state',
            payload: { audioEnabled: true, videoEnabled: true, held: false },
        });

        const peer = remotePeer(harness, 'peer-1');
        expect(peer?.held).toBe(false);
        expect(peer?.audioEnabled).toBe(true);
    });
});
