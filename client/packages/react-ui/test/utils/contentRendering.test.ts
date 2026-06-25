import { describe, expect, it } from 'vitest';
import {
    pickPrimaryContent,
    resolveContentScene,
    resolveContentSource,
    resolveLocalContent,
    resolveRemoteContents,
    shouldRenderContentStage,
    deriveStageTiles,
    pickStageSpotlightTileId,
    stageTileId,
    parseStageTileId,
    stageTileKeyEquals,
    type ContentStreamAccessors,
    type ResolveContentInput,
    type ResolvedContent,
    type StageCameraParticipant,
} from '../../src/utils/contentRendering';

// ---------------------------------------------------------------------------
// Fakes — the package ships no jsdom, so MediaStream is faked structurally.
// Only getVideoTracks()/readyState are read by the helper.
// ---------------------------------------------------------------------------

function fakeStream(opts: { live?: boolean; enabled?: boolean; tracks?: number } = {}): MediaStream {
    const trackCount = opts.tracks ?? 1;
    const readyState = opts.live === false ? 'ended' : 'live';
    const enabled = opts.enabled !== false;
    const tracks = Array.from({ length: trackCount }, () => ({ readyState, enabled }));
    return {
        getVideoTracks: () => tracks,
    } as unknown as MediaStream;
}

function noStreams(): ContentStreamAccessors {
    return {
        getLocalContentStream: () => null,
        getRemoteContentStream: () => undefined,
    };
}

function baseInput(over: Partial<ResolveContentInput> = {}): ResolveContentInput {
    return {
        local: null,
        localStream: null,
        remotes: [],
        remoteStreams: new Map(),
        independentContentEnabled: true,
        legacyRemoteContent: null,
        accessors: noStreams(),
        localVideoMediaEnabled: true,
        ...over,
    };
}

describe('contentRendering — independent (flag-on) content', () => {
    it('renders remote content from the content stream when content.active', () => {
        const contentStream = fakeStream();
        const input = baseInput({
            remotes: [{ cid: 'r1', content: { active: true, type: 'screenShare', revision: 3 }, supportsIndependentContentVideo: true }],
            accessors: {
                getLocalContentStream: () => null,
                getRemoteContentStream: (cid) => (cid === 'r1' ? contentStream : undefined),
            },
        });

        const remotes = resolveRemoteContents(input);
        expect(remotes).toHaveLength(1);
        expect(remotes[0].mode).toBe('independent');
        expect(remotes[0].stream).toBe(contentStream);
        expect(remotes[0].loading).toBe(false);
        expect(remotes[0].ownerId).toBe('r1');
    });

    it('treats a camera-framing (worldCamera) content_state as LEGACY, not a black independent tile', () => {
        // A frontline peer in world-camera mode emits content_state(active,
        // worldCamera) but has NO content track; the negotiated content transceiver
        // still yields a frameless stream. Gating on stream presence alone rendered
        // a BLACK independent content tile (bug [F]). It must resolve LEGACY (camera).
        const framelessContentStream = fakeStream();
        const cameraStream = fakeStream();
        const input = baseInput({
            remotes: [{ cid: 'r1', content: { active: true, type: 'worldCamera', revision: 4 } }],
            remoteStreams: new Map([['r1', cameraStream]]),
            accessors: {
                getLocalContentStream: () => null,
                getRemoteContentStream: (cid) => (cid === 'r1' ? framelessContentStream : undefined),
            },
        });

        const remotes = resolveRemoteContents(input);
        expect(remotes).toHaveLength(1);
        expect(remotes[0].mode).toBe('legacy');
        expect(remotes[0].stream).toBe(cameraStream);
        expect(remotes[0].stream).not.toBe(framelessContentStream);
    });

    it('treats a non-capable remote as LEGACY even if a content stream accessor is present', () => {
        const apparentContentStream = fakeStream();
        const cameraStream = fakeStream();
        const input = baseInput({
            remotes: [{
                cid: 'legacy',
                content: { active: true, type: 'screenShare', revision: 5 },
                supportsIndependentContentVideo: false,
            }],
            remoteStreams: new Map([['legacy', cameraStream]]),
            accessors: {
                getLocalContentStream: () => null,
                getRemoteContentStream: (cid) => (cid === 'legacy' ? apparentContentStream : undefined),
            },
        });

        const remotes = resolveRemoteContents(input);
        expect(remotes).toHaveLength(1);
        expect(remotes[0].mode).toBe('legacy');
        expect(remotes[0].stream).toBe(cameraStream);
    });

    it('shows camera AND content as separate streams for the same owner', () => {
        // The helper resolves content; the owner's camera is sourced separately
        // by the component. Here we assert content does NOT pull the camera/legacy
        // stream when an independent content stream exists.
        const contentStream = fakeStream();
        const cameraStream = fakeStream();
        const input = baseInput({
            remotes: [{ cid: 'r1', content: { active: true, type: 'screenShare', revision: 1 }, supportsIndependentContentVideo: true }],
            remoteStreams: new Map([['r1', cameraStream]]),
            accessors: {
                getLocalContentStream: () => null,
                getRemoteContentStream: (cid) => (cid === 'r1' ? contentStream : undefined),
            },
        });

        const remotes = resolveRemoteContents(input);
        expect(remotes[0].stream).toBe(contentStream);
        expect(remotes[0].stream).not.toBe(cameraStream);
    });

    it('renders local content from getLocalContentStream when content.active', () => {
        const localContent = fakeStream();
        const input = baseInput({
            local: { cid: 'me', cameraMode: 'selfie', content: { active: true, type: 'screenShare', revision: 2 } },
            accessors: {
                getLocalContentStream: () => localContent,
                getRemoteContentStream: () => undefined,
            },
        });

        const local = resolveLocalContent(input);
        expect(local).not.toBeNull();
        expect(local!.mode).toBe('independent');
        expect(local!.stream).toBe(localContent);
        expect(local!.isLocal).toBe(true);
    });
});

