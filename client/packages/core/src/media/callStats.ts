import type { CallStats, SerenadaLogger } from '../types.js';

interface MediaTotals {
    inboundPacketsReceived: number;
    inboundPacketsLost: number;
    inboundBytes: number;
    inboundJitterSumSeconds: number;
    inboundJitterCount: number;
    inboundJitterBufferDelaySeconds: number;
    inboundJitterBufferEmittedCount: number;
    inboundConcealedSamples: number;
    inboundTotalSamples: number;
    inboundFpsSum: number;
    inboundFpsCount: number;
    inboundFrameWidth: number;
    inboundFrameHeight: number;
    inboundFramesDecoded: number;
    inboundFreezeCount: number;
    inboundFreezeDurationSeconds: number;
    outboundPacketsSent: number;
    outboundBytes: number;
    outboundPacketsRetransmitted: number;
    remoteInboundPacketsLost: number;
}

interface StatsSample {
    timestampMs: number;
    audioRxBytes: number;
    audioTxBytes: number;
    videoRxBytes: number;
    videoTxBytes: number;
    videoFramesDecoded: number;
}

interface FreezeSample {
    timestampMs: number;
    freezeCount: number;
    freezeDurationSeconds: number;
}

const createMediaTotals = (): MediaTotals => ({
    inboundPacketsReceived: 0, inboundPacketsLost: 0, inboundBytes: 0,
    inboundJitterSumSeconds: 0, inboundJitterCount: 0,
    inboundJitterBufferDelaySeconds: 0, inboundJitterBufferEmittedCount: 0,
    inboundConcealedSamples: 0, inboundTotalSamples: 0,
    inboundFpsSum: 0, inboundFpsCount: 0,
    inboundFrameWidth: 0, inboundFrameHeight: 0, inboundFramesDecoded: 0,
    inboundFreezeCount: 0, inboundFreezeDurationSeconds: 0,
    outboundPacketsSent: 0, outboundBytes: 0, outboundPacketsRetransmitted: 0,
    remoteInboundPacketsLost: 0,
});

const asStatMap = (stat: RTCStats): Record<string, unknown> => stat as unknown as Record<string, unknown>;
const getStatNumber = (stat: RTCStats, key: string): number | null => {
    const value = asStatMap(stat)[key];
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
};
const getStatString = (stat: RTCStats, key: string): string | null => {
    const value = asStatMap(stat)[key];
    return typeof value === 'string' ? value : null;
};
const getStatBoolean = (stat: RTCStats, key: string): boolean | null => {
    const value = asStatMap(stat)[key];
    return typeof value === 'boolean' ? value : null;
};
const getMediaKind = (stat: RTCStats): 'audio' | 'video' | null => {
    const kind = getStatString(stat, 'kind') ?? getStatString(stat, 'mediaType');
    return kind === 'audio' || kind === 'video' ? kind : null;
};

const calculateBitrateKbps = (previousBytes: number, currentBytes: number, elapsedSeconds: number): number | null => {
    if (elapsedSeconds <= 0 || currentBytes < previousBytes) return null;
    return (currentBytes - previousBytes) * 8 / elapsedSeconds / 1000;
};

const ratioPercent = (numerator: number, denominator: number): number | null => {
    if (denominator <= 0) return null;
    return (numerator / denominator) * 100;
};

export class CallStatsCollector {
    private statsSample: StatsSample | null = null;
    private freezeSamples: FreezeSample[] = [];
    private timer: number | null = null;
    private _stats: CallStats | null = null;
    private onChange: (() => void) | null = null;
    private logger?: SerenadaLogger;

    constructor(logger?: SerenadaLogger) {
        this.logger = logger;
    }

    get stats(): CallStats | null { return this._stats; }

    start(getPeerConnections: () => RTCPeerConnection[], onChange: () => void): void {
        this.stop();
        this.onChange = onChange;
        this.timer = window.setInterval(() => {
            void this.poll(getPeerConnections());
        }, 1000);
    }

    stop(): void {
        if (this.timer !== null) { window.clearInterval(this.timer); this.timer = null; }
        this._stats = null;
        this.statsSample = null;
        this.freezeSamples = [];
        this.onChange = null;
    }

    reset(): void {
        this.statsSample = null;
        this.freezeSamples = [];
    }

