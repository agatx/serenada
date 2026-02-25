package main

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

type FCMService struct {
	projectID   string
	clientEmail string
	privateKey  *rsa.PrivateKey
	httpClient  *http.Client

	mu          sync.Mutex
	accessToken string
	expiresAt   time.Time
}

type fcmServiceAccount struct {
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
}

func initFCMServiceFromEnv() (*FCMService, error) {
	rawJSON := strings.TrimSpace(os.Getenv("FCM_SERVICE_ACCOUNT_JSON"))
	if rawJSON == "" {
		path := strings.TrimSpace(os.Getenv("FCM_SERVICE_ACCOUNT_FILE"))
		if path != "" {
			bytes, err := os.ReadFile(path)
			if err != nil {
				return nil, fmt.Errorf("read FCM service account file: %w", err)
			}
			rawJSON = strings.TrimSpace(string(bytes))
		}
	}

	if rawJSON == "" {
		log.Printf("[PUSH] FCM service account not configured (set FCM_SERVICE_ACCOUNT_JSON or FCM_SERVICE_ACCOUNT_FILE)")
		return nil, nil
	}

	var creds fcmServiceAccount
	if err := json.Unmarshal([]byte(rawJSON), &creds); err != nil {
		return nil, fmt.Errorf("parse FCM service account JSON: %w", err)
	}

	if strings.TrimSpace(creds.ProjectID) == "" ||
		strings.TrimSpace(creds.ClientEmail) == "" ||
		strings.TrimSpace(creds.PrivateKey) == "" {
		return nil, fmt.Errorf("FCM service account is missing required fields")
	}

	privateKey, err := parseServiceAccountPrivateKey(creds.PrivateKey)
	if err != nil {
		return nil, err
	}

	log.Printf("[PUSH] FCM initialized for project %s", creds.ProjectID)
	return &FCMService{
		projectID:   creds.ProjectID,
		clientEmail: creds.ClientEmail,
		privateKey:  privateKey,
		httpClient: &http.Client{
			Timeout: 12 * time.Second,
		},
	}, nil
}

func parseServiceAccountPrivateKey(raw string) (*rsa.PrivateKey, error) {
	normalized := strings.ReplaceAll(raw, "\\n", "\n")
	block, _ := pem.Decode([]byte(normalized))
	if block == nil {
		return nil, fmt.Errorf("failed to decode service account private key PEM")
	}

	if key, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		rsaKey, ok := key.(*rsa.PrivateKey)
		if !ok {
			return nil, fmt.Errorf("service account private key is not RSA")
		}
		return rsaKey, nil
	}

	key, err := x509.ParsePKCS1PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("failed to parse service account private key: %w", err)
	}
	return key, nil
}

func (s *FCMService) SendDataMessage(token string, payload map[string]string) (int, []byte, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return 0, nil, fmt.Errorf("missing FCM registration token")
	}

	accessToken, err := s.getAccessToken()
	if err != nil {
		return 0, nil, err
	}

	title := strings.TrimSpace(payload["title"])
	bodyText := strings.TrimSpace(payload["body"])
	aps := map[string]any{
		"mutable-content": 1,
		"content-available": 1,
	}
	if title != "" || bodyText != "" {
		alert := map[string]string{}
		if title != "" {
			alert["title"] = title
		}
		if bodyText != "" {
			alert["body"] = bodyText
		}
		if len(alert) > 0 {
			aps["alert"] = alert
			aps["sound"] = "default"
		}
	}

	requestBody := map[string]any{
		"message": map[string]any{
			"token": token,
			"data":  payload,
			"android": map[string]any{
				"priority": "HIGH",
				"ttl":      "60s",
			},
			"apns": map[string]any{
				"headers": map[string]string{
					"apns-priority":  "10",
					"apns-push-type": "alert",
				},
				"payload": map[string]any{
					"aps": aps,
				},
			},
		},
	}
	encoded, _ := json.Marshal(requestBody)

	endpoint := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", s.projectID)
	req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(encoded))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Content-Type", "application/json")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	return resp.StatusCode, body, nil
}

func (s *FCMService) getAccessToken() (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()
	if s.accessToken != "" && now.Before(s.expiresAt.Add(-60*time.Second)) {
		return s.accessToken, nil
	}

	assertion, err := s.buildJWTAssertion(now)
	if err != nil {
		return "", err
	}

	form := url.Values{}
	form.Set("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
	form.Set("assertion", assertion)

	req, err := http.NewRequest(http.MethodPost, "https://oauth2.googleapis.com/token", strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("FCM oauth token request failed (%d): %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}

	var tokenResp struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &tokenResp); err != nil {
		return "", fmt.Errorf("parse FCM oauth response: %w", err)
	}
	if tokenResp.AccessToken == "" {
		return "", fmt.Errorf("FCM oauth response missing access_token")
	}

	ttl := tokenResp.ExpiresIn
	if ttl <= 0 {
		ttl = 3600
	}
	s.accessToken = tokenResp.AccessToken
	s.expiresAt = now.Add(time.Duration(ttl) * time.Second)
	return s.accessToken, nil
}

func (s *FCMService) buildJWTAssertion(now time.Time) (string, error) {
	header, _ := json.Marshal(map[string]string{
		"alg": "RS256",
		"typ": "JWT",
	})
	claims, _ := json.Marshal(map[string]any{
		"iss":   s.clientEmail,
		"scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud":   "https://oauth2.googleapis.com/token",
		"iat":   now.Unix(),
		"exp":   now.Add(60 * time.Minute).Unix(),
	})

	encodedHeader := base64.RawURLEncoding.EncodeToString(header)
	encodedClaims := base64.RawURLEncoding.EncodeToString(claims)
	signingInput := encodedHeader + "." + encodedClaims

	hash := sha256.Sum256([]byte(signingInput))
	signature, err := rsa.SignPKCS1v15(rand.Reader, s.privateKey, crypto.SHA256, hash[:])
	if err != nil {
		return "", err
	}

	encodedSignature := base64.RawURLEncoding.EncodeToString(signature)
	return signingInput + "." + encodedSignature, nil
}

func isFCMTokenInvalid(statusCode int, body []byte) bool {
	if statusCode < http.StatusBadRequest {
		return false
	}

	var fcmErr struct {
		Error struct {
			Status  string `json:"status"`
			Message string `json:"message"`
			Details []struct {
				ErrorCode string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	if err := json.Unmarshal(body, &fcmErr); err == nil {
		status := strings.ToUpper(strings.TrimSpace(fcmErr.Error.Status))
		message := strings.ToLower(fcmErr.Error.Message)
		if status == "UNREGISTERED" {
			return true
		}
		if status == "INVALID_ARGUMENT" && strings.Contains(message, "registration token") {
			return true
		}
		for _, detail := range fcmErr.Error.Details {
			switch strings.ToUpper(strings.TrimSpace(detail.ErrorCode)) {
			case "UNREGISTERED":
				return true
			case "INVALID_ARGUMENT":
				if strings.Contains(message, "registration token") {
					return true
				}
			}
		}
	}

	// Fallback for older/non-standard responses that only expose text markers.
	// Do not treat bare HTTP 404/410 as invalid token without explicit token signals.
	upper := strings.ToUpper(string(body))
	return strings.Contains(upper, "UNREGISTERED")
}
