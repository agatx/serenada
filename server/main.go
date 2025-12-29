package main

import (
	"log"
	"net/http"
	"os"
)

func main() {
	// Initialize signaling
	hub := newHub()
	go hub.run()

	// Simple CORS middleware for API
	enableCors := func(h http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Access-Control-Allow-Origin", "*")
			if r.Method == "OPTIONS" {
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
				return
			}
			h(w, r)
		}
	}

	// Rate Limiters
	// WS: 10 connections per minute per IP
	wsLimiter := NewIPLimiter(10.0/60.0, 5)

	// API: 10 requests per minute per IP
	apiLimiter := NewIPLimiter(10.0/60.0, 5)

	http.HandleFunc("/ws", rateLimitMiddleware(wsLimiter, func(w http.ResponseWriter, r *http.Request) {
		serveWs(hub, w, r)
	}))

	http.HandleFunc("/api/turn-credentials", rateLimitMiddleware(apiLimiter, enableCors(handleTurnCredentials)))

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	log.Printf("Server executing on :%s", port)
	if err := http.ListenAndServe(":"+port, nil); err != nil {
		log.Fatal("ListenAndServe: ", err)
	}
}
