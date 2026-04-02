/**
 * Integration tests for the Serenada signaling server.
 *
 * Usage:
 *   node signaling.test.mjs <base-url>
 *
 * Requires the `ws` npm package (installed via package.json in this directory).
 */

import WebSocket from "ws";
import crypto from "node:crypto";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const BASE = process.argv[2];
if (!BASE) {
  console.error("Usage: node signaling.test.mjs <base-url>");
  process.exit(1);
}

const WS = BASE.replace(/^http/, "ws");

/** POST /api/room-id and return the roomId string. */
async function createRoom() {
  const res = await fetch(`${BASE}/api/room-id`, { method: "POST" });
  if (!res.ok) throw new Error(`POST /api/room-id failed: ${res.status}`);
  const body = await res.json();
  if (!body.roomId) throw new Error("Missing roomId in response");
  return body.roomId;
}

/**
 * Open a WebSocket, wait for the connection, and return a thin wrapper with
 * helpers for sending protocol messages and receiving them with timeouts.
 */
function connectWS() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS}/ws`);
    const pending = [];   // waiting receive() promises
    const buffer  = [];   // messages received but not yet consumed

    ws.on("open", () => resolve(client));
    ws.on("error", reject);

    ws.on("message", (data) => {
      const msg = JSON.parse(data.toString());
      if (pending.length > 0) {
        pending.shift().resolve(msg);
      } else {
        buffer.push(msg);
      }
    });

    ws.on("close", () => {
      // Reject any pending receives.
      for (const p of pending) {
        p.reject(new Error("WebSocket closed while waiting for message"));
      }
      pending.length = 0;
    });

    const client = {
      ws,
      /** Send a protocol v1 message. */
      send(msg) {
        ws.send(JSON.stringify({ ...msg, v: 1 }));
      },
      /**
       * Wait for the next message matching an optional predicate.
       * If no predicate, returns the very next message.
       * Times out after `ms` milliseconds (default 5000).
       */
      receive(predicate, ms = 5000) {
        // Check buffer first.
        for (let i = 0; i < buffer.length; i++) {
          if (!predicate || predicate(buffer[i])) {
            return Promise.resolve(buffer.splice(i, 1)[0]);
          }
        }
        return new Promise((resolve, reject) => {
          const timer = setTimeout(() => {
            // Remove from pending.
            const idx = pending.indexOf(entry);
            if (idx !== -1) pending.splice(idx, 1);
            reject(new Error(`Timed out waiting for message (${ms}ms)`));
          }, ms);

          const entry = {
            resolve: (msg) => {
              if (predicate && !predicate(msg)) {
                // Not matching — put back in buffer and stay pending.
                buffer.push(msg);
                return;
              }
              clearTimeout(timer);
              resolve(msg);
            },
            reject: (err) => {
              clearTimeout(timer);
              reject(err);
            },
          };
          pending.push(entry);
        });
      },
      close() {
        ws.close();
      },
    };
  });
}

/**
 * Open an SSE stream (GET /sse), parse server-sent events, and return a thin
 * wrapper with the same send/receive/close interface as connectWS().
 * Messages are sent via POST /sse?sid=<sid>.
 */
async function connectSSE() {
  const sid = `S-${crypto.randomBytes(8).toString("hex")}`;
  const controller = new AbortController();

  const pending = [];
  const buffer = [];
  let closed = false;
  let pings = 0;

  let readyResolve;
  const readyPromise = new Promise((r) => { readyResolve = r; });

  function deliverMessage(msg) {
    if (pending.length > 0) {
      pending.shift().resolve(msg);
    } else {
      buffer.push(msg);
    }
  }

  const response = await fetch(`${BASE}/sse?sid=${sid}`, {
    headers: { Accept: "text/event-stream" },
    signal: controller.signal,
  });
  if (!response.ok) throw new Error(`SSE connect failed: ${response.status}`);

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let lineBuf = "";
  let dataBuf = "";

  // Background pump: read chunks, parse SSE framing, dispatch messages.
  (async () => {
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        lineBuf += decoder.decode(value, { stream: true });
        const lines = lineBuf.split("\n");
        lineBuf = lines.pop(); // keep incomplete trailing fragment

        for (const line of lines) {
          if (line.startsWith(":")) {
            if (line.includes("ping")) pings++;
            if (line.includes("ready")) readyResolve();
            continue;
          }
          if (line.startsWith("data: ")) {
            dataBuf += (dataBuf ? "\n" : "") + line.slice(6);
            continue;
          }
          if (line === "" && dataBuf) {
            const msg = JSON.parse(dataBuf);
            dataBuf = "";
            deliverMessage(msg);
          }
        }
      }
    } catch (err) {
      if (err.name !== "AbortError") throw err;
    } finally {
      closed = true;
      for (const p of pending) {
        p.reject(new Error("SSE stream closed while waiting for message"));
      }
      pending.length = 0;
    }
  })();

  await readyPromise;

  const client = {
    get pings() { return pings; },

    send(msg) {
      return fetch(`${BASE}/sse?sid=${sid}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...msg, v: 1 }),
      });
    },

    receive(predicate, ms = 5000) {
      for (let i = 0; i < buffer.length; i++) {
        if (!predicate || predicate(buffer[i])) {
          return Promise.resolve(buffer.splice(i, 1)[0]);
        }
      }
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          const idx = pending.indexOf(entry);
          if (idx !== -1) pending.splice(idx, 1);
          reject(new Error(`Timed out waiting for SSE message (${ms}ms)`));
        }, ms);

        const entry = {
          resolve: (msg) => {
            if (predicate && !predicate(msg)) {
              buffer.push(msg);
              return;
            }
            clearTimeout(timer);
            resolve(msg);
          },
          reject: (err) => {
            clearTimeout(timer);
            reject(err);
          },
        };
        pending.push(entry);
      });
    },

    close() {
      controller.abort();
    },
  };

  return client;
}

