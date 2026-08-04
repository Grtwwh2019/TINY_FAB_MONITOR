package main

import (
	"encoding/json"
	"io"
	"log"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestStateLifecyclePersistsAndCalculatesCrossDateAverage(t *testing.T) {
	storePath := filepath.Join(t.TempDir(), "state.json")
	store, err := NewFileStore(storePath)
	if err != nil {
		t.Fatal(err)
	}
	monitor := &Monitor{store: store, logger: log.New(io.Discard, "", 0)}

	base := time.Date(2025, 12, 31, 10, 0, 0, 0, time.Local)
	key := TaskKey{ProcessDate: "20251231", ThreadID: "7", LevelNo: "41", FabID: "FAB01"}
	sequence := []struct {
		status string
		offset time.Duration
	}{
		{"I", 0}, {"E", 5 * time.Minute}, {"B", 6 * time.Minute},
		{"I", 8 * time.Minute}, {"R", 20 * time.Minute},
	}
	for _, step := range sequence {
		if err := monitor.applyTaskStates([]OracleTask{{TaskKey: key, Status: step.status, ActTime: base.Add(step.offset)}}); err != nil {
			t.Fatal(err)
		}
	}
	// 完全相同的轮询结果不能重复生成运行或异常。
	if err := monitor.applyTaskStates([]OracleTask{{TaskKey: key, Status: "R", ActTime: base.Add(20 * time.Minute)}}); err != nil {
		t.Fatal(err)
	}

	secondKey := key
	secondKey.ProcessDate = "20260101"
	secondStart := base.Add(24 * time.Hour)
	if err := monitor.applyTaskStates([]OracleTask{{TaskKey: secondKey, Status: "I", ActTime: secondStart}}); err != nil {
		t.Fatal(err)
	}
	if err := monitor.applyTaskStates([]OracleTask{{TaskKey: secondKey, Status: "R", ActTime: secondStart.Add(10 * time.Minute)}}); err != nil {
		t.Fatal(err)
	}

	reloaded, err := NewFileStore(storePath)
	if err != nil {
		t.Fatal(err)
	}
	state := reloaded.Snapshot()
	if len(state.Runs) != 2 {
		t.Fatalf("want 2 runs, got %d", len(state.Runs))
	}
	first := state.Runs[0]
	if !first.StartedAt.Equal(base.Add(8 * time.Minute)) {
		t.Fatalf("want E→B→I to reset start time to 10:08, got %s", first.StartedAt)
	}
	if first.DurationSeconds != 12*60 {
		t.Fatalf("want duration from refreshed I to R to be 720, got %d", first.DurationSeconds)
	}
	if len(first.AnomalyTimes) != 1 || !first.AnomalyTimes[0].Equal(base.Add(5*time.Minute)) {
		t.Fatalf("unexpected anomaly times: %#v", first.AnomalyTimes)
	}
	stats := buildGroupStats(state.Runs)
	stat := stats[key.GroupID()]
	if stat.count != 2 {
		t.Fatalf("want 2 completed runs, got %d", stat.count)
	}
	if stat.average != 11*60 {
		t.Fatalf("want 660-second average, got %d", stat.average)
	}
}

func TestNonAnomalousRepeatedIDoesNotResetStartTime(t *testing.T) {
	store, err := NewFileStore(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	monitor := &Monitor{store: store, logger: log.New(io.Discard, "", 0)}
	start := time.Date(2026, 2, 1, 10, 0, 0, 0, time.Local)
	key := TaskKey{ProcessDate: "20260201", ThreadID: "1", LevelNo: "41", FabID: "F1"}
	if err := monitor.applyTaskStates([]OracleTask{{TaskKey: key, Status: "I", ActTime: start}}); err != nil {
		t.Fatal(err)
	}
	if err := monitor.applyTaskStates([]OracleTask{{TaskKey: key, Status: "B", ActTime: start.Add(time.Minute)}}); err != nil {
		t.Fatal(err)
	}
	if err := monitor.applyTaskStates([]OracleTask{{TaskKey: key, Status: "I", ActTime: start.Add(2 * time.Minute)}}); err != nil {
		t.Fatal(err)
	}
	run := store.Snapshot().Runs[0]
	if !run.StartedAt.Equal(start) {
		t.Fatalf("I without a preceding E must not reset start time: got %s", run.StartedAt)
	}
}

func TestAverageIsHiddenUntilSecondCompletedRun(t *testing.T) {
	start := time.Date(2026, 1, 1, 1, 2, 3, 0, time.UTC)
	run := &RunRecord{
		Task:      TaskKey{ProcessDate: "20260101", ThreadID: "T", LevelNo: "42", FabID: "F"},
		StartedAt: start, CompletedAt: timePtr(start.Add(2 * time.Minute)), DurationSeconds: 120,
	}
	stat := buildGroupStats([]*RunRecord{run})[run.Task.GroupID()]
	if stat.count != 1 || stat.average != 0 {
		t.Fatalf("first run must not expose an average: %#v", stat)
	}
}

func TestGraphDirectionIsDependencyToTask(t *testing.T) {
	nodes, edges := buildGraph(map[string]TaskView{"A": {OracleTask: OracleTask{TaskKey: TaskKey{FabID: "A"}, Status: "I"}}}, []Dependency{{FabID: "A", DependencyID: "B"}}, map[string]groupStat{})
	if len(nodes) != 2 {
		t.Fatalf("want 2 nodes, got %d", len(nodes))
	}
	if len(edges) != 1 || edges[0].From != "B" || edges[0].To != "A" {
		t.Fatalf("unexpected edge: %#v", edges)
	}
}

func TestVersionOneStateMigratesToRefreshedStartTime(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "state.json")
	start := time.Date(2026, 2, 2, 8, 0, 0, 0, time.UTC)
	completed := start.Add(30 * time.Minute)
	oldState := PersistedState{Version: 1, Tracked: map[string]*TrackedTask{}, Runs: []*RunRecord{{
		ID: "old", Task: TaskKey{ProcessDate: "20260202", ThreadID: "1", LevelNo: "41", FabID: "F1"},
		StartedAt: start, CompletedAt: &completed, DurationSeconds: 1800,
		Events: []StateEvent{{Status: "I", At: start}, {Status: "E", At: start.Add(10 * time.Minute)}, {Status: "B", At: start.Add(11 * time.Minute)}, {Status: "I", At: start.Add(20 * time.Minute)}, {Status: "R", At: completed}},
	}}}
	data, err := json.Marshal(oldState)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	store, err := NewFileStore(path)
	if err != nil {
		t.Fatal(err)
	}
	migrated := store.Snapshot()
	if migrated.Version != 2 {
		t.Fatalf("want version 2, got %d", migrated.Version)
	}
	if !migrated.Runs[0].StartedAt.Equal(start.Add(20*time.Minute)) || migrated.Runs[0].DurationSeconds != 600 {
		t.Fatalf("unexpected migrated run: %#v", migrated.Runs[0])
	}
}

func timePtr(value time.Time) *time.Time { return &value }
