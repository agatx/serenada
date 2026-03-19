import React, { useState } from 'react';
import type { CallStats } from '@serenada/core';
import type { SerenadaString } from '../types.js';
import { resolveString } from '../types.js';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

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

export interface DebugPanelConnectionInfo {
    isSignalingConnected: boolean;
    activeTransport: string | null;
    iceConnectionState: RTCIceConnectionState;
    peerConnectionState: RTCPeerConnectionState;
    rtcSignalingState: RTCSignalingState;
    roomParticipantCount: number | null;
    showReconnecting: boolean;
}

export interface DebugPanelProps {
    stats: CallStats | null;
    connectionInfo?: DebugPanelConnectionInfo;
    /** Pre-built sections override. When provided, stats are ignored. */
    sections?: DebugPanelSection[];
    strings?: Partial<Record<SerenadaString, string>>;
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

const fmtMs = (v: number | null): string => (v === null ? 'n/a' : `${Math.round(v)} ms`);
const fmtPct = (v: number | null): string => (v === null ? 'n/a' : `${v.toFixed(1)}%`);
const fmtKbps = (v: number | null): string => (v === null ? 'n/a' : `${Math.round(v)} kbps`);
const fmtFps = (v: number | null): string => (v === null ? 'n/a' : `${v.toFixed(1)} fps`);
const fmtFreezeWindow = (count: number | null, durationSeconds: number | null): string => {
    if (count === null || durationSeconds === null) return 'n/a';
    return `${count} / ${durationSeconds.toFixed(1)}s`;
};
const fmtTime = (timestampMs: number | null): string => {
    if (timestampMs === null) return 'n/a';
    return new Date(timestampMs).toLocaleTimeString([], { hour12: false });
};

const lowerIsBetter = (v: number | null, good: number, warn: number): DebugStatus => {
    if (v === null) return 'na';
    if (v <= good) return 'good';
    if (v <= warn) return 'warn';
    return 'bad';
};

const higherIsBetter = (v: number | null, good: number, warn: number): DebugStatus => {
    if (v === null) return 'na';
    if (v >= good) return 'good';
    if (v >= warn) return 'warn';
    return 'bad';
};

const worst = (...ss: DebugStatus[]): DebugStatus => {
    const concrete = ss.filter(s => s !== 'na');
    if (concrete.length === 0) return 'na';
    if (concrete.includes('bad')) return 'bad';
    if (concrete.includes('warn')) return 'warn';
    return 'good';
};

// ---------------------------------------------------------------------------
// Build sections from CallStats
// ---------------------------------------------------------------------------

function buildSections(
    stats: CallStats | null,
    connectionInfo?: DebugPanelConnectionInfo,
): DebugPanelSection[] {
    const sections: DebugPanelSection[] = [];

    if (connectionInfo) {
        const signalingStatus: DebugStatus = connectionInfo.isSignalingConnected ? 'good' : 'bad';
        const iceStatus: DebugStatus = (
            connectionInfo.iceConnectionState === 'connected' || connectionInfo.iceConnectionState === 'completed'
                ? 'good'
                : (connectionInfo.iceConnectionState === 'checking' || connectionInfo.iceConnectionState === 'disconnected' ? 'warn' : 'bad')
        );
        const pcStatus: DebugStatus = (
            connectionInfo.peerConnectionState === 'connected'
                ? 'good'
                : (connectionInfo.peerConnectionState === 'connecting' || connectionInfo.peerConnectionState === 'disconnected' ? 'warn' : 'bad')
        );

        sections.push({
            title: 'Connection',
            metrics: [
                {
                    label: 'Signaling',
                    value: connectionInfo.isSignalingConnected ? 'connected' : 'disconnected',
                    status: signalingStatus,
                },
                {
                    label: 'Transport',
                    value: connectionInfo.activeTransport ?? 'n/a',
                    status: signalingStatus,
                },
                {
                    label: 'ICE / PC',
                    value: `${connectionInfo.iceConnectionState} / ${connectionInfo.peerConnectionState}`,
                    status: worst(iceStatus, pcStatus),
                },
                {
                    label: 'SDP',
                    value: connectionInfo.rtcSignalingState,
                    status: connectionInfo.rtcSignalingState === 'stable' ? 'good' : 'warn',
                },
                {
                    label: 'Room',
                    value: connectionInfo.roomParticipantCount !== null
                        ? `${connectionInfo.roomParticipantCount} participants`
                        : 'none',
                    status: connectionInfo.roomParticipantCount !== null ? 'good' : 'warn',
                },
                {
                    label: 'Reconnecting',
                    value: connectionInfo.showReconnecting ? 'yes' : 'no',
                    status: connectionInfo.showReconnecting ? 'bad' : 'good',
                },
            ],
        });
    }

    if (!stats) {
        return sections;
    }

    return [
        ...sections,
        {
            title: 'Latency',
            metrics: [
                { label: 'RTT', value: fmtMs(stats.rttMs), status: lowerIsBetter(stats.rttMs, 120, 250) },
                { label: 'Path', value: stats.transportPath ?? 'n/a', status: stats.transportPath ? (stats.transportPath.startsWith('TURN') ? 'warn' : 'good') : 'na' },
                { label: 'Outgoing headroom', value: fmtKbps(stats.availableOutgoingKbps), status: higherIsBetter(stats.availableOutgoingKbps, 1500, 600) },
                { label: 'Updated', value: fmtTime(stats.updatedAtMs), status: 'na' },
            ],
        },
        {
            title: 'Audio',
            metrics: [
                { label: 'Loss RX/TX', value: `${fmtPct(stats.audioRxPacketLossPct)} / ${fmtPct(stats.audioTxPacketLossPct)}`, status: worst(lowerIsBetter(stats.audioRxPacketLossPct, 1, 3), lowerIsBetter(stats.audioTxPacketLossPct, 1, 3)) },
                { label: 'Jitter', value: fmtMs(stats.audioJitterMs), status: lowerIsBetter(stats.audioJitterMs, 20, 40) },
                { label: 'Playout delay', value: fmtMs(stats.audioPlayoutDelayMs), status: lowerIsBetter(stats.audioPlayoutDelayMs, 80, 180) },
                { label: 'Concealed', value: fmtPct(stats.audioConcealedPct), status: lowerIsBetter(stats.audioConcealedPct, 2, 8) },
                { label: 'Bitrate RX/TX', value: `${fmtKbps(stats.audioRxKbps)} / ${fmtKbps(stats.audioTxKbps)}`, status: worst(higherIsBetter(stats.audioRxKbps, 20, 12), higherIsBetter(stats.audioTxKbps, 20, 12)) },
            ],
        },
        {
            title: 'Video',
            metrics: [
                { label: 'Loss RX/TX', value: `${fmtPct(stats.videoRxPacketLossPct)} / ${fmtPct(stats.videoTxPacketLossPct)}`, status: worst(lowerIsBetter(stats.videoRxPacketLossPct, 1, 3), lowerIsBetter(stats.videoTxPacketLossPct, 1, 3)) },
                { label: 'Bitrate RX/TX', value: `${fmtKbps(stats.videoRxKbps)} / ${fmtKbps(stats.videoTxKbps)}`, status: worst(higherIsBetter(stats.videoRxKbps, 900, 350), higherIsBetter(stats.videoTxKbps, 900, 350)) },
                { label: 'FPS', value: fmtFps(stats.videoFps), status: higherIsBetter(stats.videoFps, 24, 15) },
                { label: 'Resolution', value: stats.videoResolution ?? 'n/a', status: stats.videoResolution ? 'good' : 'na' },
                {
                    label: 'Freezes (last 60s)',
                    value: fmtFreezeWindow(stats.videoFreezeCount60s, stats.videoFreezeDuration60s),
                    status: worst(
                        lowerIsBetter(stats.videoFreezeCount60s, 0, 2),
                        lowerIsBetter(stats.videoFreezeDuration60s, 0.2, 1),
                    ),
                },
                { label: 'Retransmit', value: fmtPct(stats.videoRetransmitPct), status: lowerIsBetter(stats.videoRetransmitPct, 1, 3) },
            ],
        },
    ];
}

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const STATUS_COLORS: Record<DebugStatus, string> = {
    good: '#22c55e',
    warn: '#eab308',
    bad: '#ef4444',
    na: '#94a3b8',
};

const panelStyle: React.CSSProperties = {
    position: 'absolute',
    top: 16,
    left: 16,
    zIndex: 60,
    width: 'min(92vw, 430px)',
    maxHeight: 'calc(100vh - 140px)',
    overflowY: 'auto',
    background: 'rgba(0, 0, 0, 0.7)',
    color: '#e6edf3',
    borderRadius: 10,
    padding: '0.65rem',
    fontSize: '0.73rem',
    lineHeight: 1.3,
    border: '1px solid rgba(255, 255, 255, 0.12)',
    backdropFilter: 'blur(6px)',
    WebkitBackdropFilter: 'blur(6px)',
};

const panelGridStyle: React.CSSProperties = {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))',
    gap: '0.45rem',
};

