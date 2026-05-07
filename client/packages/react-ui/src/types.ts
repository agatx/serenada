import type { ReactNode } from 'react';
import type { SerenadaSessionHandle, CallStats, MediaCapability } from '@agatx/serenada-core';

// ---------------------------------------------------------------------------
// Avatar provider (host-supplied)
// ---------------------------------------------------------------------------

/**
 * Avatar payload returned by an {@link AvatarProvider}. The image is rendered
 * cover-fit, cropped to a circle above the participant's name when their
 * remote video track is off.
 */
export type AvatarSource =
    | { kind: 'url'; url: string }
    | { kind: 'bytes'; bytes: Uint8Array }
    | { kind: 'image'; image: HTMLImageElement };

/**
 * Resolves an avatar for a given host-supplied `peerId`. Returning `null` (or
 * throwing) falls back to the initials placeholder. The call UI never blocks
 * on the provider — it shows initials immediately and swaps in the avatar
 * when the promise resolves. Each `peerId` is resolved at most once per call.
 */
export type AvatarProvider = (peerId: string) => Promise<AvatarSource | null>;

// ---------------------------------------------------------------------------
// Feature configuration
// ---------------------------------------------------------------------------

export interface SerenadaCallFlowConfig {
    screenSharingEnabled?: boolean;
    inviteControlsEnabled?: boolean;
    debugOverlayEnabled?: boolean;
    /**
     * When `true` (default), the call controls bar fades out after a few
     * seconds of idle time and a tap on the stage brings it back. When
     * `false`, the controls are always visible and the idle timer never runs.
     */
    autoHideControls?: boolean;
    /**
     * Optional resolver that returns an avatar for a remote participant's
     * host-supplied `peerId` (passed to {@link SerenadaCore.join}). When
     * unset or when `peerId` is absent on the participant, the call UI shows
     * an initials placeholder derived from their display name.
     */
    avatarProvider?: AvatarProvider;
    /**
     * Optional host-app permission requester. Electron/native hosts can use this
     * to bridge OS-level media permission APIs that may disagree with browser
     * `navigator.permissions` state.
     */
    requestPermissions?: (capabilities: MediaCapability[]) => Promise<boolean>;
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
    | 'permissionDeniedSettings'
    | 'grantPermissions'
    | 'cancel'
    | 'debugPanel'
    | 'you'
    | 'remote'
    | 'cameraOff';

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
    permissionDeniedSettings: 'Permission denied. Please allow camera access in system settings.',
    grantPermissions: 'Grant permissions',
    cancel: 'Cancel',
    debugPanel: 'Debug',
    you: 'You',
    remote: 'Remote',
    cameraOff: 'Camera off',
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
