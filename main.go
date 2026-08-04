package main

import (
	"context"
	"embed"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

//go:embed web/*
var webAssets embed.FS

func main() {
	configPath := flag.String("config", "", "配置文件路径（默认：程序目录/config.json）")
	flag.Parse()

	exePath, err := os.Executable()
	if err != nil {
		log.Fatal(err)
	}
	baseDir := filepath.Dir(exePath)
	if *configPath == "" {
		*configPath = filepath.Join(baseDir, "config.json")
	}

	cfg, err := LoadConfig(*configPath, baseDir)
	if err != nil {
		showFatal("配置错误", err)
	}
	if err := os.MkdirAll(cfg.Storage.Directory, 0o755); err != nil {
		showFatal("无法创建数据目录", err)
	}

	logPath := filepath.Join(cfg.Storage.Directory, "oracle-fab-monitor.log")
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		showFatal("无法创建日志文件", err)
	}
	defer logFile.Close()
	logger := log.New(logFile, "", log.Ldate|log.Ltime|log.Lmicroseconds)

	store, err := NewFileStore(filepath.Join(cfg.Storage.Directory, "state.json"))
	if err != nil {
		showFatal("无法读取本地历史数据", err)
	}
	repo, err := NewOracleRepository(cfg.Oracle, cfg.Tables)
	if err != nil {
		showFatal("Oracle 配置错误", err)
	}
	defer repo.Close()

	monitor := NewMonitor(cfg, repo, store, logger)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go monitor.Run(ctx)

	webRoot, err := fs.Sub(webAssets, "web")
	if err != nil {
		showFatal("无法载入网页资源", err)
	}
	server := NewWebServer(cfg, monitor, webRoot, logger)
	url := "http://" + cfg.Server.Listen
	if strings.HasPrefix(cfg.Server.Listen, "0.0.0.0:") {
		url = "http://127.0.0.1:" + strings.TrimPrefix(cfg.Server.Listen, "0.0.0.0:")
	}

	logger.Printf("服务启动：%s", url)
	fmt.Printf("Oracle FAB 运行监控已启动：%s\n", url)
	fmt.Printf("数据目录：%s\n", cfg.Storage.Directory)

	httpServer := &http.Server{
		Addr:              cfg.Server.Listen,
		Handler:           server.Routes(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	if err := httpServer.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		showFatal("服务启动失败", err)
	}
}

func showFatal(title string, err error) {
	message := fmt.Sprintf("%s：%v", title, err)
	fmt.Fprintln(os.Stderr, message)
	errorPath := "startup-error.txt"
	if exePath, executableErr := os.Executable(); executableErr == nil {
		errorPath = filepath.Join(filepath.Dir(exePath), errorPath)
	}
	content := fmt.Sprintf("%s\r\n%s\r\n", time.Now().Format("2006-01-02 15:04:05"), message)
	_ = os.WriteFile(errorPath, []byte(content), 0o600)
	os.Exit(1)
}
