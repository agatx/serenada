package app.serenada.core.call

private fun withDtxParameter(parameters: String): String {
    val values = parameters.split(';').map(String::trim).filter(String::isNotEmpty).toMutableList()
    val dtxIndex = values.indexOfFirst { value ->
        value.substringBefore('=').trim().equals("usedtx", ignoreCase = true)
    }
    if (dtxIndex >= 0) values[dtxIndex] = "usedtx=1" else values.add("usedtx=1")
    return values.joinToString(";")
}

/** Advertise Opus DTX as the local receive preference in every audio media section. */
internal fun enableOpusDtxInSdp(sdp: String): String {
    val separator = if (sdp.contains("\r\n")) "\r\n" else "\n"
    val hasTrailingSeparator = sdp.endsWith(separator)
    val body = if (hasTrailingSeparator) sdp.dropLast(separator.length) else sdp
    val lines = if (body.isEmpty()) mutableListOf() else body.split(Regex("\\r?\\n")).toMutableList()
    val mediaStarts = lines.indices.filter { lines[it].startsWith("m=") }

    for (mediaIndex in mediaStarts.indices.reversed()) {
        val start = mediaStarts[mediaIndex]
        if (!lines[start].startsWith("m=audio ", ignoreCase = true)) continue
        val end = mediaStarts.getOrNull(mediaIndex + 1) ?: lines.size
        val opusPayloads = (start until end).mapNotNull { lineIndex ->
            val match = OPUS_RTPMAP.matchEntire(lines[lineIndex]) ?: return@mapNotNull null
            OpusPayload(match.groupValues[1], lineIndex)
        }

        for (opus in opusPayloads.asReversed()) {
            val fmtpPattern = Regex("^a=fmtp:${Regex.escape(opus.payloadType)}\\s+(.*)$", RegexOption.IGNORE_CASE)
            val fmtpIndex = (start until end).firstOrNull { fmtpPattern.matches(lines[it]) }
            if (fmtpIndex != null) {
                val parameters = fmtpPattern.matchEntire(lines[fmtpIndex])?.groupValues?.get(1).orEmpty()
                lines[fmtpIndex] = "a=fmtp:${opus.payloadType} ${withDtxParameter(parameters)}"
            } else {
                lines.add(opus.lineIndex + 1, "a=fmtp:${opus.payloadType} usedtx=1")
            }
        }
    }

    val result = lines.joinToString(separator)
    return if (hasTrailingSeparator) result + separator else result
}

private data class OpusPayload(val payloadType: String, val lineIndex: Int)

private val OPUS_RTPMAP = Regex(
    "^a=rtpmap:(\\d+)\\s+opus/48000(?:/2)?\\s*$",
    RegexOption.IGNORE_CASE,
)
