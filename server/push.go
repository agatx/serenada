package main

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/SherClockHolmes/webpush-go"
	_ "modernc.org/sqlite"
)

type PushService struct {
	db         *sql.DB
	privateKey string
	publicKey  string
	mu         sync.RWMutex
}

type VAPIDKeys struct {
	PrivateKey string `json:"privateKey"`
	PublicKey  string `json:"publicKey"`
}

type PushSubscriptionRequest struct {
	Endpoint string `json:"endpoint"`
	Keys     struct {
		Auth   string `json:"auth"`
		P256dh string `json:"p256dh"`
	} `json:"keys"`
	Locale       string          `json:"locale"`
	EncPublicKey json.RawMessage `json:"encPublicKey"`
}

type SnapshotRecipient struct {
	ID           int    `json:"id"`
	WrappedKey   string `json:"wrappedKey"`
	WrappedKeyIV string `json:"wrappedKeyIv"`
}

type SnapshotUploadRequest struct {
	Ciphertext           string              `json:"ciphertext"`
	SnapshotIV           string              `json:"snapshotIv"`
	SnapshotSalt         string              `json:"snapshotSalt"`
	SnapshotEphemeralKey string              `json:"snapshotEphemeralPubKey"`
	SnapshotMime         string              `json:"snapshotMime"`
	Recipients           []SnapshotRecipient `json:"recipients"`
}

type SnapshotRecipientKey struct {
	WrappedKey   string `json:"wrappedKey"`
	WrappedKeyIV string `json:"wrappedKeyIv"`
}

type SnapshotMeta struct {
	IV           string                          `json:"iv"`
	Salt         string                          `json:"salt"`
	EphemeralKey string                          `json:"ephemeralPubKey"`
	Mime         string                          `json:"mime"`
	CreatedAt    int64                           `json:"createdAt"`
	Recipients   map[string]SnapshotRecipientKey `json:"recipients"`
}

var pushService *PushService

func getDataDir() string {
	dataDir := os.Getenv("DATA_DIR")
	if dataDir == "" {
		dataDir = "."
	}
	return dataDir
}

func getSnapshotDir() string {
	return filepath.Join(getDataDir(), "snapshots")
}

func snapshotDataPath(id string) string {
	return filepath.Join(getSnapshotDir(), id+".bin")
}

func snapshotMetaPath(id string) string {
	return filepath.Join(getSnapshotDir(), id+".json")
}

func InitPushService() error {
	dataDir := getDataDir()
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		return fmt.Errorf("failed to create data dir: %v", err)
	}
	if err := os.MkdirAll(getSnapshotDir(), 0755); err != nil {
		return fmt.Errorf("failed to create snapshot dir: %v", err)
	}

	// 1. Setup SQLite
	dbPath := fmt.Sprintf("%s/subscriptions.db", dataDir)
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return fmt.Errorf("failed to open sqlite db: %v", err)
	}

	createTableSQL := `
	CREATE TABLE IF NOT EXISTS subscriptions (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		room_id TEXT NOT NULL,
		endpoint TEXT NOT NULL,
		auth TEXT NOT NULL,
		p256dh TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		locale TEXT DEFAULT 'en',
		enc_pubkey TEXT,
		UNIQUE(room_id, endpoint)
	);`

	if _, err := db.Exec(createTableSQL); err != nil {
		return fmt.Errorf("failed to create table: %v", err)
	}

	// Migration: Add locale column if not exists (simplistic check)
	// Ignore error if column exists
	_, _ = db.Exec("ALTER TABLE subscriptions ADD COLUMN locale TEXT DEFAULT 'en'")
	_, _ = db.Exec("ALTER TABLE subscriptions ADD COLUMN enc_pubkey TEXT")

	// 2. Setup VAPID Keys
	keys, err := loadOrGenerateVAPIDKeys()
	if err != nil {
		return fmt.Errorf("failed to setup VAPID keys: %v", err)
	}

	pushService = &PushService{
		db:         db,
		privateKey: keys.PrivateKey,
		publicKey:  keys.PublicKey,
	}

	log.Printf("[PUSH] PushService initialized with SQLite persistence at %s", dbPath)
	return nil
}

