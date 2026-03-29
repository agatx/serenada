package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
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

func TestIPLimiterPrunesIdleEntries(t *testing.T) {
	base := time.Date(2026, time.March, 25, 12, 0, 0, 0, time.UTC)
	limiter := NewIPLimiter(1, 1)
	limiter.now = func() time.Time { return base }
	limiter.lastPrunedAt = base.Add(-11 * time.Minute)

	stale := NewSimpleTokenBucket(1, 1)
	stale.lastSeen = base.Add(-31 * time.Minute)
	fresh := NewSimpleTokenBucket(1, 1)
	fresh.lastSeen = base.Add(-29 * time.Minute)

	limiter.ips["stale"] = stale
	limiter.ips["fresh"] = fresh

	got := limiter.GetLimiter("fresh")
	if got != fresh {
		t.Fatalf("expected existing fresh limiter to be returned")
	}
	if _, ok := limiter.ips["stale"]; ok {
		t.Fatalf("expected stale limiter entry to be pruned")
	}
	if _, ok := limiter.ips["fresh"]; !ok {
		t.Fatalf("expected fresh limiter entry to remain")
	}
	if !fresh.lastSeen.Equal(base) {
		t.Fatalf("expected fresh limiter lastSeen to refresh to %v, got %v", base, fresh.lastSeen)
	}
}
