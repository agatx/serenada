import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { TestSessionHarness } from './helpers/TestSessionHarness.js';
import type { RoomParticipant } from '../src/signaling/types.js';

// SerenadaSession uses `window.setTimeout` etc. In Node (no jsdom), `window`
// is undefined — delegate to globalThis so vi.useFakeTimers() patches apply.
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

/**
 * Phase 1 independent-screen-share state on SerenadaSession (flag off):
 * - remote `content` driven by received `content_state` with (cid, sid)/revision
 *   tracking,
 * - `cameraEnabled` mirrors `videoEnabled`,
 * - `cameraMode` behavior unchanged with the flag off,
 * - remote capabilities/mediaPolicy stored with contract defaults.
 */
describe('SerenadaSession — independent screen share (Phase 1)', () => {
    let harness: TestSessionHarness;

    /** Bring the session to inCall with `me` + the given remote CIDs. */
    function joinInCall(remoteCids: string[], remote?: Partial<RoomParticipant>[]): void {
        harness.signaling.emitConnected('ws');
        harness.signaling.emitJoined({
            peerId: 'me',
            participants: [{ peerId: 'me', joinedAt: 1 }],
        });
        harness.signaling.emitRoomStateUpdated({
            hostPeerId: 'me',
            participants: [
                { peerId: 'me', joinedAt: 1 },
                ...remoteCids.map((cid, i) => ({
                    peerId: cid,
                    joinedAt: 2 + i,
                    capabilities: remote?.[i]?.capabilities,
                    mediaPolicy: remote?.[i]?.mediaPolicy,
                    contentState: remote?.[i]?.contentState,
                })),
            ],
        });
    }

    function remote(cid: string) {
        return harness.state.remoteParticipants.find((p) => p.cid === cid);
    }

    beforeEach(() => {
        vi.useFakeTimers();
        harness = new TestSessionHarness();
    });

    afterEach(() => {
        harness?.destroy();
        vi.useRealTimers();
    });

    describe('remote content_state revision tracking', () => {
        it('populates remote content from a received content_state', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 1 },
                sid: 'S-1',
            });

            expect(remote('peer-1')?.content).toEqual({
                active: true,
                type: 'screenShare',
                revision: 1,
            });
        });

        it('defaults content type to screenShare when contentType is absent', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, revision: 1 },
                sid: 'S-1',
            });
            expect(remote('peer-1')?.content?.type).toBe('screenShare');
        });

        it('keeps the highest revision within the same (cid, sid)', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 5 },
                sid: 'S-1',
            });
            // Out-of-order stale active:false (revision 4) must be discarded.
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: false, revision: 4 },
                sid: 'S-1',
            });

            expect(remote('peer-1')?.content).toEqual({
                active: true,
                type: 'screenShare',
                revision: 5,
            });
        });

        it('discards a revision equal to the tracked one', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 3 },
                sid: 'S-1',
            });
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: false, revision: 3 },
                sid: 'S-1',
            });
            expect(remote('peer-1')?.content?.active).toBe(true);
            expect(remote('peer-1')?.content?.revision).toBe(3);
        });

        it('accepts a strictly-greater revision within the same (cid, sid)', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 1 },
                sid: 'S-1',
            });
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: false, revision: 2 },
                sid: 'S-1',
            });
            expect(remote('peer-1')?.content).toBeUndefined();
        });

        it('ignores malformed live revisions for ordering without poisoning the high-water mark', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 5 },
                sid: 'S-1',
            });

            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: Number.MAX_SAFE_INTEGER + 1 },
                sid: 'S-1',
            });
            expect(remote('peer-1')?.content).toEqual({
                active: true,
                type: 'screenShare',
                revision: 0,
            });

            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: false, revision: 5 },
                sid: 'S-1',
            });
            expect(remote('peer-1')?.content?.active).toBe(true);

            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: false, revision: 6 },
                sid: 'S-1',
            });
            expect(remote('peer-1')?.content).toBeUndefined();
        });

        it('supersedes by identity when a new sid arrives, even with a lower revision', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 7 },
                sid: 'S-1',
            });
            // Rejoin: new sid restarting at revision:1 must be accepted by
            // identity, not discarded as stale against the prior revision 7.
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 1 },
                sid: 'S-2',
            });
            expect(remote('peer-1')?.content).toEqual({
                active: true,
                type: 'screenShare',
                revision: 1,
            });
        });

        it('tracks revisions independently per remote CID', () => {
            joinInCall(['peer-1', 'peer-2']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 9 },
                sid: 'S-1',
            });
            // peer-2 starting fresh at revision 1 is independent of peer-1's 9.
            harness.signaling.emitMessage({
                from: 'peer-2',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 1 },
                sid: 'S-2',
            });
            expect(remote('peer-1')?.content?.revision).toBe(9);
            expect(remote('peer-2')?.content?.revision).toBe(1);
        });

        it('clears tracked content when the peer leaves', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 1 },
                sid: 'S-1',
            });
            expect(remote('peer-1')?.content?.active).toBe(true);

            harness.simulatePeerLeft('peer-1');
            // Peer rejoins fresh; content should not resurrect from the old record.
            harness.signaling.emitPeerJoined({ peerId: 'peer-1', joinedAt: 5 });
            expect(remote('peer-1')?.content).toBeUndefined();
        });

        it('ignores content_state with a non-boolean active field', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: 'yes', revision: 1 },
                sid: 'S-1',
            });
            expect(remote('peer-1')?.content).toBeUndefined();
        });
    });

    describe('snapshot content from joined/room_state', () => {
        it('surfaces remote content from a room snapshot active content_state', () => {
            // Peer was already sharing when we joined: the snapshot carries its
            // content_state on the participant record.
            joinInCall(['peer-1'], [{
                contentState: { active: true, contentType: 'screenShare', revision: 4 },
            }]);
            expect(remote('peer-1')?.content).toEqual({
                active: true,
                type: 'screenShare',
                revision: 4,
            });
        });

        it('defaults snapshot content type to screenShare when absent', () => {
            joinInCall(['peer-1'], [{
                contentState: { active: true, revision: 1 },
            }]);
            expect(remote('peer-1')?.content?.type).toBe('screenShare');
        });

        it('reconciles a snapshot revision via keep-highest against a cached live value', () => {
            joinInCall(['peer-1']);
            // Live update advances to revision 5.
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare', revision: 5 },
            });
            expect(remote('peer-1')?.content?.revision).toBe(5);

            // A later room_state snapshot carrying a STALE revision (3) must not
            // overwrite the higher cached live value.
            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me', joinedAt: 1 },
                    { peerId: 'peer-1', joinedAt: 2, contentState: { active: false, contentType: 'screenShare', revision: 3 } },
                ],
            });
            expect(remote('peer-1')?.content).toEqual({
                active: true,
                type: 'screenShare',
                revision: 5,
            });

            // A snapshot with a HIGHER revision (incl. active:false) supersedes
            // the stale cached active:true.
            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me', joinedAt: 1 },
                    { peerId: 'peer-1', joinedAt: 2, contentState: { active: false, contentType: 'screenShare', revision: 6 } },
                ],
            });
            expect(remote('peer-1')?.content).toBeUndefined();
        });

        it('does not let a revisionless snapshot overwrite a cached live state', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare' },
            });
            expect(remote('peer-1')?.content).toEqual({
                active: true,
                type: 'screenShare',
                revision: 0,
            });

            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me', joinedAt: 1 },
                    { peerId: 'peer-1', joinedAt: 2, contentState: { active: false, contentType: 'screenShare' } },
                ],
            });

            expect(remote('peer-1')?.content).toEqual({
                active: true,
                type: 'screenShare',
                revision: 0,
            });
        });
    });

    describe('cameraEnabled mirrors videoEnabled (flag off)', () => {
        it('mirrors remote videoEnabled onto cameraEnabled', () => {
            joinInCall(['peer-1']);
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'participant_media_state',
                payload: { audioEnabled: true, videoEnabled: false },
            });
            const p = remote('peer-1');
            expect(p?.videoEnabled).toBe(false);
            expect(p?.cameraEnabled).toBe(false);

            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'participant_media_state',
                payload: { audioEnabled: true, videoEnabled: true },
            });
            const p2 = remote('peer-1');
            expect(p2?.videoEnabled).toBe(true);
            expect(p2?.cameraEnabled).toBe(true);
        });

        it('mirrors local videoEnabled onto local cameraEnabled', () => {
            joinInCall(['peer-1']);
            const local = harness.state.localParticipant;
            expect(local).not.toBeNull();
            expect(local?.cameraEnabled).toBe(local?.videoEnabled);
        });
    });

    describe('local content + cameraMode (flag off)', () => {
        it('reports local content from media screen-share state', () => {
            joinInCall(['peer-1']);
            // Before sharing, no content has been sent.
            expect(harness.state.localParticipant?.content).toBeUndefined();

            harness.media.emit({ isScreenSharing: true, lastContentRevision: 1 });
            expect(harness.state.localParticipant?.content).toEqual({
                active: true,
                type: 'screenShare',
                revision: 1,
            });

            // After stopping, public content is absent; the latest revision stays
            // internal so the next send remains monotonic.
            harness.media.emit({ isScreenSharing: false, lastContentRevision: 2 });
            expect(harness.state.localParticipant?.content).toBeUndefined();
        });

        it('seeds local content revision from recovered snapshots', () => {
            harness.simulateJoined({
                clientId: 'me',
                participants: [
                    {
                        cid: 'me',
                        contentState: { active: false, contentType: 'screenShare', revision: 7 },
                    },
                    { cid: 'peer-1' },
                ],
                hostCid: 'me',
            });

            expect(harness.media.lastContentRevision).toBe(7);
            expect(harness.state.localParticipant?.content).toBeUndefined();

            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me', contentState: { active: false, contentType: 'screenShare', revision: 9 } },
                    { peerId: 'peer-1' },
                ],
            });

            expect(harness.media.lastContentRevision).toBe(9);
        });

        it('keeps cameraMode=screenShare while sharing (legacy behavior, flag off)', () => {
            joinInCall(['peer-1']);
            harness.media.emit({ isScreenSharing: true, lastContentRevision: 1 });
            expect(harness.state.localParticipant?.cameraMode).toBe('screenShare');

            harness.media.emit({ isScreenSharing: false, lastContentRevision: 2 });
            expect(harness.state.localParticipant?.cameraMode).toBe('selfie');
        });
    });

    describe('remote capabilities / mediaPolicy storage + defaults', () => {
        it('stores advertised capabilities and applies them via accessors', () => {
            joinInCall(['peer-1'], [{
                capabilities: { independentContentVideo: true },
                mediaPolicy: { videoMediaEnabled: false },
            }]);
            expect(harness.session.getRemoteIndependentContentVideo('peer-1')).toBe(true);
            expect(harness.session.getRemoteVideoMediaEnabled('peer-1')).toBe(false);
        });

        it('defaults missing independentContentVideo to false and videoMediaEnabled to true', () => {
            joinInCall(['peer-1']);
            expect(harness.session.getRemoteIndependentContentVideo('peer-1')).toBe(false);
            expect(harness.session.getRemoteVideoMediaEnabled('peer-1')).toBe(true);
        });

        it('drops stored capabilities when the peer leaves', () => {
            joinInCall(['peer-1'], [{ capabilities: { independentContentVideo: true } }]);
            expect(harness.session.getRemoteIndependentContentVideo('peer-1')).toBe(true);
            harness.simulatePeerLeft('peer-1');
            expect(harness.session.getRemoteIndependentContentVideo('peer-1')).toBe(false);
        });

        it('clears stale capabilities/mediaPolicy when a later snapshot omits them for a still-present CID', () => {
            // First snapshot advertises non-default capabilities + policy.
            joinInCall(['peer-1'], [{
                capabilities: { independentContentVideo: true },
                mediaPolicy: { videoMediaEnabled: false },
            }]);
            expect(harness.session.getRemoteIndependentContentVideo('peer-1')).toBe(true);
            expect(harness.session.getRemoteVideoMediaEnabled('peer-1')).toBe(false);

            // A later authoritative snapshot for the SAME CID omits both fields.
            // The stored entries must be cleared so accessors fall back to
            // contract defaults instead of returning the stale advertised values.
            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me', joinedAt: 1 },
                    { peerId: 'peer-1', joinedAt: 2 },
                ],
            });
            expect(harness.session.getRemoteIndependentContentVideo('peer-1')).toBe(false);
            expect(harness.session.getRemoteVideoMediaEnabled('peer-1')).toBe(true);
        });
    });

    describe('independent mode public state (flag on)', () => {
        beforeEach(() => {
            harness?.destroy();
            harness = new TestSessionHarness({
                config: { enableIndependentContentVideo: true },
            });
        });

        it('never sets cameraMode=screenShare while sharing, even with a legacy peer present', () => {
            joinInCall(['peer-1']); // peer-1 advertises nothing → legacy peer
            harness.media.emit({ isScreenSharing: true, lastContentRevision: 1, facingMode: 'user' });

            expect(harness.state.localParticipant?.cameraMode).toBe('selfie');
            expect(harness.state.localParticipant?.content).toEqual({
                active: true,
                type: 'screenShare',
                revision: 1,
            });

            harness.media.emit({ isScreenSharing: false, lastContentRevision: 2 });
            expect(harness.state.localParticipant?.cameraMode).toBe('selfie');
        });

        it('reflects camera mode (world) while sharing in independent mode', () => {
            joinInCall(['peer-1']);
            harness.media.emit({ isScreenSharing: true, lastContentRevision: 1, facingMode: 'environment' });
            expect(harness.state.localParticipant?.cameraMode).toBe('world');
        });

        it('exposes content.active from local screen share state', () => {
            joinInCall(['peer-1']);
            expect(harness.state.localParticipant?.content).toBeUndefined();
            harness.media.emit({ isScreenSharing: true, lastContentRevision: 1 });
            expect(harness.state.localParticipant?.content?.active).toBe(true);
        });

        it('wires role-specific stream accessors through to the media engine', () => {
            joinInCall(['peer-1']);
            const camera = { id: 'cam' } as unknown as MediaStream;
            const content = { id: 'content' } as unknown as MediaStream;
            harness.media.remoteCameraStreams.set('peer-1', camera);
            harness.media.remoteContentStreams.set('peer-1', content);
            harness.media.localContentStream = content;

            expect(harness.session.getRemoteCameraStream('peer-1')).toBe(camera);
            expect(harness.session.getRemoteContentStream('peer-1')).toBe(content);
            expect(harness.session.getLocalContentStream()).toBe(content);
        });
    });
});
