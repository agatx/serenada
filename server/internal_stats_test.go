package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestInternalStatsDisabledReturnsNotFound(t *testing.T) {
	t.Setenv("ENABLE_INTERNAL_STATS", "0")
	t.Setenv("INTERNAL_STATS_TOKEN", "test-token")

	handler := handleInternalStats(newHub())
	req := httptest.NewRequest(http.MethodGet, "/api/internal/stats", nil)
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected %d, got %d", http.StatusNotFound, rec.Code)
	}
}

func TestInternalStatsEnabledRequiresConfiguredToken(t *testing.T) {
	t.Setenv("ENABLE_INTERNAL_STATS", "1")
	t.Setenv("INTERNAL_STATS_TOKEN", "")

	handler := handleInternalStats(newHub())
	req := httptest.NewRequest(http.MethodGet, "/api/internal/stats", nil)
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected %d, got %d", http.StatusServiceUnavailable, rec.Code)
	}
}

func TestInternalStatsRejectsMissingHeaderToken(t *testing.T) {
	t.Setenv("ENABLE_INTERNAL_STATS", "1")
	t.Setenv("INTERNAL_STATS_TOKEN", "test-token")

	handler := handleInternalStats(newHub())
	req := httptest.NewRequest(http.MethodGet, "/api/internal/stats", nil)
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected %d, got %d", http.StatusUnauthorized, rec.Code)
	}
}

func TestInternalStatsSuccessWithToken(t *testing.T) {
	t.Setenv("ENABLE_INTERNAL_STATS", "1")
	t.Setenv("INTERNAL_STATS_TOKEN", "test-token")

	handler := handleInternalStats(newHub())
	req := httptest.NewRequest(http.MethodGet, "/api/internal/stats", nil)
	req.Header.Set("X-Internal-Token", "test-token")
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected %d, got %d", http.StatusOK, rec.Code)
	}
	if contentType := rec.Header().Get("Content-Type"); contentType != "application/json" {
		t.Fatalf("expected application/json content type, got %q", contentType)
	}
}
