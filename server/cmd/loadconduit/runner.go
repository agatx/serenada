package main

import (
	"context"
	"fmt"
	"math/rand"
	"sync"
	"time"
)

type roomPair struct {
	roomID string
	host   *loadClient
	peer   *loadClient
}

func runSweep(ctx context.Context, cfg Config) (SweepReport, error) {
	report := SweepReport{
		GeneratedAtRFC3339: nowRFC3339(),
		Config:             cfg,
		Steps:              make([]StepResult, 0),
	}

	statsClient := NewStatsClient(cfg.BaseURL, cfg.StatsURL, cfg.StatsToken)
	rng := rand.New(rand.NewSource(cfg.RandomSeed))

	printStepHeader()
	lastPassing := 0
	stoppedAt := 0
	finalReason := "max clients reached"

	for target := cfg.StartClients; target <= cfg.MaxClients; target += cfg.StepClients {
		stepResult, err := runStep(ctx, cfg, target, statsClient, rng)
		if err != nil {
			stepResult.Passed = false
			if stepResult.FailReason == "" {
				stepResult.FailReason = err.Error()
			}
			report.Steps = append(report.Steps, stepResult)
			printStepResult(stepResult, true)
			stoppedAt = stepResult.TargetClients
			finalReason = stepResult.FailReason
			break
		}

		report.Steps = append(report.Steps, stepResult)
		printStepResult(stepResult, true)

		if stepResult.Passed {
			lastPassing = stepResult.TargetClients
			continue
		}

		stoppedAt = stepResult.TargetClients
		if stepResult.FailReason != "" {
			finalReason = stepResult.FailReason
		} else {
			finalReason = "SLO threshold failed"
		}
		break
	}

	if stoppedAt == 0 && len(report.Steps) > 0 {
		stoppedAt = report.Steps[len(report.Steps)-1].TargetClients
	}

	report.LastPassingClients = lastPassing
	report.StoppedAtClients = stoppedAt
	report.FinalReason = finalReason

	return report, nil
}

func runStep(parent context.Context, cfg Config, requestedClients int, statsClient *StatsClient, rng *rand.Rand) (StepResult, error) {
	started := time.Now()
	stepCtx, cancel := context.WithCancel(parent)
	defer cancel()

	targetClients := requestedClients
	if targetClients%2 != 0 {
		targetClients--
	}
	if targetClients <= 0 {
		targetClients = 2
	}
	targetRooms := targetClients / 2

	metrics := &StepMetrics{}
	var serverStatsStart InternalStatsSnapshot
	startStatsErr := fmt.Errorf("stats not fetched")

	roomIDs, err := generateRoomIDs(stepCtx, cfg, targetRooms)
	if err != nil {
		return StepResult{TargetClients: targetClients, TargetRooms: targetRooms, StartedAtRFC3339: started.UTC().Format(time.RFC3339), EndedAtRFC3339: time.Now().UTC().Format(time.RFC3339), DurationSeconds: int64(time.Since(started).Seconds()), FailReason: fmt.Sprintf("failed to generate room IDs: %v", err)}, err
	}
	if err := waitForServerStabilization(stepCtx, cfg, statsClient); err != nil {
		return StepResult{TargetClients: targetClients, TargetRooms: targetRooms, StartedAtRFC3339: started.UTC().Format(time.RFC3339), EndedAtRFC3339: time.Now().UTC().Format(time.RFC3339), DurationSeconds: int64(time.Since(started).Seconds()), FailReason: fmt.Sprintf("server stabilization interrupted: %v", err)}, err
	}
	serverStatsStart, startStatsErr = fetchStats(stepCtx, statsClient)

	pairs := make([]roomPair, 0, targetRooms)
	clients := make([]*loadClient, 0, targetClients)
	for i := 0; i < targetRooms; i++ {
		host := newLoadClient(i*2, roomIDs[i], cfg.WSURL, time.Duration(cfg.JoinTimeoutSeconds)*time.Second, metrics)
		peer := newLoadClient(i*2+1, roomIDs[i], cfg.WSURL, time.Duration(cfg.JoinTimeoutSeconds)*time.Second, metrics)
		pairs = append(pairs, roomPair{roomID: roomIDs[i], host: host, peer: peer})
		clients = append(clients, host, peer)
	}

	var rampWG sync.WaitGroup
	rampInterval := time.Duration(0)
	if len(clients) > 1 {
		rampInterval = (time.Duration(cfg.RampSeconds) * time.Second) / time.Duration(len(clients)-1)
	}

	rampStopped := false
rampLoop:
	for i, client := range clients {
		if i > 0 && rampInterval > 0 {
			select {
			case <-stepCtx.Done():
				rampStopped = true
				break rampLoop
			case <-time.After(rampInterval):
			}
		}
		rampWG.Add(1)
		go func(c *loadClient) {
			defer rampWG.Done()
			joinCtx, joinCancel := context.WithTimeout(stepCtx, time.Duration(cfg.JoinTimeoutSeconds)*time.Second)
			defer joinCancel()
			_ = c.connectAndJoin(joinCtx, "")
		}(client)
	}
	rampWG.Wait()
	if rampStopped {
		err := stepCtx.Err()
		if err == nil {
			err = context.Canceled
		}
		return StepResult{
			TargetClients:    targetClients,
			TargetRooms:      targetRooms,
			StartedAtRFC3339: started.UTC().Format(time.RFC3339),
			EndedAtRFC3339:   time.Now().UTC().Format(time.RFC3339),
			DurationSeconds:  int64(time.Since(started).Seconds()),
			FailReason:       fmt.Sprintf("ramp canceled: %v", err),
		}, err
	}

	relayCancel, relayWG := startRelayLoops(stepCtx, cfg, pairs)
	defer func() {
		relayCancel()
		relayWG.Wait()
	}()

	reconnectWG := &sync.WaitGroup{}
	if cfg.ReconnectStormPercent > 0 && cfg.ReconnectStormAtSecond < cfg.SteadySeconds {
		stormTimer := time.NewTimer(time.Duration(cfg.ReconnectStormAtSecond) * time.Second)
		go func() {
			defer stormTimer.Stop()
			select {
			case <-stepCtx.Done():
				return
			case <-stormTimer.C:
				selected := pickReconnectClients(clients, cfg.ReconnectStormPercent, rng)
				for _, c := range selected {
					reconnectWG.Add(1)
					go func(client *loadClient) {
						defer reconnectWG.Done()
						reconnectCtx, reconnectCancel := context.WithTimeout(stepCtx, time.Duration(cfg.JoinTimeoutSeconds)*time.Second)
						defer reconnectCancel()
						_ = client.reconnect(reconnectCtx)
					}(c)
				}
			}
		}()
	}

	steadyTimer := time.NewTimer(time.Duration(cfg.SteadySeconds) * time.Second)
	select {
	case <-stepCtx.Done():
		steadyTimer.Stop()
	case <-steadyTimer.C:
	}

	relayCancel()
	relayWG.Wait()
	reconnectWG.Wait()

	serverStatsEnd, endStatsErr := fetchStats(stepCtx, statsClient)

	for _, client := range clients {
		client.leaveAndClose()
	}
	if cfg.CooldownSeconds > 0 {
		select {
		case <-stepCtx.Done():
		case <-time.After(time.Duration(cfg.CooldownSeconds) * time.Second):
		}
	}

	ended := time.Now()
	result := metrics.ToStepResult(targetClients, targetRooms, started, ended)
	result.ServerStatsAvailable = startStatsErr == nil && endStatsErr == nil
	if result.ServerStatsAvailable {
		result.SendQueueDropDelta = serverStatsEnd.Counters.SendQueueDropTotal - serverStatsStart.Counters.SendQueueDropTotal
		if result.SendQueueDropDelta < 0 {
			result.SendQueueDropDelta = 0
		}
		result.ServerJoinP95Ms = estimateJoinP95DeltaMs(serverStatsStart, serverStatsEnd)
	}

	result = evaluateStep(cfg, result)
	return result, nil
}

