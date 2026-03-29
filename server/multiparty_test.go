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

// joinPayload builds a raw JSON join message with optional capabilities.
func joinPayload(rid string, capMax int, createMax int) []byte {
	type caps struct {
		MaxParticipants int `json:"maxParticipants,omitempty"`
	}
	payload := struct {
		Capabilities          caps `json:"capabilities,omitempty"`
		CreateMaxParticipants int  `json:"createMaxParticipants,omitempty"`
	}{
		Capabilities:          caps{MaxParticipants: capMax},
		CreateMaxParticipants: createMax,
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
	count := len(room.Participants)
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
