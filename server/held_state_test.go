package main

import (
	"encoding/json"
	"testing"
)

// Multi-call session "held" signaling server tests.
//
// The clients carry an additive `held` flag on participant_media_state. The
// server allowlists media-state fields (it does NOT relay the payload verbatim),
// so `held` must be explicitly parsed, stored on the participant, re-emitted on
// the peer relay, and included in the joined/room_state snapshot the same way
// audio/video mute state is. These tests pin that behavior.

func heldBool(b bool) *bool { return &b }

// mediaStatePayload builds a participant_media_state message. Each pointer field
// that is nil is omitted entirely from the wire payload (so we can assert the
// "only present fields are applied" contract).
func mediaStatePayload(rid string, audio, video, held *bool) []byte {
	payload := map[string]interface{}{}
	if audio != nil {
		payload["audioEnabled"] = *audio
	}
	if video != nil {
		payload["videoEnabled"] = *video
	}
	if held != nil {
		payload["held"] = *held
	}
	payloadBytes, _ := json.Marshal(payload)
	return mustMarshal(Message{V: 1, Type: "participant_media_state", RID: rid, Payload: payloadBytes})
}

// TestMediaStateRelaysHeldToPeer proves the server re-emits `held` on the peer
// relay so the other participant actually receives the hold state. This is the
// core bug: before the fix the server dropped `held` because it rebuilt the
// relay payload from an allowlist that only knew audioEnabled/videoEnabled.
func TestMediaStateRelaysHeldToPeer(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	captureJoined(t, b)
	drainMessages(a)
	drainMessages(b)

	// A goes on hold: audio/video off + held true.
	hub.handleMessage(a, mediaStatePayload(rid, heldBool(false), heldBool(false), heldBool(true)))

	var sawHeld bool
	for _, m := range drainMessages(b) {
		if m.Type != "participant_media_state" {
			continue
		}
		var p struct {
			AudioEnabled *bool  `json:"audioEnabled"`
			VideoEnabled *bool  `json:"videoEnabled"`
			Held         *bool  `json:"held"`
			From         string `json:"from"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			continue
		}
		if p.From != a.cid {
			continue
		}
		if p.Held == nil {
			t.Fatalf("expected held relayed to peer, got payload without held: %s", string(m.Payload))
		}
		if !*p.Held {
			t.Fatalf("expected held=true relayed, got %+v", p)
		}
		// A held sender always also reports audio/video off.
		if p.AudioEnabled == nil || *p.AudioEnabled || p.VideoEnabled == nil || *p.VideoEnabled {
			t.Fatalf("expected held sender to relay audio/video=false, got %+v", p)
		}
		sawHeld = true
	}
	if !sawHeld {
		t.Fatal("expected B to receive a relayed participant_media_state with held=true")
	}
}

// TestMediaStateRelaysHeldFalseOnResume proves resuming a held call relays
// held=false to the peer so the remote converges back to a live presentation.
func TestMediaStateRelaysHeldFalseOnResume(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	captureJoined(t, b)
	drainMessages(a)
	drainMessages(b)

	// Hold, then resume with audio on.
	hub.handleMessage(a, mediaStatePayload(rid, heldBool(false), heldBool(false), heldBool(true)))
	drainMessages(b)
	hub.handleMessage(a, mediaStatePayload(rid, heldBool(true), heldBool(false), heldBool(false)))

	var sawResume bool
	for _, m := range drainMessages(b) {
		if m.Type != "participant_media_state" {
			continue
		}
		var p struct {
			Held *bool  `json:"held"`
			From string `json:"from"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			continue
		}
		if p.From == a.cid && p.Held != nil && !*p.Held {
			sawResume = true
		}
	}
	if !sawResume {
		t.Fatal("expected B to receive a relayed participant_media_state with held=false on resume")
	}
}

// TestMediaStateSnapshotIncludesHeld proves a held participant's `held` flag is
// stored on the participant record and surfaced in the joined/room_state
// snapshot, so a LATE JOINER sees the hold state the same way it sees mute state.
func TestMediaStateSnapshotIncludesHeld(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	aJoined := captureJoined(t, a)

	// A holds before anyone else is present.
	hub.handleMessage(a, mediaStatePayload(rid, heldBool(false), heldBool(false), heldBool(true)))

	// Stored on the participant record.
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	pa := room.participantByCID(aJoined.CID)
	storedHeld := pa != nil && pa.Held != nil && *pa.Held
	room.mu.Unlock()
	if !storedHeld {
		t.Fatal("expected held stored on A's participant record")
	}

	// A late joiner B sees A's held flag in its joined snapshot.
	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	bJoined := captureJoined(t, b)

	aEntry := findParticipant(bJoined.Participants, aJoined.CID)
	if aEntry == nil {
		t.Fatal("expected A in B's joined snapshot")
	}
	if aEntry.Held == nil || !*aEntry.Held {
		t.Fatalf("expected A snapshot to carry held=true, got %+v", aEntry.Held)
	}
}

// TestMediaStateOmittedHeldPreservesStored verifies the additive contract: a
// later participant_media_state that toggles only audio (and omits `held`) does
// not clobber the previously stored held flag, and a legacy client that never
// sends `held` leaves the snapshot field omitted (nil).
func TestMediaStateOmittedHeldPreservesStored(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	captureJoined(t, a)

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()

	// Legacy media-state update (no held field at all): held stays nil/omitted.
	hub.handleMessage(a, mediaStatePayload(rid, heldBool(true), heldBool(true), nil))
	room.mu.Lock()
	pa := room.participantByCID(a.cid)
	legacyHeldNil := pa != nil && pa.Held == nil
	room.mu.Unlock()
	if !legacyHeldNil {
		t.Fatal("expected held to remain nil when never advertised")
	}

	// Now A holds.
	hub.handleMessage(a, mediaStatePayload(rid, heldBool(false), heldBool(false), heldBool(true)))
	// Then A toggles only audio, omitting held: the stored held must persist.
	hub.handleMessage(a, mediaStatePayload(rid, heldBool(true), nil, nil))

	room.mu.Lock()
	pa = room.participantByCID(a.cid)
	preserved := pa != nil && pa.Held != nil && *pa.Held
	room.mu.Unlock()
	if !preserved {
		t.Fatal("expected stored held to survive an audio-only media-state update that omits held")
	}
}
