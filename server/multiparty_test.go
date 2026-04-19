package main

import (
	"encoding/json"
	"testing"
	"time"
)

// fakeClient creates a Client with a buffered send channel for test assertions.
func fakeClient(hub *Hub) *Client {
	return &Client{
		hub:  hub,
		send: make(chan []byte, 64),
		sid:  generateID("S-"),
	}
}

// lastSentMessage reads the most recently queued message from the client's send channel.
func lastSentMessage(c *Client) *Message {
	select {
	case raw := <-c.send:
		var msg Message
		if err := json.Unmarshal(raw, &msg); err != nil {
			return nil
		}
		return &msg
	default:
		return nil
	}
}

// drainMessages reads all queued messages and returns them.
func drainMessages(c *Client) []Message {
	var msgs []Message
	for {
		select {
		case raw := <-c.send:
			var msg Message
			if err := json.Unmarshal(raw, &msg); err == nil {
				msgs = append(msgs, msg)
			}
		default:
			return msgs
		}
	}
}

type joinPayloadOptions struct {
	ReconnectCID string
	DisplayName  *string
}

// joinPayload builds a raw JSON join message with optional capabilities.
func joinPayload(rid string, capMax int, createMax int) []byte {
	return joinPayloadWithOptions(rid, capMax, createMax, joinPayloadOptions{})
}

func joinPayloadWithOptions(rid string, capMax int, createMax int, options joinPayloadOptions) []byte {
	type caps struct {
		MaxParticipants int `json:"maxParticipants,omitempty"`
	}
	payload := struct {
		Capabilities          caps    `json:"capabilities,omitempty"`
		CreateMaxParticipants int     `json:"createMaxParticipants,omitempty"`
		ReconnectCID          string  `json:"reconnectCid,omitempty"`
		DisplayName           *string `json:"displayName,omitempty"`
	}{
		Capabilities:          caps{MaxParticipants: capMax},
		CreateMaxParticipants: createMax,
		ReconnectCID:          options.ReconnectCID,
		DisplayName:           options.DisplayName,
	}
	payloadBytes, _ := json.Marshal(payload)

	msg := Message{
		V:       1,
		Type:    "join",
		RID:     rid,
		Payload: payloadBytes,
	}
	b, _ := json.Marshal(msg)
	return b
}

func watchRoomsPayload(rids []string) []byte {
	payloadBytes, _ := json.Marshal(map[string]interface{}{
		"rids": rids,
	})
	msg := Message{
		V:       1,
		Type:    "watch_rooms",
		Payload: payloadBytes,
	}
	b, _ := json.Marshal(msg)
	return b
}

// legacyJoinPayload builds a join message without capabilities (legacy client).
func legacyJoinPayload(rid string) []byte {
	msg := Message{
		V:    1,
		Type: "join",
		RID:  rid,
	}
	b, _ := json.Marshal(msg)
	return b
}

func mustTestRoomID(t *testing.T) string {
	t.Helper()
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid, err := generateRoomID()
	if err != nil {
		t.Fatalf("failed to generate room id: %v", err)
	}
	return rid
}

func TestLegacyClientCreates1v1Room(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	c := fakeClient(hub)
	hub.registerClient(c)

	hub.handleMessage(c, legacyJoinPayload(rid))

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()

	if room == nil {
		t.Fatal("room was not created")
	}
	if room.MaxParticipants != 2 {
		t.Fatalf("expected room maxParticipants=2, got %d", room.MaxParticipants)
	}
}

func TestReconnectJoinCanClearDisplayName(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	original := fakeClient(hub)
	hub.registerClient(original)
	initialDisplayName := "Alice"
	hub.handleMessage(original, joinPayloadWithOptions(rid, 4, 4, joinPayloadOptions{
		DisplayName: &initialDisplayName,
	}))

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	if room == nil {
		t.Fatal("expected room to exist")
	}

	room.mu.Lock()
	originalCID := room.cidForClient(original)
	p := room.participantByCID(originalCID)
	if p == nil {
		room.mu.Unlock()
		t.Fatal("expected participant record to exist")
	}
	if p.DisplayName != initialDisplayName {
		room.mu.Unlock()
		t.Fatalf("expected initial display name %q, got %q", initialDisplayName, p.DisplayName)
	}
	room.mu.Unlock()

	reconnected := fakeClient(hub)
	hub.registerClient(reconnected)
	clearedDisplayName := ""
	hub.handleMessage(reconnected, joinPayloadWithOptions(rid, 4, 4, joinPayloadOptions{
		ReconnectCID: originalCID,
		DisplayName:  &clearedDisplayName,
	}))

	room.mu.Lock()
	defer room.mu.Unlock()
	if got := room.cidForClient(reconnected); got != originalCID {
		t.Fatalf("expected reconnect to reuse CID %q, got %q", originalCID, got)
	}
	reattached := room.participantByCID(originalCID)
	if reattached == nil {
		t.Fatal("expected reattached participant record")
	}
	if reattached.DisplayName != "" {
		t.Fatalf("expected reconnect with empty displayName to clear the stored name, got %q", reattached.DisplayName)
	}
}

