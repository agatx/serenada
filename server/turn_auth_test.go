package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestIssueTurnTokenRoundTrip(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")

	token, expiresAt, err := issueTurnToken(10*time.Minute, turnTokenKindCall)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}
	if token == "" {
		t.Fatalf("expected non-empty token")
	}
	if expiresAt.IsZero() {
		t.Fatalf("expected non-zero expiry")
	}

	claims, ok := parseTurnToken(token)
	if !ok {
		t.Fatalf("parseTurnToken failed on valid token")
	}
	if claims.V != turnTokenVersion {
		t.Fatalf("expected version %d, got %d", turnTokenVersion, claims.V)
	}
	if claims.Kind != turnTokenKindCall {
		t.Fatalf("expected kind %q, got %q", turnTokenKindCall, claims.Kind)
	}
}

func TestParseTurnTokenMalformedNoDot(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")

	_, ok := parseTurnToken("nodothere")
	if ok {
		t.Fatalf("expected parse to fail for token without dot separator")
	}
}

func TestParseTurnTokenMalformedBadBase64(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")

	_, ok := parseTurnToken("!!!invalid!!!.!!!base64!!!")
	if ok {
		t.Fatalf("expected parse to fail for invalid base64")
	}
}

func TestParseTurnTokenTamperedSignature(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")

	token, _, err := issueTurnToken(10*time.Minute, turnTokenKindCall)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}

	parts := strings.Split(token, ".")
	tampered := parts[0] + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
	_, ok := parseTurnToken(tampered)
	if ok {
		t.Fatalf("expected parse to fail for tampered signature")
	}
}

func TestValidateTurnTokenCallKind(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")

	token, _, err := issueTurnToken(10*time.Minute, turnTokenKindCall)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}
	if !validateTurnToken(token, turnTokenKindCall) {
		t.Fatalf("expected valid call token to pass validation")
	}
}

func TestValidateTurnTokenDiagnosticKind(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")

	token, _, err := issueTurnToken(5*time.Second, turnTokenKindDiagnostic)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}
	if !validateTurnToken(token, turnTokenKindDiagnostic) {
		t.Fatalf("expected valid diagnostic token to pass validation")
	}
}

func TestValidateTurnTokenWrongKind(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")

	token, _, err := issueTurnToken(10*time.Minute, turnTokenKindCall)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}
	if validateTurnToken(token, turnTokenKindDiagnostic) {
		t.Fatalf("expected call token to fail validation as diagnostic")
	}
}

func TestValidateTurnTokenMissingSecret(t *testing.T) {
	_, _, err := issueTurnToken(10*time.Minute, turnTokenKindCall)
	if err == nil {
		t.Fatalf("expected error when secret is missing")
	}
}

func TestHandleTurnCredentialsMissingToken(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")

	handler := handleTurnCredentials()
	req := httptest.NewRequest(http.MethodGet, "/api/turn-credentials", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w.Code)
	}
}

func TestHandleTurnCredentialsInvalidToken(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")

	handler := handleTurnCredentials()
	req := httptest.NewRequest(http.MethodGet, "/api/turn-credentials?token=bogus", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w.Code)
	}
}

func TestHandleTurnCredentialsValidCallToken(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")
	t.Setenv("TURN_SECRET", "coturn-secret")
	t.Setenv("STUN_HOST", "stun.example.com")

	token, _, err := issueTurnToken(10*time.Minute, turnTokenKindCall)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}

	handler := handleTurnCredentials()
	req := httptest.NewRequest(http.MethodGet, "/api/turn-credentials?token="+token, nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	var config TurnConfig
	if err := json.NewDecoder(w.Body).Decode(&config); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if config.Username == "" || config.Password == "" {
		t.Fatalf("expected non-empty credentials")
	}
	if config.TTL != 15*60 {
		t.Fatalf("expected TTL=900 for call token, got %d", config.TTL)
	}
	if len(config.URIs) == 0 {
		t.Fatalf("expected non-empty URIs")
	}
}