// ---------------------------------------------------------------------------
// Test runner
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;
const results = [];

async function test(name, fn) {
  try {
    await fn();
    passed++;
    results.push({ name, ok: true });
    console.log(`[PASS] ${name}`);
  } catch (err) {
    failed++;
    results.push({ name, ok: false, err });
    console.log(`[FAIL] ${name}`);
    console.log(`       ${err.message}`);
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(`Assertion failed: ${message}`);
}

function assertEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

await test("Two-client signaling round-trip", async () => {
  const roomId = await createRoom();

  const clientA = await connectWS();
  clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });

  const joinedA = await clientA.receive((m) => m.type === "joined");
  assertEqual(joinedA.v, 1, "joined.v");
  assert(joinedA.cid, "joined should have cid");
  assert(joinedA.sid, "joined should have sid");
  const payloadA = joinedA.payload;
  assert(payloadA.hostCid, "joined.payload should have hostCid");
  assertEqual(payloadA.hostCid, joinedA.cid, "first joiner should be host");
  assert(Array.isArray(payloadA.participants), "joined should have participants array");
  assertEqual(payloadA.participants.length, 1, "should be 1 participant after first join");

  const clientB = await connectWS();
  clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });

  const joinedB = await clientB.receive((m) => m.type === "joined");
  assertEqual(joinedB.v, 1, "joinedB.v");
  assert(joinedB.cid, "joinedB should have cid");
  assert(joinedB.cid !== joinedA.cid, "clients should have different cids");
  assertEqual(joinedB.payload.participants.length, 2, "should be 2 participants after second join");

  const roomStateA = await clientA.receive(
    (m) => m.type === "room_state" && m.payload.participants.length === 2,
  );
  assertEqual(roomStateA.payload.participants.length, 2, "room_state should show 2 participants");

  clientA.send({
    type: "offer",
    rid: roomId,
    sid: joinedA.sid,
    cid: joinedA.cid,
    to: joinedB.cid,
    payload: { sdp: "test-sdp-offer" },
  });

  const offerB = await clientB.receive((m) => m.type === "offer");
  assertEqual(offerB.payload.sdp, "test-sdp-offer", "offer sdp should match");
  assertEqual(offerB.payload.from, joinedA.cid, "offer.from should be clientA cid");

  clientB.send({
    type: "answer",
    rid: roomId,
    sid: joinedB.sid,
    cid: joinedB.cid,
    to: joinedA.cid,
    payload: { sdp: "test-sdp-answer" },
  });

  const answerA = await clientA.receive((m) => m.type === "answer");
  assertEqual(answerA.payload.sdp, "test-sdp-answer", "answer sdp should match");
  assertEqual(answerA.payload.from, joinedB.cid, "answer.from should be clientB cid");

  clientA.send({ type: "leave", rid: roomId, sid: joinedA.sid, cid: joinedA.cid });

  const roomStateB = await clientB.receive(
    (m) => m.type === "room_state" && m.payload.participants.length === 1,
  );
  assertEqual(roomStateB.payload.participants[0].cid, joinedB.cid, "remaining participant should be clientB");

  clientA.close();
  clientB.close();
});

