package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

// helpers ---------------------------------------------------------------------

type joinedFields struct {
	CID                 string        `json:"-"`
	Reconnect           string        `json:"reconnect"`
	Epoch               int64         `json:"epoch"`
	ReconnectToken      string        `json:"reconnectToken"`
	ReconnectTokenTTLMs int64         `json:"reconnectTokenTTLMs"`
	Participants        []Participant `json:"participants"`
}

func captureJoined(t *testing.T, c *Client) joinedFields {
	t.Helper()
	for _, msg := range drainMessages(c) {
		if msg.Type != "joined" {
			continue
		}
		var f joinedFields
		if err := json.Unmarshal(msg.Payload, &f); err != nil {
			t.Fatalf("parse joined payload: %v", err)
		}
		f.CID = msg.CID
		return f
	}
	t.Fatal("expected joined message")
	return joinedFields{}
}

func captureRoomState(t *testing.T, c *Client) (epoch int64, participants []Participant, ok bool) {
	t.Helper()
	for _, msg := range drainMessages(c) {
		if msg.Type != "room_state" {
			continue
		}
		var f struct {
			Epoch        int64         `json:"epoch"`
			Participants []Participant `json:"participants"`
		}
		if err := json.Unmarshal(msg.Payload, &f); err != nil {
			continue
		}
		epoch = f.Epoch
		participants = f.Participants
		ok = true
	}
	return
}

func joinWithReconnect(rid, cid, token string) []byte {
	type caps struct {
		MaxParticipants int `json:"maxParticipants,omitempty"`
	}
	payload := struct {
		Capabilities          caps   `json:"capabilities,omitempty"`
		CreateMaxParticipants int    `json:"createMaxParticipants,omitempty"`
		ReconnectCID          string `json:"reconnectCid,omitempty"`
		ReconnectToken        string `json:"reconnectToken,omitempty"`
	}{
		Capabilities:          caps{MaxParticipants: 4},
		CreateMaxParticipants: 4,
		ReconnectCID:          cid,
		ReconnectToken:        token,
	}
	body, _ := json.Marshal(payload)
	msg := Message{V: 1, Type: "join", RID: rid, Payload: body}
	b, _ := json.Marshal(msg)
	return b
}

func legacyReconnectToken(secret, cid, rid string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(cid + "|" + rid))
	return hex.EncodeToString(mac.Sum(nil))
}

// Reconnect outcomes ----------------------------------------------------------

func TestJoinedPayloadIncludesReconnectOutcomeAndEpochOnFreshJoin(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))

	got := captureJoined(t, a)
	if got.Reconnect != reconnectOutcomeFresh {
		t.Fatalf("expected reconnect=%q on fresh join, got %q", reconnectOutcomeFresh, got.Reconnect)
	}
	if got.Epoch <= 0 {
		t.Fatalf("expected positive epoch on fresh join, got %d", got.Epoch)
	}
	if got.ReconnectToken == "" {
		t.Fatal("expected reconnect token on fresh join")
	}
	if got.ReconnectTokenTTLMs <= 0 {
		t.Fatalf("expected positive reconnectTokenTTLMs, got %d", got.ReconnectTokenTTLMs)
	}
	if got.ReconnectTokenTTLMs != int64(20*time.Minute/time.Millisecond) {
		t.Fatalf("expected reconnectTokenTTLMs to match 20m TTL, got %d", got.ReconnectTokenTTLMs)
	}
}

func TestReconnectTokenRefreshReturnsNewTokenForActiveParticipant(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)

	refreshMsg := Message{V: 1, Type: "reconnect-token-refresh", RID: rid}
	refreshBytes, _ := json.Marshal(refreshMsg)
	hub.handleMessage(a, refreshBytes)

	var sawRefresh bool
	for _, msg := range drainMessages(a) {
		if msg.Type != "reconnect-token-refreshed" {
			continue
		}
		var p struct {
			ReconnectToken      string `json:"reconnectToken"`
			ReconnectTokenTTLMs int64  `json:"reconnectTokenTTLMs"`
		}
		if err := json.Unmarshal(msg.Payload, &p); err != nil {
			t.Fatalf("parse refresh payload: %v", err)
		}
		if p.ReconnectToken == "" {
			t.Fatal("expected refreshed reconnect token")
		}
		if p.ReconnectTokenTTLMs != int64(20*time.Minute/time.Millisecond) {
			t.Fatalf("expected 20m refreshed reconnectTokenTTLMs, got %d", p.ReconnectTokenTTLMs)
		}
		valid, expired := validateReconnectToken(p.ReconnectToken, first.CID, rid)
		if !valid || expired {
			t.Fatalf("expected refreshed token to validate, valid=%t expired=%t", valid, expired)
		}
		sawRefresh = true
	}
	if !sawRefresh {
		t.Fatal("expected reconnect-token-refreshed message")
	}
}

