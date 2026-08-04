package main

import (
	"context"
	"fmt"
	"log"
	"sort"
	"strings"
	"sync"
	"time"
)

type Monitor struct {
	cfg    Config
	repo   *OracleRepository
	store  *FileStore
	logger *log.Logger

	mu           sync.RWMutex
	processDate  string
	tasks        []OracleTask
	dependencies []Dependency
	connected    bool
	polling      bool
	lastPollAt   *time.Time
	nextPollAt   *time.Time
	lastError    string
	refresh      chan struct{}
}

func NewMonitor(cfg Config, repo *OracleRepository, store *FileStore, logger *log.Logger) *Monitor {
	date := cfg.Monitor.ProcessDate
	state := store.Snapshot()
	if state.SelectedProcessDate != "" {
		date = state.SelectedProcessDate
	}
	return &Monitor{
		cfg: cfg, repo: repo, store: store, logger: logger,
		processDate: date, refresh: make(chan struct{}, 1),
	}
}

func (m *Monitor) Run(ctx context.Context) {
	m.TriggerRefresh()
	interval := time.Duration(m.cfg.Monitor.PollIntervalMinutes) * time.Minute
	timer := time.NewTimer(interval)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-m.refresh:
			m.poll(ctx)
			resetTimer(timer, interval)
		case <-timer.C:
			m.poll(ctx)
			timer.Reset(interval)
		}
	}
}

func resetTimer(timer *time.Timer, d time.Duration) {
	if !timer.Stop() {
		select {
		case <-timer.C:
		default:
		}
	}
	timer.Reset(d)
}

func (m *Monitor) TriggerRefresh() {
	select {
	case m.refresh <- struct{}{}:
	default:
	}
}

func (m *Monitor) SetProcessDate(date string) error {
	if len(date) != 8 {
		return fmt.Errorf("日期必须是 YYYYMMDD 格式")
	}
	if _, err := time.Parse("20060102", date); err != nil {
		return fmt.Errorf("日期无效：%w", err)
	}
	m.mu.Lock()
	m.processDate = date
	m.tasks = nil
	m.dependencies = nil
	m.lastError = ""
	m.mu.Unlock()
	if err := m.store.Update(func(state *PersistedState) error {
		state.SelectedProcessDate = date
		return nil
	}); err != nil {
		return err
	}
	m.TriggerRefresh()
	return nil
}

func (m *Monitor) poll(parent context.Context) {
	m.mu.Lock()
	if m.polling {
		m.mu.Unlock()
		return
	}
	m.polling = true
	date := m.processDate
	m.mu.Unlock()

	defer func() {
		m.mu.Lock()
		m.polling = false
		m.mu.Unlock()
	}()

	ctx, cancel := context.WithTimeout(parent, 90*time.Second)
	defer cancel()
	m.logger.Printf("开始读取业务日期 %s", date)
	tasks, err := m.repo.FetchTasks(ctx, date, m.cfg.Monitor.LevelMin, m.cfg.Monitor.LevelMax)
	if err != nil {
		m.setPollError(fmt.Errorf("读取任务失败：%w", err))
		return
	}

	if err := m.applyTaskStates(tasks); err != nil {
		m.setPollError(fmt.Errorf("保存状态失败：%w", err))
		return
	}

	rootIDs := make([]string, 0, len(tasks))
	seen := map[string]bool{}
	for _, task := range tasks {
		if task.FabID != "" && !seen[task.FabID] {
			seen[task.FabID] = true
			rootIDs = append(rootIDs, task.FabID)
		}
	}
	dependencies, depErr := m.repo.FetchDependencyGraph(ctx, rootIDs)

	now := time.Now()
	next := now.Add(time.Duration(m.cfg.Monitor.PollIntervalMinutes) * time.Minute)
	m.mu.Lock()
	// 用户可能在查询过程中切换日期，旧结果不能覆盖新日期。
	if m.processDate == date {
		m.tasks = tasks
		if depErr == nil {
			m.dependencies = dependencies
		}
		m.connected = true
		m.lastPollAt = &now
		m.nextPollAt = &next
		if depErr != nil {
			m.lastError = "任务已读取，但依赖关系读取失败：" + depErr.Error()
		} else {
			m.lastError = ""
		}
	}
	m.mu.Unlock()
	if depErr != nil {
		m.logger.Printf("依赖读取失败：%v", depErr)
	}
	m.logger.Printf("读取完成：%d 个任务，%d 条依赖", len(tasks), len(dependencies))
}