describe('contentRendering — media liveness', () => {
    it('treats disabled video tracks as not live media', () => {
        const input = baseInput({
            local: { cid: 'me', cameraMode: 'selfie', content: { active: true, type: 'screenShare', revision: 1 } },
            accessors: {
                getLocalContentStream: () => fakeStream({ enabled: false }),
                getRemoteContentStream: () => undefined,
            },
        });

        const local = resolveLocalContent(input);
        expect(local?.mode).toBe('independent');
        expect(local?.stream).toBeNull();
        expect(local?.loading).toBe(true);
    });
});

describe('contentRendering — flag-off / legacy single-video-as-content', () => {
    it('presents the local single video as content when cameraMode=screenShare', () => {
        const localStream = fakeStream();
        const input = baseInput({
            // No content field (legacy peer / flag off), only legacy cameraMode signal.
            local: { cid: 'me', cameraMode: 'screenShare' },
            localStream,
            accessors: noStreams(),
        });

        const local = resolveLocalContent(input);
        expect(local).not.toBeNull();
        expect(local!.mode).toBe('legacy');
        expect(local!.stream).toBe(localStream);
    });

    it('does NOT present local content when not legacy-sharing and no content field', () => {
        const input = baseInput({
            local: { cid: 'me', cameraMode: 'selfie' },
            localStream: fakeStream(),
        });
        expect(resolveLocalContent(input)).toBeNull();
    });

    it('presents a remote single video as content from the legacy content_state signal', () => {
        const remoteCamera = fakeStream();
        const input = baseInput({
            remotes: [{ cid: 'r1' }], // no content field → legacy peer
            remoteStreams: new Map([['r1', remoteCamera]]),
            legacyRemoteContent: { cid: 'r1', contentType: 'screenShare' },
            accessors: noStreams(),
        });

        const remotes = resolveRemoteContents(input);
        expect(remotes).toHaveLength(1);
        expect(remotes[0].mode).toBe('legacy');
        expect(remotes[0].stream).toBe(remoteCamera);
    });

    it('does NOT present remote content for a legacy peer with no content_state signal', () => {
        const input = baseInput({
            remotes: [{ cid: 'r1' }],
            remoteStreams: new Map([['r1', fakeStream()]]),
            legacyRemoteContent: null,
        });
        expect(resolveRemoteContents(input)).toHaveLength(0);
    });

    it('legacy content_state only affects the signaled cid, not others', () => {
        const input = baseInput({
            remotes: [{ cid: 'r1' }, { cid: 'r2' }],
            remoteStreams: new Map([['r1', fakeStream()], ['r2', fakeStream()]]),
            legacyRemoteContent: { cid: 'r2', contentType: 'screenShare' },
        });
        const remotes = resolveRemoteContents(input);
        expect(remotes.map((r) => r.ownerId)).toEqual(['r2']);
    });
});

