# Java 8 交付文件

`TinyFabMonitor.jar` 是可直接运行的 Java 8 Fat JAR，已经包含 Oracle JDBC 和 JSON 运行依赖。

`TINY_FAB_MONITOR-Java8-Windows-x64.zip` 包含 Fat JAR、中文说明和可直接修改的示例 `config.properties`。

SHA-256：

```text
TinyFabMonitor.jar
ee007bb07cc8c6aa6e7ef7c8ba30013bb02d2176cb93f28727f3b9dea60e45d6

TINY_FAB_MONITOR-Java8-Windows-x64.zip
6ad38adbcef3a21434871ed25062d4d1bf4c0f11a7149a866a8903c80a1ed00f
```

下载 JAR 后，将 `java/config.example.properties` 复制为同目录的 `config.properties`，填写实际连接信息和表名，然后运行：

```bat
"C:\你的JRE目录\bin\java.exe" -jar TinyFabMonitor.jar
```

不要把含有真实账号、密码或内部数据的 `config.properties`、`data` 文件夹提交到公开仓库。