func (m *Monitor) setPollError(err error) {
	m.logger.Printf("轮询失败：%v", err)
	now := time.Now()
	next := now.Add(time.Duration(m.cfg.Monitor.PollIntervalMinutes) * time.Minute)
	m.mu.Lock()
	m.connected = false
	m.lastPollAt = &now
	m.nextPollAt = &next
	m.lastError = err.Error()
	m.mu.Unlock()
}

func (m *Monitor) applyTaskStates(tasks []OracleTask) error {
	return m.store.Update(func(state *PersistedState) error {
		for _, task := range tasks {
			fullID := task.FullID()
			tracked := state.Tracked[fullID]
			if tracked != nil && tracked.LastStatus == task.Status && tracked.LastActTime.Equal(task.ActTime) {
				continue
			}

			if tracked == nil {
				tracked = &TrackedTask{Key: task.TaskKey}
				state.Tracked[fullID] = tracked
			}
			active := findRun(state.Runs, tracked.ActiveRunID)
			event := StateEvent{Status: task.Status, At: task.ActTime}

			switch task.Status {
			case "I":
				// W/R 后进入 I 表示一次新运行；E/B 后回到 I 仍属于原运行。
				if active == nil {
					runID := fmt.Sprintf("%s|%d", fullID, task.ActTime.UnixNano())
					active = &RunRecord{
						ID: runID, Task: task.TaskKey, StartedAt: task.ActTime,
						Events: []StateEvent{event},
					}
					state.Runs = append(state.Runs, active)
					tracked.ActiveRunID = runID
				} else {
					// 捕获过 E 后再次进入 I（包括 E→B→I），从新的 I 时间重新计时。
					if hasAnomalySinceLastI(active.Events) {
						active.StartedAt = task.ActTime
					}
					appendEvent(active, event)
				}
			case "E":
				if active != nil {
					appendEvent(active, event)
					if !containsTime(active.AnomalyTimes, task.ActTime) {
						active.AnomalyTimes = append(active.AnomalyTimes, task.ActTime)
					}
				}
			case "B":
				if active != nil {
					appendEvent(active, event)
				}
			case "R":
				if active != nil && active.CompletedAt == nil {
					appendEvent(active, event)
					completed := task.ActTime
					active.CompletedAt = &completed
					seconds := int64(completed.Sub(active.StartedAt).Seconds())
					if seconds < 0 {
						seconds = 0
					}
					active.DurationSeconds = seconds
					tracked.ActiveRunID = ""
				}
			default:
				if active != nil {
					appendEvent(active, event)
				}
			}

			tracked.LastStatus = task.Status
			tracked.LastActTime = task.ActTime
		}
		return nil
	})
}

func findRun(runs []*RunRecord, id string) *RunRecord {
	if id == "" {
		return nil
	}
	for i := len(runs) - 1; i >= 0; i-- {
		if runs[i].ID == id {
			return runs[i]
		}
	}
	return nil
}

func appendEvent(run *RunRecord, event StateEvent) {
	if len(run.Events) > 0 {
		last := run.Events[len(run.Events)-1]
		if last.Status == event.Status && last.At.Equal(event.At) {
			return
		}
	}
	run.Events = append(run.Events, event)
}

func hasAnomalySinceLastI(events []StateEvent) bool {
	for i := len(events) - 1; i >= 0; i-- {
		switch events[i].Status {
		case "E":
			return true
		case "I":
			return false
		}
	}
	return false
}

func containsTime(values []time.Time, target time.Time) bool {
	for _, value := range values {
		if value.Equal(target) {
			return true
		}
	}
	return false
}

