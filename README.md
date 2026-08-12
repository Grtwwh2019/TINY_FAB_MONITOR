# TINY FAB MONITOR

面向 Windows 10/11 x64 的 Oracle FAB 状态监控工具。当前交付版本是 Java 8 Swing 桌面程序，不启动网页服务、不监听端口，也不会创建 Windows 服务或修改注册表。

## 直接运行

仓库内提供已经包含 Oracle JDBC 和 JSON 依赖的 Fat JAR：

- [`release/TinyFabMonitor.jar`](release/TinyFabMonitor.jar)
- [JAR 校验值与使用说明](release/README.md)

运行电脑只需准备 64 位 JRE 8：

```bat
"C:\你的JRE目录\bin\java.exe" -jar TinyFabMonitor.jar
```

首次使用时，将 [`java/config.example.properties`](java/config.example.properties) 复制为 `config.properties`，与 JAR 放在同一目录，然后填写 Oracle 连接、四个实际业务表名和业务日期。公开示例中的表名均为占位名。

## 主要功能

- 默认每5分钟按业务日期读取 Oracle 任务状态。
- 使用 `prcss_dt + thread_id + lvl_no + fab_id` 唯一标识任务。
- 捕获 `I → R` 并计算时长；遇到 `E → I` 或 `E → B → I` 时刷新 I 开始时间。
- 持久化运行历史、异常时间和 FAB 描述，支持手动清理旧数据及24小时清理备份。
- 任务页支持状态、Thread ID、Level No区间和关键字筛选。
- 按需递归查询上下游依赖并绘制 DAG，可设置画面层数和隐藏 R 节点。
- 为中心 FAB 计算预计完成时间：完整上游不受画面层数限制，按有效 R/I 时间锚点和历史平均计算最晚关键路径。
- 兼容 Oracle `CHAR` 格式时间及 `0001-01-01-00.00.00.000000` 占位时间。

只要有1次完整的 `I → R` 记录即可形成历史平均；多次记录使用算术平均。详细规则、配置和安全行为见 [`java/README-ZH.md`](java/README-ZH.md)。

## 本地构建

开发电脑需要 JDK 8 和 Maven：

```bash
cd java
mvn clean package
```

生成文件为 `java/target/TinyFabMonitor.jar`。

## 数据安全

- 生产环境建议使用只读 Oracle 账号，并通过 `oracle.password_env` 引用 Windows 环境变量。
- `config.properties`、`data/state.json`、日志和其他运行数据不应提交到公开仓库。
- 程序不调用 PowerShell、`rundll32`、浏览器或其他外部程序。
- 程序不创建或修改 Windows 服务、计划任务、注册表、防火墙规则或开机启动项。

程序按轮询间隔读取当前状态，两次轮询之间快速出现并结束的状态可能无法捕获；时间以数据库的 `act_tm` 为准。
