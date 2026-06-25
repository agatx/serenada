/**
 * Content (screen share) vs camera tile resolution for the call UI.
 *
 * Phase 2b of independent screen share. The core SDK now exposes content as an
 * independent stream (`getRemoteContentStream` / `getLocalContentStream`) and
 * carries `content { active, type, revision }` on each participant, separate
 * from camera (`cameraEnabled`, `cameraMode`). This module consumes that state
 * and decides, per render, what to present as content and what to present as
 * camera — WITHOUT inferring content from `cameraMode`.
 *
 * Backward / flag-off compatibility is the default path and must stay
 * byte-identical to today:
 *   - When `independentContentEnabled` is false, or a remote peer did not
 *     advertise `supportsIndependentContentVideo`, the single video that the SDK
 *     already routes (the local stream or `remoteStreams.get(cid)`) is presented
 *     as content, exactly as today.
 *   - The legacy "is the local user sharing" signal stays `cameraMode ===
 *     'screenShare'`, and the legacy remote signal stays the received
 *     `content_state` peer message — these drive content-active when there is
 *     no precise `content.active` to read.
 *
 * The functions here are pure and DOM-free so they can be unit tested in the
 * Node test environment (the package ships no jsdom).
 */

/** Subset of `LocalParticipant` this module reads. */
export interface ContentLocalParticipant {
    cid: string;
    /** Legacy single-video share signal (flag off). Never `'screenShare'` in independent mode. */
    cameraMode?: string;
    /** Precise content presentation state (independent mode). Absent on legacy peers / flag off. */
    content?: { active: boolean; type: string; revision: number };
}

/** Subset of a remote `Participant` this module reads. */
export interface ContentRemoteParticipant {
    cid: string;
    /** Precise content presentation state. Absent on legacy peers / flag off. */
    content?: { active: boolean; type: string; revision: number };
    /**
     * Whether this peer advertised independent content video at join.
     * Defaults false, matching the signaling contract.
     */
    supportsIndependentContentVideo?: boolean;
}

/** Legacy `content_state` peer-message signal for a remote sharer (flag-off path). */
export interface LegacyRemoteContentSignal {
    cid: string;
    contentType: 'screenShare' | 'worldCamera' | 'compositeCamera';
}

export type ContentType = 'screenShare' | 'worldCamera' | 'compositeCamera';

/**
 * Resolved content presentation for a single owner.
 *
 * `stream` is the content media to render, or `null` when content is active but
 * media has not arrived yet (`loading`). `mode` distinguishes the independent
 * content stream from the legacy single-video-as-content fallback so callers can
 * keep the legacy tile behavior unchanged.
 */
export interface ResolvedContent {
    ownerId: string;
    isLocal: boolean;
    type: ContentType;
    /** `'independent'` when a dedicated content stream exists; `'legacy'` for single-video fallback. */
    mode: 'independent' | 'legacy';
    /** Content media to render, or `null` while waiting for media (`loading`). */
    stream: MediaStream | null;
    /** Content is active per state but no content media has arrived for this owner yet. */
    loading: boolean;
    /**
     * Local-only: capture is live but no peer is receiving content media yet
     * (independent "start and wait"). The UI must show "sharing, waiting for
     * participants" rather than implying media is flowing.
     */
    waitingForParticipants: boolean;
}

/** Stream accessors the resolver needs from the session handle. */
export interface ContentStreamAccessors {
    /** Local content (screen share) stream, or null when not sharing / flag off. */
    getLocalContentStream: () => MediaStream | null | undefined;
    /** Remote content (screen share) stream for a peer, or undefined. */
    getRemoteContentStream: (cid: string) => MediaStream | undefined;
}

