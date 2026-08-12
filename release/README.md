# Java 8 交付文件

`TinyFabMonitor.jar` 是可直接运行的 Java 8 Fat JAR，已经包含 Oracle JDBC 和 JSON 运行依赖。

`TINY_FAB_MONITOR-Java8-Windows-x64.zip` 包含 Fat JAR、中文说明和可直接修改的示例 `config.properties`。

SHA-256：

```text
TinyFabMonitor.jar
eb600c20ec1d432c242a365e19192f3e4502ab74f5201e4a0b04017f92e1f21f

TINY_FAB_MONITOR-Java8-Windows-x64.zip
bd9eacc56e289a7af045169b57302da018c2bfcb1055b9f513b1782a6cc3472d
```

下载 JAR 后，将 `java/config.example.properties` 复制为同目录的 `config.properties`，填写实际连接信息和表名，然后运行：

```bat
"C:\你的JRE目录\bin\java.exe" -jar TinyFabMonitor.jar
```

不要把含有真实账号、密码或内部数据的 `config.properties`、`data` 文件夹提交到公开仓库。
