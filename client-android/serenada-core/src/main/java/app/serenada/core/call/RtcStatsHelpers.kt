package app.serenada.core.call

import org.webrtc.RTCStats

internal fun memberString(stat: RTCStats?, key: String): String? {
    val value = stat?.members?.get(key) ?: return null
    return value.toString().ifBlank { null }
}

internal fun memberDouble(stat: RTCStats?, key: String): Double? {
    val value = stat?.members?.get(key) ?: return null
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

internal fun memberLong(stat: RTCStats?, key: String): Long? {
    val value = stat?.members?.get(key) ?: return null
    return when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

internal fun memberBoolean(stat: RTCStats?, key: String): Boolean? {
    val value = stat?.members?.get(key) ?: return null
    return when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }
}

internal fun getMediaKind(stat: RTCStats?): String? {
    val kind = memberString(stat, "kind") ?: memberString(stat, "mediaType")
    return if (kind == "audio" || kind == "video") kind else null
}

internal fun joinCodecMimeTypes(values: Iterable<String>): String? {
    return values
        .flatMap { it.split(" | ") }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
        .joinToString(" | ")
        .ifBlank { null }
}

internal fun firstAudioCodecMimeType(sdp: String?): String? {
    if (sdp.isNullOrBlank()) return null
    val lines = sdp.lineSequence().map(String::trim).toList()
    val audioLineIndex = lines.indexOfFirst { it.startsWith("m=audio ") }
    if (audioLineIndex < 0) return null
    val firstPayloadType = lines[audioLineIndex].split(Regex("\\s+")).getOrNull(3) ?: return null

    for (index in (audioLineIndex + 1) until lines.size) {
        val line = lines[index]
        if (line.startsWith("m=")) break
        val match = Regex("^a=rtpmap:(\\S+)\\s+([^/\\s]+)", RegexOption.IGNORE_CASE).find(line) ?: continue
        if (match.groupValues[1] == firstPayloadType) {
            return "audio/${match.groupValues[2].lowercase()}"
        }
    }
    return null
}

// Native WebRTC stats report the inner Opus codec for an RTP stream wrapped
// in RED. The negotiated answer carries the outer codec that is actually sent.
internal fun effectiveAudioCodecMimeType(
    statsCodecMimeType: String?,
    negotiatedAnswerCodecMimeType: String?,
): String? {
    return if (
        statsCodecMimeType.equals("audio/opus", ignoreCase = true) &&
        negotiatedAnswerCodecMimeType.equals("audio/red", ignoreCase = true)
    ) {
        negotiatedAnswerCodecMimeType
    } else {
        statsCodecMimeType
    }
}

internal fun formatNumber(value: Double?, decimals: Int): String {
    val current = value ?: return "n/a"
    if (!current.isFinite()) return "n/a"
    return "%.${decimals}f".format(java.util.Locale.US, current)
}
