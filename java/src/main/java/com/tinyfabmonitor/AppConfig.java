package com.tinyfabmonitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

final class AppConfig {
    private static final Pattern TABLE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_$#]*(\\.[A-Za-z][A-Za-z0-9_$#]*)?$");
    private static final Pattern DATE = Pattern.compile("^\\d{8}$");

    final Path baseDirectory;
    final Path storageDirectory;
    final String host;
    final int port;
    final String serviceName;
    final String username;
    final String password;
    final int connectTimeoutSeconds;
    final String scheduleTable;
    final String levelDescTable;
    final String fabPlanTable;
    final String dependencyTable;
    final String processDate;
    final int pollIntervalMinutes;
    final int levelMin;
    final int levelMax;

    private AppConfig(Path baseDirectory, Properties p) {
        this.baseDirectory = baseDirectory;
        host = required(p, "oracle.host");
        port = positiveInt(p, "oracle.port", 1521);
        serviceName = required(p, "oracle.service_name");
        username = required(p, "oracle.username");
        String passwordEnv = trim(p.getProperty("oracle.password_env"));
        String configuredPassword = trim(p.getProperty("oracle.password"));
        password = passwordEnv.isEmpty() ? configuredPassword : trim(System.getenv(passwordEnv));
        if (password.isEmpty()) throw new IllegalArgumentException("oracle.password 不能为空，或 password_env 指向的环境变量不存在");
        connectTimeoutSeconds = positiveInt(p, "oracle.connect_timeout_seconds", 15);
        scheduleTable = table(p, "tables.schedule", "IATFSC_FABSCHD");
        levelDescTable = table(p, "tables.level_desc", "IATLVL_LEVEL_DESC");
        fabPlanTable = table(p, "tables.fab_plan", "IATCFB_FABPLAN");
        dependencyTable = table(p, "tables.fab_dependency", "IATCFB_FABDEPN");
        processDate = trim(p.getProperty("monitor.process_date"));
        if (!DATE.matcher(processDate).matches()) throw new IllegalArgumentException("monitor.process_date 必须是 YYYYMMDD 格式");
        pollIntervalMinutes = positiveInt(p, "monitor.poll_interval_minutes", 5);
        levelMin = integer(p, "monitor.level_min", 41);
        levelMax = integer(p, "monitor.level_max", 69);
        if (levelMin > levelMax) throw new IllegalArgumentException("monitor.level_min 不能大于 monitor.level_max");
        String storage = trim(p.getProperty("storage.directory"));
        if (storage.isEmpty()) storage = "data";
        Path configured = java.nio.file.Paths.get(storage);
        storageDirectory = configured.isAbsolute() ? configured.normalize() : baseDirectory.resolve(configured).normalize();
    }

    static AppConfig load(Path path, Path baseDirectory) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("找不到 " + path + "，请将 config.example.properties 复制为 config.properties 并填写数据库连接信息");
        }
        Properties properties = new Properties();
        InputStream in = Files.newInputStream(path);
        try { properties.load(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)); } finally { in.close(); }
        return new AppConfig(baseDirectory, properties);
    }

    private static String required(Properties p, String key) {
        String value = trim(p.getProperty(key));
        if (value.isEmpty()) throw new IllegalArgumentException(key + " 不能为空");
        return value;
    }

    private static String table(Properties p, String key, String fallback) {
        String value = trim(p.getProperty(key));
        if (value.isEmpty()) value = fallback;
        if (!TABLE_NAME.matcher(value).matches()) throw new IllegalArgumentException(key + " 只允许 TABLE 或 SCHEMA.TABLE 格式");
        return value;
    }

    private static int positiveInt(Properties p, String key, int fallback) {
        int value = integer(p, key, fallback);
        if (value <= 0) throw new IllegalArgumentException(key + " 必须大于 0");
        return value;
    }

    private static int integer(Properties p, String key, int fallback) {
        String value = trim(p.getProperty(key));
        if (value.isEmpty()) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 必须是整数"); }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