func TestHandleTurnCredentialsDiagnosticTokenShortTTL(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")
	t.Setenv("TURN_SECRET", "coturn-secret")
	t.Setenv("STUN_HOST", "stun.example.com")

	token, _, err := issueTurnToken(30*time.Second, turnTokenKindDiagnostic)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}

	handler := handleTurnCredentials()
	req := httptest.NewRequest(http.MethodGet, "/api/turn-credentials?token="+token, nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	var config TurnConfig
	if err := json.NewDecoder(w.Body).Decode(&config); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if config.TTL != 5 {
		t.Fatalf("expected TTL=5 for diagnostic token, got %d", config.TTL)
	}
}

func TestHandleTurnCredentialsWrongMethod(t *testing.T) {
	handler := handleTurnCredentials()
	req := httptest.NewRequest(http.MethodPost, "/api/turn-credentials", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", w.Code)
	}
}

func TestHandleTurnCredentialsMissingSTUN(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")
	t.Setenv("TURN_SECRET", "coturn-secret")
	// STUN_HOST not set

	token, _, err := issueTurnToken(10*time.Minute, turnTokenKindCall)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}

	handler := handleTurnCredentials()
	req := httptest.NewRequest(http.MethodGet, "/api/turn-credentials?token="+token, nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d", w.Code)
	}
}

func TestHandleDiagnosticTokenSuccess(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")

	handler := handleDiagnosticToken()
	req := httptest.NewRequest(http.MethodPost, "/api/diagnostic-token", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	var resp map[string]interface{}
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if resp["token"] == nil || resp["token"] == "" {
		t.Fatalf("expected non-empty token in response")
	}
	if resp["expires"] == nil {
		t.Fatalf("expected expires in response")
	}
}

func TestHandleDiagnosticTokenWrongMethod(t *testing.T) {
	handler := handleDiagnosticToken()
	req := httptest.NewRequest(http.MethodPut, "/api/diagnostic-token", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", w.Code)
	}
}

func TestHandleDiagnosticTokenMissingSecret(t *testing.T) {
	handler := handleDiagnosticToken()
	req := httptest.NewRequest(http.MethodPost, "/api/diagnostic-token", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d", w.Code)
	}
}

// mockCloudflareTURN sets up a mock Cloudflare TURN API server and overrides
// cfHTTPClient and cfTURNBaseURL so fetchCloudflareCredentials hits the mock.
// Cleanup is registered via t.Cleanup automatically.
func mockCloudflareTURN(t *testing.T, handler http.Handler) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(handler)

	origClient := cfHTTPClient
	origURL := cfTURNBaseURL
	cfHTTPClient = srv.Client()
	cfTURNBaseURL = srv.URL
	t.Cleanup(func() {
		cfHTTPClient = origClient
		cfTURNBaseURL = origURL
		srv.Close()
	})
	return srv
}

func TestHandleTurnCredentialsCloudflareHappyPath(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")
	t.Setenv("CF_TURN_KEY_ID", "test-key-id")
	t.Setenv("CF_TURN_API_TOKEN", "test-api-token")

	mockCloudflareTURN(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if !strings.Contains(r.URL.Path, "test-key-id") {
			t.Errorf("expected key ID in path, got %s", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer test-api-token" {
			t.Errorf("bad auth header: %s", r.Header.Get("Authorization"))
		}
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478","turn:turn.cloudflare.com:3478?transport=udp","turn:turn.cloudflare.com:3478?transport=tcp","turns:turn.cloudflare.com:5349?transport=tcp"],"username":"cf-user","credential":"cf-pass"}]}`)
	}))

	token, _, err := issueTurnToken(10*time.Minute, turnTokenKindCall)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}

	handler := handleTurnCredentials()
	req := httptest.NewRequest(http.MethodGet, "/api/turn-credentials?token="+token, nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	var result TurnConfig
	if err := json.NewDecoder(w.Body).Decode(&result); err != nil {
		t.Fatalf("failed to decode: %v", err)
	}
	if result.Username != "cf-user" {
		t.Fatalf("expected Cloudflare username, got %s", result.Username)
	}
	if result.Password != "cf-pass" {
		t.Fatalf("expected Cloudflare credential, got %s", result.Password)
	}
	if result.TTL != 900 {
		t.Fatalf("expected TTL=900, got %d", result.TTL)
	}
	if len(result.URIs) != 4 {
		t.Fatalf("expected 4 Cloudflare URIs, got %d", len(result.URIs))
	}
}