func loadOrGenerateVAPIDKeys() (*VAPIDKeys, error) {
	filename := fmt.Sprintf("%s/vapid.json", getDataDir())
	if _, err := os.Stat(filename); os.IsNotExist(err) {
		log.Println("[PUSH] Generating new VAPID keys...")
		privateKey, publicKey, err := webpush.GenerateVAPIDKeys()
		if err != nil {
			return nil, err
		}
		keys := &VAPIDKeys{
			PrivateKey: privateKey,
			PublicKey:  publicKey,
		}
		data, _ := json.MarshalIndent(keys, "", "  ")
		if err := os.WriteFile(filename, data, 0600); err != nil {
			return nil, err
		}
		return keys, nil
	}

	data, err := os.ReadFile(filename)
	if err != nil {
		return nil, err
	}
	var keys VAPIDKeys
	if err := json.Unmarshal(data, &keys); err != nil {
		return nil, err
	}
	return &keys, nil
}

func (s *PushService) GetVAPIDPublicKey() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.publicKey
}

func (s *PushService) Subscribe(roomID string, sub PushSubscriptionRequest) error {
	stmt, err := s.db.Prepare("INSERT OR REPLACE INTO subscriptions(room_id, endpoint, auth, p256dh, locale, enc_pubkey, created_at) VALUES(?, ?, ?, ?, ?, ?, ?)")
	if err != nil {
		return err
	}
	defer stmt.Close()

	locale := sub.Locale
	if locale == "" {
		locale = "en"
	}

	encKey := strings.TrimSpace(string(sub.EncPublicKey))
	if encKey == "null" {
		encKey = ""
	}

	_, err = stmt.Exec(roomID, sub.Endpoint, sub.Keys.Auth, sub.Keys.P256dh, locale, encKey, time.Now().UnixMilli())
	if err != nil {
		log.Printf("[PUSH] Failed to save subscription: %v", err)
		return err
	}
	log.Printf("[PUSH] Subscribed endpoint %s to room %s (locale: %s)", sub.Endpoint, roomID, locale)
	return nil
}

func (s *PushService) Unsubscribe(roomID string, endpoint string) error {
	stmt, err := s.db.Prepare("DELETE FROM subscriptions WHERE room_id = ? AND endpoint = ?")
	if err != nil {
		return err
	}
	defer stmt.Close()

	_, err = stmt.Exec(roomID, endpoint)
	if err != nil {
		return err
	}
	log.Printf("[PUSH] Unsubscribed endpoint %s from room %s", endpoint, roomID)
	return nil
}

func (s *PushService) SendNotificationToRoom(roomID string, excludeEndpoint string, snapshotID string) {
	rows, err := s.db.Query("SELECT id, endpoint, auth, p256dh, locale FROM subscriptions WHERE room_id = ?", roomID)
	if err != nil {
		log.Printf("[PUSH] Failed to query subscriptions for room %s: %v", roomID, err)
		return
	}
	defer rows.Close()

	type subData struct {
		ID       int
		Endpoint string
		Auth     string
		P256dh   string
		Locale   string
	}
	var targets []subData

	for rows.Next() {
		var sd subData
		if err := rows.Scan(&sd.ID, &sd.Endpoint, &sd.Auth, &sd.P256dh, &sd.Locale); err != nil {
			log.Printf("[PUSH] Scan error: %v", err)
			continue
		}
		if sd.Endpoint == excludeEndpoint {
			continue
		}
		targets = append(targets, sd)
	}

	log.Printf("[PUSH] Found %d subscribers for room %s", len(targets), roomID)

	var snapshotMeta *SnapshotMeta
	if snapshotID != "" && isSafeSnapshotID(snapshotID) {
		if meta, err := loadSnapshotMeta(snapshotID); err == nil {
			snapshotMeta = meta
		} else {
			log.Printf("[PUSH] Failed to load snapshot %s: %v", snapshotID, err)
		}
	}

	// Send in parallel or just loop
	for _, target := range targets {
		go s.sendOne(roomID, target, snapshotID, snapshotMeta)
	}
}

func getLocalizedMessage(locale string) (string, string) {
	// Simple mapping, can be expanded
	// Check prefix
	lang := locale
	if len(locale) > 2 {
		lang = locale[:2]
	}

	switch lang {
	case "ru":
		return "Serenada", "Кто-то присоединился к вашему звонку!"
	case "es":
		return "Serenada", "¡Alguien se unió a tu llamada!"
	case "de":
		return "Serenada", "Jemand ist deinem Anruf beigetreten!"
	case "fr":
		return "Serenada", "Quelqu'un a rejoint votre appel !"
	default:
		return "Serenada", "Someone joined your call!"
	}
}

