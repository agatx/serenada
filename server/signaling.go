package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"os"
	"sync"
	"time"

	"serenada/server/internal/stats"
)

const maxMessageSize = 65536 // 64KB

// TURN token TTL: 30 minutes. Clients proactively refresh at 80% of TTL.
const turnTokenTTL = 30 * time.Minute

// issueReconnectToken generates an HMAC proof that allows a client to reclaim
// its CID on reconnect. Format: hex(HMAC-SHA256(secret, cid|rid)).
// The token is bound to (cid, rid) — NOT session id — because the session id
// changes on every reconnect.
func issueReconnectToken(cid, rid string) string {
	secret := os.Getenv("TURN_TOKEN_SECRET")
	if secret == "" {
		secret = os.Getenv("TURN_SECRET")
	}
	if secret == "" {
		return ""
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(cid + "|" + rid))
	return hex.EncodeToString(mac.Sum(nil))
}

// validateReconnectToken checks that the provided token matches the expected HMAC.
func validateReconnectToken(token, cid, rid string) bool {
	if token == "" {
		return false
	}
	expected := issueReconnectToken(cid, rid)
	if expected == "" {
		// No secret configured — allow legacy clients (backwards compatible)
		return true
	}
	return hmac.Equal([]byte(expected), []byte(token))
}

type TransportKind string

const (
	TransportWS  TransportKind = "ws"
	TransportSSE TransportKind = "sse"
)