func TestReconnectAfterSuspendReportsReattachedOutcome(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)
	if first.ReconnectToken == "" {
		t.Fatal("expected reconnect token from initial join")
	}

	hub.disconnectClient(a)

	a2 := fakeClient(hub)
	hub.registerClient(a2)
	hub.handleMessage(a2, joinWithReconnect(rid, first.CID, first.ReconnectToken))
	second := captureJoined(t, a2)
	if second.Reconnect != reconnectOutcomeReattached {
		t.Fatalf("expected reconnect=%q after suspend, got %q", reconnectOutcomeReattached, second.Reconnect)
	}
	if second.CID != first.CID {
		t.Fatalf("expected reattach to preserve CID %s, got %s", first.CID, second.CID)
	}
	if second.Epoch <= first.Epoch {
		t.Fatalf("expected epoch to advance after suspend+reattach, first=%d second=%d", first.Epoch, second.Epoch)
	}
}

func TestReconnectAfterRoomGCRecoversCID(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)

	// Hard-evict and confirm the room is gone.
	hub.disconnectClient(a)
	hub.mu.RLock()
	roomBeforeEvict := hub.rooms[rid]
	hub.mu.RUnlock()
	if roomBeforeEvict == nil {
		t.Fatal("expected room to exist after suspend")
	}
	hub.hardEvictSuspended(roomBeforeEvict, first.CID)

	hub.mu.RLock()
	_, exists := hub.rooms[rid]
	hub.mu.RUnlock()
	if exists {
		t.Fatal("expected room to be GC'd after sole participant hard-evicted")
	}

	// Reconnect with the original CID and a still-valid token.
	a2 := fakeClient(hub)
	hub.registerClient(a2)
	hub.handleMessage(a2, joinWithReconnect(rid, first.CID, first.ReconnectToken))
	second := captureJoined(t, a2)
	if second.Reconnect != reconnectOutcomeRecovered {
		t.Fatalf("expected reconnect=%q after room GC, got %q", reconnectOutcomeRecovered, second.Reconnect)
	}
	if second.CID != first.CID {
		t.Fatalf("expected recovered CID to match original %s, got %s", first.CID, second.CID)
	}
}

func TestReconnectAfterEndRoomReturnsRoomEnded(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	host := fakeClient(hub)
	hub.registerClient(host)
	hub.handleMessage(host, joinPayload(rid, 4, 4))
	hostJoined := captureJoined(t, host)

	peer := fakeClient(hub)
	hub.registerClient(peer)
	hub.handleMessage(peer, joinPayload(rid, 4, 4))
	peerJoined := captureJoined(t, peer)
	drainMessages(host)
	drainMessages(peer)

	// Suspend peer so it has reason to use its reconnect token next.
	hub.disconnectClient(peer)

	// Host ends the room.
	endMsg := Message{V: 1, Type: "end_room", RID: rid}
	endBytes, _ := json.Marshal(endMsg)
	hub.handleMessage(host, endBytes)

	hub.mu.RLock()
	_, exists := hub.rooms[rid]
	hub.mu.RUnlock()
	if exists {
		t.Fatal("expected room to be removed after end_room")
	}

	// Peer reconnects with valid token.
	peer2 := fakeClient(hub)
	hub.registerClient(peer2)
	hub.handleMessage(peer2, joinWithReconnect(rid, peerJoined.CID, peerJoined.ReconnectToken))

	got := drainMessages(peer2)
	var saw bool
	for _, msg := range got {
		if msg.Type != "error" {
			continue
		}
		var p struct {
			Code   string `json:"code"`
			Reason string `json:"reason"`
		}
		if err := json.Unmarshal(msg.Payload, &p); err != nil {
			continue
		}
		if p.Code == "ROOM_ENDED" {
			saw = true
			if p.Reason == "" {
				t.Fatal("expected ROOM_ENDED to include reason")
			}
		}
	}
	if !saw {
		t.Fatalf("expected ROOM_ENDED error after end_room, got %+v", got)
	}
	_ = hostJoined
}

