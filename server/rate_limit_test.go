package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestParseRateLimitBypassAndContains(t *testing.T) {
	list := parseRateLimitBypass("127.0.0.1, 10.0.0.0/8, ::1")

	if !list.contains("127.0.0.1") {
		t.Fatalf("expected exact IP match")
	}
	if !list.contains("10.2.3.4") {
		t.Fatalf("expected CIDR match")
	}
	if !list.contains("[::1]:1234") {
		t.Fatalf("expected IPv6 match")
	}
	if list.contains("192.168.1.2") {
		t.Fatalf("unexpected match for non-whitelisted IP")
	}
}

func TestRateLimitMiddlewareBypass(t *testing.T) {
	original := rateLimitBypass
	rateLimitBypass = parseRateLimitBypass("127.0.0.1")
	defer func() { rateLimitBypass = original }()

	limiter := NewIPLimiter(0, 0)
	hits := 0
	handler := rateLimitMiddleware(limiter, func(w http.ResponseWriter, r *http.Request) {
		hits++
		w.WriteHeader(http.StatusNoContent)
	})

	for i := 0; i < 3; i++ {
		req := httptest.NewRequest(http.MethodGet, "http://example.com/ws", nil)
		req.RemoteAddr = "127.0.0.1:12345"
		w := httptest.NewRecorder()
		handler(w, req)
		if w.Code != http.StatusNoContent {
			t.Fatalf("unexpected status for bypassed IP: %d", w.Code)
		}
	}

	if hits != 3 {
		t.Fatalf("expected handler hits=3, got %d", hits)
	}
}