describe('contentRendering — receiver-side hold', () => {
    it('holds (loading, no stream) when content.active but content media absent', () => {
        const input = baseInput({
            remotes: [{ cid: 'r1', content: { active: true, type: 'screenShare', revision: 1 }, supportsIndependentContentVideo: true }],
            // No content stream yet (receiver track not promoted / pending attach).
            accessors: noStreams(),
            remoteStreams: new Map(), // no legacy fallback stream either
        });
        const remotes = resolveRemoteContents(input);
        expect(remotes).toHaveLength(1);
        expect(remotes[0].loading).toBe(true);
        expect(remotes[0].stream).toBeNull();
    });

    it('does NOT present content while content.active is false even if a stale stream exists', () => {
        const stale = fakeStream();
        const input = baseInput({
            remotes: [{ cid: 'r1', content: { active: false, type: 'screenShare', revision: 5 } }],
            accessors: {
                getLocalContentStream: () => null,
                // A receiver track may exist before content_state turns active.
                getRemoteContentStream: () => stale,
            },
        });
        expect(resolveRemoteContents(input)).toHaveLength(0);
    });

    it('treats an ended content track as loading (held), not flowing', () => {
        const ended = fakeStream({ live: false });
        const input = baseInput({
            remotes: [{ cid: 'r1', content: { active: true, type: 'screenShare', revision: 1 }, supportsIndependentContentVideo: true }],
            accessors: {
                getLocalContentStream: () => null,
                getRemoteContentStream: () => ended,
            },
        });
        const remotes = resolveRemoteContents(input);
        expect(remotes[0].loading).toBe(true);
        expect(remotes[0].stream).toBeNull();
    });
});

describe('contentRendering — waiting for participants (local)', () => {
    it('flags waitingForParticipants when local shares with no remotes', () => {
        const input = baseInput({
            local: { cid: 'me', cameraMode: 'selfie', content: { active: true, type: 'screenShare', revision: 1 } },
            remotes: [],
            accessors: {
                getLocalContentStream: () => fakeStream(),
                getRemoteContentStream: () => undefined,
            },
        });
        const local = resolveLocalContent(input);
        expect(local!.waitingForParticipants).toBe(true);
    });

    it('does not flag waiting once a remote is present', () => {
        const input = baseInput({
            local: { cid: 'me', cameraMode: 'selfie', content: { active: true, type: 'screenShare', revision: 1 } },
            remotes: [{ cid: 'r1' }],
            accessors: {
                getLocalContentStream: () => fakeStream(),
                getRemoteContentStream: () => undefined,
            },
        });
        const local = resolveLocalContent(input);
        expect(local!.waitingForParticipants).toBe(false);
    });
});

describe('contentRendering — audio-only suppression', () => {
    it('suppresses ALL content UI for an audio-only receiver (local content)', () => {
        const input = baseInput({
            local: { cid: 'me', cameraMode: 'selfie', content: { active: true, type: 'screenShare', revision: 1 } },
            localVideoMediaEnabled: false,
            accessors: { getLocalContentStream: () => fakeStream(), getRemoteContentStream: () => undefined },
        });
        expect(resolveLocalContent(input)).toBeNull();
    });

    it('suppresses ALL content UI for an audio-only receiver (remote content)', () => {
        const input = baseInput({
            remotes: [{ cid: 'r1', content: { active: true, type: 'screenShare', revision: 1 }, supportsIndependentContentVideo: true }],
            localVideoMediaEnabled: false,
            accessors: { getLocalContentStream: () => null, getRemoteContentStream: () => fakeStream() },
        });
        expect(resolveRemoteContents(input)).toHaveLength(0);
    });

    it('suppresses even the legacy single-video-as-content path when audio-only', () => {
        const input = baseInput({
            remotes: [{ cid: 'r1' }],
            remoteStreams: new Map([['r1', fakeStream()]]),
            legacyRemoteContent: { cid: 'r1', contentType: 'screenShare' },
            localVideoMediaEnabled: false,
        });
        expect(resolveRemoteContents(input)).toHaveLength(0);
    });
});

