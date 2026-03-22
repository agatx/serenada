import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { FakeTransport } from '../helpers/FakeTransport';
import type { TransportHandlers } from '../../src/signaling/transports/types';

// ---------------------------------------------------------------------------
// Provide a minimal `window` shim for the Node test environment.
// SignalingEngine references window.setTimeout / setInterval / clearTimeout /
// clearInterval / sessionStorage.  With vi.useFakeTimers() the global timer
// functions are already faked; we just need `window` to point at globalThis.
// ---------------------------------------------------------------------------
const store: Record<string, string> = {};
if (typeof globalThis.window === 'undefined') {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (globalThis as any).window = globalThis;
}
if (typeof globalThis.sessionStorage === 'undefined') {
    Object.defineProperty(globalThis, 'sessionStorage', {
        value: {
            getItem: (k: string) => store[k] ?? null,
            setItem: (k: string, v: string) => { store[k] = v; },
            removeItem: (k: string) => { delete store[k]; },
        },
        configurable: true,
    });
}

// Collect every FakeTransport created by the mocked factory.
let transports: FakeTransport[] = [];

vi.mock('../../src/signaling/transports/index.js', () => ({
    createSignalingTransport: (kind: 'ws' | 'sse', handlers: TransportHandlers) => {
        const transport = new FakeTransport(kind, handlers);
        transports.push(transport);
        return transport;
    },
}));

// Must be imported AFTER vi.mock so the mock takes effect.
// eslint-disable-next-line @typescript-eslint/consistent-type-imports
let SignalingEngine: typeof import('../../src/signaling/SignalingEngine').SignalingEngine;

beforeEach(async () => {
    transports = [];
    vi.useFakeTimers({ shouldAdvanceTime: false });

    // Clear sessionStorage stub between tests.
    for (const key of Object.keys(store)) delete store[key];

    // Re-import to pick up fresh mock state.
    const mod = await import('../../src/signaling/SignalingEngine');
    SignalingEngine = mod.SignalingEngine;
});

afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function createEngine(transportOrder: ('ws' | 'sse')[] = ['ws', 'sse']) {
    return new SignalingEngine({
        wsUrl: 'ws://localhost/ws',
        httpBaseUrl: 'http://localhost',
        transports: transportOrder,
    });
}

/** Return the last transport created by the factory. */
function lastTransport(): FakeTransport {
    const t = transports.at(-1);
    if (!t) throw new Error('No transport created yet');
    return t;
}

// ---------------------------------------------------------------------------
// 1. Transport fallback — WS never connected → immediate SSE fallback
// ---------------------------------------------------------------------------
describe('transport fallback (never-connected WS)', () => {
    it('falls back to SSE when WS has never connected', () => {
        const engine = createEngine(['ws', 'sse']);
        engine.connect();

        // The first transport should be WS.
        expect(transports).toHaveLength(1);
        expect(transports[0].kind).toBe('ws');

        // Simulate WS failing to connect (close without ever opening).
        transports[0].simulateClose('error');

        // Engine should immediately create SSE transport because WS never connected.
        expect(transports).toHaveLength(2);
        expect(transports[1].kind).toBe('sse');

        engine.destroy();
    });
});

// ---------------------------------------------------------------------------
// 2. Transport fallback — WS connected once, then drops with 'timeout' → SSE
// ---------------------------------------------------------------------------
describe('transport fallback (connected WS drops with timeout)', () => {
    it('falls back to SSE when WS drops with reason "timeout" even after successful connection', () => {
        const engine = createEngine(['ws', 'sse']);
        engine.connect();

        // WS connects successfully.
        const ws1 = lastTransport();
        expect(ws1.kind).toBe('ws');
        ws1.simulateOpen();
        expect(engine.isConnected).toBe(true);
        expect(engine.activeTransport).toBe('ws');

        // WS drops with 'timeout' — shouldFallback returns true for
        // reason === 'timeout' regardless of other state.
        ws1.simulateClose('timeout');

        // Engine should immediately try the next transport (SSE).
        const sseTrans = lastTransport();
        expect(sseTrans.kind).toBe('sse');

        engine.destroy();
    });

    it('falls back to SSE when WS drops with reason "unsupported"', () => {
        const engine = createEngine(['ws', 'sse']);
        engine.connect();

        const ws = lastTransport();
        expect(ws.kind).toBe('ws');
        // Even without opening, 'unsupported' triggers immediate fallback.
        ws.simulateClose('unsupported');

        const sseTrans = lastTransport();
        expect(sseTrans.kind).toBe('sse');

        engine.destroy();
    });
});