const sectionStyle: React.CSSProperties = {
    border: '1px solid rgba(255, 255, 255, 0.11)',
    background: 'rgba(255, 255, 255, 0.04)',
    borderRadius: 8,
    padding: '0.45rem 0.5rem',
};

const sectionTitleStyle: React.CSSProperties = {
    fontSize: '0.66rem',
    fontWeight: 700,
    textTransform: 'uppercase',
    letterSpacing: '0.04em',
    color: 'rgba(230, 237, 243, 0.85)',
    marginBottom: '0.35rem',
};

const metricRowStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: '0.45rem',
    margin: '0.2rem 0',
};

const metricLabelStyle: React.CSSProperties = {
    display: 'inline-flex',
    alignItems: 'center',
    gap: '0.36rem',
    minWidth: 0,
    color: 'rgba(230, 237, 243, 0.95)',
};

const metricLabelTextStyle: React.CSSProperties = {
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
};

const metricValueStyle: React.CSSProperties = {
    color: 'rgba(230, 237, 243, 0.9)',
    whiteSpace: 'nowrap',
    fontVariantNumeric: 'tabular-nums',
};

const dotStyle: React.CSSProperties = {
    width: '0.48rem',
    height: '0.48rem',
    borderRadius: 999,
    flex: '0 0 auto',
};