describe('contentRendering — multiple simultaneous sharers', () => {
    it('resolves a content tile per active remote sharer', () => {
        const s1 = fakeStream();
        const s2 = fakeStream();
        const input = baseInput({
            remotes: [
                { cid: 'r1', content: { active: true, type: 'screenShare', revision: 1 }, supportsIndependentContentVideo: true },
                { cid: 'r2', content: { active: true, type: 'screenShare', revision: 1 }, supportsIndependentContentVideo: true },
            ],
            accessors: {
                getLocalContentStream: () => null,
                getRemoteContentStream: (cid) => (cid === 'r1' ? s1 : cid === 'r2' ? s2 : undefined),
            },
        });
        const remotes = resolveRemoteContents(input);
        expect(remotes.map((r) => r.ownerId)).toEqual(['r1', 'r2']);
    });

    it('picks the most-recently-received active remote as primary (local receive order)', () => {
        const remotes: ResolvedContent[] = [
            { ownerId: 'r1', isLocal: false, type: 'screenShare', mode: 'independent', stream: fakeStream(), loading: false, waitingForParticipants: false },
            { ownerId: 'r2', isLocal: false, type: 'screenShare', mode: 'independent', stream: fakeStream(), loading: false, waitingForParticipants: false },
        ];
        // r2 became active after r1 (most recent last).
        const primary = pickPrimaryContent(null, remotes, ['r1', 'r2']);
        expect(primary!.ownerId).toBe('r2');

        // Reverse the receive order → r1 is now most recent.
        const primary2 = pickPrimaryContent(null, remotes, ['r2', 'r1']);
        expect(primary2!.ownerId).toBe('r1');
    });

    it('prefers a remote sharer over local content for the primary (independent mode)', () => {
        const local: ResolvedContent = { ownerId: 'me', isLocal: true, type: 'screenShare', mode: 'independent', stream: fakeStream(), loading: false, waitingForParticipants: false };
        const remotes: ResolvedContent[] = [
            { ownerId: 'r1', isLocal: false, type: 'screenShare', mode: 'independent', stream: fakeStream(), loading: false, waitingForParticipants: false },
        ];
        expect(pickPrimaryContent(local, remotes, ['r1'])!.ownerId).toBe('r1');
    });

    it('uses local content as primary when no remote is sharing', () => {
        const local: ResolvedContent = { ownerId: 'me', isLocal: true, type: 'screenShare', mode: 'independent', stream: fakeStream(), loading: false, waitingForParticipants: false };
        expect(pickPrimaryContent(local, [], [])!.ownerId).toBe('me');
    });

    // --- FIX 4: flag-off / legacy mode must stay LOCAL-first --------------------
    // The legacy (pre-Phase-2) SerenadaCallFlow chose the content source LOCAL-first:
    //   if (isScreenSharing && local) → local; else if (remoteContentState) → remote.
    // The recent-remote-first heuristic is an independent-mode feature and must NOT
    // change the byte-identical legacy multi-party layout when the local user is
    // sharing AND a remote content_state is also active.
    it('flag-off/legacy: LOCAL content is primary even when a remote content is also active', () => {
        const local: ResolvedContent = { ownerId: 'me', isLocal: true, type: 'screenShare', mode: 'legacy', stream: fakeStream(), loading: false, waitingForParticipants: false };
        const remotes: ResolvedContent[] = [
            { ownerId: 'r1', isLocal: false, type: 'screenShare', mode: 'legacy', stream: fakeStream(), loading: false, waitingForParticipants: false },
        ];
        // Legacy order: local wins, ignoring the recent-remote heuristic.
        expect(pickPrimaryContent(local, remotes, ['r1'])!.ownerId).toBe('me');
    });

    it('flag-off/legacy: a remote legacy content is primary when local is NOT sharing', () => {
        const remotes: ResolvedContent[] = [
            { ownerId: 'r1', isLocal: false, type: 'screenShare', mode: 'legacy', stream: fakeStream(), loading: false, waitingForParticipants: false },
        ];
        // No local content (local not sharing) → the legacy remote content is primary.
        expect(pickPrimaryContent(null, remotes, ['r1'])!.ownerId).toBe('r1');
    });

    it('a local screen share wins the spotlight over a remote camera-framing (legacy) content', () => {
        // Regression ([F] follow-up): a remote frontline peer in world-camera mode
        // emits a worldCamera content_state that resolves LEGACY (no content tile).
        // It must NOT steal the spotlight from the local user's real INDEPENDENT
        // screen share (which has a content tile).
        const local: ResolvedContent = { ownerId: 'me', isLocal: true, type: 'screenShare', mode: 'independent', stream: fakeStream(), loading: false, waitingForParticipants: false };
        const remotes: ResolvedContent[] = [
            { ownerId: 'r1', isLocal: false, type: 'worldCamera', mode: 'legacy', stream: fakeStream(), loading: false, waitingForParticipants: false },
        ];
        expect(pickPrimaryContent(local, remotes, ['r1'])!.ownerId).toBe('me');
    });
});