// ---------------------------------------------------------------------------
// 3. Ping/pong heartbeat timeout
// ---------------------------------------------------------------------------
describe('ping/pong heartbeat', () => {
    it('sends ping at PING_INTERVAL_MS and force-closes after PONG_MISS_THRESHOLD missed pongs', () => {
        const engine = createEngine(['ws']);
        engine.connect();
        const ws = lastTransport();
        ws.simulateOpen();

        // Advance one PING_INTERVAL_MS — the first tick.
        // On the first tick, Date.now() - lastPongAt <= PING_INTERVAL_MS
        // (both set at the same time) so it just sends a ping.
        vi.advanceTimersByTime(12_000);

        const pings = ws.sentMessages.filter(m => m.type === 'ping');
        expect(pings.length).toBeGreaterThanOrEqual(1);

        // Advance two more intervals WITHOUT responding with pong.
        // Tick 2 (24s): elapsed > 12s → missedPongs = 1, still < threshold.
        vi.advanceTimersByTime(12_000);
        // Tick 3 (36s): elapsed > 12s → missedPongs = 2 → force close.
        vi.advanceTimersByTime(12_000);

        // The transport should have been force-closed.
        expect(engine.isConnected).toBe(false);

        engine.destroy();
    });

    it('resets missed pongs on receiving a pong message', () => {
        const engine = createEngine(['ws']);
        engine.connect();
        const ws = lastTransport();
        ws.simulateOpen();

        // First tick — sends ping.
        vi.advanceTimersByTime(12_000);

        // Respond with pong before the next tick.
        ws.simulateMessage({ v: 1, type: 'pong' });

        // Two more ticks — since pong was received, counter resets.
        vi.advanceTimersByTime(12_000);
        vi.advanceTimersByTime(12_000);

        // Still connected because pong reset the counter.
        expect(engine.isConnected).toBe(true);

        engine.destroy();
    });
});

// ---------------------------------------------------------------------------
// 4. Exponential backoff on reconnect
// ---------------------------------------------------------------------------
describe('exponential backoff', () => {
    it('uses increasing delays capped at RECONNECT_BACKOFF_CAP_MS', () => {
        const engine = createEngine(['ws']);
        engine.connect();
        const ws0 = lastTransport();
        // Must open once so that subsequent failures don't trigger
        // the "never connected" fallback path (which is only for multi-transport).
        // With single-transport ['ws'], shouldFallback returns false because
        // transportOrder.length <= 1 — so it always goes to scheduleReconnect.
        ws0.simulateClose('error');

        const expectedDelays = [500, 1000, 2000, 4000, 5000];

        for (let i = 0; i < expectedDelays.length; i++) {
            const delay = expectedDelays[i];
            const countBefore = transports.length;

            // Advance time just short of the expected delay — no reconnect yet.
            vi.advanceTimersByTime(delay - 1);
            expect(transports.length).toBe(countBefore);

            // Advance the remaining 1ms — reconnect fires.
            vi.advanceTimersByTime(1);
            expect(transports.length).toBe(countBefore + 1);
            expect(lastTransport().kind).toBe('ws');

            // Fail again to trigger next backoff.
            lastTransport().simulateClose('error');
        }

        engine.destroy();
    });
});

