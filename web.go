package main

import (
	"encoding/json"
	"io/fs"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

type WebServer struct {
	cfg     Config
	monitor *Monitor
	assets  fs.FS
	logger  *log.Logger
}

func NewWebServer(cfg Config, monitor *Monitor, assets fs.FS, logger *log.Logger) *WebServer {
	return &WebServer{cfg: cfg, monitor: monitor, assets: assets, logger: logger}
}

func (s *WebServer) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/dashboard", s.dashboard)
	mux.HandleFunc("POST /api/refresh", s.refresh)
	mux.HandleFunc("POST /api/process-date", s.processDate)
	mux.HandleFunc("POST /api/shutdown", s.shutdown)
	mux.Handle("/", http.FileServer(http.FS(s.assets)))
	return securityHeaders(mux)
}

func (s *WebServer) shutdown(w http.ResponseWriter, r *http.Request) {
	origin := r.Header.Get("Origin")
	if origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "拒绝跨站关闭请求"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"message": "程序正在退出"})
	s.logger.Printf("用户从页面关闭程序")
	go func() {
		time.Sleep(300 * time.Millisecond)
		os.Exit(0)
	}()
}

func (s *WebServer) dashboard(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, s.monitor.Dashboard())
}

func (s *WebServer) refresh(w http.ResponseWriter, _ *http.Request) {
	s.monitor.TriggerRefresh()
	writeJSON(w, http.StatusAccepted, map[string]string{"message": "已开始刷新"})
}

func (s *WebServer) processDate(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ProcessDate string `json:"process_date"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024))
	if err := decoder.Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求格式无效"})
		return
	}
	body.ProcessDate = strings.TrimSpace(body.ProcessDate)
	if err := s.monitor.SetProcessDate(body.ProcessDate); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]string{"message": "日期已更新，正在刷新"})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; img-src 'self' data:")
		next.ServeHTTP(w, r)
	})
}