describe('contentRendering — resolveContentSource (content-stage gating)', () => {
    function resolved(over: Partial<ResolvedContent> = {}): ResolvedContent {
        return {
            ownerId: 'r1',
            isLocal: false,
            type: 'screenShare',
            mode: 'independent',
            stream: null,
            loading: false,
            waitingForParticipants: false,
            ...over,
        };
    }

    it('returns null when there is no primary content (any party size)', () => {
        expect(resolveContentSource(null, true)).toBeNull();
        expect(resolveContentSource(null, false)).toBeNull();
    });

    // --- 1:1: the regression this fixes -------------------------------------
    it('1:1 + INDEPENDENT content → surfaces the content source (not suppressed)', () => {
        const source = resolveContentSource(resolved({ mode: 'independent' }), false);
        expect(source).not.toBeNull();
        expect(source!.ownerParticipantId).toBe('r1');
        expect(source!.type).toBe('screenShare');
    });

    it('1:1 + INDEPENDENT local content → surfaces the local owner as content source', () => {
        const source = resolveContentSource(
            resolved({ ownerId: 'me', isLocal: true, mode: 'independent' }),
            false,
        );
        expect(source).not.toBeNull();
        expect(source!.ownerParticipantId).toBe('me');
    });

    it('1:1 + LEGACY content → returns null (single video stays swapped to the screen, byte-identical)', () => {
        expect(resolveContentSource(resolved({ mode: 'legacy' }), false)).toBeNull();
    });

    // --- multi-party: unchanged ---------------------------------------------
    it('multi-party + INDEPENDENT content → surfaces the content source', () => {
        const source = resolveContentSource(resolved({ mode: 'independent' }), true);
        expect(source!.ownerParticipantId).toBe('r1');
    });

    it('multi-party + LEGACY content → STILL surfaces the content source (unchanged behavior)', () => {
        const source = resolveContentSource(resolved({ mode: 'legacy' }), true);
        expect(source).not.toBeNull();
        expect(source!.ownerParticipantId).toBe('r1');
    });

    it('carries the resolved content type through to the layout source', () => {
        const source = resolveContentSource(resolved({ type: 'worldCamera', mode: 'independent' }), true);
        expect(source!.type).toBe('worldCamera');
    });
});

describe('contentRendering — resolveContentScene aggregate', () => {
    it('combines local + remote sharers with a chosen primary', () => {
        const localContent = fakeStream();
        const r1Content = fakeStream();
        const input = baseInput({
            local: { cid: 'me', cameraMode: 'selfie', content: { active: true, type: 'screenShare', revision: 1 } },
            remotes: [{ cid: 'r1', content: { active: true, type: 'screenShare', revision: 1 }, supportsIndependentContentVideo: true }],
            remoteContentOrder: ['r1'],
            accessors: {
                getLocalContentStream: () => localContent,
                getRemoteContentStream: (cid) => (cid === 'r1' ? r1Content : undefined),
            },
        });
        const scene = resolveContentScene(input);
        expect(scene.local!.ownerId).toBe('me');
        expect(scene.remotes.map((r) => r.ownerId)).toEqual(['r1']);
        expect(scene.all.map((c) => c.ownerId).sort()).toEqual(['me', 'r1']);
        // Remote sharer wins primary.
        expect(scene.primary!.ownerId).toBe('r1');
    });

    it('flag-off/legacy: local sharing + remote content active → LOCAL is primary (byte-identical legacy order)', () => {
        const localStream = fakeStream();
        const remoteCamera = fakeStream();
        const input = baseInput({
            // Legacy local share: cameraMode='screenShare', no content field, no content stream.
            local: { cid: 'me', cameraMode: 'screenShare' },
            localStream,
            // Legacy remote sharer: no content field, signaled via content_state.
            remotes: [{ cid: 'r1' }],
            remoteStreams: new Map([['r1', remoteCamera]]),
            legacyRemoteContent: { cid: 'r1', contentType: 'screenShare' },
            // Most-recent remote-first heuristic would pick r1; legacy must pick local.
            remoteContentOrder: ['r1'],
            accessors: noStreams(),
        });
        const scene = resolveContentScene(input);
        expect(scene.local!.mode).toBe('legacy');
        expect(scene.remotes.map((r) => r.ownerId)).toEqual(['r1']);
        // Legacy order: local wins primary even though a remote content is active.
        expect(scene.primary!.ownerId).toBe('me');
        expect(scene.primary!.mode).toBe('legacy');
    });

    it('returns an empty scene when nobody is sharing', () => {
        const scene = resolveContentScene(baseInput({
            local: { cid: 'me', cameraMode: 'selfie' },
            remotes: [{ cid: 'r1' }],
        }));
        expect(scene.primary).toBeNull();
        expect(scene.local).toBeNull();
        expect(scene.remotes).toHaveLength(0);
        expect(scene.all).toHaveLength(0);
    });
});

