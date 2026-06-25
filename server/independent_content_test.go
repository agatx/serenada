package main

import (
	"encoding/json"
	"testing"
)

// Phase 1 (independent screen share) server tests: capability/mediaPolicy
// allowlist parse + forward, content_state revision relay + persist scoped to
// the owning sid, and the reconnect-aware content-state lifecycle.

func icvBool(b bool) *bool { return &b }

// findParticipant (shared helper in multiparty_test.go) returns the snapshot
// entry for a cid, or nil.

// --- capabilities + mediaPolicy parse & forward ------------------------------

func TestJoinForwardsCapabilitiesAndMediaPolicy(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayloadWithOptions(rid, 4, 4, joinPayloadOptions{
		TrickleIce:              icvBool(true),
		IndependentContentVideo: icvBool(false),
		VideoMediaEnabled:       icvBool(true),
	}))
	aJoined := captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayloadWithOptions(rid, 4, 4, joinPayloadOptions{
		IndependentContentVideo: icvBool(false),
		VideoMediaEnabled:       icvBool(false),
	}))
	bJoined := captureJoined(t, b)

	// B's joined snapshot must carry A's capabilities + mediaPolicy.
	aEntry := findParticipant(bJoined.Participants, aJoined.CID)
	if aEntry == nil {
		t.Fatal("expected A in B's joined snapshot")
	}
	if aEntry.Capabilities == nil {
		t.Fatal("expected A capabilities forwarded")
	}
	if aEntry.Capabilities.TrickleIce == nil || !*aEntry.Capabilities.TrickleIce {
		t.Fatalf("expected A trickleIce=true, got %+v", aEntry.Capabilities)
	}
	if aEntry.Capabilities.IndependentContentVideo == nil || *aEntry.Capabilities.IndependentContentVideo {
		t.Fatalf("expected A independentContentVideo=false, got %+v", aEntry.Capabilities)
	}
	if aEntry.Capabilities.MaxParticipants == nil || *aEntry.Capabilities.MaxParticipants != 4 {
		t.Fatalf("expected A maxParticipants=4, got %+v", aEntry.Capabilities)
	}
	if aEntry.MediaPolicy == nil || aEntry.MediaPolicy.VideoMediaEnabled == nil || !*aEntry.MediaPolicy.VideoMediaEnabled {
		t.Fatalf("expected A mediaPolicy.videoMediaEnabled=true, got %+v", aEntry.MediaPolicy)
	}

	// A then sees B (audio-only) via room_state.
	_, participants, ok := captureRoomState(t, a)
	if !ok {
		t.Fatal("expected room_state on A")
	}
	bEntry := findParticipant(participants, bJoined.CID)
	if bEntry == nil {
		t.Fatal("expected B in A's room_state")
	}
	if bEntry.MediaPolicy == nil || bEntry.MediaPolicy.VideoMediaEnabled == nil || *bEntry.MediaPolicy.VideoMediaEnabled {
		t.Fatalf("expected B mediaPolicy.videoMediaEnabled=false, got %+v", bEntry.MediaPolicy)
	}
}

func TestJoinAllowlistDropsUnknownCapabilityAndPolicyKeys(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayloadWithOptions(rid, 4, 4, joinPayloadOptions{
		IndependentContentVideo: icvBool(true),
		VideoMediaEnabled:       icvBool(true),
		ExtraCapabilities: map[string]interface{}{
			"someFutureFlag": true,
			"sfu":            "maybe",
		},
		ExtraMediaPolicy: map[string]interface{}{
			"audioMediaEnabled": false,
		},
	}))
	captureJoined(t, a)

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	p := room.participantByCID(a.cid)
	room.mu.Unlock()
	if p == nil || p.Capabilities == nil || p.MediaPolicy == nil {
		t.Fatal("expected stored capabilities + mediaPolicy")
	}

	// Re-marshal the stored records: only allowlisted keys may appear.
	capBytes, _ := json.Marshal(p.Capabilities)
	var capMap map[string]interface{}
	_ = json.Unmarshal(capBytes, &capMap)
	for k := range capMap {
		switch k {
		case "trickleIce", "maxParticipants", "independentContentVideo":
		default:
			t.Fatalf("unexpected capability key survived allowlist: %q (%v)", k, capMap)
		}
	}
	if _, leaked := capMap["someFutureFlag"]; leaked {
		t.Fatal("someFutureFlag leaked into stored capabilities")
	}

	mpBytes, _ := json.Marshal(p.MediaPolicy)
	var mpMap map[string]interface{}
	_ = json.Unmarshal(mpBytes, &mpMap)
	for k := range mpMap {
		if k != "videoMediaEnabled" {
			t.Fatalf("unexpected mediaPolicy key survived allowlist: %q (%v)", k, mpMap)
		}
	}
}

