import { useEffect, useRef, useState } from 'react';

export type DebugStatus = 'good' | 'warn' | 'bad' | 'na';

export interface DebugPanelMetric {
    label: string;
    value: string;
    status: DebugStatus;
}

export interface DebugPanelSection {
    title: string;
    metrics: DebugPanelMetric[];
}

export interface RealtimeCallStats {
    transportPath: string | null;
    rttMs: number | null;
    availableOutgoingKbps: number | null;
    audioRxPacketLossPct: number | null;
    audioTxPacketLossPct: number | null;
    audioJitterMs: number | null;
    audioPlayoutDelayMs: number | null;
    audioConcealedPct: number | null;
    audioRxKbps: number | null;
    audioTxKbps: number | null;
    videoRxPacketLossPct: number | null;
    videoTxPacketLossPct: number | null;
    videoRxKbps: number | null;
    videoTxKbps: number | null;
    videoFps: number | null;
    videoResolution: string | null;
    videoFreezeCount60s: number | null;
    videoFreezeDuration60s: number | null;
    videoRetransmitPct: number | null;
    updatedAtMs: number;
}

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

interface BuildDebugPanelSectionsInput {
    isConnected: boolean;
    activeTransport: string | null;
    iceConnectionState: RTCIceConnectionState;
    connectionState: RTCPeerConnectionState;
    signalingState: RTCSignalingState;
    roomParticipantCount: number | null;
    showReconnecting: boolean;
    realtimeStats: RealtimeCallStats | null;
}

