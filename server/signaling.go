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
	"strconv"
	"strings"
	"sync"
	"time"

	"serenada/server/internal/stats"
)

const maxMessageSize = 65536 // 64KB
const maxDisplayNameLength = 40
const maxPeerIDLength = 128

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

// noActiveRoomTimeout bounds how long a room can contain only suspended
// participants. With no attached transports left, there is nobody to observe
// media liveness, so keeping ghost-only rooms around for the full suspend
// window just leaves stale occupancy visible to watchers.
const noActiveRoomTimeout = 10 * time.Second

// reconnectTokenTTL bounds how long a reconnect token can be used to reattach
// or recover a participant identity. It intentionally exceeds the suspend
// window: active clients refresh before expiry, leaving roughly one suspend
// window of validity if the transport drops just before the refresh.
const reconnectTokenTTL = 20 * time.Minute

// roomTombstoneTTL is the window during which an explicitly-ended room is
// remembered with a structured reason. Reconnect attempts with a valid
// reconnect token receive ROOM_ENDED instead of being silently turned into a
// fresh participant in a recreated room.
const roomTombstoneTTL = 5 * time.Minute

// mediaLivenessFreshnessWindow bounds how recent a peer-reported
// media_liveness hint must be to defer hard-eviction of a suspended
// participant. The hint is a cleanup signal only: it may delay eviction but
// never authorizes anything else.
const mediaLivenessFreshnessWindow = 30 * time.Second

// hardEvictMediaActiveDeferral is how long hard-eviction is pushed back when
// at least one active peer recently reported inbound media from the suspended
// CID. Re-evaluated on each fire.
const hardEvictMediaActiveDeferral = 30 * time.Second

// ghostEvictMinDwell is the minimum suspension dwell before fast-path ghost
// eviction can fire. Gives a legitimate reattach attempt (with a valid
// reconnect token) a chance to land before peers' "no media flowing" reports
// trigger early eviction. If we evicted immediately, a brief signaling-layer
// blip would cost the participant their slot.
const ghostEvictMinDwell = 30 * time.Second

// ConnectionStatus values broadcast in room_state/joined participant entries.
// Omitted when "active" (backward compatible with older clients).
const (
	connectionStatusActive    = "active"
	connectionStatusSuspended = "suspended"
)

// Reconnect outcome values reported back to SDKs in joined.payload.reconnect.
// Drives whether the SDK keeps media-active peer connections, schedules
// dirty-pair renegotiation, or starts fresh.
const (
	reconnectOutcomeFresh      = "fresh"
	reconnectOutcomeReattached = "reattached"
	reconnectOutcomeRecovered  = "recovered"
)

// Tombstone reasons broadcast via the ROOM_ENDED error to reconnect-with-token
// attempts targeting a room that is no longer present.
const (
	tombstoneReasonEndedByHost = "ended_by_host"
)

// reconnectSecret returns the HMAC secret used to sign and verify reconnect
// tokens. Falls back to TURN_SECRET so existing deployments keep working
// without configuration changes.
func reconnectSecret() []byte {
	secret := os.Getenv("TURN_TOKEN_SECRET")
	if secret == "" {
		secret = os.Getenv("TURN_SECRET")
	}
	if secret == "" {
		return nil
	}
	return []byte(secret)
}

// issueReconnectToken generates an HMAC proof bound to (cid, rid, expiresAt).
// Format: hex(HMAC-SHA256(secret, cid|rid|expiresAt)) || "." || expiresAtUnix.
// expiresAt is wall-clock unix seconds. The expiry is on-the-wire so the
// validator can reject expired tokens without server-side state.
func issueReconnectToken(cid, rid string) string {
	return issueReconnectTokenWithExpiry(cid, rid, time.Now().Add(reconnectTokenTTL))
}

func issueReconnectTokenWithExpiry(cid, rid string, expiresAt time.Time) string {
	secret := reconnectSecret()
	if secret == nil {
		return ""
	}
	exp := expiresAt.Unix()
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(cid + "|" + rid + "|" + strconv.FormatInt(exp, 10)))
	return hex.EncodeToString(mac.Sum(nil)) + "." + strconv.FormatInt(exp, 10)
}

// validateReconnectToken returns ok=true when the token's HMAC verifies and
// the embedded expiresAt is in the future. expired=true when the token's
// signature is valid but it has aged out — the SDK should treat the token as
// dead and clear persisted state. ok=false, expired=false means the token is
// malformed or the signature does not verify.
func validateReconnectToken(token, cid, rid string) (ok bool, expired bool) {
	if token == "" {
		return false, false
	}
	secret := reconnectSecret()
	if secret == nil {
		// No secret configured — legacy/dev environments. Accept any token to
		// preserve current behavior on machines that never set TURN_SECRET.
		return true, false
	}
	dot := strings.LastIndexByte(token, '.')
	if dot < 0 {
		// Legacy tokens were HMAC(cid|rid) without an embedded expiry. Accept
		// them only as expired-but-authentic so the caller can clear the stale
		// suspended slot without letting the token reattach indefinitely.
		mac := hmac.New(sha256.New, secret)
		mac.Write([]byte(cid + "|" + rid))
		expected := hex.EncodeToString(mac.Sum(nil))
		if hmac.Equal([]byte(expected), []byte(token)) {
			return false, true
		}
		return false, false
	}
	macHex := token[:dot]
	expStr := token[dot+1:]
	exp, err := strconv.ParseInt(expStr, 10, 64)
	if err != nil {
		return false, false
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(cid + "|" + rid + "|" + strconv.FormatInt(exp, 10)))
	expected := hex.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(expected), []byte(macHex)) {
		return false, false
	}
	if time.Now().Unix() > exp {
		return false, true
	}
	return true, false
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
	CID              string                   `json:"cid"`
	JoinedAt         int64                    `json:"joinedAt,omitempty"`
	DisplayName      string                   `json:"displayName,omitempty"`
	PeerID           string                   `json:"peerId,omitempty"` // host-supplied stable identity, distinct from CID; opaque to server
	AudioEnabled     *bool                    `json:"audioEnabled,omitempty"`
	VideoEnabled     *bool                    `json:"videoEnabled,omitempty"`
	ConnectionStatus string                   `json:"connectionStatus,omitempty"` // "suspended" when transport detached; omitted (= "active") otherwise
	ContentState     *ParticipantContentState `json:"contentState,omitempty"`
}