await test("ICE candidate relay", async () => {
  const roomId = await createRoom();

  const clientA = await connectWS();
  clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedA = await clientA.receive((m) => m.type === "joined");

  const clientB = await connectWS();
  clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedB = await clientB.receive((m) => m.type === "joined");

  await clientA.receive((m) => m.type === "room_state");

  const iceCandidate = {
    candidate: "candidate:1 1 UDP 2122252543 192.168.1.1 12345 typ host",
    sdpMid: "0",
    sdpMLineIndex: 0,
  };

  clientA.send({
    type: "ice",
    rid: roomId,
    sid: joinedA.sid,
    cid: joinedA.cid,
    to: joinedB.cid,
    payload: { candidate: iceCandidate },
  });

  const iceB = await clientB.receive((m) => m.type === "ice");
  assertEqual(iceB.payload.from, joinedA.cid, "ice.from should be clientA");
  assertEqual(iceB.payload.candidate.candidate, iceCandidate.candidate, "ice candidate should match");
  assertEqual(iceB.payload.candidate.sdpMid, "0", "ice sdpMid should match");

  clientA.close();
  clientB.close();
});

await test("Room full error", async () => {
  const roomId = await createRoom();

  const clientA = await connectWS();
  clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  await clientA.receive((m) => m.type === "joined");

  const clientB = await connectWS();
  clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  await clientB.receive((m) => m.type === "joined");

  const clientC = await connectWS();
  clientC.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });

  const errorC = await clientC.receive((m) => m.type === "error");
  assertEqual(errorC.payload.code, "ROOM_FULL", "error code should be ROOM_FULL");

  clientA.close();
  clientB.close();
  clientC.close();
});

await test("Host end_room", async () => {
  const roomId = await createRoom();

  const clientA = await connectWS();
  clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedA = await clientA.receive((m) => m.type === "joined");

  const clientB = await connectWS();
  clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  await clientB.receive((m) => m.type === "joined");

  await clientA.receive((m) => m.type === "room_state");
  await clientB.receive((m) => m.type === "room_state");

  clientA.send({
    type: "end_room",
    rid: roomId,
    sid: joinedA.sid,
    cid: joinedA.cid,
    payload: { reason: "host_ended" },
  });

  const endedA = await clientA.receive((m) => m.type === "room_ended");
  assertEqual(endedA.payload.reason, "host_ended", "room_ended.reason for A");
  assertEqual(endedA.payload.by, joinedA.cid, "room_ended.by should be host");

  const endedB = await clientB.receive((m) => m.type === "room_ended");
  assertEqual(endedB.payload.reason, "host_ended", "room_ended.reason for B");

  clientA.close();
  clientB.close();
});

await test("Non-host end_room rejected", async () => {
  const roomId = await createRoom();

  const clientA = await connectWS();
  clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedA = await clientA.receive((m) => m.type === "joined");

  const clientB = await connectWS();
  clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedB = await clientB.receive((m) => m.type === "joined");

  await clientA.receive((m) => m.type === "room_state");
  await clientB.receive((m) => m.type === "room_state");

  clientB.send({
    type: "end_room",
    rid: roomId,
    sid: joinedB.sid,
    cid: joinedB.cid,
    payload: { reason: "host_ended" },
  });

  const errorB = await clientB.receive((m) => m.type === "error");
  assertEqual(errorB.payload.code, "NOT_HOST", "error code should be NOT_HOST");

  clientA.close();
  clientB.close();
});