func TestNewClientCreatesGroupRoomProvisionallyAs1v1(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	c := fakeClient(hub)
	hub.registerClient(c)

	hub.handleMessage(c, joinPayload(rid, 4, 4))

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()

	if room == nil {
		t.Fatal("room was not created")
	}
	if room.MaxParticipants != 2 {
		t.Fatalf("expected provisional room maxParticipants=2, got %d", room.MaxParticipants)
	}
	if room.RequestedMaxParticipants != 4 {
		t.Fatalf("expected requested maxParticipants=4, got %d", room.RequestedMaxParticipants)
	}
	if room.CapacityLocked {
		t.Fatal("expected room capacity to remain unlocked until the second participant joins")
	}
}

func TestNewClientCanJoin1v1Room(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	// First client creates a 1:1 room (legacy)
	c1 := fakeClient(hub)
	hub.registerClient(c1)
	hub.handleMessage(c1, legacyJoinPayload(rid))
	drainMessages(c1)

	// New web client (cap=4) joins the 1:1 room — should succeed
	c2 := fakeClient(hub)
	hub.registerClient(c2)
	hub.handleMessage(c2, joinPayload(rid, 4, 4))

	msgs := drainMessages(c2)
	found := false
	for _, msg := range msgs {
		if msg.Type == "joined" {
			found = true
		}
	}
	if !found {
		t.Fatal("expected new client to successfully join 1:1 room")
	}
}

func TestLegacySecondClientLocksRequestedGroupRoomTo1v1(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	c1 := fakeClient(hub)
	hub.registerClient(c1)
	hub.handleMessage(c1, joinPayload(rid, 4, 4))
	drainMessages(c1)

	c2 := fakeClient(hub)
	hub.registerClient(c2)
	hub.handleMessage(c2, legacyJoinPayload(rid))

	msgs := drainMessages(c2)
	foundJoined := false
	for _, msg := range msgs {
		if msg.Type == "joined" {
			foundJoined = true
		}
	}
	if !foundJoined {
		t.Fatal("expected legacy second client to successfully join provisional room")
	}

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()

	if room == nil {
		t.Fatal("room was not created")
	}
	if room.MaxParticipants != 2 {
		t.Fatalf("expected room maxParticipants=2 after mixed-capability join, got %d", room.MaxParticipants)
	}
	if !room.CapacityLocked {
		t.Fatal("expected room capacity to lock after the second participant joins")
	}
}

func TestLegacyClientRejectedFromLockedGroupRoom(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	c1 := fakeClient(hub)
	hub.registerClient(c1)
	hub.handleMessage(c1, joinPayload(rid, 4, 4))
	drainMessages(c1)

	c2 := fakeClient(hub)
	hub.registerClient(c2)
	hub.handleMessage(c2, joinPayload(rid, 4, 4))
	drainMessages(c1)
	drainMessages(c2)

	c3 := fakeClient(hub)
	hub.registerClient(c3)
	hub.handleMessage(c3, legacyJoinPayload(rid))

	msgs := drainMessages(c3)
	found := false
	for _, msg := range msgs {
		if msg.Type == "error" {
			var payload struct {
				Code string `json:"code"`
			}
			if err := json.Unmarshal(msg.Payload, &payload); err == nil && payload.Code == "ROOM_CAPACITY_UNSUPPORTED" {
				found = true
			}
		}
	}
	if !found {
		t.Fatal("expected ROOM_CAPACITY_UNSUPPORTED error for legacy client joining locked group room")
	}
}

func TestRoomFullEnforcesPerRoomCapacity(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	// Create a 1:1 room and fill it
	c1 := fakeClient(hub)
	hub.registerClient(c1)
	hub.handleMessage(c1, legacyJoinPayload(rid))
	drainMessages(c1)

	c2 := fakeClient(hub)
	hub.registerClient(c2)
	hub.handleMessage(c2, legacyJoinPayload(rid))
	drainMessages(c2)

	// Third client should get ROOM_FULL
	c3 := fakeClient(hub)
	hub.registerClient(c3)
	hub.handleMessage(c3, legacyJoinPayload(rid))

	msgs := drainMessages(c3)
	found := false
	for _, msg := range msgs {
		if msg.Type == "error" {
			var payload struct {
				Code string `json:"code"`
			}
			if err := json.Unmarshal(msg.Payload, &payload); err == nil {
				if payload.Code == "ROOM_FULL" {
					found = true
				}
			}
		}
	}
	if !found {
		t.Fatal("expected ROOM_FULL error for third client in 1:1 room")
	}
}

func TestGroupRoomAccepts4Participants(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	clients := make([]*Client, 4)
	for i := 0; i < 4; i++ {
		clients[i] = fakeClient(hub)
		hub.registerClient(clients[i])
		hub.handleMessage(clients[i], joinPayload(rid, 4, 4))
		drainMessages(clients[i])
	}

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()

	if room == nil {
		t.Fatal("room was not created")
	}

	room.mu.Lock()
	count := room.participantCount()
	room.mu.Unlock()

	if count != 4 {
		t.Fatalf("expected 4 participants, got %d", count)
	}

	// Fifth client should be rejected
	c5 := fakeClient(hub)
	hub.registerClient(c5)
	hub.handleMessage(c5, joinPayload(rid, 4, 4))

	msgs := drainMessages(c5)
	found := false
	for _, msg := range msgs {
		if msg.Type == "error" {
			var payload struct {
				Code string `json:"code"`
			}
			if err := json.Unmarshal(msg.Payload, &payload); err == nil {
				if payload.Code == "ROOM_FULL" {
					found = true
				}
			}
		}
	}
	if !found {
		t.Fatal("expected ROOM_FULL error for fifth client in 4-party room")
	}
}

