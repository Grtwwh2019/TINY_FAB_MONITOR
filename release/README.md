# Java 8 交付文件

`TinyFabMonitor.jar` 是可直接运行的 Java 8 Fat JAR，已经包含 Oracle JDBC 和 JSON 运行依赖。

`TINY_FAB_MONITOR-Java8-Windows-x64.zip` 包含 Fat JAR、中文说明和可直接修改的示例 `config.properties`。

本版耗时分析只用指定结束作业在各自业务日期中的 R 完成时刻判断整体 delay；启动作业真实 R 用于有效性校验、启动对齐应完成时间、ETA 和慢点分析，但不会改变整体结论。

SHA-256：

```text
TinyFabMonitor.jar
53ee30d57c632ac503b2cc0959d197cc387394cfb64c975eceef74f853d01491

TINY_FAB_MONITOR-Java8-Windows-x64.zip
2c44ba0491431e406db7995ee21423c7ef4c0c993c5eb54331cb2f7e614b9f7d
```

下载 JAR 后，将 `java/config.example.properties` 复制为同目录的 `config.properties`，填写实际连接信息和表名，然后运行：

```bat
"C:\你的JRE目录\bin\java.exe" -jar TinyFabMonitor.jar
```

不要把含有真实账号、密码或内部数据的 `config.properties`、`data` 文件夹提交到公开仓库。
