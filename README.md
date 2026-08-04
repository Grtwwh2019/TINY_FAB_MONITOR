# TINY FAB MONITOR

一个面向 Windows 10/11 x64 的轻量级 Oracle FAB 状态监控工具。它定时读取任务状态，记录 `I`、`E`、`B`、`R` 状态事件，计算运行时长和跨日期历史平均值，并生成 FAB 依赖 DAG。

## 主要功能

- Oracle 连接、业务表名、业务日期和轮询间隔均通过外部 JSON 配置。
- 默认每 5 分钟读取一次任务状态。
- 使用 `prcss_dt + thread_id + lvl_no + fab_id` 唯一标识一次任务。
- 捕获 `I` 时记录开始时间，捕获最终 `R` 时计算持续时长。
- 捕获 `E → I` 或 `E → B → I` 后，使用新的 `I.act_tm` 刷新运行开始时间。
- 单独保存捕获到的 `E` 异常时间点。
- 按 `thread_id + lvl_no + fab_id` 跨业务日期计算历史平均时长，第 2 次完成后开始展示平均值。
- 递归读取 FAB 依赖关系并生成可缩放、拖动的 DAG。
- 本地持久化运行历史、当天运行状态和异常记录，程序重启后继续使用。
- 内置中文网页界面，不需要安装 Node.js、Python 或 Oracle Instant Client。

## 企业兼容行为

企业兼容版不会自动启动浏览器，也不会调用 PowerShell、`rundll32` 或其他外部程序。启动后请手动访问：

```text
http://127.0.0.1:8765
```

如果配置无效或服务无法启动，程序会在 EXE 同目录生成 `startup-error.txt`。

## 配置

复制 `config.example.json` 为 `config.json`，然后填写数据库连接信息：

```json
{
  "oracle": {
    "host": "127.0.0.1",
    "port": 1521,
    "service_name": "ORCL",
    "username": "your_username",
    "password": "your_password",
    "connect_timeout_seconds": 15
  },
  "tables": {
    "schedule": "IATFSC_FABSCHD",
    "level_desc": "IATLVL_LEVEL_DESC",
    "fab_plan": "IATCFB_FABPLAN",
    "fab_dependency": "IATCFB_FABDEPN"
  },
  "monitor": {
    "process_date": "20251231",
    "poll_interval_minutes": 5,
    "level_min": 41,
    "level_max": 69
  },
  "server": {
    "listen": "127.0.0.1:8765",
    "open_browser": false
  },
  "storage": {
    "directory": "data"
  }
}
```

表名支持 `TABLE` 或 `SCHEMA.TABLE` 格式。日期、Level 和 FAB 等业务参数使用 Oracle 绑定变量。

生产环境建议使用只读 Oracle 账号，并通过 `password_env` 引用环境变量，避免把密码写入配置文件：

```json
"password": "",
"password_env": "ORACLE_FAB_PASSWORD"
```

## 本地构建

需要 Go 1.22 或更高版本：

```bash
go test .
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -H=windowsgui" -o OracleFabMonitor-Enterprise.exe .
```

最终 EXE 使用纯 Go Oracle 驱动，不依赖 Oracle Client。

## 数据文件

- `data/state.json`：运行历史、跟踪状态及异常时间点。
- `data/oracle-fab-monitor.log`：数据库连接和轮询日志。
- `startup-error.txt`：仅在启动失败时生成。

这些文件可能包含内部业务信息，已通过 `.gitignore` 排除，不应提交到公共仓库。

## 准确性说明

程序按配置间隔读取当前状态。两次轮询之间快速出现并结束的状态可能无法捕获；状态时间以数据库中的 `act_tm` 为准。

