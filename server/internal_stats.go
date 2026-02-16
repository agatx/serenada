package main

import (
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"os"
	"strings"

	"serenada/server/internal/stats"
)

func handleInternalStats(hub *Hub) http.HandlerFunc {
	enabled := strings.EqualFold(strings.TrimSpace(os.Getenv("ENABLE_INTERNAL_STATS")), "1")
	requiredToken := strings.TrimSpace(os.Getenv("INTERNAL_STATS_TOKEN"))

	return func(w http.ResponseWriter, r *http.Request) {
		if !enabled {
			http.NotFound(w, r)
			return
		}
		if requiredToken == "" {
			http.Error(w, "Internal stats token is required", http.StatusServiceUnavailable)
			return
		}

		if r.Method != http.MethodGet {
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}

		provided := strings.TrimSpace(r.Header.Get("X-Internal-Token"))
		if subtle.ConstantTimeCompare([]byte(provided), []byte(requiredToken)) != 1 {
			http.Error(w, "Unauthorized", http.StatusUnauthorized)
			return
		}

		hub.refreshStatsGauges()
		snapshot := stats.SnapshotNow()

		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(snapshot)
	}
}
