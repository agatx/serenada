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

export interface DebugPanelProps {
    stats: CallStats | null;
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

function buildSections(stats: CallStats): DebugPanelSection[] {
    return [
        {
            title: 'Latency',
            metrics: [
                { label: 'RTT', value: fmtMs(stats.rttMs), status: lowerIsBetter(stats.rttMs, 120, 250) },
                { label: 'Path', value: stats.transportPath ?? 'n/a', status: stats.transportPath ? (stats.transportPath.startsWith('TURN') ? 'warn' : 'good') : 'na' },
                { label: 'Outgoing headroom', value: fmtKbps(stats.availableOutgoingKbps), status: higherIsBetter(stats.availableOutgoingKbps, 1500, 600) },
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
    top: 8,
    right: 8,
    zIndex: 60,
    maxWidth: 320,
    maxHeight: '80vh',
    overflowY: 'auto',
    background: 'rgba(0,0,0,0.78)',
    color: '#e2e8f0',
    borderRadius: 10,
    padding: '10px 14px',
    fontSize: 12,
    fontFamily: 'monospace',
    backdropFilter: 'blur(8px)',
    WebkitBackdropFilter: 'blur(8px)',
};

const sectionTitleStyle: React.CSSProperties = {
    fontWeight: 700,
    fontSize: 11,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    color: '#94a3b8',
    margin: '8px 0 4px',
};

const metricRowStyle: React.CSSProperties = {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '2px 0',
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

export const DebugPanel: React.FC<DebugPanelProps> = ({ stats, sections: sectionsProp, strings }) => {
    const [open, setOpen] = useState(false);

    const sections = sectionsProp ?? (stats ? buildSections(stats) : []);

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
            {sections.map(section => (
                <div key={section.title}>
                    <div style={sectionTitleStyle}>{section.title}</div>
                    {section.metrics.map(metric => (
                        <div key={metric.label || metric.value} style={metricRowStyle}>
                            <span style={{ color: '#94a3b8' }}>{metric.label}</span>
                            <span style={{ color: STATUS_COLORS[metric.status], fontWeight: 500 }}>{metric.value}</span>
                        </div>
                    ))}
                </div>
            ))}
        </div>
    );
};
