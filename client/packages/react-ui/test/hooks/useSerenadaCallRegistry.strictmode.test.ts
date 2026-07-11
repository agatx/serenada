import { describe, expect, it, vi, beforeEach } from 'vitest';

// D-web-4 regression: React StrictMode (dev) mounts a component, runs the effect
// cleanup, then RE-RUNS the effect. The hook used to build the registry in
// `useMemo` and close it in the effect cleanup, so the second mount reused a
// permanently-closed registry and every join failed 'Registry is closed'. The
// fix builds the registry INSIDE the effect (publishing via state), so each
// mount gets a fresh, open registry — mirroring `useSerenadaSession`.
//
// This project has no DOM/renderer in its test setup (no jsdom / @testing-library
// / react-test-renderer) and the repo forbids adding dependencies, so we cannot
// run a real `<StrictMode>` render. Instead we drive the hook through a faithful
// model of StrictMode's documented passive-effect lifecycle
// (setup -> cleanup -> setup) using a minimal mocked `react` dispatcher, so the
// ACTUAL hook code (its effect body + state publication) runs. The core barrel
// is mocked to a lightweight registry so no network is touched and 'closed' is
// observable.

const hoisted = vi.hoisted(() => {
    const createdRegistries: FakeRegistryLike[] = [];

    interface FakeRegistryLike {
        closed: boolean;
        state: unknown;
        joinHeld(room: unknown): Promise<{ kind: string; error?: { message: string } }>;
        close(): Promise<void>;
    }

    class FakeRegistry implements FakeRegistryLike {
        closed = false;
        state = { calls: [], activeCallId: null, registryOperationInProgress: false, lastError: null };
        constructor() {
            createdRegistries.push(this);
        }
        subscribe(): () => void {
            return () => {};
        }
        get activeCall(): null {
            return null;
        }
        sessionFor(): null {
            return null;
        }
        async joinHeld(): Promise<{ kind: string; error?: { message: string } }> {
            if (this.closed) {
                return { kind: 'rejected', error: { message: 'Registry is closed' } };
            }
            return { kind: 'joined' };
        }
        async close(): Promise<void> {
            this.closed = true;
        }
    }

    class FakeCore {
        constructor(_config: unknown) {}
    }

    // Single active hook "host" that the mocked react hooks dispatch to.
    const dispatcher = { current: null as unknown as HostLike };
    interface HostLike {
        useState<T>(init: T | (() => T)): [T, (v: T | ((p: T) => T)) => void];
        useMemo<T>(factory: () => T, deps: unknown[]): T;
        useEffect(fn: () => (() => void) | void, deps: unknown[]): void;
        useSyncExternalStore<T>(subscribe: (cb: () => void) => () => void, getSnapshot: () => T): T;
    }

    return { createdRegistries, FakeRegistry, FakeCore, dispatcher };
});

vi.mock('@agatx/serenada-core', () => ({
    SerenadaCallRegistry: hoisted.FakeRegistry,
    SerenadaCore: hoisted.FakeCore,
}));

vi.mock('react', () => ({
    useState: (init: unknown) => hoisted.dispatcher.current.useState(init),
    useMemo: (factory: () => unknown, deps: unknown[]) => hoisted.dispatcher.current.useMemo(factory, deps),
    useEffect: (fn: () => (() => void) | void, deps: unknown[]) => hoisted.dispatcher.current.useEffect(fn, deps),
    useSyncExternalStore: (
        subscribe: (cb: () => void) => () => void,
        getSnapshot: () => unknown,
    ) => hoisted.dispatcher.current.useSyncExternalStore(subscribe, getSnapshot),
}));

// Imported AFTER the mocks so it binds to the mocked react + core.
import { useSerenadaCallRegistry } from '../../src/hooks/useSerenadaCallRegistry.js';

function shallowEqualDeps(a: unknown[] | undefined, b: unknown[] | undefined): boolean {
    if (!a || !b || a.length !== b.length) return false;
    return a.every((v, i) => Object.is(v, b[i]));
}

interface Cell {
    type: 'state' | 'memo' | 'effect' | 'store';
    value?: unknown;
    deps?: unknown[];
    fn?: () => (() => void) | void;
    cleanup?: (() => void) | void;
    pending?: boolean;
    subscribe?: (cb: () => void) => () => void;
    unsub?: () => void;
}

/**
 * Minimal single-component hooks host that reproduces React StrictMode's
 * passive-effect double-invoke (setup -> cleanup -> setup) for the exact
 * primitives this hook uses.
 */