const createMediaTotals = (): MediaTotals => ({
    inboundPacketsReceived: 0,
    inboundPacketsLost: 0,
    inboundBytes: 0,
    inboundJitterSumSeconds: 0,
    inboundJitterCount: 0,
    inboundJitterBufferDelaySeconds: 0,
    inboundJitterBufferEmittedCount: 0,
    inboundConcealedSamples: 0,
    inboundTotalSamples: 0,
    inboundFpsSum: 0,
    inboundFpsCount: 0,
    inboundFrameWidth: 0,
    inboundFrameHeight: 0,
    inboundFramesDecoded: 0,
    inboundFreezeCount: 0,
    inboundFreezeDurationSeconds: 0,
    outboundPacketsSent: 0,
    outboundBytes: 0,
    outboundPacketsRetransmitted: 0,
    remoteInboundPacketsLost: 0
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

const formatMs = (value: number | null): string => (value === null ? 'n/a' : `${Math.round(value)} ms`);
const formatPercent = (value: number | null): string => (value === null ? 'n/a' : `${value.toFixed(1)}%`);
const formatKbps = (value: number | null): string => (value === null ? 'n/a' : `${Math.round(value)} kbps`);
const formatFps = (value: number | null): string => (value === null ? 'n/a' : `${value.toFixed(1)} fps`);
const formatFreezeWindow = (count: number | null, durationSeconds: number | null): string => {
    if (count === null || durationSeconds === null) return 'n/a';
    return `${count} / ${durationSeconds.toFixed(1)}s`;
};

const lowerIsBetter = (value: number | null, goodMax: number, warnMax: number): DebugStatus => {
    if (value === null) return 'na';
    if (value <= goodMax) return 'good';
    if (value <= warnMax) return 'warn';
    return 'bad';
};

const higherIsBetter = (value: number | null, goodMin: number, warnMin: number): DebugStatus => {
    if (value === null) return 'na';
    if (value >= goodMin) return 'good';
    if (value >= warnMin) return 'warn';
    return 'bad';
};

const worstStatus = (...statuses: DebugStatus[]): DebugStatus => {
    const concreteStatuses = statuses.filter(status => status !== 'na');
    if (concreteStatuses.length === 0) return 'na';
    if (concreteStatuses.includes('bad')) return 'bad';
    if (concreteStatuses.includes('warn')) return 'warn';
    return 'good';
};

const formatTimeLabel = (timestampMs: number | null): string => {
    if (timestampMs === null) return 'n/a';
    return new Date(timestampMs).toLocaleTimeString([], { hour12: false });
};

const calculateBitrateKbps = (previousBytes: number, currentBytes: number, elapsedSeconds: number): number | null => {
    if (elapsedSeconds <= 0 || currentBytes < previousBytes) return null;
    const bits = (currentBytes - previousBytes) * 8;
    return bits / elapsedSeconds / 1000;
};

const ratioPercent = (numerator: number, denominator: number): number | null => {
    if (denominator <= 0) return null;
    return (numerator / denominator) * 100;
};

export const useRealtimeCallStats = (
    peerConnection: RTCPeerConnection | null,
    enabled: boolean
): RealtimeCallStats | null => {
    const [realtimeStats, setRealtimeStats] = useState<RealtimeCallStats | null>(null);
    const statsSampleRef = useRef<StatsSample | null>(null);
    const freezeSamplesRef = useRef<FreezeSample[]>([]);

    useEffect(() => {
        statsSampleRef.current = null;
        freezeSamplesRef.current = [];
    }, [peerConnection]);

    useEffect(() => {
        if (!enabled) {
            setRealtimeStats(null);
            statsSampleRef.current = null;
            freezeSamplesRef.current = [];
            return;
        }

        let cancelled = false;

        const pollRealtimeStats = async () => {
            const pc = peerConnection;
            if (!pc) {
                if (!cancelled) {
                    setRealtimeStats(null);
                }
                return;
            }

            try {
                const report = await pc.getStats();
                const statsById = new Map<string, RTCStats>();
                report.forEach(stat => {
                    statsById.set(stat.id, stat);
                });

                const media = {
                    audio: createMediaTotals(),
                    video: createMediaTotals()
                };

                let selectedCandidatePair: RTCStats | null = null;
                let fallbackCandidatePair: RTCStats | null = null;
                let remoteInboundRttSumSeconds = 0;
                let remoteInboundRttCount = 0;

                report.forEach(stat => {
                    if (stat.type === 'candidate-pair') {
                        const isSelected = getStatBoolean(stat, 'selected') === true;
                        const isNominated = getStatBoolean(stat, 'nominated') === true;
                        const pairState = getStatString(stat, 'state');
                        if (isSelected) {
                            selectedCandidatePair = stat;
                        } else if (!fallbackCandidatePair && isNominated && pairState === 'succeeded') {
                            fallbackCandidatePair = stat;
                        }
                        return;
                    }

                    const kind = getMediaKind(stat);
                    if (!kind) {
                        return;
                    }
                    const bucket = media[kind];

                    if (stat.type === 'inbound-rtp') {
                        bucket.inboundPacketsReceived += getStatNumber(stat, 'packetsReceived') ?? 0;
                        bucket.inboundPacketsLost += Math.max(0, getStatNumber(stat, 'packetsLost') ?? 0);
                        bucket.inboundBytes += getStatNumber(stat, 'bytesReceived') ?? 0;

                        const jitter = getStatNumber(stat, 'jitter');
                        if (jitter !== null) {
                            bucket.inboundJitterSumSeconds += jitter;
                            bucket.inboundJitterCount += 1;
                        }

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
                        if (remoteRtt !== null) {
                            remoteInboundRttSumSeconds += remoteRtt;
                            remoteInboundRttCount += 1;
                        }
                    }
                });

                if (!selectedCandidatePair) {
                    selectedCandidatePair = fallbackCandidatePair;
                }

                const selectedPair = selectedCandidatePair;
                const localCandidate = selectedPair
                    ? statsById.get(getStatString(selectedPair, 'localCandidateId') ?? '')
                    : null;
                const remoteCandidate = selectedPair
                    ? statsById.get(getStatString(selectedPair, 'remoteCandidateId') ?? '')
                    : null;

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
                const previousSample = statsSampleRef.current;
                const elapsedSeconds = previousSample ? (now - previousSample.timestampMs) / 1000 : 0;

                const audioRxKbps = previousSample
                    ? calculateBitrateKbps(previousSample.audioRxBytes, media.audio.inboundBytes, elapsedSeconds)
                    : null;
                const audioTxKbps = previousSample
                    ? calculateBitrateKbps(previousSample.audioTxBytes, media.audio.outboundBytes, elapsedSeconds)
                    : null;
                const videoRxKbps = previousSample
                    ? calculateBitrateKbps(previousSample.videoRxBytes, media.video.inboundBytes, elapsedSeconds)
                    : null;
                const videoTxKbps = previousSample
                    ? calculateBitrateKbps(previousSample.videoTxBytes, media.video.outboundBytes, elapsedSeconds)
                    : null;

                let videoFps: number | null = null;
                if (media.video.inboundFpsCount > 0) {
                    videoFps = media.video.inboundFpsSum / media.video.inboundFpsCount;
                } else if (previousSample && elapsedSeconds > 0 && media.video.inboundFramesDecoded >= previousSample.videoFramesDecoded) {
                    videoFps = (media.video.inboundFramesDecoded - previousSample.videoFramesDecoded) / elapsedSeconds;
                }

                freezeSamplesRef.current.push({
                    timestampMs: now,
                    freezeCount: media.video.inboundFreezeCount,
                    freezeDurationSeconds: media.video.inboundFreezeDurationSeconds
                });
                freezeSamplesRef.current = freezeSamplesRef.current.filter(sample => now - sample.timestampMs <= 60_000);
                const freezeWindowBase = freezeSamplesRef.current[0];
                const videoFreezeCount60s = freezeWindowBase
                    ? Math.max(0, media.video.inboundFreezeCount - freezeWindowBase.freezeCount)
                    : null;
                const videoFreezeDuration60s = freezeWindowBase
                    ? Math.max(0, media.video.inboundFreezeDurationSeconds - freezeWindowBase.freezeDurationSeconds)
                    : null;

                const audioRxPacketLossPct = ratioPercent(
                    media.audio.inboundPacketsLost,
                    media.audio.inboundPacketsReceived + media.audio.inboundPacketsLost
                );
                const audioTxPacketLossPct = ratioPercent(
                    media.audio.remoteInboundPacketsLost,
                    media.audio.outboundPacketsSent + media.audio.remoteInboundPacketsLost
                );
                const videoRxPacketLossPct = ratioPercent(
                    media.video.inboundPacketsLost,
                    media.video.inboundPacketsReceived + media.video.inboundPacketsLost
                );
                const videoTxPacketLossPct = ratioPercent(
                    media.video.remoteInboundPacketsLost,
                    media.video.outboundPacketsSent + media.video.remoteInboundPacketsLost
                );

                const audioJitterMs = media.audio.inboundJitterCount > 0
                    ? (media.audio.inboundJitterSumSeconds / media.audio.inboundJitterCount) * 1000
                    : null;
                const audioPlayoutDelayMs = media.audio.inboundJitterBufferEmittedCount > 0
                    ? (media.audio.inboundJitterBufferDelaySeconds / media.audio.inboundJitterBufferEmittedCount) * 1000
                    : null;
                const audioConcealedPct = ratioPercent(
                    media.audio.inboundConcealedSamples,
                    media.audio.inboundConcealedSamples + media.audio.inboundTotalSamples
                );

                const videoRetransmitPct = ratioPercent(
                    media.video.outboundPacketsRetransmitted,
                    media.video.outboundPacketsSent
                );

                const videoResolution = media.video.inboundFrameWidth > 0 && media.video.inboundFrameHeight > 0
                    ? `${media.video.inboundFrameWidth}x${media.video.inboundFrameHeight}`
                    : null;

                const nextStats: RealtimeCallStats = {
                    transportPath,
                    rttMs,
                    availableOutgoingKbps,
                    audioRxPacketLossPct,
                    audioTxPacketLossPct,
                    audioJitterMs,
                    audioPlayoutDelayMs,
                    audioConcealedPct,
                    audioRxKbps,
                    audioTxKbps,
                    videoRxPacketLossPct,
                    videoTxPacketLossPct,
                    videoRxKbps,
                    videoTxKbps,
                    videoFps,
                    videoResolution,
                    videoFreezeCount60s,
                    videoFreezeDuration60s,
                    videoRetransmitPct,
                    updatedAtMs: now
                };

                statsSampleRef.current = {
                    timestampMs: now,
                    audioRxBytes: media.audio.inboundBytes,
                    audioTxBytes: media.audio.outboundBytes,
                    videoRxBytes: media.video.inboundBytes,
                    videoTxBytes: media.video.outboundBytes,
                    videoFramesDecoded: media.video.inboundFramesDecoded
                };

                if (!cancelled) {
                    setRealtimeStats(nextStats);
                }
            } catch (err) {
                console.warn('[CallRoom] Failed to collect realtime stats', err);
            }
        };

        void pollRealtimeStats();
        const timer = window.setInterval(() => {
            void pollRealtimeStats();
        }, 1000);

        return () => {
            cancelled = true;
            window.clearInterval(timer);
        };
    }, [enabled, peerConnection]);

    return realtimeStats;
};

export const buildDebugPanelSections = ({
    isConnected,
    activeTransport,
    iceConnectionState,
    connectionState,
    signalingState,
    roomParticipantCount,
    showReconnecting,
    realtimeStats
}: BuildDebugPanelSectionsInput): DebugPanelSection[] => {
    const signalingStatus: DebugStatus = isConnected ? 'good' : 'bad';
    const iceStatus: DebugStatus = (
        iceConnectionState === 'connected' || iceConnectionState === 'completed'
            ? 'good'
            : (iceConnectionState === 'checking' || iceConnectionState === 'disconnected' ? 'warn' : 'bad')
    );
    const pcStatus: DebugStatus = (
        connectionState === 'connected'
            ? 'good'
            : (connectionState === 'connecting' || connectionState === 'disconnected' ? 'warn' : 'bad')
    );
    const reconnectingStatus: DebugStatus = showReconnecting ? 'bad' : 'good';

    const transportPathStatus: DebugStatus = realtimeStats?.transportPath
        ? (realtimeStats.transportPath.startsWith('TURN relay') ? 'warn' : 'good')
        : 'na';
    const rttStatus = lowerIsBetter(realtimeStats?.rttMs ?? null, 120, 250);
    const availableOutgoingStatus = higherIsBetter(realtimeStats?.availableOutgoingKbps ?? null, 1500, 600);

    const audioLossStatus = worstStatus(
        lowerIsBetter(realtimeStats?.audioRxPacketLossPct ?? null, 1, 3),
        lowerIsBetter(realtimeStats?.audioTxPacketLossPct ?? null, 1, 3)
    );
    const audioBitrateStatus = worstStatus(
        higherIsBetter(realtimeStats?.audioRxKbps ?? null, 20, 12),
        higherIsBetter(realtimeStats?.audioTxKbps ?? null, 20, 12)
    );

    const videoLossStatus = worstStatus(
        lowerIsBetter(realtimeStats?.videoRxPacketLossPct ?? null, 1, 3),
        lowerIsBetter(realtimeStats?.videoTxPacketLossPct ?? null, 1, 3)
    );
    const videoBitrateStatus = worstStatus(
        higherIsBetter(realtimeStats?.videoRxKbps ?? null, 900, 350),
        higherIsBetter(realtimeStats?.videoTxKbps ?? null, 900, 350)
    );
    return [
        {
            title: 'Connection',
            metrics: [
                { label: 'Signaling', value: isConnected ? 'connected' : 'disconnected', status: signalingStatus },
                { label: 'Transport', value: activeTransport ?? 'n/a', status: signalingStatus },
                { label: 'ICE / PC', value: `${iceConnectionState} / ${connectionState}`, status: worstStatus(iceStatus, pcStatus) },
                { label: 'SDP', value: signalingState, status: signalingState === 'stable' ? 'good' : 'warn' },
                { label: 'Room', value: roomParticipantCount !== null ? `${roomParticipantCount} participants` : 'none', status: roomParticipantCount !== null ? 'good' : 'warn' },
                { label: 'Reconnecting', value: showReconnecting ? 'yes' : 'no', status: reconnectingStatus }
            ]
        },
        {
            title: 'Latency',
            metrics: [
                { label: 'RTT', value: formatMs(realtimeStats?.rttMs ?? null), status: rttStatus },
                { label: '', value: realtimeStats?.transportPath ?? 'n/a', status: transportPathStatus },
                { label: 'Outgoing headroom', value: formatKbps(realtimeStats?.availableOutgoingKbps ?? null), status: availableOutgoingStatus },
                { label: 'Updated', value: formatTimeLabel(realtimeStats?.updatedAtMs ?? null), status: 'na' }
            ]
        },
        {
            title: 'Audio Quality',
            metrics: [
                {
                    label: 'Packet loss ⇵',
                    value: `${formatPercent(realtimeStats?.audioRxPacketLossPct ?? null)} / ${formatPercent(realtimeStats?.audioTxPacketLossPct ?? null)}`,
                    status: audioLossStatus
                },
                { label: 'Jitter', value: formatMs(realtimeStats?.audioJitterMs ?? null), status: lowerIsBetter(realtimeStats?.audioJitterMs ?? null, 20, 40) },
                { label: 'Playout delay', value: formatMs(realtimeStats?.audioPlayoutDelayMs ?? null), status: lowerIsBetter(realtimeStats?.audioPlayoutDelayMs ?? null, 80, 180) },
                { label: 'Concealed audio', value: formatPercent(realtimeStats?.audioConcealedPct ?? null), status: lowerIsBetter(realtimeStats?.audioConcealedPct ?? null, 2, 8) },
                {
                    label: 'Bitrate ⇵',
                    value: `${formatKbps(realtimeStats?.audioRxKbps ?? null)} / ${formatKbps(realtimeStats?.audioTxKbps ?? null)}`,
                    status: audioBitrateStatus
                }
            ]
        },
        {
            title: 'Video Quality',
            metrics: [
                {
                    label: 'Packet loss ⇵',
                    value: `${formatPercent(realtimeStats?.videoRxPacketLossPct ?? null)} / ${formatPercent(realtimeStats?.videoTxPacketLossPct ?? null)}`,
                    status: videoLossStatus
                },
                {
                    label: 'Bitrate ⇵',
                    value: `${formatKbps(realtimeStats?.videoRxKbps ?? null)} / ${formatKbps(realtimeStats?.videoTxKbps ?? null)}`,
                    status: videoBitrateStatus
                },
                { label: 'Render FPS', value: formatFps(realtimeStats?.videoFps ?? null), status: higherIsBetter(realtimeStats?.videoFps ?? null, 24, 15) },
                { label: 'Resolution', value: realtimeStats?.videoResolution ?? 'n/a', status: realtimeStats?.videoResolution ? 'good' : 'na' },
                {
                    label: 'Freezes (last 60s)',
                    value: formatFreezeWindow(realtimeStats?.videoFreezeCount60s ?? null, realtimeStats?.videoFreezeDuration60s ?? null),
                    status: worstStatus(
                        lowerIsBetter(realtimeStats?.videoFreezeCount60s ?? null, 0, 2),
                        lowerIsBetter(realtimeStats?.videoFreezeDuration60s ?? null, 0.2, 1)
                    )
                },
                { label: 'Retransmit', value: formatPercent(realtimeStats?.videoRetransmitPct ?? null), status: lowerIsBetter(realtimeStats?.videoRetransmitPct ?? null, 1, 3) }
            ]
        }
    ];
};