func fetchStats(ctx context.Context, client *StatsClient) (InternalStatsSnapshot, error) {
	statsCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	return client.Fetch(statsCtx)
}

func waitForServerStabilization(ctx context.Context, cfg Config, client *StatsClient) error {
	if cfg.PreRampStabilizeSeconds <= 0 {
		return nil
	}

	minDeadline := time.Now().Add(time.Duration(cfg.PreRampStabilizeSeconds) * time.Second)
	maxDeadline := minDeadline.Add(5 * time.Second)
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	statsSeen := false
	consecutiveIdle := 0

	for {
		snapshot, err := fetchStats(ctx, client)
		if err == nil {
			statsSeen = true
			if snapshot.Gauges.ActiveClients == 0 && snapshot.Gauges.ActiveWSClients == 0 && snapshot.Gauges.ActiveSSEClients == 0 {
				consecutiveIdle++
			} else {
				consecutiveIdle = 0
			}
		} else if statsSeen {
			consecutiveIdle = 0
		}

		now := time.Now()
		if now.After(minDeadline) {
			if !statsSeen || consecutiveIdle >= 2 || now.After(maxDeadline) {
				return nil
			}
		}

		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

func startRelayLoops(ctx context.Context, cfg Config, rooms []roomPair) (context.CancelFunc, *sync.WaitGroup) {
	relayCtx, cancel := context.WithCancel(ctx)
	wg := &sync.WaitGroup{}

	if cfg.OfferRatePerRoom <= 0 {
		return cancel, wg
	}

	interval := time.Duration(float64(time.Second) / cfg.OfferRatePerRoom)
	if interval < 50*time.Millisecond {
		interval = 50 * time.Millisecond
	}

	for _, room := range rooms {
		r := room
		wg.Add(1)
		go func() {
			defer wg.Done()
			ticker := time.NewTicker(interval)
			defer ticker.Stop()
			var counter int64
			for {
				select {
				case <-relayCtx.Done():
					return
				case <-ticker.C:
					counter++
					_ = r.host.sendRelayICE(counter)
				}
			}
		}()
	}

	return cancel, wg
}

func pickReconnectClients(clients []*loadClient, percent float64, rng *rand.Rand) []*loadClient {
	if percent <= 0 || len(clients) == 0 {
		return nil
	}
	count := int(float64(len(clients))*percent/100.0 + 0.5)
	if count <= 0 {
		count = 1
	}
	if count > len(clients) {
		count = len(clients)
	}

	indices := make([]int, len(clients))
	for i := range indices {
		indices[i] = i
	}
	rng.Shuffle(len(indices), func(i, j int) {
		indices[i], indices[j] = indices[j], indices[i]
	})

	selected := make([]*loadClient, 0, count)
	for i := 0; i < count; i++ {
		selected = append(selected, clients[indices[i]])
	}
	return selected
}