// ---------------------------------------------------------------------------
// 5. Auto-rejoin on reconnect
// ---------------------------------------------------------------------------
describe('auto-rejoin on reconnect', () => {
    it('re-sends join for the current room after reconnection', () => {
        const engine = createEngine(['ws']);
        engine.connect();
        const ws1 = lastTransport();
        ws1.simulateOpen();

        // Join a room.
        engine.joinRoom('room-abc');

        const joinMsgs1 = ws1.sentMessages.filter(m => m.type === 'join');
        expect(joinMsgs1).toHaveLength(1);
        expect(joinMsgs1[0].rid).toBe('room-abc');

        // Acknowledge the join so the hard timeout doesn't interfere.
        ws1.simulateMessage({
            v: 1,
            type: 'joined',
            cid: 'c1',
            rid: 'room-abc',
            payload: { hostCid: 'c1', participants: [{ cid: 'c1' }] },
        });

        // Simulate disconnection.
        ws1.simulateClose('error');
        expect(engine.isConnected).toBe(false);

        // Wait for the reconnect backoff (attempt 1 = 500ms).
        vi.advanceTimersByTime(500);
        const ws2 = lastTransport();
        expect(ws2.kind).toBe('ws');

        // Simulate successful reconnect.
        ws2.simulateOpen();
        expect(engine.isConnected).toBe(true);

        // Engine should have automatically re-sent join for room-abc.
        const joinMsgs2 = ws2.sentMessages.filter(m => m.type === 'join');
        expect(joinMsgs2).toHaveLength(1);
        expect(joinMsgs2[0].rid).toBe('room-abc');

        engine.destroy();
    });
});

// ---------------------------------------------------------------------------
// 6. Join hard timeout
// ---------------------------------------------------------------------------
describe('join hard timeout', () => {
    it('sets error after JOIN_HARD_TIMEOUT_MS without a joined ack', () => {
        const engine = createEngine(['ws']);
        engine.connect();
        const ws = lastTransport();
        ws.simulateOpen();

        const stateChanges: string[] = [];
        engine.onStateChange(() => {
            if (engine.error) stateChanges.push(engine.error);
        });

        engine.joinRoom('room-xyz');

        // Advance past join kickstart and recovery, but not hard timeout.
        vi.advanceTimersByTime(14_999);
        expect(engine.error).toBeNull();

        // Advance past JOIN_HARD_TIMEOUT_MS (15 000).
        vi.advanceTimersByTime(1);
        expect(engine.error).toBe('Join timed out');
        expect(stateChanges).toContain('Join timed out');

        engine.destroy();
    });

    it('does not fire if joined ack arrives in time', () => {
        const engine = createEngine(['ws']);
        engine.connect();
        const ws = lastTransport();
        ws.simulateOpen();

        engine.joinRoom('room-xyz');

        // Acknowledge before timeout.
        ws.simulateMessage({
            v: 1,
            type: 'joined',
            cid: 'c2',
            rid: 'room-xyz',
            payload: { hostCid: 'c2', participants: [{ cid: 'c2' }] },
        });

        // Advance well past the hard timeout.
        vi.advanceTimersByTime(20_000);
        expect(engine.error).toBeNull();

        engine.destroy();
    });
});

// ---------------------------------------------------------------------------
// 7. Pending join (connect not ready)
// ---------------------------------------------------------------------------
describe('pending join', () => {
    it('buffers joinRoom and sends it once transport opens', () => {
        const engine = createEngine(['ws']);
        engine.connect();
        const ws = lastTransport();

        // Join before transport is open — should be buffered.
        engine.joinRoom('room-pending');
        expect(ws.sentMessages.filter(m => m.type === 'join')).toHaveLength(0);

        // Now open the transport — join should fire.
        ws.simulateOpen();

        const joins = ws.sentMessages.filter(m => m.type === 'join');
        expect(joins).toHaveLength(1);
        expect(joins[0].rid).toBe('room-pending');

        engine.destroy();
    });
});
