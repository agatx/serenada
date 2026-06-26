/**
 * Canonical room-token extraction shared by {@link SerenadaCore} and (Phase 3)
 * {@link SerenadaCallRegistry}. The token after `/call/` is the room identity;
 * scheme/host/query/fragment and trailing slashes are ignored, so both
 * `serenada.app` and `serenada-app.ru` URLs for the same token collapse to one
 * `roomId`. This is the dedup key behind the registry's call-identity policy
 * (one live call per canonical roomId; contract §7).
 *
 * @module
 */

/**
 * Extract the canonical room token from a room URL, or return the input
 * unchanged when it is already a bare room id. Host-agnostic: only the path
 * segment after `/call/` matters. Falls back to the last path segment, then the
 * raw input, so a bare id passes through untouched.
 */
export function canonicalizeRoomId(urlOrId: string): string {
    try {
        const parsed = new URL(urlOrId);
        const parts = parsed.pathname.split('/');
        const callIndex = parts.indexOf('call');
        if (callIndex !== -1 && parts[callIndex + 1]) {
            return parts[callIndex + 1];
        }
        // Fallback: last non-empty path segment.
        return parts[parts.length - 1] || urlOrId;
    } catch {
        // Not a URL (a bare room id): use it verbatim.
        return urlOrId;
    }
}