await test("Invalid room ID rejected", async () => {
  const clientA = await connectWS();
  clientA.send({ type: "join", rid: "not-a-valid-room-id", payload: {} });

  const errorA = await clientA.receive((m) => m.type === "error");
  assertEqual(errorA.payload.code, "INVALID_ROOM_ID", "error code should be INVALID_ROOM_ID");

  clientA.close();
});

await test("Ping-pong", async () => {
  const clientA = await connectWS();
  clientA.send({ type: "ping", payload: { ts: Date.now() } });

  const pong = await clientA.receive((m) => m.type === "pong");
  assertEqual(pong.v, 1, "pong.v");

  clientA.close();
});

// ---------------------------------------------------------------------------
// SSE Transport Tests
// ---------------------------------------------------------------------------

await test("[SSE] Two-client signaling round-trip", async () => {
  const roomId = await createRoom();

  const clientA = await connectSSE();
  await clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });

  const joinedA = await clientA.receive((m) => m.type === "joined");
  assertEqual(joinedA.v, 1, "joined.v");
  assert(joinedA.cid, "joined should have cid");
  assert(joinedA.sid, "joined should have sid");
  const payloadA = joinedA.payload;
  assert(payloadA.hostCid, "joined.payload should have hostCid");
  assertEqual(payloadA.hostCid, joinedA.cid, "first joiner should be host");
  assert(Array.isArray(payloadA.participants), "joined should have participants array");
  assertEqual(payloadA.participants.length, 1, "should be 1 participant after first join");

  const clientB = await connectSSE();
  await clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });

  const joinedB = await clientB.receive((m) => m.type === "joined");
  assertEqual(joinedB.v, 1, "joinedB.v");
  assert(joinedB.cid, "joinedB should have cid");
  assert(joinedB.cid !== joinedA.cid, "clients should have different cids");
  assertEqual(joinedB.payload.participants.length, 2, "should be 2 participants after second join");

  const roomStateA = await clientA.receive(
    (m) => m.type === "room_state" && m.payload.participants.length === 2,
  );
  assertEqual(roomStateA.payload.participants.length, 2, "room_state should show 2 participants");

  await clientA.send({
    type: "offer",
    rid: roomId,
    sid: joinedA.sid,
    cid: joinedA.cid,
    to: joinedB.cid,
    payload: { sdp: "test-sdp-offer" },
  });

  const offerB = await clientB.receive((m) => m.type === "offer");
  assertEqual(offerB.payload.sdp, "test-sdp-offer", "offer sdp should match");
  assertEqual(offerB.payload.from, joinedA.cid, "offer.from should be clientA cid");

  await clientB.send({
    type: "answer",
    rid: roomId,
    sid: joinedB.sid,
    cid: joinedB.cid,
    to: joinedA.cid,
    payload: { sdp: "test-sdp-answer" },
  });

  const answerA = await clientA.receive((m) => m.type === "answer");
  assertEqual(answerA.payload.sdp, "test-sdp-answer", "answer sdp should match");
  assertEqual(answerA.payload.from, joinedB.cid, "answer.from should be clientB cid");

  await clientA.send({ type: "leave", rid: roomId, sid: joinedA.sid, cid: joinedA.cid });

  const roomStateB = await clientB.receive(
    (m) => m.type === "room_state" && m.payload.participants.length === 1,
  );
  assertEqual(roomStateB.payload.participants[0].cid, joinedB.cid, "remaining participant should be clientB");

  clientA.close();
  clientB.close();
});

await test("[SSE] ICE candidate relay", async () => {
  const roomId = await createRoom();

  const clientA = await connectSSE();
  await clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedA = await clientA.receive((m) => m.type === "joined");

  const clientB = await connectSSE();
  await clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedB = await clientB.receive((m) => m.type === "joined");

  await clientA.receive((m) => m.type === "room_state");

  const iceCandidate = {
    candidate: "candidate:1 1 UDP 2122252543 192.168.1.1 12345 typ host",
    sdpMid: "0",
    sdpMLineIndex: 0,
  };

  await clientA.send({
    type: "ice",
    rid: roomId,
    sid: joinedA.sid,
    cid: joinedA.cid,
    to: joinedB.cid,
    payload: { candidate: iceCandidate },
  });

  const iceB = await clientB.receive((m) => m.type === "ice");
  assertEqual(iceB.payload.from, joinedA.cid, "ice.from should be clientA");
  assertEqual(iceB.payload.candidate.candidate, iceCandidate.candidate, "ice candidate should match");
  assertEqual(iceB.payload.candidate.sdpMid, "0", "ice sdpMid should match");

  clientA.close();
  clientB.close();
});

