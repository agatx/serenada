import { useEffect, useMemo, useState, useSyncExternalStore } from 'react';
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
     * The live registry instance, or `null` on the very first render before the
     * mount effect constructs it (mirrors `useSerenadaSession`'s `session`). Owned
     * by the hook (constructed on mount, torn down on unmount). Hosts can read it
     * for advanced usage, but the reactive fields below are the common surface.
     */
    registry: SerenadaCallRegistry | null;
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

// Rejection for an operation invoked before the mount effect built the registry.
function notReady(): Error {
    return new Error('Registry not ready');
}

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

    // Construct the registry INSIDE the mount effect (not `useMemo`) and publish
    // it via state, mirroring `useSerenadaSession`. `useMemo` is the wrong home:
    // React StrictMode (dev) mounts, runs the cleanup (which `close()`s the
    // registry — and `close()` is terminal), then RE-RUNS the effect with the
    // SAME memoized registry, so every subsequent join fails 'Registry is
    // closed'. Building it in the effect gives each mount a fresh, open registry
    // and closes it exactly once on cleanup.
    const [registry, setRegistry] = useState<SerenadaCallRegistry | null>(null);

    useEffect(() => {
        const built = new SerenadaCallRegistry(
            new SerenadaCore({
                ...config,
                transports: config.transports ? [...config.transports] : undefined,
            }),
            {
                endedCallRetentionMs,
                logger: config.logger,
            },
        );
        // eslint-disable-next-line react-hooks/set-state-in-effect -- publish the mount-built registry to consumers (mirrors useSerenadaSession); StrictMode-safe fresh instance per mount
        setRegistry(built);
        // Tear down on unmount (or when a config key below changes): close()
        // leaves every live call (releasing the foreground lease and owning mode)
        // by iterating the registry's authoritative call map, and refuses any
        // in-flight queued create that would otherwise land after unmount.
        return () => {
            void built.close();
            setRegistry(null);
        };
        // Rebuild only on the same config keys useSerenadaSession keys on, plus
        // the registry-specific retention. `config.logger` is deliberately NOT a
        // dep: a host passing an inline `logger` (the common React idiom) mints a
        // new function identity every parent re-render, which would rebuild the
        // registry and silently `leave()` every live call mid-session. The logger
        // is still captured by closure above; a stale logger after a logger swap
        // is the same accepted tradeoff useSerenadaSession makes.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [
        config.serverHost,
        config.defaultAudioEnabled,
        config.defaultVideoEnabled,
        transportsKey,
        config.turnsOnly,
        endedCallRetentionMs,
    ]);

    const state = useSyncExternalStore(
        useMemo(
            () => (onChange: () => void) => (registry ? registry.subscribe(onChange) : () => {}),
            [registry],
        ),
        () => registry?.state ?? EMPTY_REGISTRY_STATE,
        () => EMPTY_REGISTRY_STATE,
    );

    // The active call carries the live session, so it is read off the registry
    // (not the value snapshot). The getter mints a fresh `{state, session}`
    // wrapper on every call, so memoize it on the active call's identity — its
    // published snapshot, which changes whenever `activeCallId` or that call's
    // state changes — to keep the object identity stable across unrelated
    // re-renders (so consumers using it as an effect/memo dep don't churn).
    const activeState =
        state.calls.find((call) => call.id === state.activeCallId) ?? null;
    const activeCall = useMemo(
        () => registry?.activeCall ?? null,
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [registry, activeState],
    );

    // Stable operation callbacks bound to the current registry. Before the mount
    // effect has built the registry (the first render only), operations reject:
    // React runs the effect before any user interaction, so this is a transient
    // guard, not a real path.
    const ops = useMemo(
        () => ({
            sessionFor: (callId: CallId) => registry?.sessionFor(callId) ?? null,
            joinHeld: (room: RegistryRoomInput) =>
                registry ? registry.joinHeld(room) : Promise.reject(notReady()),
            joinAndSwitch: (room: RegistryRoomInput) =>
                registry ? registry.joinAndSwitch(room) : Promise.reject(notReady()),
            switchTo: (callId: CallId) =>
                registry ? registry.switchTo(callId) : Promise.reject(notReady()),
            hold: (callId: CallId) =>
                registry ? registry.hold(callId) : Promise.reject(notReady()),
            leave: (callId: CallId) =>
                registry ? registry.leave(callId) : Promise.reject(notReady()),
            end: (callId: CallId) =>
                registry ? registry.end(callId) : Promise.reject(notReady()),
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
