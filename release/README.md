# Java 8 交付文件

`TinyFabMonitor.jar` 是可直接运行的 Java 8 Fat JAR，已经包含 Oracle JDBC 和 JSON 运行依赖。

`TINY_FAB_MONITOR-Java8-Windows-x64.zip` 包含 Fat JAR、中文说明和可直接修改的示例 `config.properties`。

本版耗时分析会在所有比较日期之间统一使用启动作业 I；任意日期缺少真实 I 时全组统一切换为启动作业 R，并以“实际/预计完成时间 − 当天应完成时间”判断批次延迟。

SHA-256：

```text
TinyFabMonitor.jar
dfcf87ccec9464890d25ffc11a5f2872a0527361fd24a4059a33fff1b98d1e14

TINY_FAB_MONITOR-Java8-Windows-x64.zip
5ff75527cd0cd267e46e560a05fc701dac69ab40a0777a597814b794569af1ca
```

下载 JAR 后，将 `java/config.example.properties` 复制为同目录的 `config.properties`，填写实际连接信息和表名，然后运行：

```bat
"C:\你的JRE目录\bin\java.exe" -jar TinyFabMonitor.jar
```

不要把含有真实账号、密码或内部数据的 `config.properties`、`data` 文件夹提交到公开仓库。
