package main

import (
	"errors"
	"flag"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

type Config struct {
	BaseURL  string
	WSURL    string
	StatsURL string

	StatsToken string

	StartClients int
	StepClients  int
	MaxClients   int

	RampSeconds             int
	SteadySeconds           int
	CooldownSeconds         int
	PreRampStabilizeSeconds int

	RoomsMode string

	OfferRatePerRoom float64

	ReconnectStormPercent  float64
	ReconnectStormAtSecond int

	ReportJSON string

	JoinTimeoutSeconds int

	MaxErrorRate      float64
	MaxJoinErrorRate  float64
	MaxJoinP95Ms      int64
	MaxSendQueueDrops int64

	RoomIDSecret string
	RoomIDEnv    string

	RandomSeed int64
}

func parseConfig(args []string) (Config, error) {
	cfg := Config{}

	fs := flag.NewFlagSet("loadconduit", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)

	fs.StringVar(&cfg.BaseURL, "base-url", "http://localhost", "Base HTTP URL of the server")
	fs.StringVar(&cfg.WSURL, "ws-url", "", "WebSocket URL override (defaults to <base-url>/ws)")
	fs.StringVar(&cfg.StatsURL, "stats-url", "/api/internal/stats", "Internal stats endpoint path or absolute URL")
	fs.StringVar(&cfg.StatsToken, "stats-token", "", "Optional token for X-Internal-Token header")

	fs.IntVar(&cfg.StartClients, "start-clients", 20, "Initial concurrent clients")
	fs.IntVar(&cfg.StepClients, "step-clients", 20, "Clients added per step")
	fs.IntVar(&cfg.MaxClients, "max-clients", 100, "Maximum concurrent clients")

	fs.IntVar(&cfg.RampSeconds, "ramp-seconds", 60, "Ramp duration per step in seconds")
	fs.IntVar(&cfg.SteadySeconds, "steady-seconds", 600, "Steady-state duration per step in seconds")
	fs.IntVar(&cfg.CooldownSeconds, "cooldown-seconds", 15, "Cooldown duration between steps in seconds")
	fs.IntVar(&cfg.PreRampStabilizeSeconds, "pre-ramp-stabilize-seconds", 10, "Wait time before each step ramp to allow server to stabilize")

	fs.StringVar(&cfg.RoomsMode, "rooms-mode", "paired", "Room population mode (paired)")
	fs.Float64Var(&cfg.OfferRatePerRoom, "offer-rate-per-room", 0.2, "Relay message rate per room per second")
	fs.Float64Var(&cfg.ReconnectStormPercent, "reconnect-storm-percent", 0, "Percent of clients to reconnect during steady window")
	fs.IntVar(&cfg.ReconnectStormAtSecond, "reconnect-storm-at-second", 0, "Second offset into steady window to trigger reconnect storm")

	fs.StringVar(&cfg.ReportJSON, "report-json", "", "Optional path to write JSON report")
	fs.IntVar(&cfg.JoinTimeoutSeconds, "join-timeout-seconds", 20, "Per-client join timeout in seconds")

	fs.Float64Var(&cfg.MaxErrorRate, "max-error-rate", 0.01, "Step pass threshold: max error rate")
	fs.Float64Var(&cfg.MaxJoinErrorRate, "max-join-error-rate", 0, "Step pass threshold: max join miss rate ((target-joinSuccess)/target)")
	fs.Int64Var(&cfg.MaxJoinP95Ms, "max-join-p95-ms", 2000, "Step pass threshold: max join p95 in ms")
	fs.Int64Var(&cfg.MaxSendQueueDrops, "max-send-queue-drops", 0, "Step pass threshold: max send queue drops in step")

	defaultRoomIDSecret := strings.TrimSpace(os.Getenv("ROOM_ID_SECRET"))
	defaultRoomIDEnv := strings.TrimSpace(os.Getenv("ROOM_ID_ENV"))
	if defaultRoomIDEnv == "" {
		defaultRoomIDEnv = "dev"
	}
	fs.StringVar(&cfg.RoomIDSecret, "room-id-secret", defaultRoomIDSecret, "Optional room ID secret to generate room IDs locally")
	fs.StringVar(&cfg.RoomIDEnv, "room-id-env", defaultRoomIDEnv, "Room ID env context (used only with --room-id-secret)")
	fs.Int64Var(&cfg.RandomSeed, "random-seed", 1, "Deterministic seed for reconnect-storm sampling")

	if err := fs.Parse(args); err != nil {
		return Config{}, err
	}

	if err := cfg.validate(); err != nil {
		return Config{}, err
	}

	cfg.BaseURL = strings.TrimSpace(cfg.BaseURL)
	cfg.WSURL = strings.TrimSpace(cfg.WSURL)
	cfg.StatsURL = strings.TrimSpace(cfg.StatsURL)
	cfg.StatsToken = strings.TrimSpace(cfg.StatsToken)
	cfg.RoomIDSecret = strings.TrimSpace(cfg.RoomIDSecret)
	cfg.RoomIDEnv = strings.TrimSpace(cfg.RoomIDEnv)
	cfg.ReportJSON = strings.TrimSpace(cfg.ReportJSON)

	if cfg.WSURL == "" {
		base, _ := url.Parse(cfg.BaseURL)
		scheme := "ws"
		if strings.EqualFold(base.Scheme, "https") {
			scheme = "wss"
		}
		cfg.WSURL = fmt.Sprintf("%s://%s/ws", scheme, base.Host)
	}

	if cfg.ReportJSON != "" {
		cfg.ReportJSON = filepath.Clean(cfg.ReportJSON)
	}

	return cfg, nil
}

func (c Config) validate() error {
	if strings.TrimSpace(c.BaseURL) == "" {
		return errors.New("base-url is required")
	}
	if _, err := url.ParseRequestURI(c.BaseURL); err != nil {
		return fmt.Errorf("base-url is invalid: %w", err)
	}

	if strings.TrimSpace(c.WSURL) != "" {
		u, err := url.ParseRequestURI(c.WSURL)
		if err != nil {
			return fmt.Errorf("ws-url is invalid: %w", err)
		}
		if u.Scheme != "ws" && u.Scheme != "wss" {
			return errors.New("ws-url must use ws or wss")
		}
	}

	if strings.TrimSpace(c.StatsURL) == "" {
		return errors.New("stats-url is required")
	}
	if strings.HasPrefix(c.StatsURL, "http://") || strings.HasPrefix(c.StatsURL, "https://") {
		if _, err := url.ParseRequestURI(c.StatsURL); err != nil {
			return fmt.Errorf("stats-url is invalid: %w", err)
		}
	}

	if c.StartClients <= 0 || c.StepClients <= 0 || c.MaxClients <= 0 {
		return errors.New("start-clients, step-clients and max-clients must be > 0")
	}
	if c.MaxClients < c.StartClients {
		return errors.New("max-clients must be >= start-clients")
	}

	if c.RampSeconds <= 0 || c.SteadySeconds <= 0 || c.CooldownSeconds < 0 || c.PreRampStabilizeSeconds < 0 {
		return errors.New("ramp-seconds and steady-seconds must be > 0, cooldown-seconds and pre-ramp-stabilize-seconds must be >= 0")
	}

	if c.JoinTimeoutSeconds <= 0 {
		return errors.New("join-timeout-seconds must be > 0")
	}

	if c.RoomsMode != "paired" {
		return errors.New("rooms-mode must be paired")
	}

	if c.OfferRatePerRoom < 0 {
		return errors.New("offer-rate-per-room must be >= 0")
	}

	if c.ReconnectStormPercent < 0 || c.ReconnectStormPercent > 100 {
		return errors.New("reconnect-storm-percent must be between 0 and 100")
	}
	if c.ReconnectStormAtSecond < 0 {
		return errors.New("reconnect-storm-at-second must be >= 0")
	}

	if c.MaxErrorRate < 0 || c.MaxErrorRate > 1 {
		return errors.New("max-error-rate must be between 0 and 1")
	}
	if c.MaxJoinErrorRate < 0 || c.MaxJoinErrorRate > 1 {
		return errors.New("max-join-error-rate must be between 0 and 1")
	}
	if c.MaxJoinP95Ms < 0 {
		return errors.New("max-join-p95-ms must be >= 0")
	}
	if c.MaxSendQueueDrops < 0 {
		return errors.New("max-send-queue-drops must be >= 0")
	}

	return nil
}
