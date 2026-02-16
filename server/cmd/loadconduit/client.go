package main

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

type signalingEnvelope struct {
	V       int             `json:"v"`
	Type    string          `json:"type"`
	RID     string          `json:"rid,omitempty"`
	SID     string          `json:"sid,omitempty"`
	CID     string          `json:"cid,omitempty"`
	To      string          `json:"to,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

type joinResult struct {
	LatencyMs int64
	CID       string
	Err       error
}

type loadClient struct {
	id      int
	roomID  string
	wsURL   string
	metrics *StepMetrics

	joinTimeout time.Duration

	writeMu sync.Mutex
	connMu  sync.Mutex
	conn    *websocket.Conn

	expectedCloseSeq atomic.Int64
	joined           atomic.Bool
	cidValue         atomic.Value

	generation atomic.Int64
}

func newLoadClient(id int, roomID, wsURL string, joinTimeout time.Duration, metrics *StepMetrics) *loadClient {
	c := &loadClient{
		id:          id,
		roomID:      roomID,
		wsURL:       wsURL,
		joinTimeout: joinTimeout,
		metrics:     metrics,
	}
	c.cidValue.Store("")
	return c
}

func (c *loadClient) cid() string {
	cid, _ := c.cidValue.Load().(string)
	return cid
}

func (c *loadClient) connectAndJoin(ctx context.Context, reconnectCID string) error {
	c.metrics.connectAttempts.Add(1)
	dialer := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	conn, _, err := dialer.DialContext(ctx, c.wsURL, nil)
	if err != nil {
		c.metrics.connectFailures.Add(1)
		return err
	}
	c.metrics.connectSuccess.Add(1)

	seq := c.generation.Add(1)
	c.joined.Store(false)

	c.connMu.Lock()
	if c.conn != nil {
		c.markExpectedClose(seq - 1)
		_ = c.conn.Close()
	}
	c.conn = conn
	c.connMu.Unlock()

	joinedCh := make(chan joinResult, 1)
	readDone := make(chan struct{})
	joinSentAt := time.Now()

	go c.readLoop(seq, conn, joinedCh, readDone, joinSentAt)
	go c.pingLoop(seq, readDone)

	payload := map[string]any{
		"device":       "loadtest",
		"capabilities": map[string]any{"trickleIce": true},
	}
	if reconnectCID != "" {
		payload["reconnectCid"] = reconnectCID
	}

	c.metrics.joinAttempts.Add(1)
	if err := c.writeSignal(signalingEnvelope{V: 1, Type: "join", RID: c.roomID, Payload: mustRawJSON(payload)}); err != nil {
		c.metrics.joinFailures.Add(1)
		c.markExpectedClose(seq)
		_ = conn.Close()
		return err
	}

	joinTimer := time.NewTimer(c.joinTimeout)
	defer joinTimer.Stop()

	select {
	case <-ctx.Done():
		c.metrics.joinFailures.Add(1)
		c.markExpectedClose(seq)
		_ = conn.Close()
		return ctx.Err()
	case <-joinTimer.C:
		c.metrics.joinFailures.Add(1)
		c.markExpectedClose(seq)
		_ = conn.Close()
		return fmt.Errorf("join timeout after %s", c.joinTimeout)
	case result := <-joinedCh:
		if result.Err != nil {
			c.metrics.joinFailures.Add(1)
			return result.Err
		}
		c.metrics.joinSuccess.Add(1)
		c.metrics.AddJoinLatency(result.LatencyMs)
		c.cidValue.Store(result.CID)
		c.joined.Store(true)
		return nil
	}
}

func (c *loadClient) readLoop(seq int64, conn *websocket.Conn, joinedCh chan<- joinResult, readDone chan<- struct{}, joinSentAt time.Time) {
	defer close(readDone)
	joinReported := false

	for {
		_, payload, err := conn.ReadMessage()
		if err != nil {
			if !c.isExpectedClose(seq) {
				c.metrics.unexpectedDisconnect.Add(1)
			}
			if !joinReported {
				joinedCh <- joinResult{Err: err}
				joinReported = true
			}
			return
		}

		var msg signalingEnvelope
		if err := json.Unmarshal(payload, &msg); err != nil {
			continue
		}

		switch msg.Type {
		case "joined":
			if joinReported {
				continue
			}
			latencyMs := time.Since(joinSentAt).Milliseconds()
			joinedCh <- joinResult{CID: msg.CID, LatencyMs: latencyMs}
			joinReported = true
		case "error":
			c.metrics.serverErrorMessages.Add(1)
			if !joinReported {
				joinedCh <- joinResult{Err: fmt.Errorf("server error during join")}
				joinReported = true
			}
		case "offer", "answer", "ice":
			c.metrics.relayReceived.Add(1)
		}
	}
}

func (c *loadClient) pingLoop(seq int64, done <-chan struct{}) {
	ticker := time.NewTicker(12 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-done:
			return
		case <-ticker.C:
			_ = c.writeSignal(signalingEnvelope{V: 1, Type: "ping", RID: c.roomID, CID: c.cid()})
		}
	}
}

func (c *loadClient) writeSignal(msg signalingEnvelope) error {
	c.connMu.Lock()
	conn := c.conn
	c.connMu.Unlock()
	if conn == nil {
		return fmt.Errorf("client %d is not connected", c.id)
	}

	c.writeMu.Lock()
	defer c.writeMu.Unlock()

	if err := conn.SetWriteDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	return conn.WriteJSON(msg)
}

func (c *loadClient) sendRelayICE(counter int64) error {
	payload := map[string]any{
		"candidate": map[string]any{
			"sdpMid":        "0",
			"sdpMLineIndex": 0,
			"candidate":     fmt.Sprintf("candidate:%d:%d", c.id, counter),
		},
	}
	if err := c.writeSignal(signalingEnvelope{
		V:       1,
		Type:    "ice",
		RID:     c.roomID,
		CID:     c.cid(),
		Payload: mustRawJSON(payload),
	}); err != nil {
		c.metrics.relaySendFailures.Add(1)
		return err
	}
	c.metrics.relaySent.Add(1)
	return nil
}

func (c *loadClient) reconnect(ctx context.Context) error {
	previousCID := c.cid()
	c.metrics.reconnectAttempts.Add(1)

	c.close(true)

	err := c.connectAndJoin(ctx, previousCID)
	if err != nil {
		c.metrics.reconnectFailures.Add(1)
		return err
	}
	c.metrics.reconnectSuccess.Add(1)
	return nil
}

func (c *loadClient) leaveAndClose() {
	_ = c.writeSignal(signalingEnvelope{V: 1, Type: "leave", RID: c.roomID, CID: c.cid()})
	c.close(true)
}

func (c *loadClient) close(intentional bool) {
	if intentional {
		c.markExpectedClose(c.generation.Load())
	}
	c.connMu.Lock()
	conn := c.conn
	c.conn = nil
	c.connMu.Unlock()
	if conn != nil {
		_ = conn.Close()
	}
	c.joined.Store(false)
}

func (c *loadClient) markExpectedClose(seq int64) {
	for {
		current := c.expectedCloseSeq.Load()
		if seq <= current {
			return
		}
		if c.expectedCloseSeq.CompareAndSwap(current, seq) {
			return
		}
	}
}

func (c *loadClient) isExpectedClose(seq int64) bool {
	return seq <= c.expectedCloseSeq.Load()
}

func mustRawJSON(v any) json.RawMessage {
	payload, _ := json.Marshal(v)
	return payload
}
