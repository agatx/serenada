package main

import (
	"net/http"
	"os"
	"strings"
)

var (
	allowedOrigins = parseAllowedOrigins(os.Getenv("ALLOWED_ORIGINS"))
)

func parseAllowedOrigins(raw string) map[string]bool {
	origins := make(map[string]bool)
	for _, origin := range strings.Split(raw, ",") {
		trimmed := strings.TrimSpace(origin)
		if trimmed != "" {
			origins[trimmed] = true
		}
	}
	return origins
}

func isOriginAllowed(r *http.Request) bool {
	origin := strings.TrimSpace(r.Header.Get("Origin"))
	if origin == "" {
		return true
	}

	if allowedOrigins[origin] {
		return true
	}

	// Allow any localhost origin for local development
	if strings.HasPrefix(origin, "http://localhost:") || origin == "http://localhost" {
		return true
	}

	host := strings.TrimSpace(r.Host)
	if host == "" {
		return false
	}
	if origin == "https://"+host || origin == "http://"+host {
		return true
	}

	return false
}
