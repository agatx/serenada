package main

import "testing"

func TestParseInternalStatsSnapshotMissingFields(t *testing.T) {
	raw := []byte(`{"timestampMs":123,"counters":{"sendQueueDropTotal":7},"joinLatency":{"boundariesMs":[10,20],"bucketCounts":[1,2,3]}}`)
	snap, err := parseInternalStatsSnapshot(raw)
	if err != nil {
		t.Fatalf("expected parse to succeed, got: %v", err)
	}
	if snap.Counters.SendQueueDropTotal != 7 {
		t.Fatalf("unexpected sendQueueDropTotal: %d", snap.Counters.SendQueueDropTotal)
	}
	if len(snap.JoinLatency.BucketCounts) != 3 {
		t.Fatalf("unexpected bucket length: %d", len(snap.JoinLatency.BucketCounts))
	}
}

func TestEstimateJoinP95DeltaMs(t *testing.T) {
	start := InternalStatsSnapshot{}
	start.JoinLatency.BoundariesMs = []int64{100, 200, 500}
	start.JoinLatency.BucketCounts = []int64{0, 0, 0, 0}

	end := InternalStatsSnapshot{}
	end.JoinLatency.BoundariesMs = []int64{100, 200, 500}
	end.JoinLatency.BucketCounts = []int64{80, 15, 5, 0}

	p95 := estimateJoinP95DeltaMs(start, end)
	if p95 != 200 {
		t.Fatalf("expected p95=200, got %.1f", p95)
	}
}