await test("[SSE] Room full error", async () => {
  const roomId = await createRoom();

  const clientA = await connectSSE();
  await clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  await clientA.receive((m) => m.type === "joined");

  const clientB = await connectSSE();
  await clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  await clientB.receive((m) => m.type === "joined");

  const clientC = await connectSSE();
  await clientC.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });

  const errorC = await clientC.receive((m) => m.type === "error");
  assertEqual(errorC.payload.code, "ROOM_FULL", "error code should be ROOM_FULL");

  clientA.close();
  clientB.close();
  clientC.close();
});

await test("[SSE] Host end_room", async () => {
  const roomId = await createRoom();

  const clientA = await connectSSE();
  await clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedA = await clientA.receive((m) => m.type === "joined");

  const clientB = await connectSSE();
  await clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  await clientB.receive((m) => m.type === "joined");

  await clientA.receive((m) => m.type === "room_state");
  await clientB.receive((m) => m.type === "room_state");

  await clientA.send({
    type: "end_room",
    rid: roomId,
    sid: joinedA.sid,
    cid: joinedA.cid,
    payload: { reason: "host_ended" },
  });

  const endedA = await clientA.receive((m) => m.type === "room_ended");
  assertEqual(endedA.payload.reason, "host_ended", "room_ended.reason for A");
  assertEqual(endedA.payload.by, joinedA.cid, "room_ended.by should be host");

  const endedB = await clientB.receive((m) => m.type === "room_ended");
  assertEqual(endedB.payload.reason, "host_ended", "room_ended.reason for B");

  clientA.close();
  clientB.close();
});

await test("[SSE] Non-host end_room rejected", async () => {
  const roomId = await createRoom();

  const clientA = await connectSSE();
  await clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  await clientA.receive((m) => m.type === "joined");

  const clientB = await connectSSE();
  await clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedB = await clientB.receive((m) => m.type === "joined");

  await clientA.receive((m) => m.type === "room_state");
  await clientB.receive((m) => m.type === "room_state");

  await clientB.send({
    type: "end_room",
    rid: roomId,
    sid: joinedB.sid,
    cid: joinedB.cid,
    payload: { reason: "host_ended" },
  });

  const errorB = await clientB.receive((m) => m.type === "error");
  assertEqual(errorB.payload.code, "NOT_HOST", "error code should be NOT_HOST");

  clientA.close();
  clientB.close();
});

await test("[SSE] Invalid room ID rejected", async () => {
  const clientA = await connectSSE();
  await clientA.send({ type: "join", rid: "not-a-valid-room-id", payload: {} });

  const errorA = await clientA.receive((m) => m.type === "error");
  assertEqual(errorA.payload.code, "INVALID_ROOM_ID", "error code should be INVALID_ROOM_ID");

  clientA.close();
});

await test("[SSE] Ping-pong", async () => {
  const clientA = await connectSSE();
  await clientA.send({ type: "ping", payload: { ts: Date.now() } });

  const pong = await clientA.receive((m) => m.type === "pong");
  assertEqual(pong.v, 1, "pong.v");

  clientA.close();
});

await test("[SSE] Server sends keepalive ping", async () => {
  const client = await connectSSE();
  // Server sends : ping every 12 seconds — wait slightly longer.
  await new Promise((r) => setTimeout(r, 13000));
  assert(client.pings >= 1, `expected at least 1 keepalive ping, got ${client.pings}`);
  client.close();
});

await test("[SSE] POST to unknown sid returns 410", async () => {
  const res = await fetch(`${BASE}/sse?sid=S-nonexistent0000`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ v: 1, type: "ping", payload: {} }),
  });
  assertEqual(res.status, 410, "should return 410 for unknown sid");
});

