package main

import (
	"context"
	"database/sql"
	"fmt"
	"strconv"
	"strings"
	"time"

	go_ora "github.com/sijms/go-ora/v2"
)

const taskQueryTemplate = `
select p.prcss_dt,
       p.thread_id,
       p.lvl_no,
       p.fab_id,
       p.stat_cde,
       p.act_tm,
       (select ldesc.descr
          from %s ldesc
         where ldesc.thread_id = p.thread_id
           and ldesc.lvl_no = p.lvl_no) as Ldes,
       (select fplan.descr
          from %s fplan
         where fplan.fab_id = p.fab_id
           and fplan.thread_id = p.thread_id) as fdesc
  from %s p
 where p.prcss_dt = to_date(:1, 'yyyymmdd')
   and p.lvl_no between :2 and :3
 order by 1, 2, 3, 4, 5`

const dependencyQueryTemplate = `
select fab_id, depn_id
  from %s
 where fab_id = :1`

type OracleRepository struct {
	db              *sql.DB
	taskQuery       string
	dependencyQuery string
}

func NewOracleRepository(cfg OracleConfig, tables TableConfig) (*OracleRepository, error) {
	options := map[string]string{
		"CONNECTION TIMEOUT": strconv.Itoa(cfg.ConnectTimeoutSeconds),
	}
	dsn := go_ora.BuildUrl(cfg.Host, cfg.Port, cfg.ServiceName, cfg.Username, cfg.Password, options)
	db, err := sql.Open("oracle", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(1)
	db.SetConnMaxLifetime(30 * time.Minute)
	return &OracleRepository{
		db:              db,
		taskQuery:       fmt.Sprintf(taskQueryTemplate, tables.LevelDesc, tables.FabPlan, tables.Schedule),
		dependencyQuery: fmt.Sprintf(dependencyQueryTemplate, tables.FabDependency),
	}, nil
}

func (r *OracleRepository) Close() error { return r.db.Close() }

func (r *OracleRepository) FetchTasks(ctx context.Context, processDate string, levelMin, levelMax int) ([]OracleTask, error) {
	rows, err := r.db.QueryContext(ctx, r.taskQuery, processDate, levelMin, levelMax)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var result []OracleTask
	for rows.Next() {
		var processDateValue any
		var threadID, levelNo, fabID, status sql.NullString
		var actTime time.Time
		var levelDesc, fabDesc sql.NullString
		if err := rows.Scan(&processDateValue, &threadID, &levelNo, &fabID, &status, &actTime, &levelDesc, &fabDesc); err != nil {
			return nil, err
		}
		dateText := normalizeDate(processDateValue)
		result = append(result, OracleTask{
			TaskKey: TaskKey{
				ProcessDate: dateText,
				ThreadID:    strings.TrimSpace(threadID.String),
				LevelNo:     strings.TrimSpace(levelNo.String),
				FabID:       strings.TrimSpace(fabID.String),
			},
			Status:           strings.ToUpper(strings.TrimSpace(status.String)),
			ActTime:          actTime,
			LevelDescription: levelDesc.String,
			FabDescription:   fabDesc.String,
		})
	}
	return result, rows.Err()
}

func normalizeDate(value any) string {
	switch v := value.(type) {
	case time.Time:
		return v.Format("20060102")
	case string:
		s := strings.TrimSpace(v)
		if len(s) >= 10 {
			for _, layout := range []string{"2006-01-02", "02-JAN-06", "02-JAN-2006"} {
				if parsed, err := time.Parse(layout, strings.ToUpper(s[:min(len(s), len(layout))])); err == nil {
					return parsed.Format("20060102")
				}
			}
		}
		return s
	case []byte:
		return strings.TrimSpace(string(v))
	default:
		return strings.TrimSpace(fmt.Sprint(v))
	}
}

func (r *OracleRepository) FetchDependencyGraph(ctx context.Context, rootFabIDs []string) ([]Dependency, error) {
	seen := map[string]bool{}
	queued := map[string]bool{}
	queue := append([]string(nil), rootFabIDs...)
	for _, id := range queue {
		queued[id] = true
	}
	var result []Dependency

	for len(queue) > 0 {
		fabID := queue[0]
		queue = queue[1:]
		if seen[fabID] {
			continue
		}
		seen[fabID] = true

		rows, err := r.db.QueryContext(ctx, r.dependencyQuery, fabID)
		if err != nil {
			return nil, fmt.Errorf("读取 %s 的依赖失败：%w", fabID, err)
		}
		for rows.Next() {
			var owner, dep sql.NullString
			if err := rows.Scan(&owner, &dep); err != nil {
				rows.Close()
				return nil, err
			}
			ownerID := strings.TrimSpace(owner.String)
			depID := strings.TrimSpace(dep.String)
			if ownerID == "" || depID == "" {
				continue
			}
			result = append(result, Dependency{FabID: ownerID, DependencyID: depID})
			if !seen[depID] && !queued[depID] {
				queue = append(queue, depID)
				queued[depID] = true
			}
		}
		if err := rows.Err(); err != nil {
			rows.Close()
			return nil, err
		}
		rows.Close()
	}
	return result, nil
}