func TestHandleTurnCredentialsCloudflareFallbackToCoturn(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")
	t.Setenv("TURN_SECRET", "coturn-secret")
	t.Setenv("STUN_HOST", "stun.example.com")
	t.Setenv("CF_TURN_KEY_ID", "test-key-id")
	t.Setenv("CF_TURN_API_TOKEN", "test-api-token")

	mockCloudflareTURN(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprint(w, `{"error":"service unavailable"}`)
	}))

	token, _, err := issueTurnToken(10*time.Minute, turnTokenKindCall)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}

	handler := handleTurnCredentials()
	req := httptest.NewRequest(http.MethodGet, "/api/turn-credentials?token="+token, nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	var result TurnConfig
	if err := json.NewDecoder(w.Body).Decode(&result); err != nil {
		t.Fatalf("failed to decode: %v", err)
	}
	if !strings.Contains(result.URIs[0], "stun.example.com") {
		t.Fatalf("expected coturn URIs after Cloudflare failure, got %v", result.URIs)
	}
}

func TestHandleTurnCredentialsNoCloudflareFallsThrough(t *testing.T) {
	t.Setenv("TURN_TOKEN_SECRET", "test-secret-1234")
	t.Setenv("TURN_SECRET", "coturn-secret")
	t.Setenv("STUN_HOST", "stun.example.com")

	token, _, err := issueTurnToken(10*time.Minute, turnTokenKindCall)
	if err != nil {
		t.Fatalf("issueTurnToken: %v", err)
	}

	handler := handleTurnCredentials()
	req := httptest.NewRequest(http.MethodGet, "/api/turn-credentials?token="+token, nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	var result TurnConfig
	if err := json.NewDecoder(w.Body).Decode(&result); err != nil {
		t.Fatalf("failed to decode: %v", err)
	}
	if result.TTL != 900 {
		t.Fatalf("expected TTL=900, got %d", result.TTL)
	}
	if !strings.Contains(result.URIs[0], "stun.example.com") {
		t.Fatalf("expected coturn URIs, got %v", result.URIs)
	}
}

func TestFetchCloudflareCredentialsSuccess(t *testing.T) {
	mockCloudflareTURN(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.Header.Get("Authorization") != "Bearer test-token" {
			t.Errorf("bad auth header: %s", r.Header.Get("Authorization"))
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Errorf("bad content type: %s", r.Header.Get("Content-Type"))
		}

		var body map[string]int
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatalf("failed to decode request body: %v", err)
		}
		if body["ttl"] != 900 {
			t.Errorf("expected ttl=900, got %d", body["ttl"])
		}

		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478","turn:turn.cloudflare.com:3478?transport=udp","turn:turn.cloudflare.com:3478?transport=tcp","turns:turn.cloudflare.com:5349?transport=tcp"],"username":"cf-user","credential":"cf-pass"}]}`)
	}))

	config, err := fetchCloudflareCredentials(context.Background(), "any-key", "test-token", 900)
	if err != nil {
		t.Fatalf("fetchCloudflareCredentials: %v", err)
	}

	if config.Username != "cf-user" {
		t.Fatalf("expected username cf-user, got %s", config.Username)
	}
	if config.Password != "cf-pass" {
		t.Fatalf("expected password cf-pass, got %s", config.Password)
	}
	if config.TTL != 900 {
		t.Fatalf("expected TTL 900, got %d", config.TTL)
	}
	if len(config.URIs) != 4 {
		t.Fatalf("expected 4 URIs, got %d", len(config.URIs))
	}
}

func TestFetchCloudflareCredentialsAPIError(t *testing.T) {
	mockCloudflareTURN(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		fmt.Fprint(w, `{"error":"forbidden"}`)
	}))

	_, err := fetchCloudflareCredentials(context.Background(), "any-key", "bad-token", 900)
	if err == nil {
		t.Fatal("expected error from Cloudflare API")
	}
	if !strings.Contains(err.Error(), "403") {
		t.Fatalf("expected 403 in error, got: %v", err)
	}
}
