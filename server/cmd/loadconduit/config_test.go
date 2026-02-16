package main

import "testing"

func TestParseConfigValidSmokeProfile(t *testing.T) {
	cfg, err := parseConfig([]string{
		"--base-url", "http://localhost",
		"--start-clients", "20",
		"--step-clients", "20",
		"--max-clients", "100",
		"--ramp-seconds", "10",
		"--steady-seconds", "20",
		"--cooldown-seconds", "1",
	})
	if err != nil {
		t.Fatalf("expected valid config, got error: %v", err)
	}
	if cfg.StartClients != 20 || cfg.MaxClients != 100 {
		t.Fatalf("unexpected config values: %+v", cfg)
	}
	if cfg.WSURL == "" {
		t.Fatalf("expected derived ws URL")
	}
}

func TestParseConfigRejectsInvalidStepMath(t *testing.T) {
	_, err := parseConfig([]string{
		"--base-url", "http://localhost",
		"--start-clients", "100",
		"--step-clients", "20",
		"--max-clients", "80",
	})
	if err == nil {
		t.Fatalf("expected error when max-clients < start-clients")
	}
}

func TestParseConfigRejectsInvalidRoomsMode(t *testing.T) {
	_, err := parseConfig([]string{
		"--base-url", "http://localhost",
		"--rooms-mode", "mesh",
	})
	if err == nil {
		t.Fatalf("expected error for unsupported rooms mode")
	}
}

func TestParseConfigRejectsInvalidJoinErrorRate(t *testing.T) {
	_, err := parseConfig([]string{
		"--base-url", "http://localhost",
		"--max-join-error-rate", "1.1",
	})
	if err == nil {
		t.Fatalf("expected error for invalid max-join-error-rate")
	}
}

func TestParseConfigRejectsNegativePreRampStabilizeSeconds(t *testing.T) {
	_, err := parseConfig([]string{
		"--base-url", "http://localhost",
		"--pre-ramp-stabilize-seconds", "-1",
	})
	if err == nil {
		t.Fatalf("expected error for negative pre-ramp-stabilize-seconds")
	}
}