describe('contentRendering — shouldRenderContentStage (phase gating)', () => {
    // --- inCall: existing behavior, unchanged ------------------------------
    it('inCall + multi-party → renders the content stage', () => {
        expect(shouldRenderContentStage({
            phase: 'inCall', isMultiParty: true, hasContentStageLayout: false,
        })).toBe(true);
    });

    it('inCall + 1:1 + content-stage layout present → renders the content stage', () => {
        expect(shouldRenderContentStage({
            phase: 'inCall', isMultiParty: false, hasContentStageLayout: true,
        })).toBe(true);
    });

    it('inCall + 1:1 + no content-stage layout → does NOT render (normal single/PIP)', () => {
        expect(shouldRenderContentStage({
            phase: 'inCall', isMultiParty: false, hasContentStageLayout: false,
        })).toBe(false);
    });

    // --- waiting: the regression this fixes --------------------------------
    it('waiting + 1:1 + local content-stage layout present (sharing before any remote) → renders the content stage', () => {
        // Local user started an independent screen share before anyone joined:
        // computedLayout is present (driven by the independent content source),
        // phase is still `waiting`. The content stage + "sharing, waiting for
        // participants" badge must surface, not be suppressed.
        expect(shouldRenderContentStage({
            phase: 'waiting', isMultiParty: false, hasContentStageLayout: true,
        })).toBe(true);
    });

    it('waiting + 1:1 + NOT sharing (no content-stage layout) → does NOT render (normal waiting layout unchanged)', () => {
        expect(shouldRenderContentStage({
            phase: 'waiting', isMultiParty: false, hasContentStageLayout: false,
        })).toBe(false);
    });

    it('waiting + multi-party flag alone does NOT force the stage (waiting is never multi-party; only content-stage layout matters)', () => {
        // Defensive: in `waiting` the gate keys on the resolved content-stage
        // layout, not on `isMultiParty` (which is false with no remotes).
        expect(shouldRenderContentStage({
            phase: 'waiting', isMultiParty: true, hasContentStageLayout: false,
        })).toBe(false);
    });

    // --- other phases: never render the content stage ----------------------
    it('non-active phases never render the content stage', () => {
        for (const phase of ['idle', 'joining', 'awaitingPermissions', 'ending', 'error'] as const) {
            expect(shouldRenderContentStage({ phase, isMultiParty: true, hasContentStageLayout: true })).toBe(false);
        }
    });
});

// ===========================================================================
// Stream-keyed stage tiles
// ===========================================================================

function content(over: Partial<ResolvedContent> = {}): ResolvedContent {
    return {
        ownerId: 'r1',
        isLocal: false,
        type: 'screenShare',
        mode: 'independent',
        stream: null,
        loading: false,
        waitingForParticipants: false,
        ...over,
    };
}

function camera(cid: string, isLocal: boolean): StageCameraParticipant {
    return { cid, isLocal };
}

describe('contentRendering — stage tile id encoding', () => {
    it('encodes and round-trips a {cid, kind} key', () => {
        expect(stageTileId({ cid: 'abc', kind: 'camera' })).toBe('abc::camera');
        expect(stageTileId({ cid: 'abc', kind: 'content' })).toBe('abc::content');
        expect(parseStageTileId('abc::camera')).toEqual({ cid: 'abc', kind: 'camera' });
        expect(parseStageTileId('abc::content')).toEqual({ cid: 'abc', kind: 'content' });
    });

    it('round-trips a cid that itself contains the separator', () => {
        // Server CIDs are opaque; lastIndexOf('::') keeps the kind unambiguous.
        const id = stageTileId({ cid: 'a::b', kind: 'content' });
        expect(id).toBe('a::b::content');
        expect(parseStageTileId(id)).toEqual({ cid: 'a::b', kind: 'content' });
    });

    it('returns null for malformed or unknown-kind ids', () => {
        expect(parseStageTileId('nokind')).toBeNull();
        expect(parseStageTileId('::camera')).toBeNull();
        expect(parseStageTileId('abc::audio')).toBeNull();
    });

    it('compares keys structurally (null-safe)', () => {
        expect(stageTileKeyEquals({ cid: 'a', kind: 'camera' }, { cid: 'a', kind: 'camera' })).toBe(true);
        expect(stageTileKeyEquals({ cid: 'a', kind: 'camera' }, { cid: 'a', kind: 'content' })).toBe(false);
        expect(stageTileKeyEquals({ cid: 'a', kind: 'camera' }, { cid: 'b', kind: 'camera' })).toBe(false);
        expect(stageTileKeyEquals(null, { cid: 'a', kind: 'camera' })).toBe(false);
        expect(stageTileKeyEquals({ cid: 'a', kind: 'camera' }, null)).toBe(false);
    });
});

