# TINY FAB MONITOR Java 8 版

这是不启动网页服务的 Java Swing 桌面版本，适用于 Windows 10/11 x64。最终 Fat JAR 已包含 Oracle JDBC 和 JSON 持久化依赖，运行电脑只需准备 64 位 JRE 8。

## 运行

将构建得到的 `TinyFabMonitor.jar`、复制并改名后的 `config.properties` 放在同一目录。首次运行建议使用命令提示符，以便看到错误：

```bat
"C:\你的JRE目录\bin\java.exe" -jar TinyFabMonitor.jar
```

确认正常后可使用无命令窗口的方式：

```bat
"C:\你的JRE目录\bin\javaw.exe" -jar TinyFabMonitor.jar
```

## 配置

复制 `config.example.properties` 为 `config.properties`，填写 Oracle 地址、端口、服务名、账号、密码、业务日期和四个业务表名。示例中的表名均为公开占位名，四项表名必须替换为实际值；支持 `TABLE` 或 `SCHEMA.TABLE` 格式。

如不希望把密码写入配置文件，可在 `oracle.password_env` 中填写 Windows 环境变量名。

## 数据和安全行为

- 启动后立即查询一次，此后默认每 5 分钟查询一次。
- 仅程序窗口保持打开时定时运行。
- 数据保存在 `data/state.json`，日志保存在 `data/oracle-fab-monitor-java.log`。
- 兼容原 Go/EXE 版的 `state.json`。
- 不创建或修改 Windows 服务、注册表、计划任务、防火墙规则或开机启动项。
- 不调用 PowerShell、`rundll32`、浏览器或其他外部程序。
- 不启动本地网页服务器，不监听端口。

## 当前任务与 DAG

- “任务状态”显示当前业务日期查询到的全部状态，不仅限于 `R`。
- Oracle 查询只按业务日期过滤，不在 SQL 中限制 Level No。
- 任务表可以输入 Level No 起止区间；只填一端表示单边范围，起止值均包含在结果中。
- FAB ID 和 FAB 描述使用独立列；Thread ID 输入框使用忽略大小写的包含匹配。
- Thread、Level、状态和关键字筛选只影响任务表。
- DAG 默认不查询、不显示节点。DAG 页面提供 Thread ID、Level No、FAB ID 三个输入框，点击“搜索定位”后才按需加载目标 FAB 的依赖图。
- 如果搜索条件匹配多个任务，程序会先显示候选列表供选择。
- 依赖图向上递归查询所有前置依赖，向下递归查询所有后续 FAB，上下游各最多 15 层；非当前业务日期的任务不会显示，也不会继续穿过它递归。
- 状态不是 `R` 的节点高亮，`E` 使用异常色。节点悬浮提示显示 FAB 描述、Thread、Level、状态时间、I 开始时间、本次/当前时长和历史平均。
- 右键任意已显示节点可选择“展示此 FAB 的依赖 DAG”，将它作为新的中心重新查询。
- 任务页显示全部唯一任务，可按 `W/I/E/B/R` 状态和关键字组合筛选。
- DAG 支持输入 FAB ID 搜索，自动将对应节点移动到画布中心并高亮。

## 构建

开发电脑需要 JDK 8 和 Maven：

```bash
cd java
mvn clean package
```

生成文件为 `java/target/TinyFabMonitor.jar`。Oracle JDBC、Jackson 等运行依赖会由 Maven Shade 合并进该 JAR。

## 准确性

程序以数据库的 `act_tm` 为准。轮询间隔之间快速出现并结束的状态可能无法捕获。捕获 `E → I` 或 `E → B → I` 时，会以新的 `I.act_tm` 刷新运行开始时间。

`act_tm` 同时兼容 Oracle `DATE/TIMESTAMP`，以及形如 `2026-08-01-19.18.09.582000` 的 `CHAR` 字段。CHAR 中超过毫秒的精度会安全截断到毫秒，而界面和持续时长仍按秒展示。

数据库中的 `0001-01-01-00.00.00.000000` 被视为“无有效时间”占位值：任务和状态仍显示，但该值不参与 `I/E/R` 时间记录、持续时长或平均值计算。程序也会自动兼容旧 `state.json` 中由时区换算产生的 `+0000-...`，修复前在数据目录创建 `state.json.before-placeholder-repair.bak`。
