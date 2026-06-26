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

    // FIX N1: a held call owns NO capture (Core Invariant 2). User media toggles
    // while held update `desired*` intent ONLY — no getUserMedia / track
    // reacquire-release, and no participant_media_state broadcast (peers already
    // see held:true). The new desired is applied on the next resume.
    it('toggling audio/video while held updates desired only (no capture, no broadcast) and applies on resume', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        const stream = harness.media.installLocalStream({ audio: true, video: true });

        await harness.session.suspendForHold();
        // Baseline counters after the hold itself.
        const reacquireBefore = harness.media.reacquireVideoTrackCalls;
        const releaseBefore = harness.media.releaseVideoTrackCalls;
        harness.signaling.broadcastCalls.length = 0;

        // User mutes audio and turns the camera off while held.
        harness.session.setAudioEnabled(false);
        harness.session.setVideoEnabled(false);

        // No capture was touched: no camera reacquire/release, no new mic/cam
        // grab, and the held call still owns no tracks.
        expect(harness.media.reacquireVideoTrackCalls).toBe(reacquireBefore);
        expect(harness.media.releaseVideoTrackCalls).toBe(releaseBefore);
        expect(stream.getAudioTracks()).toHaveLength(0);
        expect(stream.getVideoTracks()).toHaveLength(0);
        // No broadcast while held.
        expect(harness.signaling.broadcastCalls.filter((c) => c.type === 'participant_media_state')).toHaveLength(0);
        // actual* stay false (still held); role unchanged.
        expect(harness.session.currentActualAudioPublished).toBe(false);
        expect(harness.session.currentActualVideoPublished).toBe(false);
        expect(harness.session.currentMediaRole).toBe('held');
        // desired* captured the toggles.
        expect(harness.session.currentDesiredAudioEnabled).toBe(false);
        expect(harness.session.currentDesiredVideoMode).toBe('off');

        // Resume applies the latest desired (muted, camera off).
        await harness.session.resumeForeground();
        const resumeCall = harness.media.resumeLocalMediaFromHoldCalls.at(-1);
        expect(resumeCall).toEqual({ desiredAudio: false, desiredVideoMode: 'off' });
        expect(stream.getAudioTracks()).toHaveLength(0);
        expect(stream.getVideoTracks()).toHaveLength(0);
        const payload = lastMediaStateBroadcast(harness);
        expect(payload).toEqual({ audioEnabled: false, videoEnabled: false, held: false });
    });

    // FIX M1: a held call owns NO screen share (Core Invariant 2).
    // startScreenShare() while held is a NO-OP — no getDisplayMedia capture and
    // no content / participant_media_state broadcast. Screen share is
    // foreground-only and is NOT auto-restored on resume.
    it('startScreenShare while held is a no-op (no getDisplayMedia, no broadcast)', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: true });
        harness.media.canScreenShare = true;

        await harness.session.suspendForHold();
        harness.signaling.broadcastCalls.length = 0;
        const startCallsBefore = harness.media.startScreenShareCalls;

        await harness.session.startScreenShare();

        // No capture started and the session is still not screen sharing.
        expect(harness.media.startScreenShareCalls).toBe(startCallsBefore);
        expect(harness.media.isScreenSharing).toBe(false);
        // No content / media-state broadcast while held.
        expect(harness.signaling.broadcastCalls).toHaveLength(0);
        // Role unchanged.
        expect(harness.session.currentMediaRole).toBe('held');
    });

    // Sanity counterpart: a FOREGROUND call's startScreenShare DOES start
    // capture and broadcast (guards on held only, no behavior regression).
    it('startScreenShare while foreground starts capture and broadcasts', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: true });
        harness.media.canScreenShare = true;
        harness.signaling.broadcastCalls.length = 0;

        await harness.session.startScreenShare();

        expect(harness.media.startScreenShareCalls).toBe(1);
        expect(harness.media.isScreenSharing).toBe(true);
        expect(harness.signaling.broadcastCalls.filter((c) => c.type === 'participant_media_state')).toHaveLength(1);
    });

    // FIX M4: resume drives mediaActivationState inactive -> activating -> active
    // (parity with iOS/Android, contract §4).
    it('resume transitions mediaActivationState inactive -> activating -> active', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: true });

        await harness.session.suspendForHold();
        // Held sits at inactive.
        expect(harness.session.currentMediaActivationState).toBe('inactive');

        // Observe the intermediate `activating` while the resume awaits media
        // reacquire (the resume sets it synchronously before the first await).
        const resumePromise = harness.session.resumeForeground();
        expect(harness.session.currentMediaActivationState).toBe('activating');

        await resumePromise;
        expect(harness.session.currentMediaActivationState).toBe('active');
        expect(harness.session.currentMediaRole).toBe('foreground');
    });

    // FIX M4: a superseded resume rolls back to inactive (stays held), never
    // landing on `active`.
    it('a superseded resume leaves mediaActivationState inactive', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: true });

        await harness.session.suspendForHold();

        const resumePromise = harness.session.resumeForeground();
        expect(harness.session.currentMediaActivationState).toBe('activating');
        // A hold supersedes the in-flight resume.
        await harness.session.suspendForHold();
        await resumePromise;

        expect(harness.session.currentMediaActivationState).toBe('inactive');
        expect(harness.session.currentMediaRole).toBe('held');
    });

    // VERIFY M3: resume must emit exactly one held:false AFTER media is flowing,
    // and NEVER a held:true after reacquire. (Web reacquires media first, then
    // broadcasts a single held:false; there is no post-reacquire held:true.)
    it('resume emits a single held:false and no held:true after reacquire', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: true });

        await harness.session.suspendForHold();
        harness.signaling.broadcastCalls.length = 0;

        await harness.session.resumeForeground();

        const mediaBroadcasts = harness.signaling.broadcastCalls
            .filter((c) => c.type === 'participant_media_state');
        // Exactly one media-state broadcast on resume, and it is held:false.
        expect(mediaBroadcasts).toHaveLength(1);
        expect((mediaBroadcasts[0].payload as Record<string, unknown>).held).toBe(false);
        // No held:true emitted after capture is reacquired.
        expect(mediaBroadcasts.some((c) => (c.payload as Record<string, unknown>).held === true)).toBe(false);
    });

    // FIX P5: a call resumed while MUTED owns no mic track (correct — we do not
    // reacquire a muted mic on resume). A LATER foreground unmute must actually
    // REACQUIRE the mic before publishing `audioEnabled:true` — otherwise peers
    // see live audio with silence (a missing track's `enabled` flip is a no-op).
    it('resume-while-muted then unmute reacquires the mic before publishing audioEnabled:true', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        const stream = harness.media.installLocalStream({ audio: true, video: true });
        // Mute, hold, resume: the resumed foreground call has NO audio track.
        harness.session.setAudioEnabled(false);
        await harness.session.suspendForHold();
        await harness.session.resumeForeground();
        expect(stream.getAudioTracks()).toHaveLength(0);
        expect(harness.session.currentMediaRole).toBe('foreground');
        const reacquireBefore = harness.media.reacquireLocalAudioCaptureCalls;
        harness.signaling.broadcastCalls.length = 0;

        // Foreground unmute must REACQUIRE capture (not just flip a missing track).
        harness.session.setAudioEnabled(true);
        await vi.advanceTimersByTimeAsync(0);

        // A fresh mic was captured and the stream now has a live audio track.
        expect(harness.media.reacquireLocalAudioCaptureCalls).toBe(reacquireBefore + 1);
        expect(stream.getAudioTracks()).toHaveLength(1);
        // Only AFTER reacquire do we publish audioEnabled:true.
        const payload = lastMediaStateBroadcast(harness);
        expect(payload?.audioEnabled).toBe(true);
        expect(payload?.held).toBe(false);
        expect(harness.session.currentActualAudioPublished).toBe(true);
        expect(harness.session.currentDesiredAudioEnabled).toBe(true);
    });

    // FIX P5 mirror: a call resumed with the camera OFF owns no video track. A
    // later foreground enable-video must reacquire the camera track before
    // publishing `videoEnabled:true`.
    it('resume-while-camera-off then enable video reacquires the camera before publishing', async () => {
        harness = new TestSessionHarness({ config: { defaultVideoEnabled: false } });
        await joinAndSettle();
        const stream = harness.media.installLocalStream({ audio: true, video: false });
        // Camera-off held then resumed: the foreground call has NO video track.
        await harness.session.suspendForHold();
        await harness.session.resumeForeground();
        expect(stream.getVideoTracks()).toHaveLength(0);
        expect(harness.session.currentMediaRole).toBe('foreground');
        const reacquireBefore = harness.media.reacquireVideoTrackCalls;
        harness.signaling.broadcastCalls.length = 0;

        // Foreground enable-video must REACQUIRE the camera.
        harness.session.setVideoEnabled(true);
        await vi.advanceTimersByTimeAsync(0);

        expect(harness.media.reacquireVideoTrackCalls).toBe(reacquireBefore + 1);
        expect(stream.getVideoTracks()).toHaveLength(1);
        const payload = lastMediaStateBroadcast(harness);
        expect(payload?.videoEnabled).toBe(true);
        expect(payload?.held).toBe(false);
        expect(harness.session.currentActualVideoPublished).toBe(true);
    });

    // FIX P5 regression: a NORMALLY-joined muted single call has a live (muted)
    // audio track. Unmuting it must NOT trigger a fresh getUserMedia — it just
    // flips `track.enabled`. Single-call behavior is unchanged.
    it('normally-joined muted call unmuting does NOT reacquire the mic (flips enabled only)', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        const stream = harness.media.installLocalStream({ audio: true, video: true });
        const audioTrack = stream.getAudioTracks()[0] as FakeMediaStreamTrack;

        // Mute the foreground single call: the track stays live, only enabled flips.
        harness.session.setAudioEnabled(false);
        expect(stream.getAudioTracks()).toHaveLength(1);
        expect(audioTrack.enabled).toBe(false);
        const reacquireBefore = harness.media.reacquireLocalAudioCaptureCalls;

        // Unmute: still no reacquire, the same track's `enabled` flips back on.
        harness.session.setAudioEnabled(true);

        expect(harness.media.reacquireLocalAudioCaptureCalls).toBe(reacquireBefore);
        expect(stream.getAudioTracks()).toHaveLength(1);
        expect(stream.getAudioTracks()[0]).toBe(audioTrack);
        expect(audioTrack.enabled).toBe(true);
        const payload = lastMediaStateBroadcast(harness);
        expect(payload?.audioEnabled).toBe(true);
        expect(harness.session.currentActualAudioPublished).toBe(true);
    });

    // FIX N2: a suspendForHold() that races an in-flight resumeForeground() must
    // win — the session stays HELD (resume's partial activation is rolled back),
    // remote playback ends disabled, and resume never broadcasts held:false.
    it('suspendForHold racing resumeForeground leaves the session held (no foreground, no held:false)', async () => {
        harness = new TestSessionHarness();
        await joinAndSettle();
        harness.media.installLocalStream({ audio: true, video: true });

        await harness.session.suspendForHold();
        harness.media.setRemotePlaybackEnabledCalls.length = 0;
        harness.signaling.broadcastCalls.length = 0;

        // Start the resume but do NOT await it yet (it suspends at the media
        // reacquire await), then run a hold that supersedes it.
        const resumePromise = harness.session.resumeForeground();
        await harness.session.suspendForHold();
        await resumePromise;

        // The hold won: session is held, not foreground.
        expect(harness.session.currentMediaRole).toBe('held');
        expect(harness.session.currentActualAudioPublished).toBe(false);
        expect(harness.session.currentActualVideoPublished).toBe(false);

        // Remote playback ends DISABLED (resume re-enabled it, hold + rollback
        // disabled it; last write must be false).
        expect(harness.media.setRemotePlaybackEnabledCalls.at(-1)).toBe(false);

        // Resume never broadcast held:false. The only media-state broadcast is
        // the hold's held:true.
        const mediaBroadcasts = harness.signaling.broadcastCalls.filter((c) => c.type === 'participant_media_state');
        expect(mediaBroadcasts.every((c) => (c.payload as Record<string, unknown>).held === true)).toBe(true);
        expect(mediaBroadcasts.some((c) => (c.payload as Record<string, unknown>).held === false)).toBe(false);
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
