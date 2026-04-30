package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func postLeave(t *testing.T, hub *Hub, body interface{}) *httptest.ResponseRecorder {
	t.Helper()
	raw, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("postLeave: marshal body: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, "/api/leave", bytes.NewReader(raw))
	w := httptest.NewRecorder()
	handleLeave(hub).ServeHTTP(w, req)
	return w
}

func TestApiLeaveEvictsParticipantWithValidToken(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-leave-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)

	w := postLeave(t, hub, map[string]string{
		"rid":            rid,
		"cid":            first.CID,
		"reconnectToken": first.ReconnectToken,
	})
	if w.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d body=%q", w.Code, w.Body.String())
	}

	hub.mu.RLock()
	_, exists := hub.rooms[rid]
	hub.mu.RUnlock()
	if exists {
		t.Fatal("expected room to be GC'd after sole participant evicted")
	}
}

func TestApiLeaveRejectsMissingFields(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-leave-secret")
	hub := newHub(4)

	for _, body := range []map[string]string{
		{"cid": "C-x", "reconnectToken": "t"},
		{"rid": "irrelevant", "reconnectToken": "t"},
		{"rid": "irrelevant", "cid": "C-x"},
		{},
	} {
		w := postLeave(t, hub, body)
		if w.Code != http.StatusBadRequest {
			t.Fatalf("expected 400 for %+v, got %d", body, w.Code)
		}
	}
}

func TestApiLeaveRejectsInvalidToken(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-leave-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)

	w := postLeave(t, hub, map[string]string{
		"rid":            rid,
		"cid":            first.CID,
		"reconnectToken": "bogus.0",
	})
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for bogus token, got %d", w.Code)
	}

	hub.mu.RLock()
	_, exists := hub.rooms[rid]
	hub.mu.RUnlock()
	if !exists {
		t.Fatal("room should still exist after rejected leave")
	}
}

func TestApiLeaveRejectsUnsignedTokenWhenSecretMissing(t *testing.T) {
	t.Setenv("TURN_SECRET", "")
	t.Setenv("TURN_TOKEN_SECRET", "")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	w := postLeave(t, hub, map[string]string{
		"rid":            rid,
		"cid":            "C-abc",
		"reconnectToken": "unsigned-dev-token",
	})
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 when reconnect token secret is missing, got %d", w.Code)
	}
}

func TestApiLeaveRejectsExpiredToken(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-leave-secret")
	rid := mustTestRoomID(t)
	hub := newHub(4)

	a := fakeClient(hub)
	hub.registerClient(a)
	hub.handleMessage(a, joinPayload(rid, 4, 4))
	first := captureJoined(t, a)
	expired := issueReconnectTokenWithExpiry(first.CID, rid, time.Now().Add(-5*time.Minute))

	w := postLeave(t, hub, map[string]string{
		"rid":            rid,
		"cid":            first.CID,
		"reconnectToken": expired,
	})
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for expired token, got %d", w.Code)
	}
}

func TestApiLeaveIsIdempotent(t *testing.T) {
	t.Setenv("TURN_SECRET", "test-leave-secret")
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

	first := postLeave(t, hub, map[string]string{
		"rid":            rid,
		"cid":            aJoined.CID,
		"reconnectToken": aJoined.ReconnectToken,
	})
	second := postLeave(t, hub, map[string]string{
		"rid":            rid,
		"cid":            aJoined.CID,
		"reconnectToken": aJoined.ReconnectToken,
	})

	if first.Code != http.StatusNoContent {
		t.Fatalf("expected 204 on first call, got %d", first.Code)
	}
	if second.Code != http.StatusNoContent {
		t.Fatalf("expected 204 on second call (idempotent), got %d", second.Code)
	}
}

func TestApiLeaveRejectsNonPost(t *testing.T) {
	hub := newHub(4)
	req := httptest.NewRequest(http.MethodGet, "/api/leave", nil)
	w := httptest.NewRecorder()
	handleLeave(hub).ServeHTTP(w, req)
	if w.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405 for GET, got %d", w.Code)
	}
}