func (s *PushService) sendOne(roomID string, target struct {
	ID       int
	Endpoint string
	Auth     string
	P256dh   string
	Locale   string
}, snapshotID string, snapshotMeta *SnapshotMeta) {
	title, body := getLocalizedMessage(target.Locale)

	// Payload
	payload := map[string]string{
		"title": title,
		"body":  body,
		"url":   fmt.Sprintf("/call/%s", roomID),
	}

	if snapshotID != "" && snapshotMeta != nil {
		if key, ok := snapshotMeta.Recipients[fmt.Sprintf("%d", target.ID)]; ok {
			payload["snapshotId"] = snapshotID
			payload["snapshotIv"] = snapshotMeta.IV
			payload["snapshotSalt"] = snapshotMeta.Salt
			payload["snapshotEphemeralPubKey"] = snapshotMeta.EphemeralKey
			payload["snapshotKey"] = key.WrappedKey
			payload["snapshotKeyIv"] = key.WrappedKeyIV
			if snapshotMeta.Mime != "" {
				payload["snapshotMime"] = snapshotMeta.Mime
			}
		}
	}

	payloadBytes, _ := json.Marshal(payload)

	sub := &webpush.Subscription{
		Endpoint: target.Endpoint,
		Keys: webpush.Keys{
			Auth:   target.Auth,
			P256dh: target.P256dh,
		},
	}

	// Determine subscriber email for VAPID; configurable via environment variable.
	subscriber := os.Getenv("PUSH_SUBSCRIBER_EMAIL")
	// Send Notification
	resp, err := webpush.SendNotification(payloadBytes, sub, &webpush.Options{
		Subscriber:      subscriber,
		VAPIDPublicKey:  s.publicKey,
		VAPIDPrivateKey: s.privateKey,
		TTL:             60, // 1 minute TTL
	})
	if err != nil {
		log.Printf("[PUSH] Failed to send to %s: %v", target.Endpoint, err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode == 201 || resp.StatusCode == 200 {
		log.Printf("[PUSH] Successfully sent notification to %s (Status %d)", target.Endpoint, resp.StatusCode)
	} else if resp.StatusCode == 410 || resp.StatusCode == 404 {
		// Subscription is gone, remove it
		log.Printf("[PUSH] Subscription expired/gone (Status %d). Removing %s", resp.StatusCode, target.Endpoint)
		s.Unsubscribe(roomID, target.Endpoint)
	} else {
		log.Printf("[PUSH] Unexpected response from push service: Status %d", resp.StatusCode)
	}
}

func isSafeSnapshotID(id string) bool {
	if id == "" {
		return false
	}
	for _, r := range id {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			continue
		}
		return false
	}
	return true
}

func loadSnapshotMeta(id string) (*SnapshotMeta, error) {
	path := snapshotMetaPath(id)
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var meta SnapshotMeta
	if err := json.Unmarshal(data, &meta); err != nil {
		return nil, err
	}
	return &meta, nil
}

func cleanupOldSnapshots(maxAge time.Duration) {
	dir := getSnapshotDir()
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-maxAge)
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		if info.ModTime().Before(cutoff) {
			_ = os.Remove(filepath.Join(dir, entry.Name()))
		}
	}
}

// HTTP Handlers

func handlePushVapidKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"publicKey": pushService.GetVAPIDPublicKey(),
	})
}

