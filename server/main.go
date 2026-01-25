package main

import (
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/joho/godotenv"
)

func main() {
	// Load .env from current directory or parent directory (for local dev)
	_ = godotenv.Load()
	_ = godotenv.Load("../.env")

	// Initialize signaling
	hub := newHub()
	go hub.run()

	// Simple CORS middleware for API
	enableCors := func(h http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if !isOriginAllowed(r) {
				http.Error(w, "Forbidden", http.StatusForbidden)
				return
			}
			origin := r.Header.Get("Origin")
			if origin != "" {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Vary", "Origin")
			}
			if r.Method == "OPTIONS" {
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
				w.WriteHeader(http.StatusNoContent)
				return
			}
			h(w, r)
		}
	}

	withTimeout := func(h http.HandlerFunc, d time.Duration) http.HandlerFunc {
		if d <= 0 {
			return h
		}
		return func(w http.ResponseWriter, r *http.Request) {
			http.TimeoutHandler(h, d, "Request timed out").ServeHTTP(w, r)
		}
	}

	// Rate Limiters
	// WS: 10 connections per minute per IP
	wsLimiter := NewIPLimiter(10.0/60.0, 5)
	// SSE: allow bursts for signaling messages
	sseLimiter := NewIPLimiter(1200.0/60.0, 200)
	wsBlockMode := strings.TrimSpace(os.Getenv("BLOCK_WEBSOCKET"))
	wsHang := strings.EqualFold(wsBlockMode, "hang")
	wsBlocked := !wsHang && strings.EqualFold(wsBlockMode, "block")

	// API: 5 requests per minute per IP
	turnCredsLimiter := NewIPLimiter(5.0/60.0, 5)
	diagnosticLimiter := NewIPLimiter(5.0/60.0, 5)
	// Room ID: 30 requests per minute per IP
	roomIDLimiter := NewIPLimiter(30.0/60.0, 10)

	http.HandleFunc("/ws", rateLimitMiddleware(wsLimiter, func(w http.ResponseWriter, r *http.Request) {
		if wsHang {
			hangWebSocket(w)
			return
		}
		if wsBlocked {
			http.Error(w, "WebSocket blocked (simulated)", http.StatusForbidden)
			return
		}
		serveWs(hub, w, r)
	}))
	http.HandleFunc("/sse", rateLimitMiddleware(sseLimiter, enableCors(handleSSE(hub))))

	http.HandleFunc("/api/turn-credentials", withTimeout(rateLimitMiddleware(turnCredsLimiter, enableCors(handleTurnCredentials())), 15*time.Second))
	http.HandleFunc("/api/diagnostic-token", withTimeout(rateLimitMiddleware(diagnosticLimiter, enableCors(handleDiagnosticToken())), 15*time.Second))
	http.HandleFunc("/api/room-id", withTimeout(rateLimitMiddleware(roomIDLimiter, enableCors(handleRoomID())), 15*time.Second))

	http.HandleFunc("/device-check", withTimeout(handleDeviceCheck, 15*time.Second))

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	log.Printf("Server executing on :%s", port)
	server := &http.Server{
		Addr:              ":" + port,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      0,
		IdleTimeout:       60 * time.Second,
	}
	if err := server.ListenAndServe(); err != nil {
		log.Fatal("ListenAndServe: ", err)
	}
}

func hangWebSocket(w http.ResponseWriter) {
	if hj, ok := w.(http.Hijacker); ok {
		conn, _, err := hj.Hijack()
		if err == nil {
			time.Sleep(30 * time.Second)
			conn.Close()
			return
		}
	}
	time.Sleep(30 * time.Second)
}
