package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestParseAllowedOriginsEmpty(t *testing.T) {
	origins := parseAllowedOrigins("")
	if len(origins) != 0 {
		t.Fatalf("expected empty map, got %d entries", len(origins))
	}
}

func TestParseAllowedOriginsSingle(t *testing.T) {
	origins := parseAllowedOrigins("https://example.com")
	if !origins["https://example.com"] {
		t.Fatalf("expected origin to be present")
	}
	if len(origins) != 1 {
		t.Fatalf("expected 1 entry, got %d", len(origins))
	}
}

func TestParseAllowedOriginsMultiple(t *testing.T) {
	origins := parseAllowedOrigins("https://a.com, https://b.com,https://c.com")
	if len(origins) != 3 {
		t.Fatalf("expected 3 entries, got %d", len(origins))
	}
	for _, origin := range []string{"https://a.com", "https://b.com", "https://c.com"} {
		if !origins[origin] {
			t.Fatalf("expected %q to be present", origin)
		}
	}
}

func TestParseAllowedOriginsWhitespace(t *testing.T) {
	origins := parseAllowedOrigins("  https://a.com  ,  ")
	if len(origins) != 1 {
		t.Fatalf("expected 1 entry, got %d", len(origins))
	}
	if !origins["https://a.com"] {
		t.Fatalf("expected trimmed origin to be present")
	}
}

func TestRefreshAllowedOriginsFromEnv(t *testing.T) {
	original := allowedOrigins
	defer func() { allowedOrigins = original }()

	t.Setenv("ALLOWED_ORIGINS", "https://allowed.example,https://other.example")
	refreshAllowedOriginsFromEnv()

	req := httptest.NewRequest(http.MethodGet, "http://serenada.app/api/room-id", nil)
	req.Host = "serenada.app"
	req.Header.Set("Origin", "https://allowed.example")
	if !isOriginAllowed(req) {
		t.Fatalf("expected configured origin to be allowed")
	}

	req = httptest.NewRequest(http.MethodGet, "http://serenada.app/api/room-id", nil)
	req.Host = "serenada.app"
	req.Header.Set("Origin", "https://denied.example")
	if isOriginAllowed(req) {
		t.Fatalf("expected non-configured origin to be denied")
	}
}

func TestIsOriginAllowedEmptyOriginHeader(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	if !isOriginAllowed(req) {
		t.Fatalf("empty origin should be allowed")
	}
}

func TestIsOriginAllowedMatchesConfigured(t *testing.T) {
	original := allowedOrigins
	allowedOrigins = parseAllowedOrigins("https://serenada.app")
	defer func() { allowedOrigins = original }()

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Origin", "https://serenada.app")
	if !isOriginAllowed(req) {
		t.Fatalf("configured origin should be allowed")
	}
}

func TestIsOriginAllowedRejectsUnknown(t *testing.T) {
	original := allowedOrigins
	allowedOrigins = parseAllowedOrigins("https://serenada.app")
	defer func() { allowedOrigins = original }()

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Origin", "https://evil.com")
	if isOriginAllowed(req) {
		t.Fatalf("unknown origin should be rejected")
	}
}

func TestIsOriginAllowedLocalhostBypass(t *testing.T) {
	original := allowedOrigins
	defer func() { allowedOrigins = original }()
	allowedOrigins = parseAllowedOrigins("")

	for _, origin := range []string{
		"http://localhost",
		"http://localhost:3000",
		"http://localhost:8080",
	} {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.Header.Set("Origin", origin)
		if !isOriginAllowed(req) {
			t.Fatalf("localhost origin %q should be allowed", origin)
		}
	}
}

func TestIsOriginAllowedHostFallback(t *testing.T) {
	original := allowedOrigins
	defer func() { allowedOrigins = original }()
	allowedOrigins = parseAllowedOrigins("")

	req := httptest.NewRequest(http.MethodGet, "http://serenada.app/api/room-id", nil)
	req.Host = "serenada.app"
	req.Header.Set("Origin", "https://serenada.app")
	if !isOriginAllowed(req) {
		t.Fatalf("expected same-host origin to be allowed")
	}
}

func TestIsOriginAllowedHostFallbackMismatch(t *testing.T) {
	original := allowedOrigins
	defer func() { allowedOrigins = original }()
	allowedOrigins = parseAllowedOrigins("")

	req := httptest.NewRequest(http.MethodGet, "http://serenada.app/api/room-id", nil)
	req.Host = "serenada.app"
	req.Header.Set("Origin", "https://other.example")
	if isOriginAllowed(req) {
		t.Fatalf("origin not matching Host should be rejected")
	}
}

func TestIsOriginAllowedLocalhostFallback(t *testing.T) {
	original := allowedOrigins
	defer func() { allowedOrigins = original }()
	allowedOrigins = parseAllowedOrigins("")

	req := httptest.NewRequest(http.MethodGet, "http://serenada.app/api/room-id", nil)
	req.Host = "serenada.app"
	req.Header.Set("Origin", "http://localhost:5173")
	if !isOriginAllowed(req) {
		t.Fatalf("expected localhost origin to be allowed")
	}
}

func TestIsOriginAllowedEmptyHostRejects(t *testing.T) {
	original := allowedOrigins
	defer func() { allowedOrigins = original }()
	allowedOrigins = parseAllowedOrigins("")

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Host = ""
	req.Header.Set("Origin", "https://something.com")
	if isOriginAllowed(req) {
		t.Fatalf("non-localhost origin with empty host should be rejected")
	}
}
