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
	"strings"
	"sync"
	"time"

	"serenada/server/internal/stats"
)

const maxMessageSize = 65536 // 64KB
const maxDisplayNameLength = 40

// TURN token TTL for call credentials: 15 minutes.
// Clients proactively refresh at 80% of TTL (~12 min). Both Cloudflare and coturn
// call credentials are issued with a matching 15-minute TTL. Diagnostic tokens
// use a shorter TTL (5 seconds).
const turnTokenTTL = 15 * time.Minute

// suspendHardEvictionTimeout is how long a participant record is preserved after
// its signaling transport drops. During this window the participant stays in the
// room (marked ConnectionStatus="suspended") so that existing peer connections
// are NOT torn down, and the client can reconnect-with-CID to reclaim its spot.
// Established media continues to flow independent of signaling. After this
// timeout the record is evicted and peers tear down.
const suspendHardEvictionTimeout = 10 * time.Minute

// ConnectionStatus values broadcast in room_state/joined participant entries.
// Omitted when "active" (backward compatible with older clients).
const (
	connectionStatusActive    = "active"
	connectionStatusSuspended = "suspended"
)

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

// Participant is the wire-format entry broadcast to clients in joined/room_state.
type Participant struct {
	CID              string `json:"cid"`
	JoinedAt         int64  `json:"joinedAt,omitempty"`
	DisplayName      string `json:"displayName,omitempty"`
	AudioEnabled     *bool  `json:"audioEnabled,omitempty"`
	VideoEnabled     *bool  `json:"videoEnabled,omitempty"`
	ConnectionStatus string `json:"connectionStatus,omitempty"` // "suspended" when transport detached; omitted (= "active") otherwise
}

// roomParticipant is the server-side stable participant record keyed by CID.
// Identity (CID + metadata) is decoupled from the current live transport
// (Client) so that a transport drop does NOT remove the participant from the
// room — it just detaches Client and marks the record suspended until either a
// reconnect reattaches a new Client or hardEvictionTimer fires.
type roomParticipant struct {
	CID          string
	JoinedAt     int64
	DisplayName  string
	AudioEnabled *bool
	VideoEnabled *bool
	// Client is the currently attached transport. Nil when suspended.
	Client *Client
	// SuspendedAt is the unix-nano timestamp at which Client was last detached.
	// Zero when the participant is active.
	SuspendedAt int64
	// hardEvictionTimer fires after suspendHardEvictionTimeout if the participant
	// has not reattached. It calls hardEvictSuspended to remove the record.
	hardEvictionTimer *time.Timer
}

type Hub struct {
	rooms                map[string]*Room
	watchers             map[string]map[*Client]bool // roomID -> set of clients
	mu                   sync.RWMutex
	clients              map[*Client]bool
	clientsBySID         map[string]*Client
	maxParticipantsLimit int // server-wide ceiling for room capacity
}

type Room struct {
	RID string
	// byCID is the primary, stable participant index. Entries may be suspended
	// (Client == nil) — those participants still count toward room occupancy
	// and are included in room_state broadcasts with ConnectionStatus="suspended".
	byCID map[string]*roomParticipant
	// byClient is a reverse index for active (non-suspended) participants only.
	// Suspended participants are absent from this map. It exists so that relay
	// handlers can quickly resolve the sender's CID from the receiving *Client.
	byClient                 map[*Client]string
	HostCID                  string
	MaxParticipants          int  // effective room capacity; group-capable rooms stay provisional at 2 until participant #2 joins
	RequestedMaxParticipants int  // creator's requested ceiling, clamped by creator capability and server ceiling
	CapacityLocked           bool // once true, MaxParticipants is final for the room lifetime
	mu                       sync.Mutex
}

// Room helper methods. All assume the caller holds room.mu unless noted.

// participantCount returns the total number of participants in the room,
// including suspended ones. Used for capacity enforcement — suspended
// participants still hold their slot until hard eviction.
func (r *Room) participantCount() int { return len(r.byCID) }