export interface ResolveContentInput {
    local: ContentLocalParticipant | null | undefined;
    /** The local camera/combined stream (`session.localStream`) — the legacy content fallback for the local user. */
    localStream: MediaStream | null | undefined;
    remotes: readonly ContentRemoteParticipant[];
    /** Per-cid legacy combined streams (`session.remoteStreams`) — the legacy content fallback for remote owners. */
    remoteStreams: ReadonlyMap<string, MediaStream>;
    /**
     * Whether the local session negotiated independent content video. When false,
     * all owners resolve through the legacy single-video path.
     */
    independentContentEnabled: boolean;
    /** Latest legacy `content_state` peer message for a single remote sharer (flag-off path). */
    legacyRemoteContent: LegacyRemoteContentSignal | null | undefined;
    accessors: ContentStreamAccessors;
    /**
     * Whether the local user can receive any video media at all
     * (`videoMediaEnabled`). When `false` the local user is an audio-only
     * receiver that never negotiated content receive and must suppress ALL
     * content UI even if room state says someone is sharing.
     */
    localVideoMediaEnabled: boolean;
    /**
     * Order in which remote content became active, most-recent LAST, as observed
     * locally. Used to pick the default primary among multiple simultaneous
     * remote sharers (local receive order). Cids not present fall back to array
     * order of `remotes`.
     */
    remoteContentOrder?: readonly string[];
}

function localContentActive(local: ContentLocalParticipant): boolean {
    // Independent mode: precise content.active. Legacy fallback: cameraMode.
    if (local.content) return local.content.active === true;
    return local.cameraMode === 'screenShare';
}

function localContentType(local: ContentLocalParticipant): ContentType {
    const t = local.content?.type;
    if (t === 'worldCamera' || t === 'compositeCamera') return t;
    return 'screenShare';
}

function remoteContentActive(
    remote: ContentRemoteParticipant,
    legacy: LegacyRemoteContentSignal | null | undefined,
): boolean {
    // Independent mode: precise content.active.
    if (remote.content) return remote.content.active === true;
    // Legacy fallback: the single received content_state peer message.
    return legacy?.cid === remote.cid;
}

function remoteContentType(
    remote: ContentRemoteParticipant,
    legacy: LegacyRemoteContentSignal | null | undefined,
): ContentType {
    const t = remote.content?.type;
    if (t === 'worldCamera' || t === 'compositeCamera' || t === 'screenShare') return t;
    if (legacy?.cid === remote.cid) return legacy.contentType;
    return 'screenShare';
}

/**
 * Resolve content for the local participant.
 *
 * Receiver-side hold and audio-only suppression are honored: when the local
 * user cannot receive video, no content is presented at all.
 */
export function resolveLocalContent(input: ResolveContentInput): ResolvedContent | null {
    const { local, accessors, localStream, localVideoMediaEnabled } = input;
    if (!local) return null;
    // Audio-only receivers never present content UI. Sharing is also blocked by
    // the SDK for the local user in this mode, so there is nothing to show.
    if (!localVideoMediaEnabled) return null;
    if (!localContentActive(local)) return null;

    // INDEPENDENT only when the build flag is on AND precise content state
    // exists AND it is a SCREEN SHARE. With the flag off, the single camera
    // video IS the content — byte-identical to today.
    const independent =
        input.independentContentEnabled &&
        local.content?.active === true &&
        localContentType(local) === 'screenShare';
    if (independent) {
        const independentStream = accessors.getLocalContentStream() ?? null;
        // Independent mode: dedicated content stream. "Waiting for participants"
        // when capture is live but the SDK reports no flowing track yet is a
        // host concern; here we surface the live independent stream.
        const hasLiveTrack = streamHasLiveVideo(independentStream);
        return {
            ownerId: local.cid,
            isLocal: true,
            type: localContentType(local),
            mode: 'independent',
            stream: hasLiveTrack ? independentStream : null,
            loading: !hasLiveTrack,
            // Capture live but no media flowing to peers yet: independent
            // start-and-wait. The dedicated content stream exists locally but is
            // not yet attached to any peer, so we still render the local preview;
            // the "waiting" hint is for the absence of receivers.
            waitingForParticipants: input.remotes.length === 0,
        };
    }

    // Legacy / flag-off path: the single video (localStream) IS the content.
    // Byte-identical to today's "screen replaces camera in localStream".
    return {
        ownerId: local.cid,
        isLocal: true,
        type: localContentType(local),
        mode: 'legacy',
        stream: localStream ?? null,
        loading: false,
        waitingForParticipants: false,
    };
}