func TestRelayWithToFieldTargetsSpecificPeer(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	// Create 3-party room
	clients := make([]*Client, 3)
	cids := make([]string, 3)
	for i := 0; i < 3; i++ {
		clients[i] = fakeClient(hub)
		hub.registerClient(clients[i])
		hub.handleMessage(clients[i], joinPayload(rid, 4, 4))
		// Give a moment for the messages to be sent
		time.Sleep(5 * time.Millisecond)
		msgs := drainMessages(clients[i])
		for _, msg := range msgs {
			if msg.Type == "joined" {
				cids[i] = msg.CID
			}
		}
	}

	// Client 0 sends an offer targeted to client 2
	offerPayload, _ := json.Marshal(map[string]interface{}{
		"sdp": "test-sdp",
	})
	offerMsg, _ := json.Marshal(Message{
		V:       1,
		Type:    "offer",
		RID:     rid,
		To:      cids[2],
		Payload: offerPayload,
	})

	// Drain any room_state broadcasts first
	for i := 0; i < 3; i++ {
		drainMessages(clients[i])
	}

	hub.handleMessage(clients[0], offerMsg)
	time.Sleep(5 * time.Millisecond)

	// Client 1 should NOT receive the offer (targeted to client 2)
	msgs1 := drainMessages(clients[1])
	for _, msg := range msgs1 {
		if msg.Type == "offer" {
			t.Fatal("client 1 should not have received targeted offer")
		}
	}

	// Client 2 SHOULD receive the offer
	msgs2 := drainMessages(clients[2])
	found := false
	for _, msg := range msgs2 {
		if msg.Type == "offer" {
			found = true
			var payload map[string]interface{}
			if err := json.Unmarshal(msg.Payload, &payload); err == nil {
				if payload["from"] != cids[0] {
					t.Fatalf("expected from=%s, got %v", cids[0], payload["from"])
				}
			}
		}
	}
	if !found {
		t.Fatal("client 2 should have received the targeted offer")
	}
}

func TestJoinedPayloadIncludesMaxParticipantsAndJoinedAt(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	c := fakeClient(hub)
	hub.registerClient(c)
	hub.handleMessage(c, joinPayload(rid, 4, 4))

	msgs := drainMessages(c)
	for _, msg := range msgs {
		if msg.Type == "joined" {
			var payload struct {
				MaxParticipants int           `json:"maxParticipants"`
				Participants    []Participant `json:"participants"`
			}
			if err := json.Unmarshal(msg.Payload, &payload); err != nil {
				t.Fatalf("failed to parse joined payload: %v", err)
			}
			if payload.MaxParticipants != 2 {
				t.Fatalf("expected provisional maxParticipants=2, got %d", payload.MaxParticipants)
			}
			if len(payload.Participants) != 1 {
				t.Fatalf("expected 1 participant, got %d", len(payload.Participants))
			}
			if payload.Participants[0].JoinedAt == 0 {
				t.Fatal("expected non-zero joinedAt")
			}
			return
		}
	}
	t.Fatal("did not receive joined message")
}

func TestCreateMaxParticipantsClampedToServerCeiling(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(3) // Server ceiling is 3

	c1 := fakeClient(hub)
	hub.registerClient(c1)
	// Client requests 4, but server ceiling is 3.
	hub.handleMessage(c1, joinPayload(rid, 4, 4))
	drainMessages(c1)

	c2 := fakeClient(hub)
	hub.registerClient(c2)
	hub.handleMessage(c2, joinPayload(rid, 4, 4))
	drainMessages(c1)
	drainMessages(c2)

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()

	if room == nil {
		t.Fatal("room was not created")
	}
	if room.RequestedMaxParticipants != 3 {
		t.Fatalf("expected requested maxParticipants clamped to 3, got %d", room.RequestedMaxParticipants)
	}
	if room.MaxParticipants != 3 {
		t.Fatalf("expected room maxParticipants clamped to 3, got %d", room.MaxParticipants)
	}
}

func TestWatchRoomsIncludesMaxParticipants(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	participant := fakeClient(hub)
	hub.registerClient(participant)
	hub.handleMessage(participant, joinPayload(rid, 4, 4))
	drainMessages(participant)

	watcher := fakeClient(hub)
	hub.registerClient(watcher)
	watchPayload, _ := json.Marshal(map[string]interface{}{
		"rids": []string{rid},
	})
	watchMsg, _ := json.Marshal(Message{
		V:       1,
		Type:    "watch_rooms",
		Payload: watchPayload,
	})
	hub.handleMessage(watcher, watchMsg)

	msgs := drainMessages(watcher)
	for _, msg := range msgs {
		if msg.Type != "room_statuses" {
			continue
		}
		var payload map[string]struct {
			Count           int `json:"count"`
			MaxParticipants int `json:"maxParticipants"`
		}
		if err := json.Unmarshal(msg.Payload, &payload); err != nil {
			t.Fatalf("failed to parse room_statuses payload: %v", err)
		}
		if payload[rid].Count != 1 {
			t.Fatalf("expected count=1, got %d", payload[rid].Count)
		}
		if payload[rid].MaxParticipants != 2 {
			t.Fatalf("expected provisional maxParticipants=2, got %d", payload[rid].MaxParticipants)
		}
		return
	}
	t.Fatal("did not receive room_statuses message")
}