// TestFreshJoinClearsTombstoneSoNewParticipantsCanReconnect locks in the
// fix for the bug where ending a room and immediately recreating it via a
// fresh join left the tombstone in place. Any reconnect with a valid
// token (including one issued for the new room session) would then be
// rejected with ROOM_ENDED until the tombstone TTL expired.
func TestFreshJoinClearsTombstoneSoNewParticipantsCanReconnect(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	// Session 1: host joins and ends the room → tombstone created.
	host1 := fakeClient(hub)
	hub.registerClient(host1)
	hub.handleMessage(host1, joinPayload(rid, 4, 4))
	captureJoined(t, host1)

	endMsg := Message{V: 1, Type: "end_room", RID: rid}
	endBytes, _ := json.Marshal(endMsg)
	hub.handleMessage(host1, endBytes)

	if t1 := hub.lookupTombstone(rid); t1 == nil {
		t.Fatal("expected tombstone after end_room")
	}

	// Session 2: someone does a FRESH join to the same RID. Tombstone
	// should be cleared once the room is alive again.
	host2 := fakeClient(hub)
	hub.registerClient(host2)
	hub.handleMessage(host2, joinPayload(rid, 4, 4))
	host2Joined := captureJoined(t, host2)
	if host2Joined.Reconnect != reconnectOutcomeFresh {
		t.Fatalf("expected fresh outcome on second join, got %q", host2Joined.Reconnect)
	}
	if t2 := hub.lookupTombstone(rid); t2 != nil {
		t.Fatal("expected tombstone to be cleared after a fresh join recreates the room")
	}

	// Now the new participant must be able to reconnect with the token
	// they were just issued, without hitting the stale ROOM_ENDED gate.
	hub.disconnectClient(host2)

	host2b := fakeClient(hub)
	hub.registerClient(host2b)
	hub.handleMessage(host2b, joinWithReconnect(rid, host2Joined.CID, host2Joined.ReconnectToken))
	rejoined := captureJoined(t, host2b)
	if rejoined.Reconnect != reconnectOutcomeReattached {
		t.Fatalf("expected reattach for new-room participant, got %q", rejoined.Reconnect)
	}
}

// TestMalformedContentStateDoesNotClearStoredValue locks in the fix for
// the bug where a content_state payload missing a boolean `active` field
// would destructively clear the participant's previously-stored content
// state instead of being ignored.
func TestMalformedContentStateDoesNotClearStoredValue(t *testing.T) {
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

	// A starts a screen share.
	csPayload, _ := json.Marshal(map[string]interface{}{
		"active":      true,
		"contentType": "screen",
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: csPayload}))

	// A sends a malformed update missing the boolean `active`. This must
	// NOT clobber the stored state.
	bogusPayload, _ := json.Marshal(map[string]interface{}{
		"contentType": "huh",
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: bogusPayload}))

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	p := room.participantByCID(a.cid)
	room.mu.Unlock()
	if p == nil || p.ContentState == nil {
		t.Fatal("expected stored content state to survive malformed update")
	}
	if !p.ContentState.Active || p.ContentState.ContentType != "screen" {
		t.Fatalf("expected active=true contentType=screen, got %+v", p.ContentState)
	}
}

func TestExpiredReconnectTokenIsRejected(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	second := captureJoined(t, b)
	if second.CID == "" {
		t.Fatal("expected second participant to join")
	}

	// Issue an artificially-expired token bound to (cid, rid).
	expired := issueReconnectTokenWithExpiry(first.CID, rid, time.Now().Add(-10*time.Minute))
	if expired == "" {
		t.Fatal("expected non-empty expired token")
	}

	hub.disconnectClient(a)

	a2 := fakeClient(hub)
	hub.registerClient(a2)
	hub.handleMessage(a2, joinWithReconnect(rid, first.CID, expired))

	var sawRejection bool
	for _, msg := range drainMessages(a2) {
		if msg.Type != "error" {
			continue
		}
		var p struct {
			Code string `json:"code"`
		}
		if err := json.Unmarshal(msg.Payload, &p); err != nil {
			continue
		}
		if p.Code == "INVALID_RECONNECT_TOKEN" {
			sawRejection = true
		}
	}
	if !sawRejection {
		t.Fatal("expected INVALID_RECONNECT_TOKEN for expired token")
	}

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	if room == nil {
		t.Fatal("expected room to remain while second participant is active")
	}
	room.mu.Lock()
	if p := room.participantByCID(first.CID); p != nil {
		room.mu.Unlock()
		t.Fatal("expected expired-token reconnect to evict stale suspended participant")
	}
	room.mu.Unlock()

	hub.handleMessage(a2, joinPayload(rid, 4, 4))
	rejoined := captureJoined(t, a2)
	if rejoined.CID == first.CID {
		t.Fatal("expected fresh rejoin to receive a new CID after expired token")
	}
	for _, participant := range rejoined.Participants {
		if participant.CID == first.CID {
			t.Fatal("expected fresh rejoin snapshot not to include stale suspended CID")
		}
	}
}

