/**
 * Integration tests for the Serenada signaling server.
 *
 * Usage:
 *   node signaling.test.mjs <base-url>
 *
 * Requires the `ws` npm package (installed via package.json in this directory).
 */

import WebSocket from "ws";

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
