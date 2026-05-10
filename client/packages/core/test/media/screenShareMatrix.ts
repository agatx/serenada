export type ScreenShareStartingState =
    | 'cameraOn'
    | 'cameraOff'
    | 'audioOnlyStream'
    | 'noLocalStream';

export type ScreenShareStartEffect =
    | 'replaceVideoWithScreenTrack'
    | 'addScreenTrack'
    | 'noOp';

export type ScreenShareStopEffect =
    | 'restoreCameraOn'
    | 'restoreCameraOff'
    | 'returnToAudioOnly'
    | 'notApplicable';

export interface ScreenShareMatrixRow {
    name: string;
    starting: ScreenShareStartingState;
    onStart: ScreenShareStartEffect;
    onStop: ScreenShareStopEffect;
}

export const SCREEN_SHARE_MATRIX: ReadonlyArray<ScreenShareMatrixRow> = [
    {
        name: 'camera on (audio + enabled video)',
        starting: 'cameraOn',
        onStart: 'replaceVideoWithScreenTrack',
        onStop: 'restoreCameraOn',
    },
    {
        name: 'camera off (audio + disabled video)',
        starting: 'cameraOff',
        onStart: 'replaceVideoWithScreenTrack',
        onStop: 'restoreCameraOff',
    },
    {
        name: 'audio-only stream (no video track)',
        starting: 'audioOnlyStream',
        onStart: 'addScreenTrack',
        onStop: 'returnToAudioOnly',
    },
    {
        name: 'no local stream',
        starting: 'noLocalStream',
        onStart: 'noOp',
        onStop: 'notApplicable',
    },
];
