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

复制 `config.example.properties` 为 `config.properties`，填写 Oracle 地址、端口、服务名、账号、密码、业务日期和四个业务表名。表名支持 `TABLE` 或 `SCHEMA.TABLE` 格式。

如不希望把密码写入配置文件，可在 `oracle.password_env` 中填写 Windows 环境变量名。

## 数据和安全行为

- 启动后立即查询一次，此后默认每 5 分钟查询一次。
- 仅程序窗口保持打开时定时运行。
- 数据保存在 `data/state.json`，日志保存在 `data/oracle-fab-monitor-java.log`。
- 兼容原 Go/EXE 版的 `state.json`。
- 不创建或修改 Windows 服务、注册表、计划任务、防火墙规则或开机启动项。
- 不调用 PowerShell、`rundll32`、浏览器或其他外部程序。
- 不启动本地网页服务器，不监听端口。

## 构建

开发电脑需要 JDK 8 和 Maven：

```bash
cd java
mvn clean package
```

生成文件为 `java/target/TinyFabMonitor.jar`。Oracle JDBC、Jackson 等运行依赖会由 Maven Shade 合并进该 JAR。

## 准确性

程序以数据库的 `act_tm` 为准。轮询间隔之间快速出现并结束的状态可能无法捕获。捕获 `E → I` 或 `E → B → I` 时，会以新的 `I.act_tm` 刷新运行开始时间。
