package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestSSESessionTakeoverProtection(t *testing.T) {
	// Setup env
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	t.Setenv("TURN_SECRET", "test-reconnect-secret")

	rid, err := generateRoomID()
	if err != nil {
		t.Fatalf("failed to generate room ID: %v", err)
	}

	hub := newHub(4)
	sid := "S-test-session-takeover"

	// 1. First client connects via GET /sse?sid=S-test-session-takeover
	req1 := httptest.NewRequest(http.MethodGet, "/sse?sid="+sid, nil)
	w1 := httptest.NewRecorder()
	go serveSSE(hub, w1, req1)

	// Wait a brief moment for registration
	time.Sleep(10 * time.Millisecond)

	// Ensure the client exists and is registered in clientsBySID
	client1 := hub.getClientBySID(sid)
	if client1 == nil {
		t.Fatal("expected client1 to be registered in Hub")
	}

	// First client joins a room
	hub.handleMessage(client1, joinPayload(rid, 4, 4))
	time.Sleep(10 * time.Millisecond)

	// Verify client is active in the room
	hub.mu.RLock()
	room := hub.rooms[rid]
	hub.mu.RUnlock()
	if room == nil {
		t.Fatal("expected room to be created")
	}

	room.mu.Lock()
	cid := room.cidForClient(client1)
	room.mu.Unlock()
	if cid == "" {
		t.Fatal("expected client1 to have a CID in room")
	}

	// Get a valid reconnect token for client1 to use later
	validToken := issueReconnectToken(cid, rid)
	if validToken == "" {
		t.Fatal("expected non-empty reconnect token")
	}

	// 2. Simulate attacker connecting via GET /sse?sid=S-test-session-takeover
	req2 := httptest.NewRequest(http.MethodGet, "/sse?sid="+sid, nil)
	w2 := httptest.NewRecorder()
	go serveSSE(hub, w2, req2)

	time.Sleep(10 * time.Millisecond)

	// Verify that the attacker's client is NOT registered in clientsBySID (which still holds client1)
	activeClient := hub.getClientBySID(sid)
	if activeClient != client1 {
		t.Fatal("expected active client in clientsBySID to remain client1")
	}

	// Verify the attacker's client is registered in pendingTakeovers
	pendingClient := hub.getPendingTakeover(sid)
	if pendingClient == nil {
		t.Fatal("expected new connection to be placed in pendingTakeovers")
	}

	// 3. Attacker attempts POST /sse?sid=S-test-session-takeover with a non-join message
	msgNonJoin := Message{
		V:    1,
		Type: "offer",
		RID:  rid,
	}
	bodyNonJoin, _ := json.Marshal(msgNonJoin)
	reqPost1 := httptest.NewRequest(http.MethodPost, "/sse?sid="+sid, bytes.NewReader(bodyNonJoin))
	wPost1 := httptest.NewRecorder()
	handleSSEPost(hub, wPost1, reqPost1)

	if wPost1.Code != http.StatusUnauthorized {
		t.Fatalf("expected HTTP 401 Unauthorized for non-join message on pending connection, got %d", wPost1.Code)
	}

	// Attacker attempts POST /sse?sid=S-test-session-takeover with a join message but invalid token
	msgBadJoin := Message{
		V:    1,
		Type: "join",
		RID:  rid,
		Payload: []byte(`{"reconnectCid": "` + cid + `", "reconnectToken": "bad-token"}`),
	}
	bodyBadJoin, _ := json.Marshal(msgBadJoin)
	reqPost2 := httptest.NewRequest(http.MethodPost, "/sse?sid="+sid, bytes.NewReader(bodyBadJoin))
	wPost2 := httptest.NewRecorder()
	handleSSEPost(hub, wPost2, reqPost2)

	if wPost2.Code != http.StatusUnauthorized {
		t.Fatalf("expected HTTP 401 Unauthorized for bad reconnect token on pending connection, got %d", wPost2.Code)
	}

	// Verify the takeover is still pending and active client remains client1
	if hub.getClientBySID(sid) != client1 {
		t.Fatal("expected active client to remain client1 after failed takeover attempts")
	}
	if hub.getPendingTakeover(sid) != pendingClient {
		t.Fatal("expected pending takeover to remain active after failed attempts")
	}

	// 4. Valid client reconnects with valid reconnect token and CID
	msgGoodJoin := Message{
		V:    1,
		Type: "join",
		RID:  rid,
		Payload: []byte(`{"reconnectCid": "` + cid + `", "reconnectToken": "` + validToken + `"}`),
	}
	bodyGoodJoin, _ := json.Marshal(msgGoodJoin)
	reqPost3 := httptest.NewRequest(http.MethodPost, "/sse?sid="+sid, bytes.NewReader(bodyGoodJoin))
	wPost3 := httptest.NewRecorder()
	handleSSEPost(hub, wPost3, reqPost3)

	if wPost3.Code != http.StatusNoContent {
		t.Fatalf("expected HTTP 204 NoContent for authorized takeover, got %d", wPost3.Code)
	}

	// Verify takeover completed: active client is now the new client (pendingClient)
	if hub.getClientBySID(sid) != pendingClient {
		t.Fatal("expected active client to be promoted to pendingClient after successful takeover")
	}
	if hub.getPendingTakeover(sid) != nil {
		t.Fatal("expected pending takeover to be removed after successful takeover")
	}
}
