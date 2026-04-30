import React from 'react';

interface AudioActivityIndicatorProps {
    /** Normalized speech level (0..1). */
    level: number;
    /** Pixel height of the indicator. Bars scale to fit. Defaults to 14. */
    size?: number;
}

const BAR_GAINS = [0.7, 1.0, 0.55] as const;
const MIN_HEIGHT_PCT = 14;
const MAX_HEIGHT_PCT = 100;

/**
 * Three small green bars that pulse with the speaker's audio level — used
 * in place of the muted mic icon when a participant is unmuted.
 */
export const AudioActivityIndicator: React.FC<AudioActivityIndicatorProps> = ({ level, size = 14 }) => {
    const clamped = Math.max(0, Math.min(1, level));
    return (
        <div
            className="audio-activity-indicator"
            style={{ width: size, height: size }}
            aria-hidden="true"
        >
            {BAR_GAINS.map((gain, index) => {
                const heightPct = MIN_HEIGHT_PCT + (MAX_HEIGHT_PCT - MIN_HEIGHT_PCT) * Math.min(1, clamped * gain);
                return (
                    <span
                        key={index}
                        className="audio-activity-bar"
                        style={{ height: `${heightPct}%` }}
                    />
                );
            })}
        </div>
    );
};
