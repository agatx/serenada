package main

import (
	"encoding/json"
	"log"
	"net/http"
)

func handleRoomID() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost && r.Method != http.MethodGet {
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}

		roomID, err := generateRoomID()
		if err != nil {
			log.Printf("room id generation failed: %v", err)
			http.Error(w, "Room ID service unavailable", http.StatusServiceUnavailable)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		json.NewEncoder(w).Encode(map[string]string{
			"roomId": roomID,
		})
	}
}