/** Resolve content for every remote participant that is presenting. */
export function resolveRemoteContents(input: ResolveContentInput): ResolvedContent[] {
    const { remotes, accessors, remoteStreams, legacyRemoteContent, localVideoMediaEnabled } = input;
    // Audio-only receivers never negotiated content receive: suppress all.
    if (!localVideoMediaEnabled) return [];

    const resolved: ResolvedContent[] = [];
    for (const remote of remotes) {
        if (!remoteContentActive(remote, legacyRemoteContent)) continue;

        // INDEPENDENT is resolved PER PEER: the local build flag is on AND this
        // remote peer advertised independent-content capability AND its content
        // is active AND the content is a SCREEN SHARE. A NON-capable peer routes
        // its share through the single-video path, so it must resolve LEGACY even
        // if a stale/empty content stream accessor is present.
        const contentType = remoteContentType(remote, legacyRemoteContent);
        const independent =
            input.independentContentEnabled &&
            remote.supportsIndependentContentVideo === true &&
            remote.content?.active === true &&
            contentType === 'screenShare';
        if (independent) {
            const independentStream = accessors.getRemoteContentStream(remote.cid) ?? null;
            const hasLiveTrack = streamHasLiveVideo(independentStream);
            resolved.push({
                ownerId: remote.cid,
                isLocal: false,
                type: contentType,
                mode: 'independent',
                // Receiver-side hold is enforced by `remoteContentActive`: the
                // stream is only ever presented when content.active is true.
                stream: hasLiveTrack ? independentStream : null,
                loading: !hasLiveTrack,
                waitingForParticipants: false,
            });
            continue;
        }

        // Legacy / flag-off path: the single received video IS the content.
        const legacyStream = remoteStreams.get(remote.cid) ?? null;
        resolved.push({
            ownerId: remote.cid,
            isLocal: false,
            type: remoteContentType(remote, legacyRemoteContent),
            mode: 'legacy',
            stream: legacyStream,
            // A legacy single video is presented as content only when active; if
            // the track is not yet present, show a loading tile rather than a
            // stale camera frame (receiver-side hold).
            loading: legacyStream == null,
            waitingForParticipants: false,
        });
    }
    return resolved;
}

/**
 * Pick the primary content owner among local + multiple simultaneous remote
 * sharers.
 *
 * - **Independent mode** (design "Multiple Sharers"): the most-recently-received
 *   active REMOTE content is primary; local content is primary only when no
 *   remote content is active. This is an explicitly local heuristic — there is
 *   no server-stamped ordering.
 * - **Flag-off / legacy mode**: preserve the legacy `SerenadaCallFlow` content
 *   selection order, which chose LOCAL content FIRST (the local
 *   `isScreenSharing` branch ran before the `remoteContentState` branch), then a
 *   remote. The most-recently-active remote-first heuristic is an
 *   independent-mode feature and must NOT change the byte-identical legacy
 *   multi-party layout in calls where the local user is sharing AND a remote
 *   `content_state` is also active.
 *
 * Web keys independent-vs-legacy on content-STREAM presence rather than a config
 * flag (see module docs): the resolved `mode` already records this. A non-null
 * `local` resolves `mode === 'legacy'` only when the local user is legacy-sharing
 * with no independent content stream — i.e. exactly the flag-off case — so we
 * prefer local-first there, matching the legacy CallFlow.
 */
