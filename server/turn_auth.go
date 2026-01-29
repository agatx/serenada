package main

import (
	"crypto/hmac"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
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

		// 1. Get Secret and Host from Env
		secret := os.Getenv("TURN_SECRET")
		turn_host := os.Getenv("TURN_HOST")
		stun_host := os.Getenv("STUN_HOST")
		if secret == "" || stun_host == "" {
			http.Error(w, "STUN not configured", http.StatusServiceUnavailable)
			return
		}

		// 2. Generate Credentials (Time-limited)
		// Standard TURN REST API: username = timestamp:user
		ttl := credentialTTL
		timestamp := time.Now().Unix() + int64(ttl)
		userPart := clientIP
		if userPart == "" {
			userPart = "unknown"
		}
		userPart = strings.ReplaceAll(userPart, ":", "-")
		userPart = strings.ReplaceAll(userPart, "%", "-")
		username := fmt.Sprintf("%d:%s", timestamp, userPart)

		// Password = HMAC-SHA1(secret, username)
		mac := hmac.New(sha1.New, []byte(secret))
		mac.Write([]byte(username))
		password := base64.StdEncoding.EncodeToString(mac.Sum(nil))

		config := TurnConfig{
			Username: username,
			Password: password,
			URIs: []string{
				"stun:" + stun_host,
				"turn:" + stun_host,
			},
			TTL: ttl,
		}

		if turn_host != "" {
			config.URIs = append(config.URIs, "turns:"+turn_host+":443?transport=tcp")
		} else {
			config.URIs = append(config.URIs, "turns:"+stun_host+":5349?transport=tcp")
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