// Protocol structures
type Message struct {
	V       int             `json:"v"`
	Type    string          `json:"type"`
	RID     string          `json:"rid,omitempty"`
	SID     string          `json:"sid,omitempty"`
	CID     string          `json:"cid,omitempty"`
	To      string          `json:"to,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

type Participant struct {
	CID      string `json:"cid"`
	JoinedAt int64  `json:"joinedAt,omitempty"`
}

type Hub struct {
	rooms        map[string]*Room
	watchers     map[string]map[*Client]bool // roomID -> set of clients
	mu           sync.RWMutex
	clients      map[*Client]bool
	clientsBySID map[string]*Client
}

type Room struct {
	RID          string
	Participants map[*Client]string // client -> cid
	HostCID      string
	mu           sync.Mutex
}

type Client struct {
	hub       *Hub
	send      chan []byte
	sid       string
	cid       string // assigned on join
	rid       string // current room
	ip        string
	replaced  bool
	lastSeen  int64
	transport TransportKind
}

func newHub() *Hub {
	return &Hub{
		rooms:        make(map[string]*Room),
		watchers:     make(map[string]map[*Client]bool),
		clients:      make(map[*Client]bool),
		clientsBySID: make(map[string]*Client),
	}
}

func (h *Hub) registerClient(c *Client) {
	h.mu.Lock()
	h.clients[c] = true
	h.clientsBySID[c.sid] = c
	h.mu.Unlock()
}

func (h *Hub) getClientBySID(sid string) *Client {
	h.mu.RLock()
	client := h.clientsBySID[sid]
	h.mu.RUnlock()
	return client
}

func (h *Hub) isClientActive(c *Client) bool {
	h.mu.RLock()
	_, exists := h.clients[c]
	h.mu.RUnlock()
	return exists
}

// IsClientInRoom checks whether a client with the given CID is a participant
// in the specified room. Thread-safe for use from HTTP handlers.
func (h *Hub) IsClientInRoom(roomID, cid string) bool {
	h.mu.RLock()
	room, exists := h.rooms[roomID]
	h.mu.RUnlock()
	if !exists {
		return false
	}
	room.mu.Lock()
	defer room.mu.Unlock()
	for _, clientCID := range room.Participants {
		if clientCID == cid {
			return true
		}
	}
	return false
}

func (h *Hub) replaceClient(oldClient, newClient *Client) {
	h.mu.Lock()
	delete(h.clients, oldClient)
	h.clients[newClient] = true
	h.clientsBySID[newClient.sid] = newClient
	for _, clientSet := range h.watchers {
		if clientSet[oldClient] {
			delete(clientSet, oldClient)
			clientSet[newClient] = true
		}
	}
	h.mu.Unlock()

	if oldClient.rid != "" {
		h.mu.RLock()
		room := h.rooms[oldClient.rid]
		h.mu.RUnlock()
		if room != nil {
			room.mu.Lock()
			if cid, ok := room.Participants[oldClient]; ok {
				delete(room.Participants, oldClient)
				room.Participants[newClient] = cid
				newClient.cid = cid
				newClient.rid = oldClient.rid
			}
			room.mu.Unlock()
		}
	}

	oldClient.replaced = true
}

func (c *Client) sendMessage(msg interface{}) {
	b, err := json.Marshal(msg)
	if err != nil {
		log.Printf("json error: %v", err)
		return
	}

	defer func() {
		if r := recover(); r != nil {
			// Transport send channel may be closed during forced cleanup.
			stats.IncSendQueueDrop()
		}
	}()

	select {
	case c.send <- b:
		stats.IncMessageTX(extractMessageType(msg))
	default:
		// Buffer full. We keep current behavior (drop), but account for it.
		stats.IncSendQueueDrop()
	}
}

// Logic

func (h *Hub) handleMessage(c *Client, msgBytes []byte) {
	if !h.isClientActive(c) {
		return
	}

	var msg Message
	if err := json.Unmarshal(msgBytes, &msg); err != nil {
		stats.IncMessageRX("invalid_json")
		c.sendError(msg.RID, "BAD_REQUEST", "Invalid JSON")
		return
	}

	stats.IncMessageRX(msg.Type)

	if msg.V != 1 {
		c.sendError(msg.RID, "UNSUPPORTED_VERSION", "Only version 1 is supported")
		return
	}

	switch msg.Type {
	case "ping":
		c.sendMessage(Message{V: 1, Type: "pong"})
		return
	case "join":
		log.Printf("[JOIN] Client %s joining room %s", c.sid, msg.RID)
		if c.rid != "" {
			h.removeClientFromRoom(c)
		}
		h.handleJoin(c, msg)
	case "leave":
		log.Printf("[LEAVE] Client %s leaving", c.cid)
		h.handleLeave(c, msg)
	case "end_room":
		log.Printf("[END_ROOM] Client %s ending room %s", c.cid, c.rid)
		h.handleEndRoom(c, msg)
	case "watch_rooms":
		h.handleWatchRooms(c, msg)
	case "turn-refresh":
		h.handleTurnRefresh(c, msg)
	case "offer", "answer", "ice":
		// log.Printf("[%s] Relay from %s to room %s", msg.Type, c.cid, c.rid) // verbose
		h.handleRelay(c, msg)
	default:
		log.Printf("[UNKNOWN] Unknown message type: %s", msg.Type)
	}
}

func (h *Hub) handleJoin(c *Client, msg Message) {
	joinStartedAt := time.Now()

	rid := msg.RID
	if rid == "" {
		c.sendError("", "BAD_REQUEST", "Missing roomId")
		return
	}

	if err := validateRoomID(rid); err != nil {
		if errors.Is(err, ErrRoomIDSecretMissing) {
			c.sendError(rid, "SERVER_NOT_CONFIGURED", "Room ID service is not configured")
			return
		}
		c.sendError(rid, "INVALID_ROOM_ID", "Room ID must be a valid room token")
		return
	}

	h.mu.Lock()
	room, exists := h.rooms[rid]
	if !exists {
		log.Printf("[JOIN] Creating new room %s", rid)
		room = &Room{
			RID:          rid,
			Participants: make(map[*Client]string),
		}
		h.rooms[rid] = room
	}
	h.mu.Unlock()

	room.mu.Lock()
	var joinPayload struct {
		ReconnectCID   string `json:"reconnectCid"`
		ReconnectToken string `json:"reconnectToken"`
	}
	if len(msg.Payload) > 0 {
		if err := json.Unmarshal(msg.Payload, &joinPayload); err != nil {
			log.Printf("[JOIN] Failed to parse payload: %v", err)
		}
	}

	reconnectCID := joinPayload.ReconnectCID
	reconnectToken := joinPayload.ReconnectToken
	reusedCID := false

	// Single-pass ghost eviction: find ghost client with reconnectCID, mark for removal under room lock
	var ghostToEvict *Client
	if reconnectCID != "" {
		// Validate reconnectToken if provided (backwards compatible: legacy clients without token still allowed)
		if reconnectToken != "" && !validateReconnectToken(reconnectToken, reconnectCID, rid) {
			room.mu.Unlock()
			log.Printf("[JOIN] Invalid reconnectToken for CID %s from client %s", reconnectCID, c.sid)
			c.sendError(rid, "INVALID_RECONNECT_TOKEN", "Reconnect token validation failed")
			return
		}

		for client, cid := range room.Participants {
			if cid == reconnectCID {
				ghostToEvict = client
				break
			}
		}
		if ghostToEvict != nil {
			log.Printf("[JOIN] Reconnection detected for CID %s. Evicting ghost client %s", reconnectCID, ghostToEvict.sid)
			// Remove ghost from room under room lock (atomic)
			delete(room.Participants, ghostToEvict)
			ghostToEvict.cid = ""
			ghostToEvict.rid = ""
			reusedCID = true
			// Note: room.HostCID is intentionally left unchanged so that
			// the host assignment is preserved across reconnects via the
			// reused client ID (reconnectCID).
		}
	}

	// Room full check (after ghost eviction)
	if len(room.Participants) >= 2 {
		room.mu.Unlock()
		log.Printf("[JOIN] Room %s is full", rid)
		c.sendError(rid, "ROOM_FULL", "Room is full")
		return
	}

	// Deferred hub-level cleanup of ghost outside room lock to avoid deadlock
	if ghostToEvict != nil {
		room.mu.Unlock()
		h.cleanupEvictedClient(ghostToEvict)
		room.mu.Lock()
		if len(room.Participants) >= 2 {
			room.mu.Unlock()
			log.Printf("[JOIN] Room %s is full after ghost cleanup", rid)
			c.sendError(rid, "ROOM_FULL", "Room is full")
			return
		}
	}

	cid := generateID("C-")
	if reusedCID && reconnectCID != "" {
		cid = reconnectCID
	}
	c.cid = cid
	c.rid = rid
	room.Participants[c] = cid

	if room.HostCID == "" {
		room.HostCID = cid
	}

	log.Printf("[JOIN] Client %s assigned CID %s in room %s. Host: %s", c.sid, cid, rid, room.HostCID)

	// Send 'joined'
	participants := []Participant{}
	for _, id := range room.Participants {
		participants = append(participants, Participant{CID: id, JoinedAt: time.Now().UnixMilli()})
	}

	room.mu.Unlock() // <--- CRITICAL FIX: Unlock before broadcast/send to avoid deadlock/blocking

	payload := map[string]interface{}{
		"hostCid":      room.HostCID,
		"participants": participants,
	}

	// Include TURN token in joined response (gated by valid room ID)
	token, expiresAt, err := issueTurnToken(turnTokenTTL, turnTokenKindCall)
	if err != nil {
		log.Printf("[TURN] Failed to issue token: %v", err)
	} else {
		payload["turnToken"] = token
		payload["turnTokenExpiresAt"] = expiresAt.Unix()
		payload["turnTokenTTLMs"] = int64(turnTokenTTL / time.Millisecond)
	}

	// Include reconnectToken for authenticated reconnection
	if rt := issueReconnectToken(cid, rid); rt != "" {
		payload["reconnectToken"] = rt
	}

	payloadBytes, _ := json.Marshal(payload)

	c.sendMessage(Message{
		V:       1,
		Type:    "joined",
		RID:     rid,
		SID:     c.sid,
		CID:     cid,
		Payload: payloadBytes,
	})
	stats.RecordJoinLatency(time.Since(joinStartedAt))

	// Broadcast room_state to others
	h.broadcastRoomState(room)

	// Notify watchers
	h.broadcastRoomStatusUpdate(rid)
}

func (h *Hub) handleTurnRefresh(c *Client, msg Message) {
	if c.rid == "" {
		c.sendError(msg.RID, "NOT_IN_ROOM", "Must be in a room to refresh TURN credentials")
		return
	}

	token, expiresAt, err := issueTurnToken(turnTokenTTL, turnTokenKindCall)
	if err != nil {
		log.Printf("[TURN-REFRESH] Failed to issue token for %s: %v", c.cid, err)
		c.sendError(msg.RID, "TURN_REFRESH_FAILED", "Failed to refresh TURN credentials")
		return
	}

	payload := map[string]interface{}{
		"turnToken":          token,
		"turnTokenExpiresAt": expiresAt.Unix(),
		"turnTokenTTLMs":     int64(turnTokenTTL / time.Millisecond),
	}
	payloadBytes, _ := json.Marshal(payload)

	c.sendMessage(Message{
		V:       1,
		Type:    "turn-refreshed",
		RID:     c.rid,
		Payload: payloadBytes,
	})
	log.Printf("[TURN-REFRESH] Refreshed TURN credentials for client %s (CID: %s) in room %s", c.sid, c.cid, c.rid)
}

func (h *Hub) handleLeave(c *Client, msg Message) {
	if c.rid == "" {
		return
	}
	h.removeClientFromRoom(c)
}

func (h *Hub) handleEndRoom(c *Client, msg Message) {
	rid := c.rid
	if rid == "" {
		return
	}

	h.mu.RLock()
	room, exists := h.rooms[rid]
	h.mu.RUnlock()

	if !exists {
		log.Printf("[END_ROOM] Client %s tried to end non-existent room %s", c.sid, rid)
		return
	}

	room.mu.Lock()

	if room.HostCID != c.cid {
		room.mu.Unlock()
		c.sendError(rid, "NOT_HOST", "Only host can end room")
		log.Printf("[END_ROOM] Client %s (CID: %s) tried to end room %s but is not host (Host: %s)", c.sid, c.cid, rid, room.HostCID)
		return
	}

	// Collect clients to notify
	clients := make([]*Client, 0, len(room.Participants))
	for client := range room.Participants {
		clients = append(clients, client)
	}

	room.mu.Unlock() // Unlock before sending

	log.Printf("[END_ROOM] Host %s ending room %s. Notifying %d clients", c.cid, rid, len(clients))

	// Broadcast room_ended
	endPayload, _ := json.Marshal(map[string]string{
		"by":     c.cid,
		"reason": "host_ended",
	})
	endMsg := Message{
		V:       1,
		Type:    "room_ended",
		RID:     rid,
		Payload: endPayload,
	}

	for _, client := range clients {
		client.sendMessage(endMsg)
		// Reset client state
		// Note: modifying client struct is dangerous if read concurrently.
		// Client struct fields `rid`/`cid` are read in readPump/handle handlers.
		// Ideally we should protect client fields or just rely on them sending new join.
		// For MVP, not clearing them is safeish if we assume they will be overwritten on next join.
		// Or we can clear them but we need a lock on client? Client has no lock.
		// Let's just leave them stale, it's fine.
	}

	// Clear room
	// Re-acquire lock to clear participants? Or just delete room.
	// If we delete room from hub, existing clients can't find it.

	// Remove room from hub
	h.mu.Lock()
	delete(h.rooms, rid)
	h.mu.Unlock()

	// Also clear participants in room to help GC?
	room.mu.Lock()
	room.Participants = make(map[*Client]string)
	room.HostCID = ""
	room.mu.Unlock()

	// Notify watchers
	h.broadcastRoomStatusUpdate(rid)
}

func (h *Hub) handleRelay(c *Client, msg Message) {
	if c.rid == "" {
		log.Printf("[RELAY] Client %s (CID: %s) tried to relay but not in a room", c.sid, c.cid)
		return
	}

	h.mu.RLock()
	room, exists := h.rooms[c.rid]
	h.mu.RUnlock()

	if !exists {
		log.Printf("[RELAY] Client %s (CID: %s) tried to relay in non-existent room %s", c.sid, c.cid, c.rid)
		return
	}

	room.mu.Lock()
	defer room.mu.Unlock()

	// Check if sender is in room
	if _, ok := room.Participants[c]; !ok {
		log.Printf("[RELAY] Client %s (CID: %s) tried to relay in room %s but is not a participant", c.sid, c.cid, c.rid)
		return
	}

	// Relay to other participant(s). Protocol says "to" is optional or required.
	// MVP: Relay to all OTHER participants.

	// We need to wrap payload with "from"
	// But Message.Payload is RawMessage.
	// The protocol says: Server -> client (relay): { payload: { from: "...", ...original_payload... } }
	// This implies we need to unmarshal payload, add from, and marshal back.
	// Or more simply: construct a new map.

	var rawPayload map[string]interface{}
	if err := json.Unmarshal(msg.Payload, &rawPayload); err != nil {
		rawPayload = make(map[string]interface{})
		log.Printf("[RELAY] Client %s (CID: %s) sent invalid payload for type %s: %v", c.sid, c.cid, msg.Type, err)
	}
	rawPayload["from"] = c.cid

	newPayload, _ := json.Marshal(rawPayload)

	relayMsg := Message{
		V:       1,
		Type:    msg.Type,
		RID:     msg.RID,
		Payload: newPayload,
	}

	relayedCount := 0
	for client, cid := range room.Participants {
		if cid != c.cid {
			// Check 'to' if present? Protocol says "to" is optional/recommended.
			// Implementing direct targeting if "to" is present
			if msg.To != "" && msg.To != cid {
				continue
			}
			client.sendMessage(relayMsg)
			relayedCount++
		}
	}
	log.Printf("[RELAY] Client %s (CID: %s) relayed %s message to %d participants in room %s", c.sid, c.cid, msg.Type, relayedCount, c.rid)
}

func (h *Hub) disconnectClient(c *Client) {
	log.Printf("[DISCONNECT] Client %s disconnected", c.sid)
	h.mu.Lock()
	_, existed := h.clients[c]
	if !existed {
		h.mu.Unlock()
		return
	}

	delete(h.clients, c)
	delete(h.clientsBySID, c.sid)
	// Remove from all watchers
	for rid, clientSet := range h.watchers {
		delete(clientSet, c)
		if len(clientSet) == 0 {
			delete(h.watchers, rid)
		}
	}
	h.mu.Unlock()

	switch c.transport {
	case TransportWS:
		stats.AddActiveWSClients(-1)
	case TransportSSE:
		stats.AddActiveSSEClients(-1)
	}

	if c.rid != "" {
		h.removeClientFromRoom(c)
	}
	closeClientSend(c.send)
}

func (h *Hub) removeClientFromRoom(c *Client) {
	log.Printf("[REMOVE_FROM_ROOM] Client %s (CID: %s) being removed from room %s", c.sid, c.cid, c.rid)
	h.mu.Lock()
	room, exists := h.rooms[c.rid]
	h.mu.Unlock()

	if !exists {
		log.Printf("[REMOVE_FROM_ROOM] Room %s not found for client %s", c.rid, c.sid)
		return
	}

	rid := c.rid // Store RID for broadcast
	room.mu.Lock()
	delete(room.Participants, c)
	log.Printf("[REMOVE_FROM_ROOM] Client %s (CID: %s) removed from room %s. Remaining participants: %d", c.sid, c.cid, c.rid, len(room.Participants))

	// Manage Host
	if room.HostCID == c.cid {
		// Transfer host to next available
		newHost := ""
		for _, cid := range room.Participants {
			newHost = cid
			break // pick any
		}
		room.HostCID = newHost
		if newHost != "" {
			log.Printf("[REMOVE_FROM_ROOM] Host %s left room %s. New host: %s", c.cid, c.rid, newHost)
		} else {
			// No participants left, host is empty
		}
	}

	isEmpty := len(room.Participants) == 0
	room.mu.Unlock()

	c.rid = ""
	c.cid = ""

	if isEmpty {
		log.Printf("[REMOVE_FROM_ROOM] Room %s is now empty. Deleting room.", rid)
		h.mu.Lock()
		delete(h.rooms, rid)
		h.mu.Unlock()
	} else {
		h.broadcastRoomState(room)
	}

	// Notify watchers
	h.broadcastRoomStatusUpdate(rid)
}

func (h *Hub) broadcastRoomState(room *Room) {
	// Must be called without room lock!

	room.mu.Lock()
	participants := []Participant{}
	for _, cid := range room.Participants {
		participants = append(participants, Participant{CID: cid})
	}
	hostCid := room.HostCID
	rid := room.RID
	// Collect clients
	clients := make([]*Client, 0, len(room.Participants))
	for client := range room.Participants {
		clients = append(clients, client)
	}
	room.mu.Unlock()

	payload := map[string]interface{}{
		"hostCid":      hostCid,
		"participants": participants,
	}
	payloadBytes, _ := json.Marshal(payload)

	log.Printf("[BROADCAST] Room State for %s: %d participants", rid, len(participants))

	msg := Message{
		V:       1,
		Type:    "room_state",
		RID:     rid,
		Payload: payloadBytes,
	}

	for _, client := range clients {
		client.sendMessage(msg)
	}
}

func (c *Client) sendError(rid, code, message string) {
	payload, _ := json.Marshal(map[string]interface{}{
		"code":    code,
		"message": message,
	})
	c.sendMessage(Message{
		V:       1,
		Type:    "error",
		RID:     rid,
		Payload: payload,
	})
}

func generateID(prefix string) string {
	b := make([]byte, 8)
	rand.Read(b)
	return prefix + hex.EncodeToString(b)
}

// cleanupEvictedClient performs hub-level cleanup for a ghost client that was already
// removed from its room's Participants map. This must be called outside the room lock.
func (h *Hub) cleanupEvictedClient(ghost *Client) {
	h.mu.Lock()
	delete(h.clients, ghost)
	delete(h.clientsBySID, ghost.sid)
	for rid, clientSet := range h.watchers {
		delete(clientSet, ghost)
		if len(clientSet) == 0 {
			delete(h.watchers, rid)
		}
	}
	h.mu.Unlock()

	switch ghost.transport {
	case TransportWS:
		stats.AddActiveWSClients(-1)
	case TransportSSE:
		stats.AddActiveSSEClients(-1)
	}

	closeClientSend(ghost.send)
}

func closeClientSend(ch chan []byte) {
	defer func() {
		_ = recover()
	}()
	close(ch)
}

func extractMessageType(msg interface{}) string {
	switch v := msg.(type) {
	case Message:
		if v.Type != "" {
			return v.Type
		}
	case *Message:
		if v != nil && v.Type != "" {
			return v.Type
		}
	}

	return "unknown"
}

func (h *Hub) refreshStatsGauges() {
	h.mu.RLock()
	defer h.mu.RUnlock()

	stats.SetActiveClients(int64(len(h.clients)))
	stats.SetActiveRooms(int64(len(h.rooms)))
	stats.SetWatcherRooms(int64(len(h.watchers)))

	var subscriptions int64
	for _, clientSet := range h.watchers {
		subscriptions += int64(len(clientSet))
	}
	stats.SetWatcherSubscriptions(subscriptions)
}

func (h *Hub) handleWatchRooms(c *Client, msg Message) {
	var payload struct {
		RIDs []string `json:"rids"`
	}
	if err := json.Unmarshal(msg.Payload, &payload); err != nil {
		c.sendError(msg.RID, "BAD_REQUEST", "Invalid payload")
		return
	}

	h.mu.Lock()
	status := make(map[string]int)
	for _, rid := range payload.RIDs {
		if err := validateRoomID(rid); err != nil {
			continue
		}
		// Add to watchers
		if h.watchers[rid] == nil {
			h.watchers[rid] = make(map[*Client]bool)
		}
		h.watchers[rid][c] = true

		// Get current count
		if room, ok := h.rooms[rid]; ok {
			room.mu.Lock()
			status[rid] = len(room.Participants)
			room.mu.Unlock()
		} else {
			status[rid] = 0
		}
	}
	h.mu.Unlock()

	statusBytes, _ := json.Marshal(status)
	c.sendMessage(Message{
		V:       1,
		Type:    "room_statuses",
		Payload: statusBytes,
	})
}

func (h *Hub) broadcastRoomStatusUpdate(rid string) {
	h.mu.RLock()
	clients, exists := h.watchers[rid]
	if !exists {
		h.mu.RUnlock()
		return
	}

	// Get current count
	count := 0
	if room, ok := h.rooms[rid]; ok {
		room.mu.Lock()
		count = len(room.Participants)
		room.mu.Unlock()
	}
	h.mu.RUnlock()

	payload, _ := json.Marshal(map[string]interface{}{
		"rid":   rid,
		"count": count,
	})

	msg := Message{
		V:       1,
		Type:    "room_status_update",
		Payload: payload,
	}

	// Copy clients to avoid holding hub lock while sending
	h.mu.RLock()
	targets := make([]*Client, 0, len(clients))
	for client := range clients {
		targets = append(targets, client)
	}
	h.mu.RUnlock()

	for _, client := range targets {
		client.sendMessage(msg)
	}
}
