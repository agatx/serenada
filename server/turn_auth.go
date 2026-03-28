package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

type TurnConfig struct {
	Username string   `json:"username"`
	Password string   `json:"password"`
	URIs     []string `json:"uris"`
	TTL      int      `json:"ttl"`
}

const (
	turnTokenVersion        = 1
	turnTokenKindCall       = "call"
	turnTokenKindDiagnostic = "diagnostic"
)

// Token claims no longer include IP for robustness
type turnTokenClaims struct {
	V    int    `json:"v"`
	Kind string `json:"k"`
	Exp  int64  `json:"exp"`
}

func getTurnTokenSecret() (string, error) {
	secret := os.Getenv("TURN_TOKEN_SECRET")
	if secret == "" {
		secret = os.Getenv("TURN_SECRET")
	}
	if secret == "" {
		return "", errors.New("TURN token secret not configured")
	}
	return secret, nil
}

func issueTurnToken(ttl time.Duration, kind string) (string, time.Time, error) {
	secret, err := getTurnTokenSecret()
	if err != nil {
		return "", time.Time{}, err
	}

	expiresAt := time.Now().Add(ttl)
	claims := turnTokenClaims{
		V:    turnTokenVersion,
		Kind: kind,
		Exp:  expiresAt.Unix(),
	}

	payloadBytes, err := json.Marshal(claims)
	if err != nil {
		return "", time.Time{}, err
	}
	payload := base64.RawURLEncoding.EncodeToString(payloadBytes)

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(payload))
	sig := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))

	return payload + "." + sig, expiresAt, nil
}

func parseTurnToken(token string) (turnTokenClaims, bool) {
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		return turnTokenClaims{}, false
	}

	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return turnTokenClaims{}, false
	}

	sigBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return turnTokenClaims{}, false
	}

	secret, err := getTurnTokenSecret()
	if err != nil {
		return turnTokenClaims{}, false
	}

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(parts[0]))
	expectedSig := mac.Sum(nil)
	if !hmac.Equal(expectedSig, sigBytes) {
		return turnTokenClaims{}, false
	}

	var claims turnTokenClaims
	if err := json.Unmarshal(payloadBytes, &claims); err != nil {
		return turnTokenClaims{}, false
	}

	return claims, true
}

func validateTurnToken(token, kind string) bool {
	claims, ok := parseTurnToken(token)
	if !ok {
		return false
	}
	if claims.V != turnTokenVersion {
		return false
	}
	if claims.Kind != kind {
		return false
	}
	if time.Now().Unix() > claims.Exp {
		return false
	}
	// IP check removed
	return true
}

// cloudflareICEServer is a single entry in the Cloudflare TURN API response.
type cloudflareICEServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
}

// cloudflareCredentialsResponse matches the Cloudflare TURN API response shape.
// The generate-ice-servers endpoint returns iceServers as an array.
type cloudflareCredentialsResponse struct {
	IceServers []cloudflareICEServer `json:"iceServers"`
}

var (
	// cfHTTPClient is the HTTP client used for Cloudflare TURN API calls.
	// Tests can override this to use an httptest server's client.
	cfHTTPClient = &http.Client{Timeout: 5 * time.Second}

	// cfTURNBaseURL is the Cloudflare TURN API base URL.
	// Tests can override this to point at an httptest server.
	cfTURNBaseURL = "https://rtc.live.cloudflare.com/v1/turn/keys"
)

