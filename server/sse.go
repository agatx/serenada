package main

import (
	"bytes"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

const ssePingPeriod = 15 * time.Second

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
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		return
	}

	sid := strings.TrimSpace(r.URL.Query().Get("sid"))
	if sid == "" {
		sid = generateID("S-")
	}

	ip := getClientIP(r)
	client := &Client{hub: hub, send: make(chan []byte, 256), sid: sid, ip: ip}
	if existing := hub.getClientBySID(sid); existing != nil {
		hub.replaceClient(existing, client)
	} else {
		hub.registerClient(client)
	}

	log.Printf("[SSE] Client %s connected", client.sid)

	if _, err := w.Write([]byte(": ready\n\n")); err != nil {
		hub.handleDisconnect(client)
		return
	}
	flusher.Flush()

	// Keep the connection open until the client disconnects.
	ctxDone := r.Context().Done()
	client.writeSSE(w, flusher, ctxDone)

	hub.handleDisconnect(client)
}

func handleSSEPost(hub *Hub, w http.ResponseWriter, r *http.Request) {
	sid := strings.TrimSpace(r.Header.Get("X-SSE-SID"))
	if sid == "" {
		sid = strings.TrimSpace(r.URL.Query().Get("sid"))
	}
	if sid == "" {
		http.Error(w, "Missing SSE session", http.StatusBadRequest)
		return
	}

	client := hub.getClientBySID(sid)
	if client == nil {
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