// ParticipantContentState is the latest ephemeral content metadata (screen
// share, content camera mode, etc.) for a participant. Stored on the
// participant record so it survives suspension and is restored to a peer
// reattaching after being away. Latest wins — older transitions are not
// replayed.
type ParticipantContentState struct {
	Active      bool   `json:"active"`
	ContentType string `json:"contentType,omitempty"`
	UpdatedAtMs int64  `json:"updatedAtMs,omitempty"`
	Epoch       int64  `json:"epoch,omitempty"`
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
	PeerID       string
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
	// ContentState is the latest ephemeral content metadata for this CID (screen
	// share active, content camera mode, etc.) so a reattaching peer can
	// reconstruct UI without waiting for the sender to toggle again.
	ContentState *ParticipantContentState
}

// roomTombstone records that a room has explicitly ended. Reconnect attempts
// against the room within roomTombstoneTTL receive a structured ROOM_ENDED
// error so SDKs can clear recovery state instead of looping reconnect against
// a dead RID.
type roomTombstone struct {
	Reason    string
	ExpiresAt time.Time
}

type Hub struct {
	rooms                map[string]*Room
	watchers             map[string]map[*Client]bool // roomID -> set of clients
	tombstones           map[string]*roomTombstone   // roomID -> termination record (TTL'd)
	mu                   sync.RWMutex
	clients              map[*Client]bool
	clientsBySID         map[string]*Client
	pendingTakeovers     map[string]*Client
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
	// Epoch is a monotonic counter advanced on every membership-mutating
	// operation (join, leave, suspend, reattach, evict, host transfer).
	// Embedded in joined / room_state so SDKs can detect missed membership
	// transitions and gate ICE restart on an authoritative post-reconnect
	// snapshot rather than acting on stale in-memory peer maps.
	Epoch int64
	// negotiationDirty[fromCID] is the set of toCIDs that fromCID attempted to
	// reach via offer/answer/ice while toCID had no attached transport. On
	// reattach the server notifies the active peers so they can schedule fresh
	// glare-safe negotiation instead of waiting for an answer that never
	// arrives.
	negotiationDirty map[string]map[string]bool
	// mediaLiveness records the most recent unix-ms timestamp at which an
	// active peer reported inbound media flowing from the keyed CID. Used as
	// a cleanup hint only: hard-eviction may be deferred while at least one
	// peer recently observed media. Never used as authorization.
	mediaLiveness map[string]int64
	// mediaLivenessReporters records the most recent unix-ms timestamp at
	// which each active peer (keyed by reporter CID) sent a media_liveness
	// message — regardless of payload contents. Combined with mediaLiveness,
	// lets us detect "all active peers are alive and reporting, but none
	// observe media flowing from suspended CID X" — the trigger for fast-path
	// ghost eviction.
	mediaLivenessReporters map[string]int64
	// noActiveTimer fires when every participant in the room is suspended.
	// It deletes the room if nobody reattaches within noActiveRoomTimeout.
	noActiveTimer *time.Timer
	mu            sync.Mutex
}

// bumpEpoch increments the room state epoch. Caller must hold r.mu. Returns
// the new epoch so callers can stamp it onto outgoing payloads without a
// second lock.
func (r *Room) bumpEpoch() int64 {
	r.Epoch++
	return r.Epoch
}

// markNegotiationDirty records that fromCID attempted to negotiate with toCID
// while toCID had no attached transport. Caller must hold r.mu.
func (r *Room) markNegotiationDirty(fromCID, toCID string) {
	if r.negotiationDirty == nil {
		r.negotiationDirty = make(map[string]map[string]bool)
	}
	set, ok := r.negotiationDirty[fromCID]
	if !ok {
		set = make(map[string]bool)
		r.negotiationDirty[fromCID] = set
	}
	set[toCID] = true
}

