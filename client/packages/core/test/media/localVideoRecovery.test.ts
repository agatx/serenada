import { describe, expect, it } from 'vitest';
import {
    LOCAL_VIDEO_RESUME_GAP_MS,
    shouldForceLocalVideoRefresh,
    shouldRecoverLocalVideo,
} from '../../src/index';

describe('localVideoRecovery', () => {
    it('forces a refresh after a long hidden period', () => {
        expect(shouldForceLocalVideoRefresh({
            hiddenDurationMs: LOCAL_VIDEO_RESUME_GAP_MS,
            sleepGapMs: 0,
        })).toBe(true);
    });

    it('forces a refresh after a long suspend gap', () => {
        expect(shouldForceLocalVideoRefresh({
            hiddenDurationMs: 0,
            sleepGapMs: LOCAL_VIDEO_RESUME_GAP_MS + 1,
        })).toBe(true);
    });

    it('does not force a refresh for short interruptions', () => {
        expect(shouldForceLocalVideoRefresh({
            hiddenDurationMs: LOCAL_VIDEO_RESUME_GAP_MS - 1,
            sleepGapMs: LOCAL_VIDEO_RESUME_GAP_MS - 1,
        })).toBe(false);
    });

    it('ignores recovery when there is no local video track', () => {
        expect(shouldRecoverLocalVideo({
            hasVideoTrack: false,
            isScreenSharing: false,
            videoTrackReadyState: null,
            videoTrackMuted: false,
            forceRefresh: true,
        })).toBe(false);
    });

    it('ignores recovery while screen sharing', () => {
        expect(shouldRecoverLocalVideo({
            hasVideoTrack: true,
            isScreenSharing: true,
            videoTrackReadyState: 'ended',
            videoTrackMuted: true,
            forceRefresh: true,
        })).toBe(false);
    });

    it('recovers a forced refresh even when the track still reports live', () => {
        expect(shouldRecoverLocalVideo({
            hasVideoTrack: true,
            isScreenSharing: false,
            videoTrackReadyState: 'live',
            videoTrackMuted: false,
            forceRefresh: true,
        })).toBe(true);
    });

    it('recovers an unhealthy track without forcing', () => {
        expect(shouldRecoverLocalVideo({
            hasVideoTrack: true,
            isScreenSharing: false,
            videoTrackReadyState: 'ended',
            videoTrackMuted: false,
            forceRefresh: false,
        })).toBe(true);
        expect(shouldRecoverLocalVideo({
            hasVideoTrack: true,
            isScreenSharing: false,
            videoTrackReadyState: 'live',
            videoTrackMuted: true,
            forceRefresh: false,
        })).toBe(true);
    });

    it('leaves a healthy track alone when there is no resume signal', () => {
        expect(shouldRecoverLocalVideo({
            hasVideoTrack: true,
            isScreenSharing: false,
            videoTrackReadyState: 'live',
            videoTrackMuted: false,
            forceRefresh: false,
        })).toBe(false);
    });
});