func TestWatchRoomsReplacesPreviousSubscriptions(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	ridA := mustTestRoomID(t)
	ridB := mustTestRoomID(t)
	hub := newHub(4)

	watcher := fakeClient(hub)
	hub.registerClient(watcher)
	hub.handleMessage(watcher, watchRoomsPayload([]string{ridA}))
	drainMessages(watcher)

	hub.handleMessage(watcher, watchRoomsPayload([]string{ridB}))
	msgs := drainMessages(watcher)
	foundRoomStatuses := false
	for _, msg := range msgs {
		if msg.Type != "room_statuses" {
			continue
		}
		foundRoomStatuses = true
		var payload map[string]map[string]int
		if err := json.Unmarshal(msg.Payload, &payload); err != nil {
			t.Fatalf("failed to parse room_statuses payload: %v", err)
		}
		if _, ok := payload[ridA]; ok {
			t.Fatalf("did not expect room_statuses payload to include previous room %s", ridA)
		}
		if _, ok := payload[ridB]; !ok {
			t.Fatalf("expected room_statuses payload to include replacement room %s", ridB)
		}
	}
	if !foundRoomStatuses {
		t.Fatal("expected room_statuses message when replacing watched rooms")
	}

	participantA := fakeClient(hub)
	hub.registerClient(participantA)
	hub.handleMessage(participantA, joinPayload(ridA, 4, 4))

	for _, msg := range drainMessages(watcher) {
		if msg.Type == "room_status_update" {
			var payload struct {
				RID string `json:"rid"`
			}
			if err := json.Unmarshal(msg.Payload, &payload); err != nil {
				t.Fatalf("failed to parse room_status_update payload: %v", err)
			}
			if payload.RID == ridA {
				t.Fatalf("did not expect room_status_update for unsubscribed room %s", ridA)
			}
		}
	}

	participantB := fakeClient(hub)
	hub.registerClient(participantB)
	hub.handleMessage(participantB, joinPayload(ridB, 4, 4))

	for _, msg := range drainMessages(watcher) {
		if msg.Type != "room_status_update" {
			continue
		}
		var payload struct {
			RID   string `json:"rid"`
			Count int    `json:"count"`
		}
		if err := json.Unmarshal(msg.Payload, &payload); err != nil {
			t.Fatalf("failed to parse room_status_update payload: %v", err)
		}
		if payload.RID == ridB && payload.Count == 1 {
			return
		}
	}

	t.Fatalf("expected room_status_update for replacement room %s", ridB)
}

func TestWatchRoomsClearsSubscriptionsWithEmptyList(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	watcher := fakeClient(hub)
	hub.registerClient(watcher)
	hub.handleMessage(watcher, watchRoomsPayload([]string{rid}))
	drainMessages(watcher)

	hub.handleMessage(watcher, watchRoomsPayload([]string{}))
	msgs := drainMessages(watcher)
	foundRoomStatuses := false
	for _, msg := range msgs {
		if msg.Type != "room_statuses" {
			continue
		}
		foundRoomStatuses = true
		var payload map[string]map[string]int
		if err := json.Unmarshal(msg.Payload, &payload); err != nil {
			t.Fatalf("failed to parse room_statuses payload: %v", err)
		}
		if len(payload) != 0 {
			t.Fatalf("expected empty room_statuses payload after clearing subscriptions, got %+v", payload)
		}
	}
	if !foundRoomStatuses {
		t.Fatal("expected room_statuses message when clearing watched rooms")
	}

	participant := fakeClient(hub)
	hub.registerClient(participant)
	hub.handleMessage(participant, joinPayload(rid, 4, 4))

	for _, msg := range drainMessages(watcher) {
		if msg.Type == "room_status_update" {
			t.Fatalf("did not expect room_status_update after clearing subscriptions: %+v", msg)
		}
	}
}

