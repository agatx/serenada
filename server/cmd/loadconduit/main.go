package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	cfg, err := parseConfig(os.Args[1:])
	if err != nil {
		fmt.Fprintf(os.Stderr, "config error: %v\n", err)
		os.Exit(2)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	report, err := runSweep(ctx, cfg)
	if err != nil {
		fmt.Fprintf(os.Stderr, "load sweep failed: %v\n", err)
	}

	fmt.Printf("\nlast passing concurrency: %d clients\n", report.LastPassingClients)
	fmt.Printf("stopped at: %d clients\n", report.StoppedAtClients)
	fmt.Printf("final reason: %s\n", report.FinalReason)

	if cfg.ReportJSON != "" {
		if err := writeJSONReport(cfg.ReportJSON, report); err != nil {
			fmt.Fprintf(os.Stderr, "failed to write report: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("report: %s\n", cfg.ReportJSON)
	}

	if err != nil {
		os.Exit(1)
	}
}