func TestLegacyReconnectTokenIsRejectedAndEvictsSuspendedParticipant(t *testing.T) {
	const secret = "test-reconnect-secret"
	t.Setenv("TURN_SECRET", secret)
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	captureJoined(t, b)

	legacy := legacyReconnectToken(secret, first.CID, rid)
	valid, expired := validateReconnectToken(legacy, first.CID, rid)
	if valid || !expired {
		t.Fatalf("expected legacy token to be rejected as expired, valid=%t expired=%t", valid, expired)
	}

	hub.disconnectClient(a)

	a2 := fakeClient(hub)
	hub.registerClient(a2)
	hub.handleMessage(a2, joinWithReconnect(rid, first.CID, legacy))

	var sawRejection bool
	for _, msg := range drainMessages(a2) {
		if msg.Type != "error" {
			continue
		}
		var p struct {
			Code string `json:"code"`
		}
		if err := json.Unmarshal(msg.Payload, &p); err != nil {
			continue
		}
		if p.Code == "INVALID_RECONNECT_TOKEN" {
			sawRejection = true
		}
	}
	if !sawRejection {
		t.Fatal("expected INVALID_RECONNECT_TOKEN for legacy token")
	}

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	if room == nil {
		t.Fatal("expected room to remain while second participant is active")
	}
	room.mu.Lock()
	if p := room.participantByCID(first.CID); p != nil {
		room.mu.Unlock()
		t.Fatal("expected legacy-token reconnect to evict stale suspended participant")
	}
	room.mu.Unlock()
}

func TestReconnectTokenRefreshNotInRoomUsesAuthoritativeRoomID(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	c := fakeClient(hub)
	c.rid = rid
	c.cid = "stale-cid"
	hub.registerClient(c)

	hub.handleMessage(c, mustMarshal(Message{V: 1, Type: "reconnect-token-refresh", RID: "client-supplied"}))

	var got *Message
	for _, msg := range drainMessages(c) {
		if msg.Type == "error" {
			got = &msg
			break
		}
	}
	if got == nil {
		t.Fatal("expected NOT_IN_ROOM error")
	}
	if got.RID != rid {
		t.Fatalf("expected error rid to use authoritative client room %q, got %q", rid, got.RID)
	}
	var payload struct {
		Code string `json:"code"`
	}
	if err := json.Unmarshal(got.Payload, &payload); err != nil {
		t.Fatalf("parse error payload: %v", err)
	}
	if payload.Code != "NOT_IN_ROOM" {
		t.Fatalf("expected NOT_IN_ROOM, got %q", payload.Code)
	}
}

func TestNoActiveRoomCleanupDeletesGhostOnlyRoom(t *testing.T) {
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

	hub.disconnectClient(a)
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	if room == nil {
		t.Fatal("expected room to exist after first disconnect")
	}
	room.mu.Lock()
	if room.noActiveTimer != nil {
		room.mu.Unlock()
		t.Fatal("did not expect no-active cleanup while another participant is active")
	}
	room.mu.Unlock()

	hub.disconnectClient(b)
	room.mu.Lock()
	if room.noActiveTimer == nil {
		room.mu.Unlock()
		t.Fatal("expected no-active cleanup timer after last active participant disconnects")
	}
	if got := len(room.byClient); got != 0 {
		room.mu.Unlock()
		t.Fatalf("expected no active participants, got %d", got)
	}
	if got := room.participantCount(); got != 2 {
		room.mu.Unlock()
		t.Fatalf("expected two suspended participants, got %d", got)
	}
	room.mu.Unlock()

	hub.cleanupRoomIfNoActive(room)

	hub.mu.RLock()
	_, exists := hub.rooms[rid]
	hub.mu.RUnlock()
	if exists {
		t.Fatal("expected ghost-only room to be deleted by no-active cleanup")
	}
}