// cidForClient resolves a live *Client to its CID in this room.
// Returns "" if the client is not currently attached to any participant
// (e.g., never joined or already detached via suspend).
func (r *Room) cidForClient(c *Client) string { return r.byClient[c] }

// participantByCID returns the participant record for the given CID, or nil
// if no participant with that CID exists in the room.
func (r *Room) participantByCID(cid string) *roomParticipant { return r.byCID[cid] }

// activeClients returns the list of currently-attached (non-suspended)
// *Client pointers in arbitrary order. Useful for broadcast/relay iteration.
func (r *Room) activeClients() []*Client {
	out := make([]*Client, 0, len(r.byClient))
	for client := range r.byClient {
		out = append(out, client)
	}
	return out
}

// attachParticipant inserts a new active participant for (cid, client) and
// stamps JoinedAt. Called only during a fresh join (not a reconnect).
func (r *Room) attachParticipant(cid string, client *Client, joinedAtMs int64) *roomParticipant {
	p := &roomParticipant{
		CID:      cid,
		JoinedAt: joinedAtMs,
		Client:   client,
	}
	r.byCID[cid] = p
	r.byClient[client] = cid
	return p
}

// reattachClient transitions a suspended participant back to active by
// attaching a new live *Client. Caller must ensure the old Client pointer
// was previously detached via detachClient or evictClient.
func (r *Room) reattachClient(p *roomParticipant, client *Client) {
	p.Client = client
	p.SuspendedAt = 0
	if p.hardEvictionTimer != nil {
		p.hardEvictionTimer.Stop()
		p.hardEvictionTimer = nil
	}
	r.byClient[client] = p.CID
}

// detachClient detaches the attached *Client from the participant record
// without removing the record from the room. Subsequent broadcasts will
// emit ConnectionStatus="suspended" for this participant. Caller must NOT
// call this on a participant whose Client is already nil.
func (r *Room) detachClient(p *roomParticipant) {
	if p == nil || p.Client == nil {
		return
	}
	delete(r.byClient, p.Client)
	p.Client = nil
	p.SuspendedAt = time.Now().UnixNano()
}

// removeParticipant fully removes a participant record from the room,
// including any suspend timer. Returns the Client that was attached (if any)
// so the caller can perform hub-level cleanup outside the room lock.
func (r *Room) removeParticipant(cid string) *Client {
	p := r.byCID[cid]
	if p == nil {
		return nil
	}
	if p.hardEvictionTimer != nil {
		p.hardEvictionTimer.Stop()
		p.hardEvictionTimer = nil
	}
	oldClient := p.Client
	if oldClient != nil {
		delete(r.byClient, oldClient)
	}
	delete(r.byCID, cid)
	return oldClient
}

// snapshotParticipants returns wire-format Participant entries for all
// participants in the room. Suspended entries carry ConnectionStatus="suspended".
func (r *Room) snapshotParticipants() []Participant {
	out := make([]Participant, 0, len(r.byCID))
	for _, p := range r.byCID {
		entry := Participant{
			CID:          p.CID,
			JoinedAt:     p.JoinedAt,
			DisplayName:  p.DisplayName,
			AudioEnabled: p.AudioEnabled,
			VideoEnabled: p.VideoEnabled,
		}
		if p.Client == nil {
			entry.ConnectionStatus = connectionStatusSuspended
		}
		out = append(out, entry)
	}
	return out
}