// ---------------------------------------------------------------------------
// Cross-Transport Tests (WS + SSE in same room)
// ---------------------------------------------------------------------------

await test("[Cross] Two-client signaling round-trip (WS + SSE)", async () => {
  const roomId = await createRoom();

  const clientA = await connectWS();
  clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });

  const joinedA = await clientA.receive((m) => m.type === "joined");
  assertEqual(joinedA.v, 1, "joined.v");
  assert(joinedA.cid, "joined should have cid");
  assertEqual(joinedA.payload.participants.length, 1, "should be 1 participant after first join");

  const clientB = await connectSSE();
  await clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });

  const joinedB = await clientB.receive((m) => m.type === "joined");
  assertEqual(joinedB.v, 1, "joinedB.v");
  assert(joinedB.cid !== joinedA.cid, "clients should have different cids");
  assertEqual(joinedB.payload.participants.length, 2, "should be 2 participants after second join");

  const roomStateA = await clientA.receive(
    (m) => m.type === "room_state" && m.payload.participants.length === 2,
  );
  assertEqual(roomStateA.payload.participants.length, 2, "room_state should show 2 participants");

  clientA.send({
    type: "offer",
    rid: roomId,
    sid: joinedA.sid,
    cid: joinedA.cid,
    to: joinedB.cid,
    payload: { sdp: "test-sdp-offer" },
  });

  const offerB = await clientB.receive((m) => m.type === "offer");
  assertEqual(offerB.payload.sdp, "test-sdp-offer", "offer sdp should match");
  assertEqual(offerB.payload.from, joinedA.cid, "offer.from should be clientA cid");

  await clientB.send({
    type: "answer",
    rid: roomId,
    sid: joinedB.sid,
    cid: joinedB.cid,
    to: joinedA.cid,
    payload: { sdp: "test-sdp-answer" },
  });

  const answerA = await clientA.receive((m) => m.type === "answer");
  assertEqual(answerA.payload.sdp, "test-sdp-answer", "answer sdp should match");
  assertEqual(answerA.payload.from, joinedB.cid, "answer.from should be clientB cid");

  clientA.send({ type: "leave", rid: roomId, sid: joinedA.sid, cid: joinedA.cid });

  const roomStateB = await clientB.receive(
    (m) => m.type === "room_state" && m.payload.participants.length === 1,
  );
  assertEqual(roomStateB.payload.participants[0].cid, joinedB.cid, "remaining participant should be clientB");

  clientA.close();
  clientB.close();
});

await test("[Cross] ICE candidate relay (WS -> SSE)", async () => {
  const roomId = await createRoom();

  const clientA = await connectWS();
  clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedA = await clientA.receive((m) => m.type === "joined");

  const clientB = await connectSSE();
  await clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedB = await clientB.receive((m) => m.type === "joined");

  await clientA.receive((m) => m.type === "room_state");

  const iceCandidate = {
    candidate: "candidate:1 1 UDP 2122252543 192.168.1.1 12345 typ host",
    sdpMid: "0",
    sdpMLineIndex: 0,
  };

  clientA.send({
    type: "ice",
    rid: roomId,
    sid: joinedA.sid,
    cid: joinedA.cid,
    to: joinedB.cid,
    payload: { candidate: iceCandidate },
  });

  const iceB = await clientB.receive((m) => m.type === "ice");
  assertEqual(iceB.payload.from, joinedA.cid, "ice.from should be clientA");
  assertEqual(iceB.payload.candidate.candidate, iceCandidate.candidate, "ice candidate should match");
  assertEqual(iceB.payload.candidate.sdpMid, "0", "ice sdpMid should match");

  clientA.close();
  clientB.close();
});

