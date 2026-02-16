package main

import "testing"

func TestEvaluateStepPassesWhenWithinThresholds(t *testing.T) {
	cfg := Config{MaxErrorRate: 0.01, MaxJoinErrorRate: 0.01, MaxJoinP95Ms: 2000, MaxSendQueueDrops: 0}
	step := StepResult{
		TargetClients:        20,
		JoinSuccess:          20,
		ErrorRate:            0.005,
		ClientJoinP95Ms:      1200,
		ServerStatsAvailable: true,
		ServerJoinP95Ms:      1100,
		SendQueueDropDelta:   0,
	}

	got := evaluateStep(cfg, step)
	if !got.Passed {
		t.Fatalf("expected step to pass, got failure: %s", got.FailReason)
	}
}

func TestEvaluateStepFailsOnQueueDrops(t *testing.T) {
	cfg := Config{MaxErrorRate: 0.01, MaxJoinErrorRate: 0.01, MaxJoinP95Ms: 2000, MaxSendQueueDrops: 0}
	step := StepResult{
		TargetClients:        20,
		JoinSuccess:          20,
		ErrorRate:            0,
		ClientJoinP95Ms:      100,
		ServerStatsAvailable: true,
		ServerJoinP95Ms:      100,
		SendQueueDropDelta:   2,
	}

	got := evaluateStep(cfg, step)
	if got.Passed {
		t.Fatalf("expected failure due to queue drops")
	}
	if got.FailReason == "" {
		t.Fatalf("expected failure reason")
	}
}

func TestEvaluateStepFailsOnJoinErrorRate(t *testing.T) {
	cfg := Config{MaxErrorRate: 0.01, MaxJoinErrorRate: 0.01, MaxJoinP95Ms: 2000, MaxSendQueueDrops: 0}
	step := StepResult{
		TargetClients:        100,
		JoinSuccess:          98,
		ErrorRate:            0.005,
		ClientJoinP95Ms:      100,
		ServerStatsAvailable: true,
		ServerJoinP95Ms:      100,
		SendQueueDropDelta:   0,
	}

	got := evaluateStep(cfg, step)
	if got.Passed {
		t.Fatalf("expected failure due to join error rate")
	}
	if got.FailReason == "" {
		t.Fatalf("expected failure reason")
	}
}

func TestEvaluateStepAllowsConfiguredJoinErrorRate(t *testing.T) {
	cfg := Config{MaxErrorRate: 0.01, MaxJoinErrorRate: 0.02, MaxJoinP95Ms: 2000, MaxSendQueueDrops: 0}
	step := StepResult{
		TargetClients:        100,
		JoinSuccess:          98,
		ErrorRate:            0.005,
		ClientJoinP95Ms:      100,
		ServerStatsAvailable: true,
		ServerJoinP95Ms:      100,
		SendQueueDropDelta:   0,
	}

	got := evaluateStep(cfg, step)
	if !got.Passed {
		t.Fatalf("expected step to pass, got failure: %s", got.FailReason)
	}
}