export function pickPrimaryContent(
    local: ResolvedContent | null,
    remotes: readonly ResolvedContent[],
    remoteContentOrder?: readonly string[],
): ResolvedContent | null {
    // A real screen share (independent content — the only kind that gets its own
    // content tile) always wins the spotlight over a camera-framing legacy
    // content_state (worldCamera/compositeCamera), which has NO content tile and
    // would otherwise leave the spotlight falling back to a camera. Prefer the
    // most-recently-active remote independent share, then the local independent
    // share. (Without this, a remote frontline peer in world-camera mode stole the
    // spotlight from the local user's actual screen share.)
    const independentRemotes = remotes.filter((r) => r.mode === 'independent');
    if (independentRemotes.length > 0) {
        if (remoteContentOrder && remoteContentOrder.length > 0) {
            // Most-recently-active is LAST in order. Walk back to front.
            for (let i = remoteContentOrder.length - 1; i >= 0; i -= 1) {
                const match = independentRemotes.find((r) => r.ownerId === remoteContentOrder[i]);
                if (match) return match;
            }
        }
        return independentRemotes[independentRemotes.length - 1];
    }
    if (local && local.mode === 'independent') {
        return local;
    }

    // Legacy / flag-off: local content wins first (byte-identical to the old
    // CallFlow `if (isScreenSharing) … else if (remoteContentState) …`). Falls
    // back to a remote legacy sharer when the local user is not sharing.
    if (local && local.mode === 'legacy') {
        return local;
    }
    if (remotes.length > 0) {
        if (remoteContentOrder && remoteContentOrder.length > 0) {
            // Most-recently-active is LAST in order. Walk back to front.
            for (let i = remoteContentOrder.length - 1; i >= 0; i -= 1) {
                const match = remotes.find((r) => r.ownerId === remoteContentOrder[i]);
                if (match) return match;
            }
        }
        // No order info: last in the remotes array (stable input order).
        return remotes[remotes.length - 1];
    }
    return local;
}

/**
 * Full content resolution for a render: per-owner resolved content plus the
 * chosen primary. `all` includes local + every remote sharer so callers can
 * render a content tile per sharer (design: per-peer/per-participant content).
 */
export interface ContentScene {
    primary: ResolvedContent | null;
    local: ResolvedContent | null;
    remotes: ResolvedContent[];
    all: ResolvedContent[];
}

export function resolveContentScene(input: ResolveContentInput): ContentScene {
    const local = resolveLocalContent(input);
    const remotes = resolveRemoteContents(input);
    const primary = pickPrimaryContent(local, remotes, input.remoteContentOrder);
    const all = local ? [...remotes, local] : [...remotes];
    return { primary, local, remotes, all };
}

/**
 * The content-role input to the layout (`computeLayout`'s `contentSource`), or
 * `null` when no content tile should render. This is the render-logic seam that
 * decides WHEN the content-stage layout fires — pure so it can be unit tested
 * without a DOM.
 *
 * Gating rules (the two paths differ deliberately):
 *   - **Multi-party** (`isMultiParty === true`): a content tile renders whenever
 *     a primary content owner is resolved — INDEPENDENT or LEGACY. This is the
 *     existing behavior; legacy multi-party already presents the single video as
 *     a content tile. Unchanged.
 *   - **1:1** (`isMultiParty === false`): a content tile renders ONLY when the
 *     primary content is INDEPENDENT (`mode === 'independent'`). In the LEGACY
 *     1:1 case the single video is physically swapped to the screen by the SDK
 *     and presented in the normal one-tile layout, so surfacing a content tile
 *     would double-render it. Gating strictly on `mode === 'independent'` keeps
 *     the legacy 1:1 single-tile experience byte-identical.
 *
 * `ResolvedContentSource` is structurally `computeLayout`'s `ContentSource`
 * (kept local to avoid a value import of the core type into the helper module).
 */
export interface ResolvedContentSource {
    type: ContentType;
    ownerParticipantId: string;
    aspectRatio: number | null;
}

export function resolveContentSource(
    primary: ResolvedContent | null,
    isMultiParty: boolean,
): ResolvedContentSource | null {
    if (!primary) return null;
    // 1:1 surfaces content only when it is an independent stream; legacy 1:1
    // stays the single swapped-video tile (byte-identical to today).
    if (!isMultiParty && primary.mode !== 'independent') return null;
    return { type: primary.type, ownerParticipantId: primary.ownerId, aspectRatio: null };
}

/**
 * The call phases during which the content-stage layout may render. Mirrors the
 * subset of `CallPhase` the component treats as an active call; kept local to
 * avoid a value import of the core type into this DOM-free helper.
 */
type ContentStagePhase =
    | 'idle'
    | 'awaitingPermissions'
    | 'joining'
    | 'waiting'
    | 'inCall'
    | 'ending'
    | 'error';