func TestLegacyJoinOmitsCapabilitiesAndMediaPolicy(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, legacyJoinPayload(rid))
	captureJoined(t, a)

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	p := room.participantByCID(a.cid)
	room.mu.Unlock()
	if p == nil {
		t.Fatal("expected participant record")
	}
	// A legacy client sends nothing — server stores nothing and clients apply
	// defaults (independentContentVideo=false, videoMediaEnabled=true).
	if p.Capabilities != nil {
		t.Fatalf("expected nil capabilities for legacy join, got %+v", p.Capabilities)
	}
	if p.MediaPolicy != nil {
		t.Fatalf("expected nil mediaPolicy for legacy join, got %+v", p.MediaPolicy)
	}

	// And the wire snapshot omits the objects entirely.
	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	bJoined := captureJoined(t, b)
	aEntry := findParticipant(bJoined.Participants, a.cid)
	if aEntry == nil {
		t.Fatal("expected A in B's snapshot")
	}
	if aEntry.Capabilities != nil || aEntry.MediaPolicy != nil {
		t.Fatalf("expected omitted capabilities/mediaPolicy on wire, got cap=%+v mp=%+v", aEntry.Capabilities, aEntry.MediaPolicy)
	}
}

// TestOmittedRejoinPreservesStoredCapabilitiesAndMediaPolicy verifies that a
// token-recovery reconnect from the same CID that OMITS capabilities/mediaPolicy
// does not erase the previously advertised static media contract. A present
// object is authoritative; an omitted object means "no update".
func TestOmittedRejoinPreservesStoredCapabilitiesAndMediaPolicy(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	// A advertises independentContentVideo=true + videoMediaEnabled=false.
	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayloadWithOptions(rid, 4, 4, joinPayloadOptions{
		IndependentContentVideo: icvBool(true),
		VideoMediaEnabled:       icvBool(false),
	}))
	aJoined := captureJoined(t, a)

	// Sanity: the advertised values are stored verbatim.
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	p := room.participantByCID(aJoined.CID)
	advertisedICV := p != nil && p.Capabilities != nil && p.Capabilities.IndependentContentVideo != nil && *p.Capabilities.IndependentContentVideo
	advertisedVME := p != nil && p.MediaPolicy != nil && p.MediaPolicy.VideoMediaEnabled != nil && !*p.MediaPolicy.VideoMediaEnabled
	room.mu.Unlock()
	if !advertisedICV {
		t.Fatal("expected A to advertise independentContentVideo=true on first join")
	}
	if !advertisedVME {
		t.Fatal("expected A to advertise videoMediaEnabled=false on first join")
	}

	// Same CID rejoins via reconnect, OMITTING capabilities/mediaPolicy
	// (joinWithReconnect carries only maxParticipants + the reconnect token).
	a2 := fakeClient(hub)
	hub.registerClient(a2)
	hub.handleMessage(a2, joinWithReconnect(rid, aJoined.CID, aJoined.ReconnectToken))

	// The stored record must still carry the earlier advertised values because
	// the reconnect did not include replacement capabilities/mediaPolicy objects.
	room.mu.Lock()
	p = room.participantByCID(aJoined.CID)
	if p == nil {
		room.mu.Unlock()
		t.Fatal("expected A's record after reconnect")
	}
	preservedICV := p.Capabilities != nil && p.Capabilities.IndependentContentVideo != nil && *p.Capabilities.IndependentContentVideo
	preservedVME := p.MediaPolicy != nil && p.MediaPolicy.VideoMediaEnabled != nil && !*p.MediaPolicy.VideoMediaEnabled
	room.mu.Unlock()
	if !preservedICV {
		t.Fatalf("expected independentContentVideo preserved on omitting rejoin, got %+v", p.Capabilities)
	}
	if !preservedVME {
		t.Fatalf("expected videoMediaEnabled preserved on omitting rejoin, got %+v", p.MediaPolicy)
	}

	// A peer's joined snapshot must still advertise A's independent-content
	// support and video media policy after A reconnected without those objects.
	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	bJoined := captureJoined(t, b)

	aEntry := findParticipant(bJoined.Participants, aJoined.CID)
	if aEntry == nil {
		t.Fatal("expected A in B's joined snapshot")
	}
	if aEntry.Capabilities == nil || aEntry.Capabilities.IndependentContentVideo == nil || !*aEntry.Capabilities.IndependentContentVideo {
		t.Fatalf("expected forwarded independentContentVideo=true preserved, got %+v", aEntry.Capabilities)
	}
	if aEntry.MediaPolicy == nil || aEntry.MediaPolicy.VideoMediaEnabled == nil || *aEntry.MediaPolicy.VideoMediaEnabled {
		t.Fatalf("expected forwarded videoMediaEnabled=false preserved, got %+v", aEntry.MediaPolicy)
	}
}

