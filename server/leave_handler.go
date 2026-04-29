package main

import (
	"encoding/json"
	"log"
	"net/http"

	"serenada/server/internal/stats"
)

// handleLeave returns an HTTP handler for the explicit terminal-leave path.
//
// SDKs invoke this via `navigator.sendBeacon` or a normal HTTPS POST when the
// user has explicitly chosen to leave (or end) the call and the page may
// unload before the signaling-level `leave` message can be flushed. The body
// must carry a valid `reconnectToken` for the (rid, cid) tuple — without that
// proof the request is rejected, since this endpoint bypasses the suspension
// hold and immediately hard-evicts the participant.
//
// Idempotent: repeated calls for an already-evicted CID return 204 without
// further side effects.
func handleLeave(hub *Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}

		var body struct {
			RID            string `json:"rid"`
			CID            string `json:"cid"`
			ReconnectToken string `json:"reconnectToken"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&body); err != nil {
			http.Error(w, "Invalid JSON", http.StatusBadRequest)
			return
		}
		if body.RID == "" || body.CID == "" || body.ReconnectToken == "" {
			http.Error(w, "Missing rid, cid, or reconnectToken", http.StatusBadRequest)
			return
		}
		if err := validateRoomID(body.RID); err != nil {
			http.Error(w, "Invalid room id", http.StatusBadRequest)
			return
		}
		if reconnectSecret() == nil {
			log.Printf("[API_LEAVE] Rejecting leave for CID %s in room %s: reconnect token secret is not configured", body.CID, body.RID)
			http.Error(w, "Reconnect token validation unavailable", http.StatusUnauthorized)
			return
		}
		ok, _ := validateReconnectToken(body.ReconnectToken, body.CID, body.RID)
		if !ok {
			http.Error(w, "Invalid reconnect token", http.StatusUnauthorized)
			return
		}

		hub.evictByLeave(body.RID, body.CID)
		stats.IncMessageRX("api_leave")

		w.WriteHeader(http.StatusNoContent)
		log.Printf("[API_LEAVE] Evicted CID %s from room %s", body.CID, body.RID)
	}
}
