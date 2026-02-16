package main

import (
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// SimpleTokenBucket implements a token bucket rate limiter.
type SimpleTokenBucket struct {
	tokens         float64
	capacity       float64
	refillRate     float64 // tokens per second
	lastRefillTime time.Time
	mu             sync.Mutex
}

func NewSimpleTokenBucket(capacity float64, refillRate float64) *SimpleTokenBucket {
	// Initial full bucket
	return &SimpleTokenBucket{
		tokens:         capacity,
		capacity:       capacity,
		refillRate:     refillRate,
		lastRefillTime: time.Now(),
	}
}

func (tb *SimpleTokenBucket) Allow() bool {
	tb.mu.Lock()
	defer tb.mu.Unlock()

	now := time.Now()
	// Refill tokens based on time elapsed
	elapsed := now.Sub(tb.lastRefillTime).Seconds()
	tb.tokens = tb.tokens + elapsed*tb.refillRate
	if tb.tokens > tb.capacity {
		tb.tokens = tb.capacity
	}
	tb.lastRefillTime = now

	if tb.tokens >= 1.0 {
		tb.tokens -= 1.0
		return true
	}
	return false
}

// Global Rate Limiter Manager
type IPLimiter struct {
	ips   map[string]*SimpleTokenBucket
	mu    sync.Mutex
	rate  float64
	burst float64
}

func NewIPLimiter(r float64, b float64) *IPLimiter {
	return &IPLimiter{
		ips:   make(map[string]*SimpleTokenBucket),
		rate:  r,
		burst: b,
	}
}

func (i *IPLimiter) GetLimiter(ip string) *SimpleTokenBucket {
	i.mu.Lock()
	defer i.mu.Unlock()

	limiter, exists := i.ips[ip]
	if !exists {
		limiter = NewSimpleTokenBucket(i.burst, i.rate)
		i.ips[ip] = limiter
	}

	return limiter
}

// Cleanup routine to remove old IPs could be added here to prevent memory leaks

// Middleware
func rateLimitMiddleware(limiter *IPLimiter, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ip := getClientIP(r)
		if !limiter.GetLimiter(ip).Allow() {
			http.Error(w, "429 Too Many Requests", http.StatusTooManyRequests)
			log.Printf("Rate limit exceeded for IP: %s", ip)
			return
		}
		next(w, r)
	}
}

func getClientIP(r *http.Request) string {
	trustProxy := strings.EqualFold(os.Getenv("TRUST_PROXY"), "1")
	if trustProxy {
		realIP := strings.TrimSpace(r.Header.Get("X-Real-IP"))
		if realIP != "" {
			return realIP
		}
		// Check X-Forwarded-For first (since we are behind Nginx)
		forwarded := r.Header.Get("X-Forwarded-For")
		if forwarded != "" {
			// X-Forwarded-For can be a comma-separated list
			ips := strings.Split(forwarded, ",")
			return strings.TrimSpace(ips[0])
		}
	}

	// Fallback to RemoteAddr
	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return ip
}
