package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestStepMetricsToStepResult(t *testing.T) {
	m := &StepMetrics{}
	m.connectAttempts.Add(100)
	m.connectFailures.Add(1)
	m.joinFailures.Add(1)
	m.AddJoinLatency(50)
	m.AddJoinLatency(100)
	m.AddJoinLatency(200)

	start := time.Now().Add(-10 * time.Second)
	end := time.Now()
	result := m.ToStepResult(20, 10, start, end)
	if result.ConnectAttempts != 100 {
		t.Fatalf("unexpected connect attempts: %d", result.ConnectAttempts)
	}
	if result.ClientJoinP95Ms <= 0 {
		t.Fatalf("expected positive p95")
	}
	if result.ErrorRate <= 0 {
		t.Fatalf("expected positive error rate")
	}
}

func TestWriteJSONReport(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "report.json")
	report := SweepReport{GeneratedAtRFC3339: "2026-02-15T00:00:00Z", FinalReason: "ok"}

	if err := writeJSONReport(path, report); err != nil {
		t.Fatalf("writeJSONReport failed: %v", err)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("failed to read report: %v", err)
	}

	var parsed SweepReport
	if err := json.Unmarshal(data, &parsed); err != nil {
		t.Fatalf("invalid report json: %v", err)
	}
	if parsed.FinalReason != "ok" {
		t.Fatalf("unexpected report content: %+v", parsed)
	}
}
