package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/joho/godotenv"
)

func main() {
	// Load .env from current directory or parent directory (for local dev)
	_ = godotenv.Load()
	_ = godotenv.Load("../.env")

	turnTokenStore := NewTurnTokenStore(5 * time.Minute)
	diagnosticTokenStore := NewTurnTokenStore(5 * time.Second)

	// Initialize signaling
	hub := newHub(turnTokenStore)
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
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Turn-Token")
				w.WriteHeader(http.StatusNoContent)
				return
			}
			h(w, r)
		}
	}

	// Rate Limiters
	// WS: 10 connections per minute per IP
	wsLimiter := NewIPLimiter(10.0/60.0, 5)

	// API: 5 requests per minute per IP
	turnCredsLimiter := NewIPLimiter(5.0/60.0, 5)
	diagnosticLimiter := NewIPLimiter(5.0/60.0, 5)
	// Room ID: 30 requests per minute per IP
	roomIDLimiter := NewIPLimiter(30.0/60.0, 10)

	http.HandleFunc("/ws", rateLimitMiddleware(wsLimiter, func(w http.ResponseWriter, r *http.Request) {
		serveWs(hub, w, r)
	}))

	http.HandleFunc("/api/turn-credentials", rateLimitMiddleware(turnCredsLimiter, enableCors(handleTurnCredentials(turnTokenStore, diagnosticTokenStore))))
	http.HandleFunc("/api/diagnostic-token", rateLimitMiddleware(diagnosticLimiter, enableCors(handleDiagnosticToken(diagnosticTokenStore))))
	http.HandleFunc("/api/room-id", rateLimitMiddleware(roomIDLimiter, enableCors(handleRoomID())))

	http.HandleFunc("/device-check", handleDeviceCheck)

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	log.Printf("Server executing on :%s", port)
	server := &http.Server{
		Addr:              ":" + port,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	if err := server.ListenAndServe(); err != nil {
		log.Fatal("ListenAndServe: ", err)
	}
}
