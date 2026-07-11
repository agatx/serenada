import { afterEach, describe, expect, it } from 'vitest';
import {
    SerenadaCallRegistry,
    SerenadaCore,
    SerenadaSession,
} from '../../../core/src/index.js';
// Reset the process-singleton arbiter from its own module, not the public barrel
// (the singleton + reset are intentionally not re-exported from the package entry).
import { __resetForegroundArbiterForTests } from '../../../core/src/foregroundArbiter.js';
import type {
    CallRegistryState,
    RoomRef,
    SerenadaConfig,
} from '../../../core/src/index.js';
import type { MediaEngine } from '../../../core/src/media/MediaEngine.js';
import { FakeSignalingProvider } from '../../../core/test/helpers/FakeSignalingProvider.js';
import { FakeMediaEngine } from '../../../core/test/helpers/FakeMediaEngine.js';
import { useSerenadaCallRegistry } from '../../src/hooks/useSerenadaCallRegistry.js';

// The hook is a thin `useSyncExternalStore` + memoized-callbacks wrapper over
// `SerenadaCallRegistry`. There is no React renderer or DOM environment in this
// project's test setup (no jsdom/@testing-library, and adding one would
// introduce a new dependency, which the repo forbids), so these tests verify the
// exact registry contract the hook is glue over — `subscribe` + `state` (the
// useSyncExternalStore store), `activeCall.session` (the live session the hook
// hands to `<SerenadaCallFlow>`), `activeCallId`, `registryOperationInProgress`,
// and that the operation methods delegate. The hook's own render-time wiring is
// exercised by the app shell (CallRoom) at build time.

// window/document/navigator shim (mirrors the core registry test): the
// preflight/foreground path reads navigator.permissions; default to "granted".
if (typeof globalThis.window === 'undefined') {
    const noop = () => {};
    const handler: ProxyHandler<Record<string, unknown>> = {
        get(_target, prop) {
            if (prop === 'setTimeout') return globalThis.setTimeout.bind(globalThis);
            if (prop === 'clearTimeout') return globalThis.clearTimeout.bind(globalThis);
            if (prop === 'setInterval') return globalThis.setInterval.bind(globalThis);
            if (prop === 'clearInterval') return globalThis.clearInterval.bind(globalThis);
            if (prop === 'addEventListener') return noop;
            if (prop === 'removeEventListener') return noop;
            return undefined;
        },
    };
    (globalThis as Record<string, unknown>).window = new Proxy({}, handler);
}
if (typeof globalThis.document === 'undefined') {
    (globalThis as Record<string, unknown>).document = {
        addEventListener: () => {},
        removeEventListener: () => {},
        visibilityState: 'visible',
        hidden: false,
    };
}
Object.defineProperty(globalThis, 'navigator', {
    value: { permissions: { query: async () => ({ state: 'granted' }) } },
    configurable: true,
    writable: true,
});

afterEach(() => {
    __resetForegroundArbiterForTests();
});

class CallRig {
    readonly signaling = new FakeSignalingProvider();
    readonly media = new FakeMediaEngine();
    readonly session: SerenadaSession;
    private readonly clientId: string;

    constructor(roomId: string, roomUrl: string | null, config: SerenadaConfig, clientId: string) {
        this.clientId = clientId;
        this.session = new SerenadaSession(config, roomId, roomUrl, this.signaling, {
            media: this.media as unknown as MediaEngine,
            autoStart: false,
            initialMediaRole: 'held',
        });
    }

    settleJoined(remote = true): void {
        this.signaling.emitConnected('ws');
        const participants = remote
            ? [{ peerId: this.clientId }, { peerId: 'peer-1' }]
            : [{ peerId: this.clientId }];
        this.signaling.emitJoined({ peerId: this.clientId, participants, hostPeerId: this.clientId });
        this.media.installLocalStream({ audio: true, video: true });
    }
}

function makeRegistry(config: SerenadaConfig = { serverHost: 'localhost:8080' }) {
    const rigs: CallRig[] = [];
    let cidCounter = 0;
    const core = new SerenadaCore(config);
    const registry = new SerenadaCallRegistry(core, {
        createSession: (room: RoomRef) => {
            const roomId = 'url' in room ? room.url : room.roomId;
            const roomUrl = 'url' in room ? room.url : null;
            const rig = new CallRig(roomId, roomUrl, config, `cid-${cidCounter++}`);
            rigs.push(rig);
            return rig.session;
        },
    });
    return { registry, rigs };
}

function settleNextOnJoin(rigs: CallRig[], remote = true): void {
    queueMicrotask(() => rigs[rigs.length - 1]?.settleJoined(remote));
}

