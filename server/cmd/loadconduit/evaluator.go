package main

import "fmt"

func evaluateStep(cfg Config, step StepResult) StepResult {
	joinP95 := step.ClientJoinP95Ms
	if step.ServerStatsAvailable {
		joinP95 = step.ServerJoinP95Ms
	}
	if step.TargetClients > 0 {
		missing := int64(step.TargetClients) - step.JoinSuccess
		if missing < 0 {
			missing = 0
		}
		step.JoinErrorRate = float64(missing) / float64(step.TargetClients)
	}

	failure := ""
	if step.JoinErrorRate > cfg.MaxJoinErrorRate {
		failure = fmt.Sprintf("join error rate %.4f exceeds %.4f", step.JoinErrorRate, cfg.MaxJoinErrorRate)
	}
	if step.ErrorRate > cfg.MaxErrorRate {
		failure = fmt.Sprintf("error rate %.4f exceeds %.4f", step.ErrorRate, cfg.MaxErrorRate)
	}
	if joinP95 > float64(cfg.MaxJoinP95Ms) {
		failure = fmt.Sprintf("join p95 %.1fms exceeds %dms", joinP95, cfg.MaxJoinP95Ms)
	}
	if step.ServerStatsAvailable && step.SendQueueDropDelta > cfg.MaxSendQueueDrops {
		failure = fmt.Sprintf("send queue drops %d exceed %d", step.SendQueueDropDelta, cfg.MaxSendQueueDrops)
	}

	if failure == "" {
		step.Passed = true
		return step
	}

	step.Passed = false
	step.FailReason = failure
	return step
}
