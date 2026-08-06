const withDtxParameter = (parameters: string): string => {
    const values = parameters.split(';').map(value => value.trim()).filter(Boolean);
    const dtxIndex = values.findIndex(value => value.split('=', 1)[0]?.trim().toLowerCase() === 'usedtx');
    if (dtxIndex >= 0) values[dtxIndex] = 'usedtx=1';
    else values.push('usedtx=1');
    return values.join(';');
};

/**
 * Advertise Opus DTX as the local receive preference in every audio media
 * section. Opus format parameters are directional, so both offers and answers
 * must carry `usedtx=1` for DTX to be active in both send directions.
 */
export const enableOpusDtxInSdp = (sdp: string): string => {
    const separator = sdp.includes('\r\n') ? '\r\n' : '\n';
    const hasTrailingSeparator = sdp.endsWith(separator);
    const body = hasTrailingSeparator ? sdp.slice(0, -separator.length) : sdp;
    const lines = body.length > 0 ? body.split(/\r?\n/) : [];
    const mediaStarts = lines.flatMap((line, index) => line.startsWith('m=') ? [index] : []);

    for (let mediaIndex = mediaStarts.length - 1; mediaIndex >= 0; mediaIndex -= 1) {
        const start = mediaStarts[mediaIndex];
        if (start === undefined || !lines[start]?.toLowerCase().startsWith('m=audio ')) continue;
        const end = mediaIndex + 1 < mediaStarts.length ? mediaStarts[mediaIndex + 1]! : lines.length;
        const opusPayloads = lines.slice(start, end).flatMap((line, offset) => {
            const match = line.match(/^a=rtpmap:(\d+)\s+opus\/48000(?:\/2)?\s*$/i);
            return match ? [{ payloadType: match[1]!, lineIndex: start + offset }] : [];
        });

        for (let payloadIndex = opusPayloads.length - 1; payloadIndex >= 0; payloadIndex -= 1) {
            const opus = opusPayloads[payloadIndex]!;
            const fmtpPattern = new RegExp(`^a=fmtp:${opus.payloadType}\\s+(.*)$`, 'i');
            const fmtpIndex = lines.slice(start, end).findIndex(line => fmtpPattern.test(line));
            if (fmtpIndex >= 0) {
                const absoluteIndex = start + fmtpIndex;
                const match = lines[absoluteIndex]!.match(fmtpPattern);
                lines[absoluteIndex] = `a=fmtp:${opus.payloadType} ${withDtxParameter(match?.[1] ?? '')}`;
            } else {
                lines.splice(opus.lineIndex + 1, 0, `a=fmtp:${opus.payloadType} usedtx=1`);
            }
        }
    }

    const result = lines.join(separator);
    return hasTrailingSeparator ? result + separator : result;
};
