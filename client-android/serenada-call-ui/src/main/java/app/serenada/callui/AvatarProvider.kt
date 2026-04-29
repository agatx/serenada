package app.serenada.callui

import android.graphics.Bitmap

/**
 * Resolves an avatar for a remote participant's host-supplied `peerId` (passed to
 * `SerenadaCore.join`). The call UI renders the returned avatar cover-fit, cropped
 * to a circle above the participant's name when their remote video track is off.
 *
 * Behavior:
 * - Each `peerId` is resolved at most once per call and cached for the call's lifetime.
 * - Called lazily on the first frame the placeholder is needed — silent peers don't
 *   trigger a fetch.
 * - Returning `null` or throwing falls back to an initials placeholder.
 * - The call UI never blocks on the resolver; it shows initials immediately and
 *   swaps in the avatar when the suspend function returns.
 */
fun interface AvatarProvider {
    suspend fun resolve(peerId: String): AvatarSource?
}

/** Image payload returned by an [AvatarProvider]. */
sealed class AvatarSource {
    /** A remote URL that the call UI fetches and decodes itself (no host image-loading library required). */
    data class Url(val url: String) : AvatarSource()

    /** Encoded image bytes (e.g. JPEG, PNG); decoded once at render time. */
    data class Bytes(val bytes: ByteArray) : AvatarSource() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** A pre-decoded bitmap. */
    data class Bitmap(val bitmap: android.graphics.Bitmap) : AvatarSource()
}
