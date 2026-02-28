package main

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
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
	fcm        *FCMService
	mu         sync.RWMutex
}

type VAPIDKeys struct {
	PrivateKey string `json:"privateKey"`
	PublicKey  string `json:"publicKey"`
}

type PushSubscriptionRequest struct {
	Transport string `json:"transport"`
	Endpoint  string `json:"endpoint"`
	Keys      struct {
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

const (
	pushTransportWebPush = "webpush"
	pushTransportFCM     = "fcm"
	pushKindJoin         = "join"
	pushKindInvite       = "invite"
)

func normalizePushTransport(input string) string {
	transport := strings.TrimSpace(strings.ToLower(input))
	switch transport {
	case "", pushTransportWebPush:
		return pushTransportWebPush
	case pushTransportFCM:
		return pushTransportFCM
	default:
		return ""
	}
}

func writeRoomIDValidationError(w http.ResponseWriter, roomID string) bool {
	if roomID == "" {
		http.Error(w, "Missing roomId", http.StatusBadRequest)
		return true
	}

	if err := validateRoomID(roomID); err != nil {
		if errors.Is(err, ErrRoomIDSecretMissing) {
			http.Error(w, "Room ID service unavailable", http.StatusServiceUnavailable)
			return true
		}
		http.Error(w, "Invalid roomId", http.StatusBadRequest)
		return true
	}

	return false
}

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
		transport TEXT NOT NULL DEFAULT 'webpush',
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
	_, _ = db.Exec("ALTER TABLE subscriptions ADD COLUMN transport TEXT DEFAULT 'webpush'")
	_, _ = db.Exec("UPDATE subscriptions SET transport = 'webpush' WHERE transport IS NULL OR transport = ''")

	// 2. Setup VAPID Keys
	keys, err := loadOrGenerateVAPIDKeys()
	if err != nil {
		return fmt.Errorf("failed to setup VAPID keys: %v", err)
	}

	fcmService, err := initFCMServiceFromEnv()
	if err != nil {
		return fmt.Errorf("failed to setup FCM service: %v", err)
	}

	pushService = &PushService{
		db:         db,
		privateKey: keys.PrivateKey,
		publicKey:  keys.PublicKey,
		fcm:        fcmService,
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
	if err := validateRoomID(roomID); err != nil {
		return err
	}

	transport := normalizePushTransport(sub.Transport)
	if transport == "" {
		return fmt.Errorf("unsupported push transport")
	}

	auth := strings.TrimSpace(sub.Keys.Auth)
	p256dh := strings.TrimSpace(sub.Keys.P256dh)
	if transport == pushTransportWebPush && (auth == "" || p256dh == "") {
		return fmt.Errorf("missing webpush key material")
	}

	stmt, err := s.db.Prepare("INSERT OR REPLACE INTO subscriptions(room_id, transport, endpoint, auth, p256dh, locale, enc_pubkey, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?)")
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

	_, err = stmt.Exec(roomID, transport, sub.Endpoint, auth, p256dh, locale, encKey, time.Now().UnixMilli())
	if err != nil {
		log.Printf("[PUSH] Failed to save subscription: %v", err)
		return err
	}
	log.Printf("[PUSH] Subscribed endpoint %s to room %s (transport: %s, locale: %s)", sub.Endpoint, roomID, transport, locale)
	return nil
}

func (s *PushService) Unsubscribe(roomID string, endpoint string) error {
	if err := validateRoomID(roomID); err != nil {
		return err
	}

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
	s.sendNotificationToRoom(roomID, excludeEndpoint, snapshotID, pushKindJoin)
}

func (s *PushService) SendInviteNotificationToRoom(roomID string, excludeEndpoint string) {
	s.sendNotificationToRoom(roomID, excludeEndpoint, "", pushKindInvite)
}

func (s *PushService) sendNotificationToRoom(roomID string, excludeEndpoint string, snapshotID string, kind string) {
	if err := validateRoomID(roomID); err != nil {
		log.Printf("[PUSH] Skipping notifications for invalid room %q: %v", roomID, err)
		return
	}

	rows, err := s.db.Query("SELECT id, endpoint, auth, p256dh, locale, COALESCE(transport, 'webpush') FROM subscriptions WHERE room_id = ?", roomID)
	if err != nil {
		log.Printf("[PUSH] Failed to query subscriptions for room %s: %v", roomID, err)
		return
	}
	defer rows.Close()

	type subData struct {
		ID        int
		Endpoint  string
		Auth      string
		P256dh    string
		Locale    string
		Transport string
	}
	var targets []subData

	for rows.Next() {
		var sd subData
		if err := rows.Scan(&sd.ID, &sd.Endpoint, &sd.Auth, &sd.P256dh, &sd.Locale, &sd.Transport); err != nil {
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
	if kind == pushKindJoin && snapshotID != "" && isSafeSnapshotID(snapshotID) {
		if meta, err := loadSnapshotMeta(snapshotID); err == nil {
			snapshotMeta = meta
		} else {
			log.Printf("[PUSH] Failed to load snapshot %s: %v", snapshotID, err)
		}
	}

	for _, target := range targets {
		go s.sendOne(roomID, target, snapshotID, snapshotMeta, kind)
	}
}

func getLocalizedMessage(locale string, kind string) (string, string) {
	// Simple mapping, can be expanded
	// Check prefix
	lang := locale
	if len(locale) > 2 {
		lang = locale[:2]
	}

	if kind == pushKindInvite {
		switch lang {
		case "ru":
			return "Serenada", "Вас позвали в комнату."
		case "es":
			return "Serenada", "Te invitaron a una sala."
		case "de":
			return "Serenada", "Du wurdest in einen Raum eingeladen."
		case "fr":
			return "Serenada", "Vous avez été invité dans une salle."
		default:
			return "Serenada", "You were invited to a room."
		}
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

func configuredPushHost() string {
	// Prefer deployment/domain fallbacks.
	candidates := []string{
		os.Getenv("DOMAIN"),
		os.Getenv("STUN_HOST"),
	}
	for _, candidate := range candidates {
		if normalized := normalizePushHost(candidate); normalized != "" {
			return normalized
		}
	}
	return ""
}

func normalizePushHost(raw string) string {
	normalized := strings.TrimSpace(raw)
	if normalized == "" {
		return ""
	}
	if strings.HasPrefix(normalized, "http://") || strings.HasPrefix(normalized, "https://") {
		if parsed, err := url.Parse(normalized); err == nil && parsed.Host != "" {
			normalized = parsed.Host
		}
	}
	normalized = strings.TrimSpace(normalized)
	normalized = strings.TrimSuffix(normalized, "/")
	if slash := strings.IndexRune(normalized, '/'); slash >= 0 {
		normalized = normalized[:slash]
	}
	return strings.TrimSpace(normalized)
}

func (s *PushService) sendOne(roomID string, target struct {
	ID        int
	Endpoint  string
	Auth      string
	P256dh    string
	Locale    string
	Transport string
}, snapshotID string, snapshotMeta *SnapshotMeta, kind string) {
	title, body := getLocalizedMessage(target.Locale, kind)
	host := configuredPushHost()

	// Payload
	payload := map[string]string{
		"title": title,
		"body":  body,
		"url":   fmt.Sprintf("/call/%s", roomID),
		"kind":  kind,
	}
	if host != "" {
		payload["host"] = host
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

	transport := normalizePushTransport(target.Transport)
	switch transport {
	case pushTransportFCM:
		s.sendOneFCM(roomID, target.Endpoint, payload)
	default:
		s.sendOneWebPush(roomID, target.Endpoint, target.Auth, target.P256dh, payload)
	}
}

func (s *PushService) sendOneWebPush(roomID string, endpoint string, auth string, p256dh string, payload map[string]string) {
	payloadBytes, _ := json.Marshal(payload)

	sub := &webpush.Subscription{
		Endpoint: endpoint,
		Keys: webpush.Keys{
			Auth:   auth,
			P256dh: p256dh,
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
		log.Printf("[PUSH] Failed to send web push to %s: %v", endpoint, err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode == 201 || resp.StatusCode == 200 {
		log.Printf("[PUSH] Successfully sent web push to %s (Status %d)", endpoint, resp.StatusCode)
	} else if resp.StatusCode == 410 || resp.StatusCode == 404 {
		// Subscription is gone, remove it
		log.Printf("[PUSH] Webpush subscription expired/gone (Status %d). Removing %s", resp.StatusCode, endpoint)
		s.Unsubscribe(roomID, endpoint)
	} else {
		log.Printf("[PUSH] Unexpected response from web push service: Status %d", resp.StatusCode)
	}
}

func (s *PushService) sendOneFCM(roomID string, token string, payload map[string]string) {
	if s.fcm == nil {
		log.Printf("[PUSH] FCM is not configured; skipping token %s", token)
		return
	}

	statusCode, body, err := s.fcm.SendDataMessage(token, payload)
	if err != nil {
		log.Printf("[PUSH] Failed to send FCM push to %s: %v", token, err)
		return
	}

	if statusCode >= 200 && statusCode < 300 {
		log.Printf("[PUSH] Successfully sent FCM push to %s (Status %d)", token, statusCode)
		return
	}

	if isFCMTokenInvalid(statusCode, body) {
		log.Printf("[PUSH] FCM token invalid (Status %d). Removing %s", statusCode, token)
		_ = s.Unsubscribe(roomID, token)
		return
	}

	log.Printf("[PUSH] Unexpected FCM response (Status %d): %s", statusCode, strings.TrimSpace(string(body)))
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

	roomId := strings.TrimSpace(r.URL.Query().Get("roomId"))
	if writeRoomIDValidationError(w, roomId) {
		return
	}

	if r.Method == "POST" {
		var sub PushSubscriptionRequest
		if err := json.NewDecoder(r.Body).Decode(&sub); err != nil {
			http.Error(w, "Invalid body", http.StatusBadRequest)
			return
		}
		if strings.TrimSpace(sub.Endpoint) == "" {
			http.Error(w, "Missing endpoint", http.StatusBadRequest)
			return
		}
		transport := normalizePushTransport(sub.Transport)
		if transport == "" {
			http.Error(w, "Unsupported push transport", http.StatusBadRequest)
			return
		}
		if transport == pushTransportWebPush {
			if strings.TrimSpace(sub.Keys.Auth) == "" || strings.TrimSpace(sub.Keys.P256dh) == "" {
				http.Error(w, "Missing webpush key material", http.StatusBadRequest)
				return
			}
		}
		if len(sub.EncPublicKey) > 4096 {
			http.Error(w, "Encryption key too large", http.StatusBadRequest)
			return
		}
		if len(sub.EncPublicKey) > 0 && !json.Valid(sub.EncPublicKey) {
			http.Error(w, "Invalid encryption key", http.StatusBadRequest)
			return
		}
		sub.Transport = transport

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

	roomId := strings.TrimSpace(r.URL.Query().Get("roomId"))
	if writeRoomIDValidationError(w, roomId) {
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

func handlePushInvite(w http.ResponseWriter, r *http.Request) {
	if r.Method == "OPTIONS" {
		return
	}
	if r.Method != "POST" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	roomID := strings.TrimSpace(r.URL.Query().Get("roomId"))
	if writeRoomIDValidationError(w, roomID) {
		return
	}

	var body struct {
		Endpoint string `json:"endpoint"`
	}
	decoder := json.NewDecoder(io.LimitReader(r.Body, 4096))
	if err := decoder.Decode(&body); err != nil && !errors.Is(err, io.EOF) {
		http.Error(w, "Invalid body", http.StatusBadRequest)
		return
	}

	if pushService == nil {
		http.Error(w, "Push service unavailable", http.StatusServiceUnavailable)
		return
	}

	go pushService.SendInviteNotificationToRoom(roomID, strings.TrimSpace(body.Endpoint))
	w.WriteHeader(http.StatusOK)
}

func handlePushNotify(hub *Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "OPTIONS" {
			return
		}
		if r.Method != "POST" {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		roomID := strings.TrimSpace(r.URL.Query().Get("roomId"))
		if writeRoomIDValidationError(w, roomID) {
			return
		}

		var body struct {
			CID          string `json:"cid"`
			SnapshotID   string `json:"snapshotId"`
			PushEndpoint string `json:"pushEndpoint"`
		}
		decoder := json.NewDecoder(io.LimitReader(r.Body, 4096))
		if err := decoder.Decode(&body); err != nil && !errors.Is(err, io.EOF) {
			http.Error(w, "Invalid body", http.StatusBadRequest)
			return
		}

		cid := strings.TrimSpace(body.CID)
		if cid == "" {
			http.Error(w, "Missing cid", http.StatusBadRequest)
			return
		}

		if !hub.IsClientInRoom(roomID, cid) {
			http.Error(w, "Not a room participant", http.StatusForbidden)
			return
		}

		if pushService == nil {
			http.Error(w, "Push service unavailable", http.StatusServiceUnavailable)
			return
		}

		go pushService.SendNotificationToRoom(roomID, strings.TrimSpace(body.PushEndpoint), strings.TrimSpace(body.SnapshotID))
		w.WriteHeader(http.StatusOK)
	}
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