await test("[Cross] ICE candidate relay (SSE -> WS)", async () => {
  const roomId = await createRoom();

  const clientA = await connectSSE();
  await clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedA = await clientA.receive((m) => m.type === "joined");

  const clientB = await connectWS();
  clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedB = await clientB.receive((m) => m.type === "joined");

  await clientA.receive((m) => m.type === "room_state");

  const iceCandidate = {
    candidate: "candidate:2 1 UDP 2122252543 10.0.0.1 54321 typ host",
    sdpMid: "1",
    sdpMLineIndex: 1,
  };

  await clientA.send({
    type: "ice",
    rid: roomId,
    sid: joinedA.sid,
    cid: joinedA.cid,
    to: joinedB.cid,
    payload: { candidate: iceCandidate },
  });

  const iceB = await clientB.receive((m) => m.type === "ice");
  assertEqual(iceB.payload.from, joinedA.cid, "ice.from should be clientA");
  assertEqual(iceB.payload.candidate.candidate, iceCandidate.candidate, "ice candidate should match");
  assertEqual(iceB.payload.candidate.sdpMid, "1", "ice sdpMid should match");

  clientA.close();
  clientB.close();
});

await test("[Cross] Host end_room across transports", async () => {
  const roomId = await createRoom();

  const clientA = await connectWS();
  clientA.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  const joinedA = await clientA.receive((m) => m.type === "joined");

  const clientB = await connectSSE();
  await clientB.send({ type: "join", rid: roomId, payload: { capabilities: { maxParticipants: 2 } } });
  await clientB.receive((m) => m.type === "joined");

  await clientA.receive((m) => m.type === "room_state");
  await clientB.receive((m) => m.type === "room_state");

  clientA.send({
    type: "end_room",
    rid: roomId,
    sid: joinedA.sid,
    cid: joinedA.cid,
    payload: { reason: "host_ended" },
  });

  const endedA = await clientA.receive((m) => m.type === "room_ended");
  assertEqual(endedA.payload.reason, "host_ended", "room_ended.reason for A");
  assertEqual(endedA.payload.by, joinedA.cid, "room_ended.by should be host");

  const endedB = await clientB.receive((m) => m.type === "room_ended");
  assertEqual(endedB.payload.reason, "host_ended", "room_ended.reason for B");

  clientA.close();
  clientB.close();
});

// ---------------------------------------------------------------------------
// Stress Tests
// ---------------------------------------------------------------------------

await test("[Stress] Rapid join/leave cycling", async () => {
  const COUNT = 10;
  const roomId = await createRoom();
  const clients = [];
  const joinedInfos = [];

  // Join 10 clients sequentially.
  for (let i = 0; i < COUNT; i++) {
    const c = await connectWS();
    clients.push(c);
    c.send({
      type: "join",
      rid: roomId,
      payload: { capabilities: { maxParticipants: COUNT }, createMaxParticipants: COUNT },
    });
    const joined = await c.receive((m) => m.type === "joined", 10000);
    joinedInfos.push(joined);
  }

  // The last joiner should see all participants in its joined payload.
  assertEqual(
    joinedInfos[COUNT - 1].payload.participants.length,
    COUNT,
    `last joiner should see ${COUNT} participants`,
  );

  // Verify all CIDs are unique.
  const cids = new Set(joinedInfos.map((j) => j.cid));
  assertEqual(cids.size, COUNT, "all CIDs should be unique");

  // All leave, then close.
  for (let i = 0; i < COUNT; i++) {
    clients[i].send({
      type: "leave",
      rid: roomId,
      sid: joinedInfos[i].sid,
      cid: joinedInfos[i].cid,
    });
  }

  // Give the server a moment to process all leaves, then close connections.
  await new Promise((r) => setTimeout(r, 500));
  for (const c of clients) c.close();
});