export interface ShouldRenderContentStageInput {
    /** Current call phase. */
    phase: ContentStagePhase;
    /** True when more than one remote stream is present (multi-party). */
    isMultiParty: boolean;
    /**
     * True when a content-stage layout has resolved for this render — i.e. there
     * is a pin or an (independent) content source to present. In 1:1 this is only
     * ever true for INDEPENDENT content; legacy 1:1 keeps the single swapped-video
     * tile and never resolves a content-stage layout, so this stays false there.
     */
    hasContentStageLayout: boolean;
}

/**
 * Decide WHEN the content-stage render branch fires, gated on the call phase.
 * Pure so it can be unit tested without a DOM.
 *
 * The phase gate is the only thing widening here:
 *   - **`inCall`** (existing): renders when multi-party OR a content-stage layout
 *     is present (1:1 independent content). Unchanged.
 *   - **`waiting`** (the fix): renders ONLY when a content-stage layout is present
 *     — which in 1:1 means the local user has started an INDEPENDENT screen share
 *     before any remote joined. This surfaces the local content preview + the
 *     "sharing, waiting for participants" badge instead of the normal waiting
 *     layout. `isMultiParty` is irrelevant in `waiting` (there are no remotes),
 *     so the gate keys on the resolved layout. When the user is NOT sharing,
 *     `hasContentStageLayout` is false and the normal waiting UI is unchanged.
 *     Legacy/flag-off 1:1 never resolves a content-stage layout, so this path
 *     stays byte-identical to today.
 *   - all other phases: never render the content stage.
 */
export function shouldRenderContentStage({
    phase,
    isMultiParty,
    hasContentStageLayout,
}: ShouldRenderContentStageInput): boolean {
    if (phase === 'inCall') {
        return isMultiParty || hasContentStageLayout;
    }
    if (phase === 'waiting') {
        return hasContentStageLayout;
    }
    return false;
}

/** True when a stream carries at least one live (non-ended), enabled video track. */
function streamHasLiveVideo(stream: MediaStream | null | undefined): boolean {
    if (!stream) return false;
    const tracks = stream.getVideoTracks();
    if (tracks.length === 0) return false;
    return tracks.some((t) => t.readyState !== 'ended' && t.enabled !== false);
}

// ===========================================================================
// Stream-keyed stage tiles (filmstrip + spotlight for active content)
//
// When ANY participant is presenting content the web call UI switches to a
// single filmstrip+spotlight stage where EVERY stream is its own tile, keyed by
// `{cid, kind}`:
//   - a CAMERA tile for every participant whose camera is on (local + remote),
//   - a CONTENT tile for every participant presenting content (local + remote),
//     including the LOCAL user's own screen (self-preview, pinnable).
// A sharer's camera is therefore a real filmstrip tile alongside their screen,
// not a PIP over the content. Multiple simultaneous sharers each get a content
// tile. The tile model is intentionally stream-keyed (not participant-keyed) so
// one participant can occupy two tiles at once.
//
// These helpers are pure / DOM-free so they unit-test in the Node test env.
// ===========================================================================

export type StageTileKind = 'camera' | 'content';

/** Identity of a single stage tile: a participant cid + which stream of theirs. */
export interface StageTileKey {
    cid: string;
    kind: StageTileKind;
}

/** A derived stage tile. `id` is the stable string key fed to `computeLayout`. */
export interface StageTile {
    /** `"<cid>::<kind>"` — opaque to the layout engine, parsed back by the UI. */
    id: string;
    cid: string;
    kind: StageTileKind;
    isLocal: boolean;
}

/** Stable string id for a stage tile (the `SceneParticipant.id` fed to layout). */
export function stageTileId(key: StageTileKey): string {
    return `${key.cid}::${key.kind}`;
}

/** Parse a stage tile id back into its `{cid, kind}` key, or null if malformed. */
export function parseStageTileId(id: string): StageTileKey | null {
    const sep = id.lastIndexOf('::');
    if (sep <= 0) return null;
    const cid = id.slice(0, sep);
    const kind = id.slice(sep + 2);
    if (kind !== 'camera' && kind !== 'content') return null;
    return { cid, kind };
}

export function stageTileKeyEquals(a: StageTileKey | null, b: StageTileKey | null): boolean {
    if (!a || !b) return false;
    return a.cid === b.cid && a.kind === b.kind;
}