describe('contentRendering — deriveStageTiles', () => {
    it('one content tile per active sharer (multiple simultaneous sharers)', () => {
        const tiles = deriveStageTiles({
            cameras: [],
            content: [
                content({ ownerId: 'r1', stream: fakeStream() }),
                content({ ownerId: 'r2', stream: fakeStream() }),
            ],
        });
        expect(tiles.filter((t) => t.kind === 'content').map((t) => t.cid)).toEqual(['r1', 'r2']);
        expect(tiles.every((t) => t.kind === 'content')).toBe(true);
    });

    it("INCLUDES the sharer's own camera as a real filmstrip tile (not excluded)", () => {
        // The sharer r1 has BOTH a camera tile and a content tile.
        const tiles = deriveStageTiles({
            cameras: [camera('r1', false), camera('me', true)],
            content: [content({ ownerId: 'r1', stream: fakeStream() })],
        });
        const ids = tiles.map((t) => t.id);
        expect(ids).toContain('r1::camera');
        expect(ids).toContain('r1::content');
        expect(ids).toContain('me::camera');
    });

    it("shows the LOCAL user's OWN screen as a content tile (self-preview)", () => {
        const tiles = deriveStageTiles({
            cameras: [camera('me', true)],
            content: [content({ ownerId: 'me', isLocal: true, stream: fakeStream() })],
        });
        const selfScreen = tiles.find((t) => t.id === 'me::content');
        expect(selfScreen).toBeDefined();
        expect(selfScreen!.isLocal).toBe(true);
    });

    it('orders remote cameras first, then local camera, then content tiles', () => {
        const tiles = deriveStageTiles({
            cameras: [camera('r1', false), camera('me', true)],
            content: [content({ ownerId: 'r1', stream: fakeStream() })],
        });
        expect(tiles.map((t) => t.id)).toEqual(['r1::camera', 'me::camera', 'r1::content']);
    });

    it('keeps an avatar camera tile for a participant whose camera is off', () => {
        // Video-off participants stay in the filmstrip as an avatar tile (identity +
        // audio status) so the strip never collapses to one stretched tile.
        const tiles = deriveStageTiles({
            cameras: [camera('r1', false), camera('me', true)],
            content: [content({ ownerId: 'r1', stream: fakeStream() })],
        });
        const ids = tiles.map((t) => t.id);
        expect(ids).toContain('r1::camera');
        expect(ids).toContain('r1::content');
        expect(ids).toContain('me::camera');
    });

    it('keeps an avatar camera tile for a camera-off / audio-only peer', () => {
        // Every participant shows in the filmstrip; a camera-off peer gets an avatar
        // camera tile rather than being dropped.
        const tiles = deriveStageTiles({
            cameras: [camera('r1', false), camera('r2', false), camera('me', true)],
            content: [content({ ownerId: 'r1', stream: fakeStream() })],
        });
        expect(tiles.some((t) => t.id === 'r2::camera')).toBe(true);
    });

    it('a held content tile (loading, no stream) is still a tile', () => {
        const tiles = deriveStageTiles({
            cameras: [],
            content: [content({ ownerId: 'r1', stream: null, loading: true })],
        });
        expect(tiles).toHaveLength(1);
        expect(tiles[0].id).toBe('r1::content');
    });

    it('skips a LEGACY-mode content entry — no duplicate tile for a mixed-room legacy sharer', () => {
        // Mixed room: r1 shares independently, r2 is a legacy peer whose single
        // video IS the content. r2's screen already renders as its camera tile,
        // so it must NOT also get a content tile (that would show one stream twice).
        const tiles = deriveStageTiles({
            cameras: [camera('r1', false), camera('r2', false)],
            content: [
                content({ ownerId: 'r1', stream: fakeStream(), mode: 'independent' }),
                content({ ownerId: 'r2', stream: fakeStream(), mode: 'legacy' }),
            ],
        });
        const ids = tiles.map((t) => t.id);
        expect(ids).toContain('r1::content');     // independent → content tile
        expect(ids).not.toContain('r2::content'); // legacy → NO content tile
        expect(ids).toContain('r2::camera');      // legacy sharer shows as a camera tile
    });
});

