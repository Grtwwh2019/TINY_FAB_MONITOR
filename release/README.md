# Java 8 交付文件

`TinyFabMonitor.jar` 是可直接运行的 Java 8 Fat JAR，已经包含 Oracle JDBC 和 JSON 运行依赖。

`TINY_FAB_MONITOR-Java8-Windows-x64.zip` 包含 Fat JAR、中文说明和可直接修改的示例 `config.properties`。

本版耗时分析采用纯 R 模式：明确输入分析日期和基准日期，只用指定结束作业的有效 R 完成时刻判断整体 delay。启动作业真实 R 只用于批次内部对齐和应完成时间，不改变整体结论。

任务偏移精确拆成“依赖就绪偏移 + 就绪后完成间隔差”，并递归显示实际决定就绪时刻的前置任务。该页面不读取或推算 I、不预测未完成任务 ETA，也不自动认定执行、等待或同 Thread 阻塞原因；发现偏移后提供人工核对方向。

SHA-256：

```text
TinyFabMonitor.jar
a81cc90ae5ba30f9bc6ec0f9e2d3c702fbdca348337d922a986ebbe464f866ea

TINY_FAB_MONITOR-Java8-Windows-x64.zip
cd2d181e9b91515ceb7e63592ded671561010fe041a3c4ad9e33523df6011b52
```

下载 JAR 后，将 `java/config.example.properties` 复制为同目录的 `config.properties`，填写实际连接信息和表名，然后运行：

```bat
"C:\你的JRE目录\bin\java.exe" -jar TinyFabMonitor.jar
```

不要把含有真实账号、密码或内部数据的 `config.properties`、`data` 文件夹提交到公开仓库。
