package main

import "time"

type TaskKey struct {
	ProcessDate string `json:"process_date"`
	ThreadID    string `json:"thread_id"`
	LevelNo     string `json:"level_no"`
	FabID       string `json:"fab_id"`
}

func (k TaskKey) FullID() string {
	return k.ProcessDate + "|" + k.ThreadID + "|" + k.LevelNo + "|" + k.FabID
}

func (k TaskKey) GroupID() string {
	return k.ThreadID + "|" + k.LevelNo + "|" + k.FabID
}

type OracleTask struct {
	TaskKey
	Status           string    `json:"status"`
	ActTime          time.Time `json:"act_time"`
	LevelDescription string    `json:"level_description"`
	FabDescription   string    `json:"fab_description"`
}

type StateEvent struct {
	Status string    `json:"status"`
	At     time.Time `json:"at"`
}

type RunRecord struct {
	ID              string       `json:"id"`
	Task            TaskKey      `json:"task"`
	StartedAt       time.Time    `json:"started_at"`
	CompletedAt     *time.Time   `json:"completed_at,omitempty"`
	DurationSeconds int64        `json:"duration_seconds,omitempty"`
	AnomalyTimes    []time.Time  `json:"anomaly_times,omitempty"`
	Events          []StateEvent `json:"events"`
}

type TrackedTask struct {
	Key         TaskKey   `json:"key"`
	LastStatus  string    `json:"last_status"`
	LastActTime time.Time `json:"last_act_time"`
	ActiveRunID string    `json:"active_run_id,omitempty"`
}

type PersistedState struct {
	Version             int                     `json:"version"`
	SelectedProcessDate string                  `json:"selected_process_date"`
	Tracked             map[string]*TrackedTask `json:"tracked"`
	Runs                []*RunRecord            `json:"runs"`
	UpdatedAt           time.Time               `json:"updated_at"`
}

type Dependency struct {
	FabID        string `json:"fab_id"`
	DependencyID string `json:"dependency_id"`
}

type TaskView struct {
	OracleTask
	StartedAt              *time.Time  `json:"started_at,omitempty"`
	CompletedAt            *time.Time  `json:"completed_at,omitempty"`
	CurrentDurationSeconds int64       `json:"current_duration_seconds,omitempty"`
	LastDurationSeconds    int64       `json:"last_duration_seconds,omitempty"`
	AverageDurationSeconds int64       `json:"average_duration_seconds,omitempty"`
	CompletedRunCount      int         `json:"completed_run_count"`
	AnomalyTimes           []time.Time `json:"anomaly_times,omitempty"`
}

type GraphNode struct {
	ID                     string     `json:"id"`
	Description            string     `json:"description,omitempty"`
	Status                 string     `json:"status,omitempty"`
	StartedAt              *time.Time `json:"started_at,omitempty"`
	AverageDurationSeconds int64      `json:"average_duration_seconds,omitempty"`
	Current                bool       `json:"current"`
}

type GraphEdge struct {
	From string `json:"from"`
	To   string `json:"to"`
}

type Dashboard struct {
	ProcessDate         string       `json:"process_date"`
	Connected           bool         `json:"connected"`
	LastPollAt          *time.Time   `json:"last_poll_at,omitempty"`
	NextPollAt          *time.Time   `json:"next_poll_at,omitempty"`
	LastError           string       `json:"last_error,omitempty"`
	Polling             bool         `json:"polling"`
	Tasks               []TaskView   `json:"tasks"`
	GraphNodes          []GraphNode  `json:"graph_nodes"`
	GraphEdges          []GraphEdge  `json:"graph_edges"`
	RecentRuns          []*RunRecord `json:"recent_runs"`
	TotalHistoricalRuns int          `json:"total_historical_runs"`
	PollIntervalSeconds int          `json:"poll_interval_seconds"`
}