func TestRoomStatusUpdateIncludesMaxParticipants(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	watcher := fakeClient(hub)
	hub.registerClient(watcher)
	hub.handleMessage(watcher, watchRoomsPayload([]string{rid}))
	drainMessages(watcher)

	participant := fakeClient(hub)
	hub.registerClient(participant)
	hub.handleMessage(participant, joinPayload(rid, 4, 4))

	msgs := drainMessages(watcher)
	foundFirstUpdate := false
	for _, msg := range msgs {
		if msg.Type != "room_status_update" {
			continue
		}
		var payload struct {
			RID             string `json:"rid"`
			Count           int    `json:"count"`
			MaxParticipants int    `json:"maxParticipants"`
		}
		if err := json.Unmarshal(msg.Payload, &payload); err != nil {
			t.Fatalf("failed to parse room_status_update payload: %v", err)
		}
		if payload.RID == rid && payload.Count == 1 && payload.MaxParticipants == 2 {
			foundFirstUpdate = true
			break
		}
	}
	if !foundFirstUpdate {
		t.Fatal("expected room_status_update with provisional maxParticipants=2 after first join")
	}

	secondParticipant := fakeClient(hub)
	hub.registerClient(secondParticipant)
	hub.handleMessage(secondParticipant, joinPayload(rid, 4, 4))

	msgs = drainMessages(watcher)
	for _, msg := range msgs {
		if msg.Type != "room_status_update" {
			continue
		}
		var payload struct {
			RID             string `json:"rid"`
			Count           int    `json:"count"`
			MaxParticipants int    `json:"maxParticipants"`
		}
		if err := json.Unmarshal(msg.Payload, &payload); err != nil {
			t.Fatalf("failed to parse room_status_update payload: %v", err)
		}
		if payload.RID == rid && payload.Count == 2 && payload.MaxParticipants == 4 {
			return
		}
	}
	t.Fatal("expected room_status_update with locked maxParticipants=4 after second join")
}

// joinAndCaptureCID joins the client to a room and returns the assigned CID
// extracted from the "joined" message (without draining other messages).
func joinAndCaptureCID(t *testing.T, hub *Hub, c *Client, rid string) string {
	t.Helper()
	hub.handleMessage(c, joinPayload(rid, 4, 4))
	for _, msg := range drainMessages(c) {
		if msg.Type == "joined" {
			return msg.CID
		}
	}
	t.Fatal("expected joined message with CID")
	return ""
}

// participantsInBroadcast returns the participants array from the most recent
// room_state broadcast queued for the client, or nil if none is found.
func participantsInBroadcast(c *Client) []Participant {
	var latest []Participant
	for _, msg := range drainMessages(c) {
		if msg.Type != "room_state" {
			continue
		}
		var p struct {
			Participants []Participant `json:"participants"`
		}
		if err := json.Unmarshal(msg.Payload, &p); err == nil {
			latest = p.Participants
		}
	}
	return latest
}

func findParticipant(list []Participant, cid string) *Participant {
	for i := range list {
		if list[i].CID == cid {
			return &list[i]
		}
	}
	return nil
}

// TestDisconnectSuspendsParticipantWithoutRemovingIt verifies the core MVP
// guarantee: when a client's signaling transport drops, the participant is
// NOT removed from the room. Peers receive a room_state with
// connectionStatus="suspended" so they can show a "reconnecting" indicator
// while keeping their peer connection alive. The slot is preserved for the
// suspend window.
func TestDisconnectSuspendsParticipantWithoutRemovingIt(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	aCID := joinAndCaptureCID(t, hub, a, rid)

	b := fakeClient(hub)
	hub.registerClient(b)
	joinAndCaptureCID(t, hub, b, rid)
	drainMessages(b) // clear b's joined+room_state messages

	hub.disconnectClient(a)

	// B should see a room_state carrying A's CID with connectionStatus=suspended.
	broadcast := participantsInBroadcast(b)
	if broadcast == nil {
		t.Fatal("expected room_state broadcast to B after A's disconnect")
	}
	a2 := findParticipant(broadcast, aCID)
	if a2 == nil {
		t.Fatalf("expected A (cid %s) to still appear in room_state, participants=%+v", aCID, broadcast)
	}
	if a2.ConnectionStatus != connectionStatusSuspended {
		t.Fatalf("expected connectionStatus=%q for suspended A, got %q", connectionStatusSuspended, a2.ConnectionStatus)
	}

	// Participant record is preserved in the room (slot held through suspend
	// window). Capacity still accounts for A.
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	if room == nil {
		t.Fatal("expected room to still exist after suspend")
	}
	room.mu.Lock()
	if got := room.participantCount(); got != 2 {
		room.mu.Unlock()
		t.Fatalf("expected participantCount=2 after suspend, got %d", got)
	}
	p := room.participantByCID(aCID)
	if p == nil {
		room.mu.Unlock()
		t.Fatal("expected A's participant record to exist")
	}
	if p.Client != nil {
		room.mu.Unlock()
		t.Fatal("expected suspended participant to have detached Client")
	}
	if p.hardEvictionTimer == nil {
		room.mu.Unlock()
		t.Fatal("expected hard-eviction timer to be scheduled")
	}
	room.mu.Unlock()
}