describe('useSerenadaCallRegistry', () => {
    it('is exported as a function', () => {
        expect(typeof useSerenadaCallRegistry).toBe('function');
    });

    describe('registry store contract (the surface the hook consumes)', () => {
        it('subscribe returns an unsubscribe and fires on state change', async () => {
            const { registry, rigs } = makeRegistry();
            const states: CallRegistryState[] = [];
            const unsubscribe = registry.subscribe((s) => states.push(s));

            const join = registry.joinAndSwitch({ url: 'https://serenada.app/call/room-a' });
            settleNextOnJoin(rigs);
            await join;

            expect(states.length).toBeGreaterThan(0);
            // The latest snapshot is identical to the `state` getter the hook reads.
            expect(states[states.length - 1]).toBe(registry.state);

            const seen = states.length;
            unsubscribe();
            await registry.hold(registry.state.activeCallId!);
            // No further callbacks after unsubscribe.
            expect(states.length).toBe(seen);
        });

        it('exposes the foreground active call with its live session', async () => {
            const { registry, rigs } = makeRegistry();
            const join = registry.joinAndSwitch({ url: 'https://serenada.app/call/room-a' });
            settleNextOnJoin(rigs);
            const result = await join;

            expect(result.kind).toBe('active');
            const active = registry.activeCall;
            expect(active).not.toBeNull();
            // This is exactly what the hook surfaces for `<SerenadaCallFlow session=...>`.
            expect(active!.session).toBeInstanceOf(SerenadaSession);
            expect(active!.state.id).toBe(registry.state.activeCallId);
            expect(active!.state.mediaRole).toBe('foreground');
            // And it is resolvable via sessionFor (the hook re-exposes this).
            expect(registry.sessionFor(active!.state.id)).toBe(active!.session);
        });

        it('a single call is a registry with one foreground call', async () => {
            const { registry, rigs } = makeRegistry();
            const join = registry.joinAndSwitch({ url: 'https://serenada.app/call/solo' });
            settleNextOnJoin(rigs);
            await join;

            expect(registry.state.calls).toHaveLength(1);
            expect(registry.state.calls[0].mediaRole).toBe('foreground');
            expect(registry.state.calls[0].held).toBe(false);
            expect(registry.state.activeCallId).toBe(registry.state.calls[0].id);
        });

        it('holding the active call clears activeCall (no auto-promote)', async () => {
            const { registry, rigs } = makeRegistry();
            const join = registry.joinAndSwitch({ url: 'https://serenada.app/call/room-a' });
            settleNextOnJoin(rigs);
            await join;
            const callId = registry.state.activeCallId!;

            await registry.hold(callId);

            expect(registry.state.activeCallId).toBeNull();
            expect(registry.activeCall).toBeNull();
            expect(registry.state.calls[0].held).toBe(true);
        });

        it('switchTo holds the prior active call before foregrounding the next', async () => {
            const { registry, rigs } = makeRegistry();
            const firstJoin = registry.joinAndSwitch({ url: 'https://serenada.app/call/room-a' });
            settleNextOnJoin(rigs);
            const first = await firstJoin;
            const secondJoin = registry.joinHeld({ url: 'https://serenada.app/call/room-b' });
            settleNextOnJoin(rigs);
            const second = await secondJoin;

            expect(first.kind).toBe('active');
            expect(second.kind).toBe('joined');
            const firstId = (first as { callId: string }).callId;
            const secondId = (second as { callId: string }).callId;
            expect(registry.state.activeCallId).toBe(firstId);

            const sw = await registry.switchTo(secondId);
            expect(sw.kind).toBe('active');
            expect(registry.state.activeCallId).toBe(secondId);

            const heldIds = registry.state.calls.filter((c) => c.held).map((c) => c.id);
            expect(heldIds).toContain(firstId);
            expect(registry.activeCall!.state.id).toBe(secondId);
        });

        it('registryOperationInProgress is reflected in published state', async () => {
            const { registry, rigs } = makeRegistry();
            let sawInProgress = false;
            registry.subscribe((s) => {
                if (s.registryOperationInProgress) sawInProgress = true;
            });
            const join = registry.joinAndSwitch({ url: 'https://serenada.app/call/room-a' });
            settleNextOnJoin(rigs);
            await join;

            expect(sawInProgress).toBe(true);
            expect(registry.state.registryOperationInProgress).toBe(false);
        });

        it('leave releases the active call and clears the active id', async () => {
            const { registry, rigs } = makeRegistry();
            const join = registry.joinAndSwitch({ url: 'https://serenada.app/call/room-a' });
            settleNextOnJoin(rigs);
            await join;
            const callId = registry.state.activeCallId!;

            await registry.leave(callId);

            expect(registry.state.activeCallId).toBeNull();
            expect(registry.activeCall).toBeNull();
        });
    });
});