func handlePushSubscribe(w http.ResponseWriter, r *http.Request) {
	if r.Method == "OPTIONS" {
		return
	}

	roomId := r.URL.Query().Get("roomId")
	if roomId == "" {
		http.Error(w, "Missing roomId", http.StatusBadRequest)
		return
	}

	if r.Method == "POST" {
		var sub PushSubscriptionRequest
		if err := json.NewDecoder(r.Body).Decode(&sub); err != nil {
			http.Error(w, "Invalid body", http.StatusBadRequest)
			return
		}
		if len(sub.EncPublicKey) > 4096 {
			http.Error(w, "Encryption key too large", http.StatusBadRequest)
			return
		}
		if len(sub.EncPublicKey) > 0 && !json.Valid(sub.EncPublicKey) {
			http.Error(w, "Invalid encryption key", http.StatusBadRequest)
			return
		}

		if err := pushService.Subscribe(roomId, sub); err != nil {
			http.Error(w, "Failed to subscribe", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
		return
	}

	if r.Method == "DELETE" {
		var body struct {
			Endpoint string `json:"endpoint"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "Invalid body", http.StatusBadRequest)
			return
		}

		if err := pushService.Unsubscribe(roomId, body.Endpoint); err != nil {
			http.Error(w, "Failed to unsubscribe", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
		return
	}

	http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
}

func handlePushRecipients(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	roomId := r.URL.Query().Get("roomId")
	if roomId == "" {
		http.Error(w, "Missing roomId", http.StatusBadRequest)
		return
	}

	rows, err := pushService.db.Query("SELECT id, enc_pubkey FROM subscriptions WHERE room_id = ? AND enc_pubkey IS NOT NULL AND enc_pubkey != ''", roomId)
	if err != nil {
		http.Error(w, "Failed to load recipients", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type recipient struct {
		ID        int         `json:"id"`
		PublicKey interface{} `json:"publicKey"`
	}
	var recipients []recipient
	for rows.Next() {
		var id int
		var keyStr string
		if err := rows.Scan(&id, &keyStr); err != nil {
			continue
		}
		var key interface{}
		if err := json.Unmarshal([]byte(keyStr), &key); err != nil {
			continue
		}
		recipients = append(recipients, recipient{ID: id, PublicKey: key})
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(recipients)
}

func handlePushSnapshot(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case "OPTIONS":
		return
	case "POST":
		var req SnapshotUploadRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "Invalid body", http.StatusBadRequest)
			return
		}

		if req.Ciphertext == "" || req.SnapshotIV == "" || req.SnapshotSalt == "" || req.SnapshotEphemeralKey == "" {
			http.Error(w, "Missing snapshot data", http.StatusBadRequest)
			return
		}

		ciphertext, err := base64.StdEncoding.DecodeString(req.Ciphertext)
		if err != nil {
			http.Error(w, "Invalid snapshot data", http.StatusBadRequest)
			return
		}
		if len(ciphertext) > 300*1024 {
			http.Error(w, "Snapshot too large", http.StatusRequestEntityTooLarge)
			return
		}

		iv, err := base64.StdEncoding.DecodeString(req.SnapshotIV)
		if err != nil || len(iv) != 12 {
			http.Error(w, "Invalid snapshot IV", http.StatusBadRequest)
			return
		}
		salt, err := base64.StdEncoding.DecodeString(req.SnapshotSalt)
		if err != nil || len(salt) < 8 || len(salt) > 64 {
			http.Error(w, "Invalid snapshot salt", http.StatusBadRequest)
			return
		}
		ephemeralKey, err := base64.StdEncoding.DecodeString(req.SnapshotEphemeralKey)
		if err != nil || len(ephemeralKey) < 32 {
			http.Error(w, "Invalid snapshot key", http.StatusBadRequest)
			return
		}

		recipients := make(map[string]SnapshotRecipientKey)
		for _, r := range req.Recipients {
			if r.ID <= 0 || r.WrappedKey == "" || r.WrappedKeyIV == "" {
				continue
			}
			wrapped, err := base64.StdEncoding.DecodeString(r.WrappedKey)
			if err != nil || len(wrapped) == 0 {
				continue
			}
			wrappedIV, err := base64.StdEncoding.DecodeString(r.WrappedKeyIV)
			if err != nil || len(wrappedIV) != 12 {
				continue
			}
			recipients[strconv.Itoa(r.ID)] = SnapshotRecipientKey{
				WrappedKey:   r.WrappedKey,
				WrappedKeyIV: r.WrappedKeyIV,
			}
		}
		if len(recipients) == 0 {
			http.Error(w, "No valid recipients", http.StatusBadRequest)
			return
		}

		id := generateID("SNAP-")
		if err := os.WriteFile(snapshotDataPath(id), ciphertext, 0600); err != nil {
			http.Error(w, "Failed to save snapshot", http.StatusInternalServerError)
			return
		}

		mime := req.SnapshotMime
		if mime == "" {
			mime = "image/jpeg"
		}
		meta := SnapshotMeta{
			IV:           req.SnapshotIV,
			Salt:         req.SnapshotSalt,
			EphemeralKey: req.SnapshotEphemeralKey,
			Mime:         mime,
			CreatedAt:    time.Now().UnixMilli(),
			Recipients:   recipients,
		}
		metaBytes, _ := json.Marshal(meta)
		if err := os.WriteFile(snapshotMetaPath(id), metaBytes, 0600); err != nil {
			_ = os.Remove(snapshotDataPath(id))
			http.Error(w, "Failed to save snapshot metadata", http.StatusInternalServerError)
			return
		}
		cleanupOldSnapshots(10 * time.Minute)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{
			"id":  id,
			"url": fmt.Sprintf("/api/push/snapshot/%s", id),
		})
		return
	case "GET":
		id := strings.TrimPrefix(r.URL.Path, "/api/push/snapshot/")
		if !isSafeSnapshotID(id) {
			http.Error(w, "Not found", http.StatusNotFound)
			return
		}
		path := snapshotDataPath(id)
		if _, err := os.Stat(path); err != nil {
			http.Error(w, "Not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/octet-stream")
		http.ServeFile(w, r, path)
		return
	default:
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
}