const toggleBtnStyle: React.CSSProperties = {
    position: 'absolute',
    top: 8,
    right: 8,
    zIndex: 60,
    padding: '4px 10px',
    borderRadius: 6,
    border: 'none',
    background: 'rgba(0,0,0,0.5)',
    color: '#94a3b8',
    fontSize: 11,
    cursor: 'pointer',
    fontFamily: 'monospace',
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export const DebugPanel: React.FC<DebugPanelProps> = ({ stats, connectionInfo, sections: sectionsProp, strings }) => {
    const [open, setOpen] = useState(false);

    const sections = sectionsProp ?? buildSections(stats, connectionInfo);

    if (!open) {
        return (
            <button type="button" style={toggleBtnStyle} onClick={() => setOpen(true)}>
                {resolveString('debugPanel', strings)}
            </button>
        );
    }

    return (
        <div style={panelStyle}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                <span style={{ fontWeight: 700, fontSize: 13 }}>{resolveString('debugPanel', strings)}</span>
                <button
                    type="button"
                    onClick={() => setOpen(false)}
                    style={{ background: 'none', border: 'none', color: '#94a3b8', cursor: 'pointer', fontSize: 16, lineHeight: 1 }}
                >
                    &times;
                </button>
            </div>
            <div style={panelGridStyle}>
                {sections.map(section => (
                    <section key={section.title} style={sectionStyle}>
                        <div style={sectionTitleStyle}>{section.title}</div>
                        {section.metrics.map(metric => (
                            <div key={metric.label || metric.value} style={metricRowStyle}>
                                <div style={metricLabelStyle}>
                                    <span style={{ ...dotStyle, background: STATUS_COLORS[metric.status] }} />
                                    {metric.label && <span style={metricLabelTextStyle}>{metric.label}</span>}
                                </div>
                                <span style={metricValueStyle}>{metric.value}</span>
                            </div>
                        ))}
                    </section>
                ))}
            </div>
        </div>
    );
};
