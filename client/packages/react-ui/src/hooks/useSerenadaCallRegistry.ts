import { useEffect, useMemo, useSyncExternalStore } from 'react';
import type {
    CallId,
    CallRegistryState,
    JoinAndSwitchResult,
    JoinResult,
    ManagedCallState,
    RoomRef,
    SerenadaConfig,
    SerenadaSession,
    SwitchResult,
} from '@agatx/serenada-core';
import { SerenadaCallRegistry, SerenadaCore } from '@agatx/serenada-core';

/**
 * A {@link RoomRef} plus an optional host display name carried onto the managed
 * call state. Mirrors the registry's own room input shape.
 */
export type RegistryRoomInput = RoomRef & { displayName?: string };

export interface UseSerenadaCallRegistryOptions {
    config: SerenadaConfig;
    /**
     * How long an `ended` managed call is retained in the published state before
     * it is auto-removed. Defaults to the registry default (remove immediately).
     */
    endedCallRetentionMs?: number;
}

export interface UseSerenadaCallRegistryResult {
    /**
     * The live registry instance. Owned by the hook (constructed once per stable
     * config and torn down on unmount). Hosts can read it for advanced usage, but
     * the reactive fields below are the common surface.
     */
    registry: SerenadaCallRegistry;
    /** All managed calls (value snapshots), reactive. */
    calls: ManagedCallState[];
    /**
     * The foreground managed call with its live {@link SerenadaSession}, or
     * `null` when none is foregrounded. Render it with
     * `<SerenadaCallFlow session={activeCall?.session} />`.
     */
    activeCall: { state: ManagedCallState; session: SerenadaSession } | null;
    /** The single foreground call's id, or `null`. */
    activeCallId: CallId | null;
    /** True while a queued registry operation is mutating the lease/call map. */
    registryOperationInProgress: boolean;
    /** The live {@link SerenadaSession} for a managed call, or `null`. */
    sessionFor: (callId: CallId) => SerenadaSession | null;
    /** Convenience: the held calls (`mediaRole === 'held'`), reactive. */
    heldCalls: ManagedCallState[];
    /** Join a room in the background (held: no capture, no foreground lease). */
    joinHeld: (room: RegistryRoomInput) => Promise<JoinResult>;
    /** Join a room and switch to it (holds the current foreground call first). */
    joinAndSwitch: (room: RegistryRoomInput) => Promise<JoinAndSwitchResult>;
    /** Foreground a held call, holding the current active call first. */
    switchTo: (callId: CallId) => Promise<SwitchResult>;
    /** Hold a call (drain its foreground resources; no auto-promote). */
    hold: (callId: CallId) => Promise<void>;
    /** Leave a call (releases foreground first if active), then tear it down. */
    leave: (callId: CallId) => Promise<void>;
    /** End a call for all participants (releases foreground first if active). */
    end: (callId: CallId) => Promise<void>;
}

const EMPTY_REGISTRY_STATE: CallRegistryState = {
    calls: [],
    activeCallId: null,
    registryOperationInProgress: false,
    lastError: null,
};

// Stable empty-array identity for `heldCalls` when there are none, so consumers
// can use it as an effect/memo dependency without churn (mirrors EMPTY_STREAMS
// in ./constants.ts). A module constant avoids accessing a ref during render.
const EMPTY_HELD_CALLS: ManagedCallState[] = [];

/**
 * Construct and own a {@link SerenadaCallRegistry} (wrapping a {@link
 * SerenadaCore}) and expose its reactive state via `useSyncExternalStore`,
 * mirroring {@link useSerenadaSession}/`useCallState`.
 *
 * Single-call usage is just a registry with one foreground call: a host renders
 * `<SerenadaCallFlow session={registry.activeCall?.session} />` and the existing
 * single-call UX is preserved. A full multi-call switcher component is deferred
 * (the design exposes state + primitives here; hosts build their own switcher).
 *
 * The registry (and its sessions) is torn down on unmount: every live call is
 * left, which releases the foreground lease and the process owning mode.
 */
export function useSerenadaCallRegistry(
    options: UseSerenadaCallRegistryOptions,
): UseSerenadaCallRegistryResult {
    const { config, endedCallRetentionMs } = options;
    const transportsKey = config.transports?.join('|') ?? '';

    const registry = useMemo(
        () => new SerenadaCallRegistry(
            new SerenadaCore({
                ...config,
                transports: config.transports ? [...config.transports] : undefined,
            }),
            {
                endedCallRetentionMs,
                logger: config.logger,
            },
        ),
        // Reconstruct only on the same config keys useSerenadaSession keys on,
        // plus the registry-specific retention. A reconstruct tears down the old
        // registry (cleanup effect below) and builds a fresh one.
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [
            config.serverHost,
            config.defaultAudioEnabled,
            config.defaultVideoEnabled,
            transportsKey,
            config.turnsOnly,
            config.logger,
            endedCallRetentionMs,
        ],
    );

    // Tear down the registry on unmount (or when it is reconstructed): leave
    // every live call so the foreground lease and owning mode are released.
    useEffect(() => {
        return () => {
            for (const call of registry.state.calls) {
                void registry.leave(call.id);
            }
        };
    }, [registry]);

    const state = useSyncExternalStore(
        useMemo(() => (onChange: () => void) => registry.subscribe(onChange), [registry]),
        () => registry.state,
        () => EMPTY_REGISTRY_STATE,
    );

    // The active call carries the live session, so it is read off the registry
    // (not the value snapshot). Re-derive on each state change.
    const activeCall = registry.activeCall;

    // Stable operation callbacks bound to the current registry.
    const ops = useMemo(
        () => ({
            sessionFor: (callId: CallId) => registry.sessionFor(callId),
            joinHeld: (room: RegistryRoomInput) => registry.joinHeld(room),
            joinAndSwitch: (room: RegistryRoomInput) => registry.joinAndSwitch(room),
            switchTo: (callId: CallId) => registry.switchTo(callId),
            hold: (callId: CallId) => registry.hold(callId),
            leave: (callId: CallId) => registry.leave(callId),
            end: (callId: CallId) => registry.end(callId),
        }),
        [registry],
    );

    // Keep a stable empty array identity for heldCalls when there are none, so
    // consumers can use it as an effect/memo dependency without churn.
    const heldCalls = useMemo(() => {
        const held = state.calls.filter((call) => call.held);
        return held.length === 0 ? EMPTY_HELD_CALLS : held;
    }, [state.calls]);

    return {
        registry,
        calls: state.calls,
        activeCall,
        activeCallId: state.activeCallId,
        registryOperationInProgress: state.registryOperationInProgress,
        heldCalls,
        ...ops,
    };
}
