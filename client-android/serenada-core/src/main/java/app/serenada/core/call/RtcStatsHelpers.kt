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

internal fun formatNumber(value: Double?, decimals: Int): String {
    val current = value ?: return "n/a"
    if (!current.isFinite()) return "n/a"
    return "%.${decimals}f".format(java.util.Locale.US, current)
}