await test("[Stress] Concurrent rooms with no cross-leak", async () => {
  const ROOM_COUNT = 20;

  // Set up all rooms in parallel.
  const rooms = await Promise.all(
    Array.from({ length: ROOM_COUNT }, async (_, i) => {
      const roomId = await createRoom();
      const clientA = await connectWS();
      const clientB = await connectWS();

      clientA.send({
        type: "join",
        rid: roomId,
        payload: { capabilities: { maxParticipants: 2 } },
      });
      const joinedA = await clientA.receive((m) => m.type === "joined", 10000);

      clientB.send({
        type: "join",
        rid: roomId,
        payload: { capabilities: { maxParticipants: 2 } },
      });
      const joinedB = await clientB.receive((m) => m.type === "joined", 10000);

      // Wait for both to see 2 participants.
      await clientA.receive(
        (m) => m.type === "room_state" && m.payload.participants.length === 2,
        10000,
      );

      return { roomId, clientA, clientB, joinedA, joinedB, index: i };
    }),
  );

  // Each room's client A sends an offer with a room-unique SDP.
  for (const r of rooms) {
    r.clientA.send({
      type: "offer",
      rid: r.roomId,
      sid: r.joinedA.sid,
      cid: r.joinedA.cid,
      to: r.joinedB.cid,
      payload: { sdp: `offer-room-${r.index}` },
    });
  }

  // Each room's client B receives the offer and verifies the SDP matches.
  for (const r of rooms) {
    const offer = await r.clientB.receive((m) => m.type === "offer", 10000);
    assertEqual(offer.payload.sdp, `offer-room-${r.index}`, `room ${r.index} SDP should match`);
  }

  // Clean up.
  for (const r of rooms) {
    r.clientA.close();
    r.clientB.close();
  }
});

await test("[Stress] Mixed WS+SSE load in one room", async () => {
  const roomId = await createRoom();

  // Connect 2 WS + 2 SSE clients.
  const ws1 = await connectWS();
  const ws2 = await connectWS();
  const sse1 = await connectSSE();
  const sse2 = await connectSSE();
  const allClients = [
    { client: ws1, label: "ws-1" },
    { client: ws2, label: "ws-2" },
    { client: sse1, label: "sse-1" },
    { client: sse2, label: "sse-2" },
  ];

  // Join all 4 clients.
  const joinedInfos = [];
  for (const { client, label } of allClients) {
    const isSSE = label.startsWith("sse");
    const sendFn = isSSE
      ? (msg) => client.send(msg)
      : (msg) => { client.send(msg); return Promise.resolve(); };
    await sendFn({
      type: "join",
      rid: roomId,
      payload: { capabilities: { maxParticipants: 4 }, createMaxParticipants: 4 },
    });
    const joined = await client.receive((m) => m.type === "joined", 10000);
    joinedInfos.push({ ...joined, label });
  }

  // Wait for the first client to see all 4 participants.
  await ws1.receive(
    (m) => m.type === "room_state" && m.payload.participants.length === 4,
    10000,
  );

  // Each client sends an offer to every other client with a unique SDP.
  for (let i = 0; i < allClients.length; i++) {
    const sender = allClients[i];
    const senderInfo = joinedInfos[i];
    for (let j = 0; j < allClients.length; j++) {
      if (i === j) continue;
      const receiverInfo = joinedInfos[j];
      const isSSE = sender.label.startsWith("sse");
      const sendFn = isSSE
        ? (msg) => sender.client.send(msg)
        : (msg) => { sender.client.send(msg); return Promise.resolve(); };
      await sendFn({
        type: "offer",
        rid: roomId,
        sid: senderInfo.sid,
        cid: senderInfo.cid,
        to: receiverInfo.cid,
        payload: { sdp: `from-${sender.label}` },
      });
    }
  }

  // Each client should receive offers from the other 3.
  for (let i = 0; i < allClients.length; i++) {
    const receiver = allClients[i];
    const expectedSenders = allClients
      .filter((_, j) => j !== i)
      .map((s) => `from-${s.label}`);

    const receivedSdps = new Set();
    for (let k = 0; k < expectedSenders.length; k++) {
      const offer = await receiver.client.receive((m) => m.type === "offer", 10000);
      receivedSdps.add(offer.payload.sdp);
    }

    for (const expected of expectedSenders) {
      assert(receivedSdps.has(expected), `${receiver.label} should receive ${expected}`);
    }
  }

  for (const { client } of allClients) client.close();
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

console.log("");
console.log(`=== Integration Tests ===`);
for (const r of results) {
  console.log(`[${r.ok ? "PASS" : "FAIL"}] ${r.name}`);
}
console.log(`=== ${passed}/${passed + failed} passed ===`);

// Give WebSocket close frames time to propagate before exit
await new Promise((resolve) => setTimeout(resolve, 200));

process.exit(failed > 0 ? 1 : 0);
