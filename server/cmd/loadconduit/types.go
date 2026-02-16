package main

import (
	"encoding/json"
	"math"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

type SweepReport struct {
	GeneratedAtRFC3339 string       `json:"generatedAt"`
	Config             Config       `json:"config"`
	Steps              []StepResult `json:"steps"`

	LastPassingClients int    `json:"lastPassingClients"`
	StoppedAtClients   int    `json:"stoppedAtClients"`
	FinalReason        string `json:"finalReason"`
}

type StepResult struct {
	TargetClients int `json:"targetClients"`
	TargetRooms   int `json:"targetRooms"`

	StartedAtRFC3339 string `json:"startedAt"`
	EndedAtRFC3339   string `json:"endedAt"`
	DurationSeconds  int64  `json:"durationSeconds"`

	ConnectAttempts      int64 `json:"connectAttempts"`
	ConnectSuccess       int64 `json:"connectSuccess"`
	ConnectFailures      int64 `json:"connectFailures"`
	JoinAttempts         int64 `json:"joinAttempts"`
	JoinSuccess          int64 `json:"joinSuccess"`
	JoinFailures         int64 `json:"joinFailures"`
	ReconnectAttempts    int64 `json:"reconnectAttempts"`
	ReconnectSuccess     int64 `json:"reconnectSuccess"`
	ReconnectFailures    int64 `json:"reconnectFailures"`
	ServerErrorMessages  int64 `json:"serverErrorMessages"`
	UnexpectedDisconnect int64 `json:"unexpectedDisconnect"`
	RelaySent            int64 `json:"relaySent"`
	RelaySendFailures    int64 `json:"relaySendFailures"`
	RelayReceived        int64 `json:"relayReceived"`

	ClientJoinP95Ms float64 `json:"clientJoinP95Ms"`
	ServerJoinP95Ms float64 `json:"serverJoinP95Ms"`
	JoinErrorRate   float64 `json:"joinErrorRate"`
	ErrorRate       float64 `json:"errorRate"`

	ServerStatsAvailable bool  `json:"serverStatsAvailable"`
	SendQueueDropDelta   int64 `json:"sendQueueDropDelta"`

	Passed     bool   `json:"passed"`
	FailReason string `json:"failReason,omitempty"`
}

type StepMetrics struct {
	connectAttempts      atomic.Int64
	connectSuccess       atomic.Int64
	connectFailures      atomic.Int64
	joinAttempts         atomic.Int64
	joinSuccess          atomic.Int64
	joinFailures         atomic.Int64
	reconnectAttempts    atomic.Int64
	reconnectSuccess     atomic.Int64
	reconnectFailures    atomic.Int64
	serverErrorMessages  atomic.Int64
	unexpectedDisconnect atomic.Int64
	relaySent            atomic.Int64
	relaySendFailures    atomic.Int64
	relayReceived        atomic.Int64

	joinLatencyMu sync.Mutex
	joinLatencies []int64
}

func (m *StepMetrics) AddJoinLatency(ms int64) {
	if ms < 0 {
		ms = 0
	}
	m.joinLatencyMu.Lock()
	m.joinLatencies = append(m.joinLatencies, ms)
	m.joinLatencyMu.Unlock()
}

func (m *StepMetrics) ClientJoinP95Ms() float64 {
	m.joinLatencyMu.Lock()
	defer m.joinLatencyMu.Unlock()
	if len(m.joinLatencies) == 0 {
		return 0
	}
	copySlice := append([]int64(nil), m.joinLatencies...)
	sort.Slice(copySlice, func(i, j int) bool { return copySlice[i] < copySlice[j] })
	idx := int(math.Ceil(0.95*float64(len(copySlice)))) - 1
	if idx < 0 {
		idx = 0
	}
	if idx >= len(copySlice) {
		idx = len(copySlice) - 1
	}
	return float64(copySlice[idx])
}

func (m *StepMetrics) ErrorRate() float64 {
	den := m.connectAttempts.Load()
	if den <= 0 {
		return 0
	}
	errEvents := m.connectFailures.Load() +
		m.joinFailures.Load() +
		m.reconnectFailures.Load() +
		m.serverErrorMessages.Load() +
		m.unexpectedDisconnect.Load() +
		m.relaySendFailures.Load()
	return float64(errEvents) / float64(den)
}

func (m *StepMetrics) ToStepResult(targetClients, targetRooms int, started, ended time.Time) StepResult {
	return StepResult{
		TargetClients: targetClients,
		TargetRooms:   targetRooms,

		StartedAtRFC3339: started.UTC().Format(time.RFC3339),
		EndedAtRFC3339:   ended.UTC().Format(time.RFC3339),
		DurationSeconds:  int64(ended.Sub(started).Seconds()),

		ConnectAttempts:      m.connectAttempts.Load(),
		ConnectSuccess:       m.connectSuccess.Load(),
		ConnectFailures:      m.connectFailures.Load(),
		JoinAttempts:         m.joinAttempts.Load(),
		JoinSuccess:          m.joinSuccess.Load(),
		JoinFailures:         m.joinFailures.Load(),
		ReconnectAttempts:    m.reconnectAttempts.Load(),
		ReconnectSuccess:     m.reconnectSuccess.Load(),
		ReconnectFailures:    m.reconnectFailures.Load(),
		ServerErrorMessages:  m.serverErrorMessages.Load(),
		UnexpectedDisconnect: m.unexpectedDisconnect.Load(),
		RelaySent:            m.relaySent.Load(),
		RelaySendFailures:    m.relaySendFailures.Load(),
		RelayReceived:        m.relayReceived.Load(),

		ClientJoinP95Ms: m.ClientJoinP95Ms(),
		ErrorRate:       m.ErrorRate(),
	}
}

func writeJSONReport(path string, report SweepReport) error {
	data, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return err
	}
	return atomicWriteFile(path, data)
}