// TestReconnectReattachesSuspendedParticipant verifies that a client can
// reconnect after its transport dropped and reclaim its CID without the
// peer ever seeing a tear-down. The reattached participant appears as
// active (no connectionStatus field in the broadcast).
func TestReconnectReattachesSuspendedParticipant(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	aCID := joinAndCaptureCID(t, hub, a, rid)

	b := fakeClient(hub)
	hub.registerClient(b)
	joinAndCaptureCID(t, hub, b, rid)
	drainMessages(b)

	hub.disconnectClient(a)
	drainMessages(b) // clear the suspended broadcast

	// Reconnect: fresh Client instance submits a join with reconnectCid.
	a2 := fakeClient(hub)
	hub.registerClient(a2)
	hub.handleMessage(a2, joinPayloadWithOptions(rid, 4, 4, joinPayloadOptions{
		ReconnectCID: aCID,
	}))

	// B sees the reattach — A is back to active (ConnectionStatus omitted).
	broadcast := participantsInBroadcast(b)
	if broadcast == nil {
		t.Fatal("expected room_state broadcast to B after A reconnects")
	}
	p := findParticipant(broadcast, aCID)
	if p == nil {
		t.Fatalf("expected A (cid %s) in broadcast after reconnect, got %+v", aCID, broadcast)
	}
	if p.ConnectionStatus != "" {
		t.Fatalf("expected active (empty) connectionStatus after reconnect, got %q", p.ConnectionStatus)
	}

	// Server-side record: hard-eviction timer stopped, Client reattached.
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	defer room.mu.Unlock()
	rp := room.participantByCID(aCID)
	if rp == nil {
		t.Fatal("expected participant record after reconnect")
	}
	if rp.Client != a2 {
		t.Fatalf("expected Client to be the reconnected client, got %v", rp.Client)
	}
	if rp.SuspendedAt != 0 {
		t.Fatal("expected SuspendedAt to be cleared on reattach")
	}
	if rp.hardEvictionTimer != nil {
		t.Fatal("expected hard-eviction timer to be cleared on reattach")
	}
}

// TestHardEvictionRemovesParticipantAfterSuspendWindow verifies that the
// suspend window is bounded: if the client never reconnects, the participant
// is eventually evicted and the peer receives a final room_state with the
// participant absent (tear-down signal).
func TestHardEvictionRemovesParticipantAfterSuspendWindow(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	aCID := joinAndCaptureCID(t, hub, a, rid)

	b := fakeClient(hub)
	hub.registerClient(b)
	joinAndCaptureCID(t, hub, b, rid)
	drainMessages(b)

	hub.disconnectClient(a)
	drainMessages(b) // clear suspended broadcast

	// Simulate the hard-eviction timer firing (called directly so the test
	// doesn't have to wait suspendHardEvictionTimeout).
	hub.mu.RLock()
	roomForEvict := hub.rooms[rid]
	hub.mu.RUnlock()
	hub.hardEvictSuspended(roomForEvict, aCID)

	broadcast := participantsInBroadcast(b)
	if broadcast == nil {
		t.Fatal("expected room_state broadcast to B after hard eviction")
	}
	if findParticipant(broadcast, aCID) != nil {
		t.Fatalf("expected A to be absent from broadcast after hard eviction, got %+v", broadcast)
	}

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	defer room.mu.Unlock()
	if room.participantByCID(aCID) != nil {
		t.Fatal("expected participant record to be removed after hard eviction")
	}
	if got := room.participantCount(); got != 1 {
		t.Fatalf("expected participantCount=1 after eviction (only B remains), got %d", got)
	}
}

// TestRejectedReconnectSocketCannotEndRoom verifies that a socket whose
// reconnect was rejected (ROOM_CAPACITY_UNSUPPORTED) cannot subsequently
// tear down the live room by sending `end_room` — even though its stale
// c.cid still matches room.HostCID. Authorization must come from the room's
// attached-transport index (room.byClient), not from the client's own
// fields which are deliberately left as-is after rejection to avoid a data
// race with other goroutines that read them.
func TestRejectedReconnectSocketCannotEndRoom(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	// Host joins with group capability (max=4) and locks the room at 4 with
	// a second capable participant.
	host := fakeClient(hub)
	hub.registerClient(host)
	hostCID := joinAndCaptureCID(t, hub, host, rid)

	peer := fakeClient(hub)
	hub.registerClient(peer)
	joinAndCaptureCID(t, hub, peer, rid)
	drainMessages(host)
	drainMessages(peer)

	// Host's transport drops; participant becomes suspended.
	hub.disconnectClient(host)
	drainMessages(peer)

	// Host tries to reconnect but this time the client can only handle 1:1
	// (capability < room.MaxParticipants=4) — reject. Per the rejection undo
	// path, the socket's c.cid/c.rid are deliberately left stale to avoid a
	// data race with other goroutines that read them without synchronization.
	rejected := fakeClient(hub)
	hub.registerClient(rejected)
	hub.handleMessage(rejected, joinPayloadWithOptions(rid, 2, 2, joinPayloadOptions{
		ReconnectCID: hostCID,
	}))
	// Confirm the rejection actually happened (precondition for this test).
	var sawReject bool
	for _, m := range drainMessages(rejected) {
		if m.Type == "error" {
			var p struct{ Code string `json:"code"` }
			if err := json.Unmarshal(m.Payload, &p); err == nil && p.Code == "ROOM_CAPACITY_UNSUPPORTED" {
				sawReject = true
			}
		}
	}
	if !sawReject {
		t.Fatal("precondition: reconnect should have been rejected with ROOM_CAPACITY_UNSUPPORTED")
	}

	// Simulate the rejected socket's stale c.cid/c.rid (what the undo path
	// preserves on purpose to avoid the data race).
	rejected.cid = hostCID
	rejected.rid = rid

	// The rejected socket tries to end the room.
	endMsg, _ := json.Marshal(Message{V: 1, Type: "end_room", RID: rid})
	hub.handleMessage(rejected, endMsg)

	// Room must still exist and the attached peer must still be a participant.
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	if room == nil {
		t.Fatal("rejected reconnect socket ended the live room — authorization bypass")
	}
	room.mu.Lock()
	defer room.mu.Unlock()
	if room.participantCount() == 0 {
		t.Fatal("live peer was removed by unauthorized end_room")
	}
	// The rejected socket should have received a NOT_HOST error.
	var sawNotHost bool
	for _, m := range drainMessages(rejected) {
		if m.Type == "error" {
			var p struct{ Code string `json:"code"` }
			if err := json.Unmarshal(m.Payload, &p); err == nil && p.Code == "NOT_HOST" {
				sawNotHost = true
			}
		}
	}
	if !sawNotHost {
		t.Fatal("expected NOT_HOST error to rejected socket's end_room attempt")
	}
}