// fetchCloudflareCredentials calls the Cloudflare TURN API to generate
// short-lived credentials. Returns a TurnConfig in the legacy format on
// success, or an error if the API call fails.
func fetchCloudflareCredentials(ctx context.Context, keyID, apiToken string, ttl int) (*TurnConfig, error) {
	apiURL := fmt.Sprintf("%s/%s/credentials/generate-ice-servers", cfTURNBaseURL, keyID)

	body, err := json.Marshal(map[string]int{"ttl": ttl})
	if err != nil {
		return nil, fmt.Errorf("marshal request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, apiURL, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+apiToken)
	req.Header.Set("Content-Type", "application/json")

	resp, err := cfHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("cloudflare API request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("cloudflare API returned %d: %s", resp.StatusCode, string(respBody))
	}

	var cfResp cloudflareCredentialsResponse
	if err := json.NewDecoder(resp.Body).Decode(&cfResp); err != nil {
		return nil, fmt.Errorf("decode cloudflare response: %w", err)
	}

	if len(cfResp.IceServers) == 0 {
		return nil, fmt.Errorf("cloudflare returned empty iceServers array")
	}

	entry := cfResp.IceServers[0]
	return &TurnConfig{
		Username: entry.Username,
		Password: entry.Credential,
		URIs:     entry.URLs,
		TTL:      ttl,
	}, nil
}

// generateCoturnCredentials produces HMAC-SHA1 credentials for the self-hosted
// coturn server. This is the existing credential generation logic, extracted
// into its own function so handleTurnCredentials can call it as a fallback.
func generateCoturnCredentials(clientIP string, ttl int) (*TurnConfig, error) {
	secret := os.Getenv("TURN_SECRET")
	turnHost := os.Getenv("TURN_HOST")
	stunHost := os.Getenv("STUN_HOST")
	if secret == "" || stunHost == "" {
		return nil, errors.New("STUN not configured")
	}

	timestamp := time.Now().Unix() + int64(ttl)
	userPart := clientIP
	if userPart == "" {
		userPart = "unknown"
	}
	userPart = strings.ReplaceAll(userPart, ":", "-")
	userPart = strings.ReplaceAll(userPart, "%", "-")
	username := fmt.Sprintf("%d:%s", timestamp, userPart)

	mac := hmac.New(sha1.New, []byte(secret))
	mac.Write([]byte(username))
	password := base64.StdEncoding.EncodeToString(mac.Sum(nil))

	config := &TurnConfig{
		Username: username,
		Password: password,
		URIs: []string{
			"stun:" + stunHost,
			"turn:" + stunHost,
		},
		TTL: ttl,
	}

	if turnHost != "" {
		config.URIs = append(config.URIs, "turns:"+turnHost+":443?transport=tcp")
	} else {
		config.URIs = append(config.URIs, "turns:"+stunHost+":5349?transport=tcp")
	}

	return config, nil
}

func handleTurnCredentials() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}

		token := r.URL.Query().Get("token")
		clientIP := getClientIP(r)

		if token == "" {
			log.Printf("[AUTH_FAIL] TURN Credentials requested by %s: No token provided", clientIP)
			http.Error(w, "Unauthorized", http.StatusUnauthorized)
			return
		}

		credentialTTL := 15 * 60 // default: 15 minutes
		isAuthorized := false

		if validateTurnToken(token, turnTokenKindCall) {
			isAuthorized = true
		} else if validateTurnToken(token, turnTokenKindDiagnostic) {
			isAuthorized = true
			credentialTTL = 5
		}

		if !isAuthorized {
			log.Printf("[AUTH_FAIL] TURN Credentials requested by %s: Invalid token", clientIP)
			http.Error(w, "Unauthorized", http.StatusUnauthorized)
			return
		}

		log.Printf("[AUTH_OK] TURN Credentials requested by %s", clientIP)

		// Try Cloudflare TURN first (if configured), fall back to coturn.
		cfKeyID := os.Getenv("CF_TURN_KEY_ID")
		cfAPIToken := os.Getenv("CF_TURN_API_TOKEN")

		if cfKeyID != "" && cfAPIToken != "" {
			config, err := fetchCloudflareCredentials(r.Context(), cfKeyID, cfAPIToken, credentialTTL)
			if err != nil {
				log.Printf("[TURN] Cloudflare TURN failed, falling back to coturn: %v", err)
			} else {
				w.Header().Set("Content-Type", "application/json")
				json.NewEncoder(w).Encode(config)
				return
			}
		}

		// Fallback: generate coturn credentials
		config, err := generateCoturnCredentials(clientIP, credentialTTL)
		if err != nil {
			http.Error(w, "STUN not configured", http.StatusServiceUnavailable)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(config)
	}
}

// TODO: Remove this
func handleDiagnosticToken() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost && r.Method != http.MethodGet {
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}

		token, expires, err := issueTurnToken(5*time.Second, turnTokenKindDiagnostic)
		if err != nil {
			http.Error(w, "TURN token unavailable", http.StatusServiceUnavailable)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"token":   token,
			"expires": expires.Unix(),
		})
	}
}