func (m *Monitor) Dashboard() Dashboard {
	m.mu.RLock()
	date := m.processDate
	tasks := append([]OracleTask(nil), m.tasks...)
	deps := append([]Dependency(nil), m.dependencies...)
	result := Dashboard{
		ProcessDate: date, Connected: m.connected, Polling: m.polling,
		LastPollAt: cloneTimePtr(m.lastPollAt), NextPollAt: cloneTimePtr(m.nextPollAt),
		LastError:           m.lastError,
		PollIntervalSeconds: m.cfg.Monitor.PollIntervalMinutes * 60,
	}
	m.mu.RUnlock()

	state := m.store.Snapshot()
	result.TotalHistoricalRuns = len(state.Runs)
	result.RecentRuns = recentRuns(state.Runs, 200)
	stats := buildGroupStats(state.Runs)
	now := time.Now()
	currentByFab := map[string]TaskView{}
	for _, task := range tasks {
		view := TaskView{OracleTask: task}
		tracked := state.Tracked[task.FullID()]
		if tracked != nil {
			if active := findRun(state.Runs, tracked.ActiveRunID); active != nil {
				start := active.StartedAt
				view.StartedAt = &start
				view.CurrentDurationSeconds = max64(0, int64(now.Sub(start).Seconds()))
				view.AnomalyTimes = append([]time.Time(nil), active.AnomalyTimes...)
			}
		}
		group := stats[task.GroupID()]
		view.AverageDurationSeconds = group.average
		view.CompletedRunCount = group.count
		view.LastDurationSeconds = group.last
		if view.StartedAt == nil && group.lastRun != nil && group.lastRun.Task.ProcessDate == task.ProcessDate {
			start := group.lastRun.StartedAt
			view.StartedAt = &start
			view.CompletedAt = cloneTimePtr(group.lastRun.CompletedAt)
			view.AnomalyTimes = append([]time.Time(nil), group.lastRun.AnomalyTimes...)
		}
		result.Tasks = append(result.Tasks, view)
		currentByFab[task.FabID] = view
	}
	sort.Slice(result.Tasks, func(i, j int) bool {
		a, b := result.Tasks[i], result.Tasks[j]
		return strings.Join([]string{a.ThreadID, a.LevelNo, a.FabID}, "|") < strings.Join([]string{b.ThreadID, b.LevelNo, b.FabID}, "|")
	})
	result.GraphNodes, result.GraphEdges = buildGraph(currentByFab, deps, stats)
	return result
}

type groupStat struct {
	average int64
	count   int
	last    int64
	lastRun *RunRecord
}

func buildGroupStats(runs []*RunRecord) map[string]groupStat {
	type accumulator struct {
		total int64
		count int
		last  *RunRecord
	}
	acc := map[string]*accumulator{}
	for _, run := range runs {
		if run.CompletedAt == nil {
			continue
		}
		key := run.Task.GroupID()
		if acc[key] == nil {
			acc[key] = &accumulator{}
		}
		a := acc[key]
		a.total += run.DurationSeconds
		a.count++
		if a.last == nil || run.CompletedAt.After(*a.last.CompletedAt) {
			a.last = run
		}
	}
	result := map[string]groupStat{}
	for key, a := range acc {
		var average int64
		if a.count >= 2 {
			average = a.total / int64(a.count)
		}
		result[key] = groupStat{average: average, count: a.count, last: a.last.DurationSeconds, lastRun: a.last}
	}
	return result
}

func recentRuns(runs []*RunRecord, limit int) []*RunRecord {
	copyRuns := append([]*RunRecord(nil), runs...)
	sort.Slice(copyRuns, func(i, j int) bool {
		left, right := copyRuns[i].StartedAt, copyRuns[j].StartedAt
		return left.After(right)
	})
	if len(copyRuns) > limit {
		copyRuns = copyRuns[:limit]
	}
	return copyRuns
}

func buildGraph(current map[string]TaskView, deps []Dependency, stats map[string]groupStat) ([]GraphNode, []GraphEdge) {
	ids := map[string]bool{}
	for id := range current {
		ids[id] = true
	}
	var edges []GraphEdge
	for _, dep := range deps {
		ids[dep.FabID] = true
		ids[dep.DependencyID] = true
		edges = append(edges, GraphEdge{From: dep.DependencyID, To: dep.FabID})
	}
	var nodes []GraphNode
	for id := range ids {
		node := GraphNode{ID: id}
		if task, ok := current[id]; ok {
			node.Current = true
			node.Status = task.Status
			node.Description = task.FabDescription
			node.StartedAt = cloneTimePtr(task.StartedAt)
			node.AverageDurationSeconds = task.AverageDurationSeconds
		} else {
			// 依赖节点不一定在当前日期任务集合中；按 fab_id 汇总其最近历史平均值。
			var total int64
			var count int64
			for key, stat := range stats {
				if strings.HasSuffix(key, "|"+id) && stat.count > 0 {
					total += stat.average * int64(stat.count)
					count += int64(stat.count)
				}
			}
			if count > 0 {
				node.AverageDurationSeconds = total / count
			}
		}
		nodes = append(nodes, node)
	}
	sort.Slice(nodes, func(i, j int) bool { return nodes[i].ID < nodes[j].ID })
	return nodes, edges
}

func cloneTimePtr(value *time.Time) *time.Time {
	if value == nil {
		return nil
	}
	copy := *value
	return &copy
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
