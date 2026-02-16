package stats

import (
	"runtime"
	"sync"
	"sync/atomic"
	"time"
)

var joinLatencyBoundariesMs = []int64{5, 10, 25, 50, 100, 200, 500, 1000, 2000, 5000, 10000}

// Snapshot is a point-in-time view of signaling stats.
type Snapshot struct {
	TimestampMs int64                `json:"timestampMs"`
	Gauges      SnapshotGauges       `json:"gauges"`
	Counters    SnapshotCounters     `json:"counters"`
	Messages    SnapshotMessages     `json:"messages"`
	JoinLatency SnapshotJoinLatency  `json:"joinLatency"`
	Disconnects map[string]int64     `json:"disconnects"`
	Runtime     SnapshotRuntimeStats `json:"runtime"`
}

type SnapshotGauges struct {
	ActiveClients        int64 `json:"activeClients"`
	ActiveWSClients      int64 `json:"activeWsClients"`
	ActiveSSEClients     int64 `json:"activeSseClients"`
	ActiveRooms          int64 `json:"activeRooms"`
	WatcherRooms         int64 `json:"watcherRooms"`
	WatcherSubscriptions int64 `json:"watcherSubscriptions"`
}

type SnapshotCounters struct {
	ConnectionAttemptsWS  int64 `json:"connectionAttemptsWs"`
	ConnectionSuccessWS   int64 `json:"connectionSuccessWs"`
	ConnectionFailuresWS  int64 `json:"connectionFailuresWs"`
	ConnectionAttemptsSSE int64 `json:"connectionAttemptsSse"`
	ConnectionSuccessSSE  int64 `json:"connectionSuccessSse"`
	ConnectionFailuresSSE int64 `json:"connectionFailuresSse"`
	SendQueueDropTotal    int64 `json:"sendQueueDropTotal"`
}

type SnapshotMessages struct {
	RxTotal  int64            `json:"rxTotal"`
	TxTotal  int64            `json:"txTotal"`
	RxByType map[string]int64 `json:"rxByType"`
	TxByType map[string]int64 `json:"txByType"`
}

type SnapshotJoinLatency struct {
	BoundariesMs []int64 `json:"boundariesMs"`
	BucketCounts []int64 `json:"bucketCounts"`
	Total        int64   `json:"total"`
	SumMs        int64   `json:"sumMs"`
}

type SnapshotRuntimeStats struct {
	Goroutines   int    `json:"goroutines"`
	HeapAlloc    uint64 `json:"heapAlloc"`
	HeapInuse    uint64 `json:"heapInuse"`
	HeapObjects  uint64 `json:"heapObjects"`
	NumGC        uint32 `json:"numGc"`
	PauseTotalNs uint64 `json:"pauseTotalNs"`
	LastPauseNs  uint64 `json:"lastPauseNs"`
}

type counterMap struct {
	m sync.Map
}

func normalizeKey(key string) string {
	if key == "" {
		return "unknown"
	}
	return key
}

func (c *counterMap) Inc(key string) {
	k := normalizeKey(key)
	if v, ok := c.m.Load(k); ok {
		v.(*atomic.Int64).Add(1)
		return
	}

	counter := &atomic.Int64{}
	actual, _ := c.m.LoadOrStore(k, counter)
	actual.(*atomic.Int64).Add(1)
}

func (c *counterMap) Snapshot() map[string]int64 {
	result := map[string]int64{}
	c.m.Range(func(key, value any) bool {
		k, ok := key.(string)
		if !ok {
			return true
		}
		counter, ok := value.(*atomic.Int64)
		if !ok {
			return true
		}
		result[k] = counter.Load()
		return true
	})

	return result
}

var (
	connectionAttemptsWS  atomic.Int64
	connectionSuccessWS   atomic.Int64
	connectionFailuresWS  atomic.Int64
	connectionAttemptsSSE atomic.Int64
	connectionSuccessSSE  atomic.Int64
	connectionFailuresSSE atomic.Int64

	activeClients        atomic.Int64
	activeWSClients      atomic.Int64
	activeSSEClients     atomic.Int64
	activeRooms          atomic.Int64
	watcherRooms         atomic.Int64
	watcherSubscriptions atomic.Int64

	sendQueueDropTotal atomic.Int64

	messagesRXTotal  atomic.Int64
	messagesTXTotal  atomic.Int64
	messagesRXByType counterMap
	messagesTXByType counterMap

	disconnectsByReason counterMap

	joinLatencyTotal   atomic.Int64
	joinLatencySumMs   atomic.Int64
	joinLatencyBuckets []atomic.Int64
)

func init() {
	joinLatencyBuckets = make([]atomic.Int64, len(joinLatencyBoundariesMs)+1)
}

