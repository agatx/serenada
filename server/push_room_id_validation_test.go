package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func makeTestHubWithParticipant(roomID string, cid string) *Hub {
	hub := newHub()
	hub.rooms[roomID] = &Room{
		RID: roomID,
		Participants: map[*Client]string{
			&Client{}: cid,
		},
	}
	return hub
}

func mustGenerateRoomID(t *testing.T) string {
	t.Helper()
	t.Setenv("ROOM_ID_SECRET", "test-room-id-secret")
	roomID, err := generateRoomID()
	if err != nil {
		t.Fatalf("failed to generate room id: %v", err)
	}
	return roomID
}

func TestHandlePushSubscribeRejectsInvalidRoomID(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/api/push/subscribe?roomId=bad", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()

	handlePushSubscribe(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected %d, got %d", http.StatusBadRequest, rec.Code)
	}
}

func TestHandlePushRecipientsRejectsInvalidRoomID(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/push/recipients?roomId=bad", nil)
	rec := httptest.NewRecorder()

	handlePushRecipients(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected %d, got %d", http.StatusBadRequest, rec.Code)
	}
}

func TestHandlePushInviteRejectsInvalidRoomID(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/api/push/invite?roomId=bad", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()

	handlePushInvite(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected %d, got %d", http.StatusBadRequest, rec.Code)
	}
}

func TestHandlePushNotifyRejectsInvalidRoomID(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/api/push/notify?roomId=bad", strings.NewReader(`{"cid":"cid-1"}`))
	rec := httptest.NewRecorder()

	handlePushNotify(newHub())(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected %d, got %d", http.StatusBadRequest, rec.Code)
	}
}

func TestHandlePushNotifyRejectsMissingCID(t *testing.T) {
	roomID := mustGenerateRoomID(t)
	req := httptest.NewRequest(http.MethodPost, "/api/push/notify?roomId="+roomID, strings.NewReader(`{}`))
	rec := httptest.NewRecorder()

	handlePushNotify(newHub())(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected %d, got %d", http.StatusBadRequest, rec.Code)
	}
}

func TestHandlePushNotifyRejectsUnauthorizedCID(t *testing.T) {
	roomID := mustGenerateRoomID(t)
	req := httptest.NewRequest(http.MethodPost, "/api/push/notify?roomId="+roomID, strings.NewReader(`{"cid":"cid-2"}`))
	rec := httptest.NewRecorder()

	handlePushNotify(makeTestHubWithParticipant(roomID, "cid-1"))(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected %d, got %d", http.StatusForbidden, rec.Code)
	}
}

func TestHandlePushNotifyReturnsServiceUnavailableWhenPushServiceMissing(t *testing.T) {
	roomID := mustGenerateRoomID(t)
	oldPushService := pushService
	pushService = nil
	t.Cleanup(func() {
		pushService = oldPushService
	})

	req := httptest.NewRequest(http.MethodPost, "/api/push/notify?roomId="+roomID, strings.NewReader(`{"cid":"cid-1"}`))
	rec := httptest.NewRecorder()

	handlePushNotify(makeTestHubWithParticipant(roomID, "cid-1"))(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected %d, got %d", http.StatusServiceUnavailable, rec.Code)
	}
}

func TestHandlePushRecipientsReturnsServiceUnavailableWhenRoomIDSecretMissing(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "")

	req := httptest.NewRequest(http.MethodGet, "/api/push/recipients?roomId="+strings.Repeat("A", 27), nil)
	rec := httptest.NewRecorder()

	handlePushRecipients(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected %d, got %d", http.StatusServiceUnavailable, rec.Code)
	}
}

func TestHandlePushInviteReturnsServiceUnavailableWhenRoomIDSecretMissing(t *testing.T) {
	t.Setenv("ROOM_ID_SECRET", "")

	req := httptest.NewRequest(http.MethodPost, "/api/push/invite?roomId="+strings.Repeat("A", 27), strings.NewReader(`{}`))
	rec := httptest.NewRecorder()

	handlePushInvite(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected %d, got %d", http.StatusServiceUnavailable, rec.Code)
	}
}

func TestPushServiceSubscribeRejectsInvalidRoomIDBeforeDBAccess(t *testing.T) {
	service := &PushService{}

	err := service.Subscribe("bad", PushSubscriptionRequest{
		Transport: pushTransportFCM,
		Endpoint:  "token",
	})
	if err == nil {
		t.Fatalf("expected error")
	}
	if !strings.Contains(strings.ToLower(err.Error()), "room id") {
		t.Fatalf("expected room id error, got %v", err)
	}
}

func TestPushServiceUnsubscribeRejectsInvalidRoomIDBeforeDBAccess(t *testing.T) {
	service := &PushService{}

	err := service.Unsubscribe("bad", "endpoint")
	if err == nil {
		t.Fatalf("expected error")
	}
	if !strings.Contains(strings.ToLower(err.Error()), "room id") {
		t.Fatalf("expected room id error, got %v", err)
	}
}

func TestPushServiceSendNotificationToRoomReturnsOnInvalidRoomID(t *testing.T) {
	service := &PushService{}

	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("unexpected panic: %v", r)
		}
	}()

	service.SendNotificationToRoom("bad", "", "")
}
