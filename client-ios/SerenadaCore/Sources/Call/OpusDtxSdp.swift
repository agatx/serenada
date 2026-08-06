import Foundation

private func withDtxParameter(_ parameters: String) -> String {
    var values = parameters.split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    if let index = values.firstIndex(where: {
        $0.split(separator: "=", maxSplits: 1).first?.trimmingCharacters(in: .whitespaces).lowercased() == "usedtx"
    }) {
        values[index] = "usedtx=1"
    } else {
        values.append("usedtx=1")
    }
    return values.joined(separator: ";")
}

/// Advertise Opus DTX as the local receive preference in every audio media section.
func enableOpusDtxInSdp(_ sdp: String) -> String {
    let separator = sdp.contains("\r\n") ? "\r\n" : "\n"
    let hasTrailingSeparator = sdp.hasSuffix(separator)
    let body = hasTrailingSeparator ? String(sdp.dropLast(separator.count)) : sdp
    var lines = body.isEmpty ? [] : body.components(separatedBy: separator)
    let mediaStarts = lines.indices.filter { lines[$0].hasPrefix("m=") }

    for mediaIndex in mediaStarts.indices.reversed() {
        let start = mediaStarts[mediaIndex]
        guard lines[start].lowercased().hasPrefix("m=audio ") else { continue }
        let end = mediaIndex + 1 < mediaStarts.count ? mediaStarts[mediaIndex + 1] : lines.count
        let opusPayloads: [(payloadType: String, lineIndex: Int)] = (start..<end).compactMap { lineIndex in
            let lowercased = lines[lineIndex].lowercased()
            guard lowercased.hasPrefix("a=rtpmap:"), lowercased.contains(" opus/48000/2") else { return nil }
            guard let payloadType = lines[lineIndex].dropFirst("a=rtpmap:".count).split(separator: " ").first else { return nil }
            return (String(payloadType), lineIndex)
        }

        for opus in opusPayloads.reversed() {
            let prefix = "a=fmtp:\(opus.payloadType) "
            if let fmtpIndex = (start..<end).first(where: { lines[$0].lowercased().hasPrefix(prefix.lowercased()) }) {
                let parameters = String(lines[fmtpIndex].dropFirst(prefix.count))
                lines[fmtpIndex] = prefix + withDtxParameter(parameters)
            } else {
                lines.insert("a=fmtp:\(opus.payloadType) usedtx=1", at: opus.lineIndex + 1)
            }
        }
    }

    let result = lines.joined(separator: separator)
    return hasTrailingSeparator ? result + separator : result
}