func TestNoActiveRoomCleanupCancelledByReconnect(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	captureJoined(t, b)

	hub.disconnectClient(a)
	hub.disconnectClient(b)

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	if room == nil {
		t.Fatal("expected room to exist before no-active cleanup fires")
	}
	room.mu.Lock()
	if room.noActiveTimer == nil {
		room.mu.Unlock()
		t.Fatal("expected no-active cleanup timer")
	}
	room.mu.Unlock()

	a2 := fakeClient(hub)
	hub.registerClient(a2)
	hub.handleMessage(a2, joinWithReconnect(rid, first.CID, first.ReconnectToken))
	rejoined := captureJoined(t, a2)
	if rejoined.Reconnect != reconnectOutcomeReattached {
		t.Fatalf("expected reattached outcome before no-active cleanup, got %q", rejoined.Reconnect)
	}

	room.mu.Lock()
	if room.noActiveTimer != nil {
		room.mu.Unlock()
		t.Fatal("expected reconnect to cancel no-active cleanup timer")
	}
	if got := len(room.byClient); got != 1 {
		room.mu.Unlock()
		t.Fatalf("expected one active participant after reconnect, got %d", got)
	}
	room.mu.Unlock()

	hub.cleanupRoomIfNoActive(room)

	hub.mu.RLock()
	_, exists := hub.rooms[rid]
	hub.mu.RUnlock()
	if !exists {
		t.Fatal("expected room to survive stale no-active cleanup after reconnect")
	}
}

// roomStateEpoch / post-reconnect snapshot ------------------------------------

func TestEpochAdvancesOnEachMembershipChange(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	second := captureJoined(t, b)

	if second.Epoch <= first.Epoch {
		t.Fatalf("expected epoch to advance on additional join, first=%d second=%d", first.Epoch, second.Epoch)
	}

	hub.disconnectClient(a)
	// Find latest room_state on b.
	epoch3, _, ok := captureRoomState(t, b)
	if !ok {
		t.Fatal("expected room_state broadcast after suspend")
	}
	if epoch3 <= second.Epoch {
		t.Fatalf("expected epoch to advance on suspend, prev=%d after=%d", second.Epoch, epoch3)
	}
}

func TestPostReconnectSnapshotSentToReattachedClient(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-reconnect-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 4, 4))
	drainMessages(b)
	drainMessages(a)

	hub.disconnectClient(a)

	a2 := fakeClient(hub)
	hub.registerClient(a2)
	hub.handleMessage(a2, joinWithReconnect(rid, first.CID, first.ReconnectToken))

	var sawSnapshot bool
	var sawJoined bool
	var snapshotEpoch int64
	for _, msg := range drainMessages(a2) {
		if msg.Type == "joined" {
			sawJoined = true
		}
		if msg.Type == "room_state" {
			sawSnapshot = true
			var p struct {
				Epoch int64 `json:"epoch"`
			}
			_ = json.Unmarshal(msg.Payload, &p)
			snapshotEpoch = p.Epoch
		}
	}
	if !sawJoined || !sawSnapshot {
		t.Fatalf("expected joined + room_state on reconnect, joined=%t snapshot=%t", sawJoined, sawSnapshot)
	}
	if snapshotEpoch == 0 {
		t.Fatal("expected snapshot to carry epoch")
	}
}

// Dirty-pair / negotiation_dirty ---------------------------------------------

