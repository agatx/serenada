package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHandleFeedback_ValidRequest(t *testing.T) {
	handler := handleFeedback(nil) // nil telegram — just logs

	body, _ := json.Marshal(FeedbackRequest{
		Message:  "The camera freezes after 5 minutes",
		Platform: "web",
		Locale:   "en",
	})

	req := httptest.NewRequest(http.MethodPost, "/api/feedback", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	var resp map[string]bool
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if !resp["ok"] {
		t.Fatalf("expected ok=true")
	}
}

func TestHandleFeedback_MissingMessage(t *testing.T) {
	handler := handleFeedback(nil)

	body, _ := json.Marshal(FeedbackRequest{
		Message:  "",
		Platform: "android",
	})

	req := httptest.NewRequest(http.MethodPost, "/api/feedback", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}

func TestHandleFeedback_MessageTooLong(t *testing.T) {
	handler := handleFeedback(nil)

	body, _ := json.Marshal(FeedbackRequest{
		Message:  strings.Repeat("a", 2001),
		Platform: "ios",
	})

	req := httptest.NewRequest(http.MethodPost, "/api/feedback", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}

func TestHandleFeedback_WrongMethod(t *testing.T) {
	handler := handleFeedback(nil)

	req := httptest.NewRequest(http.MethodGet, "/api/feedback", nil)
	w := httptest.NewRecorder()

	handler(w, req)

	if w.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", w.Code)
	}
}

func TestHandleFeedback_InvalidJSON(t *testing.T) {
	handler := handleFeedback(nil)

	req := httptest.NewRequest(http.MethodPost, "/api/feedback", bytes.NewReader([]byte("not json")))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}