// --- content_state revision relay + persist ----------------------------------

func TestContentStateRevisionRelayedVerbatim(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
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

	csPayload, _ := json.Marshal(map[string]interface{}{
		"active":      true,
		"contentType": "screenShare",
		"revision":    7,
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: csPayload}))

	// B receives the relayed content_state with revision verbatim.
	var sawRevision bool
	for _, m := range drainMessages(b) {
		if m.Type != "content_state" {
			continue
		}
		var p struct {
			Active      bool   `json:"active"`
			ContentType string `json:"contentType"`
			Revision    int64  `json:"revision"`
			From        string `json:"from"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			continue
		}
		if p.Active && p.ContentType == "screenShare" && p.Revision == 7 && p.From == a.cid {
			sawRevision = true
		}
	}
	if !sawRevision {
		t.Fatal("expected B to receive relayed content_state with revision=7")
	}

	// And it is persisted on A's record with revision + owning sid.
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	pa := room.participantByCID(a.cid)
	room.mu.Unlock()
	if pa == nil || pa.ContentState == nil {
		t.Fatal("expected persisted content state on A")
	}
	if pa.ContentState.Revision != 7 {
		t.Fatalf("expected persisted revision=7, got %d", pa.ContentState.Revision)
	}
	if pa.SessionID != a.sid {
		t.Fatalf("expected content state scoped to owning sid %q, got %q", a.sid, pa.SessionID)
	}
}

func TestContentStateRevisionPersistsOnlySafeIntegers(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")

	tests := []struct {
		name     string
		revision interface{}
		want     int64
	}{
		{name: "integer", revision: 8, want: 8},
		{name: "fractional", revision: 2.9, want: 0},
		{name: "negative", revision: -1, want: 0},
		{name: "unsafe", revision: float64(9007199254740992), want: 0},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rid := mustTestRoomID(t)
			hub := newHub(4)

			a := fakeClient(hub)
			hub.registerClient(a)
			hub.handleMessage(a, joinPayload(rid, 4, 4))
			captureJoined(t, a)

			csPayload, _ := json.Marshal(map[string]interface{}{
				"active":      true,
				"contentType": "screenShare",
				"revision":    tt.revision,
			})
			hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: csPayload}))

			hub.mu.RLock()
			room := hub.rooms[rid]
			hub.mu.RUnlock()
			room.mu.Lock()
			pa := room.participantByCID(a.cid)
			room.mu.Unlock()
			if pa == nil || pa.ContentState == nil {
				t.Fatal("expected persisted content state on A")
			}
			if pa.ContentState.Revision != tt.want {
				t.Fatalf("expected persisted revision=%d, got %d", tt.want, pa.ContentState.Revision)
			}
		})
	}
}

func TestContentStateRevisionPersistsForReconnectingPeer(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	aJoined := captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	bJoined := captureJoined(t, b)
	drainMessages(a)
	drainMessages(b)

	// B suspends.
	hub.disconnectClient(b)
	drainMessages(a)

	// A shares with a non-trivial revision.
	csPayload, _ := json.Marshal(map[string]interface{}{
		"active":      true,
		"contentType": "screenShare",
		"revision":    42,
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: csPayload}))
	drainMessages(a)

	// B reconnects and must see A's revision via room_state/joined.
	b2 := fakeClient(hub)
	hub.registerClient(b2)
	hub.handleMessage(b2, joinWithReconnect(rid, bJoined.CID, bJoined.ReconnectToken))

	var sawRevision bool
	for _, m := range drainMessages(b2) {
		if m.Type != "joined" && m.Type != "room_state" {
			continue
		}
		var pl struct {
			Participants []Participant `json:"participants"`
		}
		if err := json.Unmarshal(m.Payload, &pl); err != nil {
			continue
		}
		if e := findParticipant(pl.Participants, aJoined.CID); e != nil &&
			e.ContentState != nil && e.ContentState.Active && e.ContentState.Revision == 42 {
			sawRevision = true
		}
	}
	if !sawRevision {
		t.Fatal("expected reattached B to see A's content revision=42")
	}
}

// --- lifecycle ---------------------------------------------------------------

// TestContentStatePreservedAcrossSenderSuspend verifies the share does NOT
// flicker off when the SENDER's transport drops within the reconnect window:
// the persisted content state survives suspend and reattach.
func TestContentStatePreservedAcrossSenderSuspend(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	aJoined := captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	captureJoined(t, b)
	drainMessages(a)
	drainMessages(b)

	// A shares, then A's transport drops (suspend).
	csPayload, _ := json.Marshal(map[string]interface{}{
		"active":      true,
		"contentType": "screenShare",
		"revision":    3,
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: csPayload}))
	drainMessages(a)
	drainMessages(b)

	hub.disconnectClient(a)

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	pa := room.participantByCID(aJoined.CID)
	preserved := pa != nil && pa.ContentState != nil && pa.ContentState.Active && pa.ContentState.Revision == 3
	room.mu.Unlock()
	if !preserved {
		t.Fatal("expected content state preserved while sender is suspended")
	}

	// A reattaches — content state must still be present (no flicker).
	a2 := fakeClient(hub)
	hub.registerClient(a2)
	hub.handleMessage(a2, joinWithReconnect(rid, aJoined.CID, aJoined.ReconnectToken))

	room.mu.Lock()
	pa = room.participantByCID(aJoined.CID)
	stillThere := pa != nil && pa.ContentState != nil && pa.ContentState.Active && pa.ContentState.Revision == 3
	sidUpdated := pa != nil && pa.SessionID == a2.sid
	room.mu.Unlock()
	if !stillThere {
		t.Fatal("expected content state preserved across sender reattach")
	}
	if !sidUpdated {
		t.Fatal("expected owning sid updated to the reattaching transport")
	}
}

// TestContentStateClearedOnExplicitLeave verifies the record (and its content
// state) is gone after an explicit leave.
func TestContentStateClearedOnExplicitLeave(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	aJoined := captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	captureJoined(t, b)
	drainMessages(a)
	drainMessages(b)

	csPayload, _ := json.Marshal(map[string]interface{}{
		"active":      true,
		"contentType": "screenShare",
		"revision":    1,
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: csPayload}))
	drainMessages(a)
	drainMessages(b)

	// A leaves explicitly.
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "leave", RID: rid}))

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	pa := room.participantByCID(aJoined.CID)
	room.mu.Unlock()
	if pa != nil {
		t.Fatalf("expected A's record (and content state) cleared on explicit leave, got %+v", pa.ContentState)
	}
}

// TestContentStateClearedOnHardEviction verifies the suspended record's content
// state is removed when the hard-eviction timer fires.
func TestContentStateClearedOnHardEviction(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	aJoined := captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	captureJoined(t, b)
	drainMessages(a)
	drainMessages(b)

	csPayload, _ := json.Marshal(map[string]interface{}{
		"active":      true,
		"contentType": "screenShare",
		"revision":    9,
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: csPayload}))
	drainMessages(a)
	drainMessages(b)

	// A suspends, then is hard-evicted directly (simulating timer fire).
	hub.disconnectClient(a)
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	hub.hardEvictSuspended(room, aJoined.CID)

	room.mu.Lock()
	pa := room.participantByCID(aJoined.CID)
	room.mu.Unlock()
	if pa != nil {
		t.Fatal("expected A's record (and content state) cleared on hard eviction")
	}
}

// TestMalformedContentStatePreservesRevision asserts a malformed content_state
// (missing boolean active) does not clobber a stored revision.
func TestMalformedContentStatePreservesRevision(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
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

	good, _ := json.Marshal(map[string]interface{}{
		"active":      true,
		"contentType": "screenShare",
		"revision":    5,
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: good}))

	bogus, _ := json.Marshal(map[string]interface{}{
		"revision": 6,
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: bogus}))

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	pa := room.participantByCID(a.cid)
	room.mu.Unlock()
	if pa == nil || pa.ContentState == nil {
		t.Fatal("expected stored content state to survive malformed update")
	}
	if !pa.ContentState.Active || pa.ContentState.Revision != 5 {
		t.Fatalf("expected active=true revision=5 preserved, got %+v", pa.ContentState)
	}
}
