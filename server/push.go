package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/SherClockHolmes/webpush-go"
	_ "github.com/mattn/go-sqlite3"
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
	Locale string `json:"locale"`
}

var pushService *PushService

func getDataDir() string {
	dataDir := os.Getenv("DATA_DIR")
	if dataDir == "" {
		dataDir = "."
	}
	return dataDir
}

func InitPushService() error {
	dataDir := getDataDir()
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		return fmt.Errorf("failed to create data dir: %v", err)
	}

	// 1. Setup SQLite
	dbPath := fmt.Sprintf("%s/subscriptions.db", dataDir)
	db, err := sql.Open("sqlite3", dbPath)
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
		UNIQUE(room_id, endpoint)
	);`

	if _, err := db.Exec(createTableSQL); err != nil {
		return fmt.Errorf("failed to create table: %v", err)
	}

	// Migration: Add locale column if not exists (simplistic check)
	// Ignore error if column exists
	_, _ = db.Exec("ALTER TABLE subscriptions ADD COLUMN locale TEXT DEFAULT 'en'")

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
	stmt, err := s.db.Prepare("INSERT OR REPLACE INTO subscriptions(room_id, endpoint, auth, p256dh, locale, created_at) VALUES(?, ?, ?, ?, ?, ?)")
	if err != nil {
		return err
	}
	defer stmt.Close()

	locale := sub.Locale
	if locale == "" {
		locale = "en"
	}

	_, err = stmt.Exec(roomID, sub.Endpoint, sub.Keys.Auth, sub.Keys.P256dh, locale, time.Now().UnixMilli())
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

func (s *PushService) SendNotificationToRoom(roomID string, excludeEndpoint string) {
	rows, err := s.db.Query("SELECT endpoint, auth, p256dh, locale FROM subscriptions WHERE room_id = ?", roomID)
	if err != nil {
		log.Printf("[PUSH] Failed to query subscriptions for room %s: %v", roomID, err)
		return
	}
	defer rows.Close()

	type subData struct {
		Endpoint string
		Auth     string
		P256dh   string
		Locale   string
	}
	var targets []subData

	for rows.Next() {
		var sd subData
		if err := rows.Scan(&sd.Endpoint, &sd.Auth, &sd.P256dh, &sd.Locale); err != nil {
			log.Printf("[PUSH] Scan error: %v", err)
			continue
		}
		if sd.Endpoint == excludeEndpoint {
			continue
		}
		targets = append(targets, sd)
	}

	log.Printf("[PUSH] Found %d subscribers for room %s", len(targets), roomID)

	// Send in parallel or just loop
	for _, target := range targets {
		go s.sendOne(roomID, target.Endpoint, target.Auth, target.P256dh, target.Locale)
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

func (s *PushService) sendOne(roomID, endpoint, auth, p256dh, locale string) {
	title, body := getLocalizedMessage(locale)

	// Payload
	payload := map[string]string{
		"title": title,
		"body":  body,
		"url":   fmt.Sprintf("/call/%s", roomID),
	}
	payloadBytes, _ := json.Marshal(payload)

	sub := &webpush.Subscription{
		Endpoint: endpoint,
		Keys: webpush.Keys{
			Auth:   auth,
			P256dh: p256dh,
		},
	}

	// Send Notification
	resp, err := webpush.SendNotification(payloadBytes, sub, &webpush.Options{
		Subscriber:      "mailto:admin@connected.app", // Should probably be configurable
		VAPIDPublicKey:  s.publicKey,
		VAPIDPrivateKey: s.privateKey,
		TTL:             60, // 1 minute TTL
	})
	if err != nil {
		log.Printf("[PUSH] Failed to send to %s: %v", endpoint, err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode == 201 || resp.StatusCode == 200 {
		log.Printf("[PUSH] Successfully sent notification to %s (Status %d)", endpoint, resp.StatusCode)
	} else if resp.StatusCode == 410 || resp.StatusCode == 404 {
		// Subscription is gone, remove it
		log.Printf("[PUSH] Subscription expired/gone (Status %d). Removing %s", resp.StatusCode, endpoint)
		s.Unsubscribe(roomID, endpoint)
	} else {
		log.Printf("[PUSH] Unexpected response from push service: Status %d", resp.StatusCode)
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
