# Java 8 交付文件

`TinyFabMonitor.jar` 是可直接运行的 Java 8 Fat JAR，已经包含 Oracle JDBC 和 JSON 运行依赖。

`TINY_FAB_MONITOR-Java8-Windows-x64.zip` 包含 Fat JAR、中文说明和可直接修改的示例 `config.properties`。

SHA-256：

```text
TinyFabMonitor.jar
80ac91d0bfe7b8063d9658eddfdd05f5c74bae5d89adf4ab75ed8a0bdd0a5a03

TINY_FAB_MONITOR-Java8-Windows-x64.zip
ab83fb178258ae544c0b3082176398a5f90b1009a20ccdfad7bc2190b652b4b2
```

下载 JAR 后，将 `java/config.example.properties` 复制为同目录的 `config.properties`，填写实际连接信息和表名，然后运行：

```bat
"C:\你的JRE目录\bin\java.exe" -jar TinyFabMonitor.jar
```

不要把含有真实账号、密码或内部数据的 `config.properties`、`data` 文件夹提交到公开仓库。