describe('contentRendering — pickStageSpotlightTileId', () => {
    const tiles = deriveStageTiles({
        cameras: [camera('r1', false), camera('me', true)],
        content: [
            content({ ownerId: 'r1', stream: fakeStream() }),
            content({ ownerId: 'r2', stream: fakeStream() }),
        ],
    });

    it('default spotlight = the most-recent share (contentScene.primary content tile)', () => {
        // r2 is the most-recent share → its content tile is spotlighted.
        const primary = content({ ownerId: 'r2', stream: fakeStream() });
        expect(pickStageSpotlightTileId(tiles, null, primary)).toBe('r2::content');
    });

    it('a pin overrides the default and can select ANY tile (a camera tile)', () => {
        const primary = content({ ownerId: 'r2', stream: fakeStream() });
        expect(pickStageSpotlightTileId(tiles, { cid: 'r1', kind: 'camera' }, primary)).toBe('r1::camera');
    });

    it('a pin can select a content tile other than the most-recent default', () => {
        const primary = content({ ownerId: 'r2', stream: fakeStream() });
        expect(pickStageSpotlightTileId(tiles, { cid: 'r1', kind: 'content' }, primary)).toBe('r1::content');
    });

    it('unpin (null) reverts to the most-recent-share default', () => {
        const primary = content({ ownerId: 'r2', stream: fakeStream() });
        expect(pickStageSpotlightTileId(tiles, null, primary)).toBe('r2::content');
    });

    it('a stale pin (tile no longer present) falls back to the default', () => {
        const primary = content({ ownerId: 'r2', stream: fakeStream() });
        // 'gone::camera' is not in the tile list (participant left / camera off).
        expect(pickStageSpotlightTileId(tiles, { cid: 'gone', kind: 'camera' }, primary)).toBe('r2::content');
    });

    it('falls back to the first tile when there is no content primary', () => {
        expect(pickStageSpotlightTileId(tiles, null, null)).toBe('r1::camera');
    });

    it('returns null when there are no tiles', () => {
        expect(pickStageSpotlightTileId([], { cid: 'r1', kind: 'camera' }, null)).toBeNull();
    });
});

// ===========================================================================
// 1:1 + share engages the stream-keyed stage (regression: legacy 1:1 did not)
// ===========================================================================

describe('contentRendering — 1:1 + share engages the filmstrip stage', () => {
    it('1:1 with an INDEPENDENT remote share produces stage tiles (camera + content)', () => {
        // One remote (r1) is sharing an independent screen; both cameras on.
        const scene = resolveContentScene(baseInput({
            local: { cid: 'me', cameraMode: 'selfie' },
            remotes: [{ cid: 'r1', content: { active: true, type: 'screenShare', revision: 1 }, supportsIndependentContentVideo: true }],
            accessors: {
                getLocalContentStream: () => null,
                getRemoteContentStream: (cid) => (cid === 'r1' ? fakeStream() : undefined),
            },
        }));
        // The stage is stream-keyed: an independent content resolved.
        expect(scene.all.some((c) => c.mode === 'independent')).toBe(true);

        const tiles = deriveStageTiles({
            cameras: [camera('r1', false), camera('me', true)],
            content: scene.all,
        });
        // 1:1 (2 participants) + a share → 3 tiles: both cameras + r1's screen.
        expect(tiles.map((t) => t.id)).toEqual(['r1::camera', 'me::camera', 'r1::content']);
        expect(pickStageSpotlightTileId(tiles, null, scene.primary)).toBe('r1::content');
    });

    it('1:1 LEGACY share resolves NO independent content (stays off the stream-keyed stage)', () => {
        // Flag-off: no content field, no content stream — legacy single-video path.
        const scene = resolveContentScene(baseInput({
            local: { cid: 'me', cameraMode: 'selfie' },
            remotes: [{ cid: 'r1' }],
            remoteStreams: new Map([['r1', fakeStream()]]),
            legacyRemoteContent: { cid: 'r1', contentType: 'screenShare' },
            accessors: noStreams(),
        }));
        // Content resolves but in LEGACY mode → stream-keyed stage does NOT engage.
        expect(scene.all.length).toBeGreaterThan(0);
        expect(scene.all.some((c) => c.mode === 'independent')).toBe(false);
    });
});
