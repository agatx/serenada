package main

import (
	"fmt"
	"os"
	"path/filepath"
	"time"
)

func atomicWriteFile(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}

	tmp, err := os.CreateTemp(dir, ".loadconduit-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)

	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}

	return os.Rename(tmpName, path)
}

func printStepHeader() {
	fmt.Printf("%-8s %-6s %-10s %-10s %-10s %-12s %-8s\n", "clients", "rooms", "err_rate", "join_p95", "queue_drop", "stats_src", "result")
}

func printStepResult(step StepResult, useServerJoinP95 bool) {
	joinP95 := step.ClientJoinP95Ms
	statsSrc := "client"
	if useServerJoinP95 && step.ServerStatsAvailable {
		joinP95 = step.ServerJoinP95Ms
		statsSrc = "server"
	}
	result := "PASS"
	if !step.Passed {
		result = "FAIL"
	}

	fmt.Printf("%-8d %-6d %-10.4f %-10.1f %-10d %-12s %-8s\n",
		step.TargetClients,
		step.TargetRooms,
		step.ErrorRate,
		joinP95,
		step.SendQueueDropDelta,
		statsSrc,
		result,
	)
	if step.FailReason != "" {
		fmt.Printf("  reason: %s\n", step.FailReason)
	}
}

func nowRFC3339() string {
	return time.Now().UTC().Format(time.RFC3339)
}