func TestRelayToSuspendedTargetMarksDirtyAndEmitsRelayFailed(t *testing.T) {
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
	bJoined := captureJoined(t, b)

	drainMessages(a)
	hub.disconnectClient(b)
	drainMessages(a) // clear the suspended-broadcast

	offerPayload, _ := json.Marshal(map[string]interface{}{
		"sdp": "v=0\r\n",
	})
	msg := Message{V: 1, Type: "offer", RID: rid, To: bJoined.CID, Payload: offerPayload}
	raw, _ := json.Marshal(msg)
	hub.handleMessage(a, raw)

	var saw bool
	for _, m := range drainMessages(a) {
		if m.Type != "relay_failed" {
			continue
		}
		var p struct {
			Reason  string   `json:"reason"`
			Targets []string `json:"targets"`
			Of      string   `json:"of"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			continue
		}
		if p.Reason == "target_suspended" && len(p.Targets) == 1 && p.Targets[0] == bJoined.CID && p.Of == "offer" {
			saw = true
		}
	}
	if !saw {
		t.Fatal("expected relay_failed for suspended target")
	}

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	dirty := room.negotiationDirty
	room.mu.Unlock()
	if dirty == nil || !dirty[a.cid][bJoined.CID] {
		t.Fatalf("expected dirty negotiation pair %s→%s, got %+v", a.cid, bJoined.CID, dirty)
	}
}

func TestNegotiationDirtyDeliveredOnReattach(t *testing.T) {
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
	bJoined := captureJoined(t, b)
	drainMessages(a)

	hub.disconnectClient(b)
	drainMessages(a)

	// A attempts to send an offer while B is suspended → marks dirty.
	offerPayload, _ := json.Marshal(map[string]interface{}{"sdp": "v=0\r\n"})
	msg := Message{V: 1, Type: "offer", RID: rid, To: bJoined.CID, Payload: offerPayload}
	raw, _ := json.Marshal(msg)
	hub.handleMessage(a, raw)
	drainMessages(a) // consume relay_failed

	// B reattaches.
	b2 := fakeClient(hub)
	hub.registerClient(b2)
	hub.handleMessage(b2, joinWithReconnect(rid, bJoined.CID, bJoined.ReconnectToken))

	var sawDirty bool
	for _, m := range drainMessages(a) {
		if m.Type != "negotiation_dirty" {
			continue
		}
		var p struct {
			With string `json:"with"`
		}
		_ = json.Unmarshal(m.Payload, &p)
		if p.With == bJoined.CID {
			sawDirty = true
		}
	}
	if !sawDirty {
		t.Fatal("expected negotiation_dirty notification to A after B reattaches")
	}
}

// content_state ---------------------------------------------------------------

func TestContentStatePersistsForReconnectingPeer(t *testing.T) {
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

	// A starts a screen share via content_state.
	csPayload, _ := json.Marshal(map[string]interface{}{
		"active":      true,
		"contentType": "screen",
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "content_state", RID: rid, Payload: csPayload}))
	drainMessages(a)

	// B reconnects.
	b2 := fakeClient(hub)
	hub.registerClient(b2)
	hub.handleMessage(b2, joinWithReconnect(rid, bJoined.CID, bJoined.ReconnectToken))

	var seenContentActive bool
	for _, m := range drainMessages(b2) {
		if m.Type != "joined" && m.Type != "room_state" {
			continue
		}
		var p struct {
			Participants []Participant `json:"participants"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			continue
		}
		for _, part := range p.Participants {
			if part.CID == aJoined.CID && part.ContentState != nil && part.ContentState.Active && part.ContentState.ContentType == "screen" {
				seenContentActive = true
			}
		}
	}
	if !seenContentActive {
		t.Fatal("expected reattached B to see A's active content_state via room_state")
	}
}

// media_liveness --------------------------------------------------------------

func TestMediaLivenessHintDefersHardEviction(t *testing.T) {
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
	bJoined := captureJoined(t, b)
	drainMessages(a)

	hub.disconnectClient(b)

	// A tells the server it is still receiving media from B.
	livenessPayload, _ := json.Marshal(map[string]interface{}{
		"cids": []string{bJoined.CID},
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "media_liveness", RID: rid, Payload: livenessPayload}))

	// Trigger a hard-evict pass synchronously. With a fresh liveness hint we
	// should DEFER, not remove.
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	hub.hardEvictSuspended(room, bJoined.CID)

	room.mu.Lock()
	stillThere := room.participantByCID(bJoined.CID) != nil
	room.mu.Unlock()
	if !stillThere {
		t.Fatal("expected suspended participant with recent liveness to be retained")
	}

	// Force the liveness window to elapse and try again — should evict now.
	room.mu.Lock()
	room.mediaLiveness[bJoined.CID] = time.Now().Add(-2 * mediaLivenessFreshnessWindow).UnixMilli()
	room.mu.Unlock()

	hub.hardEvictSuspended(room, bJoined.CID)
	room.mu.Lock()
	gone := room.participantByCID(bJoined.CID) == nil
	room.mu.Unlock()
	if !gone {
		t.Fatal("expected suspended participant to be evicted once liveness expired")
	}
}