// TestRejectedReconnectSocketCannotMintTurnCredentials verifies that a
// socket whose reconnect was rejected cannot keep pulling fresh TURN
// credentials via turn-refresh. Attachment must be verified via the room
// index, not just c.rid != "".
func TestRejectedReconnectSocketCannotMintTurnCredentials(t *testing.T) {
	t.Setenv("TURN_SECRET", "x")
	t.Setenv("TURN_TOKEN_SECRET", "x")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	host := fakeClient(hub)
	hub.registerClient(host)
	hostCID := joinAndCaptureCID(t, hub, host, rid)

	peer := fakeClient(hub)
	hub.registerClient(peer)
	joinAndCaptureCID(t, hub, peer, rid)
	drainMessages(host)
	drainMessages(peer)

	hub.disconnectClient(host)
	drainMessages(peer)

	rejected := fakeClient(hub)
	hub.registerClient(rejected)
	hub.handleMessage(rejected, joinPayloadWithOptions(rid, 2, 2, joinPayloadOptions{
		ReconnectCID: hostCID,
	}))
	drainMessages(rejected)
	rejected.cid = hostCID
	rejected.rid = rid

	refreshMsg, _ := json.Marshal(Message{V: 1, Type: "turn-refresh", RID: rid})
	hub.handleMessage(rejected, refreshMsg)

	var sawToken, sawNotInRoom bool
	for _, m := range drainMessages(rejected) {
		if m.Type == "turn-refreshed" {
			sawToken = true
		}
		if m.Type == "error" {
			var p struct{ Code string `json:"code"` }
			if err := json.Unmarshal(m.Payload, &p); err == nil && p.Code == "NOT_IN_ROOM" {
				sawNotInRoom = true
			}
		}
	}
	if sawToken {
		t.Fatal("rejected reconnect socket was issued TURN credentials despite no active attachment")
	}
	if !sawNotInRoom {
		t.Fatal("expected NOT_IN_ROOM error for turn-refresh from detached socket")
	}
}

// TestLateLeaveFromStaleSocketDoesNotRemoveReattachedParticipant verifies
// that a `leave` arriving from the OLD socket after a reconnect has already
// swapped the participant to a NEW client does not tear down the live
// participant. Without the `p.Client == c` guard in removeClientFromRoom,
// the stale leave would delete the freshly-reattached record and end the
// call unexpectedly.
func TestLateLeaveFromStaleSocketDoesNotRemoveReattachedParticipant(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	old := fakeClient(hub)
	hub.registerClient(old)
	aCID := joinAndCaptureCID(t, hub, old, rid)

	b := fakeClient(hub)
	hub.registerClient(b)
	joinAndCaptureCID(t, hub, b, rid)
	drainMessages(b)

	// New client reclaims aCID via reconnect. Old socket is still registered
	// (its transport hasn't been fully cleaned up yet).
	newA := fakeClient(hub)
	hub.registerClient(newA)
	hub.handleMessage(newA, joinPayloadWithOptions(rid, 4, 4, joinPayloadOptions{
		ReconnectCID: aCID,
	}))
	drainMessages(newA)
	drainMessages(b)

	// At this point the room participant for aCID is attached to newA.
	// Simulate a late `leave` arriving from the old socket (which still has
	// stale c.cid/c.rid pointing at this room).
	old.cid = aCID
	old.rid = rid
	leaveMsg, _ := json.Marshal(Message{V: 1, Type: "leave", RID: rid})
	hub.handleMessage(old, leaveMsg)

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	if room == nil {
		t.Fatal("room was deleted by stale leave — reattached participant was torn down")
	}
	room.mu.Lock()
	defer room.mu.Unlock()
	p := room.participantByCID(aCID)
	if p == nil {
		t.Fatal("reattached participant was removed by stale leave from old socket")
	}
	if p.Client != newA {
		t.Fatalf("expected new client attached to aCID, got %v", p.Client)
	}
}

