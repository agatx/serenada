import type { ReactNode } from 'react';
import type { SerenadaSessionHandle, CallStats } from '@serenada/core';

// ---------------------------------------------------------------------------
// Feature configuration
// ---------------------------------------------------------------------------

export interface SerenadaCallFlowConfig {
    screenSharingEnabled?: boolean;
    inviteControlsEnabled?: boolean;
    debugOverlayEnabled?: boolean;
}

export interface SerenadaCallFlowTheme {
    accentColor?: string;
    backgroundColor?: string;
}

// ---------------------------------------------------------------------------
// Localisable string keys
// ---------------------------------------------------------------------------

export type SerenadaString =
    | 'joiningCall'
    | 'waitingForOther'
    | 'shareLink'
    | 'copied'
    | 'endCall'
    | 'muteAudio'
    | 'unmuteAudio'
    | 'enableVideo'
    | 'disableVideo'
    | 'flipCamera'
    | 'startScreenShare'
    | 'stopScreenShare'
    | 'reconnecting'
    | 'callEnded'
    | 'errorOccurred'
    | 'permissionRequired'
    | 'permissionCamera'
    | 'permissionMicrophone'
    | 'permissionPrompt'
    | 'grantPermissions'
    | 'cancel'
    | 'debugPanel'
    | 'you'
    | 'remote';

export const serenadaDefaultStrings: Record<SerenadaString, string> = {
    joiningCall: 'Joining call\u2026',
    waitingForOther: 'Waiting for the other person to join',
    shareLink: 'Share this link to invite someone',
    copied: 'Copied!',
    endCall: 'End call',
    muteAudio: 'Mute',
    unmuteAudio: 'Unmute',
    enableVideo: 'Turn on camera',
    disableVideo: 'Turn off camera',
    flipCamera: 'Flip camera',
    startScreenShare: 'Share screen',
    stopScreenShare: 'Stop sharing',
    reconnecting: 'Reconnecting\u2026',
    callEnded: 'Call ended',
    errorOccurred: 'An error occurred',
    permissionRequired: 'Permission required',
    permissionCamera: 'Camera',
    permissionMicrophone: 'Microphone',
    permissionPrompt: 'This app needs access to your camera and microphone to make calls.',
    grantPermissions: 'Grant permissions',
    cancel: 'Cancel',
    debugPanel: 'Debug',
    you: 'You',
    remote: 'Remote',
};

export function resolveString(
    key: SerenadaString,
    overrides?: Partial<Record<SerenadaString, string>>,
): string {
    return overrides?.[key] ?? serenadaDefaultStrings[key];
}

// ---------------------------------------------------------------------------
// CallFlowProps — accepted by <SerenadaCallFlow />
// ---------------------------------------------------------------------------

export interface CallFlowProps {
    /** Optional CSS class name(s) applied to the root element for host-app style overrides. */
    className?: string;
    /** Full call URL — triggers URL-first mode (creates session internally). */
    url?: string;
    /** Provide an existing session handle — triggers session-first mode. */
    session?: SerenadaSessionHandle;
    /** Server host or origin, required when using url-first mode without an existing session. */
    serverHost?: string;
    /** Feature toggles. */
    config?: SerenadaCallFlowConfig;
    /** Theme overrides. */
    theme?: SerenadaCallFlowTheme;
    /** Localisation overrides. */
    strings?: Partial<Record<SerenadaString, string>>;
    /** Optional host-app controls rendered in the waiting screen below default invite controls. */
    waitingActions?: ReactNode;
    /** Called when the user dismisses the call UI (end/leave/cancel). */
    onDismiss?: () => void;
    /** Callback fired when call stats are updated for host-owned diagnostics or bridge code. */
    onStatsUpdate?: (stats: CallStats | null) => void;
}