// drainDirtyPartnersFor returns and clears the set of CIDs that previously
// attempted to negotiate with `cid` while it was suspended. Caller must hold
// r.mu. The returned slice is a freshly allocated copy.
func (r *Room) drainDirtyPartnersFor(cid string) []string {
	if r.negotiationDirty == nil {
		return nil
	}
	out := make([]string, 0)
	for fromCID, partners := range r.negotiationDirty {
		if !partners[cid] {
			continue
		}
		out = append(out, fromCID)
		delete(partners, cid)
		if len(partners) == 0 {
			delete(r.negotiationDirty, fromCID)
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// dropCIDFromDirty removes the CID from all dirty entries (both as source and
// target). Called on participant removal. Caller must hold r.mu.
func (r *Room) dropCIDFromDirty(cid string) {
	if r.negotiationDirty == nil {
		return
	}
	delete(r.negotiationDirty, cid)
	for fromCID, partners := range r.negotiationDirty {
		delete(partners, cid)
		if len(partners) == 0 {
			delete(r.negotiationDirty, fromCID)
		}
	}
}

// recordMediaLiveness updates the last-reported inbound-media timestamp for a
// CID. Caller must hold r.mu. Used to defer hard-eviction while peers still
// see media from a suspended participant.
func (r *Room) recordMediaLiveness(cid string, atMs int64) {
	if r.mediaLiveness == nil {
		r.mediaLiveness = make(map[string]int64)
	}
	if existing, ok := r.mediaLiveness[cid]; ok && existing >= atMs {
		return
	}
	r.mediaLiveness[cid] = atMs
}

// hasRecentMediaLiveness returns true when at least one peer has reported
// inbound media for `cid` within the last `window`. Caller must hold r.mu.
func (r *Room) hasRecentMediaLiveness(cid string, window time.Duration) bool {
	if r.mediaLiveness == nil {
		return false
	}
	last, ok := r.mediaLiveness[cid]
	if !ok {
		return false
	}
	return time.Since(time.UnixMilli(last)) <= window
}

// dropMediaLivenessFor removes any liveness record for the participant. Caller
// must hold r.mu.
func (r *Room) dropMediaLivenessFor(cid string) {
	if r.mediaLiveness != nil {
		delete(r.mediaLiveness, cid)
	}
	if r.mediaLivenessReporters != nil {
		delete(r.mediaLivenessReporters, cid)
	}
}

// recordMediaLivenessReporter updates the last-seen unix-ms timestamp at
// which `cid` sent any media_liveness message. Caller must hold r.mu.
func (r *Room) recordMediaLivenessReporter(cid string, atMs int64) {
	if r.mediaLivenessReporters == nil {
		r.mediaLivenessReporters = make(map[string]int64)
	}
	if existing, ok := r.mediaLivenessReporters[cid]; ok && existing >= atMs {
		return
	}
	r.mediaLivenessReporters[cid] = atMs
}

// suspendedGhostsExcludedByActiveReporters returns the CIDs of suspended
// participants that meet ALL of: (1) suspended for at least minDwell;
// (2) no recent positive media-flowing report from any peer; (3) at least
// one currently-active peer has sent a fresh media_liveness within freshness.
// Caller must hold r.mu.
//
// The "at least one fresh reporter" requirement (rather than "every active
// peer") accommodates mixed-version rooms: an older client that does not
// emit media_liveness should not block eviction of an unrelated CID. As long
// as ONE up-to-date peer is alive, reporting, and not observing media from
// the suspended CID, we treat that as sufficient grounds to evict — the
// suspended CID has no signaling transport AND no peer reporting media from
// it, so it can't be reached and shouldn't keep its slot.
func (r *Room) suspendedGhostsExcludedByActiveReporters(now int64, minDwell, freshness time.Duration) []string {
	minDwellMs := minDwell.Milliseconds()
	freshnessMs := freshness.Milliseconds()
	var ghosts []string
	for cid, p := range r.byCID {
		if p.Client != nil {
			continue
		}
		if p.SuspendedAt == 0 {
			continue
		}
		suspendedAtMs := p.SuspendedAt / int64(time.Millisecond)
		if now-suspendedAtMs < minDwellMs {
			continue
		}
		if last, ok := r.mediaLiveness[cid]; ok && now-last <= freshnessMs {
			continue
		}
		anyFresh := false
		for activeCID, q := range r.byCID {
			if activeCID == cid || q.Client == nil {
				continue
			}
			last, ok := r.mediaLivenessReporters[activeCID]
			if ok && now-last <= freshnessMs {
				anyFresh = true
				break
			}
		}
		if !anyFresh {
			continue
		}
		ghosts = append(ghosts, cid)
	}
	return ghosts
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

func (r *Room) cancelNoActiveTimer() {
	if r.noActiveTimer != nil {
		r.noActiveTimer.Stop()
		r.noActiveTimer = nil
	}
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
	r.cancelNoActiveTimer()
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
	r.cancelNoActiveTimer()
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
			PeerID:       p.PeerID,
			AudioEnabled: p.AudioEnabled,
			VideoEnabled: p.VideoEnabled,
			ContentState: p.ContentState,
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
		tombstones:           make(map[string]*roomTombstone),
		clients:              make(map[*Client]bool),
		clientsBySID:         make(map[string]*Client),
		pendingTakeovers:     make(map[string]*Client),
		maxParticipantsLimit: maxParticipantsLimit,
	}
}

// recordTombstone marks the room as explicitly ended. Reconnect attempts
// arriving within roomTombstoneTTL will receive a structured ROOM_ENDED error
// instead of being silently turned into a fresh participant.
func (h *Hub) recordTombstone(rid, reason string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.tombstones[rid] = &roomTombstone{
		Reason:    reason,
		ExpiresAt: time.Now().Add(roomTombstoneTTL),
	}
	h.gcTombstonesLocked()
}

// lookupTombstone returns the active tombstone for rid, or nil if absent or
// expired. Expired entries are evicted lazily on lookup.
func (h *Hub) lookupTombstone(rid string) *roomTombstone {
	h.mu.Lock()
	defer h.mu.Unlock()
	t, ok := h.tombstones[rid]
	if !ok {
		return nil
	}
	if time.Now().After(t.ExpiresAt) {
		delete(h.tombstones, rid)
		return nil
	}
	return t
}

// clearTombstone removes any tombstone for the room. Called when an explicit
// fresh start is requested (no reconnect token, or token validation failed).
func (h *Hub) clearTombstone(rid string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.tombstones, rid)
}

// gcTombstonesLocked drops expired tombstones. Must be called with h.mu held.
func (h *Hub) gcTombstonesLocked() {
	now := time.Now()
	for rid, t := range h.tombstones {
		if now.After(t.ExpiresAt) {
			delete(h.tombstones, rid)
		}
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

func (h *Hub) registerPendingTakeover(sid string, c *Client) {
	h.mu.Lock()
	h.pendingTakeovers[sid] = c
	h.mu.Unlock()
}

func (h *Hub) getPendingTakeover(sid string) *Client {
	h.mu.RLock()
	c := h.pendingTakeovers[sid]
	h.mu.RUnlock()
	return c
}

func (h *Hub) removePendingTakeover(sid string) {
	h.mu.Lock()
	delete(h.pendingTakeovers, sid)
	h.mu.Unlock()
}

func (h *Hub) isClientActive(c *Client) bool {
	h.mu.RLock()
	_, exists := h.clients[c]
	h.mu.RUnlock()
	return exists
}

// IsClientInRoom checks whether a client with the given CID is actively
// attached (non-suspended) in the specified room. Thread-safe for use from
// HTTP handlers. Suspended participants are excluded — authorization checks
// should require an active transport, not just a reserved slot in byCID.
func (h *Hub) IsClientInRoom(roomID, cid string) bool {
	h.mu.RLock()
	room, exists := h.rooms[roomID]
	h.mu.RUnlock()
	if !exists {
		return false
	}
	room.mu.Lock()
	defer room.mu.Unlock()
	p := room.participantByCID(cid)
	return p != nil && p.Client != nil
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
	case "reconnect-token-refresh":
		h.handleReconnectTokenRefresh(c, msg)
	case "offer", "answer", "ice", "content_state":
		// log.Printf("[%s] Relay from %s to room %s", msg.Type, c.cid, c.rid) // verbose
		h.handleRelay(c, msg)
	case "participant_media_state":
		h.handleMediaState(c, msg)
	case "media_liveness":
		h.handleMediaLiveness(c, msg)
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
		PeerID                *string `json:"peerId"`
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

	// Validate the reconnect token up-front so authority decisions are made on
	// the same proof regardless of which case below applies.
	tokenValid := false
	if reconnectCID != "" && reconnectToken != "" {
		valid, expired := validateReconnectToken(reconnectToken, reconnectCID, rid)
		if !valid {
			log.Printf("[JOIN] Invalid reconnectToken for CID %s from client %s (expired=%t)", reconnectCID, c.sid, expired)
			if expired {
				h.evictSuspendedParticipantForExpiredReconnect(rid, reconnectCID)
			}
			c.sendError(rid, "INVALID_RECONNECT_TOKEN", "Reconnect token validation failed")
			return
		}
		tokenValid = valid
	}

	// Tombstone gate: if a recent end_room marked this RID as terminated and
	// the client is presenting reconnect authority for it, surface the
	// terminal error rather than silently turning the request into a fresh
	// caller in a recreated room.
	if reconnectCID != "" && tokenValid {
		if t := h.lookupTombstone(rid); t != nil {
			reason := t.Reason
			if reason == "" {
				reason = tombstoneReasonEndedByHost
			}
			log.Printf("[JOIN] Rejecting reconnect to tombstoned room %s (reason=%s)", rid, reason)
			c.sendErrorWithReason(rid, "ROOM_ENDED", "Room has ended", reason)
			return
		}
	}

	// Hub-level lookup. We may need to recreate the room if a valid reconnect
	// token references a room that has been GC'd (no participants, no
	// tombstone — e.g. server restart or all participants hard-evicted).
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
	recoveredCID := false

	// Reconnect path: a client presenting reconnectCid wants to reclaim an
	// existing participant slot. Three cases:
	//   (a) Suspended participant (Client == nil): the previous transport
	//       already detached; simply reattach the new Client. No ghost cleanup.
	//   (b) Active ghost (Client != nil): the previous transport is still
	//       attached (race on fast reconnect). Detach and clean up hub state.
	//   (c) No participant record (room was GC'd or recreated): if the
	//       reconnect token validates, recreate the participant with the
	//       requested CID so identity survives server-side memory loss.
	var ghostToEvict *Client
	if reconnectCID != "" {
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
				h.armNoActiveRoomTimerLocked(room)
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
	// A token-validated identity recovery (case c) also slots into the
	// requested CID without growing the count beyond what we're about to add.
	if !reusedCID && !(reconnectCID != "" && tokenValid) && room.participantCount() >= room.MaxParticipants {
		room.mu.Unlock()
		log.Printf("[JOIN] Room %s is full (%d/%d)", rid, room.participantCount(), room.MaxParticipants)
		c.sendError(rid, "ROOM_FULL", "Room is full")
		return
	}

	// Identity recovery (case c): valid reconnect token but no participant
	// record. Recreate the participant with the requested CID so the SDK can
	// preserve media-active peer connections across signaling-only memory
	// loss. We still bound this by capacity above.
	if !reusedCID && reconnectCID != "" && tokenValid {
		if room.participantCount() >= room.MaxParticipants {
			room.mu.Unlock()
			log.Printf("[JOIN] Room %s is full while attempting to recover CID %s (%d/%d)", rid, reconnectCID, room.participantCount(), room.MaxParticipants)
			c.sendError(rid, "ROOM_FULL", "Room is full")
			return
		}
		log.Printf("[JOIN] Recovering CID %s in room %s with valid reconnect token (no prior record)", reconnectCID, rid)
		c.cid = reconnectCID
		c.rid = rid
		room.attachParticipant(reconnectCID, c, time.Now().UnixMilli())
		recoveredCID = true
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
		cid     string
		p       *roomParticipant
		outcome string
	)
	switch {
	case reusedCID:
		cid = reconnectCID
		p = room.participantByCID(cid)
		outcome = reconnectOutcomeReattached
	case recoveredCID:
		cid = reconnectCID
		p = room.participantByCID(cid)
		outcome = reconnectOutcomeRecovered
	default:
		cid = generateID("C-")
		c.cid = cid
		c.rid = rid
		p = room.attachParticipant(cid, c, time.Now().UnixMilli())
		outcome = reconnectOutcomeFresh
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

	// Update peer ID on every join. Empty string clears it (mirroring displayName).
	// PeerID is host-supplied (e.g. host's user identifier) and opaque to the server —
	// it is forwarded to other participants so call UIs can resolve avatars consistently
	// even when displayName collides.
	if joinPayload.PeerID != nil && p != nil {
		trimmed := strings.TrimSpace(*joinPayload.PeerID)
		runes := []rune(trimmed)
		if len(runes) > maxPeerIDLength {
			trimmed = string(runes[:maxPeerIDLength])
		}
		p.PeerID = trimmed
	}

	if room.HostCID == "" {
		room.HostCID = cid
	}

	// Membership-mutating operation — bump the room state epoch so SDKs can
	// detect changes that happened while their last snapshot is in hand.
	epoch := room.bumpEpoch()

	// On reattach / recover, surface any negotiations that peers attempted
	// while we had no transport so the SDK can schedule fresh glare-safe
	// negotiation rather than waiting for an answer that will never arrive.
	dirtyPartners := room.drainDirtyPartnersFor(cid)

	log.Printf("[JOIN] Client %s assigned CID %s in room %s (maxParticipants=%d outcome=%s epoch=%d). Host: %s", c.sid, cid, rid, room.MaxParticipants, outcome, epoch, room.HostCID)

	participants := room.snapshotParticipants()
	roomMaxParticipants := room.MaxParticipants
	hostCID := room.HostCID

	room.mu.Unlock() // <--- CRITICAL FIX: Unlock before broadcast/send to avoid deadlock/blocking

	// Any successful join means the room is alive again, so clear any stale
	// tombstone for this RID. Reconnect attempts arriving with a token for a
	// dead room are already gated above (before they reach this point); by
	// the time we get here the join has succeeded and a fresh participant
	// reconnecting later — including the one who just joined — must not be
	// rejected with ROOM_ENDED for a previous session.
	h.clearTombstone(rid)

	payload := map[string]interface{}{
		"hostCid":         hostCID,
		"participants":    participants,
		"maxParticipants": roomMaxParticipants,
		"epoch":           epoch,
		"reconnect":       outcome,
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
		payload["reconnectTokenTTLMs"] = int64(reconnectTokenTTL / time.Millisecond)
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

	// Always send an authoritative room_state snapshot on the new transport
	// after a successful reconnect so the SDK has a confirmed sync point
	// before scheduling renegotiation. For fresh joins the snapshot is
	// redundant with `joined`, but emitting it uniformly keeps the SDK code
	// path simple.
	h.sendRoomStateSnapshot(c, room)

	// Broadcast room_state to others so peers learn about the new/reattached/
	// recovered participant.
	h.broadcastRoomState(room)

	// Tell active peers that they have dirty negotiation pairs with this CID
	// so they can schedule fresh negotiation after the snapshot above.
	if len(dirtyPartners) > 0 {
		h.notifyDirtyNegotiation(room, cid, dirtyPartners)
	}

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

func (h *Hub) handleReconnectTokenRefresh(c *Client, msg Message) {
	if !h.isActiveParticipant(c) {
		rid := c.rid
		if rid == "" {
			rid = msg.RID
		}
		c.sendError(rid, "NOT_IN_ROOM", "Must be in a room to refresh reconnect token")
		return
	}

	token := issueReconnectToken(c.cid, c.rid)
	if token == "" {
		log.Printf("[RECONNECT-TOKEN-REFRESH] Failed to issue reconnect token for client %s (CID: %s)", c.sid, c.cid)
		c.sendError(msg.RID, "RECONNECT_TOKEN_REFRESH_FAILED", "Failed to refresh reconnect token")
		return
	}

	payload := map[string]interface{}{
		"reconnectToken":      token,
		"reconnectTokenTTLMs": int64(reconnectTokenTTL / time.Millisecond),
	}
	payloadBytes, _ := json.Marshal(payload)

	c.sendMessage(Message{
		V:       1,
		Type:    "reconnect-token-refreshed",
		RID:     c.rid,
		Payload: payloadBytes,
	})
	log.Printf("[RECONNECT-TOKEN-REFRESH] Refreshed reconnect token for client %s (CID: %s) in room %s", c.sid, c.cid, c.rid)
}

// armNoActiveRoomTimerLocked starts the room-level cleanup timer once the last
// active transport has dropped. Caller must hold room.mu.
func (h *Hub) armNoActiveRoomTimerLocked(room *Room) {
	if len(room.byClient) > 0 || len(room.byCID) == 0 || room.noActiveTimer != nil {
		return
	}
	rid := room.RID
	room.noActiveTimer = time.AfterFunc(noActiveRoomTimeout, func() {
		h.cleanupRoomIfNoActive(room)
	})
	log.Printf("[ROOM_CLEANUP] Room %s has no active participants; deleting in %s if nobody reconnects", rid, noActiveRoomTimeout)
}

func (h *Hub) cleanupRoomIfNoActive(room *Room) {
	rid := room.RID

	h.mu.Lock()
	if h.rooms[rid] != room {
		h.mu.Unlock()
		return
	}

	room.mu.Lock()
	if len(room.byClient) > 0 {
		room.noActiveTimer = nil
		room.mu.Unlock()
		h.mu.Unlock()
		return
	}

	suspendedCount := len(room.byCID)
	for _, p := range room.byCID {
		if p.hardEvictionTimer != nil {
			p.hardEvictionTimer.Stop()
			p.hardEvictionTimer = nil
		}
	}
	room.cancelNoActiveTimer()
	delete(h.rooms, rid)

	room.byCID = make(map[string]*roomParticipant)
	room.byClient = make(map[*Client]string)
	room.HostCID = ""
	room.negotiationDirty = nil
	room.mediaLiveness = nil
	room.mediaLivenessReporters = nil
	room.mu.Unlock()
	h.mu.Unlock()

	log.Printf("[ROOM_CLEANUP] Deleted room %s after %s with no active participants (suspended=%d)", rid, noActiveRoomTimeout, suspendedCount)
	h.broadcastRoomStatusUpdate(rid)
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
	room.cancelNoActiveTimer()
	room.bumpEpoch()

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

	// Record a tombstone so suspended participants that reconnect within the
	// TTL receive a structured ROOM_ENDED instead of being silently turned
	// into a fresh participant in a recreated room.
	h.recordTombstone(rid, tombstoneReasonEndedByHost)

	// Remove room from hub
	h.mu.Lock()
	delete(h.rooms, rid)
	h.mu.Unlock()

	// Clear participant maps to help GC
	room.mu.Lock()
	room.byCID = make(map[string]*roomParticipant)
	room.byClient = make(map[*Client]string)
	room.HostCID = ""
	room.negotiationDirty = nil
	room.mediaLiveness = nil
	room.mediaLivenessReporters = nil
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
	senderCID := room.cidForClient(c)
	if senderCID == "" {
		log.Printf("[RELAY] Client %s (CID: %s) tried to relay in room %s but is not a participant", c.sid, c.cid, c.rid)
		return
	}

	var rawPayload map[string]interface{}
	if err := json.Unmarshal(msg.Payload, &rawPayload); err != nil {
		rawPayload = make(map[string]interface{})
		log.Printf("[RELAY] Client %s (CID: %s) sent invalid payload for type %s: %v", c.sid, c.cid, msg.Type, err)
	}
	rawPayload["from"] = senderCID

	// content_state is latest-state UI metadata, not negotiation traffic.
	// Persist it on the participant record so a peer reconnecting after a
	// suspension still receives the current value via room_state, instead of
	// only learning about new transitions.
	if msg.Type == "content_state" {
		applyContentStateUpdate(room, senderCID, rawPayload)
	}

	newPayload, _ := json.Marshal(rawPayload)

	relayMsg := Message{
		V:       1,
		Type:    msg.Type,
		RID:     msg.RID,
		Payload: newPayload,
	}

	// Walk the full participant list (including suspended). Active peers get
	// the message; for offer/answer/ice we additionally mark the sender's
	// dirty pair with each suspended target and respond `relay_failed` so the
	// sender doesn't sit waiting forever for an answer that will never come.
	negotiationType := msg.Type == "offer" || msg.Type == "answer" || msg.Type == "ice"
	relayedCount := 0
	var suspendedTargets []string
	for cid, p := range room.byCID {
		if cid == senderCID {
			continue
		}
		if msg.To != "" && msg.To != cid {
			continue
		}
		if p.Client != nil {
			p.Client.sendMessage(relayMsg)
			relayedCount++
			continue
		}
		if negotiationType {
			room.markNegotiationDirty(senderCID, cid)
			suspendedTargets = append(suspendedTargets, cid)
		}
	}

	if len(suspendedTargets) > 0 {
		// Tell the sender we couldn't deliver. The SDK should suppress further
		// negotiation to that CID and wait for `negotiation_dirty` after the
		// peer reattaches.
		failPayload, _ := json.Marshal(map[string]interface{}{
			"reason":  "target_suspended",
			"targets": suspendedTargets,
			"of":      msg.Type,
		})
		c.sendMessage(Message{
			V:       1,
			Type:    "relay_failed",
			RID:     msg.RID,
			Payload: failPayload,
		})
	}

	log.Printf("[RELAY] Client %s (CID: %s) relayed %s message to %d participants in room %s (suspended-targets=%d)", c.sid, senderCID, msg.Type, relayedCount, c.rid, len(suspendedTargets))
}

// applyContentStateUpdate merges the latest content metadata into the
// sender's participant record so it can be replayed via room_state after a
// suspension. Caller must hold room.mu.
//
// Bails out unless the payload carries a boolean `active` field — a
// malformed/empty content_state must NOT destructively clear the
// participant's previously-stored state, since suspended peers depend on
// the latest known good value at reattach time.
func applyContentStateUpdate(room *Room, senderCID string, payload map[string]interface{}) {
	p := room.participantByCID(senderCID)
	if p == nil {
		return
	}
	active, ok := payload["active"].(bool)
	if !ok {
		return
	}
	state := &ParticipantContentState{
		Active:      active,
		Epoch:       room.Epoch,
		UpdatedAtMs: time.Now().UnixMilli(),
	}
	// contentType is meaningful only while a share is active. `active=false`
	// collapses to a cleared state so suspended peers don't see a stale
	// "screen sharing" indicator after the share stops.
	if active {
		if contentType, ok := payload["contentType"].(string); ok {
			state.ContentType = contentType
		}
	}
	p.ContentState = state
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

// handleMediaLiveness records peer-reported inbound-media observations for
// suspended CIDs. Used as a cleanup hint in hardEvictSuspended so a
// suspended participant whose signaling transport is late to recover but
// whose peer media is still flowing is not removed solely because the
// directory clock fired. The hint is NEVER used for authorization or
// authority — only to defer eviction.
//
// Wire payload:
//
//	{ "v":1, "type":"media_liveness", "payload":{ "cids":["C-..","C-.."] } }
//
// Reports apply to the sender's room. Unknown CIDs are ignored.
func (h *Hub) handleMediaLiveness(c *Client, msg Message) {
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
		CIDs []string `json:"cids"`
	}
	if err := json.Unmarshal(msg.Payload, &payload); err != nil {
		return
	}

	now := time.Now().UnixMilli()
	room.mu.Lock()
	// Sender must be an attached participant in this room. Resolve the CID
	// from the room index, NOT c.cid — stale-socket fields can outlive a
	// reattach and we should authorize / self-skip against the index, like
	// handleRelay does.
	senderCID := room.cidForClient(c)
	if senderCID == "" {
		room.mu.Unlock()
		return
	}
	room.recordMediaLivenessReporter(senderCID, now)
	for _, reportedCID := range payload.CIDs {
		if reportedCID == "" || reportedCID == senderCID {
			continue
		}
		if room.participantByCID(reportedCID) == nil {
			continue
		}
		room.recordMediaLiveness(reportedCID, now)
	}
	// Fast-path ghost eviction: any suspended CID that has cleared the
	// minimum dwell, has no recent positive media report, and is excluded
	// from every active peer's recent media_liveness is a ghost — evict
	// without waiting for the full 10-minute hard-evict timer. Compute the
	// candidate list under the lock, then drop the lock before calling
	// hardEvictSuspended (which re-acquires it).
	ghosts := room.suspendedGhostsExcludedByActiveReporters(now, ghostEvictMinDwell, mediaLivenessFreshnessWindow)
	rid := room.RID
	room.mu.Unlock()
	for _, cid := range ghosts {
		log.Printf("[HARD_EVICT] Fast-path eviction of suspended CID %s in room %s — all active peers report no inbound media", cid, rid)
		h.hardEvictSuspended(room, cid)
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
	room.bumpEpoch()
	log.Printf("[SUSPEND] Client %s (CID: %s) suspended in room %s. Hard eviction in %s", c.sid, cid, rid, suspendHardEvictionTimeout)

	// Close over this specific *Room so a timer firing after the room has been
	// replaced (end_room + new join reusing the rid) cannot evict the wrong
	// participant. Reattach on reconnect stops the timer in reattachClient.
	p.hardEvictionTimer = time.AfterFunc(suspendHardEvictionTimeout, func() {
		h.hardEvictSuspended(room, cid)
	})

	activeCount := len(room.byClient)
	if activeCount == 0 {
		h.armNoActiveRoomTimerLocked(room)
	}
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
//
// If active peers have recently reported inbound media from this CID, eviction
// is deferred for a short window and re-evaluated. media_liveness is a
// cleanup hint only — it never extends the slot indefinitely, but a peer
// whose signaling transport is late to recover should not be removed solely
// because the directory clock fired.
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

	if room.hasRecentMediaLiveness(cid, mediaLivenessFreshnessWindow) {
		log.Printf("[HARD_EVICT] Deferring eviction of CID %s in room %s — recent media-liveness hint", cid, rid)
		// Re-arm; we'll re-check once peers either go quiet or the participant
		// reattaches.
		p.hardEvictionTimer = time.AfterFunc(hardEvictMediaActiveDeferral, func() {
			h.hardEvictSuspended(room, cid)
		})
		room.mu.Unlock()
		return
	}

	log.Printf("[HARD_EVICT] Suspend window expired for CID %s in room %s. Removing participant.", cid, rid)
	room.removeParticipant(cid)
	room.dropCIDFromDirty(cid)
	room.dropMediaLivenessFor(cid)
	room.transferHostIfNeeded(cid)
	room.bumpEpoch()

	isEmpty := room.participantCount() == 0
	if !isEmpty && len(room.byClient) == 0 {
		h.armNoActiveRoomTimerLocked(room)
	}
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

// evictSuspendedParticipantForExpiredReconnect removes a suspended record
// when a reconnect attempt proves it belongs to the same CID/RID but its
// token has aged out. The caller has already validated the token signature;
// do not call this for malformed or unsigned tokens.
func (h *Hub) evictSuspendedParticipantForExpiredReconnect(rid, cid string) {
	if rid == "" || cid == "" {
		return
	}

	h.mu.RLock()
	room, exists := h.rooms[rid]
	h.mu.RUnlock()
	if !exists {
		return
	}

	room.mu.Lock()
	p := room.participantByCID(cid)
	if p == nil || p.Client != nil {
		room.mu.Unlock()
		return
	}

	log.Printf("[RECONNECT] Evicting suspended CID %s in room %s after expired reconnect token", cid, rid)
	room.removeParticipant(cid)
	room.dropCIDFromDirty(cid)
	room.dropMediaLivenessFor(cid)
	room.transferHostIfNeeded(cid)
	room.bumpEpoch()

	isEmpty := room.participantCount() == 0
	if !isEmpty && len(room.byClient) == 0 {
		h.armNoActiveRoomTimerLocked(room)
	}
	room.mu.Unlock()

	if isEmpty {
		h.mu.Lock()
		if h.rooms[rid] == room {
			delete(h.rooms, rid)
		}
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
	room.dropCIDFromDirty(cid)
	room.dropMediaLivenessFor(cid)
	room.bumpEpoch()
	log.Printf("[REMOVE_FROM_ROOM] Client %s (CID: %s) removed from room %s. Remaining participants: %d", c.sid, cid, rid, room.participantCount())
	room.transferHostIfNeeded(cid)

	isEmpty := room.participantCount() == 0
	if !isEmpty && len(room.byClient) == 0 {
		h.armNoActiveRoomTimerLocked(room)
	}
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

// evictByLeave is the explicit terminal-leave path used by `POST /api/leave`.
// Unlike `disconnectClient` it does not start a suspend window — the
// participant is removed immediately (the user explicitly chose to leave or
// end the call, often during page unload). Idempotent: a second call for an
// already-evicted CID is a no-op.
//
// The caller is responsible for verifying the reconnect token before calling
// this helper. The participant record is removed regardless of whether a
// signaling transport is currently attached, and any attached *Client is
// detached + suspended-record cleaned up so the next reconnect with a stale
// token can not silently revive the slot.
func (h *Hub) evictByLeave(rid, cid string) {
	h.mu.RLock()
	room, exists := h.rooms[rid]
	h.mu.RUnlock()
	if !exists {
		return
	}

	room.mu.Lock()
	p := room.participantByCID(cid)
	if p == nil {
		room.mu.Unlock()
		return
	}
	attachedClient := p.Client
	room.removeParticipant(cid)
	room.dropCIDFromDirty(cid)
	room.dropMediaLivenessFor(cid)
	room.transferHostIfNeeded(cid)
	room.bumpEpoch()
	isEmpty := room.participantCount() == 0
	if !isEmpty && len(room.byClient) == 0 {
		h.armNoActiveRoomTimerLocked(room)
	}
	room.mu.Unlock()

	if attachedClient != nil {
		h.cleanupEvictedClient(attachedClient)
	}

	if isEmpty {
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
	epoch := room.Epoch
	// Only broadcast to actively-attached clients; suspended participants
	// have no transport to receive messages.
	clients := room.activeClients()
	room.mu.Unlock()

	payload := map[string]interface{}{
		"hostCid":         hostCid,
		"participants":    participants,
		"maxParticipants": roomMaxParticipants,
		"epoch":           epoch,
	}
	payloadBytes, _ := json.Marshal(payload)

	log.Printf("[BROADCAST] Room State for %s: %d participants epoch=%d", rid, len(participants), epoch)

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

// sendRoomStateSnapshot delivers an authoritative room_state to a single
// client. Used after a successful reconnect so SDKs have a confirmed sync
// point on the new transport before scheduling renegotiation, regardless of
// whether the epoch advanced during the outage.
func (h *Hub) sendRoomStateSnapshot(c *Client, room *Room) {
	room.mu.Lock()
	participants := room.snapshotParticipants()
	hostCid := room.HostCID
	rid := room.RID
	roomMaxParticipants := room.MaxParticipants
	epoch := room.Epoch
	room.mu.Unlock()

	payload := map[string]interface{}{
		"hostCid":         hostCid,
		"participants":    participants,
		"maxParticipants": roomMaxParticipants,
		"epoch":           epoch,
	}
	payloadBytes, _ := json.Marshal(payload)
	c.sendMessage(Message{
		V:       1,
		Type:    "room_state",
		RID:     rid,
		Payload: payloadBytes,
	})
}

// notifyDirtyNegotiation informs each active peer that they had pending
// signaling traffic to the given CID while it was suspended. SDKs use this to
// schedule a fresh glare-safe negotiation/ICE-restart after the authoritative
// post-reconnect snapshot, instead of waiting for an answer that will never
// arrive.
func (h *Hub) notifyDirtyNegotiation(room *Room, recoveredCID string, partners []string) {
	if len(partners) == 0 {
		return
	}
	payload, _ := json.Marshal(map[string]interface{}{
		"with": recoveredCID,
	})
	msg := Message{
		V:       1,
		Type:    "negotiation_dirty",
		RID:     room.RID,
		Payload: payload,
	}
	room.mu.Lock()
	targets := make([]*Client, 0, len(partners))
	for _, partnerCID := range partners {
		p := room.participantByCID(partnerCID)
		if p == nil || p.Client == nil {
			continue
		}
		targets = append(targets, p.Client)
	}
	room.mu.Unlock()
	for _, client := range targets {
		client.sendMessage(msg)
	}
}

func (c *Client) sendError(rid, code, message string) {
	c.sendErrorWithReason(rid, code, message, "")
}

// sendErrorWithReason mirrors sendError but emits an additional `reason`
// field on the error payload. Used for terminal codes (e.g. ROOM_ENDED)
// where the SDK wants the trigger surfaced for UX or telemetry.
func (c *Client) sendErrorWithReason(rid, code, message, reason string) {
	payload := map[string]interface{}{
		"code":    code,
		"message": message,
	}
	if reason != "" {
		payload["reason"] = reason
	}
	body, _ := json.Marshal(payload)
	c.sendMessage(Message{
		V:       1,
		Type:    "error",
		RID:     rid,
		Payload: body,
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
