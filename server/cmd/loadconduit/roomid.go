package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const (
	roomIDVersion     = "v1"
	roomIDEntity      = "room"
	roomIDRandomBytes = 12
	roomIDTagBytes    = 8
)

func roomIDContext(env string) string {
	if strings.TrimSpace(env) == "" {
		env = "dev"
	}
	return fmt.Sprintf("id:%s|%s|%s", roomIDVersion, env, roomIDEntity)
}

func generateRoomIDLocal(secret string, env string) (string, error) {
	secret = strings.TrimSpace(secret)
	if secret == "" {
		return "", fmt.Errorf("room ID secret is empty")
	}
	randomBytes := make([]byte, roomIDRandomBytes)
	if _, err := rand.Read(randomBytes); err != nil {
		return "", err
	}

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(randomBytes)
	mac.Write([]byte(roomIDContext(env)))
	tag := mac.Sum(nil)[:roomIDTagBytes]

	token := append(append([]byte{}, randomBytes...), tag...)
	return base64.RawURLEncoding.EncodeToString(token), nil
}

func createRoomIDHTTP(ctx context.Context, baseURL string, client *http.Client) (string, error) {
	url := strings.TrimRight(strings.TrimSpace(baseURL), "/") + "/api/room-id"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, nil)
	if err != nil {
		return "", err
	}

	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return "", fmt.Errorf("room-id endpoint returned %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}

	var payload struct {
		RoomID string `json:"roomId"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return "", err
	}
	if strings.TrimSpace(payload.RoomID) == "" {
		return "", fmt.Errorf("room-id response missing roomId")
	}
	return payload.RoomID, nil
}

func generateRoomIDs(ctx context.Context, cfg Config, count int) ([]string, error) {
	ids := make([]string, 0, count)
	if count <= 0 {
		return ids, nil
	}

	if cfg.RoomIDSecret != "" {
		for i := 0; i < count; i++ {
			roomID, err := generateRoomIDLocal(cfg.RoomIDSecret, cfg.RoomIDEnv)
			if err != nil {
				return nil, err
			}
			ids = append(ids, roomID)
		}
		return ids, nil
	}

	httpClient := &http.Client{Timeout: 10 * time.Second}
	for i := 0; i < count; i++ {
		var roomID string
		var err error
		for attempt := 0; attempt < 3; attempt++ {
			roomID, err = createRoomIDHTTP(ctx, cfg.BaseURL, httpClient)
			if err == nil {
				break
			}
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(time.Duration(200*(attempt+1)) * time.Millisecond):
			}
		}
		if err != nil {
			return nil, err
		}
		ids = append(ids, roomID)
	}

	return ids, nil
}
