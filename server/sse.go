package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"serenada/server/internal/stats"
)

const (
	ssePingPeriod            = 12 * time.Second
	sseGracePeriod           = 5 * time.Second
	sseStaleTimeoutIdle      = 60 * time.Second  // clients not in a room
	sseStaleTimeoutInRoom    = 5 * time.Minute    // clients currently in a room
	sseReaperInterval        = 15 * time.Second
)

func (h *Hub) run() {
	ticker := time.NewTicker(sseReaperInterval)
	defer ticker.Stop()
	for range ticker.C {
		h.evictStaleSSE()
	}
}

func handleSSE(hub *Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			serveSSE(hub, w, r)
		case http.MethodPost:
			handleSSEPost(hub, w, r)
		default:
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		}
	}
}

func serveSSE(hub *Hub, w http.ResponseWriter, r *http.Request) {
	stats.IncConnectionAttempt("sse")

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		stats.IncConnectionFailure("sse")
		return
	}

	sid := strings.TrimSpace(r.URL.Query().Get("sid"))
	if sid == "" {
		sid = generateID("S-")
	}

	ip := getClientIP(r)
	client := &Client{hub: hub, send: make(chan []byte, 256), sid: sid, ip: ip, transport: TransportSSE}
	existing := hub.getClientBySID(sid)
	isPending := false
	if existing != nil && existing.rid != "" {
		// Potential participant takeover. Place in pendingTakeovers.
		hub.registerPendingTakeover(sid, client)
		isPending = true
	} else if existing != nil {
		// Existing is not in a room, safe to replace directly.
		hub.replaceClient(existing, client)
	} else {
		hub.registerClient(client)
		stats.AddActiveSSEClients(1)
	}
	stats.IncConnectionSuccess("sse")
	hub.markSSESeen(client)

	log.Printf("[SSE] Client %s connected (pending=%t)", client.sid, isPending)

	if _, err := w.Write([]byte(": ready\n\n")); err != nil {
		hub.handleDisconnectSSE(client)
		return
	}
	flusher.Flush()

	// Keep the connection open until the client disconnects.
	ctxDone := r.Context().Done()
	client.writeSSE(w, flusher, ctxDone)

	hub.handleDisconnectSSE(client)
}

func handleSSEPost(hub *Hub, w http.ResponseWriter, r *http.Request) {
	sid := strings.TrimSpace(r.URL.Query().Get("sid"))
	if sid == "" {
		http.Error(w, "Missing SSE session", http.StatusBadRequest)
		return
	}

	existing := hub.getClientBySID(sid)
	pending := hub.getPendingTakeover(sid)

	if existing == nil && pending == nil {
		http.Error(w, "Unknown SSE session", http.StatusGone)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxMessageSize)
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	if len(bytes.TrimSpace(body)) == 0 {
		http.Error(w, "Empty request body", http.StatusBadRequest)
		return
	}

	// If there is a pending takeover connection, validate the reconnect token
	// inside the incoming join message before promoting the takeover.
	if pending != nil && existing != nil {
		var msg Message
		if err := json.Unmarshal(body, &msg); err != nil {
			http.Error(w, "Invalid JSON", http.StatusBadRequest)
			return
		}

		if msg.V != 1 {
			http.Error(w, "Unsupported version", http.StatusBadRequest)
			return
		}

		if msg.Type != "join" {
			http.Error(w, "Unauthorized session takeover", http.StatusUnauthorized)
			return
		}

		var joinPayload struct {
			ReconnectCID   string `json:"reconnectCid"`
			ReconnectToken string `json:"reconnectToken"`
		}
		if err := json.Unmarshal(msg.Payload, &joinPayload); err != nil {
			http.Error(w, "Invalid join payload", http.StatusBadRequest)
			return
		}

		if joinPayload.ReconnectCID == "" || joinPayload.ReconnectToken == "" {
			http.Error(w, "Missing reconnect credentials for takeover", http.StatusUnauthorized)
			return
		}

		valid, _ := validateReconnectToken(joinPayload.ReconnectToken, joinPayload.ReconnectCID, msg.RID)
		if !valid {
			log.Printf("[SSE] Rejecting session takeover for SID %s: invalid reconnect token", sid)
			http.Error(w, "Invalid reconnect token", http.StatusUnauthorized)
			return
		}

		log.Printf("[SSE] Session takeover authorized for SID %s (CID: %s)", sid, joinPayload.ReconnectCID)
		hub.replaceClient(existing, pending)
		hub.removePendingTakeover(sid)
		existing = nil // Now pending is replaced and registered; use pending.
	}

	var client *Client
	if pending != nil {
		client = pending
	} else {
		client = existing
	}

	hub.markSSESeen(client)
	hub.handleMessage(client, body)
	w.WriteHeader(http.StatusNoContent)
}

func (c *Client) writeSSE(w http.ResponseWriter, flusher http.Flusher, done <-chan struct{}) {
	ticker := time.NewTicker(ssePingPeriod)
	defer ticker.Stop()

	for {
		select {
		case <-done:
			return
		case msg, ok := <-c.send:
			if !ok {
				return
			}
			if err := writeSSEMessage(w, flusher, msg); err != nil {
				return
			}
		case <-ticker.C:
			if _, err := w.Write([]byte(": ping\n\n")); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}

func writeSSEMessage(w http.ResponseWriter, flusher http.Flusher, data []byte) error {
	lines := bytes.Split(data, []byte("\n"))
	for _, line := range lines {
		if _, err := w.Write([]byte("data: ")); err != nil {
			return err
		}
		if _, err := w.Write(line); err != nil {
			return err
		}
		if _, err := w.Write([]byte("\n")); err != nil {
			return err
		}
	}
	if _, err := w.Write([]byte("\n")); err != nil {
		return err
	}
	flusher.Flush()
	return nil
}

func (h *Hub) markSSESeen(c *Client) {
	atomic.StoreInt64(&c.lastSeen, time.Now().UnixNano())
}

func (h *Hub) handleDisconnectSSE(c *Client) {
	if c.replaced {
		h.mu.Lock()
		delete(h.clients, c)
		h.mu.Unlock()
		return
	}
	// Also clean up from pendingTakeovers if it was there
	h.mu.Lock()
	if h.pendingTakeovers[c.sid] == c {
		delete(h.pendingTakeovers, c.sid)
	}
	h.mu.Unlock()

	stats.IncDisconnect("sse")
	go h.delayDisconnectSSE(c)
}

func (h *Hub) delayDisconnectSSE(c *Client) {
	time.Sleep(sseGracePeriod)
	h.mu.RLock()
	current := h.clientsBySID[c.sid]
	h.mu.RUnlock()
	if current != c {
		return
	}
	h.disconnectClient(c)
}

func (h *Hub) evictStaleSSE() {
	now := time.Now().UnixNano()
	cutoffIdle := now - sseStaleTimeoutIdle.Nanoseconds()
	cutoffInRoom := now - sseStaleTimeoutInRoom.Nanoseconds()
	stale := make([]*Client, 0)

	h.mu.RLock()
	for client := range h.clients {
		if client.transport != TransportSSE || client.replaced {
			continue
		}
		lastSeen := atomic.LoadInt64(&client.lastSeen)
		if lastSeen == 0 {
			continue
		}
		// Use longer timeout for clients in a room (active call participants)
		cutoff := cutoffIdle
		if client.rid != "" {
			cutoff = cutoffInRoom
		}
		if lastSeen < cutoff {
			stale = append(stale, client)
		}
	}
	h.mu.RUnlock()

	for _, client := range stale {
		stats.IncDisconnect("sse_stale")
		h.disconnectClient(client)
	}
}