/** A participant whose camera/avatar tile the stage needs (local or remote). */
export interface StageCameraParticipant {
    cid: string;
    isLocal: boolean;
}

export interface DeriveStageTilesInput {
    /** Camera participants in stable order (remotes first, local last is enforced). */
    cameras: readonly StageCameraParticipant[];
    /**
     * Resolved content for every active sharer (`ContentScene.all`), already
     * audio-only-suppressed and receiver-side-held by `resolveContentScene`.
     * Each becomes a content tile regardless of whether its media has arrived
     * yet (a held tile renders a loading spinner, exactly like today).
     */
    content: readonly ResolvedContent[];
}

/**
 * Derive the full ordered stream-keyed tile list for the stage.
 *
 * Order (stable, matches the engine's "local last" filmstrip convention):
 *   1. remote camera tiles (input order),
 *   2. local camera tile,
 *   3. content tiles (input order; local content last via `ContentScene.all`).
 *
 * A sharer's camera/avatar tile and content are BOTH present. Content
 * tiles are emitted only for INDEPENDENT content; a legacy single-video sharer
 * shows up as their camera tile (the screen replaced the camera), so they are
 * not duplicated as a content tile. Every participant gets a camera tile (an
 * avatar/placeholder when their camera is off) so the filmstrip keeps everyone
 * and never collapses to one stretched tile. Suppression for audio-only RECEIVERS
 * is already handled upstream (content is empty).
 */
export function deriveStageTiles(input: DeriveStageTilesInput): StageTile[] {
    const tiles: StageTile[] = [];

    // Every participant gets a camera tile while content is active: a live tile
    // when their camera is on, otherwise an avatar/placeholder tile (identity +
    // audio status). Without this a video-off peer vanished from the filmstrip and
    // a lone remaining tile stretched to fill the whole strip.
    const remoteCameras = input.cameras.filter((c) => !c.isLocal);
    const localCameras = input.cameras.filter((c) => c.isLocal);

    for (const cam of remoteCameras) {
        tiles.push({ id: stageTileId({ cid: cam.cid, kind: 'camera' }), cid: cam.cid, kind: 'camera', isLocal: false });
    }
    for (const cam of localCameras) {
        tiles.push({ id: stageTileId({ cid: cam.cid, kind: 'camera' }), cid: cam.cid, kind: 'camera', isLocal: true });
    }
    for (const c of input.content) {
        // Only INDEPENDENT content is its own tile. A legacy sharer's single
        // video already shows as their CAMERA tile (the screen replaces the
        // camera in legacy mode), so emitting a content tile too would render
        // the same one stream twice in a mixed independent+legacy room.
        if (c.mode !== 'independent') continue;
        tiles.push({ id: stageTileId({ cid: c.ownerId, kind: 'content' }), cid: c.ownerId, kind: 'content', isLocal: c.isLocal });
    }

    return tiles;
}

/**
 * Resolve the spotlight (primary) tile id among the derived tiles.
 *
 * - A `pinnedTile` wins whenever its tile is still present (pin ANY tile, camera
 *   OR content). Click-to-unpin reverts to the default below.
 * - Default spotlight = the MOST-RECENT active share, reusing the same primary
 *   chosen by `pickPrimaryContent` (`contentPrimary`) — surfaced as that owner's
 *   CONTENT tile.
 * - Fallbacks (no pin, no content primary tile present): the first tile, then
 *   null when there are no tiles at all.
 */
export function pickStageSpotlightTileId(
    tiles: readonly StageTile[],
    pinnedTile: StageTileKey | null | undefined,
    contentPrimary: ResolvedContent | null,
): string | null {
    if (tiles.length === 0) return null;

    if (pinnedTile) {
        const pinnedId = stageTileId(pinnedTile);
        if (tiles.some((t) => t.id === pinnedId)) return pinnedId;
    }

    if (contentPrimary) {
        const primaryId = stageTileId({ cid: contentPrimary.ownerId, kind: 'content' });
        if (tiles.some((t) => t.id === primaryId)) return primaryId;
    }

    return tiles[0].id;
}
