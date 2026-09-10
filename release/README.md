# Java 8 交付文件

`TinyFabMonitor.jar` 是可直接运行的 Java 8 Fat JAR，已经包含 Oracle JDBC 和 JSON 运行依赖。

`TINY_FAB_MONITOR-Java8-Windows-x64.zip` 包含 Fat JAR、中文说明和可直接修改的示例 `config.properties`。

本版耗时分析采用纯 R 模式：明确输入分析日期和基准日期，只用指定结束作业的有效 R 完成时刻判断整体 delay。启动作业必须是 Level 40 且有真实 R；它只用于批次内部对齐和应完成时间，不改变整体结论。

任务偏移精确拆成“依赖就绪偏移 + 就绪后完成间隔差”，并递归显示实际决定就绪时刻的前置任务。未完成任务与普通依赖 DAG 共用纯 R ETA：显示 P50/P75、历史范围、置信度和 Checkpoint；超出历史范围后取消点预测并提示人工检查。ETA 不作为正式 delay 判定。

SHA-256：

```text
TinyFabMonitor.jar
4063e972462801f7c663f6cc5d1469ea77b82da04a80573d4e5a79e61ca3523c

TINY_FAB_MONITOR-Java8-Windows-x64.zip
8c877a64506556b2556aaea08390dc1eb50fa31f2f34d7168d2d881c54a8d8e4
```

下载 JAR 后，将 `java/config.example.properties` 复制为同目录的 `config.properties`，填写实际连接信息和表名，然后运行：

```bat
"C:\你的JRE目录\bin\java.exe" -jar TinyFabMonitor.jar
```

不要把含有真实账号、密码或内部数据的 `config.properties`、`data` 文件夹提交到公开仓库。
