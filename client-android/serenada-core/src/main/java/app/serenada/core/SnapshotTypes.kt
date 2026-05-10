package app.serenada.core

/**
 * Identifies which video stream a snapshot should capture.
 */
sealed interface SnapshotSource {
    /** The local camera/screen stream. */
    object Local : SnapshotSource

    /** A specific remote participant's stream, addressed by their per-call CID. */
    data class Remote(val cid: String) : SnapshotSource
}

/**
 * A single decoded JPEG frame plus its metadata, returned from
 * [SerenadaSession.captureSnapshot] on success.
 *
 * @property jpeg encoded JPEG bytes at the source video track's full intrinsic resolution
 * @property width pixel width of the captured frame after rotation
 * @property height pixel height of the captured frame after rotation
 * @property timestampMs wall-clock time the frame was captured (`System.currentTimeMillis()`)
 * @property source which stream the frame was pulled from
 */
data class SnapshotResult(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val source: SnapshotSource,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SnapshotResult) return false
        return jpeg.contentEquals(other.jpeg) &&
            width == other.width &&
            height == other.height &&
            timestampMs == other.timestampMs &&
            source == other.source
    }

    override fun hashCode(): Int {
        var result = jpeg.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + source.hashCode()
        return result
    }
}

/**
 * Errors thrown by [SerenadaSession.captureSnapshot]. Each variant has a
 * machine-readable identity via type checks (e.g. `is SnapshotError.StreamNotActive`).
 */
sealed class SnapshotError(message: String) : RuntimeException(message) {
    /** The session has no active session, or the chosen stream's video is off. */
    object StreamNotActive : SnapshotError("Stream not active") {
        private fun readResolve(): Any = StreamNotActive
    }

    /** The track exists but has no video component. */
    object NoVideoTrack : SnapshotError("No video track") {
        private fun readResolve(): Any = NoVideoTrack
    }

    /** No frame arrived within the configured timeout. */
    object CaptureTimeout : SnapshotError("Capture timeout") {
        private fun readResolve(): Any = CaptureTimeout
    }

    /** Frame encoding failed (zero dimensions, encoder error, etc.). */
    data class CaptureFailed(val reason: String) : SnapshotError(reason)

    /** Reserved for future source variants — not currently emitted. */
    object UnsupportedSource : SnapshotError("Unsupported source") {
        private fun readResolve(): Any = UnsupportedSource
    }
}