// transferHostIfNeeded reassigns HostCID to any remaining participant when the
// departing CID was the host. Prefers an actively-connected participant (one
// with an attached transport) so host privileges aren't silently held by a
// suspended participant whose reconnect may never arrive — otherwise live
// participants could be unable to `end_room` until the suspended user hard-
// evicts. Falls back to any remaining participant only if all are suspended.
// Returns the new host CID (empty string if the room is now empty).
func (r *Room) transferHostIfNeeded(departingCID string) string {
	if r.HostCID != departingCID {
		return r.HostCID
	}
	newHost := ""
	// Prefer an active participant (one currently attached via a live *Client).
	for _, activeCID := range r.byClient {
		newHost = activeCID
		break
	}
	// Fall back to any remaining participant if every survivor is suspended.
	if newHost == "" {
		for remainingCID := range r.byCID {
			newHost = remainingCID
			break
		}
	}
	r.HostCID = newHost
	if newHost != "" {
		log.Printf("[HOST_TRANSFER] Host %s left room %s. New host: %s", departingCID, r.RID, newHost)
	}
	return newHost
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

func newHub(maxParticipantsLimit int) *Hub {
	if maxParticipantsLimit < 2 {
		maxParticipantsLimit = 2
	}
	return &Hub{
		rooms:                make(map[string]*Room),
		watchers:             make(map[string]map[*Client]bool),
		clients:              make(map[*Client]bool),
		clientsBySID:         make(map[string]*Client),
		maxParticipantsLimit: maxParticipantsLimit,
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
	return room.participantByCID(cid) != nil
}

// GetClientDisplayName returns the display name for a client in a room.
// Returns empty string if the room/client doesn't exist or has no display name.
func (h *Hub) GetClientDisplayName(roomID, cid string) string {
	h.mu.RLock()
	room, exists := h.rooms[roomID]
	h.mu.RUnlock()
	if !exists {
		return ""
	}
	room.mu.Lock()
	defer room.mu.Unlock()
	if p := room.participantByCID(cid); p != nil {
		return p.DisplayName
	}
	return ""
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
			if cid := room.cidForClient(oldClient); cid != "" {
				p := room.participantByCID(cid)
				delete(room.byClient, oldClient)
				if p != nil {
					p.Client = newClient
				}
				room.byClient[newClient] = cid
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
	case "offer", "answer", "ice", "content_state":
		// log.Printf("[%s] Relay from %s to room %s", msg.Type, c.cid, c.rid) // verbose
		h.handleRelay(c, msg)
	case "participant_media_state":
		h.handleMediaState(c, msg)
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

	// Parse join payload before acquiring locks
	var joinPayload struct {
		ReconnectCID          string  `json:"reconnectCid"`
		ReconnectToken        string  `json:"reconnectToken"`
		CreateMaxParticipants int     `json:"createMaxParticipants"`
		DisplayName           *string `json:"displayName"`
		Capabilities          struct {
			MaxParticipants int `json:"maxParticipants"`
		} `json:"capabilities"`
	}
	if len(msg.Payload) > 0 {
		if err := json.Unmarshal(msg.Payload, &joinPayload); err != nil {
			log.Printf("[JOIN] Failed to parse payload: %v", err)
		}
	}

	// Client capability: largest room size this client supports (default 2 for legacy)
	clientMaxParticipants := joinPayload.Capabilities.MaxParticipants
	if clientMaxParticipants < 2 {
		clientMaxParticipants = 2
	}

	// Requested room capacity for new rooms (default 2, clamped to client capability and server ceiling)
	createMax := joinPayload.CreateMaxParticipants
	if createMax < 2 {
		createMax = 2
	}
	if createMax > clientMaxParticipants {
		createMax = clientMaxParticipants
	}
	if createMax > h.maxParticipantsLimit {
		createMax = h.maxParticipantsLimit
	}

	reconnectCID := joinPayload.ReconnectCID
	reconnectToken := joinPayload.ReconnectToken

	h.mu.Lock()
	room, exists := h.rooms[rid]
	if !exists {
		roomMaxParticipants := createMax
		capacityLocked := true
		if roomMaxParticipants > 2 {
			// Keep group-capable rooms joinable by legacy clients until the second
			// distinct participant locks the final room capacity.
			roomMaxParticipants = 2
			capacityLocked = false
		}
		log.Printf("[JOIN] Creating new room %s (maxParticipants=%d requestedMaxParticipants=%d locked=%t)", rid, roomMaxParticipants, createMax, capacityLocked)
		room = &Room{
			RID:                      rid,
			byCID:                    make(map[string]*roomParticipant),
			byClient:                 make(map[*Client]string),
			MaxParticipants:          roomMaxParticipants,
			RequestedMaxParticipants: createMax,
			CapacityLocked:           capacityLocked,
		}
		h.rooms[rid] = room
	}
	h.mu.Unlock()

	room.mu.Lock()
	reusedCID := false

	// Reconnect path: a client presenting reconnectCid wants to reclaim an
	// existing participant slot. Two cases:
	//   (a) Suspended participant (Client == nil): the previous transport
	//       already detached; simply reattach the new Client. No ghost cleanup.
	//   (b) Active ghost (Client != nil): the previous transport is still
	//       attached (race on fast reconnect). Detach and clean up hub state.
	var ghostToEvict *Client
	if reconnectCID != "" {
		if reconnectToken != "" && !validateReconnectToken(reconnectToken, reconnectCID, rid) {
			room.mu.Unlock()
			log.Printf("[JOIN] Invalid reconnectToken for CID %s from client %s", reconnectCID, c.sid)
			c.sendError(rid, "INVALID_RECONNECT_TOKEN", "Reconnect token validation failed")
			return
		}

		if existing := room.participantByCID(reconnectCID); existing != nil {
			if existing.Client != nil {
				// Case (b): active ghost. Detach the old Client from the
				// participant record and mark it for hub-level cleanup.
				// Note: we deliberately do NOT mutate ghostToEvict.cid/.rid
				// here — those fields are read by other goroutines (SSE
				// stale-eviction scan, logging) without synchronization, so
				// mutating them after join would race. The room indexes are
				// the source of truth for membership, and the ghost is about
				// to be fully cleaned up by cleanupEvictedClient.
				ghostToEvict = existing.Client
				log.Printf("[JOIN] Reconnection detected for CID %s. Evicting ghost client %s", reconnectCID, ghostToEvict.sid)
				delete(room.byClient, ghostToEvict)
				existing.Client = nil
			} else {
				log.Printf("[JOIN] Reconnection detected for CID %s. Reattaching suspended participant", reconnectCID)
			}
			// Reattach the new client to the existing participant record.
			// Note: room.HostCID is intentionally left unchanged so that the
			// host assignment is preserved across reconnects.
			room.reattachClient(existing, c)
			c.cid = existing.CID
			c.rid = rid
			reusedCID = true
		}
	}

	// Lock capacity on second distinct participant. Suspended participants
	// count toward occupancy, so a reconnect doesn't trigger this branch.
	if !room.CapacityLocked && !reusedCID && room.participantCount() == 1 {
		lockedMaxParticipants := room.RequestedMaxParticipants
		if lockedMaxParticipants < 2 {
			lockedMaxParticipants = 2
		}
		if clientMaxParticipants < lockedMaxParticipants {
			lockedMaxParticipants = clientMaxParticipants
		}
		room.MaxParticipants = lockedMaxParticipants
		room.CapacityLocked = true
		log.Printf("[JOIN] Room %s capacity locked at %d after second participant %s (client cap=%d, requested=%d)", rid, room.MaxParticipants, c.sid, clientMaxParticipants, room.RequestedMaxParticipants)
	}

	// Reject clients that don't support this room's capacity once it's finalized.
	if clientMaxParticipants < room.MaxParticipants {
		// Undo the reconnect reattach: re-arm the hard-eviction timer
		// (reattachClient stopped it, so the slot would wedge otherwise)
		// and clean up any ghost we evicted since the normal cleanup path
		// below is skipped when we return early.
		var ghostToCleanup *Client
		if reusedCID {
			if p := room.participantByCID(reconnectCID); p != nil && p.Client == c {
				room.detachClient(p)
				p.hardEvictionTimer = time.AfterFunc(suspendHardEvictionTimeout, func() {
					h.hardEvictSuspended(room, reconnectCID)
				})
			}
			// Do not clear c.cid / c.rid: other goroutines read them without
			// synchronization. The room indexes (byCID / byClient) are the
			// source of truth for membership; the stale fields on this client
			// are harmless because handleMessage bails early via isClientActive.
			ghostToCleanup = ghostToEvict
		}
		activeClientsSnapshot := room.activeClients()
		room.mu.Unlock()
		if ghostToCleanup != nil {
			h.cleanupEvictedClient(ghostToCleanup)
		}
		// If the ghost was active and we just rolled back to suspended, peers
		// were previously seeing this participant as active. Broadcast so they
		// see the suspended status without waiting for the next trigger.
		if ghostToCleanup != nil && len(activeClientsSnapshot) > 0 {
			h.broadcastRoomState(room)
		}
		log.Printf("[JOIN] Client %s (cap=%d) cannot join room %s (maxParticipants=%d)", c.sid, clientMaxParticipants, rid, room.MaxParticipants)
		c.sendError(rid, "ROOM_CAPACITY_UNSUPPORTED", "This client does not support group calls")
		return
	}

	// Room full check. Only applies to fresh joins — a reconnect has already
	// slotted back into its existing record without increasing the count.
	if !reusedCID && room.participantCount() >= room.MaxParticipants {
		room.mu.Unlock()
		log.Printf("[JOIN] Room %s is full (%d/%d)", rid, room.participantCount(), room.MaxParticipants)
		c.sendError(rid, "ROOM_FULL", "Room is full")
		return
	}

	// Deferred hub-level cleanup of ghost outside room lock to avoid deadlock.
	// The participant record has already been reassigned to the new client, so
	// we don't need a second capacity check after cleanup.
	if ghostToEvict != nil {
		room.mu.Unlock()
		h.cleanupEvictedClient(ghostToEvict)
		room.mu.Lock()
	}

	var (
		cid string
		p   *roomParticipant
	)
	if reusedCID {
		cid = reconnectCID
		p = room.participantByCID(cid)
	} else {
		cid = generateID("C-")
		c.cid = cid
		c.rid = rid
		p = room.attachParticipant(cid, c, time.Now().UnixMilli())
	}

	// Update display name on every join (including reconnect) so users can rename.
	if joinPayload.DisplayName != nil && p != nil {
		trimmed := strings.TrimSpace(*joinPayload.DisplayName)
		runes := []rune(trimmed)
		if len(runes) > maxDisplayNameLength {
			trimmed = string(runes[:maxDisplayNameLength])
		}
		p.DisplayName = trimmed
	}

	if room.HostCID == "" {
		room.HostCID = cid
	}

	log.Printf("[JOIN] Client %s assigned CID %s in room %s (maxParticipants=%d). Host: %s", c.sid, cid, rid, room.MaxParticipants, room.HostCID)

	participants := room.snapshotParticipants()
	roomMaxParticipants := room.MaxParticipants

	room.mu.Unlock() // <--- CRITICAL FIX: Unlock before broadcast/send to avoid deadlock/blocking

	payload := map[string]interface{}{
		"hostCid":         room.HostCID,
		"participants":    participants,
		"maxParticipants": roomMaxParticipants,
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
	// c.rid / c.cid alone aren't sufficient: a socket whose reconnect was
	// rejected (ROOM_CAPACITY_UNSUPPORTED) keeps its stale fields so the
	// attached room indexes stay race-free, but the socket is no longer a
	// real participant. Require that this client is still the attached
	// transport for its claimed CID before issuing fresh credentials.
	if !h.isActiveParticipant(c) {
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

// isActiveParticipant returns true iff c is the transport currently attached
// to a participant in c.rid. Protects handlers against stale c.rid / c.cid
// fields left behind on a rejected reconnect socket — those fields are kept
// as-is on purpose to avoid a data race (other goroutines read them without
// synchronization), but authorization must come from the room index, not the
// client's own fields.
func (h *Hub) isActiveParticipant(c *Client) bool {
	if c.rid == "" || c.cid == "" {
		return false
	}
	h.mu.RLock()
	room, exists := h.rooms[c.rid]
	h.mu.RUnlock()
	if !exists {
		return false
	}
	room.mu.Lock()
	defer room.mu.Unlock()
	return room.byClient[c] == c.cid
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

	// c.cid alone is not sufficient for authorization: a rejected-reconnect
	// socket keeps its stale c.cid pointing at the host's CID, but is no
	// longer the attached transport for that participant. Require BOTH that
	// c is currently attached AND that the attached participant is the host.
	attachedCID := room.byClient[c]
	if attachedCID == "" || room.HostCID != attachedCID {
		room.mu.Unlock()
		c.sendError(rid, "NOT_HOST", "Only host can end room")
		log.Printf("[END_ROOM] Client %s (CID: %s, attachedCID: %q) tried to end room %s but is not the attached host (Host: %s)", c.sid, c.cid, attachedCID, rid, room.HostCID)
		return
	}

	// Collect currently-attached clients (suspended participants have no
	// transport to notify; they will discover the room is gone if they try
	// to reconnect). Also stop any pending hard-eviction timers.
	clients := room.activeClients()
	for _, p := range room.byCID {
		if p.hardEvictionTimer != nil {
			p.hardEvictionTimer.Stop()
			p.hardEvictionTimer = nil
		}
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
	}

	// Remove room from hub
	h.mu.Lock()
	delete(h.rooms, rid)
	h.mu.Unlock()

	// Clear participant maps to help GC
	room.mu.Lock()
	room.byCID = make(map[string]*roomParticipant)
	room.byClient = make(map[*Client]string)
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

	// Sender must be an active (attached) participant in this room.
	if room.cidForClient(c) == "" {
		log.Printf("[RELAY] Client %s (CID: %s) tried to relay in room %s but is not a participant", c.sid, c.cid, c.rid)
		return
	}

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

	// Iterate active participants only. Suspended participants have no
	// transport attached — offer/answer/ice sent to them would be dropped
	// anyway. The sender will re-send on reconnect via ICE restart.
	relayedCount := 0
	for client, cid := range room.byClient {
		if cid == c.cid {
			continue
		}
		if msg.To != "" && msg.To != cid {
			continue
		}
		client.sendMessage(relayMsg)
		relayedCount++
	}
	log.Printf("[RELAY] Client %s (CID: %s) relayed %s message to %d participants in room %s", c.sid, c.cid, msg.Type, relayedCount, c.rid)
}

func (h *Hub) handleMediaState(c *Client, msg Message) {
	if c.rid == "" || c.cid == "" {
		return
	}
	h.mu.RLock()
	room, exists := h.rooms[c.rid]
	h.mu.RUnlock()
	if !exists {
		return
	}

	var payload struct {
		AudioEnabled *bool `json:"audioEnabled"`
		VideoEnabled *bool `json:"videoEnabled"`
	}
	if err := json.Unmarshal(msg.Payload, &payload); err != nil {
		return
	}

	room.mu.Lock()
	defer room.mu.Unlock()
	p := room.participantByCID(c.cid)
	if p == nil || p.Client != c {
		return
	}
	if payload.AudioEnabled != nil {
		p.AudioEnabled = payload.AudioEnabled
	}
	if payload.VideoEnabled != nil {
		p.VideoEnabled = payload.VideoEnabled
	}

	// Relay as peer message (like offer/answer/ice) instead of broadcasting
	// room_state, which causes participant reordering and full UI rebuilds.
	// The stored state is still included in joined/room_state for late joiners.
	relayPayload := map[string]interface{}{
		"from": c.cid,
	}
	if payload.AudioEnabled != nil {
		relayPayload["audioEnabled"] = *payload.AudioEnabled
	}
	if payload.VideoEnabled != nil {
		relayPayload["videoEnabled"] = *payload.VideoEnabled
	}
	newPayload, _ := json.Marshal(relayPayload)
	relayMsg := Message{
		V:       1,
		Type:    msg.Type,
		RID:     c.rid,
		Payload: newPayload,
	}
	for client, cid := range room.byClient {
		if cid != c.cid {
			client.sendMessage(relayMsg)
		}
	}
}

// disconnectClient is called when a client's transport drops. Instead of
// removing the participant from the room, we suspend its record — the *Client
// is detached but the CID-keyed participant stays, so other peers' WebRTC
// connections continue untouched. A hard-eviction timer reaps the record if
// the client never reconnects.
//
// Explicit departures (leave, end_room, ghost re-take) bypass suspension and
// call removeParticipantByLeave / the eviction paths directly.
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
		h.suspendClientInRoom(c)
	}
	closeClientSend(c.send)
}

// suspendClientInRoom detaches the client from its participant record, marks
// the record suspended, schedules a hard-eviction timer, and broadcasts an
// updated room_state so peers can display a "reconnecting" indicator while
// keeping their peer connections alive.
func (h *Hub) suspendClientInRoom(c *Client) {
	rid := c.rid
	cid := c.cid
	if rid == "" || cid == "" {
		return
	}

	h.mu.RLock()
	room, exists := h.rooms[rid]
	h.mu.RUnlock()

	if !exists {
		log.Printf("[SUSPEND] Room %s not found for client %s", rid, c.sid)
		return
	}

	room.mu.Lock()
	p := room.participantByCID(cid)
	if p == nil || p.Client != c {
		// Record already gone (ghost re-take evicted us, or leave/end_room fired).
		room.mu.Unlock()
		return
	}

	room.detachClient(p)
	log.Printf("[SUSPEND] Client %s (CID: %s) suspended in room %s. Hard eviction in %s", c.sid, cid, rid, suspendHardEvictionTimeout)

	// Close over this specific *Room so a timer firing after the room has been
	// replaced (end_room + new join reusing the rid) cannot evict the wrong
	// participant. Reattach on reconnect stops the timer in reattachClient.
	p.hardEvictionTimer = time.AfterFunc(suspendHardEvictionTimeout, func() {
		h.hardEvictSuspended(room, cid)
	})

	activeCount := len(room.byClient)
	room.mu.Unlock()

	// Do not clear c.cid / c.rid: other goroutines read them without
	// synchronization. Membership is determined from room.byClient, from
	// which we've already detached, so stale Client fields are benign.

	// Broadcast updated room_state so peers see connectionStatus="suspended"
	// for this CID. Skip if no one is listening.
	if activeCount > 0 {
		h.broadcastRoomState(room)
	}
	h.broadcastRoomStatusUpdate(rid)
}

// hardEvictSuspended fires when the suspend timer expires without a reconnect.
// It removes the participant record, reassigns host if needed, and broadcasts
// final room_state so remaining peers tear down the stale peer connection.
// Takes *Room (not rid) so a late-firing timer against a replaced-since room
// is dropped cleanly.
func (h *Hub) hardEvictSuspended(room *Room, cid string) {
	h.mu.RLock()
	current, exists := h.rooms[room.RID]
	h.mu.RUnlock()
	if !exists || current != room {
		return
	}

	rid := room.RID
	room.mu.Lock()
	p := room.participantByCID(cid)
	if p == nil || p.Client != nil {
		// Already reconnected or already evicted. No-op.
		room.mu.Unlock()
		return
	}
	log.Printf("[HARD_EVICT] Suspend window expired for CID %s in room %s. Removing participant.", cid, rid)
	room.removeParticipant(cid)
	room.transferHostIfNeeded(cid)

	isEmpty := room.participantCount() == 0
	room.mu.Unlock()

	if isEmpty {
		log.Printf("[HARD_EVICT] Room %s is now empty. Deleting room.", rid)
		h.mu.Lock()
		delete(h.rooms, rid)
		h.mu.Unlock()
	} else {
		h.broadcastRoomState(room)
	}
	h.broadcastRoomStatusUpdate(rid)
}

// removeClientFromRoom is called for EXPLICIT departures (leave). The
// participant record is removed immediately (no suspend window).
//
// Guarded against stale-socket races: during a reconnect, handleJoin evicts
// the old *Client from the room and reattaches the CID to a new *Client
// before deferred ghost cleanup runs. A late `leave` that reaches the old
// socket during that window must NOT remove the freshly-reattached
// participant and tear down the call. We only remove the record if the
// participant is still attached to THIS client.
func (h *Hub) removeClientFromRoom(c *Client) {
	log.Printf("[REMOVE_FROM_ROOM] Client %s (CID: %s) being removed from room %s", c.sid, c.cid, c.rid)
	h.mu.Lock()
	room, exists := h.rooms[c.rid]
	h.mu.Unlock()

	if !exists {
		log.Printf("[REMOVE_FROM_ROOM] Room %s not found for client %s", c.rid, c.sid)
		return
	}

	rid := c.rid
	cid := c.cid
	room.mu.Lock()
	p := room.participantByCID(cid)
	if p == nil || p.Client != c {
		// Already reattached to a new client, already evicted, or never
		// belonged to us. Nothing to do here.
		room.mu.Unlock()
		log.Printf("[REMOVE_FROM_ROOM] Skipping removal for client %s (CID: %s) — not the currently-attached transport", c.sid, cid)
		return
	}
	room.removeParticipant(cid)
	log.Printf("[REMOVE_FROM_ROOM] Client %s (CID: %s) removed from room %s. Remaining participants: %d", c.sid, cid, rid, room.participantCount())
	room.transferHostIfNeeded(cid)

	isEmpty := room.participantCount() == 0
	room.mu.Unlock()

	if isEmpty {
		log.Printf("[REMOVE_FROM_ROOM] Room %s is now empty. Deleting room.", rid)
		h.mu.Lock()
		delete(h.rooms, rid)
		h.mu.Unlock()
	} else {
		h.broadcastRoomState(room)
	}
	h.broadcastRoomStatusUpdate(rid)
}

func (h *Hub) broadcastRoomState(room *Room) {
	// Must be called without room lock!

	room.mu.Lock()
	participants := room.snapshotParticipants()
	hostCid := room.HostCID
	rid := room.RID
	roomMaxParticipants := room.MaxParticipants
	// Only broadcast to actively-attached clients; suspended participants
	// have no transport to receive messages.
	clients := room.activeClients()
	room.mu.Unlock()

	payload := map[string]interface{}{
		"hostCid":         hostCid,
		"participants":    participants,
		"maxParticipants": roomMaxParticipants,
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
	status := make(map[string]map[string]int)
	for rid, clientSet := range h.watchers {
		delete(clientSet, c)
		if len(clientSet) == 0 {
			delete(h.watchers, rid)
		}
	}
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
			status[rid] = map[string]int{
				"count":           room.participantCount(),
				"maxParticipants": room.MaxParticipants,
			}
			room.mu.Unlock()
		} else {
			status[rid] = map[string]int{
				"count": 0,
			}
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
	maxParticipants := 0
	if room, ok := h.rooms[rid]; ok {
		room.mu.Lock()
		count = room.participantCount()
		maxParticipants = room.MaxParticipants
		room.mu.Unlock()
	}
	h.mu.RUnlock()

	payloadMap := map[string]interface{}{
		"rid":   rid,
		"count": count,
	}
	if maxParticipants > 0 {
		payloadMap["maxParticipants"] = maxParticipants
	}
	payload, _ := json.Marshal(payloadMap)

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