func TestMediaLivenessFastPathEvictsGhostExcludedByActiveReporters(t *testing.T) {
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
	bJoined := captureJoined(t, b)
	drainMessages(a)

	// B drops its signaling transport and becomes suspended.
	hub.disconnectClient(b)

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()

	// Force the suspension dwell to satisfy ghostEvictMinDwell so the fast
	// path is eligible to fire on the next media_liveness from A. Without
	// this the test would have to wait 30s of wall time.
	room.mu.Lock()
	if p := room.participantByCID(bJoined.CID); p != nil {
		p.SuspendedAt = time.Now().Add(-2 * ghostEvictMinDwell).UnixNano()
	}
	room.mu.Unlock()

	// A reports it is alive and observes media — but only from itself
	// (empty cids list since B is the only other participant). With the
	// dwell condition satisfied and no positive media report for B, this
	// is the "ghost" signal: every active peer is reporting and none
	// observe media flowing from B.
	livenessPayload, _ := json.Marshal(map[string]interface{}{
		"cids": []string{},
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "media_liveness", RID: rid, Payload: livenessPayload}))

	room.mu.Lock()
	gone := room.participantByCID(bJoined.CID) == nil
	room.mu.Unlock()
	if !gone {
		t.Fatal("expected fast-path eviction to remove suspended ghost when all active peers report no inbound media")
	}
}

func TestMediaLivenessFastPathRespectsMinDwell(t *testing.T) {
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
	bJoined := captureJoined(t, b)
	drainMessages(a)

	hub.disconnectClient(b)

	// SuspendedAt is "now" — well under ghostEvictMinDwell. A's report
	// should NOT trigger eviction because the suspension is too fresh
	// (a legitimate reattach attempt may still be in flight).
	livenessPayload, _ := json.Marshal(map[string]interface{}{
		"cids": []string{},
	})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "media_liveness", RID: rid, Payload: livenessPayload}))

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	stillThere := room.participantByCID(bJoined.CID) != nil
	room.mu.Unlock()
	if !stillThere {
		t.Fatal("expected fresh suspension (under ghostEvictMinDwell) to survive fast-path check")
	}
}

// TestMediaLivenessFastPathTolersSilentOlderPeer covers the mixed-version
// case from the field: a 3-peer room where peer C is on an older client
// build that doesn't emit media_liveness. When peer B is suspended past the
// dwell and peer A reports inbound media but excludes B, the fast path
// should still fire — the silent peer C does not block eviction. Without
// this tolerance, an older client in the same room indefinitely keeps the
// ghost slot alive.
func TestMediaLivenessFastPathTolersSilentOlderPeer(t *testing.T) {
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
	bJoined := captureJoined(t, b)
	drainMessages(a)

	c := fakeClient(hub)
	hub.registerClient(c)
	hub.handleMessage(c, joinPayload(rid, 4, 4))
	captureJoined(t, c)
	drainMessages(a)
	drainMessages(b)

	// B's transport drops; B becomes a suspended ghost.
	hub.disconnectClient(b)

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()

	// Force the suspension dwell so the fast-path is eligible.
	room.mu.Lock()
	if p := room.participantByCID(bJoined.CID); p != nil {
		p.SuspendedAt = time.Now().Add(-2 * ghostEvictMinDwell).UnixNano()
	}
	room.mu.Unlock()

	// A reports — empty list (B is suspended, doesn't observe). C never
	// emits media_liveness (simulating an older build). With the
	// any-fresh-reporter rule, A's report alone should be enough to evict.
	livenessPayload, _ := json.Marshal(map[string]interface{}{"cids": []string{}})
	hub.handleMessage(a, mustMarshal(Message{V: 1, Type: "media_liveness", RID: rid, Payload: livenessPayload}))

	room.mu.Lock()
	gone := room.participantByCID(bJoined.CID) == nil
	room.mu.Unlock()
	if !gone {
		t.Fatal("expected fast-path eviction to fire even when one active peer never sends media_liveness")
	}
}

// helpers ---------------------------------------------------------------------

func mustMarshal(msg Message) []byte {
	b, _ := json.Marshal(msg)
	return b
}

// Strings helper kept local to keep imports stable.
var _ = strings.HasPrefix