    private async poll(pcs: RTCPeerConnection[]): Promise<void> {
        if (pcs.length === 0) { this._stats = null; return; }

        try {
            const reports = await Promise.all(pcs.map(pc => pc.getStats()));
            const statsById = new Map<string, RTCStats>();
            reports.forEach((r, i) => {
                const prefix = `p${i}:`;
                r.forEach(stat => {
                    const namespacedId = prefix + stat.id;
                    statsById.set(namespacedId, { ...stat, id: namespacedId } as RTCStats);
                });
            });

            const media = { audio: createMediaTotals(), video: createMediaTotals() };
            let selectedCandidatePair: RTCStats | null = null;
            let fallbackCandidatePair: RTCStats | null = null;
            let remoteInboundRttSumSeconds = 0;
            let remoteInboundRttCount = 0;

            statsById.forEach(stat => {
                if (stat.type === 'candidate-pair') {
                    const isSelected = getStatBoolean(stat, 'selected') === true;
                    const isNominated = getStatBoolean(stat, 'nominated') === true;
                    const pairState = getStatString(stat, 'state');
                    if (isSelected) selectedCandidatePair = stat;
                    else if (!fallbackCandidatePair && isNominated && pairState === 'succeeded') fallbackCandidatePair = stat;
                    return;
                }

                const kind = getMediaKind(stat);
                if (!kind) return;
                const bucket = media[kind];

                if (stat.type === 'inbound-rtp') {
                    bucket.inboundPacketsReceived += getStatNumber(stat, 'packetsReceived') ?? 0;
                    bucket.inboundPacketsLost += Math.max(0, getStatNumber(stat, 'packetsLost') ?? 0);
                    bucket.inboundBytes += getStatNumber(stat, 'bytesReceived') ?? 0;
                    const jitter = getStatNumber(stat, 'jitter');
                    if (jitter !== null) { bucket.inboundJitterSumSeconds += jitter; bucket.inboundJitterCount += 1; }
                    bucket.inboundJitterBufferDelaySeconds += getStatNumber(stat, 'jitterBufferDelay') ?? 0;
                    bucket.inboundJitterBufferEmittedCount += getStatNumber(stat, 'jitterBufferEmittedCount') ?? 0;
                    bucket.inboundConcealedSamples += getStatNumber(stat, 'concealedSamples') ?? 0;
                    bucket.inboundTotalSamples += getStatNumber(stat, 'totalSamplesReceived') ?? 0;
                    const fps = getStatNumber(stat, 'framesPerSecond');
                    bucket.inboundFpsSum += fps ?? 0;
                    bucket.inboundFpsCount += fps !== null ? 1 : 0;
                    bucket.inboundFrameWidth = Math.max(bucket.inboundFrameWidth, Math.round(getStatNumber(stat, 'frameWidth') ?? 0));
                    bucket.inboundFrameHeight = Math.max(bucket.inboundFrameHeight, Math.round(getStatNumber(stat, 'frameHeight') ?? 0));
                    bucket.inboundFramesDecoded += getStatNumber(stat, 'framesDecoded') ?? 0;
                    bucket.inboundFreezeCount += getStatNumber(stat, 'freezeCount') ?? 0;
                    bucket.inboundFreezeDurationSeconds += getStatNumber(stat, 'totalFreezesDuration') ?? 0;
                    return;
                }
                if (stat.type === 'outbound-rtp') {
                    bucket.outboundPacketsSent += getStatNumber(stat, 'packetsSent') ?? 0;
                    bucket.outboundBytes += getStatNumber(stat, 'bytesSent') ?? 0;
                    bucket.outboundPacketsRetransmitted += getStatNumber(stat, 'retransmittedPacketsSent') ?? 0;
                    return;
                }
                if (stat.type === 'remote-inbound-rtp') {
                    bucket.remoteInboundPacketsLost += Math.max(0, getStatNumber(stat, 'packetsLost') ?? 0);
                    const remoteRtt = getStatNumber(stat, 'roundTripTime');
                    if (remoteRtt !== null) { remoteInboundRttSumSeconds += remoteRtt; remoteInboundRttCount += 1; }
                }
            });

            if (!selectedCandidatePair) selectedCandidatePair = fallbackCandidatePair;
            const selectedPair = selectedCandidatePair as RTCStats | null;
            const pairPrefix = selectedPair ? selectedPair.id.substring(0, selectedPair.id.indexOf(':') + 1) : '';
            const localCandidate = selectedPair ? statsById.get(pairPrefix + (getStatString(selectedPair, 'localCandidateId') ?? '')) : null;
            const remoteCandidate = selectedPair ? statsById.get(pairPrefix + (getStatString(selectedPair, 'remoteCandidateId') ?? '')) : null;

            const localCandidateType = localCandidate ? getStatString(localCandidate, 'candidateType') : null;
            const remoteCandidateType = remoteCandidate ? getStatString(remoteCandidate, 'candidateType') : null;
            const localProtocol = localCandidate ? getStatString(localCandidate, 'protocol') : null;
            const remoteProtocol = remoteCandidate ? getStatString(remoteCandidate, 'protocol') : null;
            const isRelay = localCandidateType === 'relay' || remoteCandidateType === 'relay';
            const transportPath = localCandidateType || remoteCandidateType
                ? `${isRelay ? 'TURN relay' : 'Direct'} (${localCandidateType ?? 'n/a'} -> ${remoteCandidateType ?? 'n/a'}, ${localProtocol ?? remoteProtocol ?? 'n/a'})`
                : null;

            const candidateRttSeconds = selectedPair ? getStatNumber(selectedPair, 'currentRoundTripTime') : null;
            const remoteInboundRttSeconds = remoteInboundRttCount > 0 ? (remoteInboundRttSumSeconds / remoteInboundRttCount) : null;
            const chosenRttSeconds = candidateRttSeconds ?? remoteInboundRttSeconds;
            const rttMs = chosenRttSeconds !== null ? chosenRttSeconds * 1000 : null;
            const availableOutgoingBitrate = selectedPair ? getStatNumber(selectedPair, 'availableOutgoingBitrate') : null;
            const availableOutgoingKbps = availableOutgoingBitrate !== null ? availableOutgoingBitrate / 1000 : null;

            const now = Date.now();
            const previousSample = this.statsSample;
            const elapsedSeconds = previousSample ? (now - previousSample.timestampMs) / 1000 : 0;

            const audioRxKbps = previousSample ? calculateBitrateKbps(previousSample.audioRxBytes, media.audio.inboundBytes, elapsedSeconds) : null;
            const audioTxKbps = previousSample ? calculateBitrateKbps(previousSample.audioTxBytes, media.audio.outboundBytes, elapsedSeconds) : null;
            const videoRxKbps = previousSample ? calculateBitrateKbps(previousSample.videoRxBytes, media.video.inboundBytes, elapsedSeconds) : null;
            const videoTxKbps = previousSample ? calculateBitrateKbps(previousSample.videoTxBytes, media.video.outboundBytes, elapsedSeconds) : null;

            let videoFps: number | null = null;
            if (media.video.inboundFpsCount > 0) {
                videoFps = media.video.inboundFpsSum / media.video.inboundFpsCount;
            } else if (previousSample && elapsedSeconds > 0 && media.video.inboundFramesDecoded >= previousSample.videoFramesDecoded) {
                videoFps = (media.video.inboundFramesDecoded - previousSample.videoFramesDecoded) / elapsedSeconds;
            }

            this.freezeSamples.push({ timestampMs: now, freezeCount: media.video.inboundFreezeCount, freezeDurationSeconds: media.video.inboundFreezeDurationSeconds });
            this.freezeSamples = this.freezeSamples.filter(sample => now - sample.timestampMs <= 60_000);
            const freezeWindowBase = this.freezeSamples[0];
            const videoFreezeCount60s = freezeWindowBase ? Math.max(0, media.video.inboundFreezeCount - freezeWindowBase.freezeCount) : null;
            const videoFreezeDuration60s = freezeWindowBase ? Math.max(0, media.video.inboundFreezeDurationSeconds - freezeWindowBase.freezeDurationSeconds) : null;

            const audioRxPacketLossPct = ratioPercent(media.audio.inboundPacketsLost, media.audio.inboundPacketsReceived + media.audio.inboundPacketsLost);
            const audioTxPacketLossPct = ratioPercent(media.audio.remoteInboundPacketsLost, media.audio.outboundPacketsSent + media.audio.remoteInboundPacketsLost);
            const videoRxPacketLossPct = ratioPercent(media.video.inboundPacketsLost, media.video.inboundPacketsReceived + media.video.inboundPacketsLost);
            const videoTxPacketLossPct = ratioPercent(media.video.remoteInboundPacketsLost, media.video.outboundPacketsSent + media.video.remoteInboundPacketsLost);

            const audioJitterMs = media.audio.inboundJitterCount > 0 ? (media.audio.inboundJitterSumSeconds / media.audio.inboundJitterCount) * 1000 : null;
            const audioPlayoutDelayMs = media.audio.inboundJitterBufferEmittedCount > 0 ? (media.audio.inboundJitterBufferDelaySeconds / media.audio.inboundJitterBufferEmittedCount) * 1000 : null;
            const audioConcealedPct = ratioPercent(media.audio.inboundConcealedSamples, media.audio.inboundTotalSamples);
            const videoRetransmitPct = ratioPercent(media.video.outboundPacketsRetransmitted, media.video.outboundPacketsSent);
            const videoResolution = media.video.inboundFrameWidth > 0 && media.video.inboundFrameHeight > 0
                ? `${media.video.inboundFrameWidth}x${media.video.inboundFrameHeight}` : null;

            this._stats = {
                transportPath, rttMs, availableOutgoingKbps,
                audioRxPacketLossPct, audioTxPacketLossPct, audioJitterMs, audioPlayoutDelayMs, audioConcealedPct,
                audioRxKbps, audioTxKbps,
                videoRxPacketLossPct, videoTxPacketLossPct, videoRxKbps, videoTxKbps,
                videoFps, videoResolution, videoFreezeCount60s, videoFreezeDuration60s, videoRetransmitPct,
                updatedAtMs: now,
            };

            this.statsSample = {
                timestampMs: now,
                audioRxBytes: media.audio.inboundBytes, audioTxBytes: media.audio.outboundBytes,
                videoRxBytes: media.video.inboundBytes, videoTxBytes: media.video.outboundBytes,
                videoFramesDecoded: media.video.inboundFramesDecoded,
            };

            this.onChange?.();
        } catch (err) {
            this.logger?.log('warning', 'Stats', `Failed to collect realtime stats: ${err}`);
        }
    }
}
