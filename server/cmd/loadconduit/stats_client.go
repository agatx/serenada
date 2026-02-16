package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

type InternalStatsSnapshot struct {
	TimestampMs int64 `json:"timestampMs"`

	Gauges struct {
		ActiveClients    int64 `json:"activeClients"`
		ActiveWSClients  int64 `json:"activeWsClients"`
		ActiveSSEClients int64 `json:"activeSseClients"`
	} `json:"gauges"`

	Counters struct {
		SendQueueDropTotal int64 `json:"sendQueueDropTotal"`
	} `json:"counters"`

	JoinLatency struct {
		BoundariesMs []int64 `json:"boundariesMs"`
		BucketCounts []int64 `json:"bucketCounts"`
		Total        int64   `json:"total"`
	} `json:"joinLatency"`
}

type StatsClient struct {
	httpClient *http.Client
	baseURL    string
	statsURL   string
	token      string
}

func NewStatsClient(baseURL, statsURL, token string) *StatsClient {
	return &StatsClient{
		httpClient: &http.Client{},
		baseURL:    strings.TrimSpace(baseURL),
		statsURL:   strings.TrimSpace(statsURL),
		token:      strings.TrimSpace(token),
	}
}

func (c *StatsClient) endpointURL() (string, error) {
	if strings.HasPrefix(c.statsURL, "http://") || strings.HasPrefix(c.statsURL, "https://") {
		return c.statsURL, nil
	}
	base, err := url.Parse(c.baseURL)
	if err != nil {
		return "", err
	}
	path := c.statsURL
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	base.Path = path
	base.RawQuery = ""
	base.Fragment = ""
	return base.String(), nil
}

func (c *StatsClient) Fetch(ctx context.Context) (InternalStatsSnapshot, error) {
	var snapshot InternalStatsSnapshot
	endpoint, err := c.endpointURL()
	if err != nil {
		return snapshot, err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return snapshot, err
	}
	if c.token != "" {
		req.Header.Set("X-Internal-Token", c.token)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return snapshot, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return snapshot, fmt.Errorf("stats endpoint returned %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return snapshot, err
	}

	snapshot, err = parseInternalStatsSnapshot(body)
	if err != nil {
		return snapshot, err
	}
	return snapshot, nil
}

func parseInternalStatsSnapshot(raw []byte) (InternalStatsSnapshot, error) {
	var snapshot InternalStatsSnapshot
	if err := json.Unmarshal(raw, &snapshot); err != nil {
		return snapshot, err
	}
	if len(snapshot.JoinLatency.BucketCounts) > 0 && len(snapshot.JoinLatency.BoundariesMs) > 0 {
		expected := len(snapshot.JoinLatency.BoundariesMs) + 1
		if len(snapshot.JoinLatency.BucketCounts) > expected {
			snapshot.JoinLatency.BucketCounts = snapshot.JoinLatency.BucketCounts[:expected]
		}
	}
	return snapshot, nil
}

func estimateJoinP95DeltaMs(start, end InternalStatsSnapshot) float64 {
	if len(start.JoinLatency.BucketCounts) == 0 || len(end.JoinLatency.BucketCounts) == 0 {
		return 0
	}
	if len(start.JoinLatency.BucketCounts) != len(end.JoinLatency.BucketCounts) {
		return 0
	}

	delta := make([]int64, len(end.JoinLatency.BucketCounts))
	var total int64
	for i := range end.JoinLatency.BucketCounts {
		d := end.JoinLatency.BucketCounts[i] - start.JoinLatency.BucketCounts[i]
		if d < 0 {
			d = 0
		}
		delta[i] = d
		total += d
	}
	if total == 0 {
		return 0
	}

	threshold := int64(float64(total)*0.95 + 0.999999)
	if threshold <= 0 {
		threshold = 1
	}

	var cumulative int64
	for i, c := range delta {
		cumulative += c
		if cumulative >= threshold {
			if i < len(end.JoinLatency.BoundariesMs) {
				return float64(end.JoinLatency.BoundariesMs[i])
			}
			if len(end.JoinLatency.BoundariesMs) == 0 {
				return 0
			}
			return float64(end.JoinLatency.BoundariesMs[len(end.JoinLatency.BoundariesMs)-1] + 1)
		}
	}

	return 0
}