class StrictModeHookHost<T> {
    private cells: Cell[] = [];
    private cursor = 0;
    private dirty = false;
    result!: T;

    constructor(private readonly hookFn: () => T) {}

    private render(): void {
        hoisted.dispatcher.current = this as unknown as never;
        this.cursor = 0;
        this.result = this.hookFn();
    }

    private commitPendingEffects(): void {
        for (const cell of this.cells) {
            if (cell?.type === 'effect' && cell.pending) {
                const c = cell.fn!();
                cell.cleanup = typeof c === 'function' ? c : undefined;
                cell.pending = false;
            }
        }
    }

    private flush(): void {
        let guard = 0;
        while (this.dirty && guard++ < 50) {
            this.dirty = false;
            this.render();
            this.commitPendingEffects();
        }
    }

    /** Mount, then run StrictMode's dev cleanup+setup, then settle re-renders. */
    mountStrict(): T {
        this.render();
        this.commitPendingEffects();
        // StrictMode dev remount: cleanup then re-run every effect that mounted.
        for (const cell of this.cells) {
            if (cell?.type === 'effect' && cell.cleanup) {
                cell.cleanup();
                const c = cell.fn!();
                cell.cleanup = typeof c === 'function' ? c : undefined;
            }
        }
        this.flush();
        return this.result;
    }

    // --- mocked react hooks ---

    useState<S>(init: S | (() => S)): [S, (v: S | ((p: S) => S)) => void] {
        const i = this.cursor++;
        if (!this.cells[i]) {
            const value = typeof init === 'function' ? (init as () => S)() : init;
            this.cells[i] = { type: 'state', value };
        }
        const cell = this.cells[i];
        const setter = (v: S | ((p: S) => S)) => {
            const next = typeof v === 'function' ? (v as (p: S) => S)(cell.value as S) : v;
            if (!Object.is(next, cell.value)) {
                cell.value = next;
                this.dirty = true;
            }
        };
        return [cell.value as S, setter];
    }

    useMemo<M>(factory: () => M, deps: unknown[]): M {
        const i = this.cursor++;
        const existing = this.cells[i];
        if (existing && existing.type === 'memo' && shallowEqualDeps(existing.deps, deps)) {
            return existing.value as M;
        }
        const value = factory();
        this.cells[i] = { type: 'memo', value, deps };
        return value;
    }

    useEffect(fn: () => (() => void) | void, deps: unknown[]): void {
        const i = this.cursor++;
        const prev = this.cells[i];
        const changed = !prev || prev.type !== 'effect' || !shallowEqualDeps(prev.deps, deps);
        this.cells[i] = {
            type: 'effect',
            deps,
            fn,
            cleanup: prev?.type === 'effect' ? prev.cleanup : undefined,
            pending: changed,
        };
    }

    useSyncExternalStore<V>(subscribe: (cb: () => void) => () => void, getSnapshot: () => V): V {
        const i = this.cursor++;
        const cell = this.cells[i] ?? (this.cells[i] = { type: 'store' });
        if (cell.subscribe !== subscribe) {
            cell.unsub?.();
            cell.unsub = subscribe(() => { this.dirty = true; });
            cell.subscribe = subscribe;
        }
        return getSnapshot();
    }
}

describe('useSerenadaCallRegistry under StrictMode (D-web-4)', () => {
    beforeEach(() => {
        hoisted.createdRegistries.length = 0;
    });

    it('hands consumers an OPEN registry after the StrictMode double-mount cycle', () => {
        const host = new StrictModeHookHost(() =>
            useSerenadaCallRegistry({ config: { serverHost: 'localhost:8080' } }),
        );
        const result = host.mountStrict();

        // Two registries were built (one per effect setup): the first was closed
        // on the strict cleanup, the second is the live one.
        expect(hoisted.createdRegistries).toHaveLength(2);
        expect(hoisted.createdRegistries[0].closed).toBe(true);
        expect(hoisted.createdRegistries[1].closed).toBe(false);

        // The registry handed to consumers is the fresh, open one.
        expect(result.registry).toBe(hoisted.createdRegistries[1]);
        expect(result.registry).not.toBeNull();
        expect((result.registry as unknown as { closed: boolean }).closed).toBe(false);
    });

    it('a join after the StrictMode cycle is not rejected with "Registry is closed"', async () => {
        const host = new StrictModeHookHost(() =>
            useSerenadaCallRegistry({ config: { serverHost: 'localhost:8080' } }),
        );
        const result = host.mountStrict();

        const joinResult = await result.joinHeld({ url: 'https://serenada.app/call/room-a' });
        expect(joinResult.kind).not.toBe('rejected');
    });
});