// TestHostTransferPrefersActiveOverSuspended verifies that when the host
// departs, the new host is chosen from the ACTIVE participants — not a
// suspended one that might never reconnect, leaving live participants
// unable to exercise host privileges like end_room.
func TestHostTransferPrefersActiveOverSuspended(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	host := fakeClient(hub)
	hub.registerClient(host)
	hostCID := joinAndCaptureCID(t, hub, host, rid)

	suspended := fakeClient(hub)
	hub.registerClient(suspended)
	joinAndCaptureCID(t, hub, suspended, rid)

	active := fakeClient(hub)
	hub.registerClient(active)
	activeCID := joinAndCaptureCID(t, hub, active, rid)

	// Suspend one of the non-host participants; host role should go to the
	// active peer when the host leaves.
	hub.disconnectClient(suspended)
	drainMessages(host)
	drainMessages(active)

	// Host leaves.
	leaveMsg, _ := json.Marshal(Message{V: 1, Type: "leave", RID: rid})
	hub.handleMessage(host, leaveMsg)

	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	room.mu.Lock()
	defer room.mu.Unlock()
	if room.HostCID == hostCID {
		t.Fatalf("expected host to transfer from %q, but it stayed", hostCID)
	}
	if room.HostCID == "" {
		t.Fatal("expected new host to be assigned after host leaves a non-empty room")
	}
	if p := room.participantByCID(room.HostCID); p == nil || p.Client == nil {
		t.Fatalf("expected new host %q to be an active (attached) participant, got suspended", room.HostCID)
	}
	if room.HostCID != activeCID {
		t.Fatalf("expected host transfer to active peer %q, got %q", activeCID, room.HostCID)
	}
}

// TestRejectedReconnectRestoresSuspendedStateAndRearmsTimer verifies that
// when a reconnect reattaches a suspended participant and then fails the
// capacity check (ROOM_CAPACITY_UNSUPPORTED), the undo path leaves the
// participant in a suspended-and-reapeable state with a fresh hard-eviction
// timer — otherwise reattachClient would have stopped the original timer,
// wedging the slot forever.
func TestRejectedReconnectRestoresSuspendedStateAndRearmsTimer(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	// Lock the room at maxParticipants=4 by having two capable clients join.
	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	drainMessages(a)

	b := fakeClient(hub)
	hub.registerClient(b)
	bCID := joinAndCaptureCID(t, hub, b, rid)
	drainMessages(a)
	drainMessages(b)

	hub.disconnectClient(b)
	drainMessages(a)

	// Reconnect with a capability that is LOWER than the room's locked cap.
	// Simulates an edge case where the returning client can no longer honor
	// the room's group mode (e.g., feature regressed).
	b2 := fakeClient(hub)
	hub.registerClient(b2)
	hub.handleMessage(b2, joinPayloadWithOptions(rid, 2, 2, joinPayloadOptions{
		ReconnectCID: bCID,
	}))

	// Reconnect must be rejected with ROOM_CAPACITY_UNSUPPORTED.
	var sawCapError bool
	for _, msg := range drainMessages(b2) {
		if msg.Type != "error" {
			continue
		}
		var p struct {
			Code string `json:"code"`
		}
		if err := json.Unmarshal(msg.Payload, &p); err == nil && p.Code == "ROOM_CAPACITY_UNSUPPORTED" {
			sawCapError = true
		}
	}
	if !sawCapError {
		t.Fatal("expected ROOM_CAPACITY_UNSUPPORTED when reconnecting with insufficient capability")
	}

	// Server state: participant still suspended, slot preserved, and the
	// hard-eviction timer is re-armed so the slot will eventually clear.
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	if room == nil {
		t.Fatal("expected room to still exist after rejected reconnect")
	}
	room.mu.Lock()
	defer room.mu.Unlock()
	p := room.participantByCID(bCID)
	if p == nil {
		t.Fatal("expected participant record to still exist after rejected reconnect")
	}
	if p.Client != nil {
		t.Fatalf("expected reattach to be undone; got attached client %v", p.Client)
	}
	if p.hardEvictionTimer == nil {
		t.Fatal("expected hard-eviction timer to be re-armed after undo — otherwise the slot wedges forever")
	}
}

// TestSuspendedParticipantHoldsCapacitySlot verifies that a suspended
// participant still counts toward room occupancy — a third client cannot
// squeeze in while a 1:1 room has a suspended participant. This is required
// because the suspended client holds the right to reclaim its slot.
func TestSuspendedParticipantHoldsCapacitySlot(t *testing.T) {
	rid := mustTestRoomID(t)
	hub := newHub(4)

	// Build a 1:1 room by having both clients advertise capability=2.
	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 2, 2))
	drainMessages(a)

	b := fakeClient(hub)
	hub.registerClient(b)
	hub.handleMessage(b, joinPayload(rid, 2, 2))
	drainMessages(a)
	drainMessages(b)

	hub.disconnectClient(b)
	drainMessages(a)

	// Third client cannot squeeze in — B still holds the slot.
	c := fakeClient(hub)
	hub.registerClient(c)
	hub.handleMessage(c, joinPayload(rid, 2, 2))

	var sawFull bool
	for _, msg := range drainMessages(c) {
		if msg.Type != "error" {
			continue
		}
		var p struct {
			Code string `json:"code"`
		}
		if err := json.Unmarshal(msg.Payload, &p); err == nil && p.Code == "ROOM_FULL" {
			sawFull = true
		}
	}
	if !sawFull {
		t.Fatal("expected ROOM_FULL when joining a room whose spare slot is held by a suspended participant")
	}
}
