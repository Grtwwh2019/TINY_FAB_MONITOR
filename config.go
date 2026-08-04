package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
)

type Config struct {
	Oracle  OracleConfig  `json:"oracle"`
	Tables  TableConfig   `json:"tables"`
	Monitor MonitorConfig `json:"monitor"`
	Server  ServerConfig  `json:"server"`
	Storage StorageConfig `json:"storage"`
}

type TableConfig struct {
	Schedule      string `json:"schedule"`
	LevelDesc     string `json:"level_desc"`
	FabPlan       string `json:"fab_plan"`
	FabDependency string `json:"fab_dependency"`
}

type OracleConfig struct {
	Host                  string `json:"host"`
	Port                  int    `json:"port"`
	ServiceName           string `json:"service_name"`
	Username              string `json:"username"`
	Password              string `json:"password"`
	PasswordEnv           string `json:"password_env,omitempty"`
	ConnectTimeoutSeconds int    `json:"connect_timeout_seconds"`
}

type MonitorConfig struct {
	ProcessDate         string `json:"process_date"`
	PollIntervalMinutes int    `json:"poll_interval_minutes"`
	LevelMin            int    `json:"level_min"`
	LevelMax            int    `json:"level_max"`
}

type ServerConfig struct {
	Listen      string `json:"listen"`
	OpenBrowser bool   `json:"open_browser"`
}

type StorageConfig struct {
	Directory string `json:"directory"`
}

func LoadConfig(path, baseDir string) (Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return Config{}, fmt.Errorf("找不到 %s，请将 config.example.json 复制为 config.json 并填写数据库连接信息", path)
		}
		return Config{}, err
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return Config{}, fmt.Errorf("JSON 格式无效：%w", err)
	}
	if cfg.Oracle.PasswordEnv != "" {
		cfg.Oracle.Password = os.Getenv(cfg.Oracle.PasswordEnv)
	}
	if cfg.Oracle.Port == 0 {
		cfg.Oracle.Port = 1521
	}
	if cfg.Tables.Schedule == "" {
		cfg.Tables.Schedule = "IATFSC_FABSCHD"
	}
	if cfg.Tables.LevelDesc == "" {
		cfg.Tables.LevelDesc = "IATLVL_LEVEL_DESC"
	}
	if cfg.Tables.FabPlan == "" {
		cfg.Tables.FabPlan = "IATCFB_FABPLAN"
	}
	if cfg.Tables.FabDependency == "" {
		cfg.Tables.FabDependency = "IATCFB_FABDEPN"
	}
	if cfg.Oracle.ConnectTimeoutSeconds <= 0 {
		cfg.Oracle.ConnectTimeoutSeconds = 15
	}
	if cfg.Monitor.PollIntervalMinutes <= 0 {
		cfg.Monitor.PollIntervalMinutes = 5
	}
	if cfg.Monitor.LevelMin == 0 {
		cfg.Monitor.LevelMin = 41
	}
	if cfg.Monitor.LevelMax == 0 {
		cfg.Monitor.LevelMax = 69
	}
	if cfg.Server.Listen == "" {
		cfg.Server.Listen = "127.0.0.1:8765"
	}
	if cfg.Storage.Directory == "" {
		cfg.Storage.Directory = "data"
	}
	if !filepath.IsAbs(cfg.Storage.Directory) {
		cfg.Storage.Directory = filepath.Join(baseDir, cfg.Storage.Directory)
	}
	if cfg.Oracle.Host == "" || cfg.Oracle.ServiceName == "" || cfg.Oracle.Username == "" || cfg.Oracle.Password == "" {
		return Config{}, fmt.Errorf("oracle.host、service_name、username 和 password 均不能为空")
	}
	identifierPattern := regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_$#]*(\.[A-Za-z][A-Za-z0-9_$#]*)?$`)
	tables := map[string]string{
		"tables.schedule":       cfg.Tables.Schedule,
		"tables.level_desc":     cfg.Tables.LevelDesc,
		"tables.fab_plan":       cfg.Tables.FabPlan,
		"tables.fab_dependency": cfg.Tables.FabDependency,
	}
	for field, tableName := range tables {
		if !identifierPattern.MatchString(tableName) {
			return Config{}, fmt.Errorf("%s 不是有效表名；只允许 TABLE 或 SCHEMA.TABLE 格式", field)
		}
	}
	if !regexp.MustCompile(`^\d{8}$`).MatchString(cfg.Monitor.ProcessDate) {
		return Config{}, fmt.Errorf("monitor.process_date 必须是 YYYYMMDD 格式")
	}
	if cfg.Monitor.LevelMin > cfg.Monitor.LevelMax {
		return Config{}, fmt.Errorf("monitor.level_min 不能大于 level_max")
	}
	return cfg, nil
}