func IncConnectionAttempt(kind string) {
	switch kind {
	case "ws":
		connectionAttemptsWS.Add(1)
	case "sse":
		connectionAttemptsSSE.Add(1)
	}
}

func IncConnectionSuccess(kind string) {
	switch kind {
	case "ws":
		connectionSuccessWS.Add(1)
	case "sse":
		connectionSuccessSSE.Add(1)
	}
}

func IncConnectionFailure(kind string) {
	switch kind {
	case "ws":
		connectionFailuresWS.Add(1)
	case "sse":
		connectionFailuresSSE.Add(1)
	}
}

func AddActiveWSClients(delta int64) {
	activeWSClients.Add(delta)
}

func AddActiveSSEClients(delta int64) {
	activeSSEClients.Add(delta)
}

func SetActiveClients(value int64) {
	activeClients.Store(value)
}

func SetActiveRooms(value int64) {
	activeRooms.Store(value)
}

func SetWatcherRooms(value int64) {
	watcherRooms.Store(value)
}

func SetWatcherSubscriptions(value int64) {
	watcherSubscriptions.Store(value)
}

func IncSendQueueDrop() {
	sendQueueDropTotal.Add(1)
}

func IncMessageRX(messageType string) {
	messagesRXTotal.Add(1)
	messagesRXByType.Inc(messageType)
}

func IncMessageTX(messageType string) {
	messagesTXTotal.Add(1)
	messagesTXByType.Inc(messageType)
}

func IncDisconnect(reason string) {
	disconnectsByReason.Inc(reason)
}

func RecordJoinLatency(duration time.Duration) {
	ms := duration.Milliseconds()
	if ms < 0 {
		ms = 0
	}

	joinLatencyTotal.Add(1)
	joinLatencySumMs.Add(ms)

	bucketIndex := len(joinLatencyBoundariesMs)
	for i, boundary := range joinLatencyBoundariesMs {
		if ms <= boundary {
			bucketIndex = i
			break
		}
	}
	joinLatencyBuckets[bucketIndex].Add(1)
}

func SnapshotNow() Snapshot {
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)

	lastPause := uint64(0)
	if mem.NumGC > 0 {
		idx := (mem.NumGC - 1) % uint32(len(mem.PauseNs))
		lastPause = mem.PauseNs[idx]
	}

	bucketCounts := make([]int64, len(joinLatencyBuckets))
	for i := range joinLatencyBuckets {
		bucketCounts[i] = joinLatencyBuckets[i].Load()
	}

	rx := messagesRXByType.Snapshot()
	tx := messagesTXByType.Snapshot()
	disconnects := disconnectsByReason.Snapshot()

	return Snapshot{
		TimestampMs: time.Now().UnixMilli(),
		Gauges: SnapshotGauges{
			ActiveClients:        activeClients.Load(),
			ActiveWSClients:      activeWSClients.Load(),
			ActiveSSEClients:     activeSSEClients.Load(),
			ActiveRooms:          activeRooms.Load(),
			WatcherRooms:         watcherRooms.Load(),
			WatcherSubscriptions: watcherSubscriptions.Load(),
		},
		Counters: SnapshotCounters{
			ConnectionAttemptsWS:  connectionAttemptsWS.Load(),
			ConnectionSuccessWS:   connectionSuccessWS.Load(),
			ConnectionFailuresWS:  connectionFailuresWS.Load(),
			ConnectionAttemptsSSE: connectionAttemptsSSE.Load(),
			ConnectionSuccessSSE:  connectionSuccessSSE.Load(),
			ConnectionFailuresSSE: connectionFailuresSSE.Load(),
			SendQueueDropTotal:    sendQueueDropTotal.Load(),
		},
		Messages: SnapshotMessages{
			RxTotal:  messagesRXTotal.Load(),
			TxTotal:  messagesTXTotal.Load(),
			RxByType: rx,
			TxByType: tx,
		},
		JoinLatency: SnapshotJoinLatency{
			BoundariesMs: append([]int64(nil), joinLatencyBoundariesMs...),
			BucketCounts: bucketCounts,
			Total:        joinLatencyTotal.Load(),
			SumMs:        joinLatencySumMs.Load(),
		},
		Disconnects: disconnects,
		Runtime: SnapshotRuntimeStats{
			Goroutines:   runtime.NumGoroutine(),
			HeapAlloc:    mem.HeapAlloc,
			HeapInuse:    mem.HeapInuse,
			HeapObjects:  mem.HeapObjects,
			NumGC:        mem.NumGC,
			PauseTotalNs: mem.PauseTotalNs,
			LastPauseNs:  lastPause,
		},
	}
}
