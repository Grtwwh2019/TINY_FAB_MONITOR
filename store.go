package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type FileStore struct {
	mu    sync.Mutex
	path  string
	state PersistedState
}

func NewFileStore(path string) (*FileStore, error) {
	s := &FileStore{path: path}
	s.state = PersistedState{Version: 2, Tracked: map[string]*TrackedTask{}, Runs: []*RunRecord{}}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return s, nil
		}
		return nil, err
	}
	if err := json.Unmarshal(data, &s.state); err != nil {
		return nil, fmt.Errorf("解析 %s 失败：%w", path, err)
	}
	if s.state.Tracked == nil {
		s.state.Tracked = map[string]*TrackedTask{}
	}
	if s.state.Runs == nil {
		s.state.Runs = []*RunRecord{}
	}
	if s.state.Version < 2 {
		migrateStateV2(&s.state)
		if err := s.persist(s.state); err != nil {
			return nil, fmt.Errorf("升级历史数据失败：%w", err)
		}
	}
	return s, nil
}

func (s *FileStore) Snapshot() PersistedState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return cloneState(s.state)
}

func (s *FileStore) Update(fn func(*PersistedState) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	working := cloneState(s.state)
	if err := fn(&working); err != nil {
		return err
	}
	working.UpdatedAt = time.Now()
	if err := s.persist(working); err != nil {
		return err
	}
	s.state = working
	return nil
}

func (s *FileStore) persist(state PersistedState) error {
	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func migrateStateV2(state *PersistedState) {
	for _, run := range state.Runs {
		start := run.StartedAt
		seenAnomaly := false
		for _, event := range run.Events {
			switch event.Status {
			case "E":
				seenAnomaly = true
			case "I":
				if seenAnomaly {
					start = event.At
					seenAnomaly = false
				}
			}
		}
		run.StartedAt = start
		if run.CompletedAt != nil {
			run.DurationSeconds = max64(0, int64(run.CompletedAt.Sub(start).Seconds()))
		}
	}
	state.Version = 2
}

func cloneState(in PersistedState) PersistedState {
	data, _ := json.Marshal(in)
	var out PersistedState
	_ = json.Unmarshal(data, &out)
	if out.Tracked == nil {
		out.Tracked = map[string]*TrackedTask{}
	}
	if out.Runs == nil {
		out.Runs = []*RunRecord{}
	}
	return out
}
